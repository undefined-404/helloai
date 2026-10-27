#!/usr/bin/env bash
# ============================================================
# HelloAI - production host preflight for a Docker Engine / Compose upgrade
#
# Purpose: run this ON THE PRODUCTION SERVER, BEFORE upgrading Docker Engine
#   and/or the Compose v2 plugin. It captures the current host + daemon state
#   and surfaces every blocker/warning that would otherwise be discovered
#   halfway through the upgrade (when the stack is already down).
#
# Safety contract (important):
#   READ-ONLY by default. It never starts / stops / removes a container, never
#   pulls an image, never edits a config file, never touches a volume.
#   The ONLY write is the optional `--record <file>` artifact, which is a
#   rollback record you keep outside the repo.
#
# Why capture a record first:
#   A Docker upgrade is only reversible if you can name exactly where you came
#   from -- engine + compose package versions, and the DIGESTS of the images the
#   stack currently runs. `--record` captures precisely that set.
#
# Usage:
#   bash deploy/docker-host-preflight.sh                       # probe + verdict
#   bash deploy/docker-host-preflight.sh --record rollback.txt # also write record
#   bash deploy/docker-host-preflight.sh --compose ./docker-compose.server.yml
#   bash deploy/docker-host-preflight.sh --help
#
# Exit code: 0 = no FAIL blocker; 1 = at least one FAIL blocker.
#   WARN never fails the script -- it is a judgement call for the operator.
#
# Ref: doc/plan/  -> go-live checklist, section 2 (Docker engine / compose upgrade)
#      doc/       -> implementation gap table, item G-017 (deployment-side security actions)
#      LOG-20261011-011
#
# (Two doc filenames in this repo are Chinese; they are deliberately NOT spelled
#  out here so that this deploy artifact stays ASCII-only. Locate them by the
#  anchors above -- the checklist section and LOG-20261011-011 both point back
#  at this script by its full path.)
#
# NOTE: ASCII-only file (repo convention for deploy artifacts, e.g. this script's
#       output may be pasted into a terminal whose locale is C/POSIX).
# ============================================================
set -uo pipefail

REC=""
COMPOSE_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --record)  REC="${2:-}"; shift 2 ;;
    --compose) COMPOSE_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,41p' "$0"; exit 0 ;;
    *) printf 'unknown arg: %s\n' "$1" >&2; exit 64 ;;
  esac
done

FAILS=0
WARNS=0
ok()   { printf '  [ OK ] %s\n' "$1"; }
warn() { WARNS=$((WARNS + 1)); printf '  [WARN] %s\n' "$1"; }
fail() { FAILS=$((FAILS + 1)); printf '  [FAIL] %s\n' "$1"; }
info() { printf '  %s\n' "$1"; }
sec()  { printf '\n== %s ==\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

REC_LINES=()
rec() { if [ -n "$REC" ]; then REC_LINES+=("$1"); fi; return 0; }

# Resolve the compose file EARLY: section 2 uses it to include the images the
# stack declares (including ones not running yet); section 6 validates it.
CF="$COMPOSE_FILE"
if [ -z "$CF" ]; then
  for c in docker-compose.server.yml docker-compose.yml /home/admin/helloai/docker-compose.server.yml; do
    if [ -f "$c" ]; then CF="$c"; break; fi
  done
fi

printf 'HelloAI - Docker host preflight (read-only apart from --record)\n'
printf 'host: %s   time(UTC): %s\n' "$(hostname 2>/dev/null || echo '?')" "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo '?')"

# ------------------------------------------------------------
sec "1. Host / OS"
# ------------------------------------------------------------
KERNEL="$(uname -srm 2>/dev/null || echo '?')"
info "kernel: ${KERNEL}"
rec "host=$(hostname 2>/dev/null || echo '?')"
rec "kernel=${KERNEL}"

OS_NAME="unknown"
if [ -r /etc/os-release ]; then
  OS_NAME="$( . /etc/os-release 2>/dev/null; printf '%s %s' "${NAME:-?}" "${VERSION_ID:-}" )"
fi
info "os: ${OS_NAME}"
rec "os=${OS_NAME}"

if [ -f /.dockerenv ]; then
  warn "running INSIDE a container (/.dockerenv present) - preflight must run on the HOST, not in a container"
fi

CPUS="$(nproc 2>/dev/null || echo '?')"
info "cpus: ${CPUS}"
rec "cpus=${CPUS}"

if have free; then
  MEM="$(free -h 2>/dev/null | awk 'NR==2{print $2" total / "$7" available"}')"
  info "memory: ${MEM}"
  rec "memory=${MEM}"
fi

if have timedatectl; then
  TZLINE="$(timedatectl 2>/dev/null | sed -n 's/^ *Time zone: //p' || true)"
  [ -n "$TZLINE" ] && info "time zone: ${TZLINE}"
  SYNC="$(timedatectl 2>/dev/null | sed -n 's/^ *System clock synchronized: //p' || true)"
  case "${SYNC:-}" in
    yes) ok "system clock synchronized (NTP)" ;;
    no)  warn "system clock NOT synchronized - fix NTP before trusting log/lease timestamps" ;;
  esac
