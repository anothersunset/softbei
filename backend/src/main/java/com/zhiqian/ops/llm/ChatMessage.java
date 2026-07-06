package com.zhiqian.ops.llm;

import java.util.List;

/**
 * 多轮对话消息（OpenAI 兼容 role 语义）：system / user / assistant / tool。
 * assistant 消息可携带 toolCalls；tool 消息以 toolCallId 关联所回应的调用。
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }
}
