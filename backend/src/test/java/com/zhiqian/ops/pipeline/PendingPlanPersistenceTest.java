package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.MockLlmClient;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续跑（官方失分点④）：REVIEW_PENDING 的待确认计划落盘后，
 * 服务重启（新 OpsPipeline 实例）仍可用原 traceId 确认执行「已被审阅的同一计划」。
 */
class PendingPlanPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void pending_plan_survives_restart_and_can_still_be_confirmed() throws Exception {
        Path stateDir = tempDir.resolve("state");

        // 第一段生命周期：产生待确认计划
        OpsPipeline first = pipeline(stateDir);
        ChatRequest req = new ChatRequest();
        req.setInstruction("执行 systemctl restart nginx");
        ChatResponse pending = first.chat(req);
        assertEquals("REVIEW_PENDING", pending.getStatus());
        assertTrue(Files.exists(stateDir.resolve("pending-plans.jsonl")));

        // 模拟重启：全新 OpsPipeline 实例（内存计划缓存为空，仅靠落盘恢复）
        OpsPipeline restarted = pipeline(stateDir);
        ChatRequest confirm = new ChatRequest();
        confirm.setInstruction("确认执行");
        confirm.setTraceId(pending.getTraceId());
        confirm.setConfirm(true);
        ChatResponse executed = restarted.chat(confirm);

        assertEquals("EXECUTED", executed.getStatus());
        assertEquals(pending.getTraceId(), executed.getTraceId());
        assertEquals("systemctl restart nginx", executed.getDecisions().get(0).command());

        // 确认消费后，落盘中的该计划应被清除：重放同一 traceId 不再命中缓存计划，
        // 而是按全新请求处理（分配新 traceId），不可重复确认
        OpsPipeline third = pipeline(stateDir);
        ChatRequest replay = new ChatRequest();
        replay.setInstruction("重复确认");
        replay.setTraceId(pending.getTraceId());
        replay.setConfirm(true);
        assertNotEquals(pending.getTraceId(), third.chat(replay).getTraceId());
    }

    private OpsPipeline pipeline(Path stateDir) throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setStateDir(stateDir.toString());

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        return new OpsPipeline(
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
                new McpDispatcher(new ToolRegistry(List.of(new FakeSenseTool())), sanitizer));
    }

    private static final class FakeSenseTool implements AgentTool {
        @Override
        public String name() { return "fake_system_sense"; }

        @Override
        public String description() { return "deterministic test sense tool"; }

        @Override
        public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
            return Map.of("ok", true);
        }
    }
}
