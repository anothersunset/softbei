package com.zhiqian.ops.guard;

import com.zhiqian.ops.llm.ChatMessage;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.MockLlmClient;
import com.zhiqian.ops.llm.ToolChatResult;
import com.zhiqian.ops.mcp.McpToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图一致性交叉校验器行为测试：
 *  - Mock（非真实）模型：一律视为一致（no-op），保证评测确定性；
 *  - 真实模型：按审计模型返回的 JSON 判定，检出不一致时 escalated=true。
 */
class IntentConsistencyCheckerTest {

    @Test
    void mock_model_is_always_consistent_noop() {
        IntentConsistencyChecker checker = new IntentConsistencyChecker(new MockLlmClient());
        var v = checker.check("清理磁盘日志", List.of("rm -rf /"));
        assertTrue(v.consistent(), "Mock 路径应视为一致(no-op)，不改变确定性");
        assertFalse(v.escalated());
    }

    @Test
    void real_model_flags_inconsistent_command() {
        // 桩审计模型：判定命令与诉求不一致
        LlmClient auditor = new LlmClient() {
            @Override public boolean isReal() { return true; }
            @Override public boolean supportsTools() { return true; }
            @Override public String providerName() { return "audit-stub"; }
            @Override public String chat(String prompt) { return "{}"; }
            @Override public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
                return ToolChatResult.text("{\"consistent\": false, \"concern\": \"用户只要求查看日志，命令却在删除数据目录\"}");
            }
        };
        IntentConsistencyChecker checker = new IntentConsistencyChecker(auditor);
        var v = checker.check("帮我看看最近的错误日志", List.of("rm -rf /var/lib/mysql"));
        assertFalse(v.consistent());
        assertTrue(v.escalated(), "检出意图偏差应升级");
        assertTrue(v.concern().contains("删除"));
    }

    @Test
    void real_model_passes_consistent_command() {
        LlmClient auditor = new LlmClient() {
            @Override public boolean isReal() { return true; }
            @Override public boolean supportsTools() { return true; }
            @Override public String providerName() { return "audit-stub"; }
            @Override public String chat(String prompt) { return "{}"; }
            @Override public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
                return ToolChatResult.text("{\"consistent\": true, \"concern\": \"\"}");
            }
        };
        IntentConsistencyChecker checker = new IntentConsistencyChecker(auditor);
        var v = checker.check("查看磁盘使用率", List.of("df -h"));
        assertTrue(v.consistent());
        assertFalse(v.escalated());
    }
}
