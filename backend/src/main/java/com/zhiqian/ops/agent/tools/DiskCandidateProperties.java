package com.zhiqian.ops.agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 application.yml 中的 ops.disk-candidate.* 配置：磁盘候选文件感知与门禁阈值。 */
@Component
@ConfigurationProperties(prefix = "ops.disk-candidate")
public class DiskCandidateProperties {
    /** 候选门禁触发的最小文件体量（MB）：低于此值不进入候选表，避免占用用户注意力。 */
    private int minSizeMb = 50;
    /** find 结果最多保留的候选文件数（按大小降序，落盘前截断）。 */
    private int maxCandidates = 10;
    /** find 递归探查的最大深度，防止对过大范围路径做无意义的长时间扫描。 */
    private int findMaxDepth = 6;

    public int getMinSizeMb() { return minSizeMb; }
    public void setMinSizeMb(int minSizeMb) { this.minSizeMb = minSizeMb; }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
    public int getFindMaxDepth() { return findMaxDepth; }
    public void setFindMaxDepth(int findMaxDepth) { this.findMaxDepth = findMaxDepth; }
}
