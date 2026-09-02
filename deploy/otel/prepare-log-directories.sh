#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_ROOT="${AF_OTEL_LOG_ROOT_DIR:-$ROOT_DIR/data/logs}"
DEPLOYMENT_UID="$(id -u)"

is_jvm_service() {
  case "$1" in
    domestic-stock-service | domestic-index-service | domestic-fund-service | \
      domestic-listed-asset-service | domestic-fetch-service | admin-service | \
      portfolio-service | agent-langchain-service | external-info-service | \
      python-sandbox-gateway-service | frontend)
      return 0
      ;;
    *) return 1 ;;
  esac
}

directory_mode() {
  stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

directory_uid() {
  stat -c '%u' -- "$1" 2>/dev/null || stat -f '%u' "$1"
}

ensure_private_directory() {
  local directory="$1"
  local description="$2"
  local mode
  local owner_uid

  if [[ -L "$directory" || ( -e "$directory" && ! -d "$directory" ) ]]; then
    echo "[otel-logs] ERROR: ${description} 必须是真实目录，不能是文件或符号链接：${directory}" >&2
    return 1
  fi

  if [[ ! -d "$directory" ]]; then
    (umask 077 && mkdir -p -- "$directory")
    chmod 0700 "$directory"
  fi

  mode="$(directory_mode "$directory")"
  owner_uid="$(directory_uid "$directory")"
  if [[ "$owner_uid" != "$DEPLOYMENT_UID" ]]; then
    echo "[otel-logs] ERROR: ${description} 必须由当前部署账号 UID ${DEPLOYMENT_UID} 持有，实际为 UID ${owner_uid}：${directory}" >&2
    return 1
  fi
  if [[ "$mode" != "700" ]]; then
    echo "[otel-logs] ERROR: ${description} 权限必须是 0700，实际为 ${mode}：${directory}" >&2
    echo "[otel-logs] 部署脚本不会自动修改已有目录；请由部署账号核对内容后收紧权限再重试。" >&2
    return 1
  fi
}

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <compose-service> [...]" >&2
  exit 2
fi

for service_name in "$@"; do
  if ! is_jvm_service "$service_name"; then
    echo "[otel-logs] ERROR: 不认识 JVM 服务 ${service_name}。" >&2
    exit 1
  fi
done

ensure_private_directory "$LOG_ROOT" "应用日志根目录"
for service_name in "$@"; do
  ensure_private_directory "$LOG_ROOT/$service_name" "${service_name} 日志目录"
done

echo "[otel-logs] 已确认 $# 个 JVM 服务的日志目录由部署账号持有且权限为 0700。"
