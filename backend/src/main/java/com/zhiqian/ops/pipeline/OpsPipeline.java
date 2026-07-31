package com.zhiqian.ops.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentNode;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ActiveTool;
import com.zhiqian.ops.agent.tools.DiskCandidateProperties;
import com.zhiqian.ops.agent.tools.FileCandidate;
import com.zhiqian.ops.agent.tools.IoActivityProperties;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.LsofWriters;
import com.zhiqian.ops.exec.PreChangeBackup;
import com.zhiqian.ops.guard.CrossTurnProperties;
import com.zhiqian.ops.guard.CrossTurnThreatMonitor;
import com.zhiqian.ops.guard.DiskCandidateClassifier;
import com.zhiqian.ops.guard.InjectionResult;
import com.zhiqian.ops.guard.IntentConsistencyChecker;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.ChatMessage;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.llm.PlanStep;
import com.zhiqian.ops.llm.ToolCall;
import com.zhiqian.ops.llm.ToolChatResult;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.mcp.McpToolSpec;
import com.zhiqian.ops.planner.ExecutionPlanner;
import com.zhiqian.ops.planner.OpsExecutionPlan;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.retriever.Evidence;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import com.zhiqian.ops.trace.TraceStage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 安全护栏编排管线：接收指令 -> 抗注入 -> 感知环境 -> 知识检索 -> 推理决策 -> 任务规划 -> 安全校验 -> 执行 -> 根因分析。
 * 复用原项目 AgentRunner 「逐节点迭代 + onStep 回调」模式，每步均落盘滯源。
 * 另提供带 stepListener 的 chat 重载，供 SSE 实时逐步推送思维链（不改变裁决与状态机）。
 */
@Service
public class OpsPipeline {
    private static final int MAX_PENDING_PLANS = 200;
    private static final long PLAN_TTL_MS = 30 * 60 * 1000L;
    /** IRREVERSIBLE 计划必须由操作者明确输入，不能只靠一个通用 confirm 布尔值放行。 */
    public static final String HIGH_IMPACT_CONFIRMATION = "我已核对影响并确认执行";
    /** ReAct 感知循环最大轮次（可由 OPS_REACT_MAX_ROUNDS 覆盖）。 */
    private static final int REACT_MAX_ROUNDS = reactMaxRounds();
    private static final String REACT_SYSTEM_PROMPT =
            "你是严谨的 Linux 运维诊断 Agent，采用 ReAct（Thought→Action→Observation）方式工作。"
            + "你只能调用给定的【只读感知工具】来收集系统证据，绝不臆造数据。"
            + "每一步先思考还缺什么证据，再调用最合适的工具（可带参数，按需精确采集，避免无差别全量拉取）。"
            + "当已收集到足以定位问题的证据时，停止调用工具，用一两句话总结你的关键观察与初步根因方向。";
    private final AgentRunner runner;
    private final PromptInjectionDetector injectionDetector;
    private final IntentRiskGuard guard;
    private final LlmClient llm;
    private final RootCauseAnalyzer analyzer;
    private final OpsAuditService audit;
    private final LeastPrivilegeExecutor executor;
    private final List<AgentTool> senseTools;
    private final ContextRetriever retriever;
    private final ExecProperties execProps;
    private final SensitiveDataSanitizer sanitizer;
    private final McpDispatcher mcpDispatcher;
    private final ExecutionPlanner executionPlanner;
    private final IntentConsistencyChecker consistencyChecker;
    private final PreChangeBackup preChangeBackup;
    private final SessionStore sessionStore;
    private final CrossTurnThreatMonitor crossTurnMonitor;
    private final CrossTurnProperties crossTurnProps;
    private final DiskCandidateClassifier diskCandidateClassifier;
    private final DiskCandidateProperties diskCandidateProps;
    private final IoActivityProperties ioActivityProps;
    private final OpsOutcomeResolver outcomeResolver = new OpsOutcomeResolver();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();
    private final Map<String, CachedCandidates> candidateCache = new ConcurrentHashMap<>();
    /** 待确认计划落盘文件（断点续跑）；state-dir 为空时为 null（纯内存）。 */
    private final java.nio.file.Path pendingPlanFile;

    public OpsPipeline(AgentRunner runner,
                       PromptInjectionDetector injectionDetector,
                       IntentRiskGuard guard,
                       LlmClient llm,
                       RootCauseAnalyzer analyzer,
                       OpsAuditService audit,
                       LeastPrivilegeExecutor executor,
                       List<AgentTool> senseTools,
                       ContextRetriever retriever,
                       ExecProperties execProps,
                       SensitiveDataSanitizer sanitizer,
                       McpDispatcher mcpDispatcher,
                       SessionStore sessionStore,
                       CrossTurnThreatMonitor crossTurnMonitor,
                       CrossTurnProperties crossTurnProps,
                       DiskCandidateClassifier diskCandidateClassifier,
                       DiskCandidateProperties diskCandidateProps,
                       IoActivityProperties ioActivityProps) {
        this.runner = runner;
        this.injectionDetector = injectionDetector;
        this.guard = guard;
        this.llm = llm;
        this.analyzer = analyzer;
        this.audit = audit;
        this.executor = executor;
        this.senseTools = senseTools;
        this.retriever = retriever;
        this.execProps = execProps;
        this.sanitizer = sanitizer;
        this.mcpDispatcher = mcpDispatcher;
        this.sessionStore = sessionStore;
        this.crossTurnMonitor = crossTurnMonitor;
        this.crossTurnProps = crossTurnProps;
        this.diskCandidateClassifier = diskCandidateClassifier;
        this.diskCandidateProps = diskCandidateProps;
        this.ioActivityProps = ioActivityProps;
        this.executionPlanner = new ExecutionPlanner(guard);
        this.consistencyChecker = new IntentConsistencyChecker(llm);
        this.preChangeBackup = new PreChangeBackup(execProps);
        String stateDir = execProps == null ? null : execProps.getStateDir();
        this.pendingPlanFile = stateDir == null || stateDir.isBlank()
                ? null : java.nio.file.Path.of(stateDir).resolve("pending-plans.jsonl");
        loadPendingPlans();
    }

