#!/usr/bin/env bash
# ============================================================
# Data migration: OLD full-stack server -> NEW standalone middleware (Option A)
#
# Source & target hosts are NOT hardcoded here (no real IPs in repo).
#   NEW_HOST defaults to 127.0.0.1 (run this ON the new middleware server).
#   OLD_HOST is required: pass it explicitly, e.g. OLD_HOST=<old-ip>.
# Target passwords are read from ../.env (POSTGRES_PASSWORD etc.).
#
# Usage (run on the new middleware server):
#   OLD_HOST=<old-server-ip> ./migrate.sh pg     # PostgreSQL only (HelloAI)
#   OLD_HOST=<old-server-ip> ./migrate.sh mysql  # MySQL only (other project)
#   OLD_HOST=<old-server-ip> ./migrate.sh minio  # MinIO object data only
#   OLD_HOST=<old-server-ip> ./migrate.sh all    # everything
#
# Prerequisites:
#   - New middleware already: docker compose up -d && all healthy
#   - This host has pg_dump/pg_restore, mysqldump/mysql, mc
# ============================================================
export LANG=C
export LC_ALL=C
set -euo pipefail

# ---------- Config region ----------
# Source old full-stack server (HelloAI currently runs here) - REQUIRED
OLD_HOST="${OLD_HOST:?OLD_HOST must be set, e.g. OLD_HOST=1.2.3.4}"
# Target new middleware server (default: run locally on this machine)
NEW_HOST="${NEW_HOST:-127.0.0.1}"

# Optional: load ../.env for target passwords (idempotent)
ENV_FILE="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)/.env"
if [ -f "${ENV_FILE}" ]; then
  set -a; . "${ENV_FILE}"; set +a
fi

# ---------- PostgreSQL (HelloAI) ----------
OLD_PG_PORT="${OLD_PG_PORT:-15432}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-helloai}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"            # source: old compose
NEW_PG_PORT="${NEW_PG_PORT:-15432}"
NEW_PG_PASSWORD="${NEW_PG_PASSWORD:-${POSTGRES_PASSWORD:-}}"  # target: .env

# ---------- MySQL (other project) ----------
OLD_MYSQL_PORT="${OLD_MYSQL_PORT:-3306}"
NEW_MYSQL_PORT="${NEW_MYSQL_PORT:-13306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_DB="${MYSQL_DB:-other_project}"
OLD_MYSQL_PASSWORD="${OLD_MYSQL_PASSWORD:-}"
NEW_MYSQL_PASSWORD="${NEW_MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"  # target: .env

# ---------- MinIO (HelloAI bucket) ----------
OLD_MINIO_ENDPOINT="${OLD_MINIO_ENDPOINT:-http://${OLD_HOST}:29000}"
OLD_MINIO_ACCESS="${OLD_MINIO_ACCESS:-minioadmin}"
OLD_MINIO_SECRET="${OLD_MINIO_SECRET:-minioadmin123}"   # source: old compose
NEW_MINIO_ENDPOINT="${NEW_MINIO_ENDPOINT:-http://${NEW_HOST}:29000}"
NEW_MINIO_ACCESS="${NEW_MINIO_ACCESS:-helloai-s3}"      # created by minio-init
NEW_MINIO_SECRET="${NEW_MINIO_SECRET:-${HELLOAI_S3_SECRET_KEY:-}}"  # target: .env
MINIO_BUCKETS="${MINIO_BUCKETS:-helloai-artifacts}"
# ---------- /Config region ----------

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "[migrate] missing required tool: $1"; exit 1; }
}

