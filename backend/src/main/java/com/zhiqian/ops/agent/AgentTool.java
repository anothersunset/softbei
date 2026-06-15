package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * Agent 可调用工具接口（沿用原 智迁云枢 com.zhiqian.agent.AgentTool 签名）。
 * 本项目中每个运维感知/动作都封装为一个 AgentTool，并通过 MCP 暴露。
 */
public interface AgentTool {
    /** 工具唯一名称（MCP tool name）。 */
    String name();

    /** 供大模型理解的工具描述。 */
    String description();

    /** 执行工具，返回结果 map。 */
    Map<String, Object> run(AgentContext ctx, Map<String, Object> input);
}
