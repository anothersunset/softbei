package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * 推理链路中的一步（沿用原 智迁云枢 com.zhiqian.agent.AgentStep record 设计）。
 * 这正是赛题要求的「接收指令→感知环境→推理决策→安全校验→执行结果」闭环溯源的原子记录。
 */
public record AgentStep(
        String stage,
        String agentName,
        Map<String, Object> input,
        Map<String, Object> output,
        String model,
        Double confidence,
        Long elapsedMs,
        Integer tokenIn,
        Integer tokenOut,
        String status
) {}