migrate_pg() {
  require pg_dump
  require pg_restore
  [ -n "${NEW_PG_PASSWORD}" ] || { echo "[PG] NEW_PG_PASSWORD empty; set POSTGRES_PASSWORD in .env"; exit 1; }
  local dump="helloai-$(date +%Y%m%d%H%M%S).dump"
  echo "[PG] dump  ${OLD_HOST}:${OLD_PG_PORT}/${PG_DB} -> ${dump}"
  PGPASSWORD="${PG_PASSWORD}" pg_dump -h "${OLD_HOST}" -p "${OLD_PG_PORT}" \
    -U "${PG_USER}" -d "${PG_DB}" -Fc -f "${dump}"
  echo "[PG] ensure database exists on target"
  PGPASSWORD="${NEW_PG_PASSWORD}" psql -h "${NEW_HOST}" -p "${NEW_PG_PORT}" \
    -U "${PG_USER}" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname='${PG_DB}'" | grep -q 1 || \
    PGPASSWORD="${NEW_PG_PASSWORD}" psql -h "${NEW_HOST}" -p "${NEW_PG_PORT}" \
      -U "${PG_USER}" -d postgres -c "CREATE DATABASE ${PG_DB}"
  echo "[PG] restore to ${NEW_HOST}:${NEW_PG_PORT}/${PG_DB}"
  PGPASSWORD="${NEW_PG_PASSWORD}" pg_restore -h "${NEW_HOST}" -p "${NEW_PG_PORT}" \
    -U "${PG_USER}" -d "${PG_DB}" --no-owner --no-privileges -j 4 "${dump}"
  echo "[PG] DONE"
}

migrate_mysql() {
  require mysqldump
  require mysql
  [ -n "${OLD_MYSQL_PASSWORD}" ] || { echo "[MySQL] OLD_MYSQL_PASSWORD empty; set source mysql password"; exit 1; }
  [ -n "${NEW_MYSQL_PASSWORD}" ] || { echo "[MySQL] NEW_MYSQL_PASSWORD empty; set MYSQL_ROOT_PASSWORD in .env"; exit 1; }
  local dump="mysql-${MYSQL_DB}-$(date +%Y%m%d%H%M%S).sql"
  echo "[MySQL] dump ${OLD_HOST}:${OLD_MYSQL_PORT}/${MYSQL_DB} -> ${dump}"
  mysqldump -h "${OLD_HOST}" -P "${OLD_MYSQL_PORT}" -u "${MYSQL_USER}" \
    -p"${OLD_MYSQL_PASSWORD}" --default-character-set=utf8mb4 \
    --single-transaction --routines --triggers "${MYSQL_DB}" > "${dump}"
  echo "[MySQL] restore to ${NEW_HOST}:${NEW_MYSQL_PORT}/${MYSQL_DB}"
  mysql -h "${NEW_HOST}" -P "${NEW_MYSQL_PORT}" -u "${MYSQL_USER}" \
    -p"${NEW_MYSQL_PASSWORD}" --default-character-set=utf8mb4 < "${dump}"
  echo "[MySQL] DONE"
}

migrate_minio() {
  require mc
  [ -n "${NEW_MINIO_SECRET}" ] || { echo "[MinIO] NEW_MINIO_SECRET empty; set HELLOAI_S3_SECRET_KEY in .env"; exit 1; }
  echo "[MinIO] mirror buckets: ${MINIO_BUCKETS}"
  mc alias set old-s3 "${OLD_MINIO_ENDPOINT}" "${OLD_MINIO_ACCESS}" "${OLD_MINIO_SECRET}" >/dev/null
  mc alias set new-s3 "${NEW_MINIO_ENDPOINT}" "${NEW_MINIO_ACCESS}" "${NEW_MINIO_SECRET}" >/dev/null
  for b in ${MINIO_BUCKETS}; do
    echo "[MinIO] mirror ${b}"
    mc mirror --overwrite --remove "old-s3/${b}" "new-s3/${b}"
  done
  echo "[MinIO] DONE"
}

case "${1:-}" in
  pg)    migrate_pg ;;
  mysql) migrate_mysql ;;
  minio) migrate_minio ;;
  all)
    migrate_pg
    migrate_mysql
    migrate_minio
    ;;
  *)
    echo "Usage: $0 [pg|mysql|minio|all]"
    exit 1
    ;;
esac