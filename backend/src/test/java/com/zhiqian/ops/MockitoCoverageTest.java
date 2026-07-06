package com.zhiqian.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.analyzer.CrossSourceRca;
import com.zhiqian.ops.analyzer.RcaInsight;
import com.zhiqian.ops.analyzer.RcaLlmSummarizer;
import com.zhiqian.ops.analyzer.RcaResult;
import com.zhiqian.ops.common.GlobalExceptionHandler;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.inspect.*;
import com.zhiqian.ops.llm.*;
import com.zhiqian.ops.mcp.McpController;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.retriever.Evidence;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import com.zhiqian.ops.web.InspectionController;
import com.zhiqian.ops.web.TraceController;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-based tests to cover Spring service/controller classes
 * that require dependency injection.
 */
public class MockitoCoverageTest {

    // ==================== ChatResponse POJO ====================
    @Nested
    class ChatResponseTests {
        @Test
        void allGettersSetters() {
            ChatResponse r = new ChatResponse();
            r.setTraceId("t1");
            assertEquals("t1", r.getTraceId());
            r.setStatus("OK");
            assertEquals("OK", r.getStatus());
            r.setMessage("msg");
            assertEquals("msg", r.getMessage());
            r.setAnalysis("ana");
            assertEquals("ana", r.getAnalysis());

            // Lists default to empty
            assertNotNull(r.getDecisions());
            assertNotNull(r.getExecResults());
            assertNotNull(r.getRetrieval());
            assertNotNull(r.getSteps());
            assertNotNull(r.getCounterfactual());
            assertNotNull(r.getRollbackPlan());

            // Set lists
            r.setDecisions(List.of());
            r.setExecResults(List.of(Map.of("k", "v")));
            r.setRetrieval(List.of());
            r.setSteps(List.of());
            r.setCounterfactual(List.of());
            r.setRollbackPlan(List.of(Map.of("a", 1)));
            assertEquals(1, r.getExecResults().size());
            assertEquals(1, r.getRollbackPlan().size());
        }
    }

    // ==================== LlmProperties ====================
    @Nested
    class LlmPropertiesTests {
        @Test
        void defaults() {
            LlmProperties p = new LlmProperties();
            assertEquals("mock", p.getProvider());
            assertEquals("https://api.deepseek.com", p.getBaseUrl());
            assertEquals("deepseek-chat", p.getModel());
            assertEquals("", p.getApiKey());
            assertEquals(60, p.getTimeoutSeconds());
        }

        @Test
        void setters() {
            LlmProperties p = new LlmProperties();
            p.setProvider("deepseek");
            assertEquals("deepseek", p.getProvider());
            p.setBaseUrl("https://custom.api");
            assertEquals("https://custom.api", p.getBaseUrl());
            p.setModel("qwen3");
            assertEquals("qwen3", p.getModel());
            p.setApiKey("sk-test");
            assertEquals("sk-test", p.getApiKey());
            p.setTimeoutSeconds(120);
            assertEquals(120, p.getTimeoutSeconds());
        }
    }

    // ==================== LlmClientFactory ====================
    @Nested
    class LlmClientFactoryTests {
        @Test
        void mockProvider_returnsMockClient() {
            LlmClientFactory factory = new LlmClientFactory();
            LlmProperties props = new LlmProperties();
            props.setProvider("mock");
            props.setApiKey("");
            LlmClient client = factory.llmClient(props);
            assertFalse(client.isReal());
            assertEquals("mock", client.providerName());
        }

        @Test
        void realProviderWithKey_returnsDeepSeekClient() {
            LlmClientFactory factory = new LlmClientFactory();
            LlmProperties props = new LlmProperties();
            props.setProvider("deepseek");
            props.setApiKey("sk-test123");
            LlmClient client = factory.llmClient(props);
            assertTrue(client.isReal());
            assertEquals("deepseek", client.providerName());
        }

        @Test
        void nullProvider_returnsMock() {
            LlmClientFactory factory = new LlmClientFactory();
            LlmProperties props = new LlmProperties();
            props.setProvider(null);
            props.setApiKey("sk-test");
            LlmClient client = factory.llmClient(props);
            assertFalse(client.isReal());
        }

