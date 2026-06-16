package com.zhiqian.ops.inspect;

/**
 * 单项巡检结论。severity: OK / WARN / CRITICAL / UNKNOWN。
 */
public record InspectionFinding(
        String id,
        String category,
        String severity,
        String title,
        String metric,
        String observed,
        String threshold,
        String evidence,
        String suggestion
) {}
