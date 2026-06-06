FROM amazoncorretto:17
WORKDIR /app

# 安装网络工具（用于获取 IP 地址）和 curl（用于容器内出网/代理诊断）
RUN yum install -y iproute curl ca-certificates && yum clean all

COPY target/externalInfoService-1.0-SNAPSHOT.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
