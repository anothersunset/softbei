package com.zhiqian.ops.trace;

/**
 * 推理链路的闭环阶段：接收指令 -> 抗注入 -> 感知环境 -> 磁盘候选门禁 -> 知识检索 -> 推理决策
 * -> 任务规划 -> 安全校验 -> 跨轮攻击检测 -> 执行结果 -> 根因分析。
 */
public enum TraceStage {
    RECEIVE,
    INJECTION_GUARD,
    SENSE,
    DISK_CANDIDATE_GATE,
    RETRIEVE,
    REASON,
    PLAN,
    GUARD,
    CROSS_TURN_GUARD,
    EXECUTE,
    ANALYZE
}
