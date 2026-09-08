#!/bin/sh
# external-info-service 同时连接 alphafrog-network 和 shared-infra。
# Dubbo provider 必须注册到 frontend/nacos 所在的 alphafrog-network，而不是
# shared-infra；否则 Nacos 里会出现 frontend 无法访问的 provider IP。
# 这里用到 Nacos 的路由源地址来选网卡，比“取第一个 172.* 地址”稳定。
NACOS_HOST="${NACOS_ADDRESS:-nacos}"
NACOS_IP=$(getent hosts "$NACOS_HOST" | awk '{print $1; exit}')

if [ -n "$NACOS_IP" ]; then
    DUBBO_IP=$(ip route get "$NACOS_IP" 2>/dev/null | awk '{for (i=1; i<=NF; i++) if ($i == "src") {print $(i+1); exit}}')
fi

if [ -z "$DUBBO_IP" ]; then
    DUBBO_IP=$(ip -4 addr show | grep "inet 172.18." | head -1 | awk '{print $2}' | cut -d/ -f1)
fi

if [ -z "$DUBBO_IP" ]; then
    echo "[alphafrog] ERROR: Could not detect IP!"
    exit 1
fi

echo "[alphafrog] Detected DUBBO_IP: $DUBBO_IP"

# Triple 协议需要使用 TRI_DUBBO_IP_TO_BIND 环境变量。
# 绑定地址始终用自动检测的容器 IP；注册地址若已被外部预设（跨机订阅场景，
# 由 docker-compose.beta-fallback.yml 注入宿主机可路由地址），则不再覆盖。
export TRI_DUBBO_IP_TO_BIND="$DUBBO_IP"
export TRI_DUBBO_IP_TO_REGISTRY="${TRI_DUBBO_IP_TO_REGISTRY:-$DUBBO_IP}"

exec java -jar /app/app.jar
