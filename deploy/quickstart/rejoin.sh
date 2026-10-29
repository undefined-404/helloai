#!/usr/bin/env bash
# ============================================================
# HelloAI - rejoin Gitee Release split pieces back into tarballs
#
# Gitee Release attachments cap at 100 MB per file, so the two big
# offline bundles are published as 95 MB pieces named
#   helloai-offline-{core,sandbox}-<VER>-part-NNN
# Run this in the directory where you downloaded the pieces:
#   ./rejoin.sh <VER>
# e.g.
#   ./rejoin.sh 0.2.0
# then verify:
#   sha256sum -c SHA256SUMS-<VER>.txt
#
# ASCII-only (repo convention for deploy artifacts).
# ============================================================
set -euo pipefail

VER="${1:?usage: $0 <version>  (e.g. $0 0.2.0)}"
cd "$(dirname "$0")"

parts=$(ls helloai-offline-*-part-* 2>/dev/null || true)
if [ -z "$parts" ]; then
  echo "no helloai-offline-*-part-* files found in this directory."
  echo "download the pieces next to this script first."
  exit 1
fi

COUNT=0
for base in $(printf '%s\n' $parts | sed 's/-part-[0-9]*$//' | sort -u); do
  echo "rejoining $base ..."
  cat "${base}-part-"* > "${base}.tar.gz"
  rm -f "${base}-part-"*
  COUNT=$((COUNT + 1))
done

echo "rejoined $COUNT bundle(s)."
echo "verify download integrity:  sha256sum -c SHA256SUMS-${VER}.txt"
echo "then: tar xzf helloai-quickstart-${VER}.tar.gz && cd helloai-quickstart-${VER}"