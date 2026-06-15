package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.InjectionResult;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.llm.PlanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维对话响应，包含完整的闭环过程产物，便于前端展示溯源。
 */
public class ChatResponse {
    private String traceId;
    private String status;
    private String message;
    private PlanResult plan;
    private InjectionResult injection;
    private List<RiskDecision> decisions = new ArrayList<>();
    private List<Map<String, Object>> execResults = new ArrayList<>();
    private String analysis;
    private List<AgentStep> steps = new ArrayList<>();

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public PlanResult getPlan() { return plan; }
    public void setPlan(PlanResult plan) { this.plan = plan; }
    public InjectionResult getInjection() { return injection; }
    public void setInjection(InjectionResult injection) { this.injection = injection; }
    public List<RiskDecision> getDecisions() { return decisions; }
    public void setDecisions(List<RiskDecision> decisions) { this.decisions = decisions; }
    public List<Map<String, Object>> getExecResults() { return execResults; }
    public void setExecResults(List<Map<String, Object>> execResults) { this.execResults = execResults; }
    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }
    public List<AgentStep> getSteps() { return steps; }
    public void setSteps(List<AgentStep> steps) { this.steps = steps; }
}
