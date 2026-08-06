#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# BuildKit 支持构建阶段 --network=host（Linux 上代理常只监听 127.0.0.1）
export DOCKER_BUILDKIT=1

USE_PROXY=${USE_PROXY:-1}

NETWORK_ARGS=()
HOST_ARGS=()
PROXY_ARGS=()

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  # 优先使用系统环境变量中的代理地址，不再硬编码 127.0.0.1:7890
  if [ -n "${http_proxy:-}" ]; then
    PROXY_URL="$http_proxy"
  elif [ -n "${https_proxy:-}" ]; then
    PROXY_URL="$https_proxy"
  else
    # 回退：仍可通过 DOCKER_PROXY_HOST / DOCKER_PROXY_PORT 覆盖
    DOCKER_PROXY_PORT=${DOCKER_PROXY_PORT:-7890}
    if [ "$(uname -s)" = "Linux" ]; then
      DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-127.0.0.1}
    else
      DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-host.docker.internal}
    fi
    PROXY_URL="http://${DOCKER_PROXY_HOST}:${DOCKER_PROXY_PORT}"
  fi

  # 代理地址指向 loopback 且是 Linux 时，docker build 须 --network=host
  if [ "$(uname -s)" = "Linux" ] && echo "$PROXY_URL" | grep -qE '://(127\.0\.0\.1|localhost)[:/]'; then
    NETWORK_ARGS=(--network=host)
    echo "[agentLangchain] proxy=${PROXY_URL}, --network=host (auto-detected loopback proxy)"
  else
    echo "[agentLangchain] proxy=${PROXY_URL}"
  fi

  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS=(
    --build-arg "http_proxy=${PROXY_URL}"
    --build-arg "https_proxy=${PROXY_URL}"
  )

  # 探活检查（curl 替代 /dev/tcp），失败只警告不退出
  if ! curl -s --max-time 3 "$PROXY_URL" >/dev/null 2>&1; then
    echo "[agentLangchain] WARNING: 代理 ${PROXY_URL} 不可达，构建可能失败。" >&2
    echo "  跳过代理可: USE_PROXY=0 bash $0" >&2
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
