package com.zhiqian.ops.llm;

import com.zhiqian.ops.mcp.McpToolSpec;

import java.util.List;

/**
 * 大模型推理客户端统一抽象。沿用原项目多实现 + 工厂选择的设计，
 * 便于在国产开源模型（DeepSeek / Qwen3）与本地 Mock 之间切换。
 */
public interface LlmClient {
    /** 是否为真实联网模型（非 Mock）。 */
    boolean isReal();

    /** 发送提示词，返回模型原始文本输出（期望为 PlanResult 的 JSON）。 */
    String chat(String prompt);

    /** 提供商名称，用于溯源记录。 */
    String providerName();

    /** 是否支持工具调用（function calling）。不支持时 ReAct 循环退化为一次性全量感知。 */
    default boolean supportsTools() {
        return false;
    }

    /**
     * 携带 MCP 工具定义的多轮对话（ReAct 循环的单步）：
     * 模型可返回 toolCalls 请求调用工具，或返回最终文本。
     * 默认实现降级为单轮 chat（取最后一条 user 消息），保证既有实现向后兼容。
     */
    default ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
        String lastUser = "";
        for (ChatMessage m : messages) {
            if ("user".equals(m.role())) {
                lastUser = m.content();
            }
        }
        return ToolChatResult.text(chat(lastUser));
    }
}
