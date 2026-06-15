package com.zhiqian.ops.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全规则库 POJO，对应 risk-rules.yaml 中的 guard 节点。
 */
public class GuardRules {
    private List<BlockedPattern> blockedPatterns = new ArrayList<>();
    private List<String> blockedMetacharacters = new ArrayList<>();
    private List<String> criticalPaths = new ArrayList<>();
    private List<String> reviewBinaries = new ArrayList<>();
    private List<String> readOnlyBinaries = new ArrayList<>();
    private List<String> injectionPatterns = new ArrayList<>();

    public List<BlockedPattern> getBlockedPatterns() { return blockedPatterns; }
    public void setBlockedPatterns(List<BlockedPattern> blockedPatterns) { this.blockedPatterns = blockedPatterns; }
    public List<String> getBlockedMetacharacters() { return blockedMetacharacters; }
    public void setBlockedMetacharacters(List<String> blockedMetacharacters) { this.blockedMetacharacters = blockedMetacharacters; }
    public List<String> getCriticalPaths() { return criticalPaths; }
    public void setCriticalPaths(List<String> criticalPaths) { this.criticalPaths = criticalPaths; }
    public List<String> getReviewBinaries() { return reviewBinaries; }
    public void setReviewBinaries(List<String> reviewBinaries) { this.reviewBinaries = reviewBinaries; }
    public List<String> getReadOnlyBinaries() { return readOnlyBinaries; }
    public void setReadOnlyBinaries(List<String> readOnlyBinaries) { this.readOnlyBinaries = readOnlyBinaries; }
    public List<String> getInjectionPatterns() { return injectionPatterns; }
    public void setInjectionPatterns(List<String> injectionPatterns) { this.injectionPatterns = injectionPatterns; }

    public static class BlockedPattern {
        private String pattern;
        private String reason;
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
