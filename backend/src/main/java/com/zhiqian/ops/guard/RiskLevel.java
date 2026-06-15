package com.zhiqian.ops.guard;

/**
 * 风险等级。SAFE 可直接执行；REVIEW 需人工二次确认；BLOCK 命中红线，永不执行。
 */
public enum RiskLevel {
    SAFE,
    REVIEW,
    BLOCK
}
