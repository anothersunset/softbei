package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDispatcherSanitizerTest {

    @Test
    void toolsCall_sanitizes_text_and_structured_content() throws Exception {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "secret_sense";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, Object> run(AgentContext ctx, Map<String, Object> args) {
                return Map.of(
                        "stdout", "password=plain-secret",
                        "nested", List.of(Map.of("stderr", "token: raw-token")));
            }
        };
        McpDispatcher dispatcher = new McpDispatcher(
                new ToolRegistry(List.of(tool)),
                new SensitiveDataSanitizer(new RiskRuleLoader()));

        Map<String, Object> response = dispatcher.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of("name", "secret_sense", "arguments", Map.of())));
        String rendered = String.valueOf(response);

        assertTrue(rendered.contains("password=***"));
        assertTrue(rendered.contains("token=***"));
        assertFalse(rendered.contains("plain-secret"));
        assertFalse(rendered.contains("raw-token"));
    }
}
