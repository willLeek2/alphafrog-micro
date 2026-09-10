#!/usr/bin/env bash
# 从开发机调用 Beta 机本机接待命令 af-beta 的 SSH 包装。
#
# 访问能力由 Beta 侧的 SSH 强制命令收紧：本脚本使用的专用钥匙在 beta 的
# authorized_keys 里绑定了 command="/opt/alphafrog-beta/bin/af-beta-shell" 与
# restrict 选项——即使本机把 ssh 参数改掉，服务端也只会运行 af-beta 的白名单
# 子命令，拿不到 shell，也不能转发端口。私钥只存在各开发机本机，不进仓库。
#
# 环境变量：
#   AF_BETA_SSH  Beta 机 SSH 目标（例如 user@beta-host），由每台开发机自己配置。
#   AF_BETA_KEY  专用私钥路径，默认 ~/.ssh/af-beta-ed25519。
#
# 用法：bash deploy/beta/af-beta-remote.sh <af-beta 子命令及参数>
set -euo pipefail

if [ -z "${AF_BETA_SSH:-}" ]; then
  echo "AF_BETA_SSH 未设置。请在本机环境导出 Beta 机 SSH 目标（例如 user@beta-host）后重试。" >&2
  exit 2
fi

KEY="${AF_BETA_KEY:-$HOME/.ssh/af-beta-ed25519}"
if [ ! -f "$KEY" ]; then
  echo "专用私钥不存在: $KEY。生成方式: ssh-keygen -t ed25519 -f \"$KEY\" -C dev-agent，公钥交给 Beta 侧管理员登记。" >&2
  exit 2
fi

exec ssh \
  -i "$KEY" \
  -o IdentitiesOnly=yes \
  -o BatchMode=yes \
  "$AF_BETA_SSH" af-beta "$@"
