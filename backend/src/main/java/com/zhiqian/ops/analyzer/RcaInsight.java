package com.zhiqian.ops.analyzer;

import java.util.List;

/**
 * 单条跨源根因洞察。level 为 L1/L2/L3 分级处置等级。
 */
public record RcaInsight(
        String level,
        String domain,
        String title,
        String correlation,
        String rootCause,
        String recommendation,
        String disposition,
        int confidence,
        List<String> evidenceChain
) {}
