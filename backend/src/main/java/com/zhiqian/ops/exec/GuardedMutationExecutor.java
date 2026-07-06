package com.zhiqian.ops.exec;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更类 MCP 工具的共享安全内核：任何 {@link com.zhiqian.ops.agent.MutatingTool}
 * 的执行都必须经由本类，保证与 /api/ops/chat 主链路相同的安全闭环——
 * <ol>
 *   <li>意图风险护栏逐条裁决（红线 BLOCK 即拒绝，confirm 也无法越过）；</li>
 *   <li>需审批级别（EXECUTABLE/IRREVERSIBLE）未携带 confirm=true 时返回 REVIEW_PENDING，不执行；</li>
 *   <li>真实执行前自动备份目标文件（PreChangeBackup）；</li>
 *   <li>经最小权限执行器执行（dry-run 默认开启、熔断兜底、输出脱敏落盘）；</li>
 *   <li>备份产生的「从备份恢复」补偿指令登记回滚账本（POST /api/ops/rollback/{traceId} 一键回滚）；</li>
 *   <li>全过程按 traceId 落 JSONL 溯源审计（/api/ops/trace/{traceId} 可查）。</li>
 * </ol>
 * 工具只负责把入参编译为固定 argv 步骤序列（参数经白名单清洗，不经 shell），
 * 门槛高低完全由护栏对命令的裁决决定，工具自身无权降级。
 */
@Component
public class GuardedMutationExecutor {
    private static final int MAX_PENDING = 200;
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    private final IntentRiskGuard guard;
    private final LeastPrivilegeExecutor executor;
    private final ExecProperties props;
    private final RollbackLedger ledger;
    private final OpsAuditService audit;
    private final PreChangeBackup preChangeBackup;
    private final Map<String, PendingMutation> pendingMutations = new ConcurrentHashMap<>();

    public GuardedMutationExecutor(IntentRiskGuard guard,
                                   LeastPrivilegeExecutor executor,
                                   ExecProperties props,
                                   RollbackLedger ledger,
                                   OpsAuditService audit) {
        this.guard = guard;
        this.executor = executor;
        this.props = props;
        this.ledger = ledger;
        this.audit = audit;
        this.preChangeBackup = new PreChangeBackup(props);
    }

    /** 无验证探针的执行。 */
    public Map<String, Object> execute(String toolName, List<List<String>> steps, boolean confirm) {
        return execute(toolName, steps, confirm, null, null);
    }

    public Map<String, Object> execute(String toolName, List<List<String>> steps,
                                       boolean confirm, String pendingMutationId) {
        return execute(toolName, steps, confirm, pendingMutationId, null);
    }

    /**
     * 护栏在环地执行一组变更步骤。
     * @param toolName   发起调用的 MCP 工具名（用于溯源与结果标注）
     * @param steps      argv 步骤序列（依序执行，某步真实失败则中止后续步骤）
     * @param confirm    调用方是否已二次确认
     * @param verifyArgv 可选的只读复核探针（全部步骤真实成功后执行，结果回填）
     */
    public Map<String, Object> execute(String toolName, List<List<String>> steps,
                                       boolean confirm, List<String> verifyArgv) {
        return execute(toolName, steps, confirm, null, verifyArgv);
    }

    /**
     * 护栏在环地执行一组变更步骤。
     * confirm=true 不能首轮直达执行，必须携带上一轮 REVIEW_PENDING 返回的 pendingMutationId，
     * 且工具名与 argv 签名必须完全匹配。
     */
    public Map<String, Object> execute(String toolName, List<List<String>> steps,
                                       boolean confirm, String pendingMutationId,
                                       List<String> verifyArgv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName);

        // 1. 护栏逐条裁决，取最高风险
        List<Map<String, Object>> decisionViews = new ArrayList<>();
        RiskLevel worst = RiskLevel.READONLY;
        for (List<String> argv : steps) {
            String command = String.join(" ", argv);
            RiskDecision d = guard.evaluate(command);
            worst = RiskLevel.max(worst, d.level());
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("command", command);
            dv.put("level", d.level().name());
            dv.put("reason", d.reason());
            decisionViews.add(dv);
        }
        out.put("decisions", decisionViews);
        out.put("riskLevel", worst.name());

        // 2. 红线：confirm 也无法越过
        if (worst == RiskLevel.BLOCK) {
            out.put("status", "BLOCKED");
            out.put("executed", false);
            out.put("message", "变更步骤命中安全红线，已拒绝执行（confirm 无法越过红线）");
            return out;
        }

        // 3. 二次确认门禁：门槛由护栏裁决决定，工具无权降级
        if (worst.requiresApproval() && !confirm) {
            return pendingReview(toolName, steps, out,
                    "变更操作需人工二次确认：请审阅以上风险裁决后携带 pendingMutationId 与 confirm=true 重新调用");
        }
        if (worst.requiresApproval()) {
            evictExpiredPending();
            String signature = signature(toolName, steps);
            PendingMutation pending = pendingMutationId == null ? null : pendingMutations.remove(pendingMutationId);
            if (pending == null || !pending.toolName().equals(toolName) || !pending.signature().equals(signature)) {
                return pendingReview(toolName, steps, out,
                        "confirm=true 需要匹配服务端已登记的 pendingMutationId；本次请求未执行，已返回新的待确认 id");
            }
        }

