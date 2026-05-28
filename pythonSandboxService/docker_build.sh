#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

USE_PROXY=${USE_PROXY:-1}
# 构建容器访问宿主机代理：默认 host.docker.internal（勿用 127.0.0.1，那是构建容器自身）
DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-host.docker.internal}
DOCKER_PROXY_PORT=${DOCKER_PROXY_PORT:-7890}
# 代理仅监听宿主机 127.0.0.1 时设为 1，构建使用 --network=host + 127.0.0.1:端口
USE_PROXY_HOST_NETWORK=${USE_PROXY_HOST_NETWORK:-0}

PROXY_ARGS=""
HOST_ARGS=""
NETWORK_ARGS=""

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  if [ "$USE_PROXY_HOST_NETWORK" = "1" ] || [ "$USE_PROXY_HOST_NETWORK" = "true" ]; then
    PROXY_URL="http://127.0.0.1:${DOCKER_PROXY_PORT}"
    NETWORK_ARGS="--network=host"
  else
    PROXY_URL="http://${DOCKER_PROXY_HOST}:${DOCKER_PROXY_PORT}"
    HOST_ARGS="--add-host=host.docker.internal:host-gateway"
  fi
  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS="--build-arg http_proxy=$PROXY_URL --build-arg https_proxy=$PROXY_URL"
else
  unset https_proxy http_proxy all_proxy
fi

PIP_ARGS=""
if [ -n "${PIP_INDEX_URL:-}" ]; then
  PIP_ARGS="$PIP_ARGS --build-arg PIP_INDEX_URL=${PIP_INDEX_URL}"
fi

if [ -n "${PIP_EXTRA_INDEX_URL:-}" ]; then
  PIP_ARGS="$PIP_ARGS --build-arg PIP_EXTRA_INDEX_URL=${PIP_EXTRA_INDEX_URL}"
fi

BUILD_COMMON=( $NETWORK_ARGS $HOST_ARGS $PROXY_ARGS )

# Build the runtime image for the sandbox (contains numpy, pandas, etc.)
docker build "${BUILD_COMMON[@]}" $PIP_ARGS -t alphafrog-sandbox-runtime:latest -f "$SCRIPT_DIR/Dockerfile.runtime" "$SCRIPT_DIR"

# Build the service image
docker build "${BUILD_COMMON[@]}" -t alphafrog-python-sandbox:latest "$SCRIPT_DIR"