        @Test
        void blankKey_returnsMock() {
            LlmClientFactory factory = new LlmClientFactory();
            LlmProperties props = new LlmProperties();
            props.setProvider("deepseek");
            props.setApiKey("   ");
            LlmClient client = factory.llmClient(props);
            assertFalse(client.isReal());
        }
    }

    // ==================== ToolRegistry ====================
    @Nested
    class ToolRegistryTests {
        @Test
        void registersAndRetrievesTools() {
            AgentTool t1 = mock(AgentTool.class);
            when(t1.name()).thenReturn("tool_a");
            AgentTool t2 = mock(AgentTool.class);
            when(t2.name()).thenReturn("tool_b");

            ToolRegistry registry = new ToolRegistry(List.of(t1, t2));
            assertEquals(2, registry.all().size());
            assertTrue(registry.has("tool_a"));
            assertTrue(registry.has("tool_b"));
            assertFalse(registry.has("tool_c"));
            assertSame(t1, registry.get("tool_a"));
            assertSame(t2, registry.get("tool_b"));
            assertNull(registry.get("nonexistent"));
        }

        @Test
        void emptyRegistry() {
            ToolRegistry registry = new ToolRegistry(List.of());
            assertEquals(0, registry.all().size());
            assertFalse(registry.has("anything"));
        }
    }

    // ==================== McpController ====================
    @Nested
    class McpControllerTests {
        @Test
        void rpcReturnsResponse() {
            McpDispatcher dispatcher = mock(McpDispatcher.class);
            McpController controller = new McpController(dispatcher);
            Map<String, Object> req = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
            Map<String, Object> resp = Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of());
            when(dispatcher.handle(req)).thenReturn(resp);

            ResponseEntity<Map<String, Object>> result = controller.rpc(req);
            assertEquals(HttpStatus.OK, result.getStatusCode());
            assertNotNull(result.getBody());
        }

