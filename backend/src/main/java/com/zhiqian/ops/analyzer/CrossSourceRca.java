package com.zhiqian.ops.analyzer;

import com.zhiqian.ops.inspect.InspectionFinding;
import com.zhiqian.ops.inspect.InspectionProperties;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.inspect.LogEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 跨源根因分析器：以一次只读巡检报告为输入，将「指标域」(磁盘/内存/负载/进程)信号
 * 与「日志域」(系统错误日志)信号做跨源关联，输出根因假设与 L1-L3 分级处置建议。
 *
 * 相较于纯阈值规则，本分析器在「同一巡检窗口同时升高」之上，进一步引入
 * 「时间窗口 + 错误类型」的精细关联：在最近 N 分钟(ops.inspect.log-window-minutes)内，
 * 若 journal 出现与指标异常同源的错误类型(内存↔OOM、磁盘↔DISK_FULL/IO)，
 * 则判定为同一根因并提升处置等级与置信度(例如「高内存 + 5 分钟内 OOM」→ L3)。
 * 纯计算、只读，不触发任何变更；真正处置仍需走主链路护栏与人工确认。
 */
@Component
public class CrossSourceRca {

    private final InspectionProperties props;

    public CrossSourceRca(InspectionProperties props) {
        this.props = props;
    }

    public RcaResult analyze(InspectionReport report) {
        List<RcaInsight> insights = new ArrayList<>();
        if (report == null || report.findings() == null) {
            return new RcaResult(null, null, "L0", "无巡检数据可分析", insights);
        }

        InspectionFinding log = find(report, "log-errors");
        String logSev = log != null ? log.severity() : "UNKNOWN";
        boolean logElevated = "WARN".equals(logSev) || "CRITICAL".equals(logSev);

        long now = System.currentTimeMillis();
        long windowMs = props.getLogWindowMinutes() * 60_000L;
        int windowMin = props.getLogWindowMinutes();

        int criticalCount = 0;
        boolean anyMetricAnomaly = false;

        for (InspectionFinding f : report.findings()) {
            if (f == null) continue;
            if ("log-errors".equals(f.id()) || "ports".equals(f.id())) continue;
            String sev = f.severity();
            if (!("WARN".equals(sev) || "CRITICAL".equals(sev))) continue;
            anyMetricAnomaly = true;
            boolean metricCritical = "CRITICAL".equals(sev);
            if (metricCritical) criticalCount++;

            Set<String> kinds = expectedKinds(f.id());
            List<LogEvent> matched = kinds.isEmpty()
                    ? List.of() : matchEvents(report, kinds, windowMs, now);
            boolean kindMatch = !matched.isEmpty();

            String level = grade(metricCritical, logElevated, kindMatch);

            String correlation;
            if (kindMatch) {
                LogEvent latest = matched.get(matched.size() - 1);
                correlation = "在 " + windowMin + " 分钟时间窗口内，指标异常(" + f.title() + "=" + f.observed()
                        + ") 与 journal 中 " + matched.size() + " 条 " + kindLabel(matched)
                        + " 错误(最近一条 @" + safeTime(latest) + ")同时出现，时间窗口跨源关联，高置信判定为同一根因";
            } else if (logElevated) {
                correlation = "指标异常(" + f.title() + "=" + f.observed() + ") 与 系统错误日志(" + log.observed()
                        + " 条 err 级，" + logSev + ") 在同一巡检窗口内同时升高，跨源相互印证，判定为关联事件";
            } else {
                correlation = "仅指标侧异常(" + f.title() + "=" + f.observed() + ")，日志侧未见明显错误升高，暂判为单源信号，建议二次观察确认";
            }

            List<String> chain = new ArrayList<>();
            chain.add("[指标] " + f.title() + ": " + f.observed() + " (阈值 " + f.threshold() + "，" + sev + ")");
            chain.add("[指标证据] " + f.evidence());
            if (kindMatch) {
                chain.add("[时间窗口] 窗口=" + windowMin + "min，命中同源错误日志 " + matched.size() + " 条：");
                int shown = 0;
                for (LogEvent e : matched) {
                    if (shown++ >= 3) {
                        chain.add("  …其余 " + (matched.size() - 3) + " 条略");
                        break;
                    }
                    chain.add("  @" + safeTime(e) + " [" + e.kind() + "] " + e.message());
                }
            } else if (logElevated) {
                chain.add("[日志] 系统错误日志: " + log.observed() + " 条 (" + logSev + ")");
                chain.add("[日志证据] " + log.evidence());
            } else {
                chain.add("[日志] 系统错误日志: " + (log != null ? log.observed() + " 条 (" + logSev + ")" : "不可用") + " — 未构成跨源印证");
            }

            insights.add(new RcaInsight(
                    level, f.category(),
                    f.title() + " 根因研判",
                    correlation, rootCause(f.id(), metricCritical, kindMatch, matched),
                    f.suggestion() + "（处置须走主链路：高危指令经 EXECUTABLE/IRREVERSIBLE + 人工确认后执行，并保留一键回滚账本）",
                    disposition(level), confidence(metricCritical, logElevated, kindMatch), chain));
        }

        if (logElevated && !anyMetricAnomaly) {
            String level = "CRITICAL".equals(logSev) ? "L2" : "L1";
            List<String> chain = new ArrayList<>();
            chain.add("[日志] 系统错误日志: " + log.observed() + " 条 (" + logSev + ")");
            chain.add("[日志证据] " + log.evidence());
            chain.add("[指标] 各项资源指标未见同步异常");
            insights.add(new RcaInsight(
                    level, "log",
                    "错误日志激增研判",
                    "日志侧错误升高但资源指标正常，可能为应用层错误/外部依赖故障，而非本机资源瓶颈",
                    "应用或依赖服务异常导致错误日志增多，需结合具体 err 日志与 runbook 定位",
                    "查看 journalctl -p 3 -xb 定位错误来源，结合知识库 runbook 处置（处置须经护栏与人工确认）",
                    disposition(level),
                    "CRITICAL".equals(logSev) ? 70 : 55, chain));
        }

        String overall = overallLevel(insights);
        return new RcaResult(report.inspectId(), report.traceId(), overall,
                buildSummary(insights, criticalCount, logElevated, overall), insights);
    }

