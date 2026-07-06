package com.zhiqian.ops.inspect;

/**
 * 预测性感知结论（感知成熟度 3 级）：基于本进程内历史采样对资源耗尽做线性趋势外推。
 * severity：WARN=预计 24h 内耗尽；INFO=预计 7 天内耗尽；OK=稳定/下降或增长缓慢。
 */
public record TrendPrediction(
        String metric,
        int currentPercent,
        double ratePerHour,
        String severity,
        String projection,
        String basis
) {}
