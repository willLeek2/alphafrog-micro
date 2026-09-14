FROM eclipse-temurin:17-jdk-noble
WORKDIR /app

# 只补 entrypoint.sh 用的 ip 命令（iproute2）；curl / ca-certificates Temurin
# noble 底镜像已预装。从 Amazon Linux yum 换到 Ubuntu apt，与其余 Java 服务同一套底。
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

COPY target/externalInfoService-1.0-SNAPSHOT.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
