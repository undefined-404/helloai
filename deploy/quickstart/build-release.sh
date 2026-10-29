#!/usr/bin/env bash
# ============================================================
# HelloAI - Quickstart release builder
#
# RUN THIS ON A BUILD MACHINE, never on the user's. Needs docker + bash
# (Linux, WSL, or Git Bash). The user only ever receives the OUTPUT.
#
# WHY THE MAIN PATH IS A BUNDLE AND NOT A REGISTRY
#   The repo lives on Gitee and has no ghcr.io / ACR repository by default, so
#   telling users to `docker pull ghcr.io/<owner>/helloai-app:<ver>` only yields
#   404. A tarball costs nothing to distribute (a Gitee Release attachment) and
#   stays byte-identical, whereas a registry costs storage, bandwidth, auth,
#   multi-arch builds and image GC -- none of which this project needs yet.
#   `--push` exists for people who DO have a registry; it is opt-in, never a
#   prerequisite of the quickstart path.
#
# WHY app AND web ARE ONE BUNDLE
#   The jar and the dist are a matched pair: nginx.conf inside the web image
#   proxies to http://app:6565 by service name. Shipping both tags in one tar
#   makes a version skew impossible -- which is the exact failure that the
#   "download a jar here, a dist there" approach used to cause.
#
# OUTPUT (all under <repo>/dist/<version>/)
#   helloai-quickstart-<ver>/              staged bundle: compose + scripts + README
#   helloai-quickstart-<ver>.tar.gz        the bundle itself, a few tens of KB
#   helloai-offline-core-<ver>.tar.gz      app + web + 4 middleware images
#   helloai-offline-sandbox-<ver>.tar.gz   optional: sandbox server + helpers + runtimes
#   IMAGES-<ver>.txt                       image list + image IDs + repo digests
#   SHA256SUMS-<ver>.txt                   checksums for everything above
#
# USAGE
#   ./build-release.sh --version 0.1.0
#   ./build-release.sh --version 0.1.0 --no-build        # reuse existing local tags
#   ./build-release.sh --version 0.1.0 --skip-sandbox    # core bundle only
#   ./build-release.sh --version 0.1.0 --push registry.cn-hangzhou.aliyuncs.com/yourns
#
# ASCII-only file (repo convention for deploy artifacts).
# ============================================================
set -euo pipefail

VERSION=""
PUSH_PREFIX=""
NO_BUILD=0
SKIP_SANDBOX=0

log()  { printf '[release] %s\n' "$*"; }
warn() { printf '[release][warn] %s\n' "$*" >&2; }
die()  { printf '[release][error] %s\n' "$*" >&2; exit 1; }

usage() {
  awk 'NR==1{next} /^# =+$/{ if (seen++) exit; next } {print}' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version)      VERSION="${2:-}"; shift 2 ;;
    --push)         PUSH_PREFIX="${2:-}"; shift 2 ;;
    --no-build)     NO_BUILD=1; shift ;;
    --skip-sandbox) SKIP_SANDBOX=1; shift ;;
    -h|--help)      usage ;;
    *)              die "unknown option: $1 (try --help)" ;;
  esac
done

[ -n "$VERSION" ] || die "--version is required, e.g. --version 0.1.0"
case "$VERSION" in
  *[!A-Za-z0-9._-]*) die "version '$VERSION' may only contain letters, digits, dot, dash, underscore" ;;
  latest) die "'latest' is a moving tag and is rejected on purpose: a bundle must pin an exact version" ;;
esac

QS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$QS_DIR/../.." && pwd)"
OUT="$ROOT/dist/$VERSION"

APP_TAG="helloai-app:$VERSION"
WEB_TAG="helloai-web:$VERSION"

# In the bundle, .env points at whatever the consumer can actually reach:
#   with --push  -> the registry reference (they can pull)
#   without      -> the local tag (they must docker load the offline archive)
APP_ENV_REF="$APP_TAG"
WEB_ENV_REF="$WEB_TAG"
if [ -n "$PUSH_PREFIX" ]; then
  APP_ENV_REF="$PUSH_PREFIX/helloai-app:$VERSION"
  WEB_ENV_REF="$PUSH_PREFIX/helloai-web:$VERSION"
fi

# Middleware is version-pinned EXCEPT minio, which is :latest across all five
#   compose files in this repo (pre-existing, not introduced here). Keeping the
#   tag identical to the rest of the repo matters more than pinning it in one
#   place, so the digest goes into IMAGES-<ver>.txt instead -- that is what makes
#   a bundle reproducible even when the tag floats.
CORE_MIDDLEWARE="pgvector/pgvector:0.8.0-pg16 redis:7.2.5-alpine rabbitmq:3.12.14-management-alpine minio/minio:latest"

