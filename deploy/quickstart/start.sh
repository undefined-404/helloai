#!/usr/bin/env bash
# ============================================================================
# HelloAI - Quickstart launcher (Linux / macOS / WSL)
#
# Goal: one command, no source tree, no JDK/Maven/Node, no local image build.
#   ./start.sh                 core mode  (DB, Redis, MQ, MinIO, app, web)
#   ./start.sh --with-sandbox  + OpenSandbox (extra ~2 GB of images)
#   ./start.sh --offline       import ./helloai-offline-*.tar instead of pull
#   ./start.sh --no-pull       skip pull (use locally built images)
#
# What this script hides from the user (and why each guard exists):
#   1. .env generation   -- 6 secrets, random, IDEMPOTENT. The AES key is
#      never regenerated: it decrypts every stored LLM API key.
#   2. RabbitMQ bootstrap -- on a FIRST boot the official entrypoint already
#      creates user+vhost from RABBITMQ_DEFAULT_*; on an EXISTING volume it
#      silently ignores them, so this script applies them via rabbitmqctl.
#      Without this, the app enters an endless reconnect with ACCESS_REFUSED.
#   3. Port / memory / arch preflight -- the three things that actually stop
#      a fresh laptop, surfaced BEFORE a 2 GB pull.
#   4. Health gating -- returns non-zero if the stack never converges, so CI
#      and humans both get a real signal instead of "it looked like it ran".
#
# Idempotent: safe to re-run. `./stop.sh` keeps data; `./stop.sh --purge`
#   destroys volumes AND the .env secrets (asks for confirmation).
#
# ASCII-only output (repo convention for deploy artifacts: the terminal may
#   run a non-UTF-8 locale). Chinese user docs live in README.md.
# ============================================================================
set -euo pipefail

QS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$QS_DIR"

ENV_FILE=".env"
EXAMPLE_FILE=".env.example"
MIN_MEM_CORE_MB=3500
MIN_MEM_SANDBOX_MB=5500

WITH_SANDBOX=0
OFFLINE=0
NO_PULL=0

dc() { docker compose -f docker-compose.yml "$@"; }

log()  { printf '[helloai] %s\n' "$*"; }
warn() { printf '[helloai][warn] %s\n' "$*" >&2; }
die()  { printf '[helloai][error] %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
HelloAI Quickstart

  ./start.sh                 start the core stack
  ./start.sh --with-sandbox  also enable OpenSandbox skill execution
  ./start.sh --offline       load ./helloai-offline-*.tar instead of pulling
  ./start.sh --no-pull       do not pull (use images already on this host)
  ./start.sh --help          show this message

Environment overrides (edit .env instead of the shell):
  HELLOAI_HTTP_PORT     host port for the web entry (default 8080)
  HELLOAI_SANDBOX_PORT  host port for the sandbox server (default 8090, loopback)
  HELLOAI_JVM_XMX       backend heap (default 2g)
EOF
}

# ---------------------------------------------------------------- arg parsing
for arg in "$@"; do
  case "$arg" in
    --with-sandbox) WITH_SANDBOX=1 ;;
    --offline)      OFFLINE=1 ;;
    --no-pull)      NO_PULL=1 ;;
    -h|--help)      usage; exit 0 ;;
    *)              warn "unknown argument: $arg"; usage; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- randomness
rand_hex() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    od -An -tx1 -N"$bytes" /dev/urandom | tr -d ' \n'
  fi
}

rand_b64() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32 | tr -d '\n'
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

# ---------------------------------------------------------------- .env helpers
env_get() {
  [ -f "$ENV_FILE" ] || { printf ''; return 0; }
  sed -n "s/^$1=//p" "$ENV_FILE" | head -n1
}

env_set() {
  local key="$1" val="$2" tmp
  tmp="$(mktemp)"
  awk -v k="$key" -v v="$val" '
    index($0, k"=") == 1 { print k"="v; found=1; next }
    { print }
    END { if (!found) print k"="v }
  ' "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
}

# Fills a key only when it is absent or empty. Never overwrites a real value.
env_fill() {
  local key="$1" generator="$2" cur val
  cur="$(env_get "$key")"
  if [ -z "$cur" ]; then
    val="$($generator)"
    env_set "$key" "$val"
    log "generated $key"
  fi
}

env_require() {
  local key="$1" cur
  cur="$(env_get "$key")"
  [ -n "$cur" ] || die "$key is empty in $ENV_FILE"
}

