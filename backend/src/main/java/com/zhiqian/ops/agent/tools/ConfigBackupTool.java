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
 * 配置备份工具（变更类，非破坏）：为指定配置文件创建带时间戳的同目录副本
 * （<path>.bak-<时间戳>），真实执行时受管备份目录同时留存一份快照并登记回滚账本。
 * 虽只新增文件不改动原文件，仍统一走 GuardedMutationExecutor 安全闭环——
 * 所有变更面共用同一确认门禁与审计口径，无一例外。
 */
@Component
public class ConfigBackupTool implements MutatingTool {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GuardedMutationExecutor guarded;

    public ConfigBackupTool(GuardedMutationExecutor guarded) {
        this.guarded = guarded;
    }

    @Override
    public String name() { return "config_backup"; }

    @Override
    public String description() {
        return "【变更类·非破坏】为指定配置文件创建带时间戳的备份副本（<path>.bak-<时间戳>），"
                + "真实执行时受管备份目录同步留存快照并登记回滚账本（可一键恢复）。"
                + "与其他变更工具共用护栏裁决与 pendingMutationId + confirm=true 二次确认门禁；默认 dry-run";
    }

    /** 仅新增副本、不改动原文件：覆写破坏性提示。 */
    @Override
    public Map<String, Object> annotations() {
        Map<String, Object> a = MutatingTool.super.annotations();
        a.put("destructiveHint", false);
        return a;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "待备份配置文件的绝对路径，如 /etc/nginx/nginx.conf、/etc/my.cnf");
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
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", name());
            out.put("status", "REJECTED");
            out.put("executed", false);
            out.put("message", "必须提供待备份配置文件的绝对路径 path");
            return out;
        }
        boolean confirm = AgentTool.boolArg(input, "confirm", false);
        String pendingMutationId = AgentTool.safeStrArg(input, "pendingMutationId", null);
        String backup = path + ".bak-" + LocalDateTime.now().format(TS);
        Map<String, Object> out = guarded.execute(name(),
                List.of(List.of("cp", "-p", path, backup)), confirm, pendingMutationId);
        out.put("backupTarget", backup);
        return out;
    }
}
