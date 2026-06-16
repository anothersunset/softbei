package com.zhiqian.ops.guard;

import java.util.List;

/**
 * 反事实回放：对被拦截/待确认命令「若放行会发生什么」的静态影响预估。
 * irreversibility 取值：NONE / LOW / MEDIUM / HIGH / CATASTROPHIC。
 */
public record ImpactEstimate(
        String command,
        RiskLevel level,
        String irreversibility,
        List<String> impacts,
        String worstCase,
        String rollbackHint
) {}
