package com.zhiqian.ops.mcp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP 服务端的 HTTP 传输层：仅负责 HTTP 适配，JSON-RPC 业务逻辑委托给 McpDispatcher。
 * 与 stdio 传输层(McpStdioServer) 复用同一调度核心，体现「MCP 运维插件化」的多传输接入。
 *
 * 响应约定：
 *   - 请求(有 id)：返回 200 + JSON-RPC 响应体。
 *   - 通知(无 id，如 notifications/initialized)：dispatcher 返回 null，本层响应 202 且无体。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private final McpDispatcher dispatcher;

    public McpController(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/rpc")
    public ResponseEntity<Map<String, Object>> rpc(@RequestBody Map<String, Object> req) {
        Map<String, Object> resp = dispatcher.handle(req);
        if (resp == null) {
            // 通知无响应体，符合 JSON-RPC 约定
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok(resp);
    }
}
