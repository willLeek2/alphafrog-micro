#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# BuildKit 才支持构建阶段 --network=host（ECS 上代理常只监听 127.0.0.1）
export DOCKER_BUILDKIT=1

USE_PROXY=${USE_PROXY:-1}
DOCKER_PROXY_PORT=${DOCKER_PROXY_PORT:-7890}

# Linux 宿主机（如阿里云 ECS）代理多绑定 127.0.0.1，构建须 --network=host；macOS 可用 host.docker.internal
if [ "$(uname -s)" = "Linux" ]; then
  USE_PROXY_HOST_NETWORK=${USE_PROXY_HOST_NETWORK:-1}
  DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-127.0.0.1}
else
  USE_PROXY_HOST_NETWORK=${USE_PROXY_HOST_NETWORK:-0}
  DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-host.docker.internal}
fi

# 必须初始化为数组；用空字符串会导致 docker buildx 参数错位、丢失构建上下文 PATH
NETWORK_ARGS=()
HOST_ARGS=()
PROXY_ARGS=()

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  if [ "$USE_PROXY_HOST_NETWORK" = "1" ] || [ "$USE_PROXY_HOST_NETWORK" = "true" ]; then
    PROXY_URL="http://127.0.0.1:${DOCKER_PROXY_PORT}"
    NETWORK_ARGS=(--network=host)
    echo "[pythonSandbox] USE_PROXY=1, --network=host, proxy=${PROXY_URL}"
  else
    PROXY_URL="http://${DOCKER_PROXY_HOST}:${DOCKER_PROXY_PORT}"
    HOST_ARGS=(--add-host=host.docker.internal:host-gateway)
    echo "[pythonSandbox] USE_PROXY=1, proxy=${PROXY_URL} (host-gateway)"
  fi
  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS=(
    --build-arg "http_proxy=${PROXY_URL}"
    --build-arg "https_proxy=${PROXY_URL}"
  )

  if ! (echo >/dev/tcp/127.0.0.1/"${DOCKER_PROXY_PORT}") 2>/dev/null; then
    echo "[pythonSandbox] ERROR: 宿主机 127.0.0.1:${DOCKER_PROXY_PORT} 无进程监听，请先启动 Clash 等代理。" >&2
    echo "  无代理时可: USE_PROXY=0 PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ bash $0" >&2
    exit 1
  fi
else
  unset https_proxy http_proxy all_proxy
  echo "[pythonSandbox] USE_PROXY=0（直连或仅用 PIP_INDEX_URL 镜像）"
fi

PIP_ARGS=()
if [ -n "${PIP_INDEX_URL:-}" ]; then
  PIP_ARGS+=(--build-arg "PIP_INDEX_URL=${PIP_INDEX_URL}")
fi
if [ -n "${PIP_EXTRA_INDEX_URL:-}" ]; then
  PIP_ARGS+=(--build-arg "PIP_EXTRA_INDEX_URL=${PIP_EXTRA_INDEX_URL}")
fi

# Build the runtime image for the sandbox (contains numpy, pandas, etc.)
docker build \
  "${NETWORK_ARGS[@]}" \
  "${HOST_ARGS[@]}" \
  "${PROXY_ARGS[@]}" \
  "${PIP_ARGS[@]}" \
  -t alphafrog-sandbox-runtime:latest \
  -f "$SCRIPT_DIR/Dockerfile.runtime" \
  "$SCRIPT_DIR"

# Build the service image
docker build \
  "${NETWORK_ARGS[@]}" \
  "${HOST_ARGS[@]}" \
  "${PROXY_ARGS[@]}" \
  -t alphafrog-python-sandbox:latest \
  "$SCRIPT_DIR"