SANDBOX_IMAGES="opensandbox/server:release-1.1.0
sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.1.0
sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/egress:v1.1.7
python:3.12
node:20-bookworm-slim"

BUNDLE_FILES="docker-compose.yml .env.example start.sh stop.sh start.ps1 stop.ps1 README.md rejoin.sh"

# ---------------------------------------------------------------- helpers
image_exists() { docker image inspect "$1" >/dev/null 2>&1; }

if command -v sha256sum >/dev/null 2>&1; then
  hash_cmd() { sha256sum "$@"; }
else
  hash_cmd() { shasum -a 256 "$@"; }
fi

preflight() {
  command -v docker >/dev/null 2>&1 || die "docker not found in PATH"
  docker info >/dev/null 2>&1 || die "cannot reach the docker daemon"
  [ -f "$ROOT/Dockerfile" ] || die "Dockerfile not found at $ROOT/Dockerfile"
  local f
  for f in $BUNDLE_FILES; do
    [ -f "$QS_DIR/$f" ] || die "bundle file missing: $QS_DIR/$f"
  done
  [ -f "$ROOT/deploy/pull-sandbox-images.sh" ] || die "deploy/pull-sandbox-images.sh missing (the bundle must ship it)"
  docker buildx version >/dev/null 2>&1 || warn "buildx not available; falling back to the classic builder"
}

# ---------------------------------------------------------------- build
build_images() {
  if [ "$NO_BUILD" -eq 1 ]; then
    log "--no-build: reusing $APP_TAG and $WEB_TAG"
    image_exists "$APP_TAG" || die "--no-build was given but $APP_TAG does not exist"
    image_exists "$WEB_TAG" || die "--no-build was given but $WEB_TAG does not exist"
    return 0
  fi
  log "building $APP_TAG (multi-stage: maven + jdk17)"
  docker build -f "$ROOT/Dockerfile" --target app -t "$APP_TAG" "$ROOT"
  log "building $WEB_TAG (multi-stage: node + nginx)"
  docker build -f "$ROOT/Dockerfile" --target web -t "$WEB_TAG" "$ROOT"
}

ensure_core_images() {
  local img
  for img in $CORE_MIDDLEWARE; do
    if image_exists "$img"; then
      continue
    fi
    log "pulling $img"
    docker pull "$img" || die "could not pull $img"
  done
}

ensure_sandbox_images() {
  log "verifying sandbox images on this host"
  if ! bash "$ROOT/deploy/pull-sandbox-images.sh"; then
    die "sandbox images incomplete -- re-run deploy/pull-sandbox-images.sh, or use --skip-sandbox"
  fi
}

