#!/usr/bin/env bash
# OpsGuard Web 演示严格启动脚本
# - 自动从脚本位置定位仓库根目录，可在仓库根目录或 scripts/ 内运行
# - 启动前验证 DeepSeek Key、模型和 chat/completions 接口
# - 启动后执行一次完整项目 LLM 链路探针
# - 请求真实模式却降级为 Mock 时，立即停止并明确报错
set -euo pipefail

PORT="${PORT:-18080}"
BIND="${BIND:-0.0.0.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$(dirname "$SCRIPT_DIR")}"
JAR="${JAR:-$REPO_DIR/backend/target/ops-agent.jar}"
JAVA_OPTS="${JAVA_OPTS:--Xmx768m}"
OPS_API_TOKEN="${OPS_API_TOKEN:-opsguard-ui-token}"
OPS_LLM_PROVIDER="${OPS_LLM_PROVIDER:-deepseek}"
OPS_LLM_BASE_URL="${OPS_LLM_BASE_URL:-https://api.deepseek.com}"
OPS_LLM_MODEL="${OPS_LLM_MODEL:-deepseek-chat}"
DRY_RUN="${DRY_RUN:-true}"
REBUILD="${REBUILD:-0}"
LOG_DIR="${LOG_DIR:-$REPO_DIR/acceptance-runs/ui-real-llm}"
LOG_FILE="$LOG_DIR/backend-demo.log"
TRACE_FILE="${OPS_TRACE_FILE:-$LOG_DIR/ops-trace.jsonl}"
PID_FILE="$LOG_DIR/backend-demo.pid"

mkdir -p "$LOG_DIR"

stop_backend() {
  if [[ -f "$PID_FILE" ]]; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
  fi
  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${PORT}/tcp" 2>/dev/null || true
  fi
}

if [[ "${1:-}" == "stop" ]]; then
  stop_backend
  echo "[demo] 已尝试停止端口 ${PORT} 上的后端。"
  exit 0
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi
JAVA_BIN="$JAVA_HOME/bin/java"
JV="$("$JAVA_BIN" -version 2>&1 | head -1)"
echo "[demo] REPO_DIR=$REPO_DIR"
echo "[demo] JAVA_HOME=$JAVA_HOME"
echo "[demo] $JV"
case "$JV" in
  *\"17.*|*\"21.*) : ;;
  *) echo "[demo] ⚠️ 警告：不是 JDK 17/21，Spring Boot 3 可能无法运行" ;;
esac

if [[ "$REBUILD" == "1" ]]; then
  echo "[demo] 重新编译（mvn clean package -DskipTests）..."
  (cd "$REPO_DIR/backend" && JAVA_HOME="$JAVA_HOME" mvn -B -DskipTests clean package)
  NEWJAR="$(find "$REPO_DIR/backend/target" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -1 | cut -d' ' -f2-)"
  [[ -n "$NEWJAR" ]] && JAR="$NEWJAR"
fi

if [[ ! -f "$JAR" ]]; then
  echo "[demo] ❌ 找不到 jar：$JAR"
  echo "[demo] 请执行：REBUILD=1 ./scripts/demo-start.sh"
  exit 1
fi
echo "[demo] JAR=$JAR ($(date -r "$JAR" '+%Y-%m-%d %H:%M'))"

stop_backend
sleep 1

# 读取 Key。Key 非空不等于真实模型可用，必须先做真实接口预检。
if [[ -z "${OPS_LLM_API_KEY:-}" ]]; then
  read -rsp "[demo] 输入 DeepSeek API Key（回车留空=使用 Mock）: " OPS_LLM_API_KEY
  echo
fi

REAL_LLM_REQUESTED=0
if [[ -n "${OPS_LLM_API_KEY:-}" ]]; then
  REAL_LLM_REQUESTED=1
  export OPS_LLM_API_KEY OPS_LLM_PROVIDER OPS_LLM_BASE_URL OPS_LLM_MODEL

  PREFLIGHT_FILE="$LOG_DIR/deepseek-preflight.json"
  LLM_ENDPOINT="${OPS_LLM_BASE_URL%/}/chat/completions"
  echo "[demo] 验证 DeepSeek Key、模型和接口（不会打印 Key）..."

  if ! PREFLIGHT_HTTP="$(curl -sS --max-time 60 -o "$PREFLIGHT_FILE" -w '%{http_code}' \
      "$LLM_ENDPOINT" \
      -H "Authorization: Bearer $OPS_LLM_API_KEY" \
      -H 'Content-Type: application/json' \
      -d "{\"model\":\"$OPS_LLM_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复 OK\"}],\"stream\":false,\"max_tokens\":16}")"; then
    PREFLIGHT_HTTP="000"
  fi

  if [[ ! "$PREFLIGHT_HTTP" =~ ^2[0-9][0-9]$ ]] \
      || ! jq -e '.choices[0].message.content' "$PREFLIGHT_FILE" >/dev/null 2>&1; then
    echo "[demo] ❌ DeepSeek 预检失败（HTTP $PREFLIGHT_HTTP）。"
    jq -r '.error.message // .message // "无法解析服务端错误"' "$PREFLIGHT_FILE" 2>/dev/null \
      || cat "$PREFLIGHT_FILE" 2>/dev/null || true
    echo "[demo] 响应文件：$PREFLIGHT_FILE"
    exit 1
  fi
  echo "[demo] ✅ DeepSeek API 预检通过，接下来验证项目完整链路。"
else
  OPS_LLM_PROVIDER=mock
  export OPS_LLM_PROVIDER
  echo "[demo] LLM=Mock（未提供 Key）"
