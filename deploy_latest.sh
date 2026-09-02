#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
export D15_CALLER_ID="deploy_latest.sh"  # v14 MF3: caller tag for docker mock

# 基础设施服务（不常重建）
INFRA_SERVICES=(
  redis
  rabbitmq
  nacos
  meilisearch
  otel-collector
)

# Python沙箱服务（独立于Java服务）
PYTHON_SERVICES=(
  python-sandbox-service
)

# Python沙箱运行时镜像（build-only，不是 compose service）
PYTHON_RUNTIME_IMAGES=(
  python-sandbox-runtime
)

# 业务服务（经常重建）
BUSINESS_SERVICES=(
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-listed-asset-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-langchain-service
  external-info-service
  python-sandbox-gateway-service
  frontend
)

# 所有 Java 业务服务
ALL_SERVICES=(
  "${BUSINESS_SERVICES[@]}"
)

usage() {
  cat <<'EOF'
Usage:
  ./deploy_latest.sh                  # rebuild all business services
  ./deploy_latest.sh serviceA serviceB
  ./deploy_latest.sh --services serviceA,serviceB
  ./deploy_latest.sh --with-infra     # rebuild with infrastructure services
  ./deploy_latest.sh --all            # rebuild all including python runtime/services
  ./deploy_latest.sh python-sandbox-runtime python-sandbox-service
  ./deploy_latest.sh --skip-maven     # skip Maven, still run docker_build.sh, then recreate containers
  ./deploy_latest.sh --deploy-only    # skip Maven and docker_build.sh, only recreate containers

Services:
  # Business Services
  domestic-stock-service
  domestic-index-service
  domestic-fund-service
  domestic-listed-asset-service
  domestic-fetch-service
  admin-service
  portfolio-service
  agent-langchain-service
  external-info-service
  python-sandbox-gateway-service
  frontend

  # Infrastructure (use --with-infra to include)
  redis, rabbitmq, nacos, meilisearch

  # Python Runtime Images (build-only)
  python-sandbox-runtime

  # Python Services (use --all to include)
  python-sandbox-service
EOF
}

service_build_script() {
  case "$1" in
    domestic-stock-service) echo "domesticStockService/docker_build.sh" ;;
    domestic-index-service) echo "domesticIndexService/docker_build.sh" ;;
    domestic-fund-service) echo "domesticFundService/docker_build.sh" ;;
    domestic-listed-asset-service) echo "domesticListedAssetService/docker_build.sh" ;;
    domestic-fetch-service) echo "domesticFetchService/docker_build.sh" ;;
    admin-service) echo "adminService/docker_build.sh" ;;
    portfolio-service) echo "portfolioService/docker_build.sh" ;;
    agent-langchain-service) echo "agentLangchainService/docker_build.sh" ;;
    external-info-service) echo "externalInfoService/docker_build.sh" ;;
    python-sandbox-service) echo "pythonSandboxService/docker_build.sh" ;;
    python-sandbox-runtime) echo "pythonSandboxService/docker_build.sh" ;;
    python-sandbox-gateway-service) echo "pythonSandboxGatewayService/docker_build.sh" ;;
    frontend) echo "frontend/docker_build.sh" ;;
    *) return 1 ;;
  esac
}

service_module() {
  case "$1" in
    domestic-stock-service) echo "domesticStockService" ;;
    domestic-index-service) echo "domesticIndexService" ;;
    domestic-fund-service) echo "domesticFundService" ;;
    domestic-listed-asset-service) echo "domesticListedAssetService" ;;
    domestic-fetch-service) echo "domesticFetchService" ;;
    admin-service) echo "adminService" ;;
    portfolio-service) echo "portfolioService" ;;
    agent-langchain-service) echo "agentLangchainService" ;;
    external-info-service) echo "externalInfoService" ;;
    python-sandbox-gateway-service) echo "pythonSandboxGatewayService" ;;
    frontend) echo "frontend" ;;
    *) return 1 ;;
  esac
}

