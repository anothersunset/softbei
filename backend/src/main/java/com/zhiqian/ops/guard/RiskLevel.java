package com.zhiqian.ops.guard;

/**
 * 四级风险等级。
 */
public enum RiskLevel {
    /** 不改变系统状态的采集/查询类操作。 */
    READONLY,
    /** 会改变状态但通常可恢复的受限变更。 */
    EXECUTABLE,
    /** 会改变状态且难以完全回滚的高危操作。 */
    IRREVERSIBLE,
    /** 命中红线规则，永不放行。 */
    BLOCK;

    public int severity() {
        return switch (this) {
            case READONLY -> 0;
            case EXECUTABLE -> 1;
            case IRREVERSIBLE -> 2;
            case BLOCK -> 3;
        };
    }

    public static RiskLevel max(RiskLevel left, RiskLevel right) {
        if (left == null) return right == null ? READONLY : right;
        if (right == null) return left;
        return left.severity() >= right.severity() ? left : right;
    }

    public boolean requiresApproval() {
        return this == EXECUTABLE || this == IRREVERSIBLE;
    }

    public boolean requiresBackup() {
        return this == IRREVERSIBLE;
    }

    public boolean requiresDryRun() {
        return this == EXECUTABLE || this == IRREVERSIBLE;
    }

    public boolean executableWithoutApproval() {
        return this == READONLY;
    }
}
