package com.zhiqian.ops.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 单会话（sessionId）内存态：跨轮攻击检测的「纯检测账本」+ 只读降级开关。
 * 会话级、内存态、不落盘——重开会话即解除只读降级是可接受的（重开等于操作者主动放弃当前对话，
 * 攻击链清零，攻击未得逞）。方法均加 synchronized：单会话理论上可能有并发请求（如浏览器重复点击），
 * 成本很低、正确性优先，与本项目一贯风格一致。
 */
public final class SessionContext {
    private final String sessionId;
    private final int maxHistoryTurns;
    private final int maxEvidenceTurns;
    private final Deque<Turn> cleanHistory;
    /** 诊断证据继承：只装工具原始采集到的结构化事实，独立于 cleanHistory，窗口更小（按「轮」淘汰，
     *  一轮可能产出多条证据）。见设计文档「诊断证据继承」——ReasonNode 只能读到展平后的快照，
     *  永远拿不到 SessionContext 对象本身。 */
    private final Deque<EvidenceTurn> recentEvidence;
    private int suspicion = 0;
    private boolean readOnlyLockdown = false;
    private volatile long lastActiveMs;

    public SessionContext(String sessionId, int maxHistoryTurns) {
        this(sessionId, maxHistoryTurns, 2);
    }

    public SessionContext(String sessionId, int maxHistoryTurns, int maxEvidenceTurns) {
        this.sessionId = sessionId;
        this.maxHistoryTurns = Math.max(1, maxHistoryTurns);
        this.maxEvidenceTurns = Math.max(1, maxEvidenceTurns);
        this.cleanHistory = new ArrayDeque<>(this.maxHistoryTurns);
        this.recentEvidence = new ArrayDeque<>(this.maxEvidenceTurns);
        this.lastActiveMs = System.currentTimeMillis();
    }

    public String sessionId() {
        return sessionId;
    }

    public long lastActiveMs() {
        return lastActiveMs;
    }

    public synchronized void touch() {
        lastActiveMs = System.currentTimeMillis();
    }

    /** 追加一轮「干净」历史；超过窗口大小自动淘汰最早一轮。 */
    public synchronized void appendCleanTurn(Turn turn) {
        if (turn == null) {
            return;
        }
        cleanHistory.addLast(turn);
        while (cleanHistory.size() > maxHistoryTurns) {
            cleanHistory.removeFirst();
        }
    }

    /** 每轮开始时的可疑度衰减，下限 0。 */
    public synchronized void decay(int amount) {
        if (amount <= 0) {
            return;
        }
        suspicion = Math.max(0, suspicion - amount);
    }

    /** 累加可疑度增量（可为负，用于衰减以外的场景），下限 0。 */
    public synchronized void addSuspicion(int delta) {
        suspicion = Math.max(0, suspicion + delta);
    }

    public synchronized int suspicion() {
        return suspicion;
    }

    public synchronized List<Turn> cleanHistorySnapshot() {
        return List.copyOf(cleanHistory);
    }

    /** 追加本轮采集到的证据（仅干净轮调用，见 OpsPipeline.finalize()）；超过窗口自动淘汰最早一批。 */
    public synchronized void appendEvidence(List<EvidenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        recentEvidence.addLast(new EvidenceTurn(records));
        while (recentEvidence.size() > maxEvidenceTurns) {
            recentEvidence.removeFirst();
        }
    }

    /** 展平返回最近若干轮的证据记录，按轮次先后顺序；ReasonNode 仅读这个快照，读不到本对象。 */
    public synchronized List<EvidenceRecord> recentEvidenceSnapshot() {
        List<EvidenceRecord> out = new java.util.ArrayList<>();
        for (EvidenceTurn t : recentEvidence) {
            out.addAll(t.records());
        }
        return List.copyOf(out);
    }

    private record EvidenceTurn(List<EvidenceRecord> records) {}

    public synchronized boolean isReadOnlyLockdown() {
        return readOnlyLockdown;
    }

    public synchronized void setReadOnlyLockdown(boolean lockdown) {
        this.readOnlyLockdown = lockdown;
    }
}