bootstrap_env() {
  if [ ! -f "$ENV_FILE" ]; then
    [ -f "$EXAMPLE_FILE" ] || die "missing $EXAMPLE_FILE"
    cp "$EXAMPLE_FILE" "$ENV_FILE"
    log "created $ENV_FILE from $EXAMPLE_FILE"
  fi
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  # A Windows editor can leave CRLF, and a sourced value would then carry a
  # trailing \r (postgres/minio reject the password with no useful message).
  if grep -q $'\r' "$ENV_FILE" 2>/dev/null; then
    tr -d '\r' < "$ENV_FILE" > "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"
    log "normalized CRLF line endings in $ENV_FILE"
  fi

  # Secrets only. Topology defaults come from .env.example / the images.
  env_fill DATASOURCE_PASSWORD                 rand_hex16
  env_fill HELLOAI_RABBIT_PASSWORD             rand_hex16
  env_fill HELLOAI_CREDENTIAL_AES_KEY_BASE64   rand_b64
  env_fill HELLOAI_AGENT_REGISTRATION_TOKEN    rand_hex32
  env_fill MINIO_ROOT_USER                     rand_hex12
  env_fill MINIO_ROOT_PASSWORD                 rand_hex16
  env_fill SANDBOX_API_KEY                     rand_hex32
}

rand_hex12() { rand_hex 12; }
rand_hex16() { rand_hex 16; }
rand_hex32() { rand_hex 32; }

validate_env() {
  env_require HELLOAI_APP_IMAGE
  env_require HELLOAI_WEB_IMAGE
  env_require DATASOURCE_PASSWORD
  env_require HELLOAI_RABBIT_PASSWORD
  env_require HELLOAI_CREDENTIAL_AES_KEY_BASE64
  env_require HELLOAI_AGENT_REGISTRATION_TOKEN
  env_require MINIO_ROOT_USER
  env_require MINIO_ROOT_PASSWORD
  if [ "$WITH_SANDBOX" -eq 1 ]; then
    env_require SANDBOX_API_KEY
  fi
}

# ---------------------------------------------------------------- preflight
preflight() {
  command -v docker >/dev/null 2>&1 || die "docker not found. Install Docker first: https://docs.docker.com/get-docker/"
  docker info >/dev/null 2>&1 || die "cannot reach the Docker daemon. Start Docker Desktop / dockerd, then re-run."
  docker compose version >/dev/null 2>&1 || die "docker compose (v2) not available. Enable the compose plugin."
}

check_arch() {
  local arch
  arch="$(docker info --format '{{.Architecture}}' 2>/dev/null || echo unknown)"
  log "docker architecture: $arch"
  if [ "$WITH_SANDBOX" -eq 1 ] && [ "$arch" != "x86_64" ]; then
    warn "sandbox mode is only validated on linux/amd64; on $arch the OpenSandbox images may be unavailable."
  fi
}

check_memory() {
  local bytes mb need
  bytes="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
  mb=$(( bytes / 1048576 ))
  need="$MIN_MEM_CORE_MB"
  [ "$WITH_SANDBOX" -eq 1 ] && need="$MIN_MEM_SANDBOX_MB"
  if [ "$mb" -le 0 ]; then
    warn "could not read Docker memory limit; skipping the check."
    return 0
  fi
  log "docker memory available: ${mb} MB (recommended >= ${need} MB)"
  if [ "$mb" -lt "$need" ]; then
    warn "Docker has only ${mb} MB. Raise the Docker Desktop memory limit, or the app may be OOM-killed during Flyway/LLM reporting."
  fi
}

port_busy() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}$"
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    return 1
  fi
}

check_port() {
  local port="${HELLOAI_HTTP_PORT:-8080}"
  if port_busy "$port"; then
    die "host port $port is already in use. Set HELLOAI_HTTP_PORT in $ENV_FILE to a free port (e.g. 8081) and re-run."
  fi
  log "web entry will be published on port $port"
}

