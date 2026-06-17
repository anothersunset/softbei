package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务端：严格遵循 JSON-RPC 2.0 与 Model Context Protocol(2024-11-05) 规范，
 * 将运维感知/巡检工具以 MCP 插件化方式对外暴露。
 *
 * 支持的方法：
 *   - initialize                 初始化与协议版本协商
 *   - notifications/initialized   初始化完成通知（通知无响应体）
 *   - ping                       心跳（返回空结果）
 *   - tools/list                 列出工具（含 inputSchema 与 annotations）
 *   - tools/call                 调用工具（执行错误以 isError 结果返回，而非协议级错误）
 *
 * 设计要点（对齐规范）：
 *   1. 校验 jsonrpc=="2.0"；缺失/非法返回 -32600。
 *   2. 区分请求与通知：无 id 的为通知，处理后不返回响应体（HTTP 202）。
 *   3. initialize 协商客户端请求的 protocolVersion。
 *   4. 工具执行错误按 MCP 约定以 result.isError=true 返回；仅协议级问题才用 JSON-RPC error。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    /** 服务端默认与支持的 MCP 协议版本。 */
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    // JSON-RPC 2.0 标准错误码
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/rpc")
    public ResponseEntity<Map<String, Object>> rpc(@RequestBody Map<String, Object> req) {
        boolean isNotification = !req.containsKey("id") || req.get("id") == null;
        Object id = req.get("id");
        Object methodObj = req.get("method");
        String method = methodObj == null ? null : String.valueOf(methodObj);

        // 1) JSON-RPC 版本校验
        if (!"2.0".equals(String.valueOf(req.get("jsonrpc")))) {
            if (isNotification) return noContent();
            return ResponseEntity.ok(error(id, INVALID_REQUEST, "无效请求：jsonrpc 必须为 \"2.0\""));
        }
        // 2) method 校验
        if (method == null || method.isBlank() || "null".equals(method)) {
            if (isNotification) return noContent();
            return ResponseEntity.ok(error(id, INVALID_REQUEST, "无效请求：缺少 method"));
        }
        // 3) 通知（无 id）：处理后不返回响应体，符合 JSON-RPC 约定
        if (isNotification) {
            // 例如 notifications/initialized、notifications/cancelled 等，无需响应
            return noContent();
        }

        try {
            return switch (method) {
                case "initialize" -> ResponseEntity.ok(ok(id, initialize(req)));
                case "ping" -> ResponseEntity.ok(ok(id, Map.of()));
                case "tools/list" -> ResponseEntity.ok(ok(id, toolsList()));
                case "tools/call" -> ResponseEntity.ok(ok(id, toolsCall(req)));
                default -> ResponseEntity.ok(error(id, METHOD_NOT_FOUND, "未知方法：" + method));
            };
        } catch (InvalidParamsException e) {
            return ResponseEntity.ok(error(id, INVALID_PARAMS, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(error(id, INTERNAL_ERROR, e.getMessage()));
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
            // 当前所有工具均无入参（只读感知/巡检），inputSchema 为不含属性的 object。
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
            String text = mapper.writeValueAsString(output);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", text)));
            result.put("structuredContent", output);
            result.put("isError", false);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of(
                    "type", "text",
                    "text", "工具执行失败：" + e.getMessage())));
            result.put("isError", true);
            return result;
        }
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

    private ResponseEntity<Map<String, Object>> noContent() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** 入参非法（映射为 JSON-RPC -32602）。 */
    private static class InvalidParamsException extends RuntimeException {
        InvalidParamsException(String message) { super(message); }
    }
}
