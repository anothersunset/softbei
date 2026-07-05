package com.zhiqian.ops.inspect;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主动巡检服务：周期性/按需对系统做只读体检，按阈值规则生成风险预警与健康评分。
 * 关键安全约束：全程仅调用只读命令(runReadOnly)，绝不触发任何变更；
 * 不绕过安全护栏——仅提供「主动发现 + 处置建议」，真正的处置仍需走主链路的护栏与人工确认。
 * 错误日志采集带时间戳与类型分类(OOM/IO/DISK_FULL)，供跨源根因分析做时间窗口关联。
 */
@Service
public class InspectionService {
    /** 采样历史上限：24h × 5min 间隔 = 288 个点。 */
    private static final int MAX_SAMPLES = 288;
    private static final long SAMPLE_TTL_MS = 24 * 60 * 60 * 1000L;

    private final LeastPrivilegeExecutor executor;
    private final InspectionProperties props;
    private final OpsAuditService audit;
    /** 预测性感知：本进程内的巡检采样历史（磁盘/内存使用率）。 */
    private final java.util.Deque<Sample> history = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public InspectionService(LeastPrivilegeExecutor executor, InspectionProperties props, OpsAuditService audit) {
        this.executor = executor;
        this.props = props;
        this.audit = audit;
    }

    public InspectionReport inspect() {
        long start = System.currentTimeMillis();
        OpsTrace trace = audit.newTrace("[主动巡检] 系统健康体检");
        String traceId = trace.getTraceId();

        List<InspectionFinding> findings = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Metrics metrics = new Metrics();

        findings.add(checkDisk(sources, metrics));
        findings.add(checkMemory(sources, metrics));
        findings.add(checkLoad(sources));
        findings.add(checkZombie(sources));
        findings.add(checkPorts(sources));

        List<LogEvent> logEvents = collectLogEvents(sources);
        findings.add(checkLogErrors(logEvents));

        recordSample(metrics);
        List<TrendPrediction> predictions = computePredictions();

        int score = computeScore(findings);
        String overall = overall(findings);
        String summary = buildSummary(findings, overall, score, predictions);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("healthScore", score);
        output.put("overall", overall);
        output.put("findings", findings.size());
        output.put("predictions", predictions.size());
        audit.appendStep(traceId, new AgentStep(
                "INSPECT", "ProactiveInspector",
                Map.of("checks", sources),
                output, "rule-based", 1.0,
                System.currentTimeMillis() - start, 0, 0, overall));
        audit.complete(traceId, overall);

        return new InspectionReport(
                UUID.randomUUID().toString(), traceId, Instant.now().toString(),
                score, overall, summary, findings, sources,
                logEvents == null ? List.of() : logEvents,
                System.currentTimeMillis() - start,
                predictions);
    }

    // ---------- 预测性感知（感知成熟度 3 级）----------

    /** 单轮巡检的数值指标快照（各 check 回填，-1 表示不可用）。 */
    private static final class Metrics {
        int diskPct = -1;
        String diskMount = "";
        int memPct = -1;
    }

    private record Sample(long epochMs, int diskPct, String diskMount, int memPct) {}

    private void recordSample(Metrics m) {
        if (m.diskPct < 0 && m.memPct < 0) {
            return;
        }
        history.addLast(new Sample(System.currentTimeMillis(), m.diskPct, m.diskMount, m.memPct));
        long cutoff = System.currentTimeMillis() - SAMPLE_TTL_MS;
        while (history.size() > MAX_SAMPLES
                || (!history.isEmpty() && history.peekFirst().epochMs() < cutoff)) {
            history.pollFirst();
        }
    }

    /** 基于首末采样点做线性趋势外推：预测磁盘写满/内存耗尽时间。采样不足（<2 点）时返回空。 */
    private List<TrendPrediction> computePredictions() {
        List<TrendPrediction> out = new ArrayList<>();
        List<Sample> samples = new ArrayList<>(history);
        predictOne(out, samples, "disk", s -> s.diskPct(), "磁盘写满(100%)");
        predictOne(out, samples, "memory", s -> s.memPct(), "内存耗尽(100%)");
        return out;
    }

