#!/usr/bin/env bash
# OpsGuard L3 级联故障证据采集 —— 龙芯麒麟 V11（LoongArch）
#
# 合规打包：安装包不含第三方依赖；部署时 mvn 按 pom.xml 从 Maven 镜像下载依赖。
# 依赖均为架构无关的纯 Java jar，x86 与 LoongArch 通用。
#
# 判级规则（源码 CrossSourceRca.grade）：
#   L3 = 指标 CRITICAL 且 (日志域升高 || 5min 内命中同源错误日志)
#   disk-usage 同源类型 = DISK_FULL / IO；阈值 disk-critical-percent=90%
#
# 前提（缺一只到 L2）：
#   ① root 运行（journalctl -p 3 读系统日志；logger 注入 journal）
#   ② JDK17(loongarch64)+Maven 已装；源码已就位；可访问 Maven 镜像
#   ③ 服务与压盘/注日志在同一台机
#
# 用法：sudo REPO=$HOME/softbei bash collect-l3-evidence-kylin.sh
# 可选：BASE(默认 http://127.0.0.1:8080) TARGET_PCT(默认91) OUT(默认 $REPO/backend/rca-evidence)
set -euo pipefail

REPO="${REPO:-$HOME/softbei}"
BASE="${BASE:-http://127.0.0.1:8080}"
TARGET_PCT="${TARGET_PCT:-91}"
OUT="${OUT:-$REPO/backend/rca-evidence}"; mkdir -p "$OUT"
FILL="/var/tmp/opsguard-fill.bin"
EVID="$OUT/l3-cascade-kylin.json"

# 0) 工具链校验（缺则给出麒麟 yum 安装命令）
command -v java >/dev/null || { echo "❌ 未装 JDK：sudo yum install -y java-17-openjdk-devel"; exit 1; }
command -v mvn  >/dev/null || { echo "❌ 未装 Maven：sudo yum install -y maven"; exit 1; }
command -v jq   >/dev/null || echo "ℹ️ 无 jq，将用 grep 解析（不影响判级）"
java -version 2>&1 | head -n1

cleanup(){ rm -f "$FILL"; }; trap cleanup EXIT

# 1) 构建：依赖从 Maven 镜像下载，不打进安装包
#    如 Maven Central 慢，可在 ~/.m2/settings.xml 配阿里云镜像：
#    <mirror><id>aliyun</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror>
cd "$REPO/backend"
mvn -q clean package -DskipTests

# 2) 后台启动（mock + dry-run，安全，不真正改系统）
nohup java -jar target/*.jar > /tmp/opsguard.log 2>&1 &
for i in $(seq 1 90); do curl -sf "$BASE/api/ops/runtime" >/dev/null 2>&1 && break; sleep 2; done
curl -sf "$BASE/api/ops/runtime" >/dev/null 2>&1 || { echo "❌ 服务未就绪，看 /tmp/opsguard.log："; tail -n 30 /tmp/opsguard.log; exit 1; }

# 3) 压满根分区到 ≥TARGET_PCT%（触发 disk-usage CRITICAL，阈值 90%）
read -r total used < <(df -P / | awk 'NR==2{print $2, $3}')
need=$(( total * TARGET_PCT / 100 - used ))
if [ "$need" -gt 0 ]; then
  fallocate -l "${need}K" "$FILL" 2>/dev/null || dd if=/dev/zero of="$FILL" bs=1024 count="$need" status=none
fi
df -P / | awk 'NR==2{print "[disk] 使用率 " $5 " mount " $6}'

# 4) 5min 窗口内注入同源 err 日志（DISK_FULL / IO）
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
  echo "✅ PASS：L3 证据已存 -> $EVID（把该 JSON 贴回对话即可回填 docs/19 + README）"
else
  echo "⚠️ 当前 ${LEVEL:-未知}，排查：① df -P / 是否 ≥90% ② journalctl -p 3 -n 5 是否见刚注入日志 ③ 服务/logger/压盘是否同机同用户 ④ 是否 root"
fi
