package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图风险校验器：对大模型生成的原始指令做"二次过滤"。
 * 裁决顺序：红线正则 -> 参数/子命令级高风险 -> 关键路径上的变更 -> 变更类二进制 -> 只读白名单 -> 默认 REVIEW。
 * 注：shell 元字符检测已移至 PromptInjectionDetector（用户输入层），
 * 不在生成命令层重复拦截——真实 LLM 生成的正常命令常含管道(|)或顺序执行(;)，
 * 此类合法用法由 blockedPatterns + criticalPaths 做精准拦截。
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

        // 1. 红线正则（含 shell 元字符相关的危险模式，如覆写 /etc/passwd 等）
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
        RiskDecision commandSurfaceDecision = evaluateCommandSurface(cmd, argv, binary);
        if (commandSurfaceDecision != null) {
            return commandSurfaceDecision;
        }
        boolean mutating = rules.getReviewBinaries().contains(binary);

        // 2. 关键路径上的变更类操作 -> 升级为 BLOCK
        if (mutating && touchesCriticalPath(argv)) {
            return new RiskDecision(cmd, RiskLevel.BLOCK,
                    "在系统关键路径/数据库数据目录上执行变更操作（" + binary + "），可能导致系统或数据不可恢复",
                    "criticalPath");
        }

        // 3. 变更类二进制 -> REVIEW
        if (mutating) {
            return new RiskDecision(cmd, RiskLevel.REVIEW,
                    "变更类操作（" + binary + "）需人工二次确认", "reviewBinary");
        }

        // 4. 只读白名单 -> SAFE
        if (rules.getReadOnlyBinaries().contains(binary)) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "只读/感知类指令", "readOnly");
        }

        // 5. 未知二进制 -> 默认 REVIEW（最小信任原则）
        return new RiskDecision(cmd, RiskLevel.REVIEW,
                "未在白名单内的指令（" + binary + "），默认需人工确认", "unknown");
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

    private RiskDecision evaluateCommandSurface(String cmd, List<String> argv, String binary) {
        return switch (binary) {
            case "find" -> evaluateFind(cmd, argv);
            case "docker" -> evaluateDocker(cmd, argv);
            case "kubectl" -> evaluateKubectl(cmd, argv);
            case "crontab" -> evaluateCrontab(cmd, argv);
            case "sed" -> evaluateSed(cmd, argv);
            case "tar" -> evaluateTar(cmd, argv);
            case "rsync" -> evaluateRsync(cmd, argv);
            default -> null;
        };
    }

    private RiskDecision evaluateFind(String cmd, List<String> argv) {
        RiskDecision worst = null;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            // -delete 无条件拦截
            if ("-delete".equals(arg)) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "find -delete 会删除匹配文件，不可逆", "findDelete");
            }
            // -fprint/-fls 写文件 → BLOCK
            if (arg.startsWith("-fprint") || "-fls".equals(arg)) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "find " + arg + " 会写文件到磁盘", "findWriteFile");
            }
            // -ok/-okdir 交互式确认 → REVIEW
            if ("-ok".equals(arg) || "-okdir".equals(arg)) {
                worst = worst(worst, new RiskDecision(cmd, RiskLevel.REVIEW,
                        "find " + arg + " 需交互确认执行，请人工审核", "findInteractive"));
                continue;
            }
            // -exec/-execdir：检查被执行的二进制
            if ("-exec".equals(arg) || "-execdir".equals(arg)) {
                String execBin = findExecBinary(argv, i + 1);
                if (execBin == null) {
                    // 无法解析被执行的命令 → 保守 BLOCK
                    return new RiskDecision(cmd, RiskLevel.BLOCK,
                            "find " + arg + " 无法确定执行的命令，保守拦截", "findExecUnknown");
                }
                if (rules.getReadOnlyBinaries().contains(execBin)) {
                    // 只读二进制 → 允许（继续检查后续 -exec）
                    continue;
                }
                if (rules.getReviewBinaries().contains(execBin)) {
                    // 变更类二进制在 -exec 中批量应用，升级为 REVIEW
                    worst = worst(worst, new RiskDecision(cmd, RiskLevel.REVIEW,
                            "find " + arg + " " + execBin + " 批量执行变更操作，需人工确认", "findExecMutating"));
                    continue;
                }
                // 未知二进制 → 保守 BLOCK
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "find " + arg + " 执行非白名单命令（" + execBin + "），保守拦截", "findExecBlocked");
            }
        }
        return worst;
    }

    /** 从 -exec/-execdir 后续 token 中提取被执行的二进制名（跳过 {}, options 等）。 */
    private String findExecBinary(List<String> argv, int start) {
        for (int i = start; i < argv.size(); i++) {
            String arg = argv.get(i);
            // \; 或 ; 或 + 终结 exec 语句
            if ("\\;".equals(arg) || ";".equals(arg) || "+".equals(arg)) {
                return null;
            }
            // {} 是占位符，跳过
            if ("{}".equals(arg)) {
                continue;
            }
            // 跳过选项（以 - 开头）
            if (arg.startsWith("-")) {
                continue;
            }
            // 找到第一个非选项 token → 这就是被执行命令
            return basename(arg);
        }
        return null;
    }

    /** 返回更严重（BLOCK > REVIEW > SAFE）的裁决。 */
    private RiskDecision worst(RiskDecision a, RiskDecision b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.level() == RiskLevel.BLOCK || b.level() == RiskLevel.BLOCK) {
            return a.level() == RiskLevel.BLOCK ? a : b;
        }
        if (a.level() == RiskLevel.REVIEW || b.level() == RiskLevel.REVIEW) {
            return a.level() == RiskLevel.REVIEW ? a : b;
        }
        return a;
    }

    private RiskDecision evaluateDocker(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub == null) {
            return null;
        }
        if (List.of("ps", "images", "logs", "inspect", "version", "info", "stats").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "docker 只读查询子命令", "dockerReadOnly");
        }
        if (List.of("rm", "kill", "stop", "prune", "rmi", "restart", "compose").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.REVIEW, "docker 变更类子命令需人工确认", "dockerMutatingSubcommand");
        }
        return null;
    }

    private RiskDecision evaluateKubectl(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub == null) {
            return null;
        }
        if (List.of("get", "describe", "logs", "top", "version", "cluster-info", "api-resources", "api-versions").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "kubectl 只读查询子命令", "kubectlReadOnly");
        }
        if (List.of("delete", "apply", "patch", "edit", "drain", "scale", "replace", "rollout").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.REVIEW, "kubectl 变更类子命令需人工确认", "kubectlMutatingSubcommand");
        }
        return null;
    }

    private RiskDecision evaluateCrontab(String cmd, List<String> argv) {
        if (argv.contains("-r") || argv.contains("-e")) {
            return new RiskDecision(cmd, RiskLevel.REVIEW, "crontab 修改/删除计划任务需人工确认", "crontabMutatingOption");
        }
        if (argv.contains("-l")) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "crontab 只读列出计划任务", "crontabReadOnly");
        }
        return null;
    }

    private RiskDecision evaluateSed(String cmd, List<String> argv) {
        for (String arg : argv) {
            if ("-i".equals(arg) || arg.startsWith("-i.")) {
                return touchesCriticalPath(argv)
                        ? new RiskDecision(cmd, RiskLevel.BLOCK, "sed -i 修改系统关键路径文件，可能导致配置不可恢复", "criticalPath")
                        : new RiskDecision(cmd, RiskLevel.REVIEW, "sed -i 会原地修改文件，需人工确认", "sedInPlace");
            }
        }
        return new RiskDecision(cmd, RiskLevel.SAFE, "sed 未启用原地修改，按文本只读处理", "sedReadOnly");
    }

    private RiskDecision evaluateTar(String cmd, List<String> argv) {
        boolean extract = false;
        boolean listOnly = false;
        for (int i = 1; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (i > 1 && !arg.startsWith("-")) {
                continue;
            }
            String opt = arg.startsWith("--") ? arg.substring(2) : (arg.startsWith("-") ? arg.substring(1) : arg);
            boolean clusteredShortOptions = opt.matches("[A-Za-z]+");
            if ("-x".equals(arg) || arg.startsWith("-x") || arg.startsWith("--extract")
                    || (clusteredShortOptions && opt.indexOf('x') >= 0)) {
                extract = true;
            }
            if ("-t".equals(arg) || arg.startsWith("-t") || arg.startsWith("--list")
                    || (clusteredShortOptions && opt.indexOf('t') >= 0)) {
                listOnly = true;
            }
        }
        if (extract) {
            return touchesCriticalPath(argv)
                    ? new RiskDecision(cmd, RiskLevel.BLOCK, "tar 解压目标触及系统关键路径，可能覆盖文件", "criticalPath")
                    : new RiskDecision(cmd, RiskLevel.REVIEW, "tar 解压可能覆盖写入文件，需人工确认", "tarExtract");
        }
        if (listOnly) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "tar 只列出归档内容", "tarReadOnly");
        }
        return null;
    }

    private RiskDecision evaluateRsync(String cmd, List<String> argv) {
        if (argv.contains("--dry-run") || argv.contains("-n")) {
            return new RiskDecision(cmd, RiskLevel.SAFE, "rsync dry-run 只预演不写入", "rsyncDryRun");
        }
        return touchesCriticalPath(argv)
                ? new RiskDecision(cmd, RiskLevel.BLOCK, "rsync 覆盖同步目标触及系统关键路径", "criticalPath")
                : new RiskDecision(cmd, RiskLevel.REVIEW, "rsync 可能覆盖写入目标文件，需人工确认", "rsyncMutating");
    }

    private String firstNonOption(List<String> argv, int from) {
        for (int i = from; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (!arg.startsWith("-")) {
                return arg;
            }
        }
        return null;
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
