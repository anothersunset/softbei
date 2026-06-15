package com.zhiqian.ops.llm;

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
}