    private static int reactMaxRounds() {
        String v = System.getenv("OPS_REACT_MAX_ROUNDS");
        if (v != null && !v.isBlank()) {
            try {
                return Math.max(1, Math.min(10, Integer.parseInt(v.trim())));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 5;
    }

    public ChatResponse chat(ChatRequest req) {
        return chat(req, null);
    }

    /**
     * 与 {@link #chat(ChatRequest)} 逻辑完全一致，仅额外在每个节点完成时通过 stepListener 实时回调。
     * stepListener 为 null 时与原方法等价，保证同步 REST 路径与评测行为不变。
     */
    public ChatResponse chat(ChatRequest req, Consumer<AgentStep> stepListener) {
        String rawInstruction = req.getInstruction() == null ? "" : req.getInstruction().trim();
        // 秘密脱敏(P0)：入口即遮蔽指令中的 password/token/secret/JDBC 等敏感片段，
        // 使下游所有回显（规划摘要 executionPlan.summary、根因叙述、trace、对外响应）
        // 天然不泄漏。抗注入检测不依赖这些字段，脱敏不影响注入识别。
        String instruction = sanitizer == null ? rawInstruction : sanitizer.sanitize(rawInstruction);
        boolean confirm = req.isConfirm();
        ChatResponse resp = new ChatResponse();

        // 候选选择路径：用户从磁盘候选表中选定目标后提交，针对选定文件生成真实计划
        evictExpiredCandidates();
        List<String> selectedTargets = req.getSelectedTargets();
        if (selectedTargets != null && !selectedTargets.isEmpty()
                && req.getTraceId() != null && candidateCache.containsKey(req.getTraceId())) {
            return runWithSelection(req.getTraceId(), selectedTargets, resp, stepListener);
        }

        // 人工确认执行路径：复用缓存的计划，仅重跑感知+检索+校验+执行+分析
        evictExpiredPlans();
        if (confirm && req.getTraceId() != null && planCache.containsKey(req.getTraceId())) {
            return runConfirmed(req.getTraceId(), resp, stepListener,
                    HIGH_IMPACT_CONFIRMATION.equals(req.getHighImpactConfirmation()));
        }

        // 安全修复(P0)：confirm 仅在命中「已缓存的待确认计划」时才生效。
        // 否则一律视为未确认，防止首次请求直接携带 confirm=true（或伪造 traceId）
        // 绕过人工确认门禁、把变更类指令直接推到执行阶段。
        confirm = false;

        OpsTrace trace = audit.newTrace(instruction);
        resp.setTraceId(trace.getTraceId());
        AgentContext ctx = new AgentContext(System.currentTimeMillis(), 0L);
        ctx.state().put("traceId", trace.getTraceId());
        ctx.state().put("instruction", instruction);
        ctx.state().put("confirm", confirm);
        ctx.state().put("highImpactConfirmed", false);

        // 跨轮攻击检测：按 sessionId 取建会话（未携带 sessionId 视为一次性独立请求，不与其它请求共享
        // 状态，保证评测确定性不变）；每轮开始先做可疑度衰减，避免正常长对话的历史小分永久累积。
        SessionContext session = sessionStore.getOrCreate(req.getSessionId());
        session.decay(crossTurnProps.getDecayPerTurn());
        ctx.state().put("session", session);
        // 诊断证据继承：只把展平后的快照（tool/target/summary）放进 state，ReasonNode 只读这个
        // key，永远拿不到 session 对象本身，够不着 cleanHistory——见设计文档「诊断证据继承」。
        ctx.state().put("recentEvidence", session.recentEvidenceSnapshot());

        List<AgentNode> nodes = List.of(
                new ReceiveNode(), new InjectionNode(), new SenseNode(), new DiskCandidateGateNode(),
                new RetrieveNode(), new ReasonNode(), new PlanNode(), new GuardNode(), new CrossTurnGuardNode(),
                new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(trace.getTraceId(), s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);

        finalize(ctx, resp, instruction, confirm);
        audit.complete(trace.getTraceId(), resp.getStatus());
        return resp;
    }

    private ChatResponse runConfirmed(String traceId, ChatResponse resp, Consumer<AgentStep> stepListener,
                                      boolean highImpactConfirmed) {
        CachedPlan cached = planCache.get(traceId);
        resp.setTraceId(traceId);
        AgentContext ctx = new AgentContext(System.currentTimeMillis(), 0L);
        ctx.state().put("traceId", traceId);
        ctx.state().put("instruction", cached.instruction);
        ctx.state().put("confirm", true);
        ctx.state().put("highImpactConfirmed", highImpactConfirmed);
        // 复用首轮已被审阅过的计划，不重新推理（保证人工确认执行的就是被审阅的同一计划）。
        ctx.state().put("plan", cached.plan);

        // 沿用「提出计划」那一轮绑定的会话，而非信任确认请求自带的 sessionId——
        // 防止攻击者在确认时伪造/更换 sessionId 逃避已经命中的只读降级。确认执行不重新计入衰减，
        // 因为它是同一次交互的延续，不是新的一轮。
        SessionContext session = sessionStore.getOrCreate(cached.sessionId());
        ctx.state().put("session", session);
        ctx.state().put("recentEvidence", session.recentEvidenceSnapshot());

        // 安全修复(P1)：重跑「感知 + 检索」节点，补全确认执行后处置报告的「感知证据 / 知识依据」，
        // 避免确认链路只剩 校验/执行/分析 三步、retrieval=0 的断层问题。
        List<AgentNode> nodes = List.of(
                new SenseNode(), new RetrieveNode(), new PlanNode(), new GuardNode(), new CrossTurnGuardNode(),
                new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(traceId, s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);
        finalize(ctx, resp, cached.instruction, true);
        // 高影响确认短语缺失时仍保持 REVIEW_PENDING，不消费已审阅计划，允许操作者补充强确认。
        if (!OpsOutcomeStatus.REVIEW_PENDING.name().equals(resp.getStatus())) {
            planCache.remove(traceId);
            persistPendingPlans();
        }
        audit.complete(traceId, resp.getStatus());
        return resp;
    }

    /**
     * 候选选择路径：用户从磁盘候选表中勾选具体文件后提交，针对选定目标重新走近乎完整的管线
     * （跳过 INJECTION_GUARD——本轮指令由原始已过检的指令 + 服务端自己校验过的候选路径拼接而成，
     * 不引入新的用户可控自由文本；跳过 DISK_CANDIDATE_GATE——本轮已经是"针对具体目标"，不需要再问一次）。
     * 产出的是真实 PlanResult，会正常落入 REVIEW_PENDING，仍需第三轮 confirm=true 才真正执行。
     */
    private ChatResponse runWithSelection(String traceId, List<String> selectedTargets,
                                          ChatResponse resp, Consumer<AgentStep> stepListener) {
        CachedCandidates cached = candidateCache.get(traceId);
        resp.setTraceId(traceId);

        // 安全校验：绝不信任客户端提交的任意路径，选择必须来自候选表。
        java.util.Set<String> candidatePaths = new java.util.HashSet<>();
        for (Map<String, Object> c : cached.candidates()) {
            Object p = c.get("path");
            if (p != null) {
                candidatePaths.add(String.valueOf(p));
            }
        }
        List<String> validSelections = new ArrayList<>();
        for (String s : selectedTargets) {
            if (candidatePaths.contains(s)) {
                validSelections.add(s);
            }
        }
        if (validSelections.isEmpty()) {
            resp.setStatus(OpsOutcomeStatus.PLAN_FAILED.name());
            resp.setMessage("选定目标不在当前候选列表中，请重新发起检查");
            candidateCache.remove(traceId);
            return resp;
        }

        String enrichedInstruction = cached.instruction()
                + "（用户已从候选中选定以下文件执行清理：" + String.join("、", validSelections) + "）";

        AgentContext ctx = new AgentContext(System.currentTimeMillis(), 0L);
        ctx.state().put("traceId", traceId);
        ctx.state().put("instruction", enrichedInstruction);
        ctx.state().put("confirm", false);

        // 沿用候选表那一轮绑定的会话，理由与 runConfirmed() 相同：不信任后续请求自带的 sessionId。
        SessionContext session = sessionStore.getOrCreate(cached.sessionId());
        ctx.state().put("session", session);
        ctx.state().put("recentEvidence", session.recentEvidenceSnapshot());

        List<AgentNode> nodes = List.of(
                new SenseNode(), new RetrieveNode(), new ReasonNode(), new PlanNode(),
                new GuardNode(), new CrossTurnGuardNode(), new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(traceId, s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);
        finalize(ctx, resp, enrichedInstruction, false);
        candidateCache.remove(traceId);
        audit.complete(traceId, resp.getStatus());
        return resp;
    }

    @SuppressWarnings("unchecked")
    private void finalize(AgentContext ctx, ChatResponse resp, String instruction, boolean confirm) {
        Object inj = ctx.state().get("injection");
        if (inj instanceof InjectionResult ir) {
            resp.setInjection(ir);
        }
        Object plan = ctx.state().get("plan");
        if (plan instanceof PlanResult pr) {
            resp.setPlan(pr);
        }
        Object decisions = ctx.state().get("decisions");
        if (decisions instanceof List<?> list) {
            resp.setDecisions((List<RiskDecision>) list);
        }
        Object executionPlan = ctx.state().get("executionPlan");
        if (executionPlan instanceof OpsExecutionPlan ep) {
            resp.setExecutionPlan(ep);
        }
        Object exec = ctx.state().get("execResults");
        if (exec instanceof List<?> list) {
            resp.setExecResults((List<Map<String, Object>>) list);
        }
        Object analysis = ctx.state().get("analysis");
        if (analysis != null) {
            resp.setAnalysis(String.valueOf(analysis));
        }
        Object retrieval = ctx.state().get("retrieval");
        if (retrieval instanceof List<?> list) {
            resp.setRetrieval((List<Evidence>) list);
        }

        // 高影响计划不能只靠通用 confirm=true 放行；缺少明示短语时仍视为未确认并保持待审阅。
        RiskLevel resolvedWorst = (RiskLevel) ctx.state().getOrDefault("worstLevel", RiskLevel.READONLY);
        boolean highImpactConfirmed = Boolean.TRUE.equals(ctx.state().get("highImpactConfirmed"));
        boolean effectiveConfirm = confirm
                && (resolvedWorst != RiskLevel.IRREVERSIBLE || highImpactConfirmed);
        // 确保裁决所需事实进入 state（供权威状态解析器读取真实副作用与验证结果）。
        ctx.state().put("confirm", effectiveConfirm);
        OpsOutcomeStatus outcome = outcomeResolver.resolve(ctx.state());

        // 跨轮攻击检测收尾：回填会话状态到响应，并判定本轮是否「干净」、要不要计入历史。
        Object sessionObj = ctx.state().get("session");
        if (sessionObj instanceof SessionContext session) {
            resp.setSessionId(session.sessionId());
            resp.setReadOnlyLockdown(session.isReadOnlyLockdown());
            Object assessmentObj = ctx.state().get("crossTurnAssessment");
            if (assessmentObj instanceof CrossTurnThreatMonitor.Assessment assessment && assessment.triggered()) {
                resp.setCrossTurnAlert(true);
                resp.setCrossTurnReason(assessment.reason());
            }

            // 「干净/污染」判据（三条，已与用户确认不额外加「目标跳变即污染」）：
            // 1) 单轮 injection 命中；2) 本轮被判定跨轮越阈值；3) worstLevel==BLOCK。
            // 三者皆非才算「干净」，才追加进历史——历史只存派生标量特征，不存原始文本，
            // 且架构上不供 LLM prompt 使用，见设计文档。
            boolean injectionBlocked = Boolean.TRUE.equals(ctx.state().get("injectionBlocked"));
            RiskLevel worst = (RiskLevel) ctx.state().getOrDefault("worstLevel", RiskLevel.READONLY);
            boolean crossTurnTriggeredThisTurn = assessmentObj instanceof CrossTurnThreatMonitor.Assessment a && a.triggered();
            boolean polluted = injectionBlocked || crossTurnTriggeredThisTurn || worst == RiskLevel.BLOCK;
            if (!polluted) {
                String targetSlot = String.valueOf(ctx.state().get("thisTargetSlot"));
                if ("null".equals(targetSlot)) {
                    targetSlot = null;
                }
                session.appendCleanTurn(new Turn(targetSlot, worst, outcome.name()));
                // 诊断证据继承：同样只在「干净」轮追加，跟跨轮攻击账本共用同一道判据，
                // 不为证据继承单独设计一套标准——道理一致：被判污染的轮次不该留下可复用的痕迹。
                List<EvidenceRecord> evidence = extractEvidenceRecords(ctx);
                if (!evidence.isEmpty()) {
                    session.appendEvidence(evidence);
                }
            }
        }

        if (outcome == OpsOutcomeStatus.CLARIFICATION_REQUIRED) {
            // 磁盘候选门禁触发：缓存候选表供后续「选择」请求核对，不落入 REVIEW_PENDING 的计划缓存分支。
            Object candidatesObj = ctx.state().get("candidateTargets");
            if (candidatesObj instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) list;
                resp.setClarificationKind(String.valueOf(ctx.state().getOrDefault("clarificationKind", "DISK_FILE_SELECTION")));
                resp.setCandidateTargets(candidates);
                evictExpiredCandidates();
                if (candidateCache.size() >= MAX_PENDING_PLANS) {
                    String oldest = candidateCache.entrySet().stream()
                            .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.createdAtMs, b.createdAtMs)))
                            .map(Map.Entry::getKey)
                            .orElse(null);
                    if (oldest != null) {
                        candidateCache.remove(oldest);
                    }
                }
                String sid = sessionObj instanceof SessionContext s ? s.sessionId() : null;
                candidateCache.put(resp.getTraceId(),
                        new CachedCandidates(instruction, candidates, System.currentTimeMillis(), sid));
            }
        } else if (outcome == OpsOutcomeStatus.REVIEW_PENDING) {
            // 缓存计划供后续确认
            if (resp.getPlan() != null) {
                evictExpiredPlans();
                if (planCache.size() >= MAX_PENDING_PLANS) {
                    String oldest = planCache.entrySet().stream()
                            .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.createdAtMs, b.createdAtMs)))
                            .map(Map.Entry::getKey)
                            .orElse(null);
                    if (oldest != null) {
                        planCache.remove(oldest);
                    }
                }
                String sid = sessionObj instanceof SessionContext s ? s.sessionId() : null;
                planCache.put(resp.getTraceId(), new CachedPlan(instruction, resp.getPlan(), System.currentTimeMillis(), sid));
                persistPendingPlans();
            }
        }
        resp.setStatus(outcome.name());
        if (outcome == OpsOutcomeStatus.REVIEW_PENDING
                && resolvedWorst == RiskLevel.IRREVERSIBLE && confirm && !highImpactConfirmed) {
            resp.setMessage("高影响操作尚未完成强确认：请输入“" + HIGH_IMPACT_CONFIRMATION + "”，系统仍未执行变更。");
        } else {
            resp.setMessage(outcome.message());
        }
    }

    // ============ 节点实现 ============

    private class ReceiveNode implements AgentNode {
        public String stage() { return TraceStage.RECEIVE.name(); }
        public String agentName() { return "ReceiveAgent"; }
        public Map<String, Object> run(AgentContext ctx) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instruction", ctx.state().get("instruction"));
            return out;
        }
    }

    private class InjectionNode implements AgentNode {
        public String stage() { return TraceStage.INJECTION_GUARD.name(); }
        public String agentName() { return "PromptInjectionDetector"; }
        public Map<String, Object> run(AgentContext ctx) {
            String instruction = String.valueOf(ctx.state().get("instruction"));
            InjectionResult ir = injectionDetector.detect(instruction);
            ctx.state().put("injection", ir);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("blocked", ir.blocked());
            out.put("matchedPatterns", ir.matchedPatterns());
            if (ir.blocked()) {
                ctx.state().put("injectionBlocked", true);
                // 本节点 halt 后 CrossTurnGuardNode 不会再运行，须在此直接记账，否则跨轮累积断裂
                // ——被拦截的攻击轮次同样要计入可疑度，即使自身已被单轮规则拦下。
                Object sessionObj = ctx.state().get("session");
                if (sessionObj instanceof SessionContext session) {
                    session.addSuspicion(crossTurnProps.getInjectionHitScore());
                    boolean triggered = session.suspicion() >= crossTurnProps.getLockdownThreshold();
                    if (triggered) {
                        session.setReadOnlyLockdown(true);
                        ctx.state().put("crossTurnAssessment", new CrossTurnThreatMonitor.Assessment(
                                crossTurnProps.getInjectionHitScore(), session.suspicion(), true,
                                "累积可疑度 " + session.suspicion() + "/" + crossTurnProps.getLockdownThreshold()
                                        + "：单轮命中提示词注入/红线特征(+" + crossTurnProps.getInjectionHitScore() + ")"));
                    }
                }
                out.put("_halt", true);
                out.put("_status", "blocked");
            }
            return out;
        }
    }

    private class SenseNode implements AgentNode {
        public String stage() { return TraceStage.SENSE.name(); }
        public String agentName() { return "EnvironmentSensor"; }
        public Map<String, Object> run(AgentContext ctx) {
            // 真实且支持工具调用的模型：走 ReAct 按需感知循环（自主选择工具+参数）。
            // Mock/不支持工具的模型：保持原「全量只读感知」行为，保证评测确定性不变。
            if (llm.isReal() && llm.supportsTools()) {
                try {
                    return runReActSense(ctx);
                } catch (Exception e) {
                    // ReAct 失败（网络/解析等）优雅降级为全量感知，主链路不中断。
                    Map<String, Object> out = runFullSense(ctx);
                    out.put("reactDegraded", "ReAct 感知失败已降级为全量感知：" + e.getMessage());
                    return out;
                }
            }
            return runFullSense(ctx);
        }
    }

    /** 原始全量只读感知：无差别执行所有被动感知工具。 */
    private Map<String, Object> runFullSense(AgentContext ctx) {
        Map<String, Object> sensed = new LinkedHashMap<>();
        for (AgentTool tool : senseTools) {
            // 主动/动作类工具(如主动巡检)不在被动感知阶段自动执行，仅通过 MCP 或按需触发。
            if (tool instanceof ActiveTool) continue;
            try {
                Object output = tool.run(ctx, Map.of());
                sensed.put(tool.name(), sanitizer == null ? output : sanitizer.sanitizeValue(output));
            } catch (Exception e) {
                String message = "感知失败：" + e.getMessage();
                sensed.put(tool.name(), sanitizer == null ? message : sanitizer.sanitize(message));
            }
        }
        ctx.state().put("sensed", sensed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "full");
        out.put("toolsRun", sensed.size());
        out.put("sensed", sensed);
        return out;
    }

    /**
     * ReAct 按需感知循环：模型自主选择只读感知工具（可带参数），经 MCP 内部总线（McpDispatcher）
     * 逐轮 Thought→Action→Observation 迭代，直至收集到足够证据或达轮次上限。
     * 与官方推荐的「Plan-Execute（全局）+ ReAct（子任务）」混合架构对齐。
     *
     * 安全边界：本循环只暴露【只读感知/巡检工具】的能力面（最小权限的工具供给），
     * 不进行任何变更；变更类动作仍由后续 GUARD/EXECUTE 阶段的护栏与人工确认把关。
     */
    private Map<String, Object> runReActSense(AgentContext ctx) {
        String instruction = String.valueOf(ctx.state().get("instruction"));
        List<McpToolSpec> toolSpecs = senseToolSpecs();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(REACT_SYSTEM_PROMPT));
        messages.add(ChatMessage.user("运维诉求：" + instruction
                + "\n请通过调用只读感知工具收集定位该问题所需的证据。"));

        Map<String, Object> sensed = new LinkedHashMap<>();
        List<Map<String, Object>> rounds = new ArrayList<>();
        int toolsInvoked = 0;
        String reactSummary = null;

        for (int round = 1; round <= REACT_MAX_ROUNDS; round++) {
            ToolChatResult res = llm.chatWithTools(messages, toolSpecs);
            if (!res.hasToolCalls()) {
                reactSummary = res.content();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("round", round);
                r.put("thought", reactSummary);
                r.put("action", "final");
                rounds.add(r);
                break;
            }
            messages.add(ChatMessage.assistant(res.content(), res.toolCalls()));
            for (ToolCall tc : res.toolCalls()) {
                Map<String, Object> args = parseArguments(tc.argumentsJson());
                String observation = invokeSenseToolViaMcp(tc.name(), args);
                messages.add(ChatMessage.tool(tc.id(), observation));
                toolsInvoked++;
                sensed.put(tc.name() + "#r" + round, observation);

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("round", round);
                if (res.content() != null && !res.content().isBlank()) {
                    r.put("thought", res.content());
                }
                r.put("action", tc.name());
                r.put("arguments", args);
                r.put("observation", truncate(observation, 600));
                rounds.add(r);
            }
        }

        // 若模型一次工具都没调用（异常但可能发生），回退全量感知，保证下游有证据可用。
        if (sensed.isEmpty()) {
            Map<String, Object> full = runFullSense(ctx);
            full.put("mode", "react-fallback-full");
            full.put("reactSummary", reactSummary);
            full.put("reactRounds", rounds);
            return full;
        }

        ctx.state().put("sensed", sensed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "react");
        out.put("toolsInvoked", toolsInvoked);
        out.put("rounds", rounds);
        out.put("reactSummary", reactSummary);
        out.put("sensed", sensed);
        return out;
    }

    /** 仅暴露只读被动感知工具的 MCP 规格（最小权限的工具供给面）。 */
    private List<McpToolSpec> senseToolSpecs() {
        List<McpToolSpec> specs = new ArrayList<>();
        for (AgentTool tool : senseTools) {
            if (tool instanceof ActiveTool) continue;
            specs.add(new McpToolSpec(tool.name(), tool.description(), tool.inputSchema(), null));
        }
        return specs;
    }

    /** 经 McpDispatcher 内部总线以 JSON-RPC tools/call 调用工具（自己复用自己的 MCP 能力）。 */
    private String invokeSenseToolViaMcp(String toolName, Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", "react-" + toolName);
        req.put("method", "tools/call");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args);
        req.put("params", params);
        try {
            Map<String, Object> resp = mcpDispatcher.handle(req);
            Object result = resp.get("result");
            if (result instanceof Map<?, ?> rm) {
                Object content = rm.get("content");
                if (content instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> first) {
                    return String.valueOf(first.get("text"));
                }
                return String.valueOf(rm.get("structuredContent"));
            }
            Object error = resp.get("error");
            if (error != null) {
                return "工具调用错误：" + error;
            }
            return "工具无返回";
        } catch (Exception e) {
            return "工具调用异常：" + e.getMessage();
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(argumentsJson, Map.class);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated]";
    }

    /**
     * 磁盘候选门禁：在 SENSE 之后、RETRIEVE 之前。两个条件必须同时满足才触发候选表：
     * ① disk_sense 找到 >=2 个体量达标的文件级候选；② 本轮指令命中磁盘/清理相关意图关键词。
     * 条件②必不可少——mock/全量感知模式下 disk_sense 会在任何指令下都执行一次，只看条件①
     * 会把"重启 nginx"这类无关指令误判成磁盘清理场景，强行打断成候选表。
     * 触发后设置 _halt=true 短路后续节点；finalize() 据此进入 CLARIFICATION_REQUIRED 分支。
     */
    private class DiskCandidateGateNode implements AgentNode {
        public String stage() { return TraceStage.DISK_CANDIDATE_GATE.name(); }
        public String agentName() { return "DiskCandidateClassifier"; }
        public Map<String, Object> run(AgentContext ctx) {
            Map<String, Object> out = new LinkedHashMap<>();
            String instruction = String.valueOf(ctx.state().get("instruction"));
            if (!ContextRetriever.matchesDiskIntent(instruction)) {
                return out;
            }
            List<FileCandidate> found = extractDiskCandidates(ctx.state().get("sensed"));
            long minBytes = diskCandidateProps.getMinSizeMb() * 1024L * 1024L;
            List<FileCandidate> bigEnough = new ArrayList<>();
            for (FileCandidate f : found) {
                if (f.sizeBytes() >= minBytes) {
                    bigEnough.add(f);
                }
            }
            if (bigEnough.size() < 2) {
                out.put("skipped", "候选不足 2 个或未达体量阈值(" + diskCandidateProps.getMinSizeMb() + "MB)，照常走推理规划");
                return out;
            }

            List<Map<String, Object>> classified = new ArrayList<>();
            for (FileCandidate f : bigEnough) {
                boolean heldOpen = isHeldOpen(f.path());
                DiskCandidateClassifier.Classification c = diskCandidateClassifier.classify(f, heldOpen);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", f.path());
                m.put("sizeBytes", f.sizeBytes());
                m.put("mtimeEpochSeconds", f.mtimeEpochSeconds());
                m.put("tier", c.tier().name());
                m.put("reason", c.reason());
                m.put("selectable", c.tier() != DiskCandidateClassifier.Tier.BLOCKED);
                m.put("recommended", c.tier() == DiskCandidateClassifier.Tier.RECOMMENDED);
                classified.add(m);
            }
            reclassifyGrowingRecommended(classified);

            ctx.state().put("candidateTargets", classified);
            ctx.state().put("clarificationKind", "DISK_FILE_SELECTION");
            ctx.state().put("haltForTargetSelection", true);

            out.put("candidateCount", classified.size());
            out.put("candidates", classified);
            out.put("_halt", true);
            out.put("_status", "clarification_required");
            return out;
        }

        /**
         * 候选表 RECOMMENDED 档头几个文件做一次两点差分采样：命中增长即降级为 CAUTION（不再默认勾选），
         * reason 里写明增速与写入进程；未增长则保持原分类不变。数量与窗口由 ops.io-activity.candidate-probe-*
         * 配置，默认 3 个 × 2 秒 ≈ 6 秒的候选表生成延迟，用户已确认可接受（换来"清理后立刻回涨"不会被误推荐）。
         * lsof 探测的解析逻辑与 io_activity_sense 工具共用，见 {@link com.zhiqian.ops.exec.LsofWriters}。
         */
        private void reclassifyGrowingRecommended(List<Map<String, Object>> classified) {
            int probed = 0;
            for (Map<String, Object> m : classified) {
                if (probed >= ioActivityProps.getCandidateProbeCount()) break;
                if (!DiskCandidateClassifier.Tier.RECOMMENDED.name().equals(m.get("tier"))) continue;
                probed++;
                String path = String.valueOf(m.get("path"));
                Long before = statSize(path);
                if (before == null) continue;
                try {
                    Thread.sleep(ioActivityProps.getCandidateProbeWindowMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Long after = statSize(path);
                if (after == null || after <= before) continue;
                long growthBps = Math.round(
                        (after - before) / (ioActivityProps.getCandidateProbeWindowMs() / 1000.0));
                List<Map<String, Object>> writers = LsofWriters.probe(executor, path);
                m.put("tier", DiskCandidateClassifier.Tier.CAUTION.name());
                m.put("recommended", false);
                m.put("reason", "该文件正以 " + humanRate(growthBps) + " 增长" + writerSuffix(writers)
                        + "，清理后会立刻回涨，已从建议清单移出，如需处理请先排查写入进程");
                m.put("growthBytesPerSec", growthBps);
            }
        }

        /** 只读 stat 取文件当前大小；探测失败（文件不存在/stat 不可用）返回 null，调用方按「跳过该候选」处理。 */
        private Long statSize(String path) {
            try {
                ExecResult r = executor.runReadOnly(List.of("stat", "-c%s", path));
                if (r.exitCode() != 0 || r.stdout() == null || r.stdout().isBlank()) {
                    return null;
                }
                return Long.parseLong(r.stdout().trim());
            } catch (Exception e) {
                return null;
            }
        }

        private String writerSuffix(List<Map<String, Object>> writers) {
            if (writers.isEmpty()) {
                return "";
            }
            Map<String, Object> w = writers.get(0);
            return "（进程 " + w.getOrDefault("comm", "?") + "/" + w.getOrDefault("pid", "?") + " 正在写入）";
        }

        private String humanRate(long bytesPerSec) {
            if (bytesPerSec >= 1024 * 1024) {
                return String.format("%.1fMB/s", bytesPerSec / (1024.0 * 1024.0));
            }
            if (bytesPerSec >= 1024) {
                return String.format("%.1fKB/s", bytesPerSec / 1024.0);
            }
            return bytesPerSec + "B/s";
        }

        /**
         * 从 sensed 中提取 disk_sense 的文件级候选。两种数据形态都要兼容：
         * 全量感知模式下 sensed.get("disk_sense") 是原始 Map（含 topFiles 列表）；
         * ReAct 模式下键名形如 "disk_sense#r1"，值是 MCP 文本内容（JSON 字符串），需要反序列化。
         */
        @SuppressWarnings("unchecked")
        private List<FileCandidate> extractDiskCandidates(Object sensedObj) {
            List<FileCandidate> result = new ArrayList<>();
            if (!(sensedObj instanceof Map<?, ?> sensed)) {
                return result;
            }
            for (Map.Entry<?, ?> e : sensed.entrySet()) {
                if (!String.valueOf(e.getKey()).startsWith("disk_sense")) {
                    continue;
                }
                Object value = e.getValue();
                List<Map<String, Object>> topFiles = null;
                if (value instanceof Map<?, ?> vm && vm.get("topFiles") instanceof List<?> list) {
                    topFiles = (List<Map<String, Object>>) list;
                } else if (value instanceof String s) {
                    topFiles = parseTopFilesJson(s);
                }
                if (topFiles == null) {
                    continue;
                }
                for (Map<String, Object> m : topFiles) {
                    Object p = m.get("path");
                    if (p == null) continue;
                    result.add(new FileCandidate(String.valueOf(p),
                            longVal(m.get("sizeBytes")), longVal(m.get("mtimeEpochSeconds"))));
                }
            }
            return result;
        }

        private List<Map<String, Object>> parseTopFilesJson(String json) {
            try {
                var node = mapper.readTree(json);
                var tfNode = node.path("topFiles");
                if (!tfNode.isArray()) {
                    return null;
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (var item : tfNode) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", item.path("path").asText(null));
                    m.put("sizeBytes", item.path("sizeBytes").asLong(0));
                    m.put("mtimeEpochSeconds", item.path("mtimeEpochSeconds").asLong(0));
                    out.add(m);
                }
                return out;
            } catch (Exception ignored) {
                return null;
            }
        }

        private long longVal(Object o) {
            if (o instanceof Number n) {
                return n.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    /** 知识检索节点：在感知之后、推理之前，检索可引用的运维依据（文档/规则/历史）。 */
    private class RetrieveNode implements AgentNode {
        public String stage() { return TraceStage.RETRIEVE.name(); }
        public String agentName() { return "ContextRetriever"; }
        public Map<String, Object> run(AgentContext ctx) {
            String instruction = String.valueOf(ctx.state().get("instruction"));
            String traceId = String.valueOf(ctx.state().get("traceId"));
            List<Evidence> evidence = retriever.retrieve(instruction, 4, traceId);
            ctx.state().put("retrieval", evidence);
            List<Map<String, Object>> citations = new ArrayList<>();
            for (Evidence ev : evidence) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("title", ev.title());
                c.put("source", ev.source());
                c.put("kind", ev.kind());
                c.put("score", ev.score());
                c.put("snippet", ev.snippet());
                citations.add(c);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", evidence.size());
            out.put("citations", citations);
            out.put("degraded", evidence.isEmpty());
            return out;
        }
    }

    private class ReasonNode implements AgentNode {
        public String stage() { return TraceStage.REASON.name(); }
        public String agentName() { return "ReasoningAgent"; }
        public Map<String, Object> run(AgentContext ctx) {
            String instruction = String.valueOf(ctx.state().get("instruction"));
            String sensedJson;
            try {
                sensedJson = mapper.writeValueAsString(ctx.state().get("sensed"));
            } catch (Exception e) {
                sensedJson = "{}";
            }
            StringBuilder prompt = new StringBuilder("INSTRUCTION: ").append(instruction)
                    .append("\n\n[环境感知]\n").append(sensedJson);
            // 仅在真实模型下注入检索到的依据，避免改变 Mock 的确定性回放（保障评测可复现）。
            int evidenceUsed = 0;
            if (llm.isReal()) {
                Object ret = ctx.state().get("retrieval");
                if (ret instanceof List<?> list && !list.isEmpty()) {
                    prompt.append("\n\n[运维知识/依据]\n");
                    int i = 1;
                    for (Object o : list) {
                        if (o instanceof Evidence ev) {
                            prompt.append(i++).append(". [").append(ev.source()).append("] ")
                                    .append(ev.title()).append(" — ").append(ev.snippet()).append("\n");
                        }
                    }
                    evidenceUsed = i - 1;
                    prompt.append("\n请优先依据以上知识与安全规则给出方案，命令需最小化、可回滚，并避免触碰关键路径。"
                            + "命令不得包含 sudo/su/doas，权限由受控执行器管理；服务变更必须包含执行前只读检查与执行后复核。");
                }
                // 诊断证据继承：仅真实模型下拼接，Mock 路径不受影响（同一原则：不改变确定性回放）。
                // 严格只拼 tool/target/summary 三个字段——绝不能带用户上一轮指令原文或 LLM 上一轮
                // 结论，否则退回「叙事继承」，重新打开跨轮渐进式攻击的镀金面，见设计文档硬性约束。
                Object recentEvidenceObj = ctx.state().get("recentEvidence");
                if (recentEvidenceObj instanceof List<?> evList && !evList.isEmpty()) {
                    prompt.append("\n\n[近期诊断证据（本会话之前轮次采集，仅供参考，以本轮实际感知为准）]\n");
                    for (Object o : evList) {
                        if (o instanceof EvidenceRecord ev) {
                            prompt.append("- [").append(ev.tool()).append("]")
                                    .append(ev.target() != null ? " 目标=" + ev.target() : "")
                                    .append(": ").append(ev.summary()).append("\n");
                        }
                    }
                }
            }
            String raw = llm.chat(prompt.toString());
            PlanResult plan = parsePlan(raw);
            ctx.state().put("plan", plan);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", plan.getSummary());
            out.put("steps", plan.getSteps());
            out.put("evidenceUsed", evidenceUsed);
            out.put("_model", llm.providerName());
            out.put("_confidence", plan.getConfidence());
            return out;
        }
    }

    private class PlanNode implements AgentNode {
        public String stage() { return TraceStage.PLAN.name(); }
        public String agentName() { return "ExecutionPlanner"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            String instruction = String.valueOf(ctx.state().get("instruction"));
            PlanResult plan = (PlanResult) ctx.state().get("plan");
            List<Evidence> evidence = new ArrayList<>();
            Object ret = ctx.state().get("retrieval");
            if (ret instanceof List<?> list) {
                evidence = (List<Evidence>) list;
            }
            OpsExecutionPlan executionPlan = executionPlanner.build(instruction, plan, evidence);
            ctx.state().put("executionPlan", executionPlan);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("strategy", executionPlan.getStrategy());
            out.put("executionMode", executionPlan.getExecutionMode());
            out.put("summary", executionPlan.getSummary());
            out.put("commandCount", executionPlan.getCommandCount());
            out.put("tasks", executionPlan.getTasks());
            return out;
        }
    }

    private class GuardNode implements AgentNode {
        public String stage() { return TraceStage.GUARD.name(); }
        public String agentName() { return "IntentRiskGuard"; }
        public Map<String, Object> run(AgentContext ctx) {
            PlanResult plan = (PlanResult) ctx.state().get("plan");
            List<RiskDecision> decisions = new ArrayList<>();
            RiskLevel worst = RiskLevel.READONLY;
            RiskDecision intentDecision = guard.evaluateIntent(String.valueOf(ctx.state().get("instruction")));
            if (intentDecision != null) {
                decisions.add(intentDecision);
                worst = RiskLevel.max(worst, intentDecision.level());
            }
            if (plan != null) {
                for (PlanStep step : plan.getSteps()) {
                    RiskDecision d = guard.evaluate(step.getCommand());
                    d = guard.enforceCriticalServiceIntentFloor(
                            String.valueOf(ctx.state().get("instruction")), d);
                    decisions.add(d);
                    worst = RiskLevel.max(worst, d.level());
                }
            }
            // 动态意图审计：交叉校验候选命令是否服务于用户原始意图（仅真实模型，只升级不降级）。
            IntentConsistencyChecker.Verdict verdict = IntentConsistencyChecker.Verdict.pass();
            if (plan != null && !plan.getSteps().isEmpty()) {
                List<String> cmds = new ArrayList<>();
                for (PlanStep step : plan.getSteps()) {
                    cmds.add(step.getCommand());
                }
                verdict = consistencyChecker.check(String.valueOf(ctx.state().get("instruction")), cmds);
                if (verdict.escalated() && worst != RiskLevel.BLOCK) {
                    // 检出意图偏差/幻觉/夹带：强制升级为不可逆级，落入人工确认闸门。
                    worst = RiskLevel.max(worst, RiskLevel.IRREVERSIBLE);
                }
            }

            ctx.state().put("decisions", decisions);
            ctx.state().put("worstLevel", worst);
            ctx.state().put("intentConsistency", verdict);
            Object executionPlan = ctx.state().get("executionPlan");
            if (executionPlan instanceof OpsExecutionPlan ep) {
                executionPlanner.attachDecisions(ep, decisions);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("decisions", decisions);
            out.put("worstLevel", worst);
            out.put("intentConsistent", verdict.consistent());
            if (verdict.escalated()) {
                out.put("intentConcern", verdict.concern());
            }
            out.put("executionPlan", executionPlan);
            return out;
        }
    }

    /**
     * 跨轮攻击检测节点：在 GUARD 之后、EXECUTE 之前，此时单轮注入结果、四级风险裁决都已齐备，
     * 能算「风险升级」「目标跳变」这些需要后置信息的信号。本节点只更新会话状态、不 halt——
     * READONLY 决策仍应放行，真正的执行侧拦截由 ExecuteNode 读 session.readOnlyLockdown 完成。
     */
    private class CrossTurnGuardNode implements AgentNode {
        public String stage() { return TraceStage.CROSS_TURN_GUARD.name(); }
        public String agentName() { return "CrossTurnThreatMonitor"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            Object sessionObj = ctx.state().get("session");
            Map<String, Object> out = new LinkedHashMap<>();
            if (!(sessionObj instanceof SessionContext session)) {
                return out;
            }
            String instruction = String.valueOf(ctx.state().get("instruction"));
            boolean injectionHit = Boolean.TRUE.equals(ctx.state().get("injectionBlocked"));
            RiskLevel worst = (RiskLevel) ctx.state().getOrDefault("worstLevel", RiskLevel.READONLY);
            List<RiskDecision> decisions = (List<RiskDecision>) ctx.state()
                    .getOrDefault("decisions", new ArrayList<RiskDecision>());
            String targetSlot = crossTurnMonitor.extractTargetSlot(decisions);
            ctx.state().put("thisTargetSlot", targetSlot);

            CrossTurnThreatMonitor.Assessment assessment = crossTurnMonitor.assess(
                    session, instruction, injectionHit, worst, targetSlot);
            ctx.state().put("crossTurnAssessment", assessment);
            if (assessment.triggered()) {
                session.setReadOnlyLockdown(true);
            }

            out.put("delta", assessment.delta());
            out.put("totalSuspicion", assessment.totalSuspicion());
            out.put("triggered", assessment.triggered());
            out.put("readOnlyLockdown", session.isReadOnlyLockdown());
            if (assessment.reason() != null) {
                out.put("reason", assessment.reason());
            }
            return out;
        }
    }

    private class ExecuteNode implements AgentNode {
        public String stage() { return TraceStage.EXECUTE.name(); }
        public String agentName() { return "LeastPrivilegeExecutor"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            boolean confirm = Boolean.TRUE.equals(ctx.state().get("confirm"));
            boolean highImpactConfirmed = Boolean.TRUE.equals(ctx.state().get("highImpactConfirmed"));
            String traceId = String.valueOf(ctx.state().get("traceId"));
            List<RiskDecision> decisions = (List<RiskDecision>) ctx.state().getOrDefault("decisions", new ArrayList<RiskDecision>());
            Object sessionObj = ctx.state().get("session");
            boolean sessionLockedDown = sessionObj instanceof SessionContext s && s.isReadOnlyLockdown();
            int maxSteps = execProps.getMaxStepsPerRequest();
            int executedCount = 0;
            int cappedCount = 0;
            List<Map<String, Object>> execResults = new ArrayList<>();
            List<Map<String, Object>> verifications = new ArrayList<>();
            for (RiskDecision d : decisions) {
                Map<String, Object> er = new LinkedHashMap<>();
                er.put("command", d.command());
                er.put("level", d.level());
                er.put("reason", d.reason());
                if (d.level() == RiskLevel.BLOCK) {
                    er.put("executed", false);
                    er.put("output", "命中安全红线，已拒绝执行");
                } else if (d.level() != RiskLevel.READONLY && sessionLockedDown) {
                    er.put("executed", false);
                    er.put("sessionLockedDown", true);
                    er.put("output", "会话已因跨轮渐进式攻击累积命中阈值，降级为只读，本条变更类指令已拒绝；"
                            + "发起「新会话」可解除。");
                } else if (d.level() == RiskLevel.IRREVERSIBLE && (!confirm || !highImpactConfirmed)) {
                    // 后续分析与权威状态解析都必须看到“尚未完成有效确认”，不能仅执行层停住。
                    ctx.state().put("confirm", false);
                    er.put("executed", false);
                    er.put("requiresApproval", true);
                    er.put("requiresBackup", true);
                    er.put("requiresDryRun", true);
                    er.put("requiresStrongConfirmation", true);
                    er.put("confirmationPhrase", HIGH_IMPACT_CONFIRMATION);
                    er.put("preflightChecks", highImpactPreflightLabels(d.command()));
                    er.put("output", "高影响操作需完成执行前检查、恢复点记录，并输入强确认短语后才会执行");
                } else if (d.level().requiresApproval() && !confirm) {
                    er.put("executed", false);
                    er.put("requiresApproval", d.requiresApproval());
                    er.put("requiresBackup", d.requiresBackup());
                    er.put("requiresDryRun", d.requiresDryRun());
                    er.put("output", d.level() == RiskLevel.IRREVERSIBLE
                            ? "高危不可逆操作需人工确认、执行前备份/快照与 dry-run 验证"
                            : "受限变更需人工二次确认后才会执行");
                } else if (executedCount >= maxSteps) {
                    cappedCount++;
                    er.put("executed", false);
                    er.put("output", "[circuit] 已达单次最大执行轮次上限(" + maxSteps + ")，为保证关键任务确定性、防止失控与死循环，剩余指令暂停执行，请分批处理或人工介入");
                } else {
                    List<String> argv = tokenize(d.command());
                    boolean realMutation = d.level() != RiskLevel.READONLY && !execProps.isDryRun();
                    boolean backupBlocked = false;
                    boolean preflightBlocked = false;
                    // TOCTOU 复检：磁盘候选表生成之后到这条删除/截断命令真正执行之间，可能已经过了
                    // 用户勾选、二次确认这两轮交互，文件可能在此期间被进程重新打开并开始写入。
                    // 候选门禁只在候选表生成那一刻查过一次 lsof，这里在真正执行前原地复检，
                    // 只跳过这一条命令，同批次里未受影响的其它命令照常执行。
                    boolean reoccupied = realMutation && isDestructiveFileCommand(argv)
                            && isHeldOpen(argv.get(argv.size() - 1));
                    if (reoccupied) {
                        er.put("executed", false);
                        er.put("skippedReoccupied", true);
                        er.put("output", "该文件在候选生成或计划确认之后被进程重新打开、可能仍在写入，"
                                + "为安全起见已跳过本条命令的执行，请重新发起清理请求核对最新状态");
                    }
                    HighImpactPreparation preparation = HighImpactPreparation.notApplicable();
                    if (!reoccupied && realMutation && d.level() == RiskLevel.IRREVERSIBLE) {
                        preparation = prepareHighImpact(argv);
                        if (preparation.applicable()) {
                            er.put("preflight", preparation.checks());
                            er.put("recoveryPoint", preparation.recoveryPoint());
                            er.put("dryRunEquivalent", true);
                            if (!preparation.ready()) {
                                preflightBlocked = true;
                                er.put("executed", false);
                                er.put("preflightFailed", true);
                                er.put("output", "高影响操作执行前检查未通过，已停止真实执行：" + preparation.reason());
                            }
                        }
                    }
                    // 高影响真实变更必须先建立恢复点；备份/快照失败时停止该命令的真实执行，
                    // 不能以「已提示」代替门禁（fail-closed）。
                    if (!reoccupied && !preflightBlocked && realMutation) {
                        if (preparation.applicable()) {
                            er.put("preBackup", preparation.recoveryPoint());
                            er.put("backupReady", preparation.ready());
                            er.put("backupReason", preparation.reason());
                        } else {
                            PreChangeBackup.BackupOutcome backup = preChangeBackup.backupWithOutcome(traceId, argv);
                            if (!backup.records().isEmpty()) {
                                er.put("preBackup", backup.records());
                            }
                            er.put("backupReady", backup.ready());
                            er.put("backupReason", backup.reason());
                            if (d.requiresBackup() && !backup.ready()) {
                                backupBlocked = true;
                                er.put("executed", false);
                                er.put("backupFailed", true);
                                er.put("output", "高影响操作未能建立备份或快照，已停止真实执行：" + backup.reason());
                            }
                        }
                    }
                    if (!reoccupied && !preflightBlocked && !backupBlocked) {
                        ExecResult res = d.level() == RiskLevel.READONLY
                                ? runReadOnlySafely(d.command(), argv)
                                : executor.run(argv);
                        executedCount++;
                        er.put("executed", true);
                        er.put("exitCode", res.exitCode());
                        er.put("dryRun", res.dryRun());
                        if (res.execId() != null) {
                            er.put("execId", res.execId());
                        }
                        String output = res.stdout();
                        if (res.stderr() != null && !res.stderr().isBlank()) {
                            output = output + "\n[stderr] " + res.stderr();
                        }
                        er.put("output", sanitizer == null ? output : sanitizer.sanitize(output));
                        // VERIFY 真闭环：变更真实落地后立刻跑派生的只读复核探针，结果回填任务计划。
                        if (realMutation && !res.dryRun() && res.success()) {
                            ExecutionPlanner.VerifySpec spec = executionPlanner.deriveVerification(d.command());
                            if (spec != null) {
                                ExecResult vres = executor.runReadOnly(spec.argv());
                                Map<String, Object> v = new LinkedHashMap<>();
                                v.put("command", d.command());
                                v.put("verifyCommand", String.join(" ", spec.argv()));
                                v.put("expectation", spec.expectation());
                                v.put("exitCode", vres.exitCode());
                                v.put("passed", spec.passed(vres.exitCode()));
                                verifications.add(v);
                                er.put("verify", v);
                            }
                        } else if (realMutation && !res.dryRun() && !res.success()) {
                            er.put("verifySkipped", "mutation command failed; verification was not run");
                        }
                    }
                }
                execResults.add(er);
            }
            ctx.state().put("execResults", execResults);
            Object executionPlan = ctx.state().get("executionPlan");
            if (executionPlan instanceof OpsExecutionPlan ep) {
                executionPlanner.attachExecutionResults(ep, execResults);
                // 有真实复核结果时覆盖 VERIFY 任务状态（VERIFIED / VERIFY_FAILED）；
                // dry-run 与 mock 路径 verifications 恒为空，保持既有口径不变。
                executionPlanner.applyVerification(ep, verifications);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("execResults", execResults);
            out.put("executionPlan", executionPlan);
            out.put("executedCount", executedCount);
            out.put("maxStepsPerRequest", maxSteps);
            if (!verifications.isEmpty()) {
                out.put("verifications", verifications);
            }
            if (cappedCount > 0) {
                out.put("cappedCount", cappedCount);
            }
            return out;
        }
    }

    private class AnalyzeNode implements AgentNode {
        public String stage() { return TraceStage.ANALYZE.name(); }
        public String agentName() { return "RootCauseAnalyzer"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            String instruction = String.valueOf(ctx.state().get("instruction"));
            PlanResult plan = (PlanResult) ctx.state().get("plan");
            List<Map<String, Object>> execResults = (List<Map<String, Object>>) ctx.state().getOrDefault("execResults", new ArrayList<>());
            List<String> summaries = new ArrayList<>();
            for (Map<String, Object> er : execResults) {
                summaries.add(er.get("command") + " => " + riskLevelCn(er.get("level"))
                        + (Boolean.TRUE.equals(er.get("executed")) ? "(已执行)" : "(未执行)"));
            }
            // 直接复用权威状态解析器，避免“预检失败/复核失败但分析文字仍称已执行”的口径分裂。
            String outcome = outcomeResolver.resolve(ctx.state()).name();
            String analysis = analyzer.analyze(instruction, plan, summaries, outcome);
            ctx.state().put("analysis", analysis);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("analysis", analysis);
            return out;
        }
    }

    // ============ 工具方法 ============

    /** 返回前端强确认卡片可直接展示的确定性预检项目。 */
    private List<String> highImpactPreflightLabels(String command) {
        List<String> argv = tokenize(command == null ? "" : command);
        if (isSystemctlServiceChange(argv)) {
            String unit = argv.get(2);
            List<String> labels = new ArrayList<>();
            labels.add("systemctl cat " + unit + "（确认服务单元存在）");
            if (isNginxUnit(unit)) labels.add("nginx -t（验证配置）");
            labels.add("systemctl is-active/is-enabled " + unit + "（记录执行前状态）");
            return labels;
        }
        return List.of("建立目标备份或快照", "执行后运行只读复核探针");
    }

    /**
     * 核心 systemd 服务变更的 dry-run 等价预检与恢复点：验证 unit、校验 nginx 配置，
     * 并记录变更前 active/enabled 状态。任一必要检查失败即 fail-closed。
     */
    private HighImpactPreparation prepareHighImpact(List<String> argv) {
        if (!isSystemctlServiceChange(argv)) return HighImpactPreparation.notApplicable();
        String unit = argv.get(2);
        List<Map<String, Object>> checks = new ArrayList<>();

        ExecResult unitCheck = executor.runReadOnly(List.of("systemctl", "cat", unit));
        checks.add(preflightResult("systemctl cat " + unit, "服务单元必须存在", unitCheck,
                unitCheck.success()));
        boolean ready = unitCheck.success();

        if (isNginxUnit(unit)) {
            ExecResult configCheck = executor.runReadOnly(List.of("nginx", "-t"));
            checks.add(preflightResult("nginx -t", "nginx 配置语法必须通过", configCheck,
                    configCheck.success()));
            ready = ready && configCheck.success();
        }

        ExecResult active = executor.runReadOnly(List.of("systemctl", "is-active", unit));
        ExecResult enabled = executor.runReadOnly(List.of("systemctl", "is-enabled", unit));
        checks.add(preflightResult("systemctl is-active " + unit, "记录变更前运行状态", active, true));
        checks.add(preflightResult("systemctl is-enabled " + unit, "记录变更前启用状态", enabled, true));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "systemd-state-snapshot");
        snapshot.put("unit", unit);
        snapshot.put("activeBefore", compactExecOutput(active));
        snapshot.put("enabledBefore", compactExecOutput(enabled));
        snapshot.put("captured", true);
        List<Map<String, Object>> recoveryPoint = List.of(snapshot);
        String reason = ready
                ? "服务单元、配置预检通过，执行前状态快照已记录"
                : "服务单元不存在或配置校验失败";
        return new HighImpactPreparation(true, ready, checks, recoveryPoint, reason);
    }

    private Map<String, Object> preflightResult(String command, String expectation,
                                                ExecResult result, boolean passed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("expectation", expectation);
        out.put("exitCode", result == null ? -1 : result.exitCode());
        out.put("passed", passed);
        out.put("output", compactExecOutput(result));
        return out;
    }

    private String compactExecOutput(ExecResult result) {
        if (result == null) return "无结果";
        String value = result.stdout();
        if (result.stderr() != null && !result.stderr().isBlank()) {
            value = (value == null ? "" : value) + "\n" + result.stderr();
        }
        value = value == null ? "" : value.trim();
        if (sanitizer != null) value = sanitizer.sanitize(value);
        return truncate(value, 600);
    }

    private boolean isSystemctlServiceChange(List<String> argv) {
        if (argv == null || argv.size() < 3) return false;
        String binary = commandBaseName(argv.get(0)).toLowerCase(java.util.Locale.ROOT);
        String action = argv.get(1).toLowerCase(java.util.Locale.ROOT);
        return "systemctl".equals(binary)
                && List.of("start", "restart", "reload", "stop").contains(action)
                && argv.get(2).matches("[A-Za-z0-9_.@-]+");
    }

    private boolean isNginxUnit(String unit) {
        return unit != null && ("nginx".equalsIgnoreCase(unit) || "nginx.service".equalsIgnoreCase(unit));
    }

    private String commandBaseName(String token) {
        int slash = token == null ? -1 : token.lastIndexOf('/');
        return slash >= 0 ? token.substring(slash + 1) : String.valueOf(token);
    }

    private static final java.util.Set<String> GREP_NO_MATCH_TOOLS =
            java.util.Set.of("grep", "egrep", "fgrep");

    /**
     * grep 系族命令 exit=1 表示"未匹配到任何行"，是合法的只读诊断结果（例如"日志里没有 error"），
     * 不应被判定为执行失败——否则一次干净的健康检查会被 {@link OpsOutcomeResolver} 误报成 EXECUTION_FAILED。
     */
    private ExecResult normalizeReadOnlyExit(List<String> argv, ExecResult res) {
        if (res.exitCode() != 1 || argv == null || argv.isEmpty()) {
            return res;
        }
        String bin = commandBaseName(argv.get(0)).toLowerCase(java.util.Locale.ROOT);
        if (!GREP_NO_MATCH_TOOLS.contains(bin)) {
            return res;
        }
        return new ExecResult(0, res.stdout(), res.stderr(), res.dryRun(), res.elapsedMs(), res.execId());
    }

    private record HighImpactPreparation(boolean applicable, boolean ready,
                                         List<Map<String, Object>> checks,
                                         List<Map<String, Object>> recoveryPoint,
                                         String reason) {
        private static HighImpactPreparation notApplicable() {
            return new HighImpactPreparation(false, false, List.of(), List.of(), "不适用服务预检");
        }
    }

    private PlanResult parsePlan(String raw) {
        try {
            String json = extractJson(raw);
            PlanResult plan = mapper.readValue(json, PlanResult.class);
            if (plan.getSteps() != null) {
                for (PlanStep step : plan.getSteps()) {
                    if (step != null && step.getCommand() != null) {
                        // 模型无权选择 sudo 身份；统一移交最小权限执行器，且保证护栏看到真正二进制。
                        step.setCommand(guard.canonicalizeModelCommand(step.getCommand()));
                    }
                }
            }
            return plan;
        } catch (Exception e) {
            PlanResult fallback = new PlanResult();
            fallback.setSummary("推理输出解析失败，已降级为空计划：" + e.getMessage());
            fallback.setConfidence(0.0);
            return fallback;
        }
    }

    /** 四级风险的中文展示名，用于根因分析叙述文本（不影响内部枚举值/序列化字段，仅此处拼接展示）。 */
    private String riskLevelCn(Object level) {
        String l = String.valueOf(level).toUpperCase(java.util.Locale.ROOT);
        return switch (l) {
            case "READONLY" -> "只读";
            case "EXECUTABLE" -> "受控变更";
            case "IRREVERSIBLE" -> "高影响";
            case "BLOCK" -> "红线";
            default -> "待定";
        };
    }

    /**
     * 从 ctx.state("sensed") 提取本轮诊断证据。只取工具原始返回的结构化事实，不碰用户指令原文、
     * 不碰 LLM 生成的方案文本——这是跟磁盘候选门禁 {@code DiskCandidateGateNode.extractDiskCandidates}
     * 同样要兼容两种数据形态的原因：全量感知模式下 sensed.get(toolName) 是原始 Map，
     * ReAct 模式下键名形如 "toolName#r1"、值是 MCP 文本内容（字符串）。
     */
    @SuppressWarnings("unchecked")
    private List<EvidenceRecord> extractEvidenceRecords(AgentContext ctx) {
        List<EvidenceRecord> out = new ArrayList<>();
        Object sensedObj = ctx.state().get("sensed");
        if (!(sensedObj instanceof Map<?, ?> sensed)) {
            return out;
        }
        String traceId = String.valueOf(ctx.state().get("traceId"));
        long now = System.currentTimeMillis();
        int maxChars = crossTurnProps.getEvidenceMaxCharsPerTurn();
        for (Map.Entry<?, ?> e : sensed.entrySet()) {
            String rawKey = String.valueOf(e.getKey());
            String tool = rawKey.contains("#") ? rawKey.substring(0, rawKey.indexOf('#')) : rawKey;
            Object value = e.getValue();
            String target = null;
            String summary;
            if (value instanceof Map<?, ?> vm) {
                target = firstNonNull(vm, "du_target", "unit", "path", "target", "scope");
                summary = truncateEvidence(safeJson(value), maxChars);
            } else {
                summary = truncateEvidence(String.valueOf(value), maxChars);
            }
            out.add(new EvidenceRecord(tool, target, now, traceId, summary));
        }
        return out;
    }

    private String firstNonNull(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncateEvidence(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…[截断]";
    }

    private String extractJson(String s) {
        if (s == null) return "{}";
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return "{}";
    }

    /** 删除/截断类命令在磁盘候选门禁生成候选表之后可能已过期（用户勾选、确认之间的窗口里
     *  可能有进程重新打开了该文件并开始写入）；执行前用同一探测方式复检一次，闭合 TOCTOU 窗口。 */
    private static final List<String> DESTRUCTIVE_FILE_VERBS = List.of("rm", "rmdir", "truncate");

    private boolean isDestructiveFileCommand(List<String> argv) {
        return !argv.isEmpty() && DESTRUCTIVE_FILE_VERBS.contains(argv.get(0));
    }

    /** lsof 探测文件是否被进程持有句柄：退出码 0 且有输出 = 命中进程，视为「仍在写入」。
     *  探测失败（如 lsof 不可用）时保守放行，不视为占用——不阻断主链路，但会少一个判定信号。 */
    private boolean isHeldOpen(String path) {
        try {
            ExecResult r = executor.runReadOnly(List.of("lsof", path));
            return r.exitCode() == 0 && r.stdout() != null && !r.stdout().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** 与护栏一致的安全分词：仅按空白切分并处理引号，不做 shell 解释。 */
    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (char c : s.trim().toCharArray()) {
            if (quote != 0) {
                if (c == quote) { quote = 0; } else { cur.append(c); }
                has = true;
            } else if (c == '\'' || c == '"') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) { out.add(cur.toString()); }
        return out;
    }

    private ExecResult runReadOnlySafely(String command, List<String> fallbackArgv) {
        CommandChain chain = parseReadOnlyChain(command);
        if (chain.error() != null) {
            return new ExecResult(-1, "", chain.error(), false, 0);
        }
        if (!chain.compound()) {
            return normalizeReadOnlyExit(fallbackArgv, executor.runReadOnly(fallbackArgv));
        }

        long start = System.currentTimeMillis();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int lastExit = 0;
        for (ReadOnlyGroup group : chain.groups()) {
            if (group.connectorBefore() == Connector.AND && lastExit != 0) {
                appendOut(stdout, "[skipped] previous command failed; && group was not executed");
                continue;
            }
            for (CommandStage stage : group.stages()) {
                RiskDecision decision = guard.evaluate(stage.raw());
                if (decision.level() != RiskLevel.READONLY) {
                    return new ExecResult(-1, "", "compound readonly validation failed: " + decision.reason(),
                            false, System.currentTimeMillis() - start);
                }
            }
            List<List<String>> pipeline = new ArrayList<>();
            for (CommandStage stage : group.stages()) {
                pipeline.add(stage.argv());
            }
            ExecResult res = pipeline.size() == 1
                    ? executor.runReadOnly(pipeline.get(0))
                    : executor.runReadOnlyPipeline(pipeline);
            res = normalizeReadOnlyExit(pipeline.get(pipeline.size() - 1), res);
            lastExit = res.exitCode();
            appendOut(stdout, res.stdout());
            appendOut(stderr, res.stderr());
        }
        return new ExecResult(lastExit, stdout.toString(), stderr.toString(), false,
                System.currentTimeMillis() - start);
    }

    private void appendOut(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(System.lineSeparator());
        }
        target.append(value);
    }

    private CommandChain parseReadOnlyChain(String command) {
        if (command == null || command.isBlank()) {
            return new CommandChain(List.of(), false, "empty command");
        }
        List<ReadOnlyGroup> groups = new ArrayList<>();
        List<CommandStage> currentStages = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        Connector nextConnector = Connector.NONE;
        boolean compound = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '|') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    return new CommandChain(List.of(), true, "unsupported readonly connector: ||");
                }
                String err = addStage(currentStages, cur.toString());
                if (err != null) return new CommandChain(List.of(), true, err);
                cur.setLength(0);
                compound = true;
                continue;
            }
            if (c == ';') {
                String err = addStage(currentStages, cur.toString());
                if (err != null) return new CommandChain(List.of(), true, err);
                groups.add(new ReadOnlyGroup(nextConnector, currentStages));
                currentStages = new ArrayList<>();
                cur.setLength(0);
                nextConnector = Connector.ALWAYS;
                compound = true;
                continue;
            }
            if (c == '&') {
                if (i > 0 && command.charAt(i - 1) == '>') {
                    cur.append(c);
                    continue;
                }
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    String err = addStage(currentStages, cur.toString());
                    if (err != null) return new CommandChain(List.of(), true, err);
                    groups.add(new ReadOnlyGroup(nextConnector, currentStages));
                    currentStages = new ArrayList<>();
                    cur.setLength(0);
                    nextConnector = Connector.AND;
                    compound = true;
                    i++;
                    continue;
                }
                return new CommandChain(List.of(), true, "unsupported readonly connector: &");
            }
            cur.append(c);
        }
        if (quote != 0) {
            return new CommandChain(List.of(), compound, "unterminated quote in command");
        }
        String err = addStage(currentStages, cur.toString());
        if (err != null) return new CommandChain(List.of(), compound, err);
        groups.add(new ReadOnlyGroup(nextConnector, currentStages));
        return new CommandChain(groups, compound, null);
    }

    private String addStage(List<CommandStage> stages, String raw) {
        String segment = raw == null ? "" : raw.trim();
        if (segment.isBlank()) {
            return "empty command segment";
        }
        List<String> argv = tokenize(segment);
        if (argv.isEmpty()) {
            return "unparseable command segment";
        }
        stages.add(new CommandStage(segment, argv));
        return null;
    }

    private enum Connector { NONE, ALWAYS, AND }
    private record CommandStage(String raw, List<String> argv) {}
    private record ReadOnlyGroup(Connector connectorBefore, List<CommandStage> stages) {}
    private record CommandChain(List<ReadOnlyGroup> groups, boolean compound, String error) {}

    private void evictExpiredPlans() {
        long cutoff = System.currentTimeMillis() - PLAN_TTL_MS;
        planCache.entrySet().removeIf(entry -> entry.getValue().createdAtMs < cutoff);
    }

    /** 候选表纯内存、不落盘（跟只读降级一样，重启即视为对话重来，用户重新问一遍即可，成本低）。 */
    private void evictExpiredCandidates() {
        long cutoff = System.currentTimeMillis() - PLAN_TTL_MS;
        candidateCache.entrySet().removeIf(entry -> entry.getValue().createdAtMs() < cutoff);
    }

    /** 断点续跑：待确认计划全量重写落盘（≤200 条，重写代价可忽略）；失败仅告警不阻断主链路。 */
    private synchronized void persistPendingPlans() {
        if (pendingPlanFile == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, CachedPlan> e : planCache.entrySet()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("traceId", e.getKey());
                line.put("instruction", e.getValue().instruction());
                line.put("createdAtMs", e.getValue().createdAtMs());
                line.put("plan", e.getValue().plan());
                line.put("sessionId", e.getValue().sessionId());
                sb.append(mapper.writeValueAsString(line)).append('\n');
            }
            if (pendingPlanFile.getParent() != null) {
                java.nio.file.Files.createDirectories(pendingPlanFile.getParent());
            }
            java.nio.file.Files.writeString(pendingPlanFile, sb.toString());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OpsPipeline.class)
                    .warn("待确认计划落盘失败（不影响内存缓存）：{}", e.getMessage());
        }
    }

    /** 启动时恢复未过期的待确认计划：服务重启后 traceId + confirm=true 仍可执行已审阅的同一计划。 */
    private void loadPendingPlans() {
        if (pendingPlanFile == null || !java.nio.file.Files.exists(pendingPlanFile)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - PLAN_TTL_MS;
        int loaded = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(pendingPlanFile)) {
                if (line.isBlank()) continue;
                try {
                    var node = mapper.readTree(line);
                    String traceId = node.path("traceId").asText("");
                    long createdAtMs = node.path("createdAtMs").asLong(0);
                    if (traceId.isBlank() || createdAtMs < cutoff) continue;
                    PlanResult plan = mapper.treeToValue(node.path("plan"), PlanResult.class);
                    String instruction = node.path("instruction").asText("");
                    String sessionId = node.path("sessionId").asText(null);
                    planCache.put(traceId, new CachedPlan(instruction, plan, createdAtMs, sessionId));
                    loaded++;
                } catch (Exception badLine) {
                    org.slf4j.LoggerFactory.getLogger(OpsPipeline.class)
                            .warn("待确认计划损坏行已跳过：{}", badLine.getMessage());
                }
            }
            if (loaded > 0) {
                org.slf4j.LoggerFactory.getLogger(OpsPipeline.class)
                        .info("断点续跑：从 {} 恢复 {} 条待确认计划", pendingPlanFile, loaded);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OpsPipeline.class)
                    .warn("待确认计划恢复失败（从空缓存启动）：{}", e.getMessage());
        }
    }

    private record CachedPlan(String instruction, PlanResult plan, long createdAtMs, String sessionId) {}

    /** 候选表缓存：与 CachedPlan 平行，不侵入其类型。candidates 即 finalize() 里回填给 resp 的同一份数据。 */
    private record CachedCandidates(String instruction, List<Map<String, Object>> candidates,
                                     long createdAtMs, String sessionId) {}
}
