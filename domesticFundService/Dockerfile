FROM eclipse-temurin:17-jdk-noble
COPY target/domesticFundService-1.0-SNAPSHOT.jar /app.jar
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar"]