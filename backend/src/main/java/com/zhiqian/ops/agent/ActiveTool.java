package com.zhiqian.ops.agent;

/**
 * 标记接口：实现该接口的工具属于「主动/动作类」工具，
 * 不应在被动感知(SENSE)阶段被自动批量调用，但仍通过 MCP 插件化对外暴露。
 */
public interface ActiveTool extends AgentTool {
}
