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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
