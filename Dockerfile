# syntax=docker/dockerfile:1

# ---- build ---------------------------------------------------------------------------------------
FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# The Maven repository is a build cache, not part of any layer. Tests need Docker and run in CI, not here.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -Dmaven.test.skip=true

# ---- run -----------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home bidtix
WORKDIR /app
COPY --from=build /build/target/bidtix-backend-*.jar app.jar
USER bidtix
EXPOSE 8080
# The heap follows the container's memory limit. Configuration (database, Redis, JWT keys, VNPAY) comes from the environment.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
