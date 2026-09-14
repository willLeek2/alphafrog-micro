FROM eclipse-temurin:17-jdk-noble
COPY target/domesticFetchService-1.0-SNAPSHOT-exec.jar /app.jar
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]