#!/usr/bin/env bash
# ============================================================
# HelloAI - RabbitMQ admin bootstrap (run BEFORE `docker compose up -d`)
# Automates the 3 manual commands from the README Quick Start:
#   docker compose up -d rabbitmq
#   docker exec helloai-rabbitmq rabbitmqctl add_user helloaiadmin '<pw>'
#   docker exec helloai-rabbitmq rabbitmqctl set_user_tags helloaiadmin administrator
#
# Reads RABBITMQ_ADMIN_USER / RABBITMQ_ADMIN_PASSWORD from the .env file that
# deploy/init-env.sh generated (same directory by default) - so you never type
# or copy-paste the password by hand.
#
# Idempotent: safe to re-run. If the admin already exists it keeps the existing
# account (never resets its password), re-asserts the administrator tag, and
# verifies the result. If you need to ROTATE the password, delete the account
# first or change it via rabbitmqctl directly.
#
# Usage:
#   bash deploy/init-rabbitmq-admin.sh                 # .env in current dir
#   bash deploy/init-rabbitmq-admin.sh /home/admin/helloai   # explicit deploy dir
#   RABBITMQ_ADMIN_USER=foo RABBITMQ_ADMIN_PASSWORD=bar bash deploy/init-rabbitmq-admin.sh
#
# Requirements: docker compose project reachable from the current dir (or the
#   given dir); the compose file must define the `rabbitmq` service with
#   container_name helloai-rabbitmq.
#
# NOTE: ASCII-only file (repo convention for deploy artifacts).
# ============================================================
set -euo pipefail

TARGET_DIR=""
for a in "$@"; do
  TARGET_DIR="$a"
done
if [ -z "$TARGET_DIR" ]; then
  TARGET_DIR="$(pwd)"
fi
cd "$TARGET_DIR"

CONTAINER="helloai-rabbitmq"
COMPOSE="docker compose"
ADMIN_USER=""
ADMIN_PW=""

# --- 1. Load credentials from .env (if present), else from environment ---------
if [ -f .env ]; then
  echo "[init-rabbitmq-admin] reading credentials from ${TARGET_DIR}/.env"
  set +u
  # shellcheck disable=SC1090
  . ./.env
  set -u
fi
ADMIN_USER="${RABBITMQ_ADMIN_USER:-${ADMIN_USER}}"
ADMIN_PW="${RABBITMQ_ADMIN_PASSWORD:-${ADMIN_PW}}"

if [ -z "$ADMIN_USER" ] || [ -z "$ADMIN_PW" ]; then
  echo "[init-rabbitmq-admin] ERROR: RABBITMQ_ADMIN_USER / RABBITMQ_ADMIN_PASSWORD are not set." >&2
  echo "[init-rabbitmq-admin]   Run 'bash deploy/init-env.sh' first to generate .env, or export both variables." >&2
  exit 1
fi

# --- 2. Bring up only the broker ------------------------------------------------
echo "[init-rabbitmq-admin] starting RabbitMQ broker (docker compose up -d rabbitmq)..."
if ! $COMPOSE up -d rabbitmq; then
  echo "[init-rabbitmq-admin] ERROR: 'docker compose up -d rabbitmq' failed." >&2
  echo "[init-rabbitmq-admin]   Is this the deploy dir containing docker-compose.server.yml? Does Docker have permission?" >&2
  exit 1
fi

# --- 3. Wait until the broker's management/CLI is ready --------------------------
echo "[init-rabbitmq-admin] waiting for broker to become ready..."
MAX_ATTEMPTS=60
INTERVAL=3
attempt=0
while :; do
  if docker exec "$CONTAINER" rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
    echo "[init-rabbitmq-admin] broker is up (after ~$((attempt * INTERVAL))s)"
    break
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "[init-rabbitmq-admin] ERROR: broker did not become ready within $((MAX_ATTEMPTS * INTERVAL))s." >&2
    echo "[init-rabbitmq-admin]   Check: docker compose ps; docker compose logs rabbitmq" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done

# --- 4. Create the admin (idempotent) --------------------------------------------
# `add_user` on an existing account returns an error ("user already exists").
# That is fine on re-runs: we keep the existing password and just re-assert the tag.
# To detect "already exists" vs other failures we still fail loudly on the
# unknown cases by re-verifying afterwards (step 5) instead of blanket `|| true`.
echo "[init-rabbitmq-admin] creating admin user '${ADMIN_USER}' (idempotent)..."
if docker exec "$CONTAINER" rabbitmqctl add_user "$ADMIN_USER" "$ADMIN_PW"; then
  echo "[init-rabbitmq-admin]   user created (first run)"
else
  add_rc=$?
  if docker exec "$CONTAINER" rabbitmqctl list_users 2>/dev/null | grep -q "^${ADMIN_USER}[[:space:]]"; then
    echo "[init-rabbitmq-admin]   user already exists, keeping existing password"
  else
    echo "[init-rabbitmq-admin] ERROR: 'rabbitmqctl add_user' failed (rc=$add_rc) and the user is not listed." >&2
    echo "[init-rabbitmq-admin]   Check the password for characters that the shell/CLI may interpret." >&2
    exit 1
  fi
fi

echo "[init-rabbitmq-admin] setting administrator tag on '${ADMIN_USER}'..."
if ! docker exec "$CONTAINER" rabbitmqctl set_user_tags "$ADMIN_USER" administrator; then
  echo "[init-rabbitmq-admin] ERROR: 'rabbitmqctl set_user_tags' failed." >&2
  exit 1
fi

# --- 5. Verify --------------------------------------------------------------------
echo "[init-rabbitmq-admin] verifying..."
if ! docker exec "$CONTAINER" rabbitmqctl list_users 2>/dev/null | grep -q "^${ADMIN_USER}[[:space:]]"; then
  echo "[init-rabbitmq-admin] ERROR: verification failed - user '${ADMIN_USER}' not found in list_users." >&2
  exit 1
fi
echo "[init-rabbitmq-admin] DONE: admin '${ADMIN_USER}' ready with tag 'administrator'."
echo "[init-rabbitmq-admin] Next: docker compose up -d --build"
