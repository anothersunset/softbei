package com.zhiqian.ops.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentNode;
import com.zhiqian.ops.agent.AgentRunner;
import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ActiveTool;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.PreChangeBackup;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();
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
                       McpDispatcher mcpDispatcher) {
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

        // 人工确认执行路径：复用缓存的计划，仅重跑感知+检索+校验+执行+分析
        evictExpiredPlans();
        if (confirm && req.getTraceId() != null && planCache.containsKey(req.getTraceId())) {
            return runConfirmed(req.getTraceId(), resp, stepListener);
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

        List<AgentNode> nodes = List.of(
                new ReceiveNode(), new InjectionNode(), new SenseNode(), new RetrieveNode(),
                new ReasonNode(), new PlanNode(), new GuardNode(), new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(trace.getTraceId(), s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);

        finalize(ctx, resp, instruction, confirm);
        audit.complete(trace.getTraceId(), resp.getStatus());
        return resp;
    }

    private ChatResponse runConfirmed(String traceId, ChatResponse resp, Consumer<AgentStep> stepListener) {
        CachedPlan cached = planCache.get(traceId);
        resp.setTraceId(traceId);
        AgentContext ctx = new AgentContext(System.currentTimeMillis(), 0L);
        ctx.state().put("traceId", traceId);
        ctx.state().put("instruction", cached.instruction);
        ctx.state().put("confirm", true);
        // 复用首轮已被审阅过的计划，不重新推理（保证人工确认执行的就是被审阅的同一计划）。
        ctx.state().put("plan", cached.plan);

        // 安全修复(P1)：重跑「感知 + 检索」节点，补全确认执行后处置报告的「感知证据 / 知识依据」，
        // 避免确认链路只剩 校验/执行/分析 三步、retrieval=0 的断层问题。
        List<AgentNode> nodes = List.of(
                new SenseNode(), new RetrieveNode(), new PlanNode(), new GuardNode(), new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(traceId, s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);
        finalize(ctx, resp, cached.instruction, true);
        planCache.remove(traceId);
        persistPendingPlans();
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

        boolean injectionBlocked = Boolean.TRUE.equals(ctx.state().get("injectionBlocked"));
        RiskLevel worst = (RiskLevel) ctx.state().getOrDefault("worstLevel", RiskLevel.READONLY);
        String status;
        String message;
        if (injectionBlocked) {
            status = "INJECTION_BLOCKED";
            message = "检测到提示词注入或高危恶意意图特征，已在入口拦截，未执行任何操作。";
        } else if (worst == RiskLevel.BLOCK) {
            status = "BLOCKED";
            message = "计划中含有命中红线的高危指令，已拒绝执行。";
        } else if (worst.requiresApproval() && !confirm) {
            status = "REVIEW_PENDING";
            message = worst == RiskLevel.IRREVERSIBLE
                    ? "计划中含有高危不可逆指令，需人工确认、dry-run 与执行前备份/影响记录。"
                    : "计划中含有需人工确认的受限变更指令，请确认后重试（confirm=true）。";
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
                planCache.put(resp.getTraceId(), new CachedPlan(instruction, resp.getPlan(), System.currentTimeMillis()));
                persistPendingPlans();
            }
        } else {
            status = "EXECUTED";
            message = "已完成闭环处理。";
        }
        resp.setStatus(status);
        resp.setMessage(message);
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
                    prompt.append("\n请优先依据以上知识与安全规则给出方案，命令需最小化、可回滚，并避免触碰关键路径。");
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
            if (plan != null) {
                for (PlanStep step : plan.getSteps()) {
                    RiskDecision d = guard.evaluate(step.getCommand());
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

    private class ExecuteNode implements AgentNode {
        public String stage() { return TraceStage.EXECUTE.name(); }
        public String agentName() { return "LeastPrivilegeExecutor"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            boolean confirm = Boolean.TRUE.equals(ctx.state().get("confirm"));
            String traceId = String.valueOf(ctx.state().get("traceId"));
            List<RiskDecision> decisions = (List<RiskDecision>) ctx.state().getOrDefault("decisions", new ArrayList<RiskDecision>());
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
                    // 执行前自动备份：变更类命令真实执行前留存目标文件副本，供一键回滚恢复。
                    if (realMutation) {
                        List<Map<String, Object>> backups = preChangeBackup.backup(traceId, argv);
                        if (!backups.isEmpty()) {
                            er.put("preBackup", backups);
                        }
                    }
                    ExecResult res = d.level() == RiskLevel.READONLY
                            ? runReadOnlySafely(d.command(), argv)
                            : executor.run(argv);
                    executedCount++;
                    er.put("executed", true);
                    er.put("exitCode", res.exitCode());
                    er.put("dryRun", res.dryRun());
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
                summaries.add(er.get("command") + " => " + er.get("level") + (Boolean.TRUE.equals(er.get("executed")) ? "(已执行)" : "(未执行)"));
            }
            String analysis = analyzer.analyze(instruction, plan, summaries);
            ctx.state().put("analysis", analysis);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("analysis", analysis);
            return out;
        }
    }

    // ============ 工具方法 ============

    private PlanResult parsePlan(String raw) {
        try {
            String json = extractJson(raw);
            return mapper.readValue(json, PlanResult.class);
        } catch (Exception e) {
            PlanResult fallback = new PlanResult();
            fallback.setSummary("推理输出解析失败，已降级为空计划：" + e.getMessage());
            fallback.setConfidence(0.0);
            return fallback;
        }
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
            return executor.runReadOnly(fallbackArgv);
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
                    planCache.put(traceId, new CachedPlan(instruction, plan, createdAtMs));
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

    private record CachedPlan(String instruction, PlanResult plan, long createdAtMs) {}
}
