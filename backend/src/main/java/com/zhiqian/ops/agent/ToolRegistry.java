package com.zhiqian.ops.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 运维插件注册中心（类比原项目 MigrationToolFactory 的插件/适配器注册模式）。
 * 自动收集所有 AgentTool Bean，供 MCP tools/list、tools/call 与感知阶段调用。
 */
@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> all) {
        for (AgentTool t : all) {
            tools.put(t.name(), t);
        }
    }

    public Collection<AgentTool> all() { return tools.values(); }
    public AgentTool get(String name) { return tools.get(name); }
    public boolean has(String name) { return tools.containsKey(name); }
}
