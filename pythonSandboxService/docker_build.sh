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
    echo "[pythonSandbox] proxy=${PROXY_URL}, --network=host (auto-detected loopback proxy)"
  else
    echo "[pythonSandbox] proxy=${PROXY_URL}"
  fi

  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS=(
    --build-arg "http_proxy=${PROXY_URL}"
    --build-arg "https_proxy=${PROXY_URL}"
  )

  # 探活检查（curl 替代 /dev/tcp），失败只警告不退出
  if ! curl -s --max-time 3 "$PROXY_URL" >/dev/null 2>&1; then
    echo "[pythonSandbox] WARNING: 代理 ${PROXY_URL} 不可达，构建可能失败。" >&2
    echo "  跳过代理可: USE_PROXY=0 PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ bash $0" >&2
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
