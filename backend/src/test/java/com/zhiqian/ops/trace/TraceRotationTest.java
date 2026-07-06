package com.zhiqian.ops.trace;

import com.zhiqian.ops.agent.AgentStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 溯源 JSONL 轮转 + traceId 磁盘回扫：文件不无限增长，历史 trace 永远可查。 */
class TraceRotationTest {

    @TempDir
    Path tempDir;

    private OpsAuditService serviceWithTinyThreshold(Path file) {
        // 阈值参数会被夹紧到最小 1024 bytes，便于用少量写入触发轮转
        return new OpsAuditService(file.toString(), null, 1);
    }

    private String writeTrace(OpsAuditService svc, String instruction) {
        OpsTrace t = svc.newTrace(instruction);
        svc.appendStep(t.getTraceId(), new AgentStep(
                "SENSE", "EnvironmentSensor", Map.of(),
                Map.of("padding", "x".repeat(600)),
                null, null, 5L, null, null, "ok"));
        svc.complete(t.getTraceId(), "EXECUTED");
        return t.getTraceId();
    }

    @Test
    void active_file_rotates_when_over_threshold() throws Exception {
        Path file = tempDir.resolve("ops-trace.jsonl");
        OpsAuditService svc = serviceWithTinyThreshold(file);
        for (int i = 0; i < 5; i++) {
            writeTrace(svc, "trace-" + i);
        }
        List<Path> archives = archivesIn(tempDir);
        assertFalse(archives.isEmpty(), "超过阈值后应产生归档文件");
        assertTrue(Files.size(file) < 4096, "活动文件应被轮转控制在阈值附近，实际=" + Files.size(file));
    }

    @Test
    void archived_trace_is_still_queryable_via_disk_scan() throws Exception {
        Path file = tempDir.resolve("ops-trace.jsonl");
        OpsAuditService svc = serviceWithTinyThreshold(file);
        String firstId = writeTrace(svc, "最早的一条排查记录");
        for (int i = 0; i < 6; i++) {
            writeTrace(svc, "filler-" + i);
        }
        assertFalse(archivesIn(tempDir).isEmpty(), "前置条件：已发生轮转");

        // 模拟重启：新实例只预热当前活动文件，最早的 trace 只存在于归档中
        OpsAuditService restarted = serviceWithTinyThreshold(file);
        OpsTrace reloaded = restarted.get(firstId);
        assertNotNull(reloaded, "已轮转归档的 trace 应可经磁盘回扫查到");
        assertEquals("最早的一条排查记录", reloaded.getInstruction());
        assertEquals("EXECUTED", reloaded.getFinalStatus());
        assertFalse(reloaded.getSteps().isEmpty(), "回扫应重建步骤");

        // 回扫结果放回内存 LRU：二次查询不再付出扫描成本（行为上等价，仅验证可重复查询）
        assertNotNull(restarted.get(firstId));
    }

    @Test
    void scan_returns_null_for_unknown_trace() {
        OpsAuditService svc = serviceWithTinyThreshold(tempDir.resolve("ops-trace.jsonl"));
        writeTrace(svc, "any");
        assertNull(svc.get("no-such-trace-id"));
    }

    private List<Path> archivesIn(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("ops-trace-\\d+\\.jsonl")).toList();
        }
    }
}