    private void predictOne(List<TrendPrediction> out, List<Sample> samples,
                            String metric, java.util.function.ToIntFunction<Sample> getter, String exhaustName) {
        Sample first = null, last = null;
        int count = 0;
        for (Sample s : samples) {
            if (getter.applyAsInt(s) < 0) continue;
            if (first == null) first = s;
            last = s;
            count++;
        }
        if (first == null || last == null || count < 2 || last.epochMs() <= first.epochMs()) {
            return;
        }
        double spanHours = (last.epochMs() - first.epochMs()) / 3_600_000.0;
        int current = getter.applyAsInt(last);
        double rate = (getter.applyAsInt(last) - getter.applyAsInt(first)) / spanHours;
        String basis = "基于 " + count + " 个采样点，跨度 " + formatDuration(last.epochMs() - first.epochMs());

        String severity;
        String projection;
        if (rate <= 0.01) {
            severity = "OK";
            projection = "使用率稳定或下降（" + String.format("%+.2f", rate) + "%/小时），无耗尽风险";
        } else {
            double hoursToFull = (100.0 - current) / rate;
            if (hoursToFull <= 24) {
                severity = "WARN";
                projection = "按当前增速(" + String.format("%.2f", rate) + "%/小时)预计 "
                        + formatHours(hoursToFull) + "后" + exhaustName + "，建议提前清理/扩容";
            } else if (hoursToFull <= 24 * 7) {
                severity = "INFO";
                projection = "按当前增速预计约 " + String.format("%.1f", hoursToFull / 24) + " 天后" + exhaustName + "，建议排入巡检计划";
            } else {
                severity = "OK";
                projection = "增长缓慢，按当前增速 7 天内无耗尽风险";
            }
        }
        out.add(new TrendPrediction(metric, current, Math.round(rate * 100.0) / 100.0,
                severity, projection, basis));
    }

    private String formatHours(double hours) {
        return hours < 1 ? "约 " + Math.max(1, Math.round(hours * 60)) + " 分钟"
                : "约 " + String.format("%.1f", hours) + " 小时";
    }

    private String formatDuration(long ms) {
        long minutes = ms / 60_000;
        if (minutes < 1) return ms / 1000 + " 秒";
        if (minutes < 60) return minutes + " 分钟";
        return String.format("%.1f", minutes / 60.0) + " 小时";
    }

    // ---------- 各项检查 ----------

