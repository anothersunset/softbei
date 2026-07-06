package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.agent.tools.ConfigBackupTool;
import com.zhiqian.ops.agent.tools.LogRotateTool;
import com.zhiqian.ops.agent.tools.ServiceRestartTool;
import com.zhiqian.ops.agent.tools.SystemSenseTool;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.GuardedMutationExecutor;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 变更类 MCP 工具安全闭环测试：
 * 护栏裁决 -> confirm 门禁 -> dry-run/真实执行 -> 执行前备份 -> 回滚账本 -> 溯源审计。
 */
class McpMutatingToolsTest {

    @TempDir
    Path tmp;

    private ExecProperties props;
    private GuardedMutationExecutor guarded;
    private RollbackLedger ledger;
    private OpsAuditService audit;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        props = new ExecProperties();
        props.setDryRun(true);
        props.setWorkingDir(tmp.toString());
        props.setBackupDir(tmp.resolve("backups").toString());
        props.setOutputAuditDir(tmp.resolve("exec-output").toString());
        IntentRiskGuard guard = new IntentRiskGuard(new RiskRuleLoader());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker());
        ledger = new RollbackLedger();
        audit = new OpsAuditService(tmp.resolve("trace.jsonl").toString());
        guarded = new GuardedMutationExecutor(guard, executor, props, ledger, audit);

        ToolRegistry registry = new ToolRegistry(List.of(
                new SystemSenseTool(executor),
                new LogRotateTool(guarded),
                new ServiceRestartTool(guarded),
                new ConfigBackupTool(guarded)));
        dispatcher = new McpDispatcher(registry);
    }

    // ─── tools/list：注解由工具自声明 ───

    @Test
    @SuppressWarnings("unchecked")
    void toolsList_declares_mutating_annotations() {
        Map<String, Object> resp = dispatcher.handle(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        List<McpToolSpec> tools = (List<McpToolSpec>) result.get("tools");

        McpToolSpec sense = tools.stream().filter(t -> t.name().equals("system_sense")).findFirst().orElseThrow();
        assertEquals(true, sense.annotations().get("readOnlyHint"));
        assertEquals(false, sense.annotations().get("destructiveHint"));

        McpToolSpec rotate = tools.stream().filter(t -> t.name().equals("log_rotate")).findFirst().orElseThrow();
        assertEquals(false, rotate.annotations().get("readOnlyHint"));
        assertEquals(true, rotate.annotations().get("destructiveHint"));

        // config_backup 非破坏：readOnlyHint=false 但 destructiveHint=false
        McpToolSpec backup = tools.stream().filter(t -> t.name().equals("config_backup")).findFirst().orElseThrow();
        assertEquals(false, backup.annotations().get("readOnlyHint"));
        assertEquals(false, backup.annotations().get("destructiveHint"));
    }

    // ─── confirm 门禁 ───

    @Test
    void mutation_without_confirm_returns_review_pending() {
        Map<String, Object> sc = callTool("service_restart", Map.of("unit", "nginx"));
        assertEquals("REVIEW_PENDING", sc.get("status"));
        assertEquals(false, sc.get("executed"));
        // nginx 是核心服务：护栏应升级为 IRREVERSIBLE，工具无权降级
        assertEquals("IRREVERSIBLE", sc.get("riskLevel"));
        assertNull(sc.get("traceId"), "未确认不应产生执行 trace");
        assertNotNull(sc.get("pendingMutationId"), "未确认的变更应登记服务端 pending id");
    }

    @Test
    void first_call_confirm_true_does_not_execute_without_pending_id() {
        Map<String, Object> sc = callTool("service_restart", Map.of("unit", "myapp", "confirm", true));
        assertEquals("REVIEW_PENDING", sc.get("status"));
        assertEquals(false, sc.get("executed"));
        assertNull(sc.get("traceId"), "首轮 confirm=true 不能绕过服务端 pending 门禁");
        assertNotNull(sc.get("pendingMutationId"));
    }

    @Test
    void mutation_with_confirm_and_pending_id_executes_under_dryrun() {
        Map<String, Object> pending = callTool("service_restart", Map.of("unit", "myapp"));
        String pendingId = String.valueOf(pending.get("pendingMutationId"));

        Map<String, Object> sc = callTool("service_restart",
                Map.of("unit", "myapp", "confirm", true, "pendingMutationId", pendingId));
        assertEquals("EXECUTED", sc.get("status"));
        String traceId = String.valueOf(sc.get("traceId"));
        assertNotNull(audit.get(traceId), "MCP 变更应落溯源审计");
        assertEquals("EXECUTED", audit.get(traceId).getFinalStatus());
        List<?> steps = (List<?>) sc.get("steps");
        assertEquals(1, steps.size());
        Map<?, ?> step = (Map<?, ?>) steps.get(0);
        assertEquals(true, step.get("dryRun"), "默认 dry-run 不真实落盘");
    }

    @Test
    void pending_id_cannot_be_reused_for_different_arguments() {
        Map<String, Object> pending = callTool("service_restart", Map.of("unit", "myapp"));
        String pendingId = String.valueOf(pending.get("pendingMutationId"));

        Map<String, Object> sc = callTool("service_restart",
                Map.of("unit", "nginx", "confirm", true, "pendingMutationId", pendingId));
        assertEquals("REVIEW_PENDING", sc.get("status"));
        assertEquals(false, sc.get("executed"));
        assertNull(sc.get("traceId"));
        assertNotEquals(pendingId, String.valueOf(sc.get("pendingMutationId")));
    }

    // ─── 红线：confirm 无法越过 ───

    @Test
    void redline_cannot_be_confirmed_away() {
        Map<String, Object> out = guarded.execute("service_restart",
                List.of(List.of("systemctl", "stop", "firewalld")), true);
        assertEquals("BLOCKED", out.get("status"));
        assertEquals(false, out.get("executed"));
        assertNull(out.get("traceId"), "红线拦截不应产生执行 trace");
    }

    // ─── 参数注入防护与能力面收敛 ───

    @Test
    void unit_argument_is_sanitized_against_injection() {
        Map<String, Object> sc = callTool("service_restart",
                Map.of("unit", "nginx; rm -rf /", "confirm", true));
        String decisions = String.valueOf(sc.get("decisions"));
        assertFalse(decisions.contains(";"), "分号等元字符应被白名单清洗剔除");
        assertFalse(decisions.contains("rm -rf"), "注入的危险命令不应成为独立 argv");
    }

    @Test
    void log_rotate_rejects_non_log_paths() {
        Map<String, Object> sc = callTool("log_rotate", Map.of("path", "/etc/passwd", "confirm", true));
        assertEquals("REJECTED", sc.get("status"));
        assertEquals(false, sc.get("executed"));
    }

    @Test
    void log_rotate_confirmed_produces_archive_and_truncate_steps() {
        Map<String, Object> pending = callTool("log_rotate", Map.of("path", "/var/log/demo.log"));
        String pendingId = String.valueOf(pending.get("pendingMutationId"));
        Map<String, Object> sc = callTool("log_rotate",
                Map.of("path", "/var/log/demo.log", "confirm", true, "pendingMutationId", pendingId));
        assertEquals("EXECUTED", sc.get("status"));
        List<?> steps = (List<?>) sc.get("steps");
        assertEquals(2, steps.size(), "轮转 = 归档副本 + 截断原文件两步");
        assertTrue(String.valueOf(sc.get("archive")).startsWith("/var/log/demo.log."));
    }

    @Test
    void log_rotate_rejects_symbolic_links_in_live_mode() throws Exception {
        props.setDryRun(false);
        Path target = tmp.resolve("real-secret.log");
        Path link = tmp.resolve("fake.log");
        Files.writeString(target, "secret\n");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception unsupportedOnThisFs) {
            return;
        }

        Map<String, Object> out = new LogRotateTool(guarded, props)
                .run(new AgentContext(0L, 0L), Map.of("path", link.toString(), "confirm", true));

        assertEquals("REJECTED", out.get("status"));
        assertEquals("secret\n", Files.readString(target), "symlink target must not be truncated");
    }

    // ─── 真实执行：执行前备份 + 回滚账本 ───

    @Test
    void real_mutation_backs_up_target_and_records_rollback_ledger() throws Exception {
        props.setDryRun(false);
        Path target = tmp.resolve("app.conf");
        Files.writeString(target, "key=value\n");

        Map<String, Object> out = guarded.execute("config_backup",
                List.of(List.of("cp", "-p", target.toString(), target + ".bak-test")), false);
        String pendingId = String.valueOf(out.get("pendingMutationId"));

        out = guarded.execute("config_backup",
                List.of(List.of("cp", "-p", target.toString(), target + ".bak-test")), true, pendingId);

        String traceId = String.valueOf(out.get("traceId"));
        assertTrue(ledger.has(traceId), "真实变更应登记回滚账本");
        Map<String, Object> entry = ledger.get(traceId).get(0);
        assertEquals("restore-backup", entry.get("action"));
        assertEquals(Boolean.TRUE, entry.get("reversible"));
        String backupPath = String.valueOf(entry.get("compensate")).split("\\s+")[1];
        assertTrue(Files.exists(Path.of(backupPath)), "备份文件应真实存在：" + backupPath);
        assertEquals("key=value\n", Files.readString(Path.of(backupPath)));
    }

    // ─── 工具助手 ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String name, Map<String, Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args);
        Map<String, Object> resp = dispatcher.handle(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", params));
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertEquals(false, result.get("isError"), "确认门禁/风险裁决属业务结果而非协议错误");
        return (Map<String, Object>) result.get("structuredContent");
    }

    /** 直接构造 AgentContext 调用工具（绕过 MCP 层）时结果应与 MCP 路径一致。 */
    @Test
    void direct_tool_invocation_hits_same_gate() {
        ServiceRestartTool tool = new ServiceRestartTool(guarded);
        Map<String, Object> out = tool.run(new AgentContext(0L, 0L), Map.of("unit", "mysqld"));
        assertEquals("REVIEW_PENDING", out.get("status"));
        assertEquals("IRREVERSIBLE", out.get("riskLevel"));
    }
}
