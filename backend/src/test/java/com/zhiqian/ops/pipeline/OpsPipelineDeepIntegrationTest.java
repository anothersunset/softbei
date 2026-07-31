package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.MockLlmClient;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.web.ApiSecurityProperties;
import com.zhiqian.ops.web.OpsAgentController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 面向核心业务链路的深度集成测试：覆盖首轮 REVIEW、人工确认、dry-run 执行、
 * 回滚账本与 confirm 防伪造，而不是仅验证 DTO/getter。
 */
class OpsPipelineDeepIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void review_plan_requires_confirm_then_executes_same_cached_plan_and_records_rollback() throws Exception {
        OpsAgentController controller = controller();

        ChatRequest first = new ChatRequest();
        first.setInstruction("执行 systemctl restart nginx");
        ChatResponse pending = controller.chat(first).getData();

        assertEquals("REVIEW_PENDING", pending.getStatus());
        assertNotNull(pending.getTraceId());
        // enrich() 回填链路：controller 级测试才能验证到，pipeline 级测试碰不到这一层。
        assertNotNull(pending.getThreatAssessment());
        assertTrue(pending.getThreatAssessment().index() > 0);
        // 新增 CrossTurnGuardNode（GUARD 之后）与 DiskCandidateGateNode（SENSE 之后）后管线由 9 步变 11 步。
        assertEquals(11, pending.getSteps().size());
        assertTrue(pending.getSteps().stream().anyMatch(s -> "PLAN".equals(s.stage())));
        assertNotNull(pending.getExecutionPlan());
        assertEquals("sequential-with-human-gates", pending.getExecutionPlan().getExecutionMode());
        assertTrue(pending.getExecutionPlan().getTasks().stream().anyMatch(t -> "CHANGE".equals(t.getPhase())));
        assertTrue(pending.getDecisions().stream().anyMatch(d -> d.level().requiresApproval()));
        assertTrue(pending.getExecResults().stream().allMatch(r -> Boolean.FALSE.equals(r.get("executed"))));
        String pendingSteps = String.valueOf(pending.getSteps());
        assertTrue(pendingSteps.contains("token=***"));
        assertFalse(pendingSteps.contains("fake-token"));

        ChatRequest confirm = new ChatRequest();
        confirm.setInstruction("执行完全不同的命令也不应重新推理");
        confirm.setTraceId(pending.getTraceId());
        confirm.setConfirm(true);
        ChatResponse stillPending = controller.chat(confirm).getData();

        assertEquals("REVIEW_PENDING", stillPending.getStatus());
        assertEquals(pending.getTraceId(), stillPending.getTraceId());
        assertTrue(stillPending.getExecResults().stream().allMatch(r -> Boolean.FALSE.equals(r.get("executed"))));

        confirm.setHighImpactConfirmation(OpsPipeline.HIGH_IMPACT_CONFIRMATION);
        ChatResponse executed = controller.chat(confirm).getData();

        assertEquals("DRY_RUN_COMPLETED", executed.getStatus());
        assertEquals(pending.getTraceId(), executed.getTraceId());
        assertEquals("systemctl restart nginx", executed.getDecisions().get(0).command());
        assertEquals("systemctl restart nginx", executed.getExecResults().get(0).get("command"));
        assertEquals(Boolean.TRUE, executed.getExecResults().get(0).get("dryRun"));
        assertFalse(executed.getRollbackPlan().isEmpty());
        assertNotNull(executed.getExecutionPlan());
        assertTrue(executed.getExecutionPlan().getTasks().stream()
                .anyMatch(t -> "DRY_RUN_EXECUTED".equals(t.getStatus())));

