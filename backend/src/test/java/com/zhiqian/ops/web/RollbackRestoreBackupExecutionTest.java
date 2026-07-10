package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.MockLlmClient;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.pipeline.OpsPipeline;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一键回滚必须真正执行 restore-backup 补偿命令（P1）：{@code cp <backup> <origin>}
 * 两端路径都是我们自己（PreChangeBackup / RollbackAdvisor）生成的受控路径，不是模型或用户输入，
 * 但此前 cp 因不在只读/变更白名单内被裁成 EXECUTABLE，一律要求人工确认，导致
 * "从备份恢复"这条补偿指令永远不会被 /api/ops/rollback/{traceId} 自动执行。
 */
class RollbackRestoreBackupExecutionTest {

    @TempDir
    Path tempDir;

    @Test
    void restore_backup_compensate_command_is_auto_executed() throws Exception {
        Path backup = tempDir.resolve("backup.bak");
        Path origin = tempDir.resolve("restored.txt");
        Files.writeString(backup, "original content");

        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(false); // 需要真实落地才能验证文件确实被恢复
        execProps.setWorkingDir(tempDir.toString());
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());

        List<AgentTool> senseTools = List.of();
        OpsPipeline pipeline = new OpsPipeline(
                new com.zhiqian.ops.agent.AgentRunner(),
                new PromptInjectionDetector(rules),
                guard,
                new MockLlmClient(),
                new RootCauseAnalyzer(),
                audit,
                executor,
                senseTools,
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer,
                new McpDispatcher(new ToolRegistry(senseTools), sanitizer));

        RollbackLedger ledger = new RollbackLedger();
        String traceId = "trace-restore-1";
        ledger.record(traceId, List.of(Map.of(
                "origin", "rm -f " + origin,
                "action", "restore-backup",
                "reversible", true,
                "compensate", "cp " + backup + " " + origin,
                "note", "从执行前自动备份恢复原文件")));

        OpsAgentController controller = new OpsAgentController(pipeline, senseTools, ledger, executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer, guard);

        Map<String, Object> out = controller.rollback(traceId).getData();
        List<?> results = (List<?>) out.get("results");
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) results.get(0);

        assertEquals(Boolean.TRUE, r.get("rolledBack"), "restore-backup 应被自动执行，而非卡在人工确认：" + r);
        assertTrue(Files.exists(origin), "cp 应已真实落地，恢复原文件");
        assertEquals("original content", Files.readString(origin));
    }

    @Test
    void non_restore_backup_executable_compensate_still_requires_approval() throws Exception {
        // 对照：非 restore-backup 的普通 EXECUTABLE 补偿命令（如语义逆操作）仍需人工确认，
        // 证明本次修复没有放宽一般补偿命令的审批闸门，只是给 restore-backup 单独开了口子。
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));

        RollbackLedger ledger = new RollbackLedger();
        String traceId = "trace-restore-2";
        ledger.record(traceId, List.of(Map.of(
                "origin", "systemctl stop nginx",
                "reversible", true,
                "compensate", "systemctl start nginx")));

        OpsAgentController controller = new OpsAgentController(null, List.of(), ledger, executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer, guard);

        Map<String, Object> out = controller.rollback(traceId).getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) ((List<?>) out.get("results")).get(0);
        assertEquals(Boolean.FALSE, r.get("rolledBack"));
        assertEquals(Boolean.TRUE, r.get("requiresApproval"));
    }
}
