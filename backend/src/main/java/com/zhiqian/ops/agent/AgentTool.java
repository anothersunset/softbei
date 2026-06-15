package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * Agent 工具抽象（沿用原项目接口形状）。
 * 每个运维动作都被封装为一个 Tool，通过 MCP 插件化暴露。
 */
public interface AgentTool {
    /** 工具唯一名称（也是 MCP tool name）。 */
    String name();

    /** 工具用途描述（供模型与 MCP 客户端理解）。 */
    String description();

    /** 执行工具，返回结构化结果。 */
    Map<String, Object> run(AgentContext ctx, Map<String, Object> input);
}
