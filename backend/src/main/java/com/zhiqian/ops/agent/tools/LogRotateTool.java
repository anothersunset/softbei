package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.MutatingTool;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.GuardedMutationExecutor;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志轮转工具（变更类）：先归档副本（<path>.<时间戳>）再清空原文件（truncate -s 0），
 * 释放磁盘空间且不打断持有文件句柄的进程写入。
 * 能力面收敛：仅允许 /var/log/ 下或以 .log 结尾的日志；入参经白名单清洗后编译为固定 argv，
 * 不经 shell；执行走 GuardedMutationExecutor 安全闭环（护栏裁决 + pendingMutationId/confirm 门禁 +
 * 执行前备份 + 回滚账本 + 溯源审计，默认 dry-run）。
 * 复核分两层：① 动作正确性（verifyArgv，门禁 VERIFIED 状态，语义与 service_restart 的
 * systemctl is-active 一致）——原文件是否真的清空、归档是否真的建出来；② 磁盘压力是否缓解
 * （diskUsageBefore/After，仅作证据展示不门禁——单文件清理对整体磁盘占比的影响可能被并发写入
 * 噪声掩盖，不能作为"动作是否成功"的判据）。
 */
@Component
public class LogRotateTool implements MutatingTool {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GuardedMutationExecutor guarded;
    private final ExecProperties props;
    private final LeastPrivilegeExecutor executor;

    public LogRotateTool(GuardedMutationExecutor guarded) {
        this(guarded, null, null);
    }

    public LogRotateTool(GuardedMutationExecutor guarded, ExecProperties props) {
        this(guarded, props, null);
    }

    @Autowired
    public LogRotateTool(GuardedMutationExecutor guarded, ExecProperties props, LeastPrivilegeExecutor executor) {
        this.guarded = guarded;
        this.props = props;
        this.executor = executor;
    }

    @Override
    public String name() { return "log_rotate"; }

    @Override
    public String description() {
        return "【变更类】轮转指定日志文件：先复制归档为 <path>.<时间戳>，再将原文件截断为 0 字节释放磁盘。"
                + "仅允许 /var/log/ 下或以 .log 结尾的日志文件。内置安全闭环：护栏风险裁决、pendingMutationId + confirm=true 二次确认、"
                + "执行前自动备份、回滚账本登记；默认 dry-run 不真实落盘";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "待轮转日志文件的绝对路径，须位于 /var/log/ 下或以 .log 结尾，如 /var/log/nginx/access.log");
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("type", "boolean");
        confirm.put("description", "二次确认标志：首次调用返回风险裁决（REVIEW_PENDING），审阅后需同时携带 pendingMutationId 才会执行");
        confirm.put("default", false);
        Map<String, Object> pendingMutationId = new LinkedHashMap<>();
        pendingMutationId.put("type", "string");
        pendingMutationId.put("description", "首次 REVIEW_PENDING 返回的待确认变更 id；confirm=true 时必须携带且与同一工具/参数匹配");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", path);
        props.put("confirm", confirm);
        props.put("pendingMutationId", pendingMutationId);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String path = AgentTool.safeStrArg(input, "path", null);
        if (path == null || !path.startsWith("/")) {
            return rejected("必须提供待轮转日志文件的绝对路径 path");
        }
        String safePath = validateLogPath(path);
        if (safePath == null) {
            return rejected("仅允许轮转 /var/log/ 下或以 .log 结尾的日志文件；live 模式同时拒绝符号链接/不存在文件：" + path);
        }
        boolean confirm = AgentTool.boolArg(input, "confirm", false);
        String pendingMutationId = AgentTool.safeStrArg(input, "pendingMutationId", null);
        RotationPlan plan = confirmedPlanOrNew(safePath, confirm, pendingMutationId);

        boolean realMutation = executor != null && props != null && !props.isDryRun();
        String diskUsageBefore = realMutation ? executor.runReadOnly(List.of("df", "-h")).stdout() : null;