    /** 指标 id -> 与之同源的日志错误类型(用于时间窗口关联)。 */
    private Set<String> expectedKinds(String id) {
        return switch (id) {
            case "mem-usage" -> Set.of("OOM");
            case "disk-usage" -> Set.of("DISK_FULL", "IO");
            case "load" -> Set.of("NETWORK", "DEPENDENCY");
            case "zombie" -> Set.of("DEPENDENCY", "CONFIG");
            default -> Set.of();
        };
    }

    private List<LogEvent> matchEvents(InspectionReport report, Set<String> kinds, long windowMs, long now) {
        List<LogEvent> out = new ArrayList<>();
        if (report.recentLogEvents() == null) return out;
        for (LogEvent e : report.recentLogEvents()) {
            if (e == null) continue;
            boolean inWindow = e.epochMillis() < 0 || (now - e.epochMillis() <= windowMs);
            if (inWindow && kinds.contains(e.kind())) {
                out.add(e);
            }
        }
        return out;
    }

    private String kindLabel(List<LogEvent> matched) {
        boolean oom = false, io = false, df = false, net = false, dep = false, cfg = false;
        for (LogEvent e : matched) {
            switch (e.kind()) {
                case "OOM" -> oom = true;
                case "IO" -> io = true;
                case "DISK_FULL" -> df = true;
                case "NETWORK" -> net = true;
                case "DEPENDENCY" -> dep = true;
                case "CONFIG" -> cfg = true;
                default -> { }
            }
        }
        List<String> labels = new ArrayList<>();
        if (oom) labels.add("OOM/内存耗尽");
        if (df) labels.add("磁盘写满");
        if (io) labels.add("磁盘 I/O");
        if (net) labels.add("网络故障");
        if (dep) labels.add("依赖服务异常");
        if (cfg) labels.add("配置错误");
        return labels.isEmpty() ? "同源" : String.join("、", labels);
    }

