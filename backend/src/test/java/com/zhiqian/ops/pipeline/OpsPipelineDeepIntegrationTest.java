package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentTool;
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
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.web.ApiSecurityProperties;
import com.zhiqian.ops.web.OpsAgentController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(9, pending.getSteps().size());
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
        ChatResponse executed = controller.chat(confirm).getData();

        assertEquals("EXECUTED", executed.getStatus());
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

    private OpsAgentController controller() throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setMaxStepsPerRequest(20);

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        OpsPipeline pipeline = new OpsPipeline(
                new AgentRunner(),
                new PromptInjectionDetector(rules),
                new IntentRiskGuard(rules),
                new MockLlmClient(),
                new RootCauseAnalyzer(),
                audit,
                executor,
                List.of(new FakeSenseTool()),
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer);

        return new OpsAgentController(pipeline, List.of(new FakeSenseTool()), new RollbackLedger(), executor,
                new MockLlmClient(), execProps, new ApiSecurityProperties(),
                new MockEnvironment().withProperty("server.address", "127.0.0.1"),
                sanitizer);
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
