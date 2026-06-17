package com.zhiqian.ops.analyzer;

import java.util.List;

/**
 * 跨源根因分析结果：基于一次巡检报告，关联指标与日志多源信号，给出 L1-L3 分级处置。
 */
public record RcaResult(
        String inspectId,
        String traceId,
        String overallLevel,
        String summary,
        List<RcaInsight> insights
) {}
