#!/usr/bin/env bash
# ============================================================================
# HelloAI - Quickstart shutdown (Linux / macOS / WSL)
#
#   ./stop.sh          stop containers, KEEP all data (default)
#   ./stop.sh --purge  stop + delete volumes AND .env secrets (asks first)
#
# Why the default keeps data: the postgres volume holds every task, and
#   .env holds the AES key that decrypts every stored LLM credential.
#   Deleting either is irreversible, so it must be an explicit opt-in.
#
# ASCII-only output (repo convention for deploy artifacts).
# ============================================================================
set -euo pipefail

QS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$QS_DIR"

dc() { docker compose -f docker-compose.yml "$@"; }

PURGE=0
for arg in "$@"; do
  case "$arg" in
    --purge)   PURGE=1 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      printf '[helloai][error] unknown argument: %s\n' "$arg" >&2
      exit 2
      ;;
  esac
done

# `--profile sandbox` is required on `down` too, otherwise compose only
# removes the services that are part of the currently active profile set
# and the sandbox containers are left behind as orphans.
if [ "$PURGE" -eq 1 ]; then
  cat >&2 <<'EOF'
================================================================
 WARNING: --purge is destructive and irreversible.

   - deletes volumes: postgres_data, redis_data, rabbitmq_data, minio_data
     (every task, execution record, attachment and credential is lost)
   - deletes .env
     (the AES key inside it decrypts every stored LLM API key; a new key
      cannot decrypt the old ciphertext even if you restore a database dump)
================================================================
EOF
  printf "Type 'yes' to continue: "
  read -r answer
  [ "$answer" = "yes" ] || { echo "aborted (nothing was changed)"; exit 1; }

  dc --profile sandbox down -v --remove-orphans
  rm -f .env
  echo "[helloai] purged: containers, volumes and .env removed."
else
  dc --profile sandbox down --remove-orphans
  echo "[helloai] stopped. Data volumes and .env kept; run ./start.sh to resume."
fi
