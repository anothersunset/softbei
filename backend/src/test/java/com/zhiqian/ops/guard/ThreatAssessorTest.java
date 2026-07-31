package com.zhiqian.ops.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 威胁指数计算器：确定性查表，覆盖设计文档「链路可视化与威胁评分改造」§2.4 列出的六个用例。
 * 与 SecurityScorerTest 分工——这里只测「这条指令有多危险」这一条口径，不测防御履职表现分。
 */
class ThreatAssessorTest {

    private final ThreatAssessor assessor = new ThreatAssessor();

    @Test
    void readonly_instruction_scores_low() {
        List<RiskDecision> decisions = List.of(
                RiskDecision.of("df -h", RiskLevel.READONLY, "只读探测", null));
        ThreatAssessment result = assessor.assess(false, null, RiskLevel.READONLY, decisions, List.of());

        assertEquals(10, result.index());
        assertEquals("低危", result.tier());
        assertEquals(10, result.axes().get("commandRisk"));
        assertTrue(result.signals().contains("全部为只读诊断命令，无变更意图"));
    }

    @Test
    void restricted_change_without_redline_scores_medium() {
        List<RiskDecision> decisions = List.of(
                RiskDecision.of("systemctl restart nginx", RiskLevel.EXECUTABLE, "受限变更", null));
        ThreatAssessment result = assessor.assess(false, null, RiskLevel.EXECUTABLE, decisions, List.of());

        assertEquals(45, result.index());
        assertEquals("中危", result.tier());
        assertTrue(result.signals().contains("含需人工确认的受限变更"));
    }

    @Test
    void redline_delete_with_catastrophic_impact_caps_at_100_severe() {
        List<RiskDecision> decisions = List.of(
                RiskDecision.of("rm -rf /var/lib/mysql", RiskLevel.BLOCK, "递归强制删除", "blockedPattern"),
                RiskDecision.of("rm -rf /etc", RiskLevel.BLOCK, "命中关键路径", "criticalPath"));
        List<ImpactEstimate> counterfactual = List.of(
                new ImpactEstimate("rm -rf /var/lib/mysql", RiskLevel.BLOCK, "CATASTROPHIC",
                        List.of("数据库数据全部丢失"), "服务不可用且数据不可恢复", "从备份恢复"));
        ThreatAssessment result = assessor.assess(false, null, RiskLevel.BLOCK, decisions, counterfactual);

        assertEquals(100, result.index());
        assertEquals("严重", result.tier());
        assertEquals(90, result.axes().get("commandRisk"));
        assertEquals(95, result.axes().get("impactScope"));
        assertTrue(result.signals().contains("命中安全红线规则 2 条"));
        assertTrue(result.signals().contains("操作涉及系统关键路径"));
        assertTrue(result.signals().contains("含高危不可逆操作"));
    }

    @Test
    void injection_blocked_at_entry_scores_severe_with_capped_axis() {
        InjectionResult injection = new InjectionResult(true, List.of("p1", "p2", "p3"), "命中注入特征");
        ThreatAssessment result = assessor.assess(true, injection, RiskLevel.READONLY, List.of(), List.of());

        assertEquals(95, result.index());
        assertEquals("严重", result.tier());
        assertEquals(100, result.axes().get("injectionSignal"));
        assertTrue(result.signals().contains("检测到提示词注入特征 3 处"));
    }

    @Test
    void irreversible_change_with_high_impact_scores_high() {
        List<RiskDecision> decisions = List.of(
                RiskDecision.of("systemctl stop mysql", RiskLevel.IRREVERSIBLE, "核心服务停止", null));
        List<ImpactEstimate> counterfactual = List.of(
                new ImpactEstimate("systemctl stop mysql", RiskLevel.IRREVERSIBLE, "HIGH",
                        List.of("数据库服务中断"), "业务不可用", "systemctl start mysql"));
        ThreatAssessment result = assessor.assess(false, null, RiskLevel.IRREVERSIBLE, decisions, counterfactual);

        assertEquals(75, result.index());
        assertEquals("高危", result.tier());
        assertTrue(result.signals().contains("含高危不可逆操作"));
    }

    @Test
    void null_inputs_do_not_throw_and_fall_back_to_base_score() {
        ThreatAssessment result = assessor.assess(false, null, null, null, null);

        assertEquals(10, result.index());
        assertEquals("低危", result.tier());
        assertNotNull(result.signals());
        assertNotNull(result.axes());
    }
}
