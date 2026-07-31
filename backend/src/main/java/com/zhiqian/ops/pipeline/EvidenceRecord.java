package com.zhiqian.ops.pipeline;

/**
 * 一条结构化的诊断证据：只存工具原始采集到的事实与元数据，不存用户指令原文、
 * 不存 LLM 上一轮的叙事性结论——这是跟 {@link SessionContext#cleanHistory}（跨轮攻击检测账本）
 * 完全独立的另一条通道，供多轮诊断追问复用，见设计文档「诊断证据继承」。
 */
public record EvidenceRecord(
        String tool,
        String target,
        long collectedAtMs,
        String traceId,
        String summary
) {}
