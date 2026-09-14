#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/javaagent.version"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "[otel-javaagent] ERROR: 固定制品信息不存在：${VERSION_FILE}" >&2
  exit 1
fi

# 本文件由仓库维护者提交，变量值不能包含 shell 表达式或空白。
# shellcheck disable=SC1090
source "$VERSION_FILE"

required_variables=(
  OTEL_JAVAAGENT_VERSION
  OTEL_JAVAAGENT_FILE
  OTEL_JAVAAGENT_URL
  OTEL_JAVAAGENT_SIZE_BYTES
  OTEL_JAVAAGENT_SHA256
)
for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "[otel-javaagent] ERROR: ${VERSION_FILE} 缺少 ${variable_name}。" >&2
    exit 1
  fi
done

if [[ ! "$OTEL_JAVAAGENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "[otel-javaagent] ERROR: 版本格式无效：${OTEL_JAVAAGENT_VERSION}" >&2
  exit 1
fi
if [[ "$OTEL_JAVAAGENT_FILE" != "opentelemetry-javaagent.jar" ]]; then
  echo "[otel-javaagent] ERROR: 制品文件名必须是 opentelemetry-javaagent.jar。" >&2
  exit 1
fi
if [[ ! "$OTEL_JAVAAGENT_URL" =~ ^https://github\.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent\.jar$ ]]; then
  echo "[otel-javaagent] ERROR: 下载地址与固定版本不一致。" >&2
  exit 1
fi
if [[ ! "$OTEL_JAVAAGENT_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]]; then
  echo "[otel-javaagent] ERROR: 固定字节数必须是正整数。" >&2
  exit 1
fi
if [[ ! "$OTEL_JAVAAGENT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "[otel-javaagent] ERROR: 固定 SHA-256 摘要必须是 64 位小写十六进制。" >&2
  exit 1
fi

agent_path="${1:-$SCRIPT_DIR/$OTEL_JAVAAGENT_FILE}"
if [[ ! -f "$agent_path" ]]; then
  echo "[otel-javaagent] ERROR: Java Agent 不存在：${agent_path}" >&2
  echo "[otel-javaagent] 请先运行 deploy/otel/fetch-javaagent.sh。" >&2
  exit 1
fi

actual_size="$(wc -c < "$agent_path" | tr -d '[:space:]')"
if [[ "$actual_size" != "$OTEL_JAVAAGENT_SIZE_BYTES" ]]; then
  echo "[otel-javaagent] ERROR: 字节数不一致，期望 ${OTEL_JAVAAGENT_SIZE_BYTES}，实际 ${actual_size}。" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$agent_path" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  actual_sha256="$(shasum -a 256 "$agent_path" | awk '{print $1}')"
else
  echo "[otel-javaagent] ERROR: 找不到 sha256sum 或 shasum，无法校验制品。" >&2
  exit 1
fi

if [[ "$actual_sha256" != "$OTEL_JAVAAGENT_SHA256" ]]; then
  echo "[otel-javaagent] ERROR: SHA-256 摘要不一致。" >&2
  exit 1
fi

echo "[otel-javaagent] 校验通过：v${OTEL_JAVAAGENT_VERSION}，${actual_size} 字节，SHA-256=${actual_sha256}。"
