#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/collector.version"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "[otel-collector] ERROR: 固定版本信息不存在：${VERSION_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$VERSION_FILE"

if [[ ! "${OTEL_COLLECTOR_IMAGE:-}" =~ ^otel/opentelemetry-collector-contrib:[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "[otel-collector] ERROR: 采集器镜像必须固定到明确版本。" >&2
  exit 1
fi
if [[ "${OTEL_COLLECTOR_CONFIG_FILE:-}" != "otel-collector-config.yaml" ]]; then
  echo "[otel-collector] ERROR: 配置文件名与固定合同不一致。" >&2
  exit 1
fi
if [[ ! "${OTEL_COLLECTOR_CONFIG_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "[otel-collector] ERROR: 配置摘要必须是 64 位小写 SHA-256。" >&2
  exit 1
fi

config_path="$SCRIPT_DIR/$OTEL_COLLECTOR_CONFIG_FILE"
if [[ ! -f "$config_path" ]]; then
  echo "[otel-collector] ERROR: 采集器配置不存在：${config_path}" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha256="$(sha256sum "$config_path" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  actual_sha256="$(shasum -a 256 "$config_path" | awk '{print $1}')"
else
  echo "[otel-collector] ERROR: 找不到 sha256sum 或 shasum。" >&2
  exit 1
fi

if [[ "$actual_sha256" != "$OTEL_COLLECTOR_CONFIG_SHA256" ]]; then
  echo "[otel-collector] ERROR: 配置摘要不一致。" >&2
  echo "[otel-collector] 期望：${OTEL_COLLECTOR_CONFIG_SHA256}" >&2
  echo "[otel-collector] 实际：${actual_sha256}" >&2
  exit 1
fi

echo "[otel-collector] 配置校验通过：${OTEL_COLLECTOR_IMAGE}，SHA-256=${actual_sha256}。"
