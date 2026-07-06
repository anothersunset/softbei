package com.zhiqian.ops;

import com.zhiqian.ops.llm.LlmProperties;
import com.zhiqian.ops.llm.PlanStep;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.common.GlobalExceptionHandler;
import com.zhiqian.ops.common.Result;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 补充覆盖率：LlmProperties、RollbackLedger、GlobalExceptionHandler。
 */
class MoreCoverageTest {

    // ── LlmProperties ──
    @Test
    void llmProperties_defaults() {
        LlmProperties p = new LlmProperties();
        assertEquals("mock", p.getProvider());
        assertEquals("https://api.deepseek.com", p.getBaseUrl());
        assertEquals("deepseek-chat", p.getModel());
        assertEquals("", p.getApiKey());
        assertEquals(60, p.getTimeoutSeconds());
    }

    @Test
    void llmProperties_setters() {
        LlmProperties p = new LlmProperties();
        p.setProvider("deepseek");
        assertEquals("deepseek", p.getProvider());
        p.setBaseUrl("https://custom.api");
        assertEquals("https://custom.api", p.getBaseUrl());
        p.setModel("deepseek-chat");
        assertEquals("deepseek-chat", p.getModel());
        p.setApiKey("sk-test");
        assertEquals("sk-test", p.getApiKey());
        p.setTimeoutSeconds(120);
        assertEquals(120, p.getTimeoutSeconds());
    }

    // ── RollbackLedger ──
    @Test
    void rollbackLedger_record_and_get() {
        RollbackLedger ledger = new RollbackLedger();
        var entry = List.of(Map.<String, Object>of("origin", "systemctl stop nginx", "compensate", "systemctl start nginx"));
        ledger.record("trace-1", entry);
        assertTrue(ledger.has("trace-1"));
        assertEquals(1, ledger.get("trace-1").size());
    }

    @Test
    void rollbackLedger_get_missing_returns_empty() {
        RollbackLedger ledger = new RollbackLedger();
        assertTrue(ledger.get("nonexistent").isEmpty());
    }

    @Test
    void rollbackLedger_has_missing() {
        RollbackLedger ledger = new RollbackLedger();
        assertFalse(ledger.has("nonexistent"));
    }

    @Test
    void rollbackLedger_null_traceId_ignored() {
        RollbackLedger ledger = new RollbackLedger();
        // ConcurrentHashMap 不允许 null key，record 方法会跳过
        ledger.record(null, List.of(Map.of("key", "val")));
        // has(null) 和 get(null) 在 ConcurrentHashMap 上会 NPE，
        // 但 record 应该跳过 null traceId（不会写入）
        // 验证：非 null key 不存在
        assertFalse(ledger.has("null"));
        assertTrue(ledger.get("null").isEmpty());
    }

    @Test
    void rollbackLedger_null_ledger_ignored() {
        RollbackLedger ledger = new RollbackLedger();
        ledger.record("t1", null);
        assertFalse(ledger.has("t1"));
    }

    @Test
    void rollbackLedger_empty_ledger_ignored() {
        RollbackLedger ledger = new RollbackLedger();
        ledger.record("t1", List.of());
        assertFalse(ledger.has("t1"));
    }

    @Test
    void rollbackLedger_overwrite() {
        RollbackLedger ledger = new RollbackLedger();
        ledger.record("t1", List.of(Map.<String, Object>of("a", 1)));
        ledger.record("t1", List.of(Map.<String, Object>of("b", 2), Map.<String, Object>of("c", 3)));
        assertEquals(2, ledger.get("t1").size());
    }

    // ── ExecProperties ──
    @Test
    void execProperties_defaults() {
        ExecProperties p = new ExecProperties();
        assertEquals("opsagent", p.getRunAsUser());
        assertFalse(p.isUseSudo());
        assertEquals(20, p.getTimeoutSeconds());
        assertEquals("/tmp", p.getWorkingDir());
        assertTrue(p.isDryRun());
        assertEquals(20, p.getMaxStepsPerRequest());
    }

    @Test
    void execProperties_setters() {
        ExecProperties p = new ExecProperties();
        p.setRunAsUser("admin");
        assertEquals("admin", p.getRunAsUser());
        p.setUseSudo(true);
        assertTrue(p.isUseSudo());
        p.setTimeoutSeconds(60);
        assertEquals(60, p.getTimeoutSeconds());
        p.setWorkingDir("/opt");
        assertEquals("/opt", p.getWorkingDir());
        p.setDryRun(false);
        assertFalse(p.isDryRun());
        p.setMaxStepsPerRequest(50);
        assertEquals(50, p.getMaxStepsPerRequest());
    }

    // ── AgentContext ──
    @Test
    void agentContext_constructor_and_accessors() {
        var ctx = new AgentContext(1L, 2L);
        assertEquals(1L, ctx.taskId());
        assertEquals(2L, ctx.projectId());
        assertNotNull(ctx.state());
        assertNotNull(ctx.memory());
        assertTrue(ctx.state().isEmpty());
        assertTrue(ctx.memory().isEmpty());
    }

    @Test
    void agentContext_state_and_memory_writable() {
        var ctx = new AgentContext(1L, 2L);
        ctx.state().put("traceId", "t1");
        ctx.memory().put("key", "val");
        assertEquals("t1", ctx.state().get("traceId"));
        assertEquals("val", ctx.memory().get("key"));
    }

    // ── AgentStep ──
    @Test
    void agentStep_record() {
        var step = new AgentStep("SENSE", "agent", Map.of(), Map.of(),
                "model", 0.9, 100L, 50, 30, "ok");
        assertEquals("SENSE", step.stage());
        assertEquals("agent", step.agentName());
        assertEquals(0.9, step.confidence());
        assertEquals(100L, step.elapsedMs());
    }

    // ── PlanStep ──
    @Test
    void planStep_pojo() {
        PlanStep step = new PlanStep();
        step.setCommand("df -h");
        step.setPurpose("check disk");
        assertEquals("df -h", step.getCommand());
        assertEquals("check disk", step.getPurpose());
    }

    @Test
    void planStep_constructor() {
        PlanStep step = new PlanStep("free -m", "check memory");
        assertEquals("free -m", step.getCommand());
        assertEquals("check memory", step.getPurpose());
    }

    // ── PlanResult ──
    @Test
    void planResult_pojo() {
        PlanResult result = new PlanResult();
        result.setSummary("disk full");
        result.setRootCauseHypothesis("log rotation failed");
        result.setConfidence(0.85);
        result.setSteps(List.of(new PlanStep("df -h", "check")));
        assertEquals("disk full", result.getSummary());
        assertEquals("log rotation failed", result.getRootCauseHypothesis());
        assertEquals(0.85, result.getConfidence());
        assertEquals(1, result.getSteps().size());
    }

    // ── GlobalExceptionHandler ──
    @Test
    void globalExceptionHandler_generic() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<Void> resp = handler.handleException(new RuntimeException("test error"));
        assertEquals(500, resp.getCode());
        assertTrue(resp.getMessage().contains("服务内部异常"));
    }

    @Test
    void globalExceptionHandler_illegal_argument() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<Void> resp = handler.handleBadRequest(new IllegalArgumentException("bad arg"));
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("bad arg"));
    }
}
