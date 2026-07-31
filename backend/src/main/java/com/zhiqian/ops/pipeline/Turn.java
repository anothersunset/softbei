package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.guard.RiskLevel;

/**
 * 一轮「干净」对话的精简摘要，供跨轮攻击检测读取算分（风险升级对比、目标跳变对比）。
 * 只存派生的标量特征，不存原始指令文本——即便某轮文本含敏感串，也不会随会话历史回流；
 * 架构上也不供 LLM prompt 拼接使用（见设计文档「为什么 cleanHistory 绝不能喂给推理 LLM」）。
 */
public record Turn(String targetSlot, RiskLevel risk, String finalStatus) {}