fi

export OPS_API_TOKEN
export OPS_TRACE_FILE="$TRACE_FILE"
export OPS_EXEC_DRY_RUN="$DRY_RUN"
# 固定执行输出/备份落盘目录到仓库根，避免随脚本运行位置漂移导致「落盘日志找不到」；
# 前端「查看完整输出」按钮回读的就是该目录。
EXEC_OUTPUT_DIR="${OPS_EXEC_OUTPUT_AUDIT_DIR:-$REPO_DIR/logs/exec-output}"
export OPS_EXEC_OUTPUT_AUDIT_DIR="$EXEC_OUTPUT_DIR"
export OPS_EXEC_BACKUP_DIR="${OPS_EXEC_BACKUP_DIR:-$REPO_DIR/logs/backups}"
mkdir -p "$EXEC_OUTPUT_DIR" "$OPS_EXEC_BACKUP_DIR"
if [[ "$DRY_RUN" == "true" ]]; then
  echo "[demo] dry-run=true（模拟执行：变更命令不会真跑、不产生落盘输出。真机实测请用 DRY_RUN=false 启动）"
else
  echo "[demo] ⚠️ dry-run=false（真实执行模式：变更命令将真正落地，请确认测试环境安全）"
fi
echo "[demo] token=$OPS_API_TOKEN  执行输出落盘=$EXEC_OUTPUT_DIR"

: >"$LOG_FILE"
cd "$REPO_DIR"
nohup "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" \
  --server.port="$PORT" --server.address="$BIND" \
  >"$LOG_FILE" 2>&1 &
BPID=$!
echo "$BPID" >"$PID_FILE"
echo "[demo] 后端已后台启动 PID=$BPID  日志=$LOG_FILE"

echo -n "[demo] 等待后端健康检查"
HEALTHY=0
for _ in $(seq 1 60); do
  if curl -fs --max-time 2 "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1; then
    HEALTHY=1
    echo " ✅"
    break
  fi
  if ! kill -0 "$BPID" 2>/dev/null; then
    echo " ❌"
    echo "[demo] 后端进程已退出："
    tail -50 "$LOG_FILE"
    exit 1
  fi
  echo -n "."
  sleep 1
done

if [[ "$HEALTHY" != "1" ]]; then
  echo " ❌ 60 秒内未通过健康检查"
  tail -50 "$LOG_FILE"
  stop_backend
  exit 1
fi

# 启动后真实链路探针：会经过 ReAct 感知、推理和意图审计。
# 如果任一步导致 FailoverLlmClient 切到 Mock，则拒绝宣称真实模式。
if [[ "$REAL_LLM_REQUESTED" == "1" ]]; then
  SMOKE_FILE="$LOG_DIR/real-llm-smoke.json"
  RUNTIME_FILE="$LOG_DIR/runtime-after-smoke.json"
  echo "[demo] 验证项目完整 LLM 链路（可能需要几十秒）..."

  if ! curl -sS --max-time 240 \
      -H "Authorization: Bearer $OPS_API_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"instruction":"检查系统运行时间，只使用只读命令","confirm":false}' \
      "http://127.0.0.1:${PORT}/api/ops/chat" >"$SMOKE_FILE"; then
    echo "[demo] ❌ 完整 LLM 链路探针请求失败。"
    tail -50 "$LOG_FILE"
    stop_backend
    exit 1
  fi

  if ! curl -sS --max-time 10 \
      -H "Authorization: Bearer $OPS_API_TOKEN" \
      "http://127.0.0.1:${PORT}/api/ops/runtime" >"$RUNTIME_FILE"; then
    echo "[demo] ❌ 无法读取运行模式。"
    stop_backend
    exit 1
  fi

  LLM_MODE="$(jq -r '.data.llmMode // "UNKNOWN"' "$RUNTIME_FILE")"
  LLM_PROVIDER_ACTIVE="$(jq -r '.data.llmProvider // "unknown"' "$RUNTIME_FILE")"
  if [[ "$LLM_MODE" != "REAL" ]]; then
    echo "[demo] ❌ 完整链路已降级：mode=$LLM_MODE provider=$LLM_PROVIDER_ACTIVE"
    echo "[demo] 不会继续显示为真实 LLM 演示。"
    echo "[demo] 探针响应：$SMOKE_FILE"
    echo "[demo] 后端日志：$LOG_FILE"
    grep -E 'llm-failover|调用大模型|状态码|切换到备用模型' "$LOG_FILE" | tail -30 || true
    stop_backend
    exit 1
  fi
  echo "[demo] ✅ LLM=真实 $LLM_PROVIDER_ACTIVE（完整链路验证通过）"
fi

LANIP="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo "====================================================="
echo "OpsGuard Web 演示已就绪，后端持续运行中："
echo "本机浏览器:  http://127.0.0.1:${PORT}/"
if [[ "$BIND" == "0.0.0.0" && -n "$LANIP" ]]; then
  echo "同网段访问: ${LANIP}:${PORT}/"
fi
echo "API Token :  $OPS_API_TOKEN"
if [[ "$DRY_RUN" == "true" ]]; then
  echo "执行模式  :  模拟执行（dry-run）。真实执行请：DRY_RUN=false ./scripts/demo-start.sh"
else
  echo "执行模式  :  ⚠️ 真实执行。完整命令输出落盘: $EXEC_OUTPUT_DIR"
fi
echo "停止演示  :  ./scripts/demo-start.sh stop"
printf '查看日志  :  tail -f %q\n' "$LOG_FILE"
echo "====================================================="
