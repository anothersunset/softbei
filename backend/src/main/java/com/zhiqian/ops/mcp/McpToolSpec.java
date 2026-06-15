package com.zhiqian.ops.mcp;

import java.util.Map;

/**
 * MCP tools/list 返回的工具描述。
 */
public record McpToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
