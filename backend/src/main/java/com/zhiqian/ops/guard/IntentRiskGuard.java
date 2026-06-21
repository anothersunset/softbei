package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图风险校验器：对大模型生成的原始指令做"二次过滤"。
 * 裁决顺序：只读管道白名单 -> shell 元字符 -> 红线正则 -> 关键路径上的变更 -> 变更类二进制 -> 只读白名单 -> 默认 REVIEW。
 */
@Component
public class IntentRiskGuard {
    private final GuardRules rules;
    private final List<Pattern> blockedPatterns = new ArrayList<>();
    private final List<String> blockedReasons = new ArrayList<>();

    public IntentRiskGuard(RiskRuleLoader loader) {
        this.rules = loader.rules();
        for (GuardRules.BlockedPattern bp : rules.getBlockedPatterns()) {
            blockedPatterns.add(Pattern.compile(bp.getPattern(), Pattern.CASE_INSENSITIVE));
            blockedReasons.add(bp.getReason());
        }
    }

    public RiskDecision evaluate(String command) {
        if (command == null || command.isBlank()) {
            return new RiskDecision(command, RiskLevel.BLOCK, "空指令", "empty");
        }
        String cmd = command.trim();

        // 0. 纯只读管道放行：各段均为只读命令、且不含管道以外的其它元字符时判定 SAFE
        //    （修复只读命令组合使用管道符被误拦的可用性问题，安全前提不变）
        if (cmd.contains("|") && isReadOnlyPipeline(cmd)) {
            return new RiskDecision(cmd, RiskLevel.SAFE,
                    "只读/感知类管道指令（各段均为只读命令）", "readOnlyPipeline");
        }

        // 1. shell 元字符：防止命令拼接/重定向/注入
        for (String mc : rules.getBlockedMetacharacters()) {
            if (cmd.contains(mc)) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "包含禁止的 shell 元字符 '" + mc + "'，可能用于命令拼接或注入", "metacharacter");
            }
        }

        // 2. 红线正则
        for (int i = 0; i < blockedPatterns.size(); i++) {
            if (blockedPatterns.get(i).matcher(cmd).find()) {
                return new RiskDecision(cmd, RiskLevel.BLOCK, blockedReasons.get(i), "blockedPattern");
            }
        }

        List<String> argv = tokenize(cmd);
        if (argv.isEmpty()) {
            return new RiskDecision(cmd, RiskLevel.BLOCK, "无法解析指令", "unparseable");
        }
        String binary = basename(argv.get(0));
        boolean mutating = rules.getReviewBinaries().contains(binary);

        // 3. 关键路径上的变更类操作 -> 升级为 BLOCK
        if (mutating && touchesCriticalPath(argv)) {
            return new RiskDecision(cmd, RiskLevel.BLOCK,
                    "在系统关键路径/数据库数据目录上执行变更操作（" + binary + "），可能导致系统或数据不可恢复",
                    "criticalPath");
        }

        // 4. 变更类二进制 -> REVIEW
        if (mutating) {
            return new RiskDecision(cmd, RiskLevel.REVIEW,
                    "变更类操作（" + binary + "）需人工二次确认", "reviewBinary");
        }

        // 5. 只读白名单 -> SAFE
        if (rules.getReadOnlyBinaries().contains(binary)) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "只读/感知类指令", "readOnly");
        }

        // 6. 未知二进制 -> 默认 REVIEW（最小信任原则）
        return new RiskDecision(cmd, RiskLevel.REVIEW,
                "未在白名单内的指令（" + binary + "），默认需人工确认", "unknown");
    }

    /**
     * 判断是否为「纯只读管道」：以 | 分段后，每段首个二进制都在只读白名单内，
     * 且段内不含除管道外的其它被禁元字符。仅此情形放行为 SAFE，其余仍按原逻辑拦截。
     */
    private boolean isReadOnlyPipeline(String cmd) {
        String[] segments = cmd.split("\\|");
        if (segments.length < 2) {
            return false;
        }
        for (String seg : segments) {
            String s = seg.trim();
            if (s.isEmpty()) {
                return false;
            }
            for (String mc : rules.getBlockedMetacharacters()) {
                if ("|".equals(mc)) {
                    continue;
                }
                if (s.contains(mc)) {
                    return false;
                }
            }
            List<String> argv = tokenize(s);
            if (argv.isEmpty()) {
                return false;
            }
            String binary = basename(argv.get(0));
            if (!rules.getReadOnlyBinaries().contains(binary)) {
                return false;
            }
        }
        return true;
    }

    private boolean touchesCriticalPath(List<String> argv) {
        for (String tok : argv) {
            if (!tok.startsWith("/")) {
                continue;
            }
            String norm = tok.endsWith("/") && tok.length() > 1 ? tok.substring(0, tok.length() - 1) : tok;
            for (String cp : rules.getCriticalPaths()) {
                if (cp.equals("/")) {
                    if (norm.equals("/") || tok.equals("/*")) {
                        return true;
                    }
                } else if (norm.equals(cp) || norm.startsWith(cp + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 简易、安全的分词：仅按空白切分并处理引号，不做任何 shell 解释。 */
    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (char c : s.trim().toCharArray()) {
            if (quote != 0) {
                if (c == quote) { quote = 0; } else { cur.append(c); }
                has = true;
            } else if (c == '\'' || c == '"') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) { out.add(cur.toString()); }
        return out;
    }

    private String basename(String token) {
        int idx = token.lastIndexOf('/');
        return idx >= 0 ? token.substring(idx + 1) : token;
    }
}
