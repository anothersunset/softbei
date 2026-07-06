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
import java.util.Map;
import java.util.Objects;
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

    public ExecutionPlanner(IntentRiskGuard guard) {
        this.guard = Objects.requireNonNull(guard, "IntentRiskGuard is required for planner risk classification");
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

    /**
     * 为已真实执行的变更命令派生只读复核探针（VERIFY 闭环）。
     * 探针由固定模板构造且入参经严格字符校验，全部只读，由执行节点直接以 runReadOnly 运行。
     * @return 无法派生时返回 null（保持原有"等待分析阶段"行为）。
     */
    public VerifySpec deriveVerification(String changeCommand) {
        if (changeCommand == null || changeCommand.isBlank()) return null;
        String[] t = changeCommand.trim().split("\\s+");
        if (t.length < 2) return null;
        String bin = baseName(t[0]);
        switch (bin) {
            case "systemctl" -> {
                if (t.length >= 3 && isSafeToken(t[2])) {
                    String action = t[1];
                    String unit = t[2];
                    if (List.of("start", "restart", "reload", "unmask", "enable").contains(action)) {
                        return new VerifySpec(List.of("systemctl", "is-active", unit),
                                "exit0", "服务 " + unit + " 应处于 active 状态");
                    }
                    if (List.of("stop", "mask", "disable").contains(action)) {
                        return new VerifySpec(List.of("systemctl", "is-active", unit),
                                "nonzero", "服务 " + unit + " 应已停止（is-active 非零退出）");
                    }
                }
            }
            case "rm" -> {
                String path = lastPathArg(t);
                if (path != null) {
                    return new VerifySpec(List.of("stat", path), "nonzero", "文件 " + path + " 应已删除");
                }
            }
            case "truncate", "chmod", "chown", "chgrp", "tee" -> {
                String path = lastPathArg(t);
                if (path != null) {
                    return new VerifySpec(List.of("stat", path), "exit0", "目标 " + path + " 应存在且状态可查");
                }
            }
            case "mv" -> {
                String dst = lastPathArg(t);
                if (dst != null) {
                    return new VerifySpec(List.of("stat", dst), "exit0", "目标 " + dst + " 应已就位");
                }
            }
            case "kill" -> {
                String pid = t[t.length - 1];
                if (pid.matches("\\d+")) {
                    return new VerifySpec(List.of("ps", "-p", pid), "nonzero", "进程 " + pid + " 应已退出");
                }
            }
            default -> { }
        }
        return null;
    }

    /** 将复核结果回填 VERIFY 任务状态：全部通过 -> VERIFIED，任一失败 -> VERIFY_FAILED（挂回滚提示）。 */
    public void applyVerification(OpsExecutionPlan plan, List<Map<String, Object>> verifications) {
        if (plan == null || verifications == null || verifications.isEmpty()) return;
        for (OpsTask task : plan.getTasks()) {
            if (!"VERIFY".equals(task.getPhase())) continue;
            int passed = 0;
            List<String> failures = new ArrayList<>();
            for (Map<String, Object> v : verifications) {
                if (Boolean.TRUE.equals(v.get("passed"))) {
                    passed++;
                } else {
                    failures.add(String.valueOf(v.getOrDefault("expectation", v.get("verifyCommand"))));
                }
            }
            if (failures.isEmpty()) {
                task.setStatus("VERIFIED");
                task.setResultSummary("闭环复核通过：" + passed + "/" + verifications.size() + " 项验证符合预期。");
            } else {
                task.setStatus("VERIFY_FAILED");
                task.setResultSummary("复核未达预期（" + passed + "/" + verifications.size() + " 通过）："
                        + String.join("；", failures)
                        + "。可查看回滚账本获取补偿指令（/api/ops/rollback/{traceId}）。");
            }
        }
    }

    /** 复核探针定义：argv 形式的只读命令 + 通过条件（exit0 / nonzero）+ 人读预期。 */
    public record VerifySpec(List<String> argv, String expect, String expectation) {
        public boolean passed(int exitCode) {
            return "nonzero".equals(expect) ? exitCode != 0 : exitCode == 0;
        }
    }

    private String lastPathArg(String[] tokens) {
        for (int i = tokens.length - 1; i >= 1; i--) {
            String tok = tokens[i];
            if (tok.startsWith("/") && isSafeToken(tok)) {
                return tok;
            }
        }
        return null;
    }

    /** 探针入参白名单字符校验：防止把元字符/空白注入到模板命令。 */
    private boolean isSafeToken(String s) {
        return s != null && !s.isBlank() && s.matches("[A-Za-z0-9._/@:-]+");
    }

    private String baseName(String bin) {
        int idx = bin.lastIndexOf('/');
        return idx >= 0 ? bin.substring(idx + 1) : bin;
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
        return guard.evaluate(command).level() == RiskLevel.READONLY;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
