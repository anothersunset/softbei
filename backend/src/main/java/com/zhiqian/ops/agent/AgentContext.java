package com.zhiqian.ops.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次运维会话的上下文（沿用原项目设计）。
 * state 保存跨节点传递的状态（如 traceId、感知结果、推理计划），
 * memory 保存可复用的上下文记忆。
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
}
