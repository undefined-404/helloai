#!/usr/bin/env bash
# ============================================================
# HelloAI - one-shot .env bootstrap for server deployment
# Generates the 5 variables required by docker-compose.server.yml
# into a .env file in the current directory (idempotent / safe to re-run).
#
# Generated values:
#   RABBITMQ_ADMIN_USER=helloaiadmin          (fixed)
#   RABBITMQ_ADMIN_PASSWORD=<random hex 16B>  (manual pre-step: must be created on the broker)
#   HELLOAI_RABBIT_USER=helloai               (fixed)
#   HELLOAI_RABBIT_PASSWORD=<random hex 16B>
#   HELLOAI_CREDENTIAL_AES_KEY_BASE64=<random base64 32B>
#
# IMPORTANT - AES key is NOT regenerated on re-run (it must match the ciphertext
#   already in the database). If .env already has HELLOAI_CREDENTIAL_AES_KEY_BASE64,
#   that existing value is kept. Only variables that are MISSING are appended.
#   To rotate deliberately: delete the line(s) from .env, then re-run.
#
# Usage:
#   bash deploy/init-env.sh            # from the repo/deploy dir (writes ./env to CWD)
#   bash deploy/init-env.sh /path      # explicit output directory
#   bash deploy/init-env.sh --print    # print values, do NOT write a file
#
# After running, do the RabbitMQ admin pre-step (README Quick Start):
#   docker compose up -d rabbitmq
#   docker exec helloai-rabbitmq rabbitmqctl add_user helloaiadmin '<RABBITMQ_ADMIN_PASSWORD>'
#   docker exec helloai-rabbitmq rabbitmqctl set_user_tags helloaiadmin administrator
#   docker compose up -d --build
#
# NOTE: ASCII-only file (repo convention for deploy artifacts).
# ============================================================
set -euo pipefail

PRINT_ONLY=0
TARGET_DIR=""
for a in "$@"; do
  case "$a" in
    --print) PRINT_ONLY=1 ;;
    *) TARGET_DIR="$a" ;;
  esac
done

if [ -z "$TARGET_DIR" ]; then
  TARGET_DIR="$(pwd)"
fi

ENV_FILE="${TARGET_DIR}/.env"

# --- Randomness helpers ------------------------------------------------------
# RABBITMQ_ADMIN_PASSWORD / HELLOAI_RABBIT_PASSWORD: hex 16 bytes = 32 chars.
# No ':' possible (hex alphabet only), which matters because init-rabbitmq.sh
# rejects ':' in the admin credentials (curl -u parses it as user/password sep).
gen_hex() {
  openssl rand -hex 16
}

# HELLOAI_CREDENTIAL_AES_KEY_BASE64: 32 random bytes base64-encoded (44 chars).
# This is a 256-bit AES key, matching the CredentialCryptoService default size.
gen_b64() {
  openssl rand -base64 32 | tr -d '\n'
}

# --- Existing .env handling ---------------------------------------------------
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set +u
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set -u
fi

ADMIN_PW="${RABBITMQ_ADMIN_PASSWORD:-$(gen_hex)}"
BIZ_PW="${HELLOAI_RABBIT_PASSWORD:-$(gen_hex)}"
AES_KEY="${HELLOAI_CREDENTIAL_AES_KEY_BASE64:-$(gen_b64)}"
ADMIN_USER="${RABBITMQ_ADMIN_USER:-helloaiadmin}"
BIZ_USER="${HELLOAI_RABBIT_USER:-helloai}"

# --- Output -------------------------------------------------------------------
if [ "$PRINT_ONLY" -eq 1 ]; then
  echo "# paste into ${ENV_FILE} (or keep this output)"
  echo "RABBITMQ_ADMIN_USER=${ADMIN_USER}"
  echo "RABBITMQ_ADMIN_PASSWORD=${ADMIN_PW}"
  echo "HELLOAI_RABBIT_USER=${BIZ_USER}"
  echo "HELLOAI_RABBIT_PASSWORD=${BIZ_PW}"
  echo "HELLOAI_CREDENTIAL_AES_KEY_BASE64=${AES_KEY}"
  echo
  echo "# NOTE: values already present in ${ENV_FILE} were kept (idempotent)."
  exit 0
fi

mkdir -p "$TARGET_DIR"
touch "$ENV_FILE"
chmod 600 "$ENV_FILE"

append_if_missing() {
  local key="$1" val="$2"
  if grep -q "^${key}=" "$ENV_FILE" 2>/dev/null; then
    echo "[keep] ${key} already in ${ENV_FILE}"
  else
    printf '%s=%s\n' "$key" "$val" >> "$ENV_FILE"
    echo "[set ] ${key}"
  fi
}

echo "Writing/updating ${ENV_FILE} (mode 600):"
append_if_missing RABBITMQ_ADMIN_USER "$ADMIN_USER"
append_if_missing RABBITMQ_ADMIN_PASSWORD "$ADMIN_PW"
append_if_missing HELLOAI_RABBIT_USER "$BIZ_USER"
append_if_missing HELLOAI_RABBIT_PASSWORD "$BIZ_PW"
append_if_missing HELLOAI_CREDENTIAL_AES_KEY_BASE64 "$AES_KEY"

echo
echo "DONE. ${ENV_FILE} now contains:"
grep -E '^[A-Z_]+=' "$ENV_FILE" | sed -E 's/(PASSWORD|AES_KEY_BASE64)=.*/\1=<hidden>/'
echo
echo "NEXT (RabbitMQ admin pre-step, required before 'docker compose up -d'):"
echo "  docker compose up -d rabbitmq"
echo "  docker exec helloai-rabbitmq rabbitmqctl add_user helloaiadmin \"\${RABBITMQ_ADMIN_PASSWORD}\""
echo "  docker exec helloai-rabbitmq rabbitmqctl set_user_tags helloaiadmin administrator"
echo "  docker compose up -d --build"
