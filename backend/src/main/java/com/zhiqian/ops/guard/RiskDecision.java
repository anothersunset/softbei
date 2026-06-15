package com.zhiqian.ops.guard;

/**
 * 单条指令的风险裁决结果。
 */
public record RiskDecision(
        String command,
        RiskLevel level,
        String reason,
        String matchedRule
) {}
