package com.zhiqian.ops.planner;

import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.llm.PlanStep;
import com.zhiqian.ops.retriever.Evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic task planner layered above the LLM command plan.
 *
 * The LLM still proposes candidate commands. This class makes the global
 * task decomposition explicit and later annotates it with guard decisions and
 * execution observations.
 */
public class ExecutionPlanner {
    private final IntentRiskGuard guard;

    private static final Set<String> READONLY_BINARIES = Set.of(
            "df", "du", "ps", "ss", "netstat", "journalctl", "tail", "cat",
            "uptime", "free", "lsof", "top", "vmstat", "iostat", "dmesg",
            "ls", "find", "grep", "awk", "sed");

    public ExecutionPlanner() {
        this(null);
    }

    public ExecutionPlanner(IntentRiskGuard guard) {
        this.guard = guard;
    }

    public OpsExecutionPlan build(String instruction, PlanResult plan, List<Evidence> evidence) {
        OpsExecutionPlan out = new OpsExecutionPlan();
        List<PlanStep> steps = plan == null || plan.getSteps() == null ? List.of() : plan.getSteps();
        out.setCommandCount(steps.size());
        out.setStrategy("Plan-and-Execute: decompose intent into evidence, action, and verification tasks; guards decide execution.");
        out.setExecutionMode("sequential-with-human-gates");
        out.setSummary(summary(instruction, steps));

        OpsTask observe = new OpsTask("T1", "OBSERVE", "采集证据", "先运行只读命令，确认故障范围和影响面。");
        OpsTask change = new OpsTask("T2", "CHANGE", "受控处置", "仅在护栏允许且人工确认后执行变更命令。");
        OpsTask verify = new OpsTask("T3", "VERIFY", "复核与闭环", "基于执行结果、trace 与 RCA 输出复核处置是否完成。");
        change.getDependsOn().add(observe.getId());
        verify.getDependsOn().add(change.getId());

        List<String> refs = evidenceRefs(evidence);
        observe.setEvidenceRefs(refs);
        change.setEvidenceRefs(refs);
        verify.setEvidenceRefs(refs);

        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            String command = step == null ? "" : safe(step.getCommand());
            OpsTask task = isProbablyReadOnly(command) ? observe : change;
            task.getCommandIndexes().add(i);
            task.getCommands().add(command);
        }