# ---------------------------------------------------------------- stage bundle
stage_bundle() {
  local stage="$OUT/helloai-quickstart-$VERSION"
  log "staging bundle -> $stage"
  rm -rf "$stage"
  mkdir -p "$stage"

  local f
  for f in $BUNDLE_FILES; do
    cp "$QS_DIR/$f" "$stage/$f"
  done
  # start.sh / start.ps1 look for this next to themselves first, then in deploy/.
  cp "$ROOT/deploy/pull-sandbox-images.sh" "$stage/pull-sandbox-images.sh"

  # Pin the two image lines to this release. sed rather than a full rewrite so
  #   the surrounding comments stay single-sourced with the repo copy.
  sed -e "s|^HELLOAI_APP_IMAGE=.*|HELLOAI_APP_IMAGE=$APP_ENV_REF|" \
      -e "s|^HELLOAI_WEB_IMAGE=.*|HELLOAI_WEB_IMAGE=$WEB_ENV_REF|" \
      "$QS_DIR/.env.example" > "$stage/.env.example"

  {
    printf 'name: helloai-quickstart\n'
    printf 'version: %s\n' "$VERSION"
    printf 'built_at_utc: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'app_image: %s\n' "$APP_TAG"
    printf 'web_image: %s\n' "$WEB_TAG"
    printf 'sandbox_bundled: %s\n' "$([ "$SKIP_SANDBOX" -eq 1 ] && printf 'no' || printf 'yes')"
    printf 'registry: %s\n' "${PUSH_PREFIX:-(none; use the offline archive)}"
  } > "$stage/RELEASE"

  # Executable bits matter: a tar that unpacks start.sh without +x is a support
  #   ticket. cp preserves them from git, but be explicit anyway.
  chmod 755 "$stage"/*.sh || true

  tar -C "$OUT" -czf "$OUT/helloai-quickstart-$VERSION.tar.gz" "helloai-quickstart-$VERSION"
  log "bundle: helloai-quickstart-$VERSION.tar.gz"
}

# ---------------------------------------------------------------- offline tars
save_core() {
  local out="$OUT/helloai-offline-core-$VERSION.tar.gz"
  log "saving core images -> $(basename "$out") (this takes a few minutes)"
  # shellcheck disable=SC2086
  docker save "$APP_TAG" "$WEB_TAG" $CORE_MIDDLEWARE | gzip -1 > "$out"
  log "core archive: $(du -h "$out" | cut -f1)"
}

save_sandbox() {
  if [ "$SKIP_SANDBOX" -eq 1 ]; then
    log "--skip-sandbox: not producing the sandbox archive"
    return 0
  fi
  local out="$OUT/helloai-offline-sandbox-$VERSION.tar.gz"
  log "saving sandbox images -> $(basename "$out") (python:3.12 alone is ~1 GB)"
  # shellcheck disable=SC2086
  docker save $SANDBOX_IMAGES | gzip -1 > "$out"
  log "sandbox archive: $(du -h "$out" | cut -f1)"
}

# ---------------------------------------------------------------- manifest
write_manifest() {
  local f="$OUT/IMAGES-$VERSION.txt" img
  log "writing $(basename "$f")"
  {
    printf '# HelloAI %s - image manifest\n' "$VERSION"
    printf '# built_at_utc: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '#\n'
    printf '# minio/minio:latest floats by repo convention; the repo digest below is\n'
    printf '#   what pins THIS bundle to a specific image build.\n'
    printf '#\n'
    printf '## helloai images\n'
    for img in "$APP_TAG" "$WEB_TAG"; do
      printf '%s\n' "$img"
      printf '  id: %s\n' "$(docker image inspect --format '{{.Id}}' "$img")"
      printf '  size: %s\n' "$(docker image inspect --format '{{.Size}}' "$img")"
    done
    printf '## middleware\n'
    for img in $CORE_MIDDLEWARE; do
      printf '%s\n' "$img"
      printf '  id: %s\n' "$(docker image inspect --format '{{.Id}}' "$img")"
      printf '  digest: %s\n' "$(docker image inspect --format '{{join .RepoDigests ","}}' "$img")"
    done
    if [ "$SKIP_SANDBOX" -eq 0 ]; then
      printf '## sandbox\n'
      for img in $SANDBOX_IMAGES; do
        printf '%s\n' "$img"
        printf '  id: %s\n' "$(docker image inspect --format '{{.Id}}' "$img")"
        printf '  digest: %s\n' "$(docker image inspect --format '{{join .RepoDigests ","}}' "$img")"
      done
    fi
  } > "$f"
}

write_checksums() {
  local f="$OUT/SHA256SUMS-$VERSION.txt" p
  log "writing $(basename "$f")"
  : > "$f"
  for p in "$OUT"/*; do
    [ -f "$p" ] || continue
    case "$p" in
      *SHA256SUMS-*) continue ;;
    esac
    ( cd "$OUT" && hash_cmd "$(basename "$p")" >> "$(basename "$f")" )
  done
}

# ---------------------------------------------------------------- optional push
push_images() {
  if [ -z "$PUSH_PREFIX" ]; then
    return 0
  fi
  local app_ref="$PUSH_PREFIX/helloai-app:$VERSION"
  local web_ref="$PUSH_PREFIX/helloai-web:$VERSION"
  log "pushing $app_ref"
  docker tag "$APP_TAG" "$app_ref" || die "could not tag $app_ref"
  docker push "$app_ref" || die "push failed for $app_ref"
  log "pushing $web_ref"
  docker tag "$WEB_TAG" "$web_ref" || die "could not tag $web_ref"
  docker push "$web_ref" || die "push failed for $web_ref"
}

# ---------------------------------------------------------------- main
log "version=$VERSION root=$ROOT"
preflight
mkdir -p "$OUT"

build_images
ensure_core_images
if [ "$SKIP_SANDBOX" -eq 0 ]; then
  ensure_sandbox_images
fi

stage_bundle
save_core
save_sandbox
write_manifest
write_checksums
push_images

cat <<EOF

================================================================
Release $VERSION is ready: $OUT

  Distribute (in this order of preference):

  1. Offline bundle -- works with zero network and no registry:
       helloai-quickstart-$VERSION.tar.gz
       helloai-offline-core-$VERSION.tar.gz
     The user runs:
       tar xzf helloai-quickstart-$VERSION.tar.gz
       cd helloai-quickstart-$VERSION
       ./start.sh --offline          (or: .\\start.ps1 --offline)

  2. Registry -- only if --push was used:
       docker compose ... with HELLOAI_APP_IMAGE=$APP_ENV_REF

  Verify a download with:
       $( [ -f "$OUT/SHA256SUMS-$VERSION.txt" ] && printf 'sha256sum -c SHA256SUMS-%s.txt' "$VERSION" )

  Attach all of the above to a Gitee Release tagged $VERSION.
================================================================
EOF
