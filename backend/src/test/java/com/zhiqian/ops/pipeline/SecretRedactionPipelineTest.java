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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秘密脱敏(P0)：指令中的 password/token 等敏感片段不得出现在 API 响应任何字段
 * （尤其 executionPlan.summary 这类 POJO 字段）与 trace 落盘中。
 */
class SecretRedactionPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void secret_in_instruction_never_leaks_to_response_or_trace() throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        Path traceFile = tempDir.resolve("trace.jsonl");
        OpsAuditService audit = new OpsAuditService(traceFile.toString(), sanitizer);
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000), sanitizer);
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        OpsPipeline pipeline = new OpsPipeline(
                new AgentRunner(sanitizer), new PromptInjectionDetector(rules), guard,
                new MockLlmClient(), new RootCauseAnalyzer(), audit, executor,
                List.of(new FakeSenseTool()), new ContextRetriever(audit, rules),
                execProps, sanitizer,
                new McpDispatcher(new ToolRegistry(List.of(new FakeSenseTool())), sanitizer));

        ChatRequest req = new ChatRequest();
        req.setInstruction("帮我查看系统状态，password=vm-secret-token");
        ChatResponse resp = pipeline.chat(req);

        // 整个响应对象序列化后不得含明文秘密
        String respJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp);
        assertFalse(respJson.contains("vm-secret-token"), "响应中泄漏了明文秘密：" + respJson);
        assertTrue(respJson.contains("password=***"), "秘密应被遮蔽为 password=***");
        // executionPlan.summary 这个 POJO 字段是历史泄漏点，重点校验
        if (resp.getExecutionPlan() != null) {
            assertFalse(String.valueOf(resp.getExecutionPlan().getSummary()).contains("vm-secret-token"));
        }

        // trace 落盘同样不得泄漏
        String traceContent = Files.readString(traceFile);
        assertFalse(traceContent.contains("vm-secret-token"), "trace 落盘泄漏了明文秘密");
    }

    private static final class FakeSenseTool implements AgentTool {
        @Override public String name() { return "fake_system_sense"; }
        @Override public String description() { return "test sense tool"; }
        @Override public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) { return Map.of("ok", true); }
    }
}