fi

# ------------------------------------------------------------
sec "2. Docker Engine"
# ------------------------------------------------------------
if ! have docker; then
  fail "docker CLI not found in PATH - install Docker Engine before anything else"
  printf '\n== VERDICT ==\n'
  printf '  FAIL blockers: %s\n' "$FAILS"
  exit 1
fi

ENGINE_RAW="$(docker --version 2>/dev/null || echo '?')"
info "client: ${ENGINE_RAW}"
rec "docker_client=${ENGINE_RAW}"

if ! docker info >/dev/null 2>&1; then
  fail "docker daemon NOT reachable (is dockerd running? is this user allowed to talk to the socket?)"
  DOCKER_OK=0
else
  DOCKER_OK=1
  ok "docker daemon reachable"
fi

din() { docker info --format "{{.$1}}" 2>/dev/null || true; }

if [ "$DOCKER_OK" = "1" ]; then
  SRV_VER="$(din ServerVersion)"
  STORAGE_DRIVER="$(din Driver)"
  DOCKER_ROOT="$(din DockerRootDir)"
  LIVE_RESTORE="$(din LiveRestoreEnabled)"
  CGROUP_VER="$(din CgroupVersion)"
  info "server: ${SRV_VER:-?}"
  info "storage driver: ${STORAGE_DRIVER:-?}"
  info "docker root dir: ${DOCKER_ROOT:-?}"
  info "live-restore: ${LIVE_RESTORE:-?}"
  [ -n "$CGROUP_VER" ] && info "cgroup version: ${CGROUP_VER}"
  rec "docker_server=${SRV_VER:-?}"
  rec "storage_driver=${STORAGE_DRIVER:-?}"
  rec "docker_root_dir=${DOCKER_ROOT:-?}"
  rec "live_restore=${LIVE_RESTORE:-?}"
  [ -n "$CGROUP_VER" ] && rec "cgroup_version=${CGROUP_VER}"

  # --- disk headroom where container/volume data actually lives ---
  if [ -n "${DOCKER_ROOT:-}" ] && [ -d "${DOCKER_ROOT}" ]; then
    # Field positions are read from the END (NF-n) on purpose: a cloud filesystem
    # source such as "overlay" or a path containing spaces shifts the left-hand
    # columns, while Mounted-on / Use% / Available are always the last three.
    AVAIL_KB="$(df -Pk "$DOCKER_ROOT" 2>/dev/null | awk 'NR==2{print $(NF-2)}')"
    ROOT_MNT="$(df -Pk "$DOCKER_ROOT" 2>/dev/null | awk 'NR==2{print $NF}')"
    if [ -n "${AVAIL_KB:-}" ]; then
      AVAIL_GB=$((AVAIL_KB / 1024 / 1024))
      info "docker root mount: ${ROOT_MNT}  free: ${AVAIL_GB} GiB"
      rec "docker_root_mount=${ROOT_MNT} free_gib=${AVAIL_GB}"
      if [ "$AVAIL_GB" -lt 2 ]; then
        fail "less than 2 GiB free on the docker root - an upgrade/pull can fill the disk and wedge dockerd"
      elif [ "$AVAIL_GB" -lt 5 ]; then
        warn "less than 5 GiB free on the docker root (${AVAIL_GB} GiB) - upgrade pulls plus build cache may not fit"
      fi
      # If the box has a dedicated data disk (common on cloud VMs) but docker
      # roots on the OS disk, all volume data sits on the small disk.
      if [ -d /data ] && [ "$ROOT_MNT" != "/data" ]; then
        warn "docker root is on '${ROOT_MNT}' but /data exists - volumes live on the OS disk; confirm this is intended"
      fi
    fi
  fi

  if have df; then
    ROOT_LINE="$(df -Pk / 2>/dev/null | awk 'NR==2{printf "%d GiB free of %d GiB (%s used)", $(NF-2)/1048576, $(NF-4)/1048576, $(NF-1)}')"
    [ -n "$ROOT_LINE" ] && info "root fs: ${ROOT_LINE}"
  fi

  # --- containers / volumes / images of THIS stack ---
  ALL_C="$(docker ps -aq 2>/dev/null | wc -l | tr -d '[:space:]')"
  RUN_C="$(docker ps -q 2>/dev/null | wc -l | tr -d '[:space:]')"
  info "containers: ${ALL_C} total / ${RUN_C} running"
  rec "containers_total=${ALL_C} running=${RUN_C}"

  if [ "${RUN_C:-0}" -gt 0 ]; then
    info "running containers (name | image | restart policy):"
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      CID="$(printf '%s' "$line" | cut -f1)"
      NM="$(docker inspect -f '{{.Name}}' "$CID" 2>/dev/null | sed 's#^/##')"
      IM="$(docker inspect -f '{{.Config.Image}}' "$CID" 2>/dev/null)"
      RP="$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$CID" 2>/dev/null)"
      printf '         %-28s %-34s %s\n' "$NM" "$IM" "$RP"
      rec "running_container=${NM} image=${IM} restart=${RP}"
    done < <(docker ps -q 2>/dev/null)
  fi

  if [ "${RUN_C:-0}" -gt 0 ] && [ "${LIVE_RESTORE:-false}" != "true" ]; then
    warn "live-restore is DISABLED and ${RUN_C} container(s) are running - upgrading dockerd will STOP them (plan a maintenance window, or enable live-restore BEFORE the upgrade)"
  fi

  HL_VOLS="$(docker volume ls --format '{{.Name}}' 2>/dev/null | grep -E 'helloai|postgres_data|redis_data|rabbitmq_data|minio_data' || true)"
  if [ -n "$HL_VOLS" ]; then
    info "stack volumes found:"
    printf '%s\n' "$HL_VOLS" | sed 's/^/         /'
    while IFS= read -r v; do rec "volume=${v}"; done < <(printf '%s\n' "$HL_VOLS")
  else
    warn "no helloai/middleware volume found - either a fresh host, or the stack has never run here"
  fi

  # Stack images = images of the RUNNING containers + images the compose file
  # declares (so a service that is not up yet is covered as well). Scoped this
  # way on purpose: a name-prefix grep would also pull in unrelated images that
  # merely share a prefix on a shared host.
  STACK_IMGS="$(
    {
      while IFS= read -r cid; do docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null; done < <(docker ps -q 2>/dev/null)
      if [ -n "$CF" ] && [ -f "$CF" ]; then
        sed -n 's/^[[:space:]]*image:[[:space:]]*\([^[:space:]]*\).*/\1/p' "$CF" 2>/dev/null
      fi
    } | grep -E '^[^[:space:]]' | sort -u || true
  )"
  if [ -n "$STACK_IMGS" ]; then
    info "stack image pins (rollback reference):"
    while IFS= read -r img; do
      [ -n "$img" ] || continue
      DIG="$(docker image inspect -f '{{index .RepoDigests 0}}' "$img" 2>/dev/null || true)"
      printf '         %-42s %s\n' "$img" "${DIG:-<built locally, no repo digest>}"
      rec "image=${img} digest=${DIG:-none}"
    done < <(printf '%s\n' "$STACK_IMGS")
    FLOATING="$(printf '%s\n' "$STACK_IMGS" | grep -E ':latest$' || true)"
    if [ -n "$FLOATING" ]; then
      warn "floating ':latest' tag in the stack - pin it (image@sha256 or a fixed tag) so a later pull cannot silently change the runtime"
    fi
  fi

  if have docker; then
    printf '  -- docker system df --\n'
    docker system df 2>/dev/null | sed 's/^/         /' || true
  fi
