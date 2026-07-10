package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecResult;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.RollbackLedger;
import com.zhiqian.ops.guard.CounterfactualAnalyzer;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.RollbackAdvisor;
import com.zhiqian.ops.guard.SecurityScorer;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final RollbackLedger rollbackLedger;
    private final LeastPrivilegeExecutor executor;
    private final LlmClient llm;
    private final ExecProperties execProperties;
    private final ApiSecurityProperties apiSecurityProperties;
    private final Environment environment;
    private final SensitiveDataSanitizer sanitizer;
    private final IntentRiskGuard guard;
    private final SecurityScorer securityScorer = new SecurityScorer();
    private final CounterfactualAnalyzer counterfactual = new CounterfactualAnalyzer();
    private final RollbackAdvisor rollbackAdvisor = new RollbackAdvisor();

    public OpsAgentController(OpsPipeline pipeline, List<AgentTool> tools,
                              RollbackLedger rollbackLedger, LeastPrivilegeExecutor executor,
                               LlmClient llm, ExecProperties execProperties,
                               ApiSecurityProperties apiSecurityProperties, Environment environment,
                               SensitiveDataSanitizer sanitizer, IntentRiskGuard guard) {
        this.pipeline = pipeline;
        this.tools = tools;
        this.rollbackLedger = rollbackLedger;
        this.executor = executor;
        this.llm = llm;
        this.execProperties = execProperties;
        this.apiSecurityProperties = apiSecurityProperties;
        this.environment = environment;
        this.sanitizer = sanitizer;
        this.guard = guard;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest req) {
        ChatResponse resp = pipeline.chat(req);
        enrich(resp);
        return Result.ok(resp);
    }

    /** 在不改变管线裁决与 status 的前提下，补充安全评分、反事实回放与可一键回滚的动作账本（纯计算）。 */
    private void enrich(ChatResponse resp) {
        boolean injectionBlocked = "INJECTION_BLOCKED".equals(resp.getStatus());
        RiskLevel worst = RiskLevel.READONLY;
        if (resp.getDecisions() != null) {
            for (RiskDecision d : resp.getDecisions()) {
                worst = RiskLevel.max(worst, d.level());
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

    @GetMapping("/runtime")
    public Result<Map<String, Object>> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean realLlm = llm != null && llm.isReal();
        out.put("llmProvider", llm == null ? "unknown" : llm.providerName());
        out.put("llmReal", realLlm);
        out.put("llmMode", realLlm ? "REAL" : "MOCK");
        out.put("mockDeterministic", !realLlm);
        out.put("dryRun", execProperties == null || execProperties.isDryRun());
        out.put("maxStepsPerRequest", execProperties == null ? null : execProperties.getMaxStepsPerRequest());
        out.put("guardMode", "rules+normalization+human-review");
        out.put("semanticBoundary", "LLM proposes; deterministic guards decide execution");
        out.put("apiTokenRequired", apiSecurityProperties != null && apiSecurityProperties.tokenEnabled());
        out.put("bindAddress", environment == null ? "unknown" : environment.getProperty("server.address", "0.0.0.0"));
        return Result.ok(out);
    }

    /**
     * 一键回滚：按动作账本回放补偿指令。
     * 安全约束：仅回放内置的非破坏性逆操作（start/enable/unmask/mount/mv 还原等）；
     * dry-run 默认开启，真实回滚需关闭 dry-run 并经人工确认，全过程仍走最小权限执行器。
     */
    @PostMapping("/rollback/{traceId}")
    public Result<Map<String, Object>> rollback(@PathVariable String traceId) {
        List<Map<String, Object>> ledger = rollbackLedger.get(traceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        List<Map<String, Object>> results = new ArrayList<>();
        if (ledger.isEmpty()) {
            out.put("message", "该追踪无可回滚的已执行变更");
            out.put("results", results);
            return Result.ok(out);
        }
        for (Map<String, Object> item : ledger) {
            Map<String, Object> r = new LinkedHashMap<>(item);
            Object comp = item.get("compensate");
            if (comp instanceof String c && !c.isBlank()) {
                RiskDecision decision = guard.evaluate(c);
                r.put("rollbackDecision", decision);
                if (decision.level() == RiskLevel.BLOCK) {
                    r.put("rolledBack", false);
                    r.put("output", "补偿命令命中安全红线，已拒绝执行：" + decision.reason());
                    results.add(r);
                    continue;
                }
                // restore-backup 条目的 compensate 命令由 PreChangeBackup 自行生成（cp <受控备份路径> <原路径>），
                // 两端路径均非模型/用户输入，且恢复目标就是此前已被批准执行过的变更命令的原路径——
                // 属于我们完全可控的补偿动作，而非任意外部命令，因此单独放行 EXECUTABLE 级（BLOCK 仍照常拒绝，
                // 意外升级为 IRREVERSIBLE 的情形仍需人工确认，不做无条件豁免）。
                boolean autoRestorable = "restore-backup".equals(item.get("action"))
                        && decision.level() == RiskLevel.EXECUTABLE;
                if (decision.level().requiresApproval() && !autoRestorable) {
                    r.put("rolledBack", false);
                    r.put("requiresApproval", true);
                    r.put("output", "补偿命令需人工确认，请通过主运维链路执行并保留影响记录：" + decision.reason());
                    results.add(r);
                    continue;
                }
                ExecResult er = decision.level() == RiskLevel.READONLY
                        ? executor.runReadOnly(tokenize(c))
                        : executor.run(tokenize(c));
                r.put("rolledBack", true);
                r.put("dryRun", er.dryRun());
                r.put("exitCode", er.exitCode());
                String output = er.stdout();
                if (er.stderr() != null && !er.stderr().isBlank()) {
                    output = output + "\n[stderr] " + er.stderr();
                }
                r.put("output", sanitizer == null ? output : sanitizer.sanitize(output));
            } else {
                r.put("rolledBack", false);
                r.put("note", "需人工恢复（见 manual 指引）");
            }
            results.add(r);
        }
        out.put("message", "已按动作账本回放补偿指令（dry-run 默认开启；真实回滚需关闭 dry-run 并人工确认）");
        out.put("results", results);
        return Result.ok(out);
    }

    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }
}
