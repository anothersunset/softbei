package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.guard.CounterfactualAnalyzer;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.SecurityScorer;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维 Agent REST 入口。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsAgentController {
    private final OpsPipeline pipeline;
    private final List<AgentTool> tools;
    private final SecurityScorer securityScorer = new SecurityScorer();
    private final CounterfactualAnalyzer counterfactual = new CounterfactualAnalyzer();

    public OpsAgentController(OpsPipeline pipeline, List<AgentTool> tools) {
        this.pipeline = pipeline;
        this.tools = tools;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest req) {
        ChatResponse resp = pipeline.chat(req);
        enrich(resp);
        return Result.ok(resp);
    }

    /** 在不改变管线裁决与 status 的前提下，补充安全评分与反事实回放（纯计算）。 */
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
    }

    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentTool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            list.add(m);
        }
        return Result.ok(list);
    }
}
