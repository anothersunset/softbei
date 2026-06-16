package com.zhiqian.ops.inspect;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主动巡检服务：周期性/按需对系统做只读体检，按阈值规则生成风险预警与健康评分。
 * 关键安全约束：全程仅调用只读命令(runReadOnly)，绝不触发任何变更；
 * 不绕过安全护栏——仅提供「主动发现 + 处置建议」，真正的处置仍需走主链路的护栏与人工确认。
 */
@Service
public class InspectionService {
    private final LeastPrivilegeExecutor executor;
    private final InspectionProperties props;
    private final OpsAuditService audit;

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

        findings.add(checkDisk(sources));
        findings.add(checkMemory(sources));
        findings.add(checkLoad(sources));
        findings.add(checkZombie(sources));
        findings.add(checkPorts(sources));
        findings.add(checkLogErrors(sources));

        int score = computeScore(findings);
        String overall = overall(findings);
        String summary = buildSummary(findings, overall, score);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("healthScore", score);
        output.put("overall", overall);
        output.put("findings", findings.size());
        audit.appendStep(traceId, new AgentStep(
                "INSPECT", "ProactiveInspector",
                Map.of("checks", sources),
                output, "rule-based", 1.0,
                System.currentTimeMillis() - start, 0, 0, overall));
        audit.complete(traceId, overall);

        return new InspectionReport(
                UUID.randomUUID().toString(), traceId, Instant.now().toString(),
                score, overall, summary, findings, sources,
                System.currentTimeMillis() - start);
    }

    // ---------- 各项检查 ----------

    private InspectionFinding checkDisk(List<String> sources) {
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
        String sev = worstPct >= props.getDiskCriticalPercent() ? "CRITICAL"
                : worstPct >= props.getDiskWarnPercent() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "磁盘容量充足，无需处理"
                : "清理 " + worstMount + " 上的大文件/旧日志(如 /var/log)，必要时扩容；建议走主链路指令并经 REVIEW 确认";
        return new InspectionFinding("disk-usage", "disk", sev,
                "磁盘使用率(最高分区 " + worstMount + ")", "使用率",
                worstPct + "%", ">=" + props.getDiskWarnPercent() + "% 告警 / >=" + props.getDiskCriticalPercent() + "% 严重",
                worstLine, sug);
    }

    private InspectionFinding checkMemory(List<String> sources) {
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

    private InspectionFinding checkLogErrors(List<String> sources) {
        sources.add("journalctl -p 3 -n 200");
        ExecResult r = run(List.of("journalctl", "-p", "3", "-n", "200", "--no-pager"));
        if (!ok(r)) {
            return unknown("log-errors", "log", "系统错误日志", "journalctl 不可用");
        }
        int count = 0;
        for (String line : r.stdout().lines().toList()) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("--")) continue;
            count++;
        }
        String sev = count >= props.getLogErrorCritical() ? "CRITICAL"
                : count >= props.getLogErrorWarn() ? "WARN" : "OK";
        String sug = "OK".equals(sev) ? "近期无明显错误日志"
                : "查看最近错误(journalctl -p 3 -xb)，结合知识库 runbook 定位故障源";
        return new InspectionFinding("log-errors", "log", sev,
                "系统错误日志(近200条优先级<=err)", "条数", String.valueOf(count),
                ">=" + props.getLogErrorWarn() + " 告警 / >=" + props.getLogErrorCritical() + " 严重",
                "匹配到 " + count + " 条错误级日志", sug);
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

    private String overall(List<InspectionFinding> findings) {
        boolean warn = false;
        int unknown = 0;
        for (InspectionFinding f : findings) {
            if ("CRITICAL".equals(f.severity())) return "CRITICAL";
            if ("WARN".equals(f.severity())) warn = true;
            if ("UNKNOWN".equals(f.severity())) unknown++;
        }
        if (warn) return "WARNING";
        // 安全修复(P2)：存在无法采集的指标时不能直接判定 HEALTHY。
        // 过半指标缺失视为数据不可信(DEGRADED)，仅个别缺失时给出 WARNING 提示。
        if (unknown > 0) {
            return unknown * 2 >= findings.size() ? "DEGRADED" : "WARNING";
        }
        return "HEALTHY";
    }

    private String buildSummary(List<InspectionFinding> findings, String over