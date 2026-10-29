# ============================================================================
# HelloAI - Multi-stage Dockerfile (backend jar + frontend dist)
#
# Produces TWO images:
#   helloai-app   (stage 3): Spring Boot jar on eclipse-temurin:17-jre
#   helloai-web   (stage 4): Vue3 dist + nginx config on nginx:1.27-alpine
#
# Usage:
#   A) End-user quickstart -- PREBUILT images, nothing is built on the
#      target host. CI publishes both images and deploy/quickstart/ consumes
#      them with one command (no source tree, no JDK/Maven/Node):
#        see deploy/quickstart/README.md
#   B) Self-host from source (dev / internal):
#        docker compose -f docker-compose.server.yml up -d --build
#
# Notes:
#   - Build arg MAVEN_OPTS can override Maven memory flags (see backend-build stage).
#   - The frontend build runs `vue-tsc -b && vite build` (package.json "build"),
#     so TypeScript errors fail the image build (same bar as local CI).
#   - nginx.server.conf is copied into the web image (replaces the compose
#     bind-mount of ./nginx.conf), so a user without the repo file can still start.
#   - ASCII-only file (see repo convention for deploy artifacts).
# ============================================================================

# ---------- Stage 1: backend jar (Maven multi-module) ----------
# The root pom builds modules in dependency order; `-am` is unnecessary when
# running from the repo root (aggregator builds all <modules>).
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace

# Copy only the pom files first to leverage Docker layer caching of dependencies.
# NOTE: `docker compose build` context is the repo root, so all module poms are
# reachable; `.dockerignore` excludes target/ and node_modules/ (see .dockerignore).
COPY pom.xml .
COPY helloai-common/pom.xml helloai-common/
COPY helloai-mq/pom.xml helloai-mq/
COPY helloai-job/pom.xml helloai-job/
COPY helloai-core/pom.xml helloai-core/
COPY helloai-api/pom.xml helloai-api/
COPY helloai-start/pom.xml helloai-start/

# Resolve dependencies (this layer is cached until any pom changes).
RUN mvn -B -q dependency:go-offline \
      -Dmaven.repo.local=/root/.m2/repository \
      || true

# Copy sources and build the runnable jar (skip tests for speed; CI runs tests).
COPY helloai-common helloai-common/
COPY helloai-mq helloai-mq/
COPY helloai-job helloai-job/
COPY helloai-core helloai-core/
COPY helloai-api helloai-api/
COPY helloai-start helloai-start/

# SECURITY NOTE (2026-10-19, revised 2026-10-29) -- WHO SUPPLIES THE PROFILE.
#   This image ships in TWO shapes, and the rule below applies to both:
#     A) prebuilt release image for deploy/quickstart (target host has no source);
#     B) built from source on the server via docker-compose.server.yml.
#   NEITHER may carry the working tree's application-dev.yml: that file is
#   skip-worktree and holds REAL credentials (cloud IP, MQ/MinIO passwords,
#   AES key). Guard 1: .dockerignore excludes **/application-dev.yml, so the
#   COPY lines above never see it. Guard 2: this file deliberately contains
#   NO `COPY ... application-dev.yml` line -- do not add one back.
#   CONSEQUENCE: the jar has NO dev profile, so every placeholder that
#   application.yml declares without a default MUST be injected at runtime.
#   - quickstart path: application-quickstart.yml (topology baked in, secrets
#     injected by deploy/quickstart/start.sh)
#   - server path:     application.yml + the deploy dir's .env

# ARG defaults mirror the local CI memory guidance (4C8GB host).
ARG MAVEN_OPTS="-Xmx1024m -XX:+UseSerialGC"
ENV MAVEN_OPTS=${MAVEN_OPTS}
RUN mvn -B -q package -DskipTests -Dmaven.repo.local=/root/.m2/repository \
      -pl helloai-start -am

# ---------- Stage 2: frontend dist ----------
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY helloai-ui/package.json helloai-ui/package-lock.json ./
RUN npm ci
COPY helloai-ui/ ./
RUN npm run build   # vue-tsc -b && vite build -> dist/

# ---------- Stage 3: app image (Spring Boot jar) ----------
# Base pinned to the Ubuntu 24.04 (noble) variant ON PURPOSE: the floating
# `17-jre` tag now resolves to Ubuntu 26.04 ("resolute"), whose default repos no
# longer carry postgresql-client-16 (only 18). See the apt line below for why 16.
FROM eclipse-temurin:17-jre-noble AS app
# Container locale pinned to C.UTF-8 + UTF-8 file.encoding (same as compose env,
# so behaviour is identical whether the jar is baked in or bind-mounted).
# TZ + -Duser.timezone pin the JVM business timezone to Asia/Shanghai: the compose
# file sets the same pair, and CI/tests are pinned to it too (root pom surefire
# <user.timezone>), so "local green / server differs by 8 hours" cannot happen.
# -Duser.timezone rather than TZ alone, so a base image without tzdata cannot
# silently fall back to UTC.
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
WORKDIR /app
# postgresql-client-16 provides the pg_dump / pg_restore that the platform backup
# capability (REF-2.3) shells out to (helloai.backup.pg-dump-path left empty =>
# resolved from PATH). Major 16 matches the server used by docker-compose
# (postgres:16.4) and RestoreGate gate 1 requires dump major == runtime major.
# Without this the backup endpoint fails explicitly ("cannot start pg_dump");
# the capability is unavailable rather than silently degraded.
#
# curl is required by the container HEALTHCHECK below (GET /actuator/health).
# deploy/quickstart gates `web` on this check, so removing it silently turns
# every startup into a 380s timeout.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-16 curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=backend-build /workspace/helloai-start/target/helloai-start-1.0.0-SNAPSHOT.jar helloai-start.jar
# Runs as root (same as the legacy bind-mount mode) so the bind-mounted
# host `./logs` directory remains writable without host-side chown.
EXPOSE 6565
# Actuator health includes the DB / Redis / RabbitMQ contributors, so a green
# check means the dependencies are actually usable, not merely that the port
# is open. deploy/quickstart uses exactly this signal to gate `web`.
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=20 \
  CMD curl -fsS http://127.0.0.1:6565/actuator/health || exit 1
ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-jar", "helloai-start.jar"]

# ---------- Stage 4: web image (frontend + nginx) ----------
FROM nginx:1.27-alpine AS web
COPY --from=frontend-build /app/dist /usr/share/nginx/html
# nginx.server.conf is the production reverse-proxy config (80 + commented 443);
# copy it in as default.conf so no external bind-mount is required.
COPY nginx.server.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
