package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.ChatMessage;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.ToolChatResult;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.mcp.McpToolSpec;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 意图交叉校验升级必须真正阻止执行（P1）：只把聚合的 worstLevel 升级为 IRREVERSIBLE
 * 是不够的——ExecuteNode 按每条 RiskDecision 自己的 level 逐条放行，如果某条候选命令
 * 本身是 READONLY（如 cat /etc/passwd），意图偏差检出后它仍会在响应被判定为
 * REVIEW_PENDING 之前先执行完。本测试用一个"生成偏离诉求命令"的真实模型桩复现该场景，
 * 断言该命令确实被拦下、未执行。
 */
class IntentEscalationExecutionGateTest {

    @TempDir
    Path tempDir;

    /** 真实（isReal=true）模型桩：chat() 生成偏离诉求的只读命令，chatWithTools() 判定不一致。 */
    private static final class EscalatingLlmStub implements LlmClient {
        @Override public boolean isReal() { return true; }
        @Override public boolean supportsTools() { return false; }
        @Override public String providerName() { return "audit-stub"; }

        @Override
        public String chat(String prompt) {
            // 用户只问了系统状态，模型却"决定"去读 /etc/passwd —— 典型的意图偏差场景。
            return "{\"summary\":\"查看敏感文件\",\"rootCauseHypothesis\":\"none\",\"confidence\":0.9,"
                    + "\"steps\":[{\"command\":\"cat /etc/passwd\",\"purpose\":\"疑似越权读取\"}]}";
        }

        @Override
        public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
            return ToolChatResult.text(
                    "{\"consistent\": false, \"concern\": \"用户只问了系统状态，命令却读取 /etc/passwd\"}");
        }
    }

    @Test
    void escalated_readonly_command_is_gated_not_executed() throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());

        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        List<AgentTool> senseTools = List.of();
        OpsPipeline pipeline = new OpsPipeline(
                new com.zhiqian.ops.agent.AgentRunner(),
                new PromptInjectionDetector(rules),
                guard,
                new EscalatingLlmStub(),
                new RootCauseAnalyzer(),
                audit,
                executor,
                senseTools,
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer,
                new McpDispatcher(new ToolRegistry(senseTools), sanitizer));

        ChatRequest req = new ChatRequest();
        req.setInstruction("查看一下系统状态");
        ChatResponse resp = pipeline.chat(req);

        // 修复前：cat /etc/passwd 单独裁决为 READONLY，会在这里被直接执行，
        // 即使最终 status 被判成 REVIEW_PENDING，命令实际已经跑过了。
        assertEquals("REVIEW_PENDING", resp.getStatus());
        assertEquals(RiskLevel.IRREVERSIBLE, resp.getDecisions().get(0).level());
        Map<String, Object> execResult = resp.getExecResults().get(0);
        assertFalse(Boolean.TRUE.equals(execResult.get("executed")),
                "意图偏差被检出后，该命令不应被执行：" + execResult);
    }
}
