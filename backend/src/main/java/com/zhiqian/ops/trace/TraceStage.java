package com.zhiqian.ops.trace;

/**
 * 推理链路的闭环阶段：接收指令 -> 抗注入 -> 感知环境 -> 推理决策 -> 安全校验 -> 执行结果 -> 根因分析。
 */
public enum TraceStage {
    RECEIVE,
    INJECTION_GUARD,
    SENSE,
    REASON,
    GUARD,
    EXECUTE,
    ANALYZE
}
