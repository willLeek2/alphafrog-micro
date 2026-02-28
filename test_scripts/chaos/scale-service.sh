#!/bin/bash
# 扩容脚本 - Docker Compose环境下模拟水平扩展
#
# 用法:
#   ./scale-service.sh <服务名> <副本数>
# 示例:
#   ./scale-service.sh domestic-index-service 3

set -euo pipefail

SERVICE="${1:?用法: $0 <服务名> <副本数>}"
REPLICAS="${2:?用法: $0 <服务名> <副本数>}"

echo "正在将 ${SERVICE} 扩展到 ${REPLICAS} 个副本..."

docker-compose up -d --scale "${SERVICE}=${REPLICAS}" --no-recreate

echo "等待服务注册到Nacos..."
sleep 10

echo "检查Nacos服务注册列表..."
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=${SERVICE}" | python3 -m json.tool 2>/dev/null || echo "(Nacos查询失败或json格式化不可用)"

echo "扩容完成: ${SERVICE} x ${REPLICAS}"
