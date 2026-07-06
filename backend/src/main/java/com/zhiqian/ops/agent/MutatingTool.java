package com.zhiqian.ops.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标记接口：实现该接口的工具属于「变更类」工具——会改变受控主机状态。
 * 继承 {@link ActiveTool}，因此绝不会在被动感知(SENSE)阶段或 ReAct 感知循环中被自动调用，
 * 仅通过 MCP tools/call 显式触发；且每次调用都必须经过 GuardedMutationExecutor 的安全闭环：
 * 意图风险护栏裁决 → pendingMutationId + confirm 二次确认门禁 → 执行前自动备份 → 最小权限执行（dry-run/熔断）
 * → 回滚账本登记 → 溯源审计落盘。
 */
public interface MutatingTool extends ActiveTool {

    /** 变更类工具的 ToolAnnotations：非只读、有破坏性提示、不幂等。 */
    @Override
    default Map<String, Object> annotations() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", name());
        a.put("readOnlyHint", false);
        a.put("destructiveHint", true);
        a.put("idempotentHint", false);
        a.put("openWorldHint", false);
        return a;
    }
}