        Map<String, Object> out = guarded.execute(name(), plan.steps(), confirm, pendingMutationId, plan.verifyArgv());
        out.put("archive", plan.archive());

        // 只有真实执行成功（status=EXECUTED）才展示前后对比；卡在待确认/被拒绝时展示会产生
        // "看起来清理了但其实什么都没做"的误导——此时 before/after 必然一样，因为压根没执行。
        boolean actuallyExecuted = "EXECUTED".equals(out.get("status"));
        if (realMutation && actuallyExecuted && diskUsageBefore != null) {
            out.put("diskUsageBefore", diskUsageBefore);
            out.put("diskUsageAfter", executor.runReadOnly(List.of("df", "-h")).stdout());
        }
        return out;
    }

    /**
     * confirm 时优先取回首次 REVIEW_PENDING 已展示的不可变计划。特别是归档名带时间戳：
     * 若在确认时重新生成，会使 argv 签名变化，合法的 pendingMutationId 也会被误拒绝。
     * 仅当缓存计划确实对应同一安全路径和固定轮转步骤时才复用；其余情况仍新建计划，
     * 由 GuardedMutationExecutor 的签名校验拒绝不一致确认。
     */
    private RotationPlan confirmedPlanOrNew(String safePath, boolean confirm, String pendingMutationId) {
        if (confirm) {
            GuardedMutationExecutor.PendingPlan pending = guarded.pendingPlan(name(), pendingMutationId);
            RotationPlan restored = toRotationPlan(safePath, pending);
            if (restored != null) {
                return restored;
            }
        }
        String archive = safePath + "." + LocalDateTime.now().format(TS);
        List<List<String>> steps = List.of(
                List.of("cp", "-p", safePath, archive),
                List.of("truncate", "-s", "0", safePath));
        // stat 支持多文件参数、按序输出多行：一次只读探针同时验证「原文件已清空」与「归档已就位」两个信号。
        return new RotationPlan(archive, steps, List.of("stat", "-c%s", safePath, archive));
    }

    private RotationPlan toRotationPlan(String safePath, GuardedMutationExecutor.PendingPlan pending) {
        if (pending == null || pending.steps().size() != 2) {
            return null;
        }
        List<String> archiveStep = pending.steps().get(0);
        List<String> truncateStep = pending.steps().get(1);
        if (archiveStep.size() != 4 || truncateStep.size() != 4
                || !List.of("cp", "-p", safePath).equals(archiveStep.subList(0, 3))
                || !List.of("truncate", "-s", "0", safePath).equals(truncateStep)) {
            return null;
        }
        String archive = archiveStep.get(3);
        if (!archive.startsWith(safePath + ".") || pending.verifyArgv().isEmpty()) {
            return null;
        }
        return new RotationPlan(archive, pending.steps(), pending.verifyArgv());
    }

    private record RotationPlan(String archive, List<List<String>> steps, List<String> verifyArgv) {}

    private String validateLogPath(String rawPath) {
        try {
            Path requested = Path.of(rawPath).normalize();
            String normalized = unixPath(requested);
            if (!allowedLogPath(normalized)) {
                return null;
            }
            boolean dryRun = props == null || props.isDryRun();
            if (!Files.exists(requested, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return dryRun ? normalized : null;
            }
            if (Files.isSymbolicLink(requested)) {
                return null;
            }
            Path real = requested.toRealPath();
            if (!Files.isRegularFile(real) || !allowedLogPath(unixPath(real))) {
                return null;
            }
            return unixPath(real);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean allowedLogPath(String path) {
        return path.startsWith("/var/log/") || path.endsWith(".log");
    }

    private String unixPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private Map<String, Object> rejected(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", name());
        out.put("status", "REJECTED");
        out.put("executed", false);
        out.put("message", reason);
        return out;
    }
}
