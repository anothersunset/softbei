package com.zhiqian.ops.web;

import com.zhiqian.ops.analyzer.CrossSourceRca;
import com.zhiqian.ops.analyzer.RcaLlmSummarizer;
import com.zhiqian.ops.analyzer.RcaResult;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.inspect.InspectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主动巡检入口：按需触发一次只读体检，返回健康评分与风险预警；
 * /rca 在体检基础上做跨源(指标↔日志)时间窗口根因关联与 L1-L3 分级处置，
 * 并在配置了真实模型时附带 LLM 自然语言根因总结(llmSummary)。
 */
@RestController
@RequestMapping("/api/ops")
public class InspectionController {
    private final InspectionService inspectionService;
    private final CrossSourceRca crossSourceRca;
    private final RcaLlmSummarizer llmSummarizer;

    public InspectionController(InspectionService inspectionService,
                               CrossSourceRca crossSourceRca,
                               RcaLlmSummarizer llmSummarizer) {
        this.inspectionService = inspectionService;
        this.crossSourceRca = crossSourceRca;
        this.llmSummarizer = llmSummarizer;
    }

    @GetMapping("/inspect")
    public Result<InspectionReport> inspectGet() {
        return Result.ok(inspectionService.inspect());
    }

    @PostMapping("/inspect")
    public Result<InspectionReport> inspectPost() {
        return Result.ok(inspectionService.inspect());
    }

    @GetMapping("/rca")
    public Result<Map<String, Object>> rcaGet() {
        return Result.ok(buildRca());
    }

    @PostMapping("/rca")
    public Result<Map<String, Object>> rcaPost() {
        return Result.ok(buildRca());
    }

    /** 跨源根因分析：先做一次只读巡检，再关联指标与日志信号给出 L1-L3 处置；真实模型下附加 LLM 总结。 */
    private Map<String, Object> buildRca() {
        InspectionReport report = inspectionService.inspect();
        RcaResult rca = crossSourceRca.analyze(report);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", report);
        out.put("rca", rca);
        out.put("llmEnabled", llmSummarizer.enabled());
        String llmSummary = llmSummarizer.summarize(report, rca);
        if (llmSummary != null && !llmSummary.isBlank()) {
            out.put("llmSummary", llmSummary);
            out.put("llmProvider", llmSummarizer.providerName());
        } else if (llmSummarizer.enabled()) {
            out.put("llmSummary", fallbackRcaSummary(report, rca));
            out.put("llmProvider", llmSummarizer.providerName());
            out.put("llmSummaryDegraded", true);
        }
        return out;
    }

    private String fallbackRcaSummary(InspectionReport report, RcaResult rca) {
        String level = rca == null ? "UNKNOWN" : rca.overallLevel();
        int score = report == null ? -1 : report.healthScore();
        String overall = report == null ? "UNKNOWN" : report.overall();
        return "真实模型摘要暂不可用，已返回规则化 RCA 摘要：处置等级 " + level
                + "，健康评分 " + score + "，总体状态 " + overall
                + "。请优先查看 rca.insights 中的跨源证据和处置等级。";
    }
}
