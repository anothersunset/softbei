package com.zhiqian.ops.agent;

import java.util.Map;

/**
 * 编排管线中的一个节点（运维版的 AgentGraph 节点）。
 * 返回 output map，可放入以下约定键供 AgentRunner 记录：
 * _model / _confidence / _tokenIn / _tokenOut / _status / _halt。
 */
public interface AgentNode {
    String stage();
    String agentName();
    Map<String, Object> run(AgentContext ctx);
}
