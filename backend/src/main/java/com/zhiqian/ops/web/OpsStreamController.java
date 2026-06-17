package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.guard.CounterfactualAnalyzer;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.RollbackAdvisor;
import com.zhiqian.ops.guard.SecurityScorer;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实时思维链(SSE)接口：以 Server-Sent Events 逐阶段推送安全护栏八阶段的执行过程，
 * 供前端像 ChatGPT 展示 tool_call 那样实时渲染 Agent 的推理与工具调用。
 *
 * 设计要点：
 *  - 使用 GET（浏览器 EventSource 仅支持 GET），参数从 query string 读取。
 *  - 复用 OpsPipeline 同一套护栏编排与裁决逻辑，仅额外注入 stepListener 做逐步推送。
 *  - done 事件复用与 REST 接口完全一致的 enrich()，附带安全评分/反事实回放/一键回滚账本。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsStreamController {
    private final OpsPipeline pipeline;
    private final RollbackLedger rollbackLedger;
    private final SecurityScorer securityScorer = new SecurityScorer();
    private final CounterfactualAnalyzer counterfactual = new CounterfactualAnalyzer();
    private final RollbackAdvisor rollbackAdvisor = new RollbackAdvisor();
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public OpsStreamController(OpsPipeline pipeline, RollbackLedger rollbackLedger) {
        this.pipeline = pipeline;
        this.rollbackLedger = rollbackLedger;
    }

    @GetMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String instruction,
                             @RequestParam(required = false, defaultValue = "false") boolean confirm,
                             @RequestParam(required = false) String traceId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        ChatRequest req = new ChatRequest();
        req.setInstruction(instruction);
        req.setConfirm(confirm);
        req.setTraceId(traceId);

        sseExecutor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                        .data(Map.of("instruction", instruction == null ? "" : instruction)));
                ChatResponse resp = pipeline.chat(req, step -> {
                    try {
                        emitter.send(SseEmitter.event().name("step").data(stepView(step)));
                    } catch (Exception ignore) {
                        // 客户端断开等，忽略单步发送失败，后续由 done/error 收尾
                    }
                });
                enrich(resp);
                emitter.send(SseEmitter.event().name("done").data(resp));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e.getMessage() == null ? "执行失败" : e.getMessage())));
                } catch (Exception ignore) {
                    // 忽略
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 将单步思维链节点整理为前端友好的视图。 */
    private Map<String, Object> stepView(AgentStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", step.stage());
        m.put("agent", step.agentName());
        m.put("model", step.model());
        m.put("confidence", step.confidence());
        m.put("elapsedMs", step.elapsedMs());
        m.put("status", step.status());
        m.put("output", step.output());
        return m;
    }

    /** 与 OpsAgentController.enrich() 保持一致：在不改变管线裁决与 status 的前提下，补充安全评分、反事实回放与可一键回滚的动作账本（纯计算）。 */
    private void enrich(ChatResponse resp) {
        boolean injectionBlocked = "INJECTION_BLOCKED".equals(resp.getStatus());
        RiskLevel worst = RiskLevel.SAFE;
        if (resp.getDecisions() != null) {
            for (RiskDecision d : resp.getDecisions()) {
                if (d.level() == RiskLevel.BLOCK) {
                    worst = RiskLevel.BLOCK;
                } else if (d.level() == RiskLevel.REVIEW && worst != RiskLevel.BLOCK) {
                    worst = RiskLevel.REVIEW;
                }
            }
        }
        resp.setSecurityScore(securityScorer.score(
                injectionBlocked, worst, resp.getDecisions(), resp.getExecResults(), resp.getStatus()));
        resp.setCounterfactual(counterfactual.analyze(resp.getDecisions()));

        // 动作账本：为已执行的变更类指令生成补偿/回滚计划，并登记以支持一键回滚
        List<Map<String, Object>> ledger = rollbackAdvisor.buildLedger(resp.getDecisions(), resp.getExecResults());
        resp.setRollbackPlan(ledger);
        rollbackLedger.record(resp.getTraceId(), ledger);
    }
}
