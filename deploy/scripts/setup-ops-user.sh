#!/usr/bin/env bash
# 在麒麟高级服务器版 V11 (LoongArch) 上创建最小权限运维账号。
# 用途：Agent 以该受限账号（非 root）执行运维动作，配合 sudoers 白名单限定可执行命令。
set -euo pipefail

OPS_USER="${OPS_USER:-opsagent}"

if id "$OPS_USER" &>/dev/null; then
  echo "[INFO] 账号 $OPS_USER 已存在"
else
  useradd -r -m -s /bin/bash "$OPS_USER"
  echo "[OK] 已创建受限账号 $OPS_USER"
fi

# 安装 sudoers 白名单（仅允许受控的运维命令，且免密码）
SUDOERS_SRC="$(dirname "$0")/sudoers-ops-agent"
if [ -f "$SUDOERS_SRC" ]; then
  install -m 0440 "$SUDOERS_SRC" /etc/sudoers.d/ops-agent
  visudo -cf /etc/sudoers.d/ops-agent
  echo "[OK] 已安装 sudoers 白名单到 /etc/sudoers.d/ops-agent"
else
  echo "[WARN] 未找到 sudoers-ops-agent，跳过"
fi

echo "[DONE] 最小权限账号准备完成。请在 application.yml 中设置 ops.exec.run-as-user=$OPS_USER 与 ops.exec.use-sudo=true。"
