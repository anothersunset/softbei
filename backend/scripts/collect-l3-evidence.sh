#!/usr/bin/env bash
# OpsGuard L3 级联故障证据一键采集 —— 在云服务器运行
#
# 作用：在一台主机上自动构造「指标 CRITICAL + 5min 内同源 err 日志」的级联场景，
#       触发跨源根因分析（CrossSourceRca），落地 L3 证据 JSON 并校验。
#
# 判级规则（源码 CrossSourceRca.grade）：
#   L3 = 指标 CRITICAL 且 (日志域升高 || 5min 内命中同源错误日志)
#   disk-usage 同源类型 = DISK_FULL / IO；阈值 disk-critical-percent=90%
#
# 前提（缺一不可，否则只到 L2）：
#   ① root 运行（journalctl -p 3 需读系统日志；logger 注入 journal）
#   ② 压盘 / 注日志 / 跑 jar 必须在同一台主机（df/free/journalctl 读本机）
#
# 用法：sudo bash backend/scripts/collect-l3-evidence.sh
#   可选环境变量：REPO=仓库路径  BASE=服务地址  TARGET_PCT=目标磁盘使用率(默认91)
set -euo pipefail

REPO="${REPO:-$HOME/softbei}"
BASE="${BASE:-http://127.0.0.1:8080}"
TARGET_PCT="${TARGET_PCT:-91}"        # 目标磁盘使用率（≥90% 即 CRITICAL）
FILL="/var/tmp/opsguard-fill.bin"
OUT="$REPO/backend/rca-evidence"; mkdir -p "$OUT"
command -v jq >/dev/null || { apt-get update -y && apt-get install -y jq; }

cleanup(){ rm -f "$FILL"; }            # 跑完自动释放磁盘
trap cleanup EXIT

# 1) 构建 + 后台启动（mock + dry-run，安全，不真正改系统）
cd "$REPO/backend"; git pull --ff-only || true
mvn -q clean package -DskipTests
nohup java -jar target/*.jar > /tmp/opsguard.log 2>&1 &
for i in $(seq 1 60); do curl -sf "$BASE/api/ops/runtime" >/dev/null && break; sleep 2; done

# 2) 把根分区写到 ≥TARGET_PCT%（触发 disk-usage CRITICAL，阈值 90%）
read -r total used < <(df -P / | awk 'NR==2{print $2, $3}')   # 1K 块
need=$(( total * TARGET_PCT / 100 - used ))
[ "$need" -gt 0 ] && fallocate -l "${need}K" "$FILL"
df -P / | awk 'NR==2{print "[disk] 使用率 " $5 " mount " $6}'

# 3) 5min 窗口内注入「同源」err 日志（disk-usage 期望 DISK_FULL / IO）
logger -p user.err "no space left on device: write error on /var/log/app.log"
logger -p user.err "EXT4-fs error (device vda1): reading directory lblock"

# 4) 触发跨源 RCA，落证据
sleep 3
curl -s "$BASE/api/ops/rca" | tee "$OUT/l3-cascade.json" >/dev/null
LEVEL=$(jq -r '.data.rca.overallLevel' "$OUT/l3-cascade.json")
echo "==== RCA overallLevel = $LEVEL ===="
jq -r '.data.rca.summary' "$OUT/l3-cascade.json"
jq -r '.data.report.findings[]|select(.id=="disk-usage")|"[disk-usage] "+.severity+" "+.observed' "$OUT/l3-cascade.json"

# 5) 判定
if [ "$LEVEL" = "L3" ]; then
  echo "✅ PASS：L3 证据已存 -> $OUT/l3-cascade.json"
else
  echo "⚠️ 当前 $LEVEL，请检查：① 磁盘是否真到 ≥90%  ② journalctl -p 3 -n 5 能否看到刚注入的日志  ③ 服务与 logger 是否同一台机/同一用户  ④ 进程能否读 journal（建议 root 运行）"
fi
