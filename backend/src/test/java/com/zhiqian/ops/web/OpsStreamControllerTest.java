package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖 /api/ops/chat/stream 的 SSE 接口：start/step/done 事件序列、
 * 与 REST 接口一致的 enrich()（安全评分/反事实回放/回滚账本）是否被执行。
 * <p>
 * 背景：static review 发现该接口——尽管在《云服务器验证报告》"已验证的 API 路径"
 * 中被列为已验证——没有任何自动化测试引用 OpsStreamController，属于覆盖盲区。
 * <p>
 * 用 standaloneSetup + mock(OpsPipeline) 隔离测试传输层/编排逻辑（enrich 部分），
 * 不依赖真实 LLM 或 Spring 全量上下文，运行快且稳定。
 */
class OpsStreamControllerTest {

    private OpsPipeline pipeline;
    private RollbackLedger rollbackLedger;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipeline = mock(OpsPipeline.class);
        rollbackLedger = mock(RollbackLedger.class);
        OpsStreamController controller = new OpsStreamController(pipeline, rollbackLedger);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ──────────────────────────────────────────────────────────────
    // start → step → done 事件顺序
    // ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void happy_path_emits_start_step_and_done_events_in_order() throws Exception {
        AgentStep step1 = new AgentStep("SENSE", "EnvironmentSensor",
                Map.of(), Map.of("df", "..."), "mock", 0.9, 12L, null, null, "OK");
        AgentStep step2 = new AgentStep("GUARD", "IntentRiskGuard",
                Map.of(), Map.of(), "mock", 0.95, 3L, null, null, "OK");
        ChatResponse resp = new ChatResponse();
        resp.setTraceId("trace-123");
        resp.setStatus("DONE");
        resp.setDecisions(List.of());
        resp.setExecResults(List.of());

        when(pipeline.chat(any(ChatRequest.class), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<AgentStep> listener = invocation.getArgument(1);
            listener.accept(step1);
            listener.accept(step2);
            return resp;
        });

        MvcResult mvcResult = mockMvc.perform(get("/api/ops/chat/stream")
                        .param("instruction", "查看磁盘使用情况")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter 在内部线程池异步完成；getAsyncResult() 会阻塞直到完成或超时
        mvcResult.getAsyncResult();

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event:start"), "应先发送 start 事件");
        assertTrue(body.contains("event:step"), "应逐步推送 step 事件");
        assertTrue(body.contains("event:done"), "应以 done 事件收尾");
        assertTrue(body.indexOf("event:start") < body.indexOf("event:step"),
                "start 应先于 step 发送");
        assertTrue(body.indexOf("event:step") < body.indexOf("event:done"),
                "step 应先于 done 发送");
        assertTrue(body.contains("trace-123"), "done 事件应携带 traceId");
    }

    // ──────────────────────────────────────────────────────────────
    // enrich() 一致性：SSE done 事件应包含安全评分等 enrich 字段
    // ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void done_event_carries_security_score_from_enrich_same_as_rest_endpoint() throws Exception {
        ChatResponse resp = new ChatResponse();
        resp.setTraceId("trace-enrich");
        resp.setStatus("DONE");
        resp.setDecisions(List.of());
        resp.setExecResults(List.of());

        when(pipeline.chat(any(ChatRequest.class), any(Consumer.class))).thenReturn(resp);

        MvcResult mvcResult = mockMvc.perform(get("/api/ops/chat/stream")
                        .param("instruction", "查看磁盘使用情况")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvcResult.getAsyncResult();

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(mvcResult))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("securityScore"), "done 事件应包含 enrich() 补充的安全评分字段");
        assertTrue(body.contains("counterfactual"), "done 事件应包含 enrich() 补充的反事实回放字段");
    }

    // ──────────────────────────────────────────────────────────────
    // 管线异常 → error 事件
    // ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void pipeline_exception_emits_error_event_instead_of_hanging_connection() throws Exception {
        when(pipeline.chat(any(ChatRequest.class), any(Consumer.class)))
                .thenThrow(new RuntimeException("模拟推理阶段异常"));

        MvcResult mvcResult = mockMvc.perform(get("/api/ops/chat/stream")
                        .param("instruction", "查看磁盘使用情况")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter 异步完成——getAsyncResult() 在 completeWithError 时会抛出 ServletException
        // 这正是我们想要的：连接没有静默挂起（没有耗尽 120s 超时），而是快速完成并传回异常信息。
        // 这里不强行获取响应体，而是验证异步在可接受时间内完成且异常携带我们的消息。
        try {
            mvcResult.getAsyncResult();
        } catch (Exception ex) {
            // getAsyncResult 抛出的异常已包装 ServletException
            // 验证根因包含我们的异常信息
            Throwable cause = ex;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("模拟推理阶段异常")) {
                    return; // ✅ 异常正确传播，连接未挂起
                }
                cause = cause.getCause();
            }
        }

        // 如果走到这里：说明 getAsyncResult() 没有抛出异常（极少见），尝试检查响应体
        try {
            String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .asyncDispatch(mvcResult))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("event:error") || body.contains("模拟推理阶段异常"),
                    "推理异常应通知前端，而不是让连接静默挂起。实际响应体: " + body);
        } catch (Exception e) {
            // asyncDispatch 也可能抛出异常——这仍然说明连接已正确完成（非挂起）
            // 验证异常链包含我们的消息
            Throwable cause = e;
            boolean found = false;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("模拟推理阶段异常")) {
                    found = true;
                    break;
                }
                cause = cause.getCause();
            }
            assertTrue(found, "推理异常应传播到 asyncDispatch。实际异常: " + e.getMessage());
        }
    }
}
