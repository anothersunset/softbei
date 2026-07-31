package com.zhiqian.ops.analyzer;

import com.zhiqian.ops.llm.PlanResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根因分析器：综合推理假设与执行结果，输出面向运维人员的闭环结论。
 */
@Component
public class RootCauseAnalyzer {

    public String analyze(String instruction, PlanResult plan, List<String> execSummaries) {
        return analyze(instruction, plan, execSummaries, null);
    }

    /**
     * @param outcome 权威结果状态（由流水线计算）：EXECUTED / REVIEW_PENDING / BLOCKED / INJECTION_BLOCKED / OBSERVED。
     * 只有真正执行并通过校验时才能宣称“闭环完成”（修复 RPT-08）。
     */
    public String analyze(String instruction, PlanResult plan, List<String> execSummaries, String outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("【问题】").append(instruction == null ? "(未提供)" : instruction).append('\n');
        if (plan != null) {
            if (plan.getRootCauseHypothesis() != null) {
                sb.append("【根因假设】").append(plan.getRootCauseHypothesis()).append('\n');
            }
            if (plan.getSummary() != null) {
                sb.append("【处置思路】").append(plan.getSummary()).append('\n');
            }
            if (plan.getConfidence() != null) {
                sb.append("【置信度】").append(plan.getConfidence()).append('\n');
            }
        }
        if (execSummaries != null && !execSummaries.isEmpty()) {
            sb.append("【执行与证据】\n");
            for (String s : execSummaries) {
                sb.append("  - ").append(s).append('\n');
            }
        }
        String verdict = outcome == null ? "" : outcome;
        switch (verdict) {
            case "VERIFIED", "EXECUTED" ->
                sb.append("【结论】已按「感知-推理-校验-执行-分析」闭环完成处理并通过校验(VERIFY)，详细步骤可在溯源详情中查看。");
            case "OBSERVED" ->
                sb.append("【结论】只读检查已完成，未修改系统；诊断证据与建议已记录。");
            case "DRY_RUN_COMPLETED" ->
                sb.append("【结论】模拟执行已完成，未修改系统；可依据结果继续审阅真实处置方案。");
            case "EXECUTED_UNVERIFIED" ->
                sb.append("【结论】变更已执行，但尚无充分的执行后验证证据，不能宣称闭环完成。");
            case "VERIFY_FAILED" ->
                sb.append("【结论】变更已执行，但执行后复核未达预期，请停止扩大操作并按回滚建议处理。");
            case "EXECUTION_FAILED" ->
                sb.append("【结论】执行前检查或命令执行失败，处置未完成；系统已停止后续变更并保留失败证据。");
            case "PLAN_FAILED", "PIPELINE_FAILED" ->
                sb.append("【结论】计划或处理链路未能完成，未执行变更，请核对输入与运行环境后重试。");
            case "REVIEW_PENDING" ->
                sb.append("【结论】已完成感知、推理与风险校验，计划中含需人工确认的变更，当前尚未执行、未形成闭环，请确认后重试。");
            case "BLOCKED" ->
                sb.append("【结论】计划中含命中红线的高危指令，已拒绝执行，未产生任何变更，未形成执行闭环。");
            case "INJECTION_BLOCKED" ->
                sb.append("【结论】检测到提示词注入或高危恶意意图，已在入口拦截，未执行任何操作，未形成执行闭环。");
            default ->
                sb.append("【结论】已完成分析，未执行变更，详细步骤可在溯源详情中查看。");
        }
        return sb.toString();
    }
}
