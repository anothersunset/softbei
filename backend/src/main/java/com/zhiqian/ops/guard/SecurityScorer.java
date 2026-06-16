package com.zhiqian.ops.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按官方「多层级安全」权重折算安全分：
 *   静态风险评估 30% + 动态意图审计 35% + 受限执行 35%。
 * 纯计算、确定性，不改变管线裁决与最终 status。
 */
public class SecurityScorer {

    public SecurityScore score(boolean injectionBlocked,
                               RiskLevel worst,
                               List<RiskDecision> decisions,
                               List<Map<String, Object>> execResults,
                               String status) {
        List<String> notes = new ArrayList<>();

        // ---- 第一层：静态风险评估（满分 30）----
        int staticRisk;
        if (decisions == null || decisions.isEmpty()) {
            staticRisk = injectionBlocked ? 30 : 15;
            notes.add(injectionBlocked
                    ? "静态层：注入在入口拦截，未进入命令评估，规则体系完好(30/30)"
                    : "静态层：无可评估命令，给予中性分(15/30)");
        } else {
            boolean hardBlock = decisions.stream().anyMatch(d ->
                    d.level() == RiskLevel.BLOCK
                            && d.matchedRule() != null
                            && (d.matchedRule().equals("metacharacter")
                            || d.matchedRule().equals("blockedPattern")
                            || d.matchedRule().equals("criticalPath")));
            boolean anyReview = decisions.stream().anyMatch(d -> d.level() == RiskLevel.REVIEW);
            if (hardBlock) {
                staticRisk = 30;
                notes.add("静态层：红线规则精准命中并拦截高危命令(30/30)");
            } else if (worst == RiskLevel.SAFE) {
                staticRisk = 28;
                notes.add("静态层：规则确认全部为只读/安全命令(28/30)");
            } else if (anyReview) {
                staticRisk = 22;
                notes.add("静态层：规则将变更类命令标记为需人工确认(22/30)");
            } else {
                staticRisk = 24;
                notes.add("静态层：规则完成评估(24/30)");
            }
        }

        // ---- 第二层：动态意图审计（满分 35）----
        int dynamicAudit;
        if (injectionBlocked) {
            dynamicAudit = 35;
            notes.add("动态层：提示词注入被动态识别并拦截(35/35)");
        } else if (worst == RiskLevel.BLOCK) {
            dynamicAudit = 33;
            notes.add("动态层：意图审计通过，且高危意图被二次过滤(33/35)");
        } else if (worst == RiskLevel.REVIEW) {
            dynamicAudit = 29;
            notes.add("动态层：意图审计识别出需确认的变更意图(29/35)");
        } else {
            dynamicAudit = 32;
            notes.add("动态层：注入检测通过，意图审计未发现异常(32/35)");
        }

        // ---- 第三层：受限执行（满分 35）----
        int restrictedExec;
        boolean anyExecuted = false;
        boolean anyRealExec = false;
        if (execResults != null) {
            for (Map<String, Object> er : execResults) {
                if (Boolean.TRUE.equals(er.get("executed"))) {
                    anyExecuted = true;
                    boolean dryRun = Boolean.TRUE.equals(er.get("dryRun"));
                    Object level = er.get("level");
                    boolean isSafe = RiskLevel.SAFE == level || "SAFE".equals(String.valueOf(level));
                    if (!dryRun && !isSafe) {
                        anyRealExec = true;
                    }
                }
            }
        }
        if (!anyExecuted) {
            restrictedExec = 35;
            notes.add("执行层：本次未落地任何变更，系统零暴露(35/35)");
        } else if (anyRealExec) {
            restrictedExec = 27;
            notes.add("执行层：在最小权限沙箱内落地变更类操作(27/35)");
        } else {
            restrictedExec = 33;
            notes.add("执行层：仅以只读/dry-run 方式执行，未产生实际变更(33/35)");
        }

        int total = staticRisk + dynamicAudit + restrictedExec;
        String grade;
        if (total >= 90) {
            grade = "A · 稳健";
        } else if (total >= 75) {
            grade = "B · 良好";
        } else if (total >= 60) {
            grade = "C · 需关注";
        } else {
            grade = "D · 高风险";
        }
        return new SecurityScore(total, grade, staticRisk, dynamicAudit, restrictedExec, notes);
    }
}
