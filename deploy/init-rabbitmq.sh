#!/usr/bin/env bash
# ============================================================
# RabbitMQ isolation bootstrap (runs inside the rabbitmq-init container, idempotent)
# Creates via the Management HTTP API:
#   - vhost  /helloai
#   - user   helloai (configure/write/read on /helloai only)
# The password is injected via environment variables (passed by compose); never written to disk.
#
# NOTE: this file is intentionally ASCII-only (no CJK characters). It runs inside
#   curlimages/curl, whose locale is C, and all of its output goes to
#   `docker compose logs rabbitmq-init` / a terminal that may not be UTF-8.
#   Keep every line ASCII. Use English for comments and runtime messages.
#
# WARNING - vhost name URL-encoding constraint (do NOT revert to a literal; measured)
#   On the Management API the vhost is a URL path segment, so the "/" in the vhost name
#   must be escaped as %2F:
#     PUT /api/vhosts/%2Fhelloai  -> creates a vhost named "/helloai"   OK  (what the app wants)
#     PUT /api/vhosts/helloai     -> creates a vhost named "helloai"    WRONG (a separate entity)
#   Measured: the three vhosts "/", "/helloai" and "helloai" are mutually independent.
#   If written as a literal `helloai`, the app (spring.rabbitmq.virtual-host=/helloai)
#   can never connect, and the failure is silent (an MQ auth failure does not block
#   Spring startup; it only spams the log).
#   Likewise, in /api/permissions/{vhost}/{user} the FIRST path segment is the vhost,
#   so it must also be written as %2Fhelloai.
#
# NOTE: compose runs this script via `entrypoint: /bin/sh` (busybox ash),
#       so only POSIX sh syntax is used. Do not introduce bash-only features
#       (such as ${!var}).
#
# WARNING - this script MUST stay idempotent (all three steps are unconditional PUTs;
#           re-running produces the same result).
#   Reason: in docker-compose.server.yml the app depends on
#           rabbitmq-init: {condition: service_completed_successfully},
#           and a one-shot container is recreated and re-run on every
#           `docker compose up -d`.
#   If this were made non-idempotent (e.g. check-then-create, or anything carrying
#   delete/overwrite semantics), that orchestration dependency would turn into
#   "a dangerous operation on every deploy".
# ============================================================
# Output is ASCII-only, so a C locale is sufficient and deterministic.
export LANG=C
export LC_ALL=C
set -euo pipefail

# --- Fail-fast guards: missing parameters abort immediately, never continue silently ---
if [ -z "${RABBITMQ_ADMIN_USER:-}" ]; then
  echo "[rabbitmq-init] ERROR: environment variable RABBITMQ_ADMIN_USER is unset or empty; refusing to continue (avoiding a silent failure)." >&2
  echo "[rabbitmq-init] NOTE: this account must be a dedicated NON-guest admin -- guest is loopback-only, so a sibling container using it is always rejected." >&2
  exit 1
fi
if [ -z "${RABBITMQ_ADMIN_PASSWORD:-}" ]; then
  echo "[rabbitmq-init] ERROR: environment variable RABBITMQ_ADMIN_PASSWORD is unset or empty; refusing to continue (avoiding a silent failure)." >&2
  exit 1
fi
if [ -z "${HELLOAI_RABBIT_PASSWORD:-}" ]; then
  echo "[rabbitmq-init] ERROR: environment variable HELLOAI_RABBIT_PASSWORD is unset or empty; refusing to continue (avoiding a silent failure)." >&2
  exit 1
fi

# Business account name (compose passes HELLOAI_RABBIT_USER; the default keeps historical behaviour)
RABBIT_USER="${HELLOAI_RABBIT_USER:-helloai}"
# URL-encoded form of the target vhost ("/" in a path segment must be escaped)
RABBIT_VHOST_ENC="%2Fhelloai"

API="http://rabbitmq:15672/api"
AUTH_B64=$(printf '%s:%s' "${RABBITMQ_ADMIN_USER}" "${RABBITMQ_ADMIN_PASSWORD}" | base64 | tr -d '\n')
AUTH="Authorization: Basic ${AUTH_B64}"

