# syntax=docker/dockerfile:1

# ---- build: compile and package the jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# The cache mount keeps downloaded dependencies between builds without baking them into a layer.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---- run: JRE only, as an unprivileged user ----
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
# Management port: health checks and metrics only.
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
