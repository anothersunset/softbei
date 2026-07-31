package com.zhiqian.ops.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yml 中的 ops.cross-turn.* 配置：跨轮渐进式攻击检测的计分权重、
 * 触发阈值与会话保留窗口。真机调参无需重新编译，见 README/设计文档「跨轮会话防护」章节。
 */
@Component
@ConfigurationProperties(prefix = "ops.cross-turn")
public class CrossTurnProperties {
    /** 单轮 injection 命中（InjectionNode 已 halt 的轮次）增量分。 */
    private int injectionHitScore = 40;
    /** 本轮风险等级相对上一干净轮每升一级的增量分。 */
    private int riskEscalationScore = 15;
    /** 本轮操作目标相对最近几轮跳变的增量分。 */
    private int targetJumpScore = 20;
    /** 命中角色扮演/身份重置等弱信号关键词（未达单轮拦截阈值）的增量分。 */
    private int weakSignalScore = 10;
    /** 每新一轮的可疑度衰减（下限 0），避免正常长对话的历史小分永久累积成误报。 */
    private int decayPerTurn = 5;
    /** 累积可疑度达到该阈值即将本会话降级为只读。 */
    private int lockdownThreshold = 60;
    /** 会话保留的最近「干净」轮次数量，仅供跨轮检测读取算分，不进入 LLM prompt。 */
    private int historyWindowTurns = 6;
    /** 会话不活跃超过该分钟数后从内存中驱逐（纯内存，不落盘）。 */
    private int sessionTtlMinutes = 30;
    /** 诊断证据继承窗口（轮次）：比 historyWindowTurns 小得多——证据是某时刻系统状态快照，
     *  窗口越大陈旧性风险和 prompt 成本越高，"继续分析"这类追问通常只指紧邻的上一轮。 */
    private int evidenceWindowTurns = 2;
    /** 每条证据摘要的最大字符数，超出截断，避免 prompt 膨胀。 */
    private int evidenceMaxCharsPerTurn = 800;

    public int getInjectionHitScore() { return injectionHitScore; }
    public void setInjectionHitScore(int injectionHitScore) { this.injectionHitScore = injectionHitScore; }
    public int getRiskEscalationScore() { return riskEscalationScore; }
    public void setRiskEscalationScore(int riskEscalationScore) { this.riskEscalationScore = riskEscalationScore; }
    public int getTargetJumpScore() { return targetJumpScore; }
    public void setTargetJumpScore(int targetJumpScore) { this.targetJumpScore = targetJumpScore; }
    public int getWeakSignalScore() { return weakSignalScore; }
    public void setWeakSignalScore(int weakSignalScore) { this.weakSignalScore = weakSignalScore; }
    public int getDecayPerTurn() { return decayPerTurn; }
    public void setDecayPerTurn(int decayPerTurn) { this.decayPerTurn = decayPerTurn; }
    public int getLockdownThreshold() { return lockdownThreshold; }
    public void setLockdownThreshold(int lockdownThreshold) { this.lockdownThreshold = lockdownThreshold; }
    public int getHistoryWindowTurns() { return historyWindowTurns; }
    public void setHistoryWindowTurns(int historyWindowTurns) { this.historyWindowTurns = historyWindowTurns; }
    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public void setSessionTtlMinutes(int sessionTtlMinutes) { this.sessionTtlMinutes = sessionTtlMinutes; }
    public int getEvidenceWindowTurns() { return evidenceWindowTurns; }
    public void setEvidenceWindowTurns(int evidenceWindowTurns) { this.evidenceWindowTurns = evidenceWindowTurns; }
    public int getEvidenceMaxCharsPerTurn() { return evidenceMaxCharsPerTurn; }
    public void setEvidenceMaxCharsPerTurn(int evidenceMaxCharsPerTurn) { this.evidenceMaxCharsPerTurn = evidenceMaxCharsPerTurn; }
}
