# ============================================================================
# HelloAI - Multi-stage Dockerfile (backend jar + frontend dist)
#
# Produces TWO images:
#   helloai-app   (stage 3): Spring Boot jar on eclipse-temurin:17-jre
#   helloai-web   (stage 4): Vue3 dist + nginx config on nginx:1.27-alpine
#
# Usage (single-command one-click deployment, see README):
#   docker compose -f docker-compose.server.yml up -d --build
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

# SECURITY: overwrite the working-tree application-dev.yml with the PUBLIC
# placeholder template. The local working tree carries a skip-worktree
# application-dev.yml with REAL credentials (cloud IP, RabbitMQ password);
# baking that into the image would leak production secrets. The template's
# defaults point at localhost and are fully overridden at runtime by the
# compose SPRING_* / RABBITMQ_* / MINIO_* env vars, so behaviour is unchanged.
COPY deploy/templates/application-dev.yml helloai-start/src/main/resources/application-dev.yml

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
FROM eclipse-temurin:17-jre AS app
# Container locale pinned to C.UTF-8 + UTF-8 file.encoding (same as compose env,
# so behaviour is identical whether the jar is baked in or bind-mounted).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
WORKDIR /app
COPY --from=backend-build /workspace/helloai-start/target/helloai-start-1.0.0-SNAPSHOT.jar helloai-start.jar
# Runs as root (same as the legacy bind-mount mode) so the bind-mounted
# host `./logs` directory remains writable without host-side chown.
EXPOSE 6565
ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-jar", "helloai-start.jar"]

# ---------- Stage 4: web image (frontend + nginx) ----------
FROM nginx:1.27-alpine AS web
COPY --from=frontend-build /app/dist /usr/share/nginx/html
# nginx.server.conf is the production reverse-proxy config (80 + commented 443);
# copy it in as default.conf so no external bind-mount is required.
COPY nginx.server.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
