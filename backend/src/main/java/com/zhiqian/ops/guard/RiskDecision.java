package com.zhiqian.ops.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 单条指令的风险裁决结果。
 */
public record RiskDecision(
        String command,
        RiskLevel level,
        DecisionAction action,
        boolean requiresApproval,
        boolean requiresBackup,
        boolean requiresDryRun,
        List<String> matchedRules,
        List<String> reasons,
        String saferAlternative,
        String normalizedInput,
        String originalInputHash
) {
    public RiskDecision {
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        normalizedInput = normalizedInput == null ? "" : normalizedInput;
        originalInputHash = originalInputHash == null ? sha256(command) : originalInputHash;
        if (action == null) {
            action = actionFor(level);
        }
    }

    public RiskDecision(String command, RiskLevel level, String reason, String matchedRule) {
        this(command, level, actionFor(level),
                level != null && level.requiresApproval(),
                level != null && level.requiresBackup(),
                level != null && level.requiresDryRun(),
                matchedRule == null || matchedRule.isBlank() ? List.of() : List.of(matchedRule),
                reason == null || reason.isBlank() ? List.of() : List.of(reason),
                defaultAlternative(level),
                command == null ? "" : command.trim(),
                sha256(command));
    }

    public static RiskDecision of(String command, RiskLevel level, String reason, String matchedRule) {
        return new RiskDecision(command, level, reason, matchedRule);
    }

    public static DecisionAction actionFor(RiskLevel level) {
        if (level == RiskLevel.BLOCK) {
            return DecisionAction.DENY;
        }
        if (level != null && level.requiresApproval()) {
            return DecisionAction.REQUIRE_APPROVAL;
        }
        return DecisionAction.ALLOW;
    }

    /** Backward-compatible single reason accessor for existing views/tests. */
    public String reason() {
        return reasons.isEmpty() ? "" : reasons.get(0);
    }

    /** Backward-compatible single rule accessor for existing views/tests. */
    public String matchedRule() {
        return matchedRules.isEmpty() ? "" : matchedRules.get(0);
    }

    private static String defaultAlternative(RiskLevel level) {
        if (level == RiskLevel.BLOCK) {
            return "改为只读诊断命令，先收集状态与日志证据。";
        }
        if (level == RiskLevel.IRREVERSIBLE) {
            return "先执行只读检查与 dry-run，确认备份/快照后再人工审批。";
        }
        if (level == RiskLevel.EXECUTABLE) {
            return "优先使用 dry-run 或只读状态检查验证影响范围。";
        }
        return "";
    }

    private static String sha256(String value) {
        String input = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
