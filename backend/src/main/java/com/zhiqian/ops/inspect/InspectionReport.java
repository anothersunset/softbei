package com.zhiqian.ops.inspect;

import java.util.List;

/**
 * 一次主动巡检的健康报告（只读，不触发任何变更操作）。
 */
public record InspectionReport(
        String inspectId,
        String traceId,
        String timestamp,
        int healthScore,
        String overall,
        String summary,
        List<InspectionFinding> findings,
        List<String> sources,
        long elapsedMs
) {}
