package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * 编排管线中的一个节点（如护栏、感知、推理、校验、执行、分析）。
 * 节点输出可携带以下约定键供 AgentRunner 读取：
 * _halt(Boolean) 短路终止；_model/_confidence/_tokenIn/_tokenOut/_status 用于溯源记录。
 */
public interface AgentNode {
    String stage();
    String agentName();
    Map<String, Object> run(AgentContext ctx);
}
