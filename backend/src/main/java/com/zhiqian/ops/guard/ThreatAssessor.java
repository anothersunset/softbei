package com.zhiqian.ops.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 威胁指数计算器：随本轮指令危险度走的确定性查表分，越危险的指令分越高（直觉正确）。
 * 与 {@link SecurityScorer}（防御体系履职表现分，越危险的指令若被正确拦截反而分高）互补，
 * 不替代——两者回答的是不同问题，详见 SecurityScorer 类注释。
 * 纯计算、无状态、不改变管线裁决与最终 status。
 */
public class ThreatAssessor {

    public ThreatAssessment assess(boolean injectionBlocked,
                                   InjectionResult injection,
                                   RiskLevel worst,
                                   List<RiskDecision> decisions,
                                   List<ImpactEstimate> counterfactual) {
        RiskLevel effectiveWorst = worst == null ? RiskLevel.READONLY : worst;
        List<RiskDecision> safeDecisions = decisions == null ? List.of() : decisions;
        List<ImpactEstimate> safeCounterfactual = counterfactual == null ? List.of() : counterfactual;
        List<String> signals = new ArrayList<>();

        int injectionPatternCount = (injection != null && injection.matchedPatterns() != null)
                ? injection.matchedPatterns().size() : 0;

        // ---- 基础分：按本轮最高风险等级；入口即被注入拦截时特判（此时 decisions 通常为空）----
        int base = injectionBlocked ? 80 : baseFor(effectiveWorst);

        // ---- 叠加项（总分封顶 100）----
        int addOn = 0;
        if (injectionPatternCount > 0) {
            addOn += 15;
            signals.add("检测到提示词注入特征 " + injectionPatternCount + " 处");
        }

        long redLineRuleCount = safeDecisions.stream()
                .filter(d -> matchesAny(d.matchedRules(), "blockedPattern", "metacharacter", "criticalPath"))
                .count();
        if (redLineRuleCount > 0) {
            addOn += Math.min(20, (int) redLineRuleCount * 10);
            signals.add("命中安全红线规则 " + redLineRuleCount + " 条");
        }

        boolean touchesCriticalPath = safeDecisions.stream()
                .anyMatch(d -> d.matchedRules() != null && d.matchedRules().contains("criticalPath"));
        if (touchesCriticalPath) {
            signals.add("操作涉及系统关键路径");
        }

        String worstIrreversibility = worstIrreversibility(safeCounterfactual);
        boolean catastrophic = "CATASTROPHIC".equals(worstIrreversibility);
        boolean highImpact = "HIGH".equals(worstIrreversibility);
        if (catastrophic) {
            addOn += 10;
        } else if (highImpact) {
            addOn += 5;
        }

        if (effectiveWorst == RiskLevel.IRREVERSIBLE || catastrophic || highImpact) {
            signals.add("含高危不可逆操作");
        } else if (effectiveWorst == RiskLevel.EXECUTABLE) {
            signals.add("含需人工确认的受限变更");
        } else if (effectiveWorst == RiskLevel.READONLY && injectionPatternCount == 0 && !injectionBlocked) {
            signals.add("全部为只读诊断命令，无变更意图");
        }

        int index = Math.min(100, base + addOn);
        String tier = tierFor(index);

        Map<String, Integer> axes = new LinkedHashMap<>();
        axes.put("injectionSignal", injectionAxis(injectionBlocked, injectionPatternCount));
        axes.put("commandRisk", commandRiskAxis(effectiveWorst));
        axes.put("impactScope", impactAxis(effectiveWorst, safeCounterfactual, worstIrreversibility));

        return new ThreatAssessment(index, tier, signals, axes);
    }

    private int baseFor(RiskLevel level) {
        return switch (level) {
            case READONLY -> 10;
            case EXECUTABLE -> 45;
            case IRREVERSIBLE -> 70;
            case BLOCK -> 85;
        };
    }

    private String tierFor(int index) {
        if (index >= 80) return "严重";
        if (index >= 55) return "高危";
        if (index >= 25) return "中危";
        return "低危";
    }

    private int injectionAxis(boolean injectionBlocked, int patternCount) {
        if (injectionBlocked) {
            return Math.min(100, 90 + patternCount * 5);
        }
        return patternCount > 0 ? 40 : 5;
    }

    private int commandRiskAxis(RiskLevel level) {
        return switch (level) {
            case READONLY -> 10;
            case EXECUTABLE -> 45;
            case IRREVERSIBLE -> 70;
            case BLOCK -> 90;
        };
    }

    private int impactAxis(RiskLevel worst, List<ImpactEstimate> counterfactual, String worstIrreversibility) {
        if (counterfactual.isEmpty()) {
            return worst == RiskLevel.READONLY ? 5 : 30;
        }
        return switch (worstIrreversibility == null ? "NONE" : worstIrreversibility) {
            case "LOW" -> 25;
            case "MEDIUM" -> 50;
            case "HIGH" -> 75;
            case "CATASTROPHIC" -> 95;
            default -> 5;
        };
    }

    /** counterfactual 里最严重（不可逆程度最高）的一条的 irreversibility 取值；空表返回 null。 */
    private String worstIrreversibility(List<ImpactEstimate> counterfactual) {
        String worst = null;
        int worstRank = -1;
        for (ImpactEstimate e : counterfactual) {
            int rank = irreversibilityRank(e.irreversibility());
            if (rank > worstRank) {
                worstRank = rank;
                worst = e.irreversibility();
            }
        }
        return worst;
    }

    private int irreversibilityRank(String irreversibility) {
        if (irreversibility == null) return -1;
        return switch (irreversibility) {
            case "NONE" -> 0;
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CATASTROPHIC" -> 4;
            default -> -1;
        };
    }

    private boolean matchesAny(List<String> rules, String... candidates) {
        if (rules == null || rules.isEmpty()) return false;
        for (String c : candidates) {
            if (rules.contains(c)) return true;
        }
        return false;
    }
}