        // 4. 溯源审计：MCP 变更与主链路同一套 trace 体系
        OpsTrace trace = audit.newTrace("[MCP:" + toolName + "] " + summarize(steps));
        String traceId = trace.getTraceId();
        out.put("traceId", traceId);

        boolean realMutation = !props.isDryRun();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> backups = new ArrayList<>();
        boolean allSuccess = true;

        for (List<String> argv : steps) {
            String command = String.join(" ", argv);
            long start = System.currentTimeMillis();
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("command", command);

            // 执行前自动备份：仅真实变更需要（dry-run 不落盘也无需恢复点）
            if (realMutation) {
                List<Map<String, Object>> b = preChangeBackup.backup(traceId, argv);
                if (!b.isEmpty()) {
                    sr.put("preBackup", b);
                    backups.addAll(b);
                }
            }

            ExecResult res = executor.run(argv);
            sr.put("executed", true);
            sr.put("exitCode", res.exitCode());
            sr.put("dryRun", res.dryRun());
            String output = res.stdout();
            if (res.stderr() != null && !res.stderr().isBlank()) {
                output = output + "\n[stderr] " + res.stderr();
            }
            sr.put("output", output);
            stepResults.add(sr);

            audit.appendStep(traceId, new AgentStep(
                    "MCP_MUTATION", toolName,
                    Map.of("command", command),
                    new LinkedHashMap<>(sr),
                    null, null, System.currentTimeMillis() - start, null, null,
                    res.success() ? "ok" : "error"));

            if (!res.dryRun() && !res.success()) {
                allSuccess = false;
                sr.put("abortedRemaining", "该步骤真实执行失败，后续步骤已中止");
                break;
            }
        }
        out.put("steps", stepResults);

        // 5. 回滚账本：备份产生的补偿指令可经 /api/ops/rollback/{traceId} 一键回放
        List<Map<String, Object>> ledgerEntries = restoreEntries(backups);
        if (!ledgerEntries.isEmpty()) {
            ledger.record(traceId, ledgerEntries);
            Map<String, Object> rb = new LinkedHashMap<>();
            rb.put("available", true);
            rb.put("entries", ledgerEntries);
            rb.put("endpoint", "POST /api/ops/rollback/" + traceId);
            out.put("rollback", rb);
        }

        // 6. 只读复核探针：全部步骤真实成功后验证变更效果
        if (verifyArgv != null && !verifyArgv.isEmpty() && realMutation && allSuccess) {
            ExecResult vres = executor.runReadOnly(verifyArgv);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("command", String.join(" ", verifyArgv));
            v.put("exitCode", vres.exitCode());
            v.put("output", vres.stdout());
            out.put("verify", v);
        }

        String status = allSuccess ? "EXECUTED" : "FAILED";
        out.put("status", status);
        out.put("executed", true);
        if (!realMutation) {
            out.put("message", "dry-run 演示模式：已通过护栏与确认门禁，未真实落盘变更（置 OPS_EXEC_DRY_RUN=false 生效）");
        }
        audit.complete(traceId, status);
        return out;
    }

    /** 从执行前备份记录生成「从备份恢复」补偿指令（与主链路 RollbackAdvisor 同一格式）。 */
    private List<Map<String, Object>> restoreEntries(List<Map<String, Object>> backups) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> record : backups) {
            Object origin = record.get("origin");
            Object backup = record.get("backup");
            if (origin == null || backup == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("origin", String.valueOf(origin));
            r.put("action", "restore-backup");
            r.put("reversible", true);
            r.put("compensate", "cp " + backup + " " + origin);
            r.put("note", "从执行前自动备份恢复原文件（备份：" + backup + "）");
            out.add(r);
        }
        return out;
    }

    private String summarize(List<List<String>> steps) {
        List<String> cmds = new ArrayList<>();
        for (List<String> argv : steps) {
            cmds.add(String.join(" ", argv));
        }
        return String.join(" && ", cmds);
    }

    private Map<String, Object> pendingReview(String toolName, List<List<String>> steps,
                                              Map<String, Object> out, String message) {
        evictExpiredPending();
        String id = UUID.randomUUID().toString();
        pendingMutations.put(id, new PendingMutation(toolName, signature(toolName, steps), System.currentTimeMillis()));
        out.put("status", "REVIEW_PENDING");
        out.put("executed", false);
        out.put("pendingMutationId", id);
        out.put("pendingExpiresInMs", PENDING_TTL_MS);
        out.put("message", message);
        return out;
    }

    private void evictExpiredPending() {
        long cutoff = System.currentTimeMillis() - PENDING_TTL_MS;
        pendingMutations.entrySet().removeIf(e -> e.getValue().createdAtMs() < cutoff);
        if (pendingMutations.size() <= MAX_PENDING) {
            return;
        }
        pendingMutations.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs())))
                .limit(Math.max(0, pendingMutations.size() - MAX_PENDING))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(pendingMutations::remove);
    }

    private String signature(String toolName, List<List<String>> steps) {
        List<String> parts = new ArrayList<>();
        parts.add(toolName == null ? "" : toolName);
        if (steps != null) {
            for (List<String> argv : steps) {
                parts.add(String.join("\u001F", argv));
            }
        }
        return String.join("\u001E", parts);
    }

    private record PendingMutation(String toolName, String signature, long createdAtMs) {}
}
