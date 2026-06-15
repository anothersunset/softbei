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
        sb.append("【结论】已按「感知-推理-校验-执行-分析」闭环完成处理，详细步骤可在溯源详情中查看。");
        return sb.toString();
    }
}
