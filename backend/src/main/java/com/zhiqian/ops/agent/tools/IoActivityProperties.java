package com.zhiqian.ops.agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 application.yml 中的 ops.io-activity.* 配置：文件增速差分采样的窗口与扫描范围。 */
@Component
@ConfigurationProperties(prefix = "ops.io-activity")
public class IoActivityProperties {
    /** 两点采样默认间隔（毫秒）：越短噪声越大，越长感知越慢。 */
    private int defaultWindowMs = 2000;
    /** 调用方可传入的最小采样窗口，防止把工具当高频轮询用。 */
    private int minWindowMs = 200;
    /** 调用方可传入的最大采样窗口，避免单次调用长时间占用请求线程。 */
    private int maxWindowMs = 5000;
    /** find 递归探查的最大深度，与磁盘候选感知保持一致的成本边界。 */
    private int findMaxDepth = 6;
    /** 输出中最多保留的活跃/新增/缩小文件条目数（按变化幅度降序，落盘前截断）。 */
    private int maxTrackedFiles = 20;
    /** 对活跃文件逐个跑 lsof 定位写入者时，最多探测的文件数（成本控制）。 */
    private int maxLsofProbes = 5;
    /** 磁盘候选表 RECOMMENDED 档最多做增长复检的候选文件数：每个耗时=candidateProbeWindowMs，
     *  数量直接决定候选表生成延迟，默认 3 个 × 2 秒 ≈ 6 秒，用户已确认可接受。 */
    private int candidateProbeCount = 3;
    /** 候选表增长复检的差分采样窗口（毫秒）。 */
    private int candidateProbeWindowMs = 2000;

    public int getDefaultWindowMs() { return defaultWindowMs; }
    public void setDefaultWindowMs(int defaultWindowMs) { this.defaultWindowMs = defaultWindowMs; }
    public int getMinWindowMs() { return minWindowMs; }
    public void setMinWindowMs(int minWindowMs) { this.minWindowMs = minWindowMs; }
    public int getMaxWindowMs() { return maxWindowMs; }
    public void setMaxWindowMs(int maxWindowMs) { this.maxWindowMs = maxWindowMs; }
    public int getFindMaxDepth() { return findMaxDepth; }
    public void setFindMaxDepth(int findMaxDepth) { this.findMaxDepth = findMaxDepth; }
    public int getMaxTrackedFiles() { return maxTrackedFiles; }
    public void setMaxTrackedFiles(int maxTrackedFiles) { this.maxTrackedFiles = maxTrackedFiles; }
    public int getMaxLsofProbes() { return maxLsofProbes; }
    public void setMaxLsofProbes(int maxLsofProbes) { this.maxLsofProbes = maxLsofProbes; }
    public int getCandidateProbeCount() { return candidateProbeCount; }
    public void setCandidateProbeCount(int candidateProbeCount) { this.candidateProbeCount = candidateProbeCount; }
    public int getCandidateProbeWindowMs() { return candidateProbeWindowMs; }
    public void setCandidateProbeWindowMs(int candidateProbeWindowMs) { this.candidateProbeWindowMs = candidateProbeWindowMs; }
}
