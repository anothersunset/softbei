package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 意图风险校验器：对大模型生成的原始指令做"二次过滤"。
 * 裁决顺序：输入规范化 -> 高危元字符红线 -> 红线正则 -> 管道分段逐段裁决 ->
 * 参数/子命令级风险 -> 上下文升级 -> 只读白名单 -> 默认人工确认。
 *
 * 管道感知（P0-2）：真实模型常生成 {@code ps aux | grep java}、{@code cat x | tail} 等
 * 由只读命令拼接的管道。旧实现把所有 shell 元字符一刀切 BLOCK，会误杀这类完全只读的诊断命令。
 * 现按管道/连接符（| ; &）拆段逐段裁决、取最高风险：全段只读 -> READONLY 放行；任一段越权/危险
 * -> 升级为对应等级。而命令替换/重定向/换行（` $( ${ &gt; &lt; \n）仍属无条件红线，不可借分段规避。
 */
@Component
public class IntentRiskGuard {
    /** 管道/连接符：作为分段边界而非直接红线（|、;、&，含 ||、&&）。 */
    private static final Set<String> PIPELINE_SEPARATORS = Set.of("|", ";", "&");

    /**
     * 安全重定向惯用写法：fd 复制（{@code 2>&1}、{@code 1>&2}）与丢弃到黑洞（{@code 2>/dev/null}）。
     * 两者都不写入任何用户可达文件，是极常见且完全无害的 shell 写法（真实 LLM 生成命令时几乎必带）。
     * 仅从「无条件元字符红线」这一步的检测面里剔除，不影响 guardInput 本身——
     * 红线正则与管道分段仍在未剔除的原串上运行，真正危险的 {@code > /path}（任意文件写）依旧被拦截。
     */
    private static final Pattern SAFE_FD_DUP_REDIRECT = Pattern.compile("\\d?>&\\d");
    private static final Pattern SAFE_DEVNULL_REDIRECT = Pattern.compile("\\d?>\\s*/dev/null");

    /**
     * shell 解释器 / 命令执行器：管道任一段流向它们即可执行任意代码
     * （经典 {@code curl evil | bash}、{@code ... | sh}、{@code | xargs rm} 攻击），一律红线 BLOCK。
     */
    private static final Set<String> SHELL_INTERPRETERS = Set.of(
            "sh", "bash", "dash", "zsh", "ksh", "csh", "tcsh", "ash", "fish",
            "python", "python2", "python3", "perl", "ruby", "node", "nodejs", "php", "lua",
            "eval", "exec", "source", "xargs", "env");

    /**
     * 同形字（confusables）映射：Cyrillic / Greek 等与 ASCII 字母形近的字符 → ASCII，
     * 堵住「用西里尔字母伪装 rm/dd 绕过红线正则」的同形字混淆攻击。NFKC 不处理跨字形混淆，需显式映射。
     */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'),
            Map.entry('р', 'p'), Map.entry('с', 'c'), Map.entry('у', 'y'),
            Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ј', 'j'),
            Map.entry('ѕ', 's'),
            Map.entry('А', 'A'), Map.entry('Е', 'E'), Map.entry('О', 'O'),
            Map.entry('Р', 'P'), Map.entry('С', 'C'), Map.entry('Т', 'T'),
            Map.entry('Х', 'X'), Map.entry('К', 'K'), Map.entry('М', 'M'),
            Map.entry('Н', 'H'), Map.entry('В', 'B'),
            Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ε', 'e'),
            Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('ν', 'v'),
            Map.entry('Ι', 'I'), Map.entry('Ο', 'O'), Map.entry('Ρ', 'P'));

    private final GuardRules rules;
    private final List<Pattern> blockedPatterns = new ArrayList<>();
    private final List<String> blockedReasons = new ArrayList<>();
    /** 无条件红线元字符：命令替换/重定向/换行，不可通过分段放行。 */
    private final List<String> hardBlockedMetacharacters = new ArrayList<>();

    public IntentRiskGuard(RiskRuleLoader loader) {
        this.rules = loader.rules();
        for (GuardRules.BlockedPattern bp : rules.getBlockedPatterns()) {
            blockedPatterns.add(Pattern.compile(bp.getPattern(), Pattern.CASE_INSENSITIVE));
            blockedReasons.add(bp.getReason());
        }
        for (String mc : rules.getBlockedMetacharacters()) {
            if (!PIPELINE_SEPARATORS.contains(mc)) {
                hardBlockedMetacharacters.add(mc);
            }
        }
    }

    public RiskDecision evaluate(String command) {
        if (command == null || command.isBlank()) {
            return new RiskDecision(command, RiskLevel.BLOCK, "空指令", "empty");
        }
        String cmd = command.trim();
        String normalized = normalizeCommand(cmd);
        String guardInput = normalized.isBlank() ? cmd : normalized;

        // 1. 高危元字符红线：命令替换/重定向/换行，无条件 BLOCK（不可借分段规避）。
        // 检测前先剔除安全重定向惯用写法（2>&1、2>/dev/null），避免真实 LLM 生成的
        // 完全无害命令被误杀；仅影响本步检测面，guardInput 原串不变，下游红线正则/
        // 管道分段仍按未剔除的原串裁决，任意文件写重定向（> /path）依旧无条件拦截。
        String metacharCheckSurface = SAFE_DEVNULL_REDIRECT.matcher(
                SAFE_FD_DUP_REDIRECT.matcher(guardInput).replaceAll(" ")).replaceAll(" ");
        for (String mc : hardBlockedMetacharacters) {
            if (metacharCheckSurface.contains(mc)) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "包含禁止的 shell 元字符 '" + mc + "'，可能用于命令替换/重定向/注入", "metacharacter");
            }
        }

        // 2. 红线正则（作用于整串，防止分段规避 rm -rf / 等）
        for (int i = 0; i < blockedPatterns.size(); i++) {
            if (blockedPatterns.get(i).matcher(guardInput).find()) {
                return new RiskDecision(cmd, RiskLevel.BLOCK, blockedReasons.get(i), "blockedPattern");
            }
        }

        // 3. 管道/连接符分段：逐段裁决取最高风险（全段只读则整体 READONLY 放行）
        List<String> segments = splitPipeline(guardInput);
        if (segments.size() > 1) {
            // 3a. 管道流向 shell 解释器 / 命令执行器 -> 可执行任意代码，红线 BLOCK
            for (String seg : segments) {
                if (seg.isBlank()) {
                    continue;
                }
                List<String> segArgv = tokenize(seg);
                if (!segArgv.isEmpty() && SHELL_INTERPRETERS.contains(basename(segArgv.get(0)))) {
                    return new RiskDecision(cmd, RiskLevel.BLOCK,
                            "管道向 shell 解释器/命令执行器（" + basename(segArgv.get(0)) + "）传递，可执行任意代码",
                            "pipeToInterpreter");
                }
            }
            RiskLevel worst = RiskLevel.READONLY;
            RiskDecision worstDecision = null;
            for (String seg : segments) {
                if (seg.isBlank()) {
                    continue;
                }
                RiskDecision segDecision = evaluateSingle(cmd, seg);
                if (worstDecision == null || segDecision.level().severity() > worst.severity()) {
                    worstDecision = segDecision;
                }
                worst = RiskLevel.max(worst, segDecision.level());
            }
            if (worstDecision == null) {
                return new RiskDecision(cmd, RiskLevel.BLOCK, "无法解析管道指令", "unparseable");
            }
            String reason = worst == RiskLevel.READONLY
                    ? "管道由只读命令拼接，整体只读放行"
                    : "管道按最高风险段裁决：" + worstDecision.reason();
            return new RiskDecision(cmd, worst, reason, worstDecision.matchedRule());
        }

        return evaluateSingle(cmd, guardInput);
    }

    /** 对单条（已分段、无管道）命令裁决。 */
    private RiskDecision evaluateSingle(String cmd, String segmentInput) {
        List<String> argv = tokenize(segmentInput);
        if (argv.isEmpty()) {
            return new RiskDecision(cmd, RiskLevel.BLOCK, "无法解析指令", "unparseable");
        }
        String binary = basename(argv.get(0));
        RiskDecision commandSurfaceDecision = evaluateCommandSurface(cmd, argv, binary);
        if (commandSurfaceDecision != null) {
            return commandSurfaceDecision;
        }
        boolean mutating = rules.getReviewBinaries().contains(binary);

        // 关键路径上的变更类操作 -> 升级为 IRREVERSIBLE，红线已在前置规则中 BLOCK
        if (mutating && touchesCriticalPath(argv)) {
            return new RiskDecision(cmd, RiskLevel.IRREVERSIBLE,
                    "在系统关键路径/数据库数据目录上执行变更操作（" + binary + "），需人工确认并执行前备份",
                    "criticalPath");
        }
        if (mutating && touchesCriticalService(argv)) {
            return new RiskDecision(cmd, RiskLevel.IRREVERSIBLE,
                    "命中核心服务变更（" + binary + "），需人工确认、dry-run 与执行前影响记录",
                    "criticalService");
        }

        // 变更类二进制 -> EXECUTABLE
        if (mutating) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE,
                    "可逆/受限变更操作（" + binary + "）需人工确认或 dry-run", "reviewBinary");
        }

        // 只读白名单 -> READONLY
        if (rules.getReadOnlyBinaries().contains(binary)) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "只读/感知类指令", "readOnly");
        }

        // 未知二进制 -> 默认 EXECUTABLE（最小信任原则，需要人工确认）
        return new RiskDecision(cmd, RiskLevel.EXECUTABLE,
                "未在白名单内的指令（" + binary + "），默认需人工确认", "unknown");
    }

    /**
     * 引号感知的管道/连接符分段：仅在引号外遇到 | ; &amp; 时切分，
     * 引号内的元字符（如 {@code sed 's/a|b/c/'}）不切分。
     */
    private List<String> splitPipeline(String s) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) { quote = 0; }
                cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == '|' || c == ';' || c == '&') {
                segments.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        segments.add(cur.toString().trim());
        return segments;
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

    private boolean touchesCriticalService(List<String> argv) {
        if (rules.getCriticalServices().isEmpty()) {
            return false;
        }
        for (String tok : argv) {
            String normalized = basename(tok).toLowerCase(Locale.ROOT);
            for (String svc : rules.getCriticalServices()) {
                if (normalized.equals(svc.toLowerCase(Locale.ROOT))
                        || normalized.equals(svc.toLowerCase(Locale.ROOT) + ".service")) {
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
            case "ip" -> evaluateIp(cmd, argv);
            case "route" -> evaluateRoute(cmd, argv);
            case "ifconfig" -> evaluateIfconfig(cmd, argv);
            case "wmic" -> evaluateWmic(cmd, argv);
            case "strace" -> evaluateStrace(cmd, argv);
            case "taskkill" -> new RiskDecision(cmd, RiskLevel.EXECUTABLE,
                    "taskkill 会终止 Windows 进程，需人工确认", "taskkillMutating");
            default -> null;
        };
    }

    private RiskDecision evaluateFind(String cmd, List<String> argv) {
        for (String arg : argv) {
            if ("-delete".equals(arg) || "-exec".equals(arg) || "-execdir".equals(arg)
                    || "-ok".equals(arg) || "-okdir".equals(arg)
                    || arg.startsWith("-fprint") || "-fls".equals(arg)) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "find 参数包含删除、执行或写文件动作（" + arg + "），只读白名单不得放行",
                        "findMutatingAction");
            }
        }
        return null;
    }

    private RiskDecision evaluateDocker(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub == null) {
            return null;
        }
        if (List.of("ps", "images", "logs", "inspect", "version", "info", "stats").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "docker 只读查询子命令", "dockerReadOnly");
        }
        if (List.of("rm", "kill", "stop", "prune", "rmi", "restart", "compose").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "docker 变更类子命令需人工确认", "dockerMutatingSubcommand");
        }
        return null;
    }

    private RiskDecision evaluateKubectl(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub == null) {
            return null;
        }
        if (List.of("get", "describe", "logs", "top", "version", "cluster-info", "api-resources", "api-versions").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "kubectl 只读查询子命令", "kubectlReadOnly");
        }
        if (List.of("delete", "apply", "patch", "edit", "drain", "scale", "replace", "rollout").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "kubectl 变更类子命令需人工确认", "kubectlMutatingSubcommand");
        }
        return null;
    }

    private RiskDecision evaluateCrontab(String cmd, List<String> argv) {
        if (argv.contains("-r") || argv.contains("-e")) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "crontab 修改/删除计划任务需人工确认", "crontabMutatingOption");
        }
        if (argv.contains("-l")) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "crontab 只读列出计划任务", "crontabReadOnly");
        }
        return null;
    }

    private RiskDecision evaluateSed(String cmd, List<String> argv) {
        for (String arg : argv) {
            if ("-i".equals(arg) || arg.startsWith("-i.")) {
                return touchesCriticalPath(argv)
                        ? new RiskDecision(cmd, RiskLevel.IRREVERSIBLE, "sed -i 修改系统关键路径文件，需执行前备份并人工确认", "criticalPath")
                        : new RiskDecision(cmd, RiskLevel.EXECUTABLE, "sed -i 会原地修改文件，需人工确认", "sedInPlace");
            }
        }
        return new RiskDecision(cmd, RiskLevel.READONLY, "sed 未启用原地修改，按文本只读处理", "sedReadOnly");
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
                    ? new RiskDecision(cmd, RiskLevel.IRREVERSIBLE, "tar 解压目标触及系统关键路径，可能覆盖文件，需执行前备份", "criticalPath")
                    : new RiskDecision(cmd, RiskLevel.EXECUTABLE, "tar 解压可能覆盖写入文件，需人工确认", "tarExtract");
        }
        if (listOnly) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "tar 只列出归档内容", "tarReadOnly");
        }
        return null;
    }

    private RiskDecision evaluateRsync(String cmd, List<String> argv) {
        if (argv.contains("--dry-run") || argv.contains("-n")) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "rsync dry-run 只预演不写入", "rsyncDryRun");
        }
        return touchesCriticalPath(argv)
                ? new RiskDecision(cmd, RiskLevel.IRREVERSIBLE, "rsync 覆盖同步目标触及系统关键路径，需执行前备份", "criticalPath")
                : new RiskDecision(cmd, RiskLevel.EXECUTABLE, "rsync 可能覆盖写入目标文件，需人工确认", "rsyncMutating");
    }

    private RiskDecision evaluateIp(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub == null) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "ip 帮助/概览查询", "ipReadOnly");
        }
        if (containsAny(argv, "add", "del", "delete", "set", "replace", "change", "flush")) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "ip 网络配置变更需人工确认", "ipMutatingSubcommand");
        }
        if (List.of("addr", "address", "a", "route", "r", "link", "l", "neigh", "neighbour", "neighbor", "n").contains(sub)) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "ip 网络状态查询子命令", "ipReadOnly");
        }
        return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "未知 ip 子命令默认需人工确认", "ipUnknownSubcommand");
    }

    private RiskDecision evaluateRoute(String cmd, List<String> argv) {
        if (containsAny(argv, "add", "del", "delete", "change", "flush")) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "route 路由表变更需人工确认", "routeMutatingSubcommand");
        }
        return new RiskDecision(cmd, RiskLevel.READONLY, "route 路由表查询", "routeReadOnly");
    }

    private RiskDecision evaluateIfconfig(String cmd, List<String> argv) {
        if (containsAny(argv, "up", "down", "netmask", "broadcast", "mtu", "add", "del", "delete")) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "ifconfig 网卡配置变更需人工确认", "ifconfigMutatingOption");
        }
        return new RiskDecision(cmd, RiskLevel.READONLY, "ifconfig 网卡状态查询", "ifconfigReadOnly");
    }

    private RiskDecision evaluateWmic(String cmd, List<String> argv) {
        if (containsAny(argv, "call", "create", "delete", "set")) {
            return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "wmic 调用/创建/删除/设置对象需人工确认", "wmicMutatingVerb");
        }
        return new RiskDecision(cmd, RiskLevel.READONLY, "wmic 查询类命令", "wmicReadOnly");
    }

    private RiskDecision evaluateStrace(String cmd, List<String> argv) {
        if (containsAny(argv, "-V", "--version", "-h", "--help")) {
            return new RiskDecision(cmd, RiskLevel.READONLY, "strace 帮助/版本查询", "straceReadOnly");
        }
        return new RiskDecision(cmd, RiskLevel.EXECUTABLE,
                "strace 会启动或附加到进程并暴露调用细节，需人工确认", "straceAttachOrExecute");
    }

    private boolean containsAny(List<String> argv, String... values) {
        for (String arg : argv) {
            for (String value : values) {
                if (arg.equalsIgnoreCase(value)) {
                    return true;
                }
            }
        }
        return false;
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

    private String normalizeCommand(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}\\u200B\\u200C\\u200D\\uFEFF]", "");
        s = mapHomoglyphs(s);
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Keep the NFKC form if percent decoding is malformed.
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 将同形字（Cyrillic/Greek 混淆字符）折叠回 ASCII，消除同形字绕过。 */
    private String mapHomoglyphs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean changed = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character mapped = HOMOGLYPHS.get(c);
            if (mapped != null) {
                sb.append(mapped.charValue());
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : s;
    }
}
