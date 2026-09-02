#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/javaagent.version"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "[otel-javaagent] ERROR: 固定制品信息不存在：${VERSION_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$VERSION_FILE"

if ! command -v curl >/dev/null 2>&1; then
  echo "[otel-javaagent] ERROR: 找不到 curl，无法下载固定制品。" >&2
  exit 1
fi

target_path="$SCRIPT_DIR/$OTEL_JAVAAGENT_FILE"
temporary_path="$(mktemp "$SCRIPT_DIR/.opentelemetry-javaagent.XXXXXX")"
cleanup() {
  rm -f "$temporary_path"
}
trap cleanup EXIT

echo "[otel-javaagent] 下载固定版本 v${OTEL_JAVAAGENT_VERSION}。"
curl \
  --fail \
  --location \
  --retry 3 \
  --proto '=https' \
  --tlsv1.2 \
  --output "$temporary_path" \
  "$OTEL_JAVAAGENT_URL"

bash "$SCRIPT_DIR/verify-javaagent.sh" "$temporary_path"
mv "$temporary_path" "$target_path"
trap - EXIT
echo "[otel-javaagent] 已保存到 ${target_path}；该文件被 .gitignore 排除，不进入 Git。"
