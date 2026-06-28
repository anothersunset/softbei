#!/usr/bin/env bash
# OpsGuard L3 级联故障证据采集 —— 龙芯麒麟 V11（LoongArch）离线版
#
# 与 collect-l3-evidence.sh 的区别：面向「无外网、文件靠上传」的国产化 VM。
#   · 不 git pull、不 mvn 构建、不 apt-get —— jar 在 x86 本机预打包后上传
#   · jar 架构无关（纯 Java，无 JNI），x86 打的包可直接在 loongarch64 JDK 上跑
#   · jq 缺失时自动回退 grep/sed 解析，不影响判级
#
# 判级规则（源码 CrossSourceRca.grade）：
#   L3 = 指标 CRITICAL 且 (日志域升高 || 5min 内命中同源错误日志)
#   disk-usage 同源类型 = DISK_FULL / IO；阈值 disk-critical-percent=90%
#
# 前提（缺一只到 L2）：
#   ① root 运行（journalctl -p 3 读系统日志；logger 注入 journal）
#   ② jar 已上传、JDK17(loongarch64) 已就绪、服务与压盘/注日志在同一台机
#
# 用法：
#   sudo JAR=/root/ops-guard.jar bash collect-l3-evidence-kylin.sh
# 可选环境变量：
#   JAR=jar 路径（默认在 $HOME 与当前目录搜 *.jar）
#   BASE=服务地址（默认 http://127.0.0.1:8080）
#   TARGET_PCT=目标磁盘使用率（默认 91，≥90% 即 CRITICAL）
#   OUT=证据输出目录（默认 $HOME/rca-evidence）
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
TARGET_PCT="${TARGET_PCT:-91}"
OUT="${OUT:-$HOME/rca-evidence}"; mkdir -p "$OUT"
FILL="/var/tmp/opsguard-fill.bin"
EVID="$OUT/l3-cascade-kylin.json"

# 0) 定位 jar
JAR="${JAR:-}"
if [ -z "$JAR" ]; then
  JAR=$(ls -1 "$HOME"/*.jar ./*.jar 2>/dev/null | head -n1 || true)
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "❌ 未找到 jar，请用 JAR=/path/ops-guard.jar 指定"; exit 1; }

# 1) 校验 JDK
command -v java >/dev/null || { echo "❌ 未检测到 java，请先装 loongarch64 JDK17：sudo yum install -y java-17-openjdk-devel"; exit 1; }
java -version 2>&1 | head -n1

cleanup(){ rm -f "$FILL"; }   # 跑完自动释放磁盘
trap cleanup EXIT

# 2) 后台启动服务（mock + dry-run，安全，不真正改系统）
echo "[boot] java -jar $JAR"
nohup java -jar "$JAR" > /tmp/opsguard.log 2>&1 &
for i in $(seq 1 90); do curl -sf "$BASE/api/ops/runtime" >/dev/null 2>&1 && break; sleep 2; done
curl -sf "$BASE/api/ops/runtime" >/dev/null 2>&1 || { echo "❌ 服务未就绪，看 /tmp/opsguard.log："; tail -n 30 /tmp/opsguard.log; exit 1; }

# 3) 压满根分区到 ≥TARGET_PCT%（触发 disk-usage CRITICAL，阈值 90%）
read -r total used < <(df -P / | awk 'NR==2{print $2, $3}')   # 1K 块
need=$(( total * TARGET_PCT / 100 - used ))
if [ "$need" -gt 0 ]; then
  fallocate -l "${need}K" "$FILL" 2>/dev/null || dd if=/dev/zero of="$FILL" bs=1024 count="$need" status=none
fi
df -P / | awk 'NR==2{print "[disk] 使用率 " $5 " mount " $6}'

# 4) 5min 窗口内注入「同源」err 日志（disk-usage 期望 DISK_FULL / IO）
logger -p user.err "no space left on device: write error on /var/log/app.log"
logger -p user.err "EXT4-fs error (device vda1): reading directory lblock"

# 5) 触发跨源 RCA，落证据
sleep 3
curl -s "$BASE/api/ops/rca" | tee "$EVID" >/dev/null

# 6) 解析 overallLevel（jq 优先，无 jq 回退 grep/sed）
if command -v jq >/dev/null; then
  LEVEL=$(jq -r '.data.rca.overallLevel' "$EVID")
  jq -r '.data.rca.summary' "$EVID"
  jq -r '.data.report.findings[]|select(.id=="disk-usage")|"[disk-usage] "+.severity+" "+.observed' "$EVID"
else
  LEVEL=$(grep -o '"overallLevel"[[:space:]]*:[[:space:]]*"[^"]*"' "$EVID" | head -n1 | sed 's/.*"\([^"]*\)"$/\1/')
fi
echo "==== RCA overallLevel = ${LEVEL:-未知} ===="

# 7) 判定
if [ "${LEVEL:-}" = "L3" ]; then
  echo "✅ PASS：L3 证据已存 -> $EVID"
  echo "   把该 JSON 贴回对话，即可回填 docs/19 + README + rca-evidence/"
else
  echo "⚠️ 当前 ${LEVEL:-未知}，排查：① df -P / 是否 ≥90%  ② journalctl -p 3 -n 5 能否看到刚注入日志  ③ 服务/logger/压盘是否同机同用户  ④ 是否 root 运行"
fi
