#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_FILE="${AF_OTEL_RUNTIME_ENV_FILE:-$SCRIPT_DIR/runtime.env}"

service_image() {
  case "$1" in
    domestic-stock-service) echo "alphafrog-micro-domestic-stock-service:latest" ;;
    domestic-index-service) echo "alphafrog-micro-domestic-index-service:latest" ;;
    domestic-fund-service) echo "alphafrog-micro-domestic-fund-service:latest" ;;
    domestic-listed-asset-service) echo "alphafrog-micro-domestic-listed-asset-service:latest" ;;
    domestic-fetch-service) echo "alphafrog-micro-domestic-fetch-service:latest" ;;
    admin-service) echo "alphafrog-micro-admin-service:latest" ;;
    portfolio-service) echo "alphafrog-micro-portfolio-service:latest" ;;
    agent-langchain-service) echo "alphafrog-micro-agent-langchain-service:latest" ;;
    external-info-service) echo "alphafrog-micro-external-info-service:latest" ;;
    python-sandbox-gateway-service) echo "alphafrog-micro-python-sandbox-gateway-service:latest" ;;
    frontend) echo "alphafrog-micro-frontend:latest" ;;
    *) return 1 ;;
  esac
}

service_image_variable() {
  case "$1" in
    domestic-stock-service) echo "AF_BUILD_IMAGE_ID_DOMESTIC_STOCK_SERVICE" ;;
    domestic-index-service) echo "AF_BUILD_IMAGE_ID_DOMESTIC_INDEX_SERVICE" ;;
    domestic-fund-service) echo "AF_BUILD_IMAGE_ID_DOMESTIC_FUND_SERVICE" ;;
    domestic-listed-asset-service) echo "AF_BUILD_IMAGE_ID_DOMESTIC_LISTED_ASSET_SERVICE" ;;
    domestic-fetch-service) echo "AF_BUILD_IMAGE_ID_DOMESTIC_FETCH_SERVICE" ;;
    admin-service) echo "AF_BUILD_IMAGE_ID_ADMIN_SERVICE" ;;
    portfolio-service) echo "AF_BUILD_IMAGE_ID_PORTFOLIO_SERVICE" ;;
    agent-langchain-service) echo "AF_BUILD_IMAGE_ID_AGENT_LANGCHAIN_SERVICE" ;;
    external-info-service) echo "AF_BUILD_IMAGE_ID_EXTERNAL_INFO_SERVICE" ;;
    python-sandbox-gateway-service) echo "AF_BUILD_IMAGE_ID_PYTHON_SANDBOX_GATEWAY_SERVICE" ;;
    frontend) echo "AF_BUILD_IMAGE_ID_FRONTEND" ;;
    *) return 1 ;;
  esac
}

validate_resource_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" || "$value" == *','* || "$value" == *'='* || "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
    echo "[otel-preflight] ERROR: ${name} 为空或含逗号、等号、换行。" >&2
    return 1
  fi
}

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <compose-service> [...]" >&2
  exit 2
fi

build_commit="${AF_BUILD_COMMIT:-$(git -C "$ROOT_DIR" rev-parse HEAD)}"
build_version="${AF_BUILD_VERSION:-$(git -C "$ROOT_DIR" describe --tags --always)}"
deployment_id="${AF_DEPLOYMENT_ID:-stable}"
lane_tag="${AF_LANE_TAG:-stable}"

validate_resource_value deployment.id "$deployment_id"
validate_resource_value lane.tag "$lane_tag"
validate_resource_value service.version "$build_version"
validate_resource_value git.commit "$build_commit"

if [[ "$deployment_id" != "stable" && ! "$deployment_id" =~ ^[a-z0-9]([a-z0-9-]{1,62}[a-z0-9])$ ]]; then
  echo "[otel-preflight] ERROR: deployment.id 必须是 stable，或 3–64 位小写字母、数字、连字符，并且首尾不是连字符。" >&2
  exit 1
fi
if [[ "$lane_tag" != "stable" && "$lane_tag" != "lane-test" ]]; then
  echo "[otel-preflight] ERROR: 首版 lane.tag 只接受 stable 或 lane-test。" >&2
  exit 1
fi
if [[ "$build_version" == "local" ]]; then
  echo "[otel-preflight] ERROR: 部署脚本不接受 service.version=local。" >&2
  exit 1
fi
if [[ ! "$build_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "[otel-preflight] ERROR: git.commit 必须是 40 位小写提交标识。" >&2
  exit 1
fi

temporary_file="$(mktemp "$SCRIPT_DIR/.runtime-env.XXXXXX")"
cleanup() {
  rm -f "$temporary_file"
}
trap cleanup EXIT
chmod 600 "$temporary_file"
{
  printf 'AF_BUILD_VERSION=%q\n' "$build_version"
  printf 'AF_BUILD_COMMIT=%q\n' "$build_commit"
  printf 'AF_DEPLOYMENT_ID=%q\n' "$deployment_id"
  printf 'AF_LANE_TAG=%q\n' "$lane_tag"
} > "$temporary_file"

for service_name in "$@"; do
  image_ref="$(service_image "$service_name" 2>/dev/null || true)"
  image_variable="$(service_image_variable "$service_name" 2>/dev/null || true)"
  if [[ -z "$image_ref" || -z "$image_variable" ]]; then
    echo "[otel-preflight] ERROR: 不认识 JVM 服务 ${service_name}。" >&2
    exit 1
  fi
  image_id="$(docker inspect --type=image --format '{{.Id}}' "$image_ref" 2>/dev/null || true)"
  if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "[otel-preflight] ERROR: ${service_name} 的本地镜像不存在或 Image ID 无效：${image_ref}。" >&2
    exit 1
  fi
  validate_resource_value image.digest "$image_id"
  printf '%s=%q\n' "$image_variable" "$image_id" >> "$temporary_file"
done

mv "$temporary_file" "$OUTPUT_FILE"
trap - EXIT
echo "[otel-preflight] 已为 $# 个 JVM 服务写入构建身份：${OUTPUT_FILE}。"
