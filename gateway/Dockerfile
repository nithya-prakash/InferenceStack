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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
