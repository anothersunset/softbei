package com.zhiqian.ops.analyzer;

import com.zhiqian.ops.inspect.InspectionFinding;
import com.zhiqian.ops.inspect.InspectionProperties;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.inspect.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RCA 深度测试：验证时间窗口同源关联、L3 升级、网络/依赖/配置类故障分类。
 */
class CrossSourceRcaDepthTest {

    private final CrossSourceRca rca = new CrossSourceRca(new InspectionProperties());

    @Test
    void critical_disk_with_recent_disk_full_log_escalates_to_l3_with_evidence_chain() {
        long now = System.currentTimeMillis();
        InspectionReport report = report(
                List.of(
                        finding("disk-usage", "disk", "CRITICAL", "磁盘使用率", "96%", ">=80% 告警 / >=90% 严重"),
                        finding("mem-usage", "memory", "OK", "内存使用率", "42%", ">=85% 告警 / >=95% 严重"),
                        finding("log-errors", "log", "CRITICAL", "系统错误日志", "120", ">=20 告警 / >=100 严重")
                ),
                List.of(new LogEvent(Instant.ofEpochMilli(now).toString(), now, "DISK_FULL", "No space left on device"))
        );

        RcaResult result = rca.analyze(report);

        assertEquals("L3", result.overallLevel());
        assertTrue(result.summary().contains("L3"));
        assertTrue(result.insights().stream().anyMatch(i ->
                i.level().equals("L3")
                        && i.correlation().contains("时间窗口")
                        && i.evidenceChain().stream().anyMatch(e -> e.contains("DISK_FULL"))));
    }

    @Test
    void critical_load_with_dependency_and_network_logs_produces_l3_dependency_insight() {
        long now = System.currentTimeMillis();
        InspectionReport report = report(
                List.of(
                        finding("load", "load", "CRITICAL", "系统负载", "2.80", ">=1.0 告警 / >=2.0 严重"),
                        finding("log-errors", "log", "CRITICAL", "系统错误日志", "105", ">=20 告警 / >=100 严重")
                ),
                List.of(
                        new LogEvent(Instant.ofEpochMilli(now).toString(), now, "DEPENDENCY", "503 Service Unavailable from upstream"),
                        new LogEvent(Instant.ofEpochMilli(now - 1000).toString(), now - 1000, "NETWORK", "Connection timed out")
                )
        );

        RcaResult result = rca.analyze(report);

        assertEquals("L3", result.overallLevel());
        assertTrue(result.insights().get(0).rootCause().contains("突发流量")
                || result.insights().get(0).correlation().contains("依赖服务异常")
                || result.insights().get(0).correlation().contains("网络故障"));
    }

    @Test
    void config_drift_logs_without_resource_metric_stay_l2_and_keep_boundary_honest() {
        long now = System.currentTimeMillis();
        InspectionReport report = report(
                List.of(
                        finding("disk-usage", "disk", "OK", "磁盘使用率", "52%", ">=80% 告警 / >=90% 严重"),
                        finding("mem-usage", "memory", "OK", "内存使用率", "48%", ">=85% 告警 / >=95% 严重"),
                        finding("log-errors", "log", "CRITICAL", "系统错误日志", "130", ">=20 告警 / >=100 严重")
                ),
                List.of(new LogEvent(Instant.ofEpochMilli(now).toString(), now, "CONFIG", "invalid configuration syntax after deploy"))
        );

        RcaResult result = rca.analyze(report);

        assertEquals("L2", result.overallLevel());
        assertTrue(result.summary().contains("指标与日志均有告警级信号")
                || result.insights().get(0).rootCause().contains("应用或依赖服务异常"));
    }

    private InspectionReport report(List<InspectionFinding> findings, List<LogEvent> events) {
        return new InspectionReport(
                "inspect-1",
                "trace-1",
                Instant.now().toString(),
                50,
                "CRITICAL",
                "test report",
                findings,
                List.of("unit-test"),
                events,
                1L);
    }

    private InspectionFinding finding(String id, String category, String severity, String title, String observed, String threshold) {
        return new InspectionFinding(
                id,
                category,
                severity,
                title,
                "metric",
                observed,
                threshold,
                "evidence-" + id,
                "suggestion-" + id);
    }
}
