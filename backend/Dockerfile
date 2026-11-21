# syntax=docker/dockerfile:1
# Built from the repository root. One image contains both the frontend and the backend.

# 1. Build the frontend
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

# 2. Build the backend jar, with the frontend inside it
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Download dependencies first, so they are cached.
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
# Spring Boot serves the files in static/.
COPY --from=ui /ui/dist ./src/main/resources/static
# Unit tests run here (database tests are skipped).
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# 3. Runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app
# Don't run as root.
RUN useradd --system --no-create-home --uid 10001 chat
COPY --from=build /src/target/chat-backend.jar ./app.jar
COPY backend/healthcheck.sh ./healthcheck.sh
USER 10001
EXPOSE 8080
# Heap sized from the container's memory. On out-of-memory, exit so Docker restarts it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]