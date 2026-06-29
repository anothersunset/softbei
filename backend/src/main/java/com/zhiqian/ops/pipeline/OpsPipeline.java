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
import com.zhiqian.ops.guard.InjectionResult;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.llm.PlanStep;
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
 * 安全护栏编排管线：接收指令 -> 抗注入 -> 感知环境 -> 知识检索 -> 推理决策 -> 安全校验 -> 执行 -> 根因分析。
 * 复用原项目 AgentRunner 「逐节点迭代 + onStep 回调」模式，每步均落盘溯源。
 * 另提供带 stepListener 的 chat 重载，供 SSE 实时逐步推送思维链（不改变裁决与状态机）。
 */
@Service
public class OpsPipeline {
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();

    public OpsPipeline(AgentRunner runner,
                       PromptInjectionDetector injectionDetector,
                       IntentRiskGuard guard,
                       LlmClient llm,
                       RootCauseAnalyzer analyzer,
                       OpsAuditService audit,
                       LeastPrivilegeExecutor executor,
                       List<AgentTool> senseTools,
                       ContextRetriever retriever,
                       ExecProperties execProps) {
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
    }

    public ChatResponse chat(ChatRequest req) {
        return chat(req, null);
    }

    /**
     * 与 {@link #chat(ChatRequest)} 逻辑完全一致，仅额外在每个节点完成时通过 stepListener 实时回调。
     * stepListener 为 null 时与原方法等价，保证同步 REST 路径与评测行为不变。
     */
    public ChatResponse chat(ChatRequest req, Consumer<AgentStep> stepListener) {
        String instruction = req.getInstruction() == null ? "" : req.getInstruction().trim();
        boolean confirm = req.isConfirm();
        ChatResponse resp = new ChatResponse();

        // 人工确认执行路径：复用缓存的计划，仅重跑感知+检索+校验+执行+分析
        if (confirm && req.getTraceId() != null && planCache.containsKey(req.getTraceId())) {
            return runConfirmed(req.getTraceId(), resp, stepListener);
        }

        // 安全修复(P0)：confirm 仅在命中「已缓存的待确认计划」时才生效。
        // 否则一律视为未确认，防止首次请求直接携带 confirm=true（或伪造 traceId）
        // 绕过 REVIEW 人工确认门禁、把变更类指令直接推到执行阶段。
        confirm = false;

        OpsTrace trace = audit.newTrace(instruction);
        resp.setTraceId(trace.getTraceId());
        AgentContext ctx = new AgentContext(System.currentTimeMillis(), 0L);
        ctx.state().put("traceId", trace.getTraceId());
        ctx.state().put("instruction", instruction);
        ctx.state().put("confirm", confirm);

        List<AgentNode> nodes = List.of(
                new ReceiveNode(), new InjectionNode(), new SenseNode(), new RetrieveNode(),
                new ReasonNode(), new GuardNode(), new ExecuteNode(), new AnalyzeNode());
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
                new SenseNode(), new RetrieveNode(), new GuardNode(), new ExecuteNode(), new AnalyzeNode());
        List<AgentStep> steps = runner.run(nodes, ctx, s -> {
            audit.appendStep(traceId, s);
            if (stepListener != null) stepListener.accept(s);
        });
        resp.setSteps(steps);
        finalize(ctx, resp, cached.instruction, true);
        planCache.remove(traceId);
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
        RiskLevel worst = (RiskLevel) ctx.state().getOrDefault("worstLevel", RiskLevel.SAFE);
        // 空计划判定：真实模型在模型侧拒绝高危指令、或推理输出无法解析时，计划为空/无步骤，
        // 此时无任何命令进入校验与执行，不应再以 EXECUTED「已完成闭环」呈现（会误导评委/用户）。
        boolean planEmpty = resp.getPlan() == null
                || resp.getPlan().getSteps() == null
                || resp.getPlan().getSteps().isEmpty();
        String status;
        String message;
        if (injectionBlocked) {
            status = "INJECTION_BLOCKED";
            message = "检测到提示词注入或高危恶意意图特征，已在入口拦截，未执行任何操作。";
        } else if (worst == RiskLevel.BLOCK) {
            status = "BLOCKED";
            message = "计划中含有命中红线的高危指令，已拒绝执行。";
        } else if (worst == RiskLevel.REVIEW && !confirm) {
            status = "REVIEW_PENDING";
            message = "计划中含有需人工确认的变更类指令，请确认后重试（confirm=true）。";
            // 缓存计划供后续确认
            if (resp.getPlan() != null) {
                planCache.put(resp.getTraceId(), new CachedPlan(instruction, resp.getPlan()));
            }
        } else if (planEmpty) {
            status = "NO_OP";
            message = "模型未产出可执行计划（可能在模型侧已拒绝高危指令，或推理输出无法解析），未执行任何操作。";
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
            Map<String, Object> sensed = new LinkedHashMap<>();
            for (AgentTool tool : senseTools) {
                // 主动/动作类工具(如主动巡检)不在被动感知阶段自动执行，仅通过 MCP 或按需触发。
                if (tool instanceof ActiveTool) continue;
                try {
                    sensed.put(tool.name(), tool.run(ctx, Map.of()));
                } catch (Exception e) {
                    sensed.put(tool.name(), "感知失败：" + e.getMessage());
                }
            }
            ctx.state().put("sensed", sensed);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("toolsRun", sensed.size());
            out.put("sensed", sensed);
            return out;
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

    private class GuardNode implements AgentNode {
        public String stage() { return TraceStage.GUARD.name(); }
        public String agentName() { return "IntentRiskGuard"; }
        public Map<String, Object> run(AgentContext ctx) {
            PlanResult plan = (PlanResult) ctx.state().get("plan");
            List<RiskDecision> decisions = new ArrayList<>();
            RiskLevel worst = RiskLevel.SAFE;
            if (plan != null) {
                for (PlanStep step : plan.getSteps()) {
                    RiskDecision d = guard.evaluate(step.getCommand());
                    decisions.add(d);
                    if (d.level() == RiskLevel.BLOCK) {
                        worst = RiskLevel.BLOCK;
                    } else if (d.level() == RiskLevel.REVIEW && worst != RiskLevel.BLOCK) {
                        worst = RiskLevel.REVIEW;
                    }
                }
            }
            ctx.state().put("decisions", decisions);
            ctx.state().put("worstLevel", worst);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("decisions", decisions);
            out.put("worstLevel", worst);
            return out;
        }
    }

    private class ExecuteNode implements AgentNode {
        public String stage() { return TraceStage.EXECUTE.name(); }
        public String agentName() { return "LeastPrivilegeExecutor"; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> run(AgentContext ctx) {
            boolean confirm = Boolean.TRUE.equals(ctx.state().get("confirm"));
            List<RiskDecision> decisions = (List<RiskDecision>) ctx.state().getOrDefault("decisions", new ArrayList<RiskDecision>());
            int maxSteps = execProps.getMaxStepsPerRequest();
            int executedCount = 0;
            int cappedCount = 0;
            List<Map<String, Object>> execResults = new ArrayList<>();
            for (RiskDecision d : decisions) {
                Map<String, Object> er = new LinkedHashMap<>();
                er.put("command", d.command());
                er.put("level", d.level());
                er.put("reason", d.reason());
                if (d.level() == RiskLevel.BLOCK) {
                    er.put("executed", false);
                    er.put("output", "命中安全红线，已拒绝执行");
                } else if (d.level() == RiskLevel.REVIEW && !confirm) {
                    er.put("executed", false);
                    er.put("output", "需人工二次确认后才会执行");
                } else if (executedCount >= maxSteps) {
                    cappedCount++;
                    er.put("executed", false);
                    er.put("output", "[circuit] 已达单次最大执行轮次上限(" + maxSteps + ")，为保证关键任务确定性、防止失控与死循环，剩余指令暂停执行，请分批处理或人工介入");
                } else {
                    List<String> argv = tokenize(d.command());
                    ExecResult res = d.level() == RiskLevel.SAFE
                            ? executor.runReadOnly(argv)
                            : executor.run(argv);
                    executedCount++;
                    er.put("executed", true);
                    er.put("exitCode", res.exitCode());
                    er.put("dryRun", res.dryRun());
                    String output = res.stdout();
                    if (res.stderr() != null && !res.stderr().isBlank()) {
                        output = output + "\n[stderr] " + res.stderr();
                    }
                    er.put("output", output);
                }
                execResults.add(er);
            }
            ctx.state().put("execResults", execResults);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("execResults", execResults);
            out.put("executedCount", executedCount);
            out.put("maxStepsPerRequest", maxSteps);
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

    private record CachedPlan(String instruction, PlanResult plan) {}
}
