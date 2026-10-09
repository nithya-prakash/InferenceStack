# Build context: gateway/.   docker build -t is-gateway gateway/
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --no-create-home gateway
COPY --from=build /app/target/gateway.jar /app/gateway.jar
USER gateway
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 CMD curl -fs http://localhost:8080/health/live || exit 1
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
