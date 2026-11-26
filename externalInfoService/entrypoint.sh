#!/bin/sh
# external-info-service 同时连接 alphafrog-network 和 shared-infra。
# Dubbo provider 必须注册到 frontend/nacos 所在的 alphafrog-network，而不是
# shared-infra；否则 Nacos 里会出现 frontend 无法访问的 provider IP。
# 这里用到 Nacos 的路由源地址来选网卡，比“取第一个 172.* 地址”稳定。
NACOS_HOST="${NACOS_ADDRESS:-nacos}"
NACOS_IP=$(getent ahostsv4 "$NACOS_HOST" | awk '{print $1; exit}')

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

# TRI_DUBBO_IP_TO_BIND 指定 Triple 绑定地址，缺省或空值时使用自动检测的容器 IP。
# none 表示取消该绑定覆盖，由 Java 协议配置决定监听地址。
# 注册地址优先采用 DUBBO_IP_TO_REGISTRY 或 TRI_DUBBO_IP_TO_REGISTRY，
# 两者均缺失时使用容器 IP；绑定地址与注册地址分别处理。
case "${TRI_DUBBO_IP_TO_BIND:-}" in
    none)
        unset TRI_DUBBO_IP_TO_BIND
        ;;
    "")
        export TRI_DUBBO_IP_TO_BIND="$DUBBO_IP"
        ;;
esac
if [ -z "${DUBBO_IP_TO_REGISTRY:-}" ] && [ -z "${TRI_DUBBO_IP_TO_REGISTRY:-}" ]; then
    export TRI_DUBBO_IP_TO_REGISTRY="$DUBBO_IP"
fi

exec java -jar /app/app.jar
