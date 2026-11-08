# syntax=docker/dockerfile:1
# Cards: one Spring Boot jar that serves the static frontend at / and the API at /api.
# Build context = REPOSITORY ROOT (the bundle profile copies ../frontend into the jar):
#   docker build -t cards .
#   docker run --rm -p 8080:8080 -e APP_ENV=prod -e DATABASE_URL=... -e GOOGLE_CLIENT_ID=... -e FRONTEND_ORIGIN=http://localhost:8080 cards

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build/app/backend

# Dependencies first, so this layer is cached until pom.xml changes.
COPY app/backend/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY app/backend/src ./src
COPY app/frontend ../frontend
# Tests are run in CI / locally, not on every image build.
RUN mvn -B -q -Pbundle-frontend package -DskipTests \
 && cp target/cards-backend-*.jar /build/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S cards && adduser -S -G cards cards
WORKDIR /app
COPY --from=build --chown=cards:cards /build/app.jar app.jar
USER cards

# Sized for a 512 MB instance: heap capped at ~60% of the container limit, the rest is for metaspace,
# thread stacks and the JVM itself. SerialGC + C1-only keep memory and startup low (this is a small game server).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=128m -Xss512k -XX:+ExitOnOutOfMemoryError" \
    APP_ENV=prod \
    PORT=8080

EXPOSE 8080
# Generous start period: JVM start + Liquibase + a possibly-suspended Neon database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
