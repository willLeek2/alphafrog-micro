FROM eclipse-temurin:17-jdk-noble

# 构建时由 docker_build.sh 传入 http(s)_proxy（macOS 为 host.docker.internal:7890）。
# Temurin noble 底镜像已预装 curl 和 ca-certificates（Adoptium 官方 Dockerfile），
# 不再用 apt-get 安装，也避开已 EOL 的 Debian 11 安全源。
ARG http_proxy
ARG https_proxy

# agentLangchainService 继承父 POM（1.0.0-SNAPSHOT），Jar 名为 agentLangchainService-1.0.0-SNAPSHOT.jar
COPY target/agentLangchainService-1.0.0-SNAPSHOT.jar /app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
