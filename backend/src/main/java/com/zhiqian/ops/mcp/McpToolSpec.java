package com.zhiqian.ops.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP tools/list 返回的单个工具描述，字段名遵循 MCP Tool 定义：
 * name / description / inputSchema(JSON Schema) / annotations(ToolAnnotations)。
 * annotations 为空时不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations
) {}
