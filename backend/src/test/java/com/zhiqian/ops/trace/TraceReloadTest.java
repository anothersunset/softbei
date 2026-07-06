package com.zhiqian.ops.trace;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 断点续跑：溯源记录落盘后，新实例（模拟重启）仍可按 traceId 查到重启前的 trace。 */
class TraceReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void completed_trace_survives_restart_and_is_queryable() {
        Path file = tempDir.resolve("ops-trace.jsonl");

        OpsAuditService before = new OpsAuditService(file.toString());
        OpsTrace t = before.newTrace("排查磁盘告警");
        before.appendStep(t.getTraceId(), new AgentStep(
                "SENSE", "EnvironmentSensor", Map.of(), Map.of("toolsRun", 3),
                null, null, 12L, null, null, "ok"));
        before.complete(t.getTraceId(), "EXECUTED");

        // 模拟重启：同一落盘文件构造新实例
        OpsAuditService after = new OpsAuditService(file.toString());
        OpsTrace reloaded = after.get(t.getTraceId());
        assertNotNull(reloaded, "重启后应能查到该 trace");
        assertEquals("排查磁盘告警", reloaded.getInstruction());
        assertEquals("EXECUTED", reloaded.getFinalStatus());
        assertTrue(reloaded.getSteps().stream().anyMatch(s -> "SENSE".equals(s.stage())));
        assertTrue(after.recent(10).stream().anyMatch(x -> x.getTraceId().equals(t.getTraceId())));
    }

    @Test
    void missing_file_starts_empty_without_error() {
        OpsAuditService svc = new OpsAuditService(tempDir.resolve("nope.jsonl").toString());
        assertTrue(svc.recent(10).isEmpty());
    }

    @Test
    void trace_jsonl_redacts_secret_inside_pojo_output() throws Exception {
        Path file = tempDir.resolve("ops-trace-secret.jsonl");
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());
        OpsAuditService audit = new OpsAuditService(file.toString(), sanitizer);

        OpsTrace t = audit.newTrace("check service");
        audit.appendStep(t.getTraceId(), new AgentStep(
                "PLAN", "Planner", Map.of(), Map.of("plan", new SecretPojo("password=vm-secret-token")),
                null, null, 1L, null, null, "ok"));

        String jsonl = Files.readString(file);
        assertFalse(jsonl.contains("vm-secret-token"), "trace JSONL must not contain raw secret");
        assertTrue(jsonl.contains("password=***"));
    }

    private record SecretPojo(String detail) {}
}
