#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_TARGET="${1:-${PYTHON_SANDBOX_BUILD_TARGET:-all}}"

usage() {
  cat <<'EOF'
Usage:
  bash docker_build.sh            # build runtime and service images
  bash docker_build.sh all
  bash docker_build.sh runtime    # build alphafrog-sandbox-runtime:latest only
  bash docker_build.sh service    # build alphafrog-python-sandbox:latest only
EOF
}

case "$BUILD_TARGET" in
  all|runtime|service) ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    echo "[pythonSandbox] Unknown build target: $BUILD_TARGET" >&2
    usage >&2
    exit 1
    ;;
esac

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

run_docker_build() {
  local docker_args=(docker build)
  if [ "${#NETWORK_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${NETWORK_ARGS[@]}")
  fi
  if [ "${#HOST_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${HOST_ARGS[@]}")
  fi
  if [ "${#PROXY_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${PROXY_ARGS[@]}")
  fi
  if [ "${#PIP_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${PIP_ARGS[@]}")
  fi
  docker_args+=("$@")
  "${docker_args[@]}"
}

if [ "$BUILD_TARGET" = "all" ] || [ "$BUILD_TARGET" = "runtime" ]; then
  echo "[pythonSandbox] Building runtime image: alphafrog-sandbox-runtime:latest"
  run_docker_build \
    -t alphafrog-sandbox-runtime:latest \
    -f "$SCRIPT_DIR/Dockerfile.runtime" \
    "$SCRIPT_DIR"
fi

if [ "$BUILD_TARGET" = "all" ] || [ "$BUILD_TARGET" = "service" ]; then
  echo "[pythonSandbox] Building service image: alphafrog-python-sandbox:latest"
  run_docker_build \
    -t alphafrog-python-sandbox:latest \
    "$SCRIPT_DIR"
fi
