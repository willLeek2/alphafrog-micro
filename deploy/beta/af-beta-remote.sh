#!/usr/bin/env bash
# 从开发机调用 Beta 机本机接待命令 af-beta 的 SSH 包装。
# 主机地址由每台开发机自己的环境变量 AF_BETA_SSH 提供（例如 user@beta-host），
# 不写进仓库。用法：bash deploy/beta/af-beta-remote.sh <af-beta 子命令及参数>
set -euo pipefail

if [ -z "${AF_BETA_SSH:-}" ]; then
  echo "AF_BETA_SSH 未设置。请在本机环境导出 Beta 机 SSH 目标（例如 user@beta-host）后重试。" >&2
  exit 2
fi

exec ssh "$AF_BETA_SSH" af-beta "$@"
