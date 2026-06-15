package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端（JSON-RPC 2.0）：将运维感知工具以 MCP 插件化方式暴露。
 * 支持 initialize / tools/list / tools/call。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(List<AgentTool> toolList) {
        for (AgentTool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    @PostMapping("/rpc")
    public Map<String, Object> rpc(@RequestBody Map<String, Object> req) {
        Object id = req.get("id");
        String method = String.valueOf(req.get("method"));
        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize());
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> ok(id, toolsCall(req));
                default -> error(id, -32601, "未知方法：" + method);
            };
        } catch (Exception e) {
            return error(id, -32000, e.getMessage());
        }
    }

    private Map<String, Object> initialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "OpsGuard-MCP", "version", "1.0.0"));
        return result;
    }

    private Map<String, Object> toolsList() {
        List<McpToolSpec> specs = new ArrayList<>();
        Map<String, Object> emptySchema = Map.of("type", "object", "properties", Map.of());
        for (AgentTool t : tools.values()) {
            specs.add(new McpToolSpec(t.name(), t.description(), emptySchema));
        }
        return Map.of("tools", specs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(Map<String, Object> req) throws Exception {
        Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        Map<String, Object> result = tool.run(new AgentContext(0L, 0L), arguments);
        String text = mapper.writeValueAsString(result);
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return r;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", Map.of("code", code, "message", message));
        return r;
    }
}
