package com.zhiqian.ops;

import com.zhiqian.ops.analyzer.RcaInsight;
import com.zhiqian.ops.analyzer.RcaResult;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.inspect.*;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.mcp.McpToolSpec;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.ExecRequest;
import com.zhiqian.ops.guard.*;
import com.zhiqian.ops.planner.OpsExecutionPlan;
import com.zhiqian.ops.planner.OpsTask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据类/POJO 覆盖率补充测试：实例化 record 和简单 POJO 以覆盖构造器、getter、setter。
 */
class DataClassesCoverageTest {

    // ── Result (common) ──
    @Test
    void result_ok_with_data() {
        Result<String> r = Result.ok("hello");
        assertEquals(0, r.getCode());
        assertEquals("ok", r.getMessage());
        assertEquals("hello", r.getData());
    }

    @Test
    void result_ok_no_data() {
        Result<String> r = Result.ok();
        assertEquals(0, r.getCode());
        assertNull(r.getData());
    }

    @Test
    void result_error() {
        Result<String> r = Result.error(500, "fail");
        assertEquals(500, r.getCode());
        assertEquals("fail", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void result_setters() {
        Result<String> r = new Result<>();
        r.setCode(201);
        r.setMessage("created");
        r.setData("data");
        assertEquals(201, r.getCode());
        assertEquals("created", r.getMessage());
        assertEquals("data", r.getData());
    }

    @Test
    void result_constructor() {
        Result<Integer> r = new Result<>(0, "ok", 42);
        assertEquals(42, r.getData());
    }

    // ── RcaResult / RcaInsight (analyzer) ──
    @Test
    void rcaResult_record() {
        var insight = new RcaInsight("L2", "disk", "High usage", "metric+log",
                "Disk full", "Clean logs", "auto", 80, List.of("e1"));
        var result = new RcaResult("i1", "t1", "L2", "summary", List.of(insight));
        assertEquals("i1", result.inspectId());
        assertEquals("t1", result.traceId());
        assertEquals("L2", result.overallLevel());
        assertEquals("summary", result.summary());
        assertEquals(1, result.insights().size());
    }

    @Test
    void rcaInsight_record() {
        var insight = new RcaInsight("L1", "cpu", "High CPU", "corr", "root",
                "rec", "manual", 90, List.of("e1", "e2"));
        assertEquals("L1", insight.level());
        assertEquals("cpu", insight.domain());
        assertEquals("High CPU", insight.title());
        assertEquals(90, insight.confidence());
        assertEquals(2, insight.evidenceChain().size());
    }

    // ── InspectionReport / InspectionFinding / LogEvent (inspect) ──
    @Test
    void inspectionReport_record() {
        var finding = new InspectionFinding("f1", "disk", "WARN", "Title",
                "metric", "80%", "90%", "evidence", "suggestion");
        var report = new InspectionReport("i1", "t1", "2026-01-01",
                75, "WARNING", "summary", List.of(finding), List.of("src1"),
                List.of(), 100L);
        assertEquals("i1", report.inspectId());
        assertEquals(75, report.healthScore());
        assertEquals("WARNING", report.overall());
        assertEquals(1, report.findings().size());
        assertEquals(100L, report.elapsedMs());
    }

    @Test
    void inspectionFinding_record() {
        var f = new InspectionFinding("f1", "mem", "CRITICAL", "High Memory",
                "mem%", "95%", "85%", "ev", "sug");
        assertEquals("f1", f.id());
        assertEquals("mem", f.category());
        assertEquals("CRITICAL", f.severity());
    }

    @Test
    void logEvent_record() {
        var le = new LogEvent("2026-01-01T00:00:00", 1735689600000L, "OOM", "Out of memory");
        assertEquals("2026-01-01T00:00:00", le.time());
        assertEquals(1735689600000L, le.epochMillis());
        assertEquals("OOM", le.kind());
        assertEquals("Out of memory", le.message());
    }

    // ── ChatRequest / ChatResponse (pipeline) ──
    @Test
    void chatRequest_pojo() {
        var req = new ChatRequest();
        req.setInstruction("hello");
        req.setConfirm(true);
        req.setTraceId("t1");
        assertEquals("hello", req.getInstruction());
        assertTrue(req.isConfirm());
        assertEquals("t1", req.getTraceId());
    }

    @Test
    void chatResponse_pojo() {
        var resp = new ChatResponse();
        resp.setTraceId("t1");
        resp.setStatus("EXECUTED");
        resp.setMessage("reply");
        var executionPlan = new OpsExecutionPlan();
        executionPlan.setExecutionMode("sequential-with-human-gates");
        resp.setExecutionPlan(executionPlan);
        assertEquals("t1", resp.getTraceId());
        assertEquals("EXECUTED", resp.getStatus());
        assertEquals("reply", resp.getMessage());
        assertEquals("sequential-with-human-gates", resp.getExecutionPlan().getExecutionMode());
    }

    @Test
    void opsExecutionPlan_pojo() {
        var task = new OpsTask("T1", "OBSERVE", "title", "objective");
        task.setCommands(List.of("df -h"));
        task.setCommandIndexes(List.of(0));
        task.setDependsOn(List.of("T0"));
        task.setEvidenceRefs(List.of("runbook"));
        task.setExpectedRisk("READONLY");
        task.setStatus("READY");
        task.setResultSummary("ok");

        var plan = new OpsExecutionPlan();
        plan.setStrategy("Plan-and-Execute");
        plan.setExecutionMode("sequential");
        plan.setSummary("summary");
        plan.setCommandCount(1);
        plan.setTasks(List.of(task));

        assertEquals("Plan-and-Execute", plan.getStrategy());
        assertEquals("sequential", plan.getExecutionMode());
        assertEquals("summary", plan.getSummary());
        assertEquals(1, plan.getCommandCount());
        assertEquals("T1", plan.getTasks().get(0).getId());
        assertEquals("OBSERVE", plan.getTasks().get(0).getPhase());
        assertEquals("READY", plan.getTasks().get(0).getStatus());
        assertEquals("ok", plan.getTasks().get(0).getResultSummary());
    }

    // ── McpToolSpec (mcp) ──
    @Test
    void mcpToolSpec_record() {
        var spec = new McpToolSpec("health_inspect", "desc", Map.of(), Map.of());
        assertEquals("health_inspect", spec.name());
        assertEquals("desc", spec.description());
    }

    // ── ExecResult / ExecRequest (exec) ──
    @Test
    void execResult_record() {
        var r = new ExecResult(0, "output", "err", false, 100L);
        assertTrue(r.success());
        assertFalse(r.dryRun());
        assertEquals(0, r.exitCode());
        assertEquals("output", r.stdout());
    }

    @Test
    void execRequest_record() {
        var r = new ExecRequest(List.of("df", "-h"), true, 30);
        assertEquals(2, r.argv().size());
        assertTrue(r.readOnly());
        assertEquals(30, r.timeoutSeconds());
    }

    // ── Guard records ──
    @Test
    void riskDecision_record() {
        var d = new RiskDecision("cmd", RiskLevel.READONLY, "reason", "rule");
        assertEquals("cmd", d.command());
        assertEquals(RiskLevel.READONLY, d.level());
    }

    @Test
    void riskLevel_enum_values() {
        assertEquals(4, RiskLevel.values().length);
        assertEquals(RiskLevel.READONLY, RiskLevel.valueOf("READONLY"));
        assertEquals(RiskLevel.EXECUTABLE, RiskLevel.valueOf("EXECUTABLE"));
        assertEquals(RiskLevel.BLOCK, RiskLevel.valueOf("BLOCK"));
    }

    @Test
    void securityScore_record() {
        var s = new SecurityScore(95, "A", 30, 35, 35, List.of("note"));
        assertEquals(95, s.score());
        assertEquals("A", s.grade());
        assertEquals(1, s.notes().size());
    }

    @Test
    void impactEstimate_record() {
        var ie = new ImpactEstimate("rm -rf /", RiskLevel.BLOCK, "CATASTROPHIC",
                List.of("impact"), "worst", "rollback");
        assertEquals("rm -rf /", ie.command());
        assertEquals("CATASTROPHIC", ie.irreversibility());
    }

    @Test
    void riskDecision_null_command() {
        var d = new RiskDecision(null, RiskLevel.BLOCK, "r", "rule");
        assertNull(d.command());
    }
}
