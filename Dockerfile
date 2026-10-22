# Multi-stage Dockerfile for single-JAR Spring Modulith container
# Stage 1: Build application with Maven Wrapper
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy Maven wrapper and POM definitions
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY modulith-strangler-starter/pom.xml modulith-strangler-starter/
COPY grid-settlement-modulith-demo/pom.xml grid-settlement-modulith-demo/

# Copy module source trees
COPY modulith-strangler-starter/src modulith-strangler-starter/src
COPY grid-settlement-modulith-demo/src grid-settlement-modulith-demo/src

# Package executable JAR
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Minimal JRE runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root service user
RUN addgroup -S strangler && adduser -S strangler -G strangler
USER strangler:strangler

# Copy packaged demo application JAR
COPY --from=build --chown=strangler:strangler /workspace/grid-settlement-modulith-demo/target/grid-settlement-modulith-demo-*.jar /app/app.jar

EXPOSE 8080

# ENTRYPOINT passes CLI flags such as --modulith.bridge.host-only=<module> directly to Spring Boot
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
