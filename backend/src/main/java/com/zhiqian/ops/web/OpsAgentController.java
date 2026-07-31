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
import com.zhiqian.ops.guard.ThreatAssessor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ThreatAssessor threatAssessor = new ThreatAssessor();
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
        List<com.zhiqian.ops.guard.ImpactEstimate> impacts = counterfactual.analyze(resp.getDecisions());
        resp.setCounterfactual(impacts);
        resp.setThreatAssessment(threatAssessor.assess(
                injectionBlocked, resp.getInjection(), worst, resp.getDecisions(), impacts));

        // 动作账本：为已执行的变更类指令生成补偿/回滚计划，并登记以支持一键回滚
        List<Map<String, Object>> ledger = rollbackAdvisor.buildLedger(resp.getDecisions(), resp.getExecResults());
        resp.setRollbackPlan(ledger);
        rollbackLedger.record(resp.getTraceId(), ledger);
    }

    /**
     * 查看单条命令的完整落盘输出（stdout/stderr）。
     * 前端结果框仅展示前 {@code output-preview-bytes} 预览，完整输出流式落盘在 {@code output-audit-dir}；
     * 本接口按 execId 回读完整文件。安全约束：execId 仅保留 [A-Za-z0-9._-]、去除路径分隔符，
     * 拼接固定 {@code -stdout.log}/{@code -stderr.log} 后缀，并强制限制在审计目录内，杜绝目录穿越读任意文件。
     * dry-run 未真实执行的命令没有落盘文件，接口返回 available=false 并说明原因。
     */
    @GetMapping("/exec-output")
    public Result<Map<String, Object>> execOutput(@RequestParam String execId,
                                                  @RequestParam(defaultValue = "stdout") String stream) {
        Map<String, Object> out = new LinkedHashMap<>();
        String s = "stderr".equalsIgnoreCase(stream) ? "stderr" : "stdout";
        out.put("stream", s);
        String safeId = execId == null ? "" : execId.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeId.isEmpty()) {
            out.put("available", false);
            out.put("message", "无效的执行标识");
            return Result.ok(out);
        }
        out.put("execId", safeId);
        String dir = execProperties == null || execProperties.getOutputAuditDir() == null
                || execProperties.getOutputAuditDir().isBlank()
                ? "logs/exec-output" : execProperties.getOutputAuditDir();
        Path base = Path.of(dir).toAbsolutePath().normalize();
        Path file = base.resolve(safeId + "-" + s + ".log").normalize();
        if (!file.startsWith(base)) {
            out.put("available", false);
            out.put("message", "路径越界，已拒绝");
            return Result.ok(out);
        }
        out.put("path", file.toString());
        if (!Files.exists(file)) {
            out.put("available", false);
            out.put("message", "未找到落盘文件：可能是 dry-run 未真实执行（导出 OPS_EXEC_DRY_RUN=false 后重试），或文件已被清理。");
            return Result.ok(out);
        }
        try {
            long size = Files.size(file);
            long maxBytes = 5L * 1024 * 1024;
            String content;
            boolean truncated = false;
            if (size > maxBytes) {
                byte[] buf = new byte[(int) maxBytes];
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    int read = 0, n;
                    while (read < buf.length && (n = in.read(buf, read, buf.length - read)) != -1) {
                        read += n;
                    }
                    content = new String(buf, 0, read, StandardCharsets.UTF_8);
                }
                truncated = true;
            } else {
                content = Files.readString(file, StandardCharsets.UTF_8);
            }
            out.put("available", true);
            out.put("size", size);
            out.put("truncated", truncated);
            out.put("content", sanitizer == null ? content : sanitizer.sanitize(content));
        } catch (Exception e) {
            out.put("available", false);
            out.put("message", "读取失败：" + e.getMessage());
        }
        return Result.ok(out);
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
                if (decision.level().requiresApproval()) {
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
