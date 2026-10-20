#!/usr/bin/env bash
#
# Fetches the Penpot sources the engine is built from (common, exporter,
# render-wasm) at the upstream commit this repo is synced to (.upstream-sha),
# into engine/.penpot. Sparse + shallow: only those folders are downloaded.
#
# Env: UPSTREAM_REPO (default penpot/penpot), PENPOT_REF (default: .upstream-sha)

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/engine/.penpot"
UPSTREAM_REPO=${UPSTREAM_REPO:-https://github.com/penpot/penpot.git}
REF=${PENPOT_REF:-$(cat "$ROOT/.upstream-sha")}

if [[ -d "$DEST/.git" ]] && [[ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null)" == "$REF" ]]; then
  echo "[fetch-penpot] $DEST already at $REF"
  exit 0
fi

rm -rf "$DEST"
git init -q "$DEST"
git -C "$DEST" remote add origin "$UPSTREAM_REPO"
git -C "$DEST" sparse-checkout set --cone common exporter render-wasm
git -C "$DEST" fetch -q --depth 1 --filter=blob:none origin "$REF"
git -C "$DEST" -c advice.detachedHead=false checkout -q FETCH_HEAD
echo "[fetch-penpot] penpot sources at $(git -C "$DEST" rev-parse HEAD) in $DEST"
