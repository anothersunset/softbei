package com.zhiqian.ops.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 上下文（沿用原 智迁云枢 com.zhiqian.agent.AgentContext 设计）。
 * state() 用于跨节点传递中间结果，memory() 用于保存会话级记忆。
 */
public class AgentContext {
    private final Long taskId;
    private final Long projectId;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private final Map<String, Object> memory = new ConcurrentHashMap<>();

    public AgentContext(Long taskId, Long projectId) {
        this.taskId = taskId;
        this.projectId = projectId;
    }

    public Long taskId() { return taskId; }
    public Long projectId() { return projectId; }
    public Map<String, Object> state() { return state; }
    public Map<String, Object> memory() { return memory; }

    public String traceId() {
        Object v = state.get("traceId");
        return v == null ? null : v.toString();
    }
}