        @Test
        void notificationReturns202() {
            McpDispatcher dispatcher = mock(McpDispatcher.class);
            McpController controller = new McpController(dispatcher);
            Map<String, Object> req = Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
            when(dispatcher.handle(req)).thenReturn(null);

            ResponseEntity<Map<String, Object>> result = controller.rpc(req);
            assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
            assertNull(result.getBody());
        }
    }

    // ==================== TraceController ====================
    @Nested
    class TraceControllerTests {
        @Test
        void traceFound() {
            OpsAuditService audit = mock(OpsAuditService.class);
            TraceController controller = new TraceController(audit);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("abc");
            when(audit.get("abc")).thenReturn(trace);

            Result<OpsTrace> result = controller.trace("abc");
            assertEquals(0, result.getCode());
            assertEquals("abc", result.getData().getTraceId());
        }

        @Test
        void traceNotFound() {
            OpsAuditService audit = mock(OpsAuditService.class);
            TraceController controller = new TraceController(audit);
            when(audit.get("missing")).thenReturn(null);

            Result<OpsTrace> result = controller.trace("missing");
            assertEquals(404, result.getCode());
        }

        @Test
        void recentTraces() {
            OpsAuditService audit = mock(OpsAuditService.class);
            TraceController controller = new TraceController(audit);
            when(audit.recent(5)).thenReturn(List.of());

            Result<List<OpsTrace>> result = controller.traces(5);
            assertEquals(0, result.getCode());
            assertNotNull(result.getData());
        }
    }

    // ==================== InspectionController ====================
    @Nested
    class InspectionControllerTests {
        @Test
        void inspectGet() {
            InspectionService svc = mock(InspectionService.class);
            CrossSourceRca rca = mock(CrossSourceRca.class);
            RcaLlmSummarizer summarizer = mock(RcaLlmSummarizer.class);
            InspectionController ctrl = new InspectionController(svc, rca, summarizer);

            InspectionReport report = new InspectionReport(
                    "id1", "t1", "2026-01-01T00:00:00Z",
                    95, "HEALTHY", "ok", List.of(), List.of(), List.of(), 100);
            when(svc.inspect()).thenReturn(report);

            Result<InspectionReport> result = ctrl.inspectGet();
            assertEquals(0, result.getCode());
            assertEquals(95, result.getData().healthScore());
        }

        @Test
        void inspectPost() {
            InspectionService svc = mock(InspectionService.class);
            CrossSourceRca rca = mock(CrossSourceRca.class);
            RcaLlmSummarizer summarizer = mock(RcaLlmSummarizer.class);
            InspectionController ctrl = new InspectionController(svc, rca, summarizer);

            InspectionReport report = new InspectionReport(
                    "id2", "t2", "2026-01-01T00:00:00Z",
                    80, "WARNING", "warn", List.of(), List.of(), List.of(), 200);
            when(svc.inspect()).thenReturn(report);

            Result<InspectionReport> result = ctrl.inspectPost();
            assertEquals(0, result.getCode());
            assertEquals(80, result.getData().healthScore());
        }

        @Test
        void rcaWithLlmSummary() {
            InspectionService svc = mock(InspectionService.class);
            CrossSourceRca rca = mock(CrossSourceRca.class);
            RcaLlmSummarizer summarizer = mock(RcaLlmSummarizer.class);
            InspectionController ctrl = new InspectionController(svc, rca, summarizer);

            InspectionReport report = new InspectionReport(
                    "id3", "t3", "2026-01-01T00:00:00Z",
                    60, "CRITICAL", "crit", List.of(), List.of(), List.of(), 300);
            RcaResult rcaResult = new RcaResult("id3", "t3", "L2", "summary", List.of());
            when(svc.inspect()).thenReturn(report);
            when(rca.analyze(report)).thenReturn(rcaResult);
            when(summarizer.enabled()).thenReturn(true);
            when(summarizer.summarize(report, rcaResult)).thenReturn("LLM root cause");
            when(summarizer.providerName()).thenReturn("deepseek");

            Result<Map<String, Object>> result = ctrl.rcaGet();
            assertEquals(0, result.getCode());
            Map<String, Object> data = result.getData();
            assertTrue((Boolean) data.get("llmEnabled"));
            assertEquals("LLM root cause", data.get("llmSummary"));
            assertEquals("deepseek", data.get("llmProvider"));
        }

        @Test
        void rcaFallsBackWhenRealLlmSummaryIsEmpty() {
            InspectionService svc = mock(InspectionService.class);
            CrossSourceRca rca = mock(CrossSourceRca.class);
            RcaLlmSummarizer summarizer = mock(RcaLlmSummarizer.class);
            InspectionController ctrl = new InspectionController(svc, rca, summarizer);

            InspectionReport report = new InspectionReport(
                    "id3b", "t3b", "2026-01-01T00:00:00Z",
                    55, "CRITICAL", "crit", List.of(), List.of(), List.of(), 300);
            RcaResult rcaResult = new RcaResult("id3b", "t3b", "L2", "summary", List.of());
            when(svc.inspect()).thenReturn(report);
            when(rca.analyze(report)).thenReturn(rcaResult);
            when(summarizer.enabled()).thenReturn(true);
            when(summarizer.summarize(report, rcaResult)).thenReturn(null);
            when(summarizer.providerName()).thenReturn("deepseek");

            Result<Map<String, Object>> result = ctrl.rcaGet();
            Map<String, Object> data = result.getData();
            assertEquals(0, result.getCode());
            assertTrue((Boolean) data.get("llmEnabled"));
            assertEquals("deepseek", data.get("llmProvider"));
            assertEquals(true, data.get("llmSummaryDegraded"));
            assertTrue(String.valueOf(data.get("llmSummary")).length() > 0);
        }

        @Test
        void rcaWithoutLlmSummary() {
            InspectionService svc = mock(InspectionService.class);
            CrossSourceRca rca = mock(CrossSourceRca.class);
            RcaLlmSummarizer summarizer = mock(RcaLlmSummarizer.class);
            InspectionController ctrl = new InspectionController(svc, rca, summarizer);

            InspectionReport report = new InspectionReport(
                    "id4", "t4", "2026-01-01T00:00:00Z",
                    95, "HEALTHY", "ok", List.of(), List.of(), List.of(), 100);
            RcaResult rcaResult = new RcaResult("id4", "t4", "L1", "ok", List.of());
            when(svc.inspect()).thenReturn(report);
            when(rca.analyze(report)).thenReturn(rcaResult);
            when(summarizer.enabled()).thenReturn(false);

            Result<Map<String, Object>> result = ctrl.rcaPost();
            assertEquals(0, result.getCode());
            assertFalse((Boolean) result.getData().get("llmEnabled"));
            assertNull(result.getData().get("llmSummary"));
        }
    }

    // ==================== GlobalExceptionHandler ====================
    @Nested
    class GlobalExceptionHandlerTests {
        @Test
        void handleBadRequest() {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            Result<Void> r = handler.handleBadRequest(new IllegalArgumentException("bad param"));
            assertEquals(400, r.getCode());
            assertTrue(r.getMessage().contains("bad param"));
        }

        @Test
        void handleNoResourceFound() {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            Result<Void> r = handler.handleNotFound(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/missing"));
            assertEquals(404, r.getCode());
        }

        @Test
        void handleGenericException() {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            Result<Void> r = handler.handleException(new RuntimeException("oops"));
            assertEquals(500, r.getCode());
            assertTrue(r.getMessage().contains("oops"));
        }
    }

    // ==================== InspectionScheduler ====================
    @Nested
    class InspectionSchedulerTests {
        @Test
        void scheduledInspectSuccess() {
            InspectionService svc = mock(InspectionService.class);
            InspectionScheduler scheduler = new InspectionScheduler(svc);
            InspectionReport report = new InspectionReport(
                    "id", "t", "ts", 90, "HEALTHY", "ok", List.of(), List.of(), List.of(), 50);
            when(svc.inspect()).thenReturn(report);

            assertDoesNotThrow(scheduler::scheduledInspect);
            verify(svc).inspect();
        }

        @Test
        void scheduledInspectExceptionCaught() {
            InspectionService svc = mock(InspectionService.class);
            InspectionScheduler scheduler = new InspectionScheduler(svc);
            when(svc.inspect()).thenThrow(new RuntimeException("fail"));

            assertDoesNotThrow(scheduler::scheduledInspect);
        }
    }

    // ==================== RcaLlmSummarizer ====================
    @Nested
    class RcaLlmSummarizerTests {
        @Test
        void enabledWithRealLlm() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertTrue(s.enabled());
        }

        @Test
        void disabledWithMockLlm() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(false);
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertFalse(s.enabled());
        }

        @Test
        void disabledWithNullLlm() {
            RcaLlmSummarizer s = new RcaLlmSummarizer(null);
            assertFalse(s.enabled());
            assertEquals("mock", s.providerName());
        }

        @Test
        void providerName() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.providerName()).thenReturn("deepseek");
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertEquals("deepseek", s.providerName());
        }

        @Test
        void summarizeReturnsNullWhenDisabled() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(false);
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertNull(s.summarize(mock(InspectionReport.class), mock(RcaResult.class)));
        }

        @Test
        void summarizeReturnsNullWhenReportNull() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertNull(s.summarize(null, mock(RcaResult.class)));
        }

        @Test
        void summarizeReturnsNullWhenRcaNull() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);
            assertNull(s.summarize(mock(InspectionReport.class), null));
        }

        @Test
        void summarizeWithRealLlm() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            when(llm.chat(anyString())).thenReturn("root cause analysis result");
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);

            InspectionFinding finding = new InspectionFinding(
                    "disk", "disk", "WARN", "title", "metric", "80%", ">=70%", "evidence", "suggestion");
            InspectionReport report = new InspectionReport(
                    "id", "t", "ts", 70, "WARNING", "warn",
                    List.of(finding), List.of("df"), List.of(), 100);
            RcaResult rca = new RcaResult("id", "t", "L2", "summary", List.of());

            String result = s.summarize(report, rca);
            assertEquals("root cause analysis result", result);
        }

        @Test
        void summarizeBlankReturnsNull() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            when(llm.chat(anyString())).thenReturn("   ");
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);

            InspectionReport report = new InspectionReport(
                    "id", "t", "ts", 70, "WARNING", "warn", List.of(), List.of(), List.of(), 100);
            RcaResult rca = new RcaResult("id", "t", "L1", "ok", List.of());
            assertNull(s.summarize(report, rca));
        }

        @Test
        void summarizeExceptionReturnsNull() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            when(llm.chat(anyString())).thenThrow(new RuntimeException("LLM error"));
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);

            InspectionReport report = new InspectionReport(
                    "id", "t", "ts", 70, "WARNING", "warn", List.of(), List.of(), List.of(), 100);
            RcaResult rca = new RcaResult("id", "t", "L1", "ok", List.of());
            assertNull(s.summarize(report, rca));
        }

        @Test
        void summarizeBuildsPromptWithFindingsAndInsights() {
            LlmClient llm = mock(LlmClient.class);
            when(llm.isReal()).thenReturn(true);
            when(llm.chat(anyString())).thenReturn("summary text");
            RcaLlmSummarizer s = new RcaLlmSummarizer(llm);

            InspectionFinding okFinding = new InspectionFinding(
                    "f1", "disk", "OK", "disk ok", "metric", "50%", "<=80%", "evidence", "ok");
            InspectionFinding warnFinding = new InspectionFinding(
                    "f2", "mem", "WARN", "mem warn", "metric", "90%", ">=80%", "evidence", "suggestion");
            InspectionFinding critFinding = new InspectionFinding(
                    "f3", "log", "CRITICAL", "log crit", "metric", "500", ">=100", "evidence", "fix");

            List<LogEvent> logEvents = List.of(
                    new LogEvent("2026-01-01T00:00:00+0800", 1735689600000L, "OOM", "out of memory kill"));

            InspectionReport report = new InspectionReport(
                    "id", "t", "ts", 40, "CRITICAL", "critical",
                    List.of(okFinding, warnFinding, critFinding), List.of("df", "free"),
                    logEvents, 200);

            RcaInsight insight = new RcaInsight(
                    "L2", "memory", "OOM detected", "OOM correlates with high mem",
                    "memory leak", "check processes", "restart", 85, List.of("evidence chain"));
            RcaResult rca = new RcaResult("id", "t", "L2", "rca summary", List.of(insight));

            String result = s.summarize(report, rca);
            assertEquals("summary text", result);

            // Verify prompt was built (capture the argument)
            verify(llm).chat(argThat(prompt ->
                    prompt.contains("40") && prompt.contains("CRITICAL")
                            && prompt.contains("mem warn") && prompt.contains("L2")
                            && prompt.contains("OOM detected")));
        }
    }

    // ==================== InspectionService ====================
    @Nested
    class InspectionServiceTests {

        private InspectionProperties defaultProps() {
            InspectionProperties p = new InspectionProperties();
            p.setDiskWarnPercent(80);
            p.setDiskCriticalPercent(95);
            p.setMemWarnPercent(80);
            p.setMemCriticalPercent(95);
            p.setLoadWarnPerCore(2.0);
            p.setLoadCriticalPerCore(4.0);
            p.setZombieWarn(1);
            p.setZombieCritical(5);
            p.setLogErrorWarn(50);
            p.setLogErrorCritical(200);
            p.setLogWindowMinutes(60);
            return p;
        }

        private String dfOutput(int rootPct) {
            return "Filesystem     1K-blocks     Used Available Use% Mounted on\n"
                    + "/dev/vda1      41922560  " + (41922560 * rootPct / 100) + "  " + (41922560 * (100 - rootPct) / 100) + "  " + rootPct + "% /\n"
                    + "tmpfs            819200        0    819200   0% /dev/shm";
        }

        private String freeOutput(long totalKb, long usedKb, long availableKb) {
            return "              total        used        free      shared  buff/cache   available\n"
                    + "Mem:        " + totalKb + "     " + usedKb + "     " + (totalKb - usedKb - availableKb) + "      12345     " + availableKb + "     " + availableKb + "\n"
                    + "Swap:       2097148      12345    2084803";
        }

        @Test
        void healthySystem() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-1");
            when(audit.newTrace(anyString())).thenReturn(trace);

            // Mock command outputs for a healthy system
            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(40), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 30 days,  1:00,  1 user,  load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "Ss\nS\nS\nR\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "tcp   LISTEN  0  128  *:80  *:*\ntcp   LISTEN  0  128  *:443  *:*\n", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            assertEquals("HEALTHY", report.overall());
            assertTrue(report.healthScore() >= 90);
            assertEquals(6, report.findings().size());
            assertNotNull(report.traceId());
            assertNotNull(report.summary());
        }

        @Test
        void criticalDisk() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-2");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(98), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            assertEquals("CRITICAL", report.overall());
            assertTrue(report.healthScore() <= 75);
            assertTrue(report.summary().contains("CRITICAL"));
        }

        @Test
        void warningMemory() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-3");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 6500000, 1000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            assertEquals("WARNING", report.overall());
        }

        @Test
        void highLoad() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-4");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            // Load 10.0 on 2 cores = 5.0 per core > 4.0 critical
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 10.00, 8.00, 6.00", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            assertEquals("CRITICAL", report.overall());
        }

        @Test
        void zombieProcesses() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-5");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            // 3 zombie processes
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\nZ\nZ\nS\nZ\nR\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            assertEquals("WARNING", report.overall());
        }

        @Test
        void logEventsWithOOM() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            props.setLogErrorWarn(5); // Lower threshold for testing
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-6");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            // Journal with OOM, IO, DISK_FULL entries
            String journal = "2026-06-20T10:00:00+0800 host kernel: Out of memory: Killed process 1234\n"
                    + "2026-06-20T10:01:00+0800 host kernel: oom-kill:constraint=CONSTRAINT_NONE\n"
                    + "2026-06-20T10:02:00+0800 host kernel: I/O error, dev sda, sector 12345\n"
                    + "2026-06-20T10:03:00+0800 host kernel: ext4-fs error on sda1\n"
                    + "2026-06-20T10:04:00+0800 host kernel: No space left on device\n"
                    + "2026-06-20T10:05:00+0800 host systemd: some other error\n"
                    + "2026-06-20T10:06:00+0800 host kernel: xfs filesystem error\n";
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, journal, "", false, 50));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            // Should have OOM events detected
            assertFalse(report.recentLogEvents().isEmpty());
            long oomCount = report.recentLogEvents().stream().filter(e -> "OOM".equals(e.kind())).count();
            assertTrue(oomCount >= 1);
        }

        @Test
        void commandFailures() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-7");
            when(audit.newTrace(anyString())).thenReturn(trace);

            // All commands fail
            ExecResult fail = new ExecResult(1, "", "command not found", false, 5);
            when(executor.runReadOnly(anyList())).thenReturn(fail);

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            // Should be DEGRADED (over half UNKNOWN)
            assertEquals("DEGRADED", report.overall());
            assertTrue(report.healthScore() <= 100);
        }

        @Test
        void diskWithNoValidFilesystem() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-8");
            when(audit.newTrace(anyString())).thenReturn(trace);

            // df output with only tmpfs
            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, "Filesystem     1K-blocks  Used Available Use% Mounted on\ntmpfs            819200     0    819200   0% /dev/shm\n", "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "tcp   LISTEN  0  128  *:80  *:*\n", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            // Disk should be UNKNOWN since only tmpfs
            boolean hasUnknown = report.findings().stream().anyMatch(f -> "UNKNOWN".equals(f.severity()));
            assertTrue(hasUnknown);
        }

        @Test
        void memoryWithNoAvailableField() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-9");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            // free output with only 3 fields (no available)
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, "              total        used        free\nMem:        8000000     6500000     1500000\n", "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 0.50, 0.60, 0.55", "", false, 10));
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(0, "2", "", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            // Memory should still be computed (uses used field as fallback)
            Optional<InspectionFinding> memFinding = report.findings().stream()
                    .filter(f -> "mem-usage".equals(f.id())).findFirst();
            assertTrue(memFinding.isPresent());
        }

        @Test
        void loadWithZeroCores() {
            LeastPrivilegeExecutor executor = mock(LeastPrivilegeExecutor.class);
            InspectionProperties props = defaultProps();
            OpsAuditService audit = mock(OpsAuditService.class);
            OpsTrace trace = new OpsTrace();
            trace.setTraceId("trace-10");
            when(audit.newTrace(anyString())).thenReturn(trace);

            when(executor.runReadOnly(List.of("df", "-P")))
                    .thenReturn(new ExecResult(0, dfOutput(30), "", false, 10));
            when(executor.runReadOnly(List.of("free")))
                    .thenReturn(new ExecResult(0, freeOutput(8000000, 2000000, 5000000), "", false, 10));
            when(executor.runReadOnly(List.of("uptime")))
                    .thenReturn(new ExecResult(0, " 10:00:00 up 1 day, load average: 2.00, 1.00, 0.50", "", false, 10));
            // nproc fails -> defaults to 1 core
            when(executor.runReadOnly(List.of("nproc")))
                    .thenReturn(new ExecResult(1, "", "not found", false, 5));
            when(executor.runReadOnly(List.of("ps", "-eo", "stat=")))
                    .thenReturn(new ExecResult(0, "S\n", "", false, 10));
            when(executor.runReadOnly(List.of("ss", "-H", "-tuln")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));
            when(executor.runReadOnly(List.of("journalctl", "-p", "3", "-n", "200", "-o", "short-iso", "--no-pager")))
                    .thenReturn(new ExecResult(0, "", "", false, 10));

            InspectionService svc = new InspectionService(executor, props, audit);
            InspectionReport report = svc.inspect();

            // 2.0 per core (1 core) = 2.0, should be CRITICAL (>=4.0 is critical, >=2.0 is warn)
            // Actually 2.0 >= 2.0 (warn) so WARNING
            assertEquals("WARNING", report.overall());
        }
    }

    // ==================== DeepSeekLlmClient ====================
    @Nested
    class DeepSeekLlmClientTests {
        @Test
        void isRealAndProviderName() {
            LlmProperties props = new LlmProperties();
            props.setProvider("deepseek");
            props.setBaseUrl("https://api.test.com");
            props.setApiKey("sk-test");
            props.setTimeoutSeconds(10);
            DeepSeekLlmClient client = new DeepSeekLlmClient(props);
            assertTrue(client.isReal());
            assertEquals("deepseek", client.providerName());
        }

        @Test
        void chatWithInvalidUrlThrows() {
            LlmProperties props = new LlmProperties();
            props.setProvider("test");
            props.setBaseUrl("http://localhost:1"); // Unreachable
            props.setApiKey("sk-test");
            props.setTimeoutSeconds(2);
            DeepSeekLlmClient client = new DeepSeekLlmClient(props);

            assertThrows(RuntimeException.class, () -> client.chat("test prompt"));
        }
    }

    // ==================== InspectionProperties ====================
    @Nested
    class InspectionPropertiesFullTests {
        @Test
        void allGettersSetters() {
            InspectionProperties p = new InspectionProperties();
            p.setDiskWarnPercent(70);
            assertEquals(70, p.getDiskWarnPercent());
            p.setDiskCriticalPercent(90);
            assertEquals(90, p.getDiskCriticalPercent());
            p.setMemWarnPercent(75);
            assertEquals(75, p.getMemWarnPercent());
            p.setMemCriticalPercent(92);
            assertEquals(92, p.getMemCriticalPercent());
            p.setLoadWarnPerCore(1.5);
            assertEquals(1.5, p.getLoadWarnPerCore());
            p.setLoadCriticalPerCore(3.0);
            assertEquals(3.0, p.getLoadCriticalPerCore());
            p.setZombieWarn(2);
            assertEquals(2, p.getZombieWarn());
            p.setZombieCritical(10);
            assertEquals(10, p.getZombieCritical());
            p.setLogErrorWarn(30);
            assertEquals(30, p.getLogErrorWarn());
            p.setLogErrorCritical(100);
            assertEquals(100, p.getLogErrorCritical());
            p.setLogWindowMinutes(30);
            assertEquals(30, p.getLogWindowMinutes());
        }
    }

    // ==================== OpsAuditService ====================
    @Nested
    class OpsAuditServiceTests {
        @Test
        void traceLifecycle() {
            OpsAuditService audit = new OpsAuditService("/tmp/test-trace-" + System.nanoTime() + ".jsonl");
            OpsTrace t = audit.newTrace("test instruction");
            assertNotNull(t.getTraceId());
            assertEquals("RUNNING", t.getFinalStatus());
            assertEquals("test instruction", t.getInstruction());

            AgentStep step = new AgentStep("PLAN", "TestAgent",
                    Map.of("input", "val"), Map.of("output", "val"),
                    "mock", 0.9, 100L, 50, 30, "OK");
            audit.appendStep(t.getTraceId(), step);

            audit.complete(t.getTraceId(), "HEALTHY");
            OpsTrace retrieved = audit.get(t.getTraceId());
            assertNotNull(retrieved);
            assertEquals("HEALTHY", retrieved.getFinalStatus());
            assertEquals(1, retrieved.getSteps().size());

            List<OpsTrace> recent = audit.recent(10);
            assertFalse(recent.isEmpty());
        }
    }
}