# Wait for the broker management API to become ready.
# WARNING - a retry cap is mandatory: when the ADMIN PASSWORD IS WRONG the management
#    API returns 401, and an unbounded `until` loop would retry forever so the container
#    would never exit (a silent hang). The two cases are therefore distinguished and
#    failed explicitly:
#      - 401/403         -> credentials rejected, exit immediately (retrying is pointless)
#      - connect/timeout -> counted against the retry cap, exit when exceeded
# WARNING - the probe curl MUST carry timeouts (--connect-timeout / --max-time):
#    otherwise a single DNS failure or a hung connection makes the "maximum wait"
#    meaningless (measured: a DNS-unresolvable case took about 179s, far beyond the
#    "about 60s" estimated from attempts x interval).
CURL_CONNECT_TIMEOUT=3
CURL_MAX_TIME=5
MAX_ATTEMPTS="${RABBITMQ_INIT_MAX_ATTEMPTS:-30}"   # default 30 attempts
INTERVAL=2
attempt=0
PROBE_CODE=""
while :; do
  if PROBE_CODE=$(curl -sS --connect-timeout "${CURL_CONNECT_TIMEOUT}" --max-time "${CURL_MAX_TIME}" \
                    -o /dev/null -w '%{http_code}' -H "${AUTH}" "${API}/overview" 2>/dev/null); then
    if [ "${PROBE_CODE}" = "200" ]; then
      echo "[rabbitmq-init] management API ready (HTTP 200)"
      break
    fi
  fi
  case "${PROBE_CODE}" in
    401|403)
      echo "[rabbitmq-init] ERROR: management API rejected authentication (HTTP ${PROBE_CODE}) -- admin account or password is wrong (or the account lacks the required tag/permissions). Retrying is pointless; exiting now." >&2
      echo "[rabbitmq-init] Check that .env's RABBITMQ_ADMIN_USER / RABBITMQ_ADMIN_PASSWORD name an account that really exists and is enabled on this broker (and is not guest)." >&2
      exit 1
      ;;
  esac
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge "${MAX_ATTEMPTS}" ]; then
    echo "[rabbitmq-init] ERROR: timed out waiting for the management API (${MAX_ATTEMPTS} attempts, worst case about $((MAX_ATTEMPTS * (INTERVAL + CURL_MAX_TIME)))s including the per-curl cap of ${CURL_MAX_TIME}s; last HTTP code='${PROBE_CODE}'). Giving up." >&2
    case "${PROBE_CODE}" in
      ""|000)
        echo "[rabbitmq-init] HINT: no HTTP response usually means the API is not listening yet, the network is unreachable, or curl is missing in this container." >&2
        ;;
    esac
    exit 1
  fi
  echo "[rabbitmq-init] waiting for management API... (${attempt}/${MAX_ATTEMPTS}, last HTTP='${PROBE_CODE}')"
  sleep "${INTERVAL}"
done

echo "[rabbitmq-init] management API ready, starting isolation bootstrap"

