#!/usr/bin/env bash
# ============================================================================
# HelloAI - one-command installer
#
#   curl -fsSL https://gitee.com/undefined_404/helloai/raw/master/deploy/install.sh | bash
#
# What "one command" means here: the script fetches the quickstart bundle from
# THIS repository (a source archive of a ref), extracts the 9 files it needs,
# then hands over to start.sh. No separate tarball host, no extra image, no
# `git clone` on the user's machine -- the repo is the distribution channel,
# which is exactly how most OSS projects do it.
#
# WHY AN ARCHIVE AND NOT RAW FILE-BY-FILE FETCHES
#   Raw fetching needs one request per file and silently succeeds on an HTML
#   login page (Gitee returns 200 for those). A tar.gz either extracts or it
#   does not -- one failure mode instead of nine.
#
# ARGUMENTS / ENV
#   bash -s -- --with-sandbox        everything after `--` goes to start.sh
#   HELLOAI_REF=v0.2.0               pin a tag instead of the moving branch
#   HELLOAI_INSTALL_DIR=$HOME/hai    where to unpack (default ./helloai)
#   HELLOAI_REPO=<url>               mirror / fork
#
# PREREQ
#   The repository must be readable ANONYMOUSLY. A private repo makes this
#   404 -- check the URL in an incognito window before publishing the command.
# ============================================================================
set -euo pipefail

REPO="${HELLOAI_REPO:-https://gitee.com/undefined_404/helloai}"
# Default to the branch on purpose: a tag is the right default for a RELEASE,
# but this repo's existing tags (v1.0.0) predate deploy/quickstart, so pointing
# at them would download a tree that has no installer to run. Pin a tag once
# 0.2.0 is tagged: HELLOAI_REF=v0.2.0.
REF="${HELLOAI_REF:-master}"
TARGET="${HELLOAI_INSTALL_DIR:-$PWD/helloai}"

log() { printf '[helloai] %s\n' "$*"; }
die() { printf '[helloai][error] %s\n' "$*" >&2; exit 1; }

# --- 1. Preflight ------------------------------------------------------------
# Fail before the download, not after: a missing docker is the single most
# common reason this script cannot finish, and telling the user that AFTER a
# 30 MB download is just rude.
command -v docker >/dev/null 2>&1 \
  || die "docker not found. Install Docker first: https://docs.docker.com/get-docker/"
docker compose version >/dev/null 2>&1 \
  || die "docker compose (v2) not found. Install the compose plugin."
docker info >/dev/null 2>&1 \
  || die "cannot talk to the Docker daemon. Is it running? (Linux: is your user in the docker group?)"

# --- 2. Fetch -----------------------------------------------------------------
# Candidates cover the two realistic packaging forms, cheapest first:
#   a) Gitee/GitHub RELEASE asset  -- the quickstart tarball, ~30 KB
#   b) source archive of a ref    -- the whole repo, ~22 MB (doc/ included)
# (a) is tried first because it is the intended release shape: it downloads
# 700x less and its name is pinned. If no release exists yet the request 404s
# in milliseconds and we fall through to (b), so this costs nothing today.
CANDIDATES=(
  "$REPO/releases/download/$REF/helloai-quickstart-${REF#v}.tar.gz"   # Gitee/GitHub release asset
  "$REPO/repository/archive/$REF.tar.gz"        # Gitee source archive
  "$REPO/archive/refs/tags/$REF.tar.gz"         # GitHub tag
  "$REPO/archive/refs/heads/$REF.tar.gz"        # GitHub branch
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

log "fetching $REPO @ $REF"
ok=0
for url in "${CANDIDATES[@]}"; do
  if curl -fsSL "$url" -o "$TMP/src.tar.gz" 2>/dev/null && tar tzf "$TMP/src.tar.gz" >/dev/null 2>&1; then
    ok=1
    break
  fi
done
[ "$ok" = "1" ] || die "cannot download the source archive. Check that:
    - the repository is PUBLIC (open $REPO in an incognito window), and
    - the ref '$REF' exists (HELLOAI_REF=<tag-or-branch>)."

tar xzf "$TMP/src.tar.gz" -C "$TMP"

# --- 3. Locate the bundle -----------------------------------------------------
# Layout differs between the two fetch forms, so anchor on `start.sh` + its
# neighbour `docker-compose.yml` instead of on a fixed path:
#   release asset : <ver>/start.sh                        (depth 2)
#   source archive: <repo>-<ref>/deploy/quickstart/start.sh (depth 4)
# Anchoring on the file ALSO rejects a Gitee HTML login page saved as .tar.gz:
# nothing extracts, nothing is found, and the error below says why.
# `sed -n 1p` instead of `| head -1` on purpose: with `pipefail` an early-closed
# pipe aborts the whole script under `set -e`.
START_FILE="$(find "$TMP" -maxdepth 4 -type f -name start.sh | sed -n '1p')"
QS="$(dirname "${START_FILE:-/nonexistent}")"
[ -n "$START_FILE" ] && [ -f "$QS/docker-compose.yml" ] || die "this ref ($REF) does not ship the quickstart bundle.
    Pin a tag/release that contains it: HELLOAI_REF=v0.2.0"

# --- 4. Lay down the files ----------------------------------------------------
mkdir -p "$TARGET"
cp -R "$QS"/. "$TARGET"/
# start.sh looks for pull-sandbox-images.sh next to itself first. Its position
# depends on the fetch form, so check both and stay quiet when neither matches
# (the script only matters for --with-sandbox):
#   release asset  -> inside the bundle  ($QS/...)
#   source archive -> sibling of quickstart/ ($QS/../...)
for cand in "$QS/pull-sandbox-images.sh" "$(dirname "$QS")/pull-sandbox-images.sh"; do
  [ -f "$cand" ] && { cp -f "$cand" "$TARGET/pull-sandbox-images.sh"; break; }
done

# build-release.sh is a repo-side tool (builds images, docker-saves offline
# tarballs). On a user's machine it only suggests they are meant to build.
rm -f "$TARGET/build-release.sh"

chmod +x "$TARGET"/*.sh 2>/dev/null || true

# .env is never written here. start.sh generates it from .env.example and only
# fills EMPTY values, so re-running this installer upgrades compose/scripts
# while keeping the AES key that decrypts every stored LLM credential.
[ -f "$TARGET/.env" ] && log "reusing existing .env (secrets kept)"

log "bundle unpacked to $TARGET"
cd "$TARGET"
exec ./start.sh "$@"
