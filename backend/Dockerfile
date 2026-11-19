# syntax=docker/dockerfile:1
# Build context is the repository root (see docker-compose.yml): the image bundles the React UI
# and the Spring Boot app, so one container serves the page, the REST API and the WebSocket.

# ---- 1. build the React UI ---------------------------------------------------------------------
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
# npm ci installs exactly what package-lock.json pins (npm install could pick newer versions).
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

# ---- 2. build the Spring Boot jar, with the UI inside it ---------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Dependencies first, in their own layer: only re-downloaded when pom.xml changes.
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
# Spring Boot serves anything under classpath:/static, so the built UI goes there.
COPY --from=ui /ui/dist ./src/main/resources/static
# Unit tests run here, so a broken commit can't produce an image. (Database integration tests are
# skipped because TEST_DATABASE_URL isn't set; see README "Tests".)
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# ---- 3. runtime --------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app
# Never run as root: a bug in request handling must not hand out root in the container.
RUN useradd --system --no-create-home --uid 10001 chat
COPY --from=build /src/target/chat-backend.jar ./app.jar
COPY backend/healthcheck.sh ./healthcheck.sh
USER 10001
EXPOSE 8080
# Exec form so java is PID 1 and receives SIGTERM from `docker stop` directly (a shell wrapper would
# swallow it and graceful shutdown would never run).
# MaxRAMPercentage: size the heap from the container's memory limit, not the host's RAM.
# ExitOnOutOfMemoryError: crash and let Docker restart it rather than limp on half-broken.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
