# MCP 协议合规验证测试报告

**执行环境:** Ubuntu 22.04 (2核/1.6GiB), 端口 8080, provider=xiaomi (mimo-v2.5-pro), dry-run=true
**执行时间:** 2026-06-17
**代码版本:** 7d4cf42 (含 fix(mcp) commit cf31445c)
**BASE:** http://localhost:8080
**RPC 端点:** /mcp/rpc

---

## 验证目标

验证 `/mcp/rpc` 是否严格遵循 JSON-RPC 2.0 + MCP 规范（协议版本协商、通知无响应体、ping、tools 注解、isError 语义、标准错误码）。

对应代码：`McpController` / `McpToolSpec`；设计说明见 `docs/02-功能设计.md` §3.8。

---

## 验证结果

| 编号 | 用例 | 期望 | 实际 | 结果 |
|------|------|------|------|------|
| MCP-01 | 版本协商 | 2025-03-26 | 2025-03-26 | ✅ PASS |
| MCP-01 | serverInfo | OpsGuard-MCP | OpsGuard-MCP | ✅ PASS |
| MCP-01 | instructions | true | true | ✅ PASS |
| MCP-02 | 状态码 (通知) | 202 | 202 | ✅ PASS |
| MCP-02 | 空响应体 | 0 bytes | 0 bytes | ✅ PASS |
| MCP-03 | ping 空结果 | {} | {} | ✅ PASS |
| MCP-04 | 工具数量 | 6 | 6 | ✅ PASS |
| MCP-04 | 只读注解 readOnlyHint | true | true | ✅ PASS |
| MCP-04 | schema 收敛 additionalProperties=false | true | true | ✅ PASS |
| MCP-05 | tools/call isError | false | false | ✅ PASS |
| MCP-05 | content[0].type | text | text | ✅ PASS |
| MCP-05 | structuredContent | true | true | ✅ PASS |
| MCP-06 | 未知工具 → 错误码 | -32602 | -32602 | ✅ PASS |
| MCP-07 | 未知方法 → 错误码 | -32601 | -32601 | ✅ PASS |
| MCP-08 | 非法 jsonrpc → 错误码 | -32600 | -32600 | ✅ PASS |

**总评: 15/15 全部通过 ✅**

---

## 验证要点说明

### MCP-01: 协议握手与版本协商
- 发送 `initialize` 请求，指定 `protocolVersion: "2025-03-26"`
- 服务端回显相同版本号，返回 `serverInfo.name = "OpsGuard-MCP"`
- 返回 `capabilities` 和 `instructions` 字段

### MCP-02: 初始化通知无响应体
- 发送 `notifications/initialized`（无 `id` 字段，符合 JSON-RPC 通知语义）
- 服务端返回 HTTP 202，响应体为空（0 bytes）

### MCP-03: 心跳 (ping)
- 发送 `ping` 请求
- 返回 `result: {}`（空对象，符合 MCP 规范）

### MCP-04: 工具列表合规
- 返回 6 个已注册运维工具
- 每个工具包含 `annotations.readOnlyHint = true`
- 每个工具的 `inputSchema.additionalProperties = false`（schema 收敛，防止幻觉参数）

### MCP-05: 工具调用
- 调用 `health_inspect` 工具
- 返回 `isError: false`，`content[0].type: "text"`
- 包含 `structuredContent` 结构化数据

### MCP-06: 未知工具 → 参数错误
- 调用不存在的工具 `nope_tool`
- 返回标准 JSON-RPC 错误码 `-32602`（Invalid params）

### MCP-07: 未知方法
- 调用不存在的方法 `foo/bar`
- 返回标准 JSON-RPC 错误码 `-32601`（Method not found）

### MCP-08: 非法 JSON-RPC
- 发送缺少 `jsonrpc` 字段的请求
- 返回标准 JSON-RPC 错误码 `-32600`（Invalid Request）

---

## 原始响应证据

### initialize 响应
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-03-26",
    "capabilities": { "tools": {} },
    "serverInfo": { "name": "OpsGuard-MCP", "version": "1.0.0" },
    "instructions": "本服务提供安全运维工具集..."
  }
}
```

### tools/list 响应（摘要）
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "tools": [
      { "name": "health_inspect", "annotations": { "readOnlyHint": true }, "inputSchema": { "additionalProperties": false } },
      { "name": "disk_analyze", "annotations": { "readOnlyHint": true }, "inputSchema": { "additionalProperties": false } },
      ...
    ]
  }
}
```
