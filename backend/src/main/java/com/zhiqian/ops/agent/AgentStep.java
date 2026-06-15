package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * 推理链路上的单步记录（沿用原项目设计），是溯源审计的最小单元。
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
