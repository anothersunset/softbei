package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentStep;
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
import com.zhiqian.ops.llm.ChatMessage;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.ToolCall;
import com.zhiqian.ops.llm.ToolChatResult;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.mcp.McpToolSpec;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 按需感知循环行为测试：用「支持工具调用的桩模型」证明真实模型路径下，
 * SENSE 阶段会自主选择只读感知工具、经 MCP 内部总线调用、透传参数，并在拿到证据后收敛。
 * 同时验证 Mock 路径不受影响（其 supportsTools=false，保持全量感知）。
 */
class ReActSenseTest {

    @TempDir
    Path tempDir;

    /** 支持工具调用的桩模型：第 1 轮请求调用 disk_probe(带 path 参数)，第 2 轮给出最终观察。 */
    static final class StubToolLlm implements LlmClient {
        final AtomicInteger toolTurns = new AtomicInteger();
        @Override public boolean isReal() { return true; }
        @Override public boolean supportsTools() { return true; }
        @Override public String providerName() { return "stub-tool"; }
        @Override public String chat(String prompt) {
            return "{\"summary\":\"日志膨胀导致磁盘占用高\",\"rootCauseHypothesis\":\"日志未轮转\","
                    + "\"confidence\":0.8,\"steps\":[{\"command\":\"df -h\",\"purpose\":\"确认分区\"}]}";
        }
        @Override public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
            if (toolTurns.getAndIncrement() == 0) {
                return new ToolChatResult("我需要先查看 /var/log 的磁盘占用",
                        List.of(new ToolCall("call-1", "disk_probe", "{\"path\":\"/var/log\"}")));
            }
            return ToolChatResult.text("已确认 /var/log 占用过高，初步判断为日志膨胀");
        }
    }

    /** 回显入参的假感知工具，用于断言 ReAct 循环把模型给的参数正确透传到工具。 */
    static final class EchoDiskTool implements AgentTool {
        @Override public String name() { return "disk_probe"; }
        @Override public String description() { return "只读探查指定目录磁盘占用"; }
        @Override public Map<String, Object> inputSchema() {
            Map<String, Object> path = new LinkedHashMap<>();
            path.put("type", "string");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", path);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            return schema;
        }
        @Override public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("probedPath", input.get("path"));
            r.put("usage", "/var/log 92% used");
            return r;
        }
    }

    private OpsPipeline pipeline(LlmClient llm) throws Exception {
        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(true);
        execProps.setWorkingDir(tempDir.toString());
        execProps.setMaxStepsPerRequest(20);
        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        List<AgentTool> tools = List.of(new EchoDiskTool());
        McpDispatcher dispatcher = new McpDispatcher(new ToolRegistry(tools), sanitizer);
        return new OpsPipeline(new AgentRunner(sanitizer), new PromptInjectionDetector(rules), guard, llm,
                new RootCauseAnalyzer(), audit, executor, tools,
                new ContextRetriever(audit, rules), execProps, sanitizer, dispatcher);
    }

    @Test
    void real_tool_model_runs_react_loop_and_passes_arguments_through() throws Exception {
        ChatRequest req = new ChatRequest();
        req.setInstruction("/var/log 磁盘满了，帮我看看");
        ChatResponse resp = pipeline(new StubToolLlm()).chat(req);

        AgentStep sense = resp.getSteps().stream()
                .filter(s -> "SENSE".equals(s.stage())).findFirst().orElse(null);
        assertNotNull(sense, "应存在 SENSE 步骤");
        assertEquals("react", sense.output().get("mode"), "真实+支持工具模型应走 ReAct 感知");

        Object rounds = sense.output().get("rounds");
        assertTrue(rounds instanceof List<?> && !((List<?>) rounds).isEmpty(), "应记录 ReAct 轮次");

        // 参数透传：观察内容里应出现被回显的 probedPath=/var/log
        String sensedDump = String.valueOf(sense.output().get("sensed"));
        assertTrue(sensedDump.contains("/var/log"), "工具参数应透传并在观察中体现，实际=" + sensedDump);
    }

    @Test
    void mock_style_model_keeps_full_sense() throws Exception {
        // supportsTools=false 的模型（默认）应保持全量感知，不触发 ReAct
        LlmClient nonTool = new LlmClient() {
            @Override public boolean isReal() { return true; }
            @Override public String providerName() { return "no-tool"; }
            @Override public String chat(String prompt) { return "{\"steps\":[]}"; }
        };
        ChatRequest req = new ChatRequest();
        req.setInstruction("看看系统状态");
        ChatResponse resp = pipeline(nonTool).chat(req);
        AgentStep sense = resp.getSteps().stream()
                .filter(s -> "SENSE".equals(s.stage())).findFirst().orElse(null);
        assertNotNull(sense);
        assertEquals("full", sense.output().get("mode"), "不支持工具的模型应走全量感知");
    }
}