    private String safeTime(LogEvent e) {
        return e.time() == null || e.time().isEmpty() ? "时间未知" : e.time();
    }

    private InspectionFinding find(InspectionReport report, String id) {
        for (InspectionFinding f : report.findings()) {
            if (f != null && id.equals(f.id())) return f;
        }
        return null;
    }

    private String grade(boolean metricCritical, boolean logElevated, boolean kindMatch) {
        if (metricCritical && (logElevated || kindMatch)) return "L3";
        if (kindMatch) return "L2";
        if (metricCritical) return "L2";
        if (logElevated) return "L2";
        return "L1";
    }

    private String disposition(String level) {
        return switch (level) {
            case "L3" -> "立即升级人工并告警；按处置预案执行，全程保留一键回滚账本";
            case "L2" -> "生成处置预案，经人工确认后走护栏执行";
            default -> "纳入观察，必要时执行只读复核，无需即时变更";
        };
    }

    private String rootCause(String id, boolean critical, boolean kindMatch, List<LogEvent> matched) {
        String base = switch (id) {
            case "disk-usage" -> critical
                    ? "磁盘空间接近写满，极可能导致服务写入失败、日志中断或数据库异常"
                    : "磁盘使用率偏高，存在写满风险，多由大文件/旧日志堆积引起";
            case "mem-usage" -> critical
                    ? "内存严重不足，存在 OOM 风险，可能触发进程被内核终止"
                    : "内存使用率偏高，疑似存在缓存堆积或进程内存泄漏";
            case "load" -> critical
                    ? "系统负载远超核数，CPU/调度严重过载，可能为异常进程或突发流量"
                    : "系统负载偏高，存在性能瓶颈苗头";
            case "zombie" -> "存在僵尸进程未被回收，父进程回收逻辑异常或已挂起";
            default -> "资源指标异常，需结合上下文进一步定位";
        };
        if (kindMatch) {
            if ("mem-usage".equals(id)) {
                return base + "；journal 已捕获 OOM/内核终止进程事件，证实内存压力已实际触发进程被杀，二者为同一根因";
            }
            if ("disk-usage".equals(id)) {
                return base + "；journal 已出现空间写满/磁盘 I/O 错误事件，证实磁盘问题已影响实际读写，二者为同一根因";
            }
        }
        return base;
    }

    private int confidence(boolean metricCritical, boolean logElevated, boolean kindMatch) {
        int base = metricCritical ? 75 : 60;
        if (logElevated) base += 10;
        if (kindMatch) base += 15;
        return Math.min(97, base);
    }

    private String overallLevel(List<RcaInsight> insights) {
        String max = "L0";
        for (RcaInsight i : insights) {
            if ("L3".equals(i.level())) return "L3";
            if ("L2".equals(i.level())) max = "L2";
            else if ("L1".equals(i.level()) && "L0".equals(max)) max = "L1";
        }
        return max;
    }

    private String buildSummary(List<RcaInsight> insights, int criticalCount, boolean logElevated, String overall) {
        if (insights.isEmpty()) {
            return "跨源分析未发现需处置的关联风险，系统处于正常区间(L0)。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("跨源根因分析共生成 ").append(insights.size()).append(" 条洞察，最高处置等级 ").append(overall).append("。");
        if (criticalCount > 0) {
            sb.append("其中 ").append(criticalCount).append(" 项指标达严重阈值");
            sb.append(logElevated ? "，且与错误日志跨源印证，研判为关联性故障，建议立即升级人工处置。" : "，暂未见日志侧印证，建议尽快人工确认。");
        } else {
            sb.append(logElevated ? "指标与日志均有告警级信号，建议生成预案并人工确认后处置。" : "以告警级信号为主，纳入观察。");
        }
        return sb.toString();
    }
}
