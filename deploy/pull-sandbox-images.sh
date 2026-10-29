#!/usr/bin/env bash
# ============================================================
# HelloAI - pull (or verify) the images the sandbox needs on THIS host
#
# WHY this script exists:
#   The sandbox server creates one container per task through the mounted
#   docker.sock, so it can only use images that ALREADY exist on this host.
#   Compose's pull_policy does not constrain that path. A missing business image
#   does not fail fast either: the create request stalls ~35s (egress readiness
#   30s + overhead) and dies as SandboxTimeoutException, which the user sees as a
#   bare "timeout" with no cause. `docker compose up -d` therefore never pulls
#   these images (profiles keep them out) -- run this script instead.
#
# TWO kinds of image, with DIFFERENT registries:
#   1. helper images (execd / egress)  -> Alibaba Cloud ACR, usually reachable
#   2. business images (python / node) -> Docker Hub, often NOT reachable on CN
#      hosts; if the pull fails, import them offline (see the hint at the end).
#
# Usage:
#   bash deploy/pull-sandbox-images.sh
#   bash deploy/pull-sandbox-images.sh --check-only        # verify, never pull
#   SANDBOX_IMAGE_MIRROR=docker.m.daocloud.io/library/ \
#     bash deploy/pull-sandbox-images.sh                   # pull via a mirror
#
# Exit code: 0 = every required image present; 1 = at least one missing.
#
# NOTE: ASCII-only file (repo convention for deploy artifacts: this output may be
#       pasted into a terminal whose locale is C/POSIX).
# ============================================================
set -uo pipefail

CHECK_ONLY=0
[ "${1:-}" = "--check-only" ] && CHECK_ONLY=1

# Business images: the sandbox server looks them up BY THIS EXACT NAME
# (application.yml: helloai.sandbox.opensandbox.spec.image / spec-presets.node.image).
BUSINESS_IMAGES="python:3.12 node:20-bookworm-slim"

# Helper images: injected into every sandbox; must match
# deploy/app/docker-compose.yml configs[opensandbox-config] ([runtime] execd_image /
# [egress] image) and docker-compose.server.yml.
HELPER_IMAGES="sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.1.0 sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/egress:v1.1.7"

MISSING=0

have() {
  command -v "$1" >/dev/null 2>&1 || return 1
}

image_present() {
  docker image inspect "$1" >/dev/null 2>&1
}

# Pull one image, optionally through a mirror prefix, and tag it back to the
# canonical name the sandbox server expects (mirror pulls arrive as
# "<mirror>library/node:20-bookworm-slim", which the server cannot find).
pull_one() {
  local target="$1"
  local mirror="${SANDBOX_IMAGE_MIRROR:-}"
  if [ -n "$mirror" ]; then
    local src="${mirror}${target}"
    printf '  pulling via mirror: %s\n' "$src"
    if docker pull "$src"; then
      docker tag "$src" "$target" && {
        printf '  tagged as: %s\n' "$target"
        return 0
      }
    fi
    printf '  mirror pull failed, falling back to the canonical name\n'
  fi
  printf '  pulling: %s\n' "$target"
  docker pull "$target"
}

check_group() {
  local label="$1"
  local images="$2"
  local img
  printf '\n== %s ==\n' "$label"
  for img in $images; do
    if image_present "$img"; then
      printf '  [ OK ] %s\n' "$img"
      continue
    fi
    if [ "$CHECK_ONLY" = "1" ]; then
      printf '  [MISS] %s\n' "$img"
      MISSING=$((MISSING + 1))
      continue
    fi
    if pull_one "$img" && image_present "$img"; then
      printf '  [ OK ] %s (pulled)\n' "$img"
    else
      printf '  [FAIL] %s\n' "$img"
      MISSING=$((MISSING + 1))
    fi
  done
}

if ! have docker; then
  printf '[FAIL] docker not found in PATH\n' >&2
  exit 1
fi
# `docker ps` rather than `docker info`: some restricted/rootless setups answer the
# former but trip the latter on daemon-wide info, which would abort the whole script.
if ! docker ps >/dev/null 2>&1; then
  printf '[FAIL] cannot talk to the docker daemon (permission or not running)\n' >&2
  exit 1
fi

printf 'Sandbox image check%s\n' "$([ "$CHECK_ONLY" = "1" ] && printf ' (check only)' || true)"
check_group "helper images (aliyun ACR)" "$HELPER_IMAGES"
check_group "business images (Docker Hub)" "$BUSINESS_IMAGES"

printf '\n== RESULT ==\n'
if [ "$MISSING" -eq 0 ]; then
  printf '  All sandbox images present. Skill execution / diagram rendering is ready.\n'
  exit 0
fi

printf '  %s image(s) missing.\n' "$MISSING"
cat <<'HINT'

  How to fix a business image (python / node) on a host without Docker Hub access:

    a) Through a reachable mirror (choose one that works for you):
         SANDBOX_IMAGE_MIRROR=docker.m.daocloud.io/library/ \
           bash deploy/pull-sandbox-images.sh

    b) Offline import (most reliable):
         # on a machine that HAS the image
         docker save node:20-bookworm-slim | gzip -1 > node20.tgz
         # copy node20.tgz to this host, then
         gunzip -c node20.tgz | docker load
         docker images | grep "node:20-bookworm-slim"     # name must match exactly

  The helper images (execd / egress) come from the aliyun registry and normally
  pull without help; if those fail too, mirror them the same way.

  Why the exact tag matters: the sandbox server resolves the image by the name in
  application.yml (`spec-presets.node.image = node:20-bookworm-slim`). An image
  imported under any other name is invisible to it.
HINT

exit 1
