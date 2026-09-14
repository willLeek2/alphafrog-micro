#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

mvn -f "$ROOT_DIR/pom.xml" -pl pythonSandboxGatewayService -am clean package -DskipTests
# IMAGE_TAG 或第一个参数可覆盖产物标签，未设置时保持 :latest（生产现有调用不变）。
IMAGE_TAG="${1:-${IMAGE_TAG:-alphafrog-micro-python-sandbox-gateway-service:latest}}"
docker build -t "$IMAGE_TAG" "$SCRIPT_DIR"