fi

# ------------------------------------------------------------
sec "3. Compose (v2 plugin REQUIRED by this repo)"
# ------------------------------------------------------------
# docker-compose.server.yml uses `depends_on: condition: service_completed_successfully`
# and has no top-level `version:` key -> it targets the Compose SPEC, i.e. the
# `docker compose` v2 CLI plugin. Compose v1 (the Python `docker-compose`) does
# NOT support that condition, so the stack cannot be brought up with it at all.
COMPOSE_V=""
COMPOSE_OK=0
if have docker && docker compose version >/dev/null 2>&1; then
  COMPOSE_V="$(docker compose version --short 2>/dev/null || docker compose version 2>/dev/null | head -1)"
  COMPOSE_OK=1
  ok "Compose v2 plugin present: ${COMPOSE_V}"
  rec "compose_v2=${COMPOSE_V}"
else
  fail "Compose v2 plugin ('docker compose') NOT available - the server compose file cannot be deployed with it"
fi

if have docker-compose; then
  DCV_RAW="$(docker-compose --version 2>/dev/null || true)"
  # Docker Desktop and recent packages install `docker-compose` as an ALIAS to the
  # v2 binary; its banner is "Docker Compose version vX.Y.Z". The EOL Python v1
  # line prints "docker-compose version 1.x.y, build ...". Only the latter is a
  # genuine v1 -- do NOT warn on the alias (a false alarm here would send the
  # operator chasing a Compose version that is not actually the problem).
  case "$DCV_RAW" in
    docker-compose\ version\ 1.*)
      warn "legacy Compose v1 present (${DCV_RAW}) - EOL, and it does NOT support service_completed_successfully; use 'docker compose' instead" ;;
    "")
      : ;;
    *)
      info "docker-compose binary is a Compose v2 alias (${DCV_RAW}) - harmless"
      rec "compose_alias=${DCV_RAW}" ;;
  esac
