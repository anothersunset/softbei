package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP JSON-RPC 路由与业务处理的共享内核，与传输层解耦。
 * HTTP 传输(McpController) 与 stdio 传输(McpStdioServer) 复用同一套逻辑，
 * 从而体现「MCP 运维插件化」：同一插件能力可通过多种传输层接入。
 *
 * 严格遵循 JSON-RPC 2.0 与 Model Context Protocol(2024-11-05)：
 *   - initialize / ping / tools/list / tools/call
 *   - 区分请求与通知(无 id)：通知处理后返回 null(无响应体)
 *   - 协议级错误用 JSON-RPC error；工具执行错误用 result.isError=true
 */
@Component
public class McpDispatcher {

    /** 服务端默认与支持的 MCP 协议版本。 */
    public static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    // JSON-RPC 2.0 标准错误码
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final ToolRegistry registry;
    private final SensitiveDataSanitizer sanitizer;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpDispatcher(ToolRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public McpDispatcher(ToolRegistry registry, SensitiveDataSanitizer sanitizer) {
        this.registry = registry;
        this.sanitizer = sanitizer;
    }

    /**
     * 处理一条 JSON-RPC 请求/通知。
     * @return 请求返回响应 Map；通知(无 id)返回 null 表示无响应体。
     */
    public Map<String, Object> handle(Map<String, Object> req) {
        boolean isNotification = !req.containsKey("id") || req.get("id") == null;
        Object id = req.get("id");
        Object methodObj = req.get("method");
        String method = methodObj == null ? null : String.valueOf(methodObj);

        // 1) JSON-RPC 版本校验
        if (!"2.0".equals(String.valueOf(req.get("jsonrpc")))) {
            if (isNotification) return null;
            return error(id, INVALID_REQUEST, "无效请求：jsonrpc 必须为 \"2.0\"");
        }
        // 2) method 校验
        if (method == null || method.isBlank() || "null".equals(method)) {
            if (isNotification) return null;
            return error(id, INVALID_REQUEST, "无效请求：缺少 method");
        }
        // 3) 通知(无 id)：处理后不返回响应体
        if (isNotification) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize(req));
                case "ping" -> ok(id, Map.of());
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> ok(id, toolsCall(req));
                default -> error(id, METHOD_NOT_FOUND, "未知方法：" + method);
            };
        } catch (InvalidParamsException e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            return error(id, INTERNAL_ERROR, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> initialize(Map<String, Object> req) {
        Map<String, Object> params = req.get("params") instanceof Map
                ? (Map<String, Object>) req.get("params") : Map.of();
        Object requestedObj = params.get("protocolVersion");
        String requested = requestedObj == null ? null : String.valueOf(requestedObj);
        String negotiated = (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
                ? requested : DEFAULT_PROTOCOL_VERSION;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "OpsGuard-MCP", "version", "1.0.0"));
        result.put("instructions",
                "OpsGuard 运维工具服务端：所有工具均为只读感知/巡检能力，不会触发任何系统变更；"
                        + "变更类操作请走 /api/ops/chat 的安全护栏与人工确认流程。");
        return result;
    }

    private Map<String, Object> toolsList() {
        List<McpToolSpec> specs = new ArrayList<>();
        for (AgentTool t : registry.all()) {
            // 当前所有工具均无入参(只读感知/巡检)，inputSchema 为不含属性的 object。
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", Map.of());
            inputSchema.put("additionalProperties", false);

            // ToolAnnotations：全部工具只读、非破坏性、幂等，仅作用于本机受控主机。
            Map<String, Object> annotations = new LinkedHashMap<>();
            annotations.put("title", t.name());
            annotations.put("readOnlyHint", true);
            annotations.put("destructiveHint", false);
            annotations.put("idempotentHint", true);
            annotations.put("openWorldHint", false);

            specs.add(new McpToolSpec(t.name(), t.description(), inputSchema, annotations));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", specs);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(Map<String, Object> req) {
        Object paramsObj = req.get("params");
        if (!(paramsObj instanceof Map)) {
            throw new InvalidParamsException("缺少 params");
        }
        Map<String, Object> params = (Map<String, Object>) paramsObj;
        Object nameObj = params.get("name");
        if (nameObj == null) {
            throw new InvalidParamsException("缺少工具名 name");
        }
        String name = String.valueOf(nameObj);
        Object argsObj = params.getOrDefault("arguments", Map.of());
        Map<String, Object> arguments = argsObj instanceof Map
                ? (Map<String, Object>) argsObj : Map.of();

        AgentTool tool = registry.get(name);
        if (tool == null) {
            // 未知工具属于调用参数错误，映射为 JSON-RPC -32602。
            throw new InvalidParamsException("未知工具：" + name);
        }

        // 工具执行错误按 MCP 约定以 isError 结果返回，而非 JSON-RPC 协议级错误。
        try {
            Map<String, Object> output = tool.run(new AgentContext(0L, 0L), arguments);
            Object sanitizedOutput = sanitizer == null ? output : sanitizer.sanitizeValue(output);
            String text = mapper.writeValueAsString(sanitizedOutput);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", text)));
            result.put("structuredContent", sanitizedOutput);
            result.put("isError", false);
            return result;
        } catch (Exception e) {
            String message = sanitizer == null ? e.getMessage() : sanitizer.sanitize(e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of(
                    "type", "text",
                    "text", "工具执行失败：" + message)));
            result.put("isError", true);
            return result;
        }
    }

    public Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return r;
    }

    public Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", Map.of("code", code, "message", message));
        return r;
    }

    /** 入参非法（映射为 JSON-RPC -32602）。 */
    private static class InvalidParamsException extends RuntimeException {
        InvalidParamsException(String message) { super(message); }
    }
}