        List<OpsTask> tasks = new ArrayList<>();
        if (!observe.getCommands().isEmpty()) {
            observe.setStatus("READY");
            observe.setExpectedRisk("READONLY");
            tasks.add(observe);
        } else {
            change.getDependsOn().clear();
        }
        if (!change.getCommands().isEmpty()) {
            change.setStatus("PENDING_GUARD");
            change.setExpectedRisk("UNKNOWN");
            tasks.add(change);
            tasks.add(verify);
        } else if (!observe.getCommands().isEmpty()) {
            verify.getDependsOn().clear();
            verify.getDependsOn().add(observe.getId());
            verify.setStatus("PLANNED");
            verify.setExpectedRisk("READONLY");
            tasks.add(verify);
        }
        if (tasks.isEmpty()) {
            OpsTask empty = new OpsTask("T1", "CLARIFY", "补充信息", "当前没有可执行命令，需补充故障现象或人工确认下一步。");
            empty.setExpectedRisk("UNKNOWN");
            empty.setStatus("NEEDS_INPUT");
            tasks.add(empty);
        }
        out.setTasks(tasks);
        return out;
    }

    public void attachDecisions(OpsExecutionPlan plan, List<RiskDecision> decisions) {
        if (plan == null || decisions == null) return;
        for (OpsTask task : plan.getTasks()) {
            if (task.getCommandIndexes().isEmpty()) continue;
            RiskLevel worst = RiskLevel.READONLY;
            boolean hasDecision = false;
            for (Integer idx : task.getCommandIndexes()) {
                if (idx == null || idx < 0 || idx >= decisions.size()) continue;
                RiskDecision d = decisions.get(idx);
                if (d == null) continue;
                worst = RiskLevel.max(worst, d.level());
                hasDecision = true;
            }
            if (!hasDecision) continue;
            task.setExpectedRisk(worst.name());
            if (worst == RiskLevel.BLOCK) {
                task.setStatus("BLOCKED_BY_GUARD");
            } else if (worst.requiresApproval()) {
                task.setStatus("WAITING_APPROVAL");
            } else {
                task.setStatus("READY");
            }
        }
    }

    public void attachExecutionResults(OpsExecutionPlan plan, List<Map<String, Object>> execResults) {
        if (plan == null || execResults == null) return;
        for (OpsTask task : plan.getTasks()) {
            if (task.getCommandIndexes().isEmpty()) {
                if ("VERIFY".equals(task.getPhase())) {
                    task.setStatus("PLANNED");
                    task.setResultSummary("等待分析阶段综合执行结果与证据链。");
                }
                continue;
            }
            int executed = 0;
            int dryRun = 0;
            int blocked = 0;
            int pending = 0;
            for (Integer idx : task.getCommandIndexes()) {
                if (idx == null || idx < 0 || idx >= execResults.size()) continue;
                Map<String, Object> er = execResults.get(idx);
                if (Boolean.TRUE.equals(er.get("executed"))) {
                    executed++;
                    if (Boolean.TRUE.equals(er.get("dryRun"))) dryRun++;
                } else {
                    String output = String.valueOf(er.getOrDefault("output", ""));
                    if (output.contains("拒绝") || output.contains("红线")) blocked++;
                    else pending++;
                }
            }
            if (executed > 0) {
                task.setStatus(dryRun > 0 ? "DRY_RUN_EXECUTED" : "EXECUTED");
                task.setResultSummary("已处理 " + executed + " 条命令" + (dryRun > 0 ? "（含 dry-run）" : "") + "。");
            } else if (blocked > 0) {
                task.setStatus("BLOCKED_BY_GUARD");
                task.setResultSummary("命令被护栏拒绝，未进入执行层。");
            } else if (pending > 0) {
                task.setStatus("WAITING_APPROVAL");
                task.setResultSummary("等待人工确认后执行。");
            }
        }
    }

    private String summary(String instruction, List<PlanStep> steps) {
        String subject = safe(instruction).isBlank() ? "当前运维请求" : safe(instruction);
        return "将“" + trim(subject, 48) + "”拆解为 " + steps.size() + " 条候选命令，并按证据采集、受控处置、复核闭环串行推进。";
    }

    private List<String> evidenceRefs(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        Set<String> refs = new LinkedHashSet<>();
        for (Evidence ev : evidence) {
            if (ev == null) continue;
            String title = safe(ev.title());
            String source = safe(ev.source());
            if (!title.isBlank()) refs.add(source.isBlank() ? title : title + " (" + source + ")");
            if (refs.size() >= 3) break;
        }
        return new ArrayList<>(refs);
    }

    private boolean isProbablyReadOnly(String command) {
        if (guard != null) {
            return guard.evaluate(command).level() == RiskLevel.READONLY;
        }
        String binary = firstToken(command).toLowerCase(Locale.ROOT);
        if (binary.isBlank()) return false;
        if ("sed".equals(binary)) {
            String lc = command.toLowerCase(Locale.ROOT);
            return !lc.contains(" -i") && !lc.contains("--in-place");
        }
        if ("find".equals(binary)) {
            String lc = command.toLowerCase(Locale.ROOT);
            return !lc.contains("-delete") && !lc.contains("-exec");
        }
        return READONLY_BINARIES.contains(binary);
    }

    private String firstToken(String command) {
        String s = safe(command).trim();
        if (s.isEmpty()) return "";
        int idx = s.indexOf(' ');
        return idx < 0 ? s : s.substring(0, idx);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
