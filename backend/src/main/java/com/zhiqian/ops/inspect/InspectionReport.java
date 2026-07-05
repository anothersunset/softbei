package com.zhiqian.ops.inspect;

import java.util.List;

/**
 * 一次主动巡检的健康报告（只读，不触发任何变更操作）。
 * recentLogEvents：本轮采集到的带时间戳/分类的错误日志事件，供跨源时间窗口关联使用。
 * predictions：预测性感知结论（磁盘/内存趋势外推），采样不足时为空列表。
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
        List<LogEvent> recentLogEvents,
        long elapsedMs,
        List<TrendPrediction> predictions
) {
    /** 兼容构造：无预测数据（既有调用点与测试不受影响）。 */
    public InspectionReport(String inspectId, String traceId, String timestamp,
                            int healthScore, String overall, String summary,
                            List<InspectionFinding> findings, List<String> sources,
                            List<LogEvent> recentLogEvents, long elapsedMs) {
        this(inspectId, traceId, timestamp, healthScore, overall, summary,
                findings, sources, recentLogEvents, elapsedMs, List.of());
    }
}