is_in_list() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    if [[ "$item" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

service_build_args() {
  case "$1" in
    python-sandbox-runtime) echo "runtime" ;;
    python-sandbox-service)
      if is_in_list "python-sandbox-runtime" "${SELECTED[@]}"; then
        echo "service"
      else
        echo "all"
      fi
      ;;
    *) echo "" ;;
  esac
}

service_has_compose_container() {
  [[ "$1" != "python-sandbox-runtime" ]] && service_build_script "$1" >/dev/null 2>&1
}

service_known() {
  service_build_script "$1" >/dev/null 2>&1 || is_in_list "$1" "${INFRA_SERVICES[@]}"
}

# 参数解析
RAW_SERVICES=()
WITH_INFRA=false
WITH_ALL=false
SKIP_MAVEN=false
DEPLOY_ONLY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --with-infra)
      WITH_INFRA=true
      shift
      ;;
    --all)
      WITH_ALL=true
      shift
      ;;
    --skip-maven)
      SKIP_MAVEN=true
      shift
      ;;
    --deploy-only)
      DEPLOY_ONLY=true
      shift
      ;;
    -s|--services)
      shift
      if [[ $# -eq 0 ]]; then
        echo "Missing value for --services" >&2
        usage
        exit 1
      fi
      RAW_SERVICES+=("$1")
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
    *)
      RAW_SERVICES+=("$1")
      shift
      ;;
  esac
done

# 解析服务列表
SERVICES=()
if [[ ${#RAW_SERVICES[@]} -gt 0 ]]; then
  for item in "${RAW_SERVICES[@]}"; do
    IFS=',' read -r -a parts <<< "$item"
    for part in "${parts[@]}"; do
      name="${part// /}"
      if [[ -n "$name" ]]; then
        SERVICES+=("$name")
      fi
    done
  done
fi

# 确定要构建的服务列表
SELECTED=()
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  # 未指定服务，使用默认列表
  if [[ "$WITH_ALL" == true ]]; then
    SELECTED=("${PYTHON_RUNTIME_IMAGES[@]}" "${PYTHON_SERVICES[@]}" "${ALL_SERVICES[@]}")
  else
    SELECTED=("${BUSINESS_SERVICES[@]}")
  fi
else
  # 指定了具体服务
  SEEN_SERVICES=()
  for svc in "${SERVICES[@]}"; do
    if ! service_known "$svc"; then
      echo "Unknown service: $svc" >&2
      usage
      exit 1
    fi
    if [[ ${#SEEN_SERVICES[@]} -eq 0 ]] || ! is_in_list "$svc" "${SEEN_SERVICES[@]}"; then
      SEEN_SERVICES+=("$svc")
    fi
  done
  
  # 按 ALL_SERVICES 顺序输出
  for svc in "${ALL_SERVICES[@]}"; do
    if [[ ${#SEEN_SERVICES[@]} -gt 0 ]] && is_in_list "$svc" "${SEEN_SERVICES[@]}"; then
      SELECTED+=("$svc")
    fi
  done
  # 检查是否包含基础设施服务、Python runtime 镜像、Python 服务
  for svc in "${INFRA_SERVICES[@]}" "${PYTHON_RUNTIME_IMAGES[@]}" "${PYTHON_SERVICES[@]}"; do
    if [[ ${#SEEN_SERVICES[@]} -gt 0 ]] && is_in_list "$svc" "${SEEN_SERVICES[@]}"; then
      SELECTED+=("$svc")
    fi
  done
fi

echo "=== Selected services: ${SELECTED[*]} ==="

# 采集器镜像版本写在 compose 中，配置内容另用固定摘要校验。101 与 Beta
# 机器使用同一份 YAML，只通过环境变量改变 VictoriaLogs 地址。
bash "$ROOT_DIR/deploy/otel/verify-collector-config.sh"

# 所有 JVM 容器挂载同一份官方 Java Agent。部署脚本在构建和启动之前验证
# 固定版本、字节数与摘要，缺失或被替换时停止。
for selected_service in "${SELECTED[@]}"; do
  if is_in_list "$selected_service" "${ALL_SERVICES[@]}"; then
    bash "$ROOT_DIR/deploy/otel/verify-javaagent.sh"
    break
  fi
done

# MethodSpec V5 §12: 部署目标运行时镜像引用必须是 sha256 摘要引用（生产）。
# 裸标签仅在显式 AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true/1 时允许，且开发开关只放行
# 【语法合法】的裸标签/引用（round-2 R2-4）：空值、空白/控制字符、大写仓库名、
# 非法 digest 形状一律拒绝；绝不静默退回 latest。
# 摘要引用语义为「全锚定、仅小写」：必须恰好是 repo/name@sha256:<64位小写hex>，
# 不允许任何大写 hex、长度偏差或前后多余内容。校验函数来自共享脚本
# pythonSandboxService/scripts/af_digest_reference.sh（与 app/config.py、
# scripts/build_runtime_manifest.py 语义完全一致，tests/digest_reference_vectors.py
# 用同一组 accept/reject 向量固定）。
# 发布门禁（fail-closed）目标绑定（round-2 R2-3）：
#   1) 构建产物 .runtime-build/image-digest-mapping.json 必须存在且可解析；
#   2) 所选 AF_SANDBOX_IMAGE 经 docker inspect 解析出不可变 image ID；
#   3) 恰好一个映射条目与所选目标对应（条目键 == 不可变 ID，或条目记录的
#      imageRef == 所选引用）；
#   4) 该条目必须绑定同一目标：其记录的不可变 image ID（条目键）== inspect
#      解析出的 ID（无法证明同一性即拒绝）；
#   5) 条目的 base/lock/library/SBOM/MethodSpec 摘要必须全部为合法
#      非占位符 sha256 值且 releasable=true。
#   唯一豁免：显式开关 AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true/1 放宽 (5)
#   （releasable/占位符），(1)-(4) 的目标绑定永不放宽（从不隐式开启）。
if is_in_list "python-sandbox-service" "${SELECTED[@]}" || is_in_list "python-sandbox-runtime" "${SELECTED[@]}"; then
  source "$ROOT_DIR/pythonSandboxService/scripts/af_digest_reference.sh"

  DEPLOY_SANDBOX_IMAGE="${AF_SANDBOX_IMAGE:-}"
  DEPLOY_DEV_ALLOW="${AF_SANDBOX_IMAGE_ALLOW_DEV_TAG:-}"
  DEPLOY_INCOMPLETE_DEV_ALLOW="${AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD:-}"
  # 260814 scheduler-03: image verify-mode selection. local-image-id (default)
  # is the single-machine contract: the configured value must BE the local
  # Image ID (sha256:<64hex>) and docker inspect must resolve to exactly that
  # ID. strict-release keeps the Spec §12 digest/mapping/Tier2a chain.
  DEPLOY_VERIFY_MODE="${AF_SANDBOX_IMAGE_VERIFY_MODE:-local-image-id}"
  case "$DEPLOY_VERIFY_MODE" in
    local-image-id|strict-release) ;;
    *)
      echo "[deploy] ERROR: AF_SANDBOX_IMAGE_VERIFY_MODE must be local-image-id or strict-release; got '${DEPLOY_VERIFY_MODE}'." >&2
      exit 1
      ;;
  esac
  DEPLOY_TAG_CHECK="${AF_SANDBOX_IMAGE_TAG_CHECK:-}"
  # compose 自动读取 .env；进程环境未设置时回退解析 .env（与 compose 取值保持一致）
  if [[ -f "$ROOT_DIR/.env" ]]; then
    if [[ -z "$DEPLOY_SANDBOX_IMAGE" ]]; then
      DEPLOY_SANDBOX_IMAGE="$(grep -E '^AF_SANDBOX_IMAGE=' "$ROOT_DIR/.env" | tail -n 1 | cut -d= -f2- || true)"
    fi
    if [[ -z "$DEPLOY_DEV_ALLOW" ]]; then
      DEPLOY_DEV_ALLOW="$(grep -E '^AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=' "$ROOT_DIR/.env" | tail -n 1 | cut -d= -f2- || true)"
    fi
    if [[ -z "$DEPLOY_INCOMPLETE_DEV_ALLOW" ]]; then
      DEPLOY_INCOMPLETE_DEV_ALLOW="$(grep -E '^AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=' "$ROOT_DIR/.env" | tail -n 1 | cut -d= -f2- || true)"
    fi
  fi
  case "$DEPLOY_DEV_ALLOW" in
    true|TRUE|True|1) DEPLOY_DEV_ALLOW=1 ;;
    *) DEPLOY_DEV_ALLOW=0 ;;
  esac
  case "$DEPLOY_INCOMPLETE_DEV_ALLOW" in
    true|TRUE|True|1) DEPLOY_INCOMPLETE_DEV_ALLOW=1 ;;
    *) DEPLOY_INCOMPLETE_DEV_ALLOW=0 ;;
  esac
  # 引用语法门禁（round-2 R2-4）：空值总是被拒绝（即使开了开发开关）；
  # digest 引用按全锚定/仅小写语义校验；开发开关只放行语法合法的裸引用，
  # 不是无条件豁免。
  # DEPLOY_USING_BARE_DEV_REF：仅当「本次实际选用合法裸引用」时为 1。
  # AF_SANDBOX_IMAGE_ALLOW_DEV_TAG 只是权限开关，不能单独跳过 Tier2a；
  # 合法 digest 即使 permission=true 也必须跑完整 Tier2a/OCI gate。
  DEPLOY_USING_BARE_DEV_REF=0
  if [[ -z "$DEPLOY_SANDBOX_IMAGE" ]]; then
    echo "[deploy] ERROR: AF_SANDBOX_IMAGE 未设置或为空 (MethodSpec V5 §12)。" >&2
    echo "  local-image-id 模式要求 sha256:<64位小写hex> 本机 Image ID；" >&2
    echo "  strict-release 模式要求 repo/name@sha256:<64hex> 摘要引用（frog 发布时固定）。" >&2
    echo "  开发环境可显式设置 AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true 使用语法合法的裸标签（仅 strict-release）。" >&2
    exit 1
  fi
  if [[ "$DEPLOY_VERIFY_MODE" == "local-image-id" ]]; then
    # 260814 scheduler-03 local-image-id gate: the configured value must BE
    # the local Image ID and docker inspect must resolve to exactly that ID.
    # A mutable tag or a repo digest is rejected in this mode -- there is no
    # dev-allow escape and the script never downgrades itself to tag mode.
    if [[ ! "$DEPLOY_SANDBOX_IMAGE" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      echo "[deploy] ERROR: local-image-id 模式要求 AF_SANDBOX_IMAGE=sha256:<64位小写hex>（本机 Image ID，不是标签也不是仓库摘要）；got '${DEPLOY_SANDBOX_IMAGE}'。" >&2
      exit 1
    fi
    DEPLOY_INSPECTED_LOCAL_ID="$(docker inspect --type=image --format '{{.Id}}' "$DEPLOY_SANDBOX_IMAGE" 2>/dev/null || true)"
    if [[ -z "$DEPLOY_INSPECTED_LOCAL_ID" ]]; then
      echo "[deploy] ERROR: docker inspect 无法解析本机 Image ID ${DEPLOY_SANDBOX_IMAGE}（镜像缺失或 Docker socket 不可访问）。" >&2
      exit 1
    fi
    if [[ "$DEPLOY_INSPECTED_LOCAL_ID" != "sha256:${DEPLOY_SANDBOX_IMAGE#sha256:}" ]]; then
      echo "[deploy] ERROR: docker inspect 解析到不同镜像（${DEPLOY_INSPECTED_LOCAL_ID}）≠ 配置的 Image ID（${DEPLOY_SANDBOX_IMAGE}）。" >&2
      exit 1
    fi
    if [[ -n "$DEPLOY_TAG_CHECK" ]]; then
      DEPLOY_TAG_ID="$(docker inspect --type=image --format '{{.Id}}' "$DEPLOY_TAG_CHECK" 2>/dev/null || true)"
      if [[ -z "$DEPLOY_TAG_ID" ]]; then
        echo "[deploy] ERROR: AF_SANDBOX_IMAGE_TAG_CHECK '${DEPLOY_TAG_CHECK}' 无法解析。" >&2
        exit 1
      fi
      if [[ "$DEPLOY_TAG_ID" != "$DEPLOY_INSPECTED_LOCAL_ID" ]]; then
        echo "[deploy] ERROR: 标签 '${DEPLOY_TAG_CHECK}' 解析到 ${DEPLOY_TAG_ID}，与配置的 Image ID ${DEPLOY_SANDBOX_IMAGE} 不一致。" >&2
        exit 1
      fi
      echo "[deploy] local-image-id 模式：标签复核通过（${DEPLOY_TAG_CHECK} -> ${DEPLOY_INSPECTED_LOCAL_ID}）。"
    fi
    echo "[deploy] local-image-id 模式：已校验本机 Image ID ${DEPLOY_SANDBOX_IMAGE}（docker inspect 精确匹配）。"
  elif af_is_digest_reference "$DEPLOY_SANDBOX_IMAGE"; then
    : # 生产 digest 引用，合法。
  elif [[ "$DEPLOY_DEV_ALLOW" == "1" ]] && af_is_valid_dev_reference "$DEPLOY_SANDBOX_IMAGE"; then
    DEPLOY_USING_BARE_DEV_REF=1
    echo "[deploy] WARNING: AF_SANDBOX_IMAGE 是裸引用，仅因显式开关 AF_SANDBOX_IMAGE_ALLOW_DEV_TAG 而放行（开发用途，勿用于生产）。" >&2
  elif [[ "$DEPLOY_DEV_ALLOW" == "1" ]]; then
    echo "[deploy] ERROR: AF_SANDBOX_IMAGE 既不是合法的 sha256 摘要引用，也不是语法合法的裸引用 (MethodSpec V5 §12 R2-4)。" >&2
    echo "  开发开关 AF_SANDBOX_IMAGE_ALLOW_DEV_TAG 只放行语法合法的裸标签/引用；" >&2
    echo "  空值、空白/控制字符、大写仓库名或 digest 形状非法的值一律拒绝（fail-closed）。" >&2
    exit 1
  else
    echo "[deploy] ERROR: AF_SANDBOX_IMAGE 不是合法的 sha256 摘要引用 (MethodSpec V5 §12)。" >&2
    echo "  生产部署目标镜像必须恰好是 repo/name@sha256:<64位小写hex>（frog 发布时固定）；" >&2
    echo "  大写 hex、63/65 位 hex、缺失 @sha256: 或 digest 后带任何多余字符都会被拒绝。" >&2
    echo "  开发环境可显式设置 AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true 允许语法合法的裸标签。" >&2
    exit 1
  fi

fi

if [[ "$DEPLOY_ONLY" == true && "$SKIP_MAVEN" == true ]]; then
  echo "Note: --deploy-only 已包含跳过 Maven 与 Docker 构建，忽略 --skip-maven" >&2
fi

# Maven 编译（--skip-maven / --deploy-only 时跳过）
if [[ "$DEPLOY_ONLY" != true ]]; then
  if [[ "$SKIP_MAVEN" != true ]]; then
    if [[ ${#SERVICES[@]} -eq 0 ]]; then
      echo "=== Building all Java modules ==="
      mvn clean -DskipTests install
    else
      MODULES=()
      for svc in "${SELECTED[@]}"; do
        mod="$(service_module "$svc" 2>/dev/null || true)"
        if [[ -n "$mod" ]]; then
          MODULES+=("$mod")
        fi
      done
      if [[ ${#MODULES[@]} -gt 0 ]]; then
        echo "=== Building modules: ${MODULES[*]} ==="
        MODULE_LIST=$(IFS=','; echo "${MODULES[*]}")
        mvn clean -DskipTests -pl "$MODULE_LIST" -am install
      fi
    fi
  else
    echo "=== Skip-maven mode: skipping Maven compile ==="
  fi

  # Docker 构建镜像（--deploy-only 时跳过）
  echo "=== Building Docker images ==="
  for svc in "${SELECTED[@]}"; do
    build_script="$(service_build_script "$svc" 2>/dev/null || true)"
    if [[ -n "$build_script" ]]; then
      echo "Building: $svc"
      build_args="$(service_build_args "$svc")"
      if [[ -n "$build_args" ]]; then
        bash "$build_script" "$build_args"
      else
        bash "$build_script"
      fi
    fi
  done
else
  echo "=== Deploy-only mode: skipping Maven and Docker image build ==="
fi

# 镜像已经构建或拉取完成、容器尚未启动。现在逐服务读取本地 Image ID，
# 生成五个观测身份字段所需的环境变量。生产部署不接受 local/unknown；本机
# 开发若直接运行 docker compose，则仍可使用 compose 中的占位默认值。
OBSERVABILITY_SERVICES=()
for svc in "${SELECTED[@]}"; do
  if is_in_list "$svc" "${ALL_SERVICES[@]}"; then
    OBSERVABILITY_SERVICES+=("$svc")
  fi
done
if [[ ${#OBSERVABILITY_SERVICES[@]} -gt 0 ]]; then
  bash "$ROOT_DIR/deploy/otel/prepare-runtime-env.sh" "${OBSERVABILITY_SERVICES[@]}"
  set -a
  # 该文件由上一步以 0600 权限原子生成，只包含已经校验的构建身份。
  # shellcheck disable=SC1091
  source "$ROOT_DIR/deploy/otel/runtime.env"
  set +a
  AF_OTEL_LOG_ROOT_DIR="$ROOT_DIR/data/logs" \
    bash "$ROOT_DIR/deploy/otel/prepare-log-directories.sh" "${OBSERVABILITY_SERVICES[@]}"
fi
# === post-build / pre-deploy 唯一执行区间 (v14 MF2 frozen) ===
if is_in_list "python-sandbox-service" "${SELECTED[@]}" || is_in_list "python-sandbox-runtime" "${SELECTED[@]}"; then

  # BEGIN_D15_MAPPING_VERIFIER (v14: prod+dev-tag 公共路径, v14 MF1 HARD gate 在前)
  # 260814 scheduler-03: the mapping/Tier2a chain is strict-release evidence
  # only. In local-image-id mode the deploy gate above (docker inspect exact
  # match on the bare Image ID) already ran and this whole block is skipped.
  if [[ "$DEPLOY_VERIFY_MODE" == "strict-release" ]]; then
  DEPLOY_MAPPING_FILE="$ROOT_DIR/pythonSandboxService/.runtime-build/image-digest-mapping.json"
  DEPLOY_IIDFILE="$ROOT_DIR/pythonSandboxService/.runtime-build/image-id"
  DEPLOY_LIBSET_FILE="$ROOT_DIR/pythonSandboxService/.runtime-build/library-set.json"

  [ -f "$DEPLOY_MAPPING_FILE" ] || {
    echo "[deploy] ERROR: 构建产物映射文件缺失 (image-digest-mapping.json)" >&2
    exit 1
  }
  [ -s "$DEPLOY_IIDFILE" ] || { echo "[deploy] ERROR: iidfile 缺失" >&2; exit 1; }
  DEPLOY_INSPECTED_ID="$(docker inspect --type=image --format '{{.Id}}' "$DEPLOY_SANDBOX_IMAGE" 2>/dev/null || true)"
  [ -n "$DEPLOY_INSPECTED_ID" ] || { echo "[deploy] ERROR: docker inspect failed" >&2; exit 1; }
  DEPLOY_LIBSET_DIGEST="$(python3 -c \
    'import json,sys,re; d=json.load(open(sys.argv[1],encoding="utf-8"))["librarySetDigest"]; \
     assert isinstance(d,str) and re.fullmatch(r"^sha256:[0-9a-f]{64}$",d), d; print(d)' "$DEPLOY_LIBSET_FILE")" || {
    echo "[deploy] ERROR: library-set.json missing/malformed" >&2; exit 1
  }

  DEPLOY_HARD_VERDICT="$(python3 "$ROOT_DIR/pythonSandboxService/scripts/d15_release_verify.py" \
    verify-hard-target-binding \
    --mapping "$DEPLOY_MAPPING_FILE" --inspected-id "$DEPLOY_INSPECTED_ID" \
    --library-set-digest "$DEPLOY_LIBSET_DIGEST" --iidfile "$DEPLOY_IIDFILE")"
  [ "$DEPLOY_HARD_VERDICT" = "ok" ] || {
    echo "[deploy] ERROR: HARD target binding = $DEPLOY_HARD_VERDICT (R2-3 永不放宽; inspected=$DEPLOY_INSPECTED_ID)" >&2
    exit 1
  }

  DEPLOY_GATE_VERDICT="$(python3 "$ROOT_DIR/pythonSandboxService/scripts/d15_release_verify.py" \
    verify-mapping --mapping "$DEPLOY_MAPPING_FILE" \
    --chosen-ref "$DEPLOY_SANDBOX_IMAGE" --inspected-id "$DEPLOY_INSPECTED_ID")"
  case "$DEPLOY_GATE_VERDICT" in
    ok) ;;
    not-releasable)
      if [[ "$DEPLOY_INCOMPLETE_DEV_ALLOW" == "1" ]]; then
        echo "[deploy] WARNING: mapping=not-releasable + AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD bypass"
      else
        echo "[deploy] ERROR: mapping=$DEPLOY_GATE_VERDICT (releasable)" >&2; exit 1
      fi
      ;;
    target-mismatch)
      echo "[deploy] ERROR: mapping=$DEPLOY_GATE_VERDICT (R2-3; inspected=$DEPLOY_INSPECTED_ID)" >&2
      exit 1
      ;;
    *) echo "[deploy] ERROR: mapping=$DEPLOY_GATE_VERDICT" >&2; exit 1 ;;
  esac
  # END_D15_MAPPING_VERIFIER

  # Tier2a 仅在「本次实际使用合法裸 dev ref」时可跳过；digest 发布路径始终执行。
  if [[ "$DEPLOY_USING_BARE_DEV_REF" == "1" ]]; then
    # BEGIN_D15_DEV_BYPASS
    echo "[deploy] dev bypass active: bare AF_SANDBOX_IMAGE + AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true, skipping Tier2a gate (dev only)"
    # END_D15_DEV_BYPASS
  else
    # BEGIN_D15_TIER2A_GATE
    AF_SANDBOX_IMAGE="$DEPLOY_SANDBOX_IMAGE" \
    AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="$( (( DEPLOY_INCOMPLETE_DEV_ALLOW == 1 )) && echo true || echo '' )" \
      bash "$ROOT_DIR/pythonSandboxService/scripts/d15_tier2a_gate.sh" || {
      echo "[deploy] ERROR: D15 Tier2a gate failed" >&2; exit 1
    }
    # END_D15_TIER2A_GATE
  fi
  fi  # strict-release only (260814 scheduler-03)
fi


# 检查 Docker Compose 命令
if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
  else
    DOCKER_COMPOSE="docker-compose"
  fi
else
  echo "docker not found in PATH" >&2
  exit 1
fi

wait_for_compose_service() {
  local svc="$1"
  local timeout_seconds="${2:-180}"
  local deadline=$((SECONDS + timeout_seconds))
  local cid status running

  while (( SECONDS < deadline )); do
    cid="$($DOCKER_COMPOSE ps -q "$svc" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      running="$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null || true)"
      if [[ "$status" == "healthy" ]] || [[ "$status" == "running" && "$running" == "true" ]]; then
        echo "Service $svc is ready ($status)"
        return 0
      fi
    fi
    sleep 2
  done

  echo "Service $svc did not become ready within ${timeout_seconds}s" >&2
  $DOCKER_COMPOSE ps "$svc" >&2 || true
  return 1
}

# 步骤1: 启动基础设施服务
# 如果使用了 --with-infra 或指定了基础设施服务，则重建它们
if [[ "$WITH_INFRA" == true ]] || [[ "$WITH_ALL" == true ]]; then
  echo "=== Starting infrastructure services (with recreate) ==="
  $DOCKER_COMPOSE up -d --force-recreate "${INFRA_SERVICES[@]}"
else
  echo "=== Ensuring infrastructure services are running ==="
  $DOCKER_COMPOSE up -d --no-recreate "${INFRA_SERVICES[@]}" 2>/dev/null || true
fi
echo "=== Waiting for infrastructure services to become healthy ==="
for svc in "${INFRA_SERVICES[@]}"; do
  wait_for_compose_service "$svc"
done

# 步骤2: 启动选定的业务服务（重建）
# 先过滤出需要重建的业务服务（排除基础设施）
BUSINESS_TO_RECREATE=()
for svc in "${SELECTED[@]}"; do
  # 检查是否是 compose 服务；python-sandbox-runtime 只是 build-only 镜像
  if service_has_compose_container "$svc"; then
    BUSINESS_TO_RECREATE+=("$svc")
  fi
done

if [[ ${#BUSINESS_TO_RECREATE[@]} -gt 0 ]]; then
  echo "=== Recreating business services: ${BUSINESS_TO_RECREATE[*]} ==="
  # 使用 --no-deps 避免连锁重建依赖服务
  # 因为步骤1已经确保了基础设施在运行
  $DOCKER_COMPOSE up -d --force-recreate --no-deps "${BUSINESS_TO_RECREATE[@]}"
fi

echo "=== Deployment completed ==="

# MethodSpec V5 §12: 运行时镜像保留。默认 PLAN 模式（只打印计划，绝不删除）。
# 实际删除由 frog 显式执行：bash pythonSandboxService/scripts/prune_runtime_images.sh --apply
if is_in_list "python-sandbox-service" "${SELECTED[@]}" || is_in_list "python-sandbox-runtime" "${SELECTED[@]}"; then
  echo "=== Runtime image retention plan (MethodSpec V5 §12, PLAN mode; deletion requires explicit --apply by frog) ==="
  AF_CURRENT_RUNTIME_IMAGE="${AF_CURRENT_RUNTIME_IMAGE:-}" \
  AF_PREVIOUS_RUNTIME_IMAGE="${AF_PREVIOUS_RUNTIME_IMAGE:-}" \
  AF_STATE_FILE="${AF_STATE_FILE:-$ROOT_DIR/data/sandbox_tasks/state.json}" \
    bash "$ROOT_DIR/pythonSandboxService/scripts/prune_runtime_images.sh"
fi

# 显示状态
$DOCKER_COMPOSE ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || true
