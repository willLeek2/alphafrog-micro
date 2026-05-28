#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# BuildKit 支持构建阶段 --network=host（Linux 上代理常只监听 127.0.0.1）
export DOCKER_BUILDKIT=1

USE_PROXY=${USE_PROXY:-1}
DOCKER_PROXY_PORT=${DOCKER_PROXY_PORT:-7890}

# Linux 宿主机代理多绑定 127.0.0.1，构建须 --network=host；macOS 用 host.docker.internal 访问宿主机 Clash
if [ "$(uname -s)" = "Linux" ]; then
  USE_PROXY_HOST_NETWORK=${USE_PROXY_HOST_NETWORK:-1}
  DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-127.0.0.1}
else
  USE_PROXY_HOST_NETWORK=${USE_PROXY_HOST_NETWORK:-0}
  DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-host.docker.internal}
fi

NETWORK_ARGS=()
HOST_ARGS=()
PROXY_ARGS=()

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  if [ "$USE_PROXY_HOST_NETWORK" = "1" ] || [ "$USE_PROXY_HOST_NETWORK" = "true" ]; then
    PROXY_URL="http://127.0.0.1:${DOCKER_PROXY_PORT}"
    NETWORK_ARGS=(--network=host)
    echo "[agentLangchain] USE_PROXY=1, --network=host, proxy=${PROXY_URL}"
  else
    PROXY_URL="http://${DOCKER_PROXY_HOST}:${DOCKER_PROXY_PORT}"
    HOST_ARGS=(--add-host=host.docker.internal:host-gateway)
    echo "[agentLangchain] USE_PROXY=1, proxy=${PROXY_URL} (host-gateway)"
  fi
  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS=(
    --build-arg "http_proxy=${PROXY_URL}"
    --build-arg "https_proxy=${PROXY_URL}"
  )

  if ! (echo >/dev/tcp/127.0.0.1/"${DOCKER_PROXY_PORT}") 2>/dev/null; then
    echo "[agentLangchain] ERROR: 宿主机 127.0.0.1:${DOCKER_PROXY_PORT} 无进程监听，请先启动 Clash 等代理。" >&2
    echo "  无代理时可: USE_PROXY=0 bash $0" >&2
    exit 1
  fi
else
  unset https_proxy http_proxy all_proxy
  echo "[agentLangchain] USE_PROXY=0（直连 apt / 拉取基础镜像）"
fi

docker build \
  "${NETWORK_ARGS[@]}" \
  "${HOST_ARGS[@]}" \
  "${PROXY_ARGS[@]}" \
  -t alphafrog-micro-agent-langchain-service:latest \
  "$SCRIPT_DIR"
