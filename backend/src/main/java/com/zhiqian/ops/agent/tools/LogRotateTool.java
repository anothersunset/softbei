package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.MutatingTool;
import com.zhiqian.ops.exec.GuardedMutationExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志轮转工具（变更类）：先归档副本（<path>.<时间戳>）再清空原文件（truncate -s 0），
 * 释放磁盘空间且不打断持有文件句柄的进程写入。
 * 能力面收敛：仅允许 /var/log/ 下或以 .log 结尾的文件；入参经白名单清洗后编译为固定 argv，
 * 不经 shell；执行走 GuardedMutationExecutor 安全闭环（护栏裁决 + confirm 门禁 +
 * 执行前备份 + 回滚账本 + 溯源审计，默认 dry-run）。
 */
@Component
public class LogRotateTool implements MutatingTool {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GuardedMutationExecutor guarded;

    public LogRotateTool(GuardedMutationExecutor guarded) {
        this.guarded = guarded;
    }

    @Override
    public String name() { return "log_rotate"; }

    @Override
    public String description() {
        return "【变更类】轮转指定日志文件：先复制归档为 <path>.<时间戳>，再将原文件截断为 0 字节释放磁盘。"
                + "仅允许 /var/log/ 下或以 .log 结尾的文件。内置安全闭环：护栏风险裁决、confirm=true 二次确认、"
                + "执行前自动备份、回滚账本登记；默认 dry-run 不真实落盘";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "待轮转日志文件的绝对路径，须位于 /var/log/ 下或以 .log 结尾，如 /var/log/nginx/access.log");
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("type", "boolean");
        confirm.put("description", "二次确认标志：首次调用返回风险裁决（REVIEW_PENDING），审阅后携带 true 才会执行");
        confirm.put("default", false);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", path);
        props.put("confirm", confirm);
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
        // 能力面收敛：轮转（截断）是破坏性动作，只对日志文件开放，防止被诱导截断任意系统文件
        if (!path.startsWith("/var/log/") && !path.endsWith(".log")) {
            return rejected("仅允许轮转 /var/log/ 下或以 .log 结尾的日志文件，拒绝：" + path);
        }
        boolean confirm = AgentTool.boolArg(input, "confirm", false);
        String archive = path + "." + LocalDateTime.now().format(TS);
        List<List<String>> steps = List.of(
                List.of("cp", "-p", path, archive),
                List.of("truncate", "-s", "0", path));
        Map<String, Object> out = guarded.execute(name(), steps, confirm);
        out.put("archive", archive);
        return out;
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
