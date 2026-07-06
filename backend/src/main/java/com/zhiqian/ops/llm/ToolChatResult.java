package com.zhiqian.ops.llm;

import java.util.List;

/**
 * 一轮带工具的对话结果：要么模型请求调用工具（toolCalls 非空），
 * 要么给出最终文本回答（content）。
 */
public record ToolChatResult(String content, List<ToolCall> toolCalls) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static ToolChatResult text(String content) {
        return new ToolChatResult(content, List.of());
    }
}
