package com.zhiqian.ops.llm;

/**
 * 模型发起的一次工具调用请求（OpenAI 兼容 tool_calls 语义）。
 * argumentsJson 为模型原样输出的 JSON 字符串，由调用方解析并校验。
 */
public record ToolCall(String id, String name, String argumentsJson) {}
