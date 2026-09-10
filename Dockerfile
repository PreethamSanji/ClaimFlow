# syntax=docker/dockerfile:1

# ---- Stage 1: build the jar with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy only the pom first, so the dependency layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI. Skip them here to keep image builds fast.
RUN mvn -B -q package -DskipTests

# ---- Stage 2: small runtime image (JRE only, no Maven, no source) ----
FROM eclipse-temurin:21-jre-alpine

# Run as a non-root user (UID 10001 matches the Helm chart's securityContext).
RUN addgroup -S -g 10001 claimflow && adduser -S -u 10001 -G claimflow claimflow

WORKDIR /app
COPY --from=build /workspace/target/claimflow.jar app.jar

USER 10001
EXPOSE 8080

# Heap = 75% of the container memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