        Map<String, Object> rollback = controller.rollback(executed.getTraceId()).getData();
        assertEquals(executed.getTraceId(), rollback.get("traceId"));
        assertFalse(((List<?>) rollback.get("results")).isEmpty());
    }

    @Test
    void forged_confirm_trace_does_not_bypass_review_gate() throws Exception {
        OpsAgentController controller = controller();

        ChatRequest forged = new ChatRequest();
        forged.setInstruction("执行 systemctl restart nginx");
        forged.setTraceId("forged-trace");
        forged.setConfirm(true);
        ChatResponse resp = controller.chat(forged).getData();

        assertEquals("REVIEW_PENDING", resp.getStatus());
        assertNotEquals("forged-trace", resp.getTraceId());
        assertTrue(resp.getExecResults().stream().allMatch(r -> Boolean.FALSE.equals(r.get("executed"))));
    }

    @Test
    void runtime_endpoint_makes_mock_and_dry_run_boundary_explicit() throws Exception {
        OpsAgentController controller = controller();

        Map<String, Object> runtime = controller.runtime().getData();

        assertEquals("mock", runtime.get("llmProvider"));
        assertEquals("MOCK", runtime.get("llmMode"));
        assertEquals(Boolean.FALSE, runtime.get("llmReal"));
        assertEquals(Boolean.TRUE, runtime.get("mockDeterministic"));
        assertEquals(Boolean.TRUE, runtime.get("dryRun"));
        assertEquals(20, runtime.get("maxStepsPerRequest"));
        assertEquals(Boolean.FALSE, runtime.get("apiTokenRequired"));
        assertEquals("127.0.0.1", runtime.get("bindAddress"));
    }

    /**
     * TOCTOU 复检回归：磁盘候选生成之后到真正执行删除之间，若文件被进程重新打开，
     * 执行前的复检必须发现并跳过这一条命令，而不是照旧删除。
     */
    @Test
    void reoccupied_file_is_skipped_at_execute_time_even_though_review_passed() throws Exception {
        String target = "/var/log/app/app.log.1";
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        // 复检发现「已被重新占用」：lsof 命中该路径，退出码 0 且有输出。
        when(executor.runReadOnly(List.of("lsof", target)))
                .thenReturn(new ExecResult(0, "java 4321 app w REG " + target, "", false, 5));
        // 其余只读探针（df/du 等）与验证探针一律放行，不是本测试关注点。
        when(executor.runReadOnly(argThat(argv -> argv != null && !argv.isEmpty() && !"lsof".equals(argv.get(0)))))
                .thenReturn(new ExecResult(0, "ok", "", false, 5));

        OpsAgentController controller = controllerWithExecutor(executor, false);

        ChatRequest first = new ChatRequest();
        first.setInstruction("磁盘快满了，帮我清理一下日志");
        ChatResponse pending = controller.chat(first).getData();
        assertEquals("REVIEW_PENDING", pending.getStatus());
        assertTrue(pending.getDecisions().stream().anyMatch(d -> d.command().equals("rm -f " + target)));

        ChatRequest confirm = new ChatRequest();
        confirm.setTraceId(pending.getTraceId());
        confirm.setConfirm(true);
        ChatResponse executed = controller.chat(confirm).getData();

        Map<String, Object> rmResult = executed.getExecResults().stream()
                .filter(r -> ("rm -f " + target).equals(r.get("command")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, rmResult.get("executed"));
        assertEquals(Boolean.TRUE, rmResult.get("skippedReoccupied"));
        verify(executor, never()).run(List.of("rm", "-f", target));
    }

    /** 对照组：候选/计划生成后文件始终未被重新占用时，复检不应误伤正常删除流程。 */
    @Test
    void unoccupied_file_still_executes_normally_after_recheck() throws Exception {
        String target = "/var/log/app/app.log.1";
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        // lsof 未命中（退出码非 0）：文件仍是「死」的，复检放行。
        when(executor.runReadOnly(List.of("lsof", target)))
                .thenReturn(new ExecResult(1, "", "", false, 5));
        when(executor.runReadOnly(argThat(argv -> argv != null && !argv.isEmpty() && !"lsof".equals(argv.get(0)))))
                .thenReturn(new ExecResult(1, "", "no such file", false, 5)); // stat 验证：文件应已删除 -> 非零
        when(executor.run(List.of("rm", "-f", target)))
                .thenReturn(new ExecResult(0, "removed", "", false, 8, "exec-test-1"));

        OpsAgentController controller = controllerWithExecutor(executor, false);

        ChatRequest first = new ChatRequest();
        first.setInstruction("磁盘快满了，帮我清理一下日志");
        ChatResponse pending = controller.chat(first).getData();
        assertEquals("REVIEW_PENDING", pending.getStatus());

        ChatRequest confirm = new ChatRequest();
        confirm.setTraceId(pending.getTraceId());
        confirm.setConfirm(true);
        ChatResponse executed = controller.chat(confirm).getData();

        Map<String, Object> rmResult = executed.getExecResults().stream()
                .filter(r -> ("rm -f " + target).equals(r.get("command")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, rmResult.get("executed"));
        assertNull(rmResult.get("skippedReoccupied"));
        verify(executor).run(List.of("rm", "-f", target));
    }

    /**
     * 磁盘候选表增长复检：RECOMMENDED 档头几个候选做两点差分采样，命中增长即降级为 CAUTION
     * 并在 reason 里写明增速与写入进程；未增长的候选保持原分类不变，不被牵连。
     */
    @Test
    @SuppressWarnings("unchecked")
    void growing_recommended_candidate_is_downgraded_to_caution_with_writer_in_reason() throws Exception {
        String growing = "/tmp/big1.log.gz";
        String stable = "/tmp/big2.log.gz";
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);

        // 候选门禁生成阶段：两个文件此刻都未被占用 -> 都先落 RECOMMENDED。
        when(executor.runReadOnly(List.of("lsof", growing))).thenReturn(new ExecResult(1, "", "", false, 5));
        when(executor.runReadOnly(List.of("lsof", stable))).thenReturn(new ExecResult(1, "", "", false, 5));
        // 增长复检阶段：growing 两次 stat 之间体量翻倍，stable 保持不变。
        when(executor.runReadOnly(List.of("stat", "-c%s", growing)))
                .thenReturn(new ExecResult(0, "1000\n", "", false, 5))
                .thenReturn(new ExecResult(0, "3000\n", "", false, 5));
        when(executor.runReadOnly(List.of("stat", "-c%s", stable)))
                .thenReturn(new ExecResult(0, "2000\n", "", false, 5))
                .thenReturn(new ExecResult(0, "2000\n", "", false, 5));
        when(executor.runReadOnly(List.of("lsof", "-F", "pcu", growing)))
                .thenReturn(new ExecResult(0, "p4321\ncjava\nuapp\n", "", false, 5));

        List<Map<String, Object>> topFiles = new ArrayList<>();
        topFiles.add(fileCandidateMap(growing, 100L * 1024 * 1024));
        topFiles.add(fileCandidateMap(stable, 100L * 1024 * 1024));

        OpsAgentController controller = controllerForDiskCandidateGrowth(executor, topFiles);

        ChatRequest req = new ChatRequest();
        req.setInstruction("磁盘快满了，帮我清理一下大文件");
        ChatResponse resp = controller.chat(req).getData();

        assertEquals("CLARIFICATION_REQUIRED", resp.getStatus());
        List<Map<String, Object>> candidates = resp.getCandidateTargets();
        Map<String, Object> growingEntry = candidates.stream()
                .filter(m -> growing.equals(m.get("path"))).findFirst().orElseThrow();
        Map<String, Object> stableEntry = candidates.stream()
                .filter(m -> stable.equals(m.get("path"))).findFirst().orElseThrow();

        assertEquals("CAUTION", growingEntry.get("tier"));
        assertEquals(Boolean.FALSE, growingEntry.get("recommended"));
        String reason = String.valueOf(growingEntry.get("reason"));
        assertTrue(reason.contains("增长"), reason);
        assertTrue(reason.contains("java/4321"), reason);

        assertEquals("RECOMMENDED", stableEntry.get("tier"));
        assertEquals(Boolean.TRUE, stableEntry.get("recommended"));
    }

    /**
     * grep 无匹配（exit=1）回归：只读诊断管道最后一段是 grep 系时，「没找到匹配行」是合法诊断结果
     * （如"日志里没有 error"），不能被误判为 EXECUTION_FAILED——否则一次干净的健康检查会被误报成失败。
     */
    @Test
    void readonly_pipe_ending_in_grep_no_match_is_not_execution_failure() throws Exception {
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        when(executor.runReadOnlyPipeline(List.of(
                List.of("journalctl", "-n", "50", "--no-pager"),
                List.of("grep", "-i", "error"))))
                .thenReturn(new ExecResult(1, "", "", false, 5)); // grep 未命中：exit=1，属正常「无匹配」

        OpsAgentController controller = controllerWithExecutor(executor, true);

        ChatRequest req = new ChatRequest();
        req.setInstruction("执行 journalctl -n 50 --no-pager | grep -i error");
        ChatResponse resp = controller.chat(req).getData();

        assertEquals("OBSERVED", resp.getStatus());
        Map<String, Object> result = resp.getExecResults().get(0);
        assertEquals(Boolean.TRUE, result.get("executed"));
        assertEquals(0, ((Number) result.get("exitCode")).intValue());
    }

    private Map<String, Object> fileCandidateMap(String path, long sizeBytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("sizeBytes", sizeBytes);
        m.put("mtimeEpochSeconds", 1_700_000_000L);
        return m;
    }

    private OpsAgentController controllerForDiskCandidateGrowth(
            LeastPrivilegeExecutor executor, List<Map<String, Object>> topFiles) throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setMaxStepsPerRequest(20);

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace-growth.jsonl").toString());
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        List<AgentTool> senseTools = List.of(new FakeSenseTool(), new FakeDiskSenseTool(topFiles));
        com.zhiqian.ops.agent.tools.IoActivityProperties ioActivityProps =
                new com.zhiqian.ops.agent.tools.IoActivityProperties();
        ioActivityProps.setCandidateProbeWindowMs(20); // 测试用短窗口，避免真实跑 3x2s

        OpsPipeline pipeline = new OpsPipeline(
                new AgentRunner(),
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
                new McpDispatcher(new ToolRegistry(senseTools), sanitizer),
                new com.zhiqian.ops.pipeline.SessionStore(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnThreatMonitor(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnProperties(),
                new com.zhiqian.ops.guard.DiskCandidateClassifier(rules),
                new com.zhiqian.ops.agent.tools.DiskCandidateProperties(),
                ioActivityProps);

        return new OpsAgentController(pipeline, senseTools, new RollbackLedger(), executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer, guard);
    }

    private static final class FakeDiskSenseTool implements AgentTool {
        private final List<Map<String, Object>> topFiles;

        FakeDiskSenseTool(List<Map<String, Object>> topFiles) {
            this.topFiles = topFiles;
        }

        @Override
        public String name() { return "disk_sense"; }

        @Override
        public String description() { return "fake disk sense for test"; }

        @Override
        public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
            return Map.of("topFiles", topFiles);
        }
    }

    private OpsAgentController controllerWithExecutor(LeastPrivilegeExecutor executor, boolean dryRun) throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(dryRun);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setMaxStepsPerRequest(20);

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace-mocked.jsonl").toString());
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        OpsPipeline pipeline = new OpsPipeline(
                new AgentRunner(),
                new PromptInjectionDetector(rules),
                guard,
                new MockLlmClient(),
                new RootCauseAnalyzer(),
                audit,
                executor,
                List.of(new FakeSenseTool()),
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer,
                new McpDispatcher(new ToolRegistry(List.of(new FakeSenseTool())), sanitizer),
                new com.zhiqian.ops.pipeline.SessionStore(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnThreatMonitor(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnProperties(),
                new com.zhiqian.ops.guard.DiskCandidateClassifier(rules),
                new com.zhiqian.ops.agent.tools.DiskCandidateProperties(),
                new com.zhiqian.ops.agent.tools.IoActivityProperties());

        return new OpsAgentController(pipeline, List.of(new FakeSenseTool()), new RollbackLedger(), executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer, guard);
    }

    private OpsAgentController controller() throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setMaxStepsPerRequest(20);

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        OpsPipeline pipeline = new OpsPipeline(
                new AgentRunner(),
                new PromptInjectionDetector(rules),
                guard,
                new MockLlmClient(),
                new RootCauseAnalyzer(),
                audit,
                executor,
                List.of(new FakeSenseTool()),
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer,
                new McpDispatcher(new ToolRegistry(List.of(new FakeSenseTool())), sanitizer),
                new com.zhiqian.ops.pipeline.SessionStore(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnThreatMonitor(new com.zhiqian.ops.guard.CrossTurnProperties()),
                new com.zhiqian.ops.guard.CrossTurnProperties(),
                new com.zhiqian.ops.guard.DiskCandidateClassifier(rules),
                new com.zhiqian.ops.agent.tools.DiskCandidateProperties(),
                new com.zhiqian.ops.agent.tools.IoActivityProperties());

        return new OpsAgentController(pipeline, List.of(new FakeSenseTool()), new RollbackLedger(), executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer, guard);
    }

    private static final class FakeSenseTool implements AgentTool {
        @Override
        public String name() {
            return "fake_system_sense";
        }

        @Override
        public String description() {
            return "deterministic test sense tool";
        }

        @Override
        public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
            return Map.of("load", "0.12", "disk", "42%", "config", "token: fake-token");
        }
    }
}