    private InspectionFinding checkDisk(List<String> sources, Metrics metrics) {
        sources.add("df -P");
        ExecResult r = run(List.of("df", "-P"));
        if (!ok(r)) {
            return unknown("disk-usage", "disk", "磁盘使用率", "df 不可用");
        }
        String worstMount = "";
        int worstPct = -1;
        String worstLine = "";
        for (String line : r.stdout().lines().toList()) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("Filesystem")) continue;
            String[] t = s.split("\\s+");
            if (t.length < 6) continue;
            String fs = t[0];
            String mount = t[t.length - 1];
            if (fs.equals("tmpfs") || fs.equals("devtmpfs") || fs.equals("udev") || fs.equals("overlay")) continue;
            if (mount.startsWith("/dev") || mount.startsWith("/sys") || mount.startsWith("/proc") || mount.startsWith("/run")) continue;
            int pct = parsePercent(t[4]);
            if (pct > worstPct) {
                worstPct = pct;
                worstMount = mount;
                worstLine = s;
            }
        }
        if (worstPct < 0) {
            return unknown("disk-usage", "disk", "磁盘使用率", "未解析到有效文件系统");
        }
        metrics.diskPct = worstPct;
        metrics.diskMount = worstMount;
        String sev = worstPct >= props.getDiskCriticalPercent() ? "CRITICAL"
                : worstPct >= props.getDiskWarnPercent() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "磁盘容量充足，无需处理"
                : "清理 " + worstMount + " 上的大文件/旧日志(如 /var/log)，必要时扩容；建议走主链路指令并经 EXECUTABLE/IRREVERSIBLE 确认";
        return new InspectionFinding("disk-usage", "disk", sev,
                "磁盘使用率(最高分区 " + worstMount + ")", "使用率",
                worstPct + "%", ">=" + props.getDiskWarnPercent() + "% 告警 / >=" + props.getDiskCriticalPercent() + "% 严重",
                worstLine, sug);
    }

    private InspectionFinding checkMemory(List<String> sources, Metrics metrics) {
        sources.add("free");
        ExecResult r = run(List.of("free"));
        if (!ok(r)) {
            return unknown("mem-usage", "memory", "内存使用率", "free 不可用");
        }
        long total = -1, available = -1, used = -1;
        for (String line : r.stdout().lines().toList()) {
            String s = line.trim();
            if (!s.startsWith("Mem:")) continue;
            String[] t = s.split("\\s+");
            if (t.length >= 3) {
                total = parseLong(t[1]);
                used = parseLong(t[2]);
            }
            if (t.length >= 7) {
                available = parseLong(t[6]);
            }
            break;
        }
        if (total <= 0) {
            return unknown("mem-usage", "memory", "内存使用率", "未解析到 Mem 行");
        }
        long usedCalc = available >= 0 ? (total - available) : used;
        int pct = (int) Math.round(usedCalc * 100.0 / total);
        metrics.memPct = pct;
        String sev = pct >= props.getMemCriticalPercent() ? "CRITICAL"
                : pct >= props.getMemWarnPercent() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "内存充足，无需处理"
                : "排查内存占用最高的进程(ps aux --sort=-%mem)，确认是否存在泄漏；释放缓存或扩容";
        return new InspectionFinding("mem-usage", "memory", sev,
                "内存使用率", "使用率", pct + "%",
                ">=" + props.getMemWarnPercent() + "% 告警 / >=" + props.getMemCriticalPercent() + "% 严重",
                "total=" + total + " used=" + usedCalc + " (KB)", sug);
    }

    private InspectionFinding checkLoad(List<String> sources) {
        sources.add("uptime + nproc");
        ExecResult up = run(List.of("uptime"));
        if (!ok(up)) {
            return unknown("load", "load", "系统负载", "uptime 不可用");
        }
        double load1 = parseLoad1(up.stdout());
        if (load1 < 0) {
            return unknown("load", "load", "系统负载", "未解析到 load average");
        }
        int cores = 1;
        ExecResult np = run(List.of("nproc"));
        if (ok(np)) {
            long c = parseLong(np.stdout().trim());
            if (c > 0) cores = (int) c;
        }
        double perCore = load1 / cores;
        String sev = perCore >= props.getLoadCriticalPerCore() ? "CRITICAL"
                : perCore >= props.getLoadWarnPerCore() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "负载正常，无需处理"
                : "定位高 CPU 进程(top/ps)，确认是否异常进程或突发流量；必要时限流或扩容";
        return new InspectionFinding("load", "load", sev,
                "系统负载(1分钟/核)", "load1/核",
                String.format("%.2f", perCore) + " (load1=" + String.format("%.2f", load1) + ", 核=" + cores + ")",
                ">=" + props.getLoadWarnPerCore() + " 告警 / >=" + props.getLoadCriticalPerCore() + " 严重",
                up.stdout().trim(), sug);
    }

    private InspectionFinding checkZombie(List<String> sources) {
        sources.add("ps -eo stat");
        ExecResult r = run(List.of("ps", "-eo", "stat="));
        if (!ok(r)) {
            return unknown("zombie", "process", "僵尸进程", "ps 不可用");
        }
        int zombies = 0;
        for (String line : r.stdout().lines().toList()) {
            if (line.trim().startsWith("Z")) zombies++;
        }
        String sev = zombies >= props.getZombieCritical() ? "CRITICAL"
                : zombies >= props.getZombieWarn() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "无僵尸进程"
                : "定位僵尸进程父进程(ps -eo pid,ppid,stat,cmd)，通知/重启父进程回收";
        return new InspectionFinding("zombie", "process", sev,
                "僵尸进程数", "个数", String.valueOf(zombies),
                ">=" + props.getZombieWarn() + " 告警 / >=" + props.getZombieCritical() + " 严重",
                "检测到 " + zombies + " 个 Z 状态进程", sug);
    }

    private InspectionFinding checkPorts(List<String> sources) {
        sources.add("ss -H -tuln");
        ExecResult r = run(List.of("ss", "-H", "-tuln"));
        if (!ok(r)) {
            return unknown("ports", "network", "监听端口", "ss 不可用");
        }
        int count = 0;
        for (String line : r.stdout().lines().toList()) {
            if (!line.trim().isEmpty()) count++;
        }
        return new InspectionFinding("ports", "network", "OK",
                "监听端口数", "数量", String.valueOf(count), "仅供参考",
                "当前共 " + count + " 个 TCP/UDP 监听套接字",
                "如需排查端口占用，可走主链路指令 ss -tlnp / lsof -i");
    }

    /**
     * 采集近 200 条 err 级 journal 日志，逐条解析时间戳并按关键字分类(OOM/IO/DISK_FULL)。
     * @return 事件列表；当 journalctl 不可用时返回 null。
     */
    private List<LogEvent> collectLogEvents(List<String> sources) {
        sources.add("journalctl -p 3 -n 200 -o short-iso");
        ExecResult r = run(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager"));
        if (r == null || r.exitCode() != 0) {
            return null;
        }
        List<LogEvent> events = new ArrayList<>();
        if (r.stdout() == null) return events;
        for (String line : r.stdout().lines().toList()) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("--")) continue;
            long epoch = parseIsoEpoch(s);
            String iso = epoch >= 0 ? s.substring(0, Math.min(s.indexOf(' ') < 0 ? s.length() : s.indexOf(' '), s.length())) : "";
            String kind = classifyLog(s);
            String msg = s.length() > 300 ? s.substring(0, 300) : s;
            events.add(new LogEvent(iso, epoch, kind, msg));
        }
        return events;
    }

    private InspectionFinding checkLogErrors(List<LogEvent> events) {
        if (events == null) {
            return unknown("log-errors", "log", "系统错误日志", "journalctl 不可用");
        }
        int count = events.size();
        long now = System.currentTimeMillis();
        long windowMs = props.getLogWindowMinutes() * 60_000L;
        int recent = 0;
        int oom = 0, io = 0, diskFull = 0, network = 0, dependency = 0, config = 0;
        for (LogEvent e : events) {
            if (e.epochMillis() >= 0 && now - e.epochMillis() <= windowMs) recent++;
            switch (e.kind()) {
                case "OOM" -> oom++;
                case "IO" -> io++;
                case "DISK_FULL" -> diskFull++;
                case "NETWORK" -> network++;
                case "DEPENDENCY" -> dependency++;
                case "CONFIG" -> config++;
                default -> { }
            }
        }
        String sev = count >= props.getLogErrorCritical() ? "CRITICAL"
                : count >= props.getLogErrorWarn() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "近期无明显错误日志"
                : "查看最近错误(journalctl -p 3 -xb)，结合知识库 runbook 定位故障源";
        StringBuilder kinds = new StringBuilder();
        if (oom > 0) kinds.append("OOM×").append(oom).append(' ');
        if (io > 0) kinds.append("IO×").append(io).append(' ');
        if (diskFull > 0) kinds.append("DISK_FULL×").append(diskFull).append(' ');
        if (network > 0) kinds.append("NETWORK×").append(network).append(' ');
        if (dependency > 0) kinds.append("DEPENDENCY×").append(dependency).append(' ');
        if (config > 0) kinds.append("CONFIG×").append(config).append(' ');
        String evidence = "匹配到 " + count + " 条错误级日志"
                + "（近 " + props.getLogWindowMinutes() + " 分钟内 " + recent + " 条）"
                + (kinds.length() > 0 ? "；关键类型：" + kinds.toString().trim() : "");
        return new InspectionFinding("log-errors", "log", sev,
                "系统错误日志(近200条优先级<=err)", "条数", String.valueOf(count),
                ">=" + props.getLogErrorWarn() + " 告警 / >=" + props.getLogErrorCritical() + " 严重",
                evidence, sug);
    }

    // ---------- 评分与汇总 ----------

    private int computeScore(List<InspectionFinding> findings) {
        int score = 100;
        for (InspectionFinding f : findings) {
            switch (f.severity()) {
                case "CRITICAL" -> score -= 25;
                case "WARN" -> score -= 10;
                case "UNKNOWN" -> score -= 8;
                default -> { }
            }
        }
        return Math.max(0, score);
    }

    /**
     * 总体状态判定。安全修复(P2)：UNKNOWN 不能被当作 HEALTHY。
     * 过半指标无法采集 -> DEGRADED(数据降级、不可信)；个别缺失 -> WARNING。
     */
    private String overall(List<InspectionFinding> findings) {
        boolean warn = false;
        int unknown = 0;
        for (InspectionFinding f : findings) {
            if ("CRITICAL".equals(f.severity())) return "CRITICAL";
            if ("WARN".equals(f.severity())) warn = true;
            if ("UNKNOWN".equals(f.severity())) unknown++;
        }
        if (warn) return "WARNING";
        if (unknown > 0) {
            return unknown * 2 >= findings.size() ? "DEGRADED" : "WARNING";
        }
        return "HEALTHY";
    }

    private String buildSummary(List<InspectionFinding> findings, String overall, int score,
                                List<TrendPrediction> predictions) {
        List<String> alerts = new ArrayList<>();
        int unknown = 0;
        for (InspectionFinding f : findings) {
            if ("CRITICAL".equals(f.severity()) || "WARN".equals(f.severity())) {
                alerts.add(f.title() + "=" + f.observed() + "(" + f.severity() + ")");
            } else if ("UNKNOWN".equals(f.severity())) {
                unknown++;
            }
        }
        StringBuilder sb = new StringBuilder("健康评分 " + score + "/100，总体状态 " + overall + "。");
        if (!alerts.isEmpty()) {
            sb.append("需关注：").append(String.join("；", alerts)).append("。");
        }
        if (unknown > 0) {
            sb.append("有 ").append(unknown)
              .append(" 项指标在当前环境无法采集（可能命令缺失或非目标系统），巡检结果不完整，评分仅供参考，建议在目标麒麟/LoongArch 主机复测。");
        } else if (alerts.isEmpty()) {
            sb.append("本轮巡检未发现需要关注的风险项。");
        }
        for (TrendPrediction p : predictions) {
            if ("WARN".equals(p.severity()) || "INFO".equals(p.severity())) {
                sb.append("【预测】").append("disk".equals(p.metric()) ? "磁盘" : "内存")
                  .append("：").append(p.projection()).append("。");
            }
        }
        return sb.toString();
    }

    // ---------- 工具方法 ----------

    private ExecResult run(List<String> argv) {
        try {
            return executor.runReadOnly(argv);
        } catch (Exception e) {
            return new ExecResult(-1, "", e.getMessage(), false, 0);
        }
    }

    private boolean ok(ExecResult r) {
        return r != null && r.exitCode() == 0 && r.stdout() != null && !r.stdout().isBlank();
    }

    private InspectionFinding unknown(String id, String category, String title, String reason) {
        return new InspectionFinding(id, category, "UNKNOWN", title, "-", "数据不可用", "-", reason,
                "该指标在当前环境无法采集(可能命令缺失)，请在目标麒麟/LoongArch 主机复测");
    }

    private int parsePercent(String s) {
        try {
            return Integer.parseInt(s.replace("%", "").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private double parseLoad1(String uptimeOut) {
        int idx = uptimeOut.indexOf("load average:");
        if (idx < 0) return -1;
        String rest = uptimeOut.substring(idx + "load average:".length()).trim();
        String[] parts = rest.split(",");
        if (parts.length == 0) return -1;
        try {
            return Double.parseDouble(parts[0].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析 journalctl short-iso 行首时间戳为毫秒；失败返回 -1。 */
    private long parseIsoEpoch(String line) {
        int sp = line.indexOf(' ');
        if (sp <= 0) return -1;
        String ts = line.substring(0, sp);
        String norm = ts;
        int len = ts.length();
        if (len >= 5) {
            char sign = ts.charAt(len - 5);
            String tail = ts.substring(len - 4);
            boolean digits = tail.chars().allMatch(Character::isDigit);
            if ((sign == '+' || sign == '-') && digits) {
                norm = ts.substring(0, len - 5) + sign + tail.substring(0, 2) + ":" + tail.substring(2);
            }
        }
        try {
            return OffsetDateTime.parse(norm).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(norm).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    private String classifyLog(String line) {
        String lc = line.toLowerCase();
        if (lc.contains("out of memory") || lc.contains("oom-kill") || lc.contains("oom_kill")
                || lc.contains("oom_reaper") || lc.contains("killed process")) {
            return "OOM";
        }
        if (lc.contains("no space left") || lc.contains("disk full") || lc.contains("quota exceeded")) {
            return "DISK_FULL";
        }
        if (lc.contains("i/o error") || lc.contains("ext4-fs error") || lc.contains("blk_update_request")
                || lc.contains("buffer i/o") || (lc.contains("xfs") && lc.contains("error"))) {
            return "IO";
        }
        if (lc.contains("connection refused") || lc.contains("connection timed out")
                || lc.contains("ehostunreach") || lc.contains("enetunreach")
                || lc.contains("no route to host") || lc.contains("network is unreachable")
                || lc.contains("dns resolution") || lc.contains("name resolution")
                || lc.contains("temporary failure in name resolution")) {
            return "NETWORK";
        }
        if (lc.contains("service unavailable") || lc.contains("upstream")
                || lc.contains("circuit breaker") || lc.contains("502 bad gateway")
                || lc.contains("503 service unavailable") || lc.contains("504 gateway timeout")
                || lc.contains("dependency") || lc.contains("backend unhealthy")
                || lc.contains("connection pool exhausted")) {
            return "DEPENDENCY";
        }
        if (lc.contains("config") && (lc.contains("error") || lc.contains("invalid") || lc.contains("syntax")))
            return "CONFIG";
        if (lc.contains("configuration") && (lc.contains("parse") || lc.contains("fail")))
            return "CONFIG";
        if (lc.contains("invalid configuration") || lc.contains("malformed config"))
            return "CONFIG";
        return "OTHER";
    }
}