image_exists() { docker image inspect "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- offline load
load_offline() {
  local tars count=0 t skipped=0
  shopt -s nullglob
  # Both forms are accepted: `docker load -i` reads gzip transparently, so the
  #   release bundle ships .tar.gz and nobody has to gunzip by hand.
  tars=(helloai-offline-*.tar helloai-offline-*.tar.gz)
  if [ ${#tars[@]} -eq 0 ]; then
    warn "no helloai-offline-*.tar or *.tar.gz found in $QS_DIR; expecting images to be loaded already."
    shopt -u nullglob
    return 0
  fi
  for t in "${tars[@]}"; do
    # Core mode must not pay for the optional sandbox bundle (several GB).
    #   The bundle name carries the distinction, so no extra flag is needed.
    case "$t" in
      *sandbox*)
        if [ "$WITH_SANDBOX" -ne 1 ]; then
          log "skipping $t (core mode; re-run with --with-sandbox to load it)"
          skipped=$((skipped + 1))
          continue
        fi
        ;;
    esac
    log "docker load <- $t"
    docker load -i "$t"
    count=$((count + 1))
  done
  shopt -u nullglob
  log "loaded $count archive(s), skipped $skipped"
}

# ---------------------------------------------------------------- pull
pull_core() {
  if [ "$OFFLINE" -eq 1 ]; then
    log "--offline: skipping pull"
    return 0
  fi
  if [ "$NO_PULL" -eq 1 ]; then
    log "--no-pull: skipping pull"
    return 0
  fi

  dc pull postgres redis rabbitmq minio || die "failed to pull middleware images"

  if ! dc pull app web; then
    warn "could not pull app/web images."
    warn "  - locally built images? re-run with --no-pull"
    warn "  - slow network? pull manually: docker compose pull app web"
    image_exists "$(env_get HELLOAI_APP_IMAGE)" || die "app image '$(env_get HELLOAI_APP_IMAGE)' is neither pullable nor present locally"
    image_exists "$(env_get HELLOAI_WEB_IMAGE)" || die "web image '$(env_get HELLOAI_WEB_IMAGE)' is neither pullable nor present locally"
  fi
}

# ---------------------------------------------------------------- sandbox images
# Delegates to pull-sandbox-images.sh (shipped next to this script in the release
#   package). Do NOT go back to `dc --profile prewarm pull`:
#   1) that covers only the two business images declared in the prewarm profile and
#      silently skips the two HELPER images (execd / egress);
#   2) it never verifies that the image actually LANDED on the host daemon -- and
#      the sandbox server resolves images by exact name through /var/run/docker.sock,
#      so a missing one surfaces as a bare ~35s timeout with no cause.
find_sandbox_helper() {
  local c
  for c in "$QS_DIR/pull-sandbox-images.sh" "$QS_DIR/../pull-sandbox-images.sh"; do
    if [ -f "$c" ]; then
      printf '%s' "$c"
      return 0
    fi
  done
  return 1
}

pull_sandbox_images() {
  if [ "$WITH_SANDBOX" -ne 1 ]; then
    return 0
  fi

  local helper reason
  if ! helper="$(find_sandbox_helper)"; then
    die "pull-sandbox-images.sh not found (looked next to start.sh and in deploy/).
  --with-sandbox needs it to guarantee that BOTH the helper images (execd/egress)
  and the business images (python/node) exist on this host; the sandbox server
  cannot pull them itself. Re-download the quickstart package."
  fi

  # --offline / --no-pull: verify only, never fetch. Either way the user asked for
  #   the sandbox EXPLICITLY, so a missing image is a hard error rather than a
  #   warning: continuing would only move the failure to the first skill call.
  if [ "$OFFLINE" -eq 1 ] || [ "$NO_PULL" -eq 1 ]; then
    reason="--no-pull"
    if [ "$OFFLINE" -eq 1 ]; then
      reason="--offline"
    fi
    log "verifying sandbox images on this host (no network)"
    if ! bash "$helper" --check-only; then
      die "sandbox images are missing and $reason forbids fetching them.
  Load the offline sandbox archive first, or drop --with-sandbox."
    fi
    return 0
  fi

  log "preparing sandbox images (helper: execd/egress, business: python/node)"
  if ! bash "$helper"; then
    die "sandbox image preparation failed -- see the guidance above.
  Nothing was broken: the core services are unaffected. Re-run without
  --with-sandbox if you only need the core experience."
  fi
}

# ---------------------------------------------------------------- health gate
wait_healthy() {
  local svc="$1" timeout="${2:-180}" start now cid status
  start="$(date +%s)"
  while :; do
    cid="$(dc ps -q "$svc" 2>/dev/null | head -n1 || true)"
    status=""
    if [ -n "$cid" ]; then
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
    fi
    case "$status" in
      healthy) return 0 ;;
      unhealthy|exited|dead)
        warn "$svc is $status -- recent logs:"
        dc logs --tail 40 "$svc" >&2 || true
        return 1
        ;;
    esac
    now="$(date +%s)"
    if [ $((now - start)) -ge "$timeout" ]; then
      warn "$svc did not become healthy within ${timeout}s (last status: ${status:-not created})"
      dc logs --tail 40 "$svc" >&2 || true
      return 1
    fi
    sleep 3
  done
}

