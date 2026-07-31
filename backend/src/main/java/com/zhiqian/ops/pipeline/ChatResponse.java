package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.ImpactEstimate;
import com.zhiqian.ops.guard.InjectionResult;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.SecurityScore;
import com.zhiqian.ops.guard.ThreatAssessment;
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
    /** 威胁指数评估：随本轮指令危险度走的直觉分，与 securityScore（防御履职表现分）互补，见两者类注释。 */
    private ThreatAssessment threatAssessment;
    private List<ImpactEstimate> counterfactual = new ArrayList<>();
    private List<Map<String, Object>> rollbackPlan = new ArrayList<>();
    /** 回填本次所属会话，便于前端核对。 */
    private String sessionId;
    /** 本会话是否已因跨轮攻击累积命中阈值而降级为只读（会话级、内存态，重开会话即解除）。 */
    private boolean readOnlyLockdown;
    /** 本轮是否触发了跨轮累积告警（即使未越阈值也可用于前端提示，当前仅在越阈值时置 true）。 */
    private boolean crossTurnAlert;
    /** 跨轮告警的人类可读原因，crossTurnAlert=false 时为 null。 */
    private String crossTurnReason;
    /** status=CLARIFICATION_REQUIRED 时的具体澄清类型，如 "DISK_FILE_SELECTION"；便于前端按类型渲染不同交互。 */
    private String clarificationKind;
    /** 磁盘候选门禁触发时返回的候选文件表：path/sizeBytes/mtimeEpochSeconds/tier/reason/selectable/recommended。 */
    private List<Map<String, Object>> candidateTargets = new ArrayList<>();

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
    public ThreatAssessment getThreatAssessment() { return threatAssessment; }
    public void setThreatAssessment(ThreatAssessment threatAssessment) { this.threatAssessment = threatAssessment; }
    public List<ImpactEstimate> getCounterfactual() { return counterfactual; }
    public void setCounterfactual(List<ImpactEstimate> counterfactual) { this.counterfactual = counterfactual; }
    public List<Map<String, Object>> getRollbackPlan() { return rollbackPlan; }
    public void setRollbackPlan(List<Map<String, Object>> rollbackPlan) { this.rollbackPlan = rollbackPlan; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public boolean isReadOnlyLockdown() { return readOnlyLockdown; }
    public void setReadOnlyLockdown(boolean readOnlyLockdown) { this.readOnlyLockdown = readOnlyLockdown; }
    public boolean isCrossTurnAlert() { return crossTurnAlert; }
    public void setCrossTurnAlert(boolean crossTurnAlert) { this.crossTurnAlert = crossTurnAlert; }
    public String getCrossTurnReason() { return crossTurnReason; }
    public void setCrossTurnReason(String crossTurnReason) { this.crossTurnReason = crossTurnReason; }
    public String getClarificationKind() { return clarificationKind; }
    public void setClarificationKind(String clarificationKind) { this.clarificationKind = clarificationKind; }
    public List<Map<String, Object>> getCandidateTargets() { return candidateTargets; }
    public void setCandidateTargets(List<Map<String, Object>> candidateTargets) { this.candidateTargets = candidateTargets; }
}
