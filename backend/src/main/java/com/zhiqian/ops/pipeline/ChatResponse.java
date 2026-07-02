package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.ImpactEstimate;
import com.zhiqian.ops.guard.InjectionResult;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.SecurityScore;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.planner.OpsExecutionPlan;
import com.zhiqian.ops.retriever.Evidence;

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
    private OpsExecutionPlan executionPlan;
    private InjectionResult injection;
    private List<RiskDecision> decisions = new ArrayList<>();
    private List<Map<String, Object>> execResults = new ArrayList<>();
    private String analysis;
    private List<Evidence> retrieval = new ArrayList<>();
    private List<AgentStep> steps = new ArrayList<>();
    private SecurityScore securityScore;
    private List<ImpactEstimate> counterfactual = new ArrayList<>();
    private List<Map<String, Object>> rollbackPlan = new ArrayList<>();

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public PlanResult getPlan() { return plan; }
    public void setPlan(PlanResult plan) { this.plan = plan; }
    public OpsExecutionPlan getExecutionPlan() { return executionPlan; }
    public void setExecutionPlan(OpsExecutionPlan executionPlan) { this.executionPlan = executionPlan; }
    public InjectionResult getInjection() { return injection; }
    public void setInjection(InjectionResult injection) { this.injection = injection; }
    public List<RiskDecision> getDecisions() { return decisions; }
    public void setDecisions(List<RiskDecision> decisions) { this.decisions = decisions; }
    public List<Map<String, Object>> getExecResults() { return execResults; }
    public void setExecResults(List<Map<String, Object>> execResults) { this.execResults = execResults; }
    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }
    public List<Evidence> getRetrieval() { return retrieval; }
    public void setRetrieval(List<Evidence> retrieval) { this.retrieval = retrieval; }
    public List<AgentStep> getSteps() { return steps; }
    public void setSteps(List<AgentStep> steps) { this.steps = steps; }
    public SecurityScore getSecurityScore() { return securityScore; }
    public void setSecurityScore(SecurityScore securityScore) { this.securityScore = securityScore; }
    public List<ImpactEstimate> getCounterfactual() { return counterfactual; }
    public void setCounterfactual(List<ImpactEstimate> counterfactual) { this.counterfactual = counterfactual; }
    public List<Map<String, Object>> getRollbackPlan() { return rollbackPlan; }
    public void setRollbackPlan(List<Map<String, Object>> rollbackPlan) { this.rollbackPlan = rollbackPlan; }
}