fi

# ------------------------------------------------------------
sec "4. Packages / upgrade candidates"
# ------------------------------------------------------------
PKG_MGR=""
if have apt-get; then
  PKG_MGR="apt"
elif have dnf; then
  PKG_MGR="dnf"
elif have yum; then
  PKG_MGR="yum"
fi
info "package manager: ${PKG_MGR:-unknown (rpm/deb untouched -> manual upgrade)}"
rec "package_manager=${PKG_MGR:-unknown}"

if [ "$PKG_MGR" = "apt" ]; then
  info "installed docker-related packages:"
  INST="$(dpkg-query -W -f='${Package} ${Version}\n' docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker.io 2>/dev/null | sed 's/^/         /' || true)"
  if [ -n "$INST" ]; then printf '%s\n' "$INST"; fi
  while IFS= read -r l; do [ -n "$l" ] && rec "pkg_installed=${l}"; done < <(printf '%s\n' "$INST" | sed 's/^ *//')
  for p in docker-ce docker-ce-cli containerd.io docker-compose-plugin; do
    CAND="$(apt-cache policy "$p" 2>/dev/null | sed -n 's/^ *Candidate: //p' | head -1 || true)"
    [ -n "$CAND" ] && [ "$CAND" != "(none)" ] && info "candidate ${p} -> ${CAND}"
  done
  if [ -f /etc/apt/sources.list.d/docker.list ] || ls /etc/apt/sources.list.d/*docker* >/dev/null 2>&1; then
    ok "a docker apt repository is configured (upgrade can be done in place)"
  else
    warn "no docker apt repository found - the upgrade needs the repo wired up first (use the Aliyun/China mirror if download.docker.com is slow)"
  fi
elif [ "$PKG_MGR" = "dnf" ] || [ "$PKG_MGR" = "yum" ]; then
  info "installed docker-related packages:"
  INST="$(rpm -q --qf '%{NAME} %{VERSION}-%{RELEASE}\n' docker-ce docker-ce-cli containerd.io docker-compose-plugin 2>/dev/null | sed 's/^/         /' || true)"
  if [ -n "$INST" ]; then printf '%s\n' "$INST"; fi
  while IFS= read -r l; do [ -n "$l" ] && rec "pkg_installed=${l}"; done < <(printf '%s\n' "$INST" | sed 's/^ *//')
  if ls /etc/yum.repos.d/*docker* >/dev/null 2>&1; then
    ok "a docker repo file is configured (upgrade can be done in place)"
  else
    warn "no docker repo file found - the upgrade needs the repo wired up first"
  fi
fi

if [ -f /etc/docker/daemon.json ]; then
  info "current /etc/docker/daemon.json:"
  sed 's/^/         /' /etc/docker/daemon.json 2>/dev/null || true
  rec "daemon_json_begin"
  while IFS= read -r l; do rec "$l"; done < <(sed 's/^/  /' /etc/docker/daemon.json 2>/dev/null)
  rec "daemon_json_end"
else
  info "/etc/docker/daemon.json: absent (daemon on built-in defaults)"
fi

# ------------------------------------------------------------
sec "5. Ports used by this stack"
# ------------------------------------------------------------
PORTS="80 15432 26379 25672 25673 29000 29001"
LISTEN=""
if have ss; then
  LISTEN="$(ss -lntp 2>/dev/null || true)"
elif have netstat; then
  LISTEN="$(netstat -lntp 2>/dev/null || netstat -lnt 2>/dev/null || true)"
fi
if [ -n "$LISTEN" ]; then
  for p in $PORTS; do
    if printf '%s\n' "$LISTEN" | grep -qE "[:.]${p}[[:space:]]"; then
      info "port ${p}: LISTENING"
    else
      info "port ${p}: free"
    fi
  done
else
  warn "neither ss nor netstat available - cannot verify port occupancy"
fi
info "NOTE: 15432/26379/25672/25673/29000/29001 must be closed at the cloud security group"
info "      (G-017 pending action) - this script cannot see the security group, check the console."

# ------------------------------------------------------------
sec "6. Compose file feature support"
# ------------------------------------------------------------
if [ -n "$CF" ] && [ -f "$CF" ]; then
  info "compose file: ${CF}"
  rec "compose_file=${CF}"
  if grep -q 'service_completed_successfully' "$CF" 2>/dev/null; then
    if [ "$COMPOSE_OK" = "1" ]; then
      ok "file uses 'service_completed_successfully' and Compose v2 is present (condition supported)"
    else
      fail "file uses 'service_completed_successfully' but Compose v2 is missing -> 'up' will be rejected"
    fi
  fi
  CF_DIR="$(dirname "$CF")"
  if [ -f "${CF_DIR}/.env" ]; then
    info ".env present next to the compose file - validating rendered config"
    if ( cd "$CF_DIR" && docker compose -f "$(basename "$CF")" config -q ) 2>/dev/null; then
      ok "docker compose config validates (required env vars present)"
    else
      fail "docker compose config FAILED - fix it before the upgrade (missing/typo'd .env var is the usual cause)"
    fi
  else
    warn "no .env next to ${CF} - 'config' will fail by design (the file uses \${VAR:?} fail-fast); generate it with deploy/init-env.sh on the server"
  fi
else
  warn "no compose file found here - pass --compose <path> if the deploy dir is elsewhere"
fi

# ------------------------------------------------------------
sec "7. Sandbox runtime images present on the host"
# ------------------------------------------------------------
# WHY this section exists (2026-10-27 incident): the sandbox server creates one
#   container per task through the mounted docker.sock, so it can only use images
#   that ALREADY exist on this host. Compose's pull_policy does not constrain that
#   path at all. On a host without Docker Hub access a missing image does not fail
#   fast: the create request burns ~35s (egress readiness_timeout 30s + overhead)
#   and dies as SandboxTimeoutException, which reaches the user as a bare "timeout"
#   with no cause. Measured: node:20-bookworm-slim was absent, every skill run failed.
# The list mirrors two files. When either changes, update this list too:
#   application.yml  helloai.sandbox.opensandbox.spec.image           -> python:3.12
#   application.yml  ...spec-presets.node.image                       -> node:20-bookworm-slim
#   deploy/app/docker-compose.yml  configs[opensandbox-config]
#                    [runtime] execd_image / [egress] image
SANDBOX_IMAGES="python:3.12
node:20-bookworm-slim
sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.1.0
sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/egress:v1.1.7"
if [ "$DOCKER_OK" = "1" ]; then
  SANDBOX_PRESENT="$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null || true)"
  SANDBOX_MISSING=0
  while IFS= read -r img; do
    [ -n "$img" ] || continue
    if printf '%s\n' "$SANDBOX_PRESENT" | grep -qxF "$img"; then
      info "image present: ${img}"
      rec "sandbox_image=${img}"
    else
      SANDBOX_MISSING=$((SANDBOX_MISSING + 1))
      fail "image MISSING: ${img} - skill runs will stall ~35s and report a bare timeout"
    fi
  done <<SANDBOX_IMAGE_LIST
$SANDBOX_IMAGES
SANDBOX_IMAGE_LIST
  if [ "$SANDBOX_MISSING" -gt 0 ]; then
    info "FIX: the opensandbox helper images pull from the aliyun registry:"
    info "       docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.1.0"
    info "       docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/egress:v1.1.7"
    info "     node:20-bookworm-slim lives on Docker Hub (usually unreachable here):"
    info "       docker save it on a working machine, copy it over, then docker load here."
  else
    ok "all sandbox runtime images present"
  fi
  info "NOTE: egress sidecars publish their proxy ports on 0.0.0.0 (e.g. 0.0.0.0:47xxx->8080)."
  info "      The range is configs [docker] port_range_min/max 40000-60000; keep it CLOSED"
  info "      at the cloud security group, otherwise the sandbox egress proxy is public."
else
  warn "docker not usable - cannot verify sandbox runtime images"
fi

# ------------------------------------------------------------
sec "VERDICT"
# ------------------------------------------------------------
if [ "$DOCKER_OK" = "0" ]; then
  fail "docker daemon unreachable - resolve this before planning an upgrade"
fi
printf '  FAIL blockers: %s\n' "$FAILS"
printf '  WARN items (operator judgement): %s\n' "$WARNS"
if [ "$FAILS" -eq 0 ]; then
  printf '  RESULT: no blocker found. Safe to proceed to the maintenance-window steps.\n'
else
  printf '  RESULT: %s blocker(s). Fix them BEFORE touching the Docker packages.\n' "$FAILS"
fi

if [ -n "$REC" ]; then
  {
    printf '# HelloAI Docker upgrade rollback record\n'
    printf '# generated: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo '?')"
    printf '# keep this file OFF the server repo; it is your "what we came from" note.\n'
    if [ "${#REC_LINES[@]}" -gt 0 ]; then
      for l in "${REC_LINES[@]}"; do printf '%s\n' "$l"; done
    fi
  } > "$REC" 2>/dev/null && printf '\n  record written: %s (%s lines)\n' "$REC" "${#REC_LINES[@]}" \
    || printf '\n  [WARN] could not write record to %s\n' "$REC"
fi

[ "$FAILS" -eq 0 ] && exit 0 || exit 1