# ------------------------------------------------------------
# PUT helper + failure diagnostics
#   Background: the three steps used to be written as `curl -sf` -- `-s` also silenced
#         the error body, and none of the three steps handled failure. When one failed,
#         the log simply stopped after the previous "creating xxx" line and the container
#         exited with 1, leaving operators unable to tell WHICH step failed and WHY
#         (403 permission denied? invalid name? broker rejected?).
#   Approach: use -w '%{http_code}' to capture the real status code (with -f you cannot
#         get it), then classify each code with an actionable hint.
#   Also: -sS requires -S together with -s so curl still writes transport-level errors
#         to stderr while staying quiet.
#
#   WARNING: compare expected codes as a SPACE-SEPARATED LIST, one literal at a time.
#     Do NOT write "201|204" and expand it inside `case`: it was measured that under
#     POSIX sh / busybox ash, `case "$c" in ${expect})` does NOT treat ${expect} as an
#     alternation (quoted or not), so successful codes would be judged as failures.
# ------------------------------------------------------------
http_put() {
  _step="$1"      # step name (for error localisation)
  _path="$2"      # API path (including the %2F encoding)
  _payload="$3"   # JSON payload (may be an empty string, e.g. {} for vhost creation)
  shift 3
  _expect="$*"    # expected HTTP codes, space-separated, e.g. "200 201 204"

  if [ -n "${_payload}" ]; then
    if _code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
                 -H "${AUTH}" -H 'content-type: application/json' \
                 -d "${_payload}" "${API}${_path}"); then
      _rc=0
    else
      _rc=$?
    fi
  else
    if _code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
                 -H "${AUTH}" "${API}${_path}"); then
      _rc=0
    else
      _rc=$?
    fi
  fi

  if [ "${_rc}" -ne 0 ]; then
    echo "[rabbitmq-init] ERROR: ${_step} failed (HTTP request did not complete; curl exit code ${_rc}, HTTP code='${_code}')." >&2
    echo "[rabbitmq-init]   Possible causes: management API unreachable, network interrupted, DNS failure, or curl missing in this container." >&2
    exit 1
  fi

  _ok=0
  for _e in ${_expect}; do
    if [ "${_code}" = "${_e}" ]; then
      _ok=1
      break
    fi
  done
  if [ "${_ok}" -eq 1 ]; then
    echo "[rabbitmq-init] ${_step} OK (HTTP ${_code})"
    return 0
  fi

  echo "[rabbitmq-init] ERROR: ${_step} failed (HTTP ${_code}; expected one of 200/201/204)." >&2
  case "${_code}" in
    401)
      echo "[rabbitmq-init]   Possible cause: admin credentials rejected or insufficient (HTTP 401) -- account missing, wrong password, or the account lacks the administrator tag." >&2
      echo "[rabbitmq-init]   Measured: the Management API returns 401 for BOTH 'authentication failed' and 'authenticated but not administrator' (it does not return 403), so both causes must be checked together." >&2
      ;;
    403)
      echo "[rabbitmq-init]   Possible cause: forbidden (HTTP 403) -- usually a reverse proxy or security-group block; the Management API itself rarely returns this code." >&2
      ;;
    404)
      echo "[rabbitmq-init]   Possible cause: path not found (HTTP 404) -- the target vhost/user does not exist yet, or the %2F encoding was broken." >&2
      ;;
    400)
      echo "[rabbitmq-init]   Possible cause: invalid name or malformed payload (HTTP 400) -- check the vhost name and the JSON payload." >&2
      ;;
    000|"")
      echo "[rabbitmq-init]   Possible cause: no HTTP response -- connection refused, or a TLS/proxy issue." >&2
      ;;
    *)
      echo "[rabbitmq-init]   Possible cause: broker rejected the request (HTTP ${_code}) -- check the Management API docs." >&2
      ;;
  esac
  exit 1
}

echo "[rabbitmq-init] creating vhost /helloai (URL-encoded as ${RABBIT_VHOST_ENC})"
http_put "create vhost /helloai" "/vhosts/${RABBIT_VHOST_ENC}" '{}' 200 201 204

echo "[rabbitmq-init] creating user ${RABBIT_USER}"
http_put "create business user ${RABBIT_USER}" "/users/${RABBIT_USER}" \
  "{\"password\":\"${HELLOAI_RABBIT_PASSWORD}\",\"tags\":\"management\"}" 200 201 204

echo "[rabbitmq-init] setting permissions (${RABBIT_USER} -> /helloai)"
http_put "set permissions ${RABBIT_USER} -> /helloai" "/permissions/${RABBIT_VHOST_ENC}/${RABBIT_USER}" \
  '{"configure":".*","write":".*","read":".*"}' 200 201 204

echo "[rabbitmq-init] resources ready: vhost=/helloai  user=${RABBIT_USER}  permissions=${RABBIT_USER}->/helloai (configure/write/read=.*)"
echo "[rabbitmq-init] DONE"
