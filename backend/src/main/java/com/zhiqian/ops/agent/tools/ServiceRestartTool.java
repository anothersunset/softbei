package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.MutatingTool;
import com.zhiqian.ops.exec.GuardedMutationExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务重启工具（变更类）：systemctl restart 指定服务单元，成功后自动跑
 * systemctl is-active 只读复核探针验证服务状态。
 * 风险门槛由护栏裁决决定：普通服务为 EXECUTABLE（需 confirm），核心服务
 * （mysql/nginx/sshd 等 criticalServices）自动升级 IRREVERSIBLE，工具自身无权降级；
 * 试图停用防火墙等红线动作 confirm 也无法越过。
 */
@Component
public class ServiceRestartTool implements MutatingTool {

    private final GuardedMutationExecutor guarded;

    public ServiceRestartTool(GuardedMutationExecutor guarded) {
        this.guarded = guarded;
    }

    @Override
    public String name() { return "service_restart"; }

    @Override
    public String description() {
        return "【变更类】重启指定 systemd 服务单元（systemctl restart），真实执行成功后自动以"
                + " systemctl is-active 复核服务状态。内置安全闭环：护栏风险裁决（核心服务自动升级为高危）、"
                + "pendingMutationId + confirm=true 二次确认、溯源审计；默认 dry-run 不真实落盘";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("type", "string");
        unit.put("description", "systemd 服务单元名，如 nginx、crond、myapp.service");
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("type", "boolean");
        confirm.put("description", "二次确认标志：首次调用返回风险裁决（REVIEW_PENDING），审阅后需同时携带 pendingMutationId 才会执行");
        confirm.put("default", false);
        Map<String, Object> pendingMutationId = new LinkedHashMap<>();
        pendingMutationId.put("type", "string");
        pendingMutationId.put("description", "首次 REVIEW_PENDING 返回的待确认变更 id；confirm=true 时必须携带且与同一工具/参数匹配");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("unit", unit);
        props.put("confirm", confirm);
        props.put("pendingMutationId", pendingMutationId);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("unit"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String unit = AgentTool.safeStrArg(input, "unit", null);
        if (unit == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool", name());
            out.put("status", "REJECTED");
            out.put("executed", false);
            out.put("message", "必须提供待重启的服务单元名 unit");
            return out;
        }
        boolean confirm = AgentTool.boolArg(input, "confirm", false);
        String pendingMutationId = AgentTool.safeStrArg(input, "pendingMutationId", null);
        return guarded.execute(name(),
                List.of(List.of("systemctl", "restart", unit)),
                confirm,
                pendingMutationId,
                List.of("systemctl", "is-active", unit));
    }
}
