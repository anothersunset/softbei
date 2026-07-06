package com.zhiqian.ops.inspect;

import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 预测性感知（感知成熟度 3 级）：磁盘/内存使用率趋势外推。 */
class PredictiveTrendTest {

    private LeastPrivilegeExecutor executorWithDisk(int firstPct, int secondPct) {
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        when(executor.runReadOnly(List.of("df", "-P")))
                .thenReturn(new ExecResult(0, dfOutput(firstPct), "", false, 10),
                        new ExecResult(0, dfOutput(secondPct), "", false, 10));
        when(executor.runReadOnly(List.of("free")))
                .thenReturn(new ExecResult(0,
                        "              total        used        free      shared  buff/cache   available\n"
                        + "Mem:        8000000     2000000     1000000       10000     5000000     5000000\n",
                        "", false, 10));
        when(executor.runReadOnly(List.of("uptime")))
                .thenReturn(new ExecResult(0, " up 1 day, load average: 0.10, 0.10, 0.10", "", false, 10));
        when(executor.runReadOnly(List.of("nproc")))
                .thenReturn(new ExecResult(0, "4", "", false, 5));
        when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                .thenReturn(new ExecResult(0, "S\nS\n", "", false, 10));
        when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                .thenReturn(new ExecResult(0, "", "", false, 10));
        when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                .thenReturn(new ExecResult(0, "", "", false, 10));
        return executor;
    }

    private InspectionService service(LeastPrivilegeExecutor executor) {
        OpsAuditService audit = mock(OpsAuditService.class);
        OpsTrace trace = new OpsTrace();
        trace.setTraceId("trace-trend");
        when(audit.newTrace(anyString())).thenReturn(trace);
        return new InspectionService(executor, new InspectionProperties(), audit);
    }

    private String dfOutput(int pct) {
        return "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
                + "/dev/vda1        41152736 " + (411527 * pct) + "  20000000      " + pct + "% /\n";
    }

    @Test
    void single_sample_produces_no_prediction() {
        InspectionService svc = service(executorWithDisk(80, 82));
        InspectionReport first = svc.inspect();
        assertTrue(first.predictions().isEmpty(), "首轮仅 1 个采样点，不应产生预测");
    }

    @Test
    void rising_disk_usage_predicts_exhaustion_with_warn() throws Exception {
        InspectionService svc = service(executorWithDisk(80, 82));
        svc.inspect();
        Thread.sleep(5); // 保证两个采样点时间跨度 > 0
        InspectionReport second = svc.inspect();

        TrendPrediction disk = second.predictions().stream()
                .filter(p -> "disk".equals(p.metric())).findFirst().orElseThrow();
        assertEquals(82, disk.currentPercent());
        assertTrue(disk.ratePerHour() > 0, "使用率上升，增速应为正");
        // 毫秒级间隔内 +2% → 外推小时级增速极快 → 24h 内写满 → WARN
        assertEquals("WARN", disk.severity());
        assertTrue(disk.projection().contains("写满"));
        assertTrue(second.summary().contains("【预测】"), "预测告警应进入巡检摘要");
    }

    @Test
    void stable_usage_predicts_no_risk() throws Exception {
        InspectionService svc = service(executorWithDisk(80, 80));
        svc.inspect();
        Thread.sleep(5); // 保证两个采样点时间跨度 > 0
        InspectionReport second = svc.inspect();

        TrendPrediction disk = second.predictions().stream()
                .filter(p -> "disk".equals(p.metric())).findFirst().orElseThrow();
        assertEquals("OK", disk.severity());
        assertTrue(disk.projection().contains("稳定或下降"));

        // 内存两轮相同 → 同样无风险
        TrendPrediction mem = second.predictions().stream()
                .filter(p -> "memory".equals(p.metric())).findFirst().orElseThrow();
        assertEquals("OK", mem.severity());
    }
}
