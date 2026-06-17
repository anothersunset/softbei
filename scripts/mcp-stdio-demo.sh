#!/usr/bin/env bash
# MCP stdio transport 演示脚本
# 以独立进程方式启动 OpsGuard MCP Server(stdio 传输层)，通过 stdin/stdout 与其通信，
# 依次发送：initialize -> notifications/initialized(通知无响应) -> tools/list -> tools/call(health_inspect) -> ping
#
# 用法：
#   bash scripts/mcp-stdio-demo.sh [可选: jar 路径]
# 默认 jar：backend/target/ops-agent.jar（请先 mvn -pl backend clean package）
set -euo pipefail

JAR="${1:-backend/target/ops-agent.jar}"
if [ ! -f "$JAR" ]; then
  echo "[ERROR] 未找到 jar: $JAR" >&2
  echo "        请先执行: mvn -pl backend -am clean package -DskipTests" >&2
  exit 1
fi

echo "[INFO] 使用 jar: $JAR" >&2
echo "[INFO] 发送 5 条 JSON-RPC 消息到 stdio MCP Server ..." >&2
echo "-----------------------------------------------------------" >&2

# 逐行发送（换行分隔 JSON-RPC）。通知不会有响应行。
{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"demo-client","version":"0.1.0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"health_inspect","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":4,"method":"ping"}'
} | java -jar "$JAR" --mcp-stdio

echo "-----------------------------------------------------------" >&2
echo "[INFO] 演示结束（上方每行为一条 JSON-RPC 响应，应看到 id=1/2/3/4 四条，通知无响应）" >&2