# ---------------------------------------------------------------- rabbitmq
ensure_rabbitmq() {
  local cid user pass
  # `|| true` on the piped lookup: `head -n1` closes the pipe early and
  #   pipefail would otherwise abort the script on SIGPIPE.
  cid="$(dc ps -q rabbitmq 2>/dev/null | head -n1 || true)"
  [ -n "$cid" ] || die "rabbitmq container not found"

  user="$(env_get HELLOAI_RABBIT_USER)"
  [ -n "$user" ] || user="helloai"
  pass="$(env_get HELLOAI_RABBIT_PASSWORD)"

  # add_vhost fails when the vhost already exists -- expected on re-runs.
  # add_user fails when the user already exists -- expected on re-runs.
  # change_password is idempotent and keeps the broker in sync with .env,
  #   which is what prevents "auth works but password differs" lockouts.
  docker exec "$cid" rabbitmqctl add_vhost /helloai          >/dev/null 2>&1 || true
  docker exec "$cid" rabbitmqctl add_user "$user" "$pass"    >/dev/null 2>&1 || true
  docker exec "$cid" rabbitmqctl change_password "$user" "$pass" >/dev/null 2>&1 \
    || die "could not set password for RabbitMQ user '$user'"
  docker exec "$cid" rabbitmqctl set_permissions -p /helloai "$user" ".*" ".*" ".*" >/dev/null 2>&1 \
    || die "could not grant permissions on vhost /helloai to '$user'"
  log "rabbitmq ready: user=$user vhost=/helloai"
}

# ---------------------------------------------------------------- main flow
main() {
  preflight
  bootstrap_env
  validate_env

  # shellcheck disable=SC1091
  set -a
  # shellcheck source=/dev/null
  . "./$ENV_FILE"
  set +a

  check_arch
  check_memory
  check_port

  if [ "$OFFLINE" -eq 1 ]; then
    load_offline
  fi
  pull_core
  pull_sandbox_images

  log "starting middleware (postgres, redis, rabbitmq, minio)"
  dc up -d postgres redis rabbitmq minio
  wait_healthy postgres 90  || die "postgres failed to start"
  wait_healthy redis 60     || die "redis failed to start"
  wait_healthy rabbitmq 120 || die "rabbitmq failed to start"
  wait_healthy minio 90     || die "minio failed to start"

  ensure_rabbitmq

  if [ "$WITH_SANDBOX" -eq 1 ]; then
    export SANDBOX_ENABLED=true
    export SANDBOX_URL="http://opensandbox-server:8090"
    export SANDBOX_API_KEY="$(env_get SANDBOX_API_KEY)"
    log "starting app + web + sandbox"
    dc --profile sandbox up -d
  else
    export SANDBOX_ENABLED=false
    log "starting app + web (core mode: sandbox disabled)"
    dc up -d
  fi

  wait_healthy app 420 || die "app failed to start -- see: docker compose -f docker-compose.yml logs app"
  wait_healthy web 60  || die "web failed to start"

  print_summary
}

print_summary() {
  local port="${HELLOAI_HTTP_PORT:-8080}"
  cat <<EOF

================================================================
 HelloAI is up.

   Web UI        : http://localhost:${port}
   API docs      : http://localhost:${port}/swagger-ui.html
   Default admin : admin / admin123   (change it after first login)

   Mode          : $([ "$WITH_SANDBOX" -eq 1 ] && echo "core + sandbox" || echo "core (sandbox disabled)")
   Secrets file  : ${QS_DIR}/${ENV_FILE}  (mode 600 -- back it up)

 Next steps:
   1. Open the Web UI and sign in with the default admin account.
   2. Configure an LLM provider key in Settings (it is encrypted with the
      AES key in ${ENV_FILE}; losing that file means losing every key).
   3. Create a task and watch the execution timeline.

 Useful commands (run from this directory):
   ./stop.sh                  stop everything, KEEP data
   ./start.sh                 start again
   docker compose -f docker-compose.yml ps
   docker compose -f docker-compose.yml logs -f app

 To enable sandbox skills later:
   ./start.sh --with-sandbox
================================================================
EOF
}

main "$@"
