package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实时文件活跃度差分采样工具：数学口径（增速/新增识别）用 mock 执行器精确验证；
 * 真实 find/lsof 在目标 Linux 环境的端到端行为由 collect-functional-evidence.py 的 IOACT 探针复核
 * （这台 Windows 开发机上 find 与系统自带 find.exe 冲突，见项目已知限制）。
 */
class IoActivitySenseToolTest {

    @TempDir
    Path tmp;

    private IoActivityProperties props() {
        IoActivityProperties p = new IoActivityProperties();
        p.setDefaultWindowMs(50);
        p.setMinWindowMs(10);
        p.setMaxWindowMs(200);
        p.setFindMaxDepth(3);
        p.setMaxTrackedFiles(5);
        p.setMaxLsofProbes(2);
        return p;
    }

    private LeastPrivilegeExecutor realExecutor() {
        ExecProperties execProps = new ExecProperties();
        execProps.setWorkingDir(tmp.toString());
        execProps.setOutputAuditDir(tmp.resolve("exec-output").toString());
        return new LeastPrivilegeExecutor(execProps, new CircuitBreaker());
    }

    @Test
    void schema_declares_path_and_window_bounds() {
        Map<String, Object> schema = new IoActivitySenseTool(realExecutor(), props()).inputSchema();
        String rendered = String.valueOf(schema);
        assertTrue(rendered.contains("windowMs"));
        assertTrue(rendered.contains("minimum=10"));
        assertTrue(rendered.contains("maximum=200"));
    }

    @Test
    void degrades_gracefully_when_find_unavailable() {
        // 本机（Windows）没有可用的 find/lsof，探测按设计静默降级为空表，不抛异常、不阻断主链路。
        IoActivitySenseTool tool = new IoActivitySenseTool(realExecutor(), props());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of());

        assertEquals("/var/log", r.get("path"));
        assertEquals(50, r.get("windowMs"));
        assertTrue(((List<?>) r.get("activeFiles")).isEmpty());
        assertTrue(((List<?>) r.get("shrinkingFiles")).isEmpty());
        assertTrue(((List<?>) r.get("newFiles")).isEmpty());
        assertNull(r.get("diskEta"));
    }

    @Test
    void non_absolute_path_falls_back_to_default() {
        IoActivitySenseTool tool = new IoActivitySenseTool(realExecutor(), props());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of("path", "relative/path"));
        assertEquals("/var/log", r.get("path"));
    }

    @Test
    void window_ms_is_clamped_to_configured_bounds() {
        IoActivitySenseTool tool = new IoActivitySenseTool(realExecutor(), props());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of("windowMs", 999999));
        assertEquals(200, r.get("windowMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detects_growth_new_file_and_writer_via_two_point_diff() {
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        String path = "/var/log";
        List<String> findArgv = List.of("find", path, "-maxdepth", "3", "-type", "f", "-printf", "%s\t%T@\t%p\n");

        when(executor.runReadOnly(eq(findArgv)))
                .thenReturn(new ExecResult(0, "1000\t1000.0\t/var/log/app.log\n", "", false, 5))
                .thenReturn(new ExecResult(0,
                        "3000\t1000.5\t/var/log/app.log\n200\t1000.5\t/var/log/new.log\n", "", false, 5));
        when(executor.runReadOnly(eq(List.of("lsof", "-F", "pcu", "/var/log/app.log"))))
                .thenReturn(new ExecResult(0, "p4321\ncjava\nuapp\n", "", false, 5));
        when(executor.runReadOnly(eq(List.of("df", "-B1", "--output=avail", path))))
                .thenReturn(new ExecResult(0, "Avail\n5000000\n", "", false, 5));

        IoActivitySenseTool tool = new IoActivitySenseTool(executor, props());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of());

        List<Map<String, Object>> active = (List<Map<String, Object>>) r.get("activeFiles");
        assertEquals(1, active.size());
        assertEquals("/var/log/app.log", active.get(0).get("path"));
        // (3000-1000) bytes / 0.05s window = 40000 B/s
        assertEquals(40000L, active.get(0).get("growthBytesPerSec"));

        List<Map<String, Object>> writers = (List<Map<String, Object>>) active.get(0).get("writers");
        assertEquals(1, writers.size());
        assertEquals("4321", writers.get(0).get("pid"));
        assertEquals("java", writers.get(0).get("comm"));
        assertEquals("app", writers.get(0).get("user"));

        List<Map<String, Object>> newFiles = (List<Map<String, Object>>) r.get("newFiles");
        assertEquals(1, newFiles.size());
        assertEquals("/var/log/new.log", newFiles.get(0).get("path"));

        assertTrue(((List<?>) r.get("shrinkingFiles")).isEmpty());
        assertNotNull(r.get("diskEta"));
        assertTrue(String.valueOf(r.get("diskEta")).startsWith(path));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shrinking_file_is_reported_without_writers() {
        LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
        String path = "/var/log";
        List<String> findArgv = List.of("find", path, "-maxdepth", "3", "-type", "f", "-printf", "%s\t%T@\t%p\n");

        when(executor.runReadOnly(eq(findArgv)))
                .thenReturn(new ExecResult(0, "5000\t1000.0\t/var/log/rotated.log\n", "", false, 5))
                .thenReturn(new ExecResult(0, "0\t1000.5\t/var/log/rotated.log\n", "", false, 5));
        when(executor.runReadOnly(eq(List.of("df", "-B1", "--output=avail", path))))
                .thenReturn(new ExecResult(0, "Avail\n5000000\n", "", false, 5));

        IoActivitySenseTool tool = new IoActivitySenseTool(executor, props());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of());

        assertTrue(((List<?>) r.get("activeFiles")).isEmpty());
        List<Map<String, Object>> shrinking = (List<Map<String, Object>>) r.get("shrinkingFiles");
        assertEquals(1, shrinking.size());
        assertEquals("/var/log/rotated.log", shrinking.get(0).get("path"));
        assertEquals(100000L, shrinking.get(0).get("shrinkBytesPerSec"));
        assertFalse(shrinking.get(0).containsKey("writers"));
        // 没有增长中的文件时不估算 ETA（totalGrowthBps<=0）
        assertNull(r.get("diskEta"));
    }
}
