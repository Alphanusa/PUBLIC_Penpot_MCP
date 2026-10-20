#!/usr/bin/env bash
#
# Snapshot Penpot's MCP (the `mcp/` folder of the Penpot monorepo) into a
# target directory as a standalone project.
#
# Usage: sync-upstream.sh <target-dir>
#
# Environment:
#   UPSTREAM_REPO  git URL of the Penpot monorepo (default: penpot/penpot on GitHub)
#   UPSTREAM_REF   branch, tag or commit SHA to sync from (default: develop)
#   GITHUB_OUTPUT  when set (GitHub Actions), outputs are appended here
#
# What ends up in <target-dir>:
#   - contents of upstream `mcp/`              -> <target-dir>/
#   - upstream `plugins/libs/plugin-types/`    -> <target-dir>/vendor/plugin-types/
#   - upstream root `LICENSE`                  -> <target-dir>/LICENSE
#   - `.upstream-sha` with the synced upstream commit
#
# Adjustments so it builds outside the monorepo (kept minimal on purpose):
#   - the plugin-types `link:` in packages/plugin/package.json and
#     pnpm-lock.yaml points to vendor/plugin-types
#   - `storeDir: ../.pnpm-store` is removed from pnpm-workspace.yaml
#
# The target directory is overwritten (files removed upstream are removed),
# except for `.git`. Nothing is committed here; the caller decides.
#
# Outputs (stdout and GITHUB_OUTPUT):
#   changed=true|false   whether the content differs from what was there
#   sha=<full sha>       upstream commit that was synced
#   short=<short sha>
#   previous=<sha>       previously synced upstream commit (may be empty)

set -euo pipefail

TARGET=${1:?usage: sync-upstream.sh <target-dir>}
UPSTREAM_REPO=${UPSTREAM_REPO:-https://github.com/penpot/penpot.git}
UPSTREAM_REF=${UPSTREAM_REF:-develop}

OLD_LINK='link:../../../plugins/libs/plugin-types'
NEW_LINK='link:../../vendor/plugin-types'

TARGET=$(cd "$TARGET" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

log() { echo "[sync-upstream] $*" >&2; }

output() {
  echo "$1=$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "$1=$2" >> "$GITHUB_OUTPUT"
  fi
}

# --- 1. fetch only what we need from upstream -----------------------------

log "fetching $UPSTREAM_REPO @ $UPSTREAM_REF"
SRC="$WORK/penpot"
git init -q "$SRC"
git -C "$SRC" remote add origin "$UPSTREAM_REPO"
git -C "$SRC" sparse-checkout set --cone mcp plugins/libs/plugin-types
git -C "$SRC" fetch -q --depth 1 --filter=blob:none origin "$UPSTREAM_REF"
git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD

SHA=$(git -C "$SRC" rev-parse HEAD)
SHORT=$(git -C "$SRC" rev-parse --short=7 HEAD)
PREVIOUS=$(cat "$TARGET/.upstream-sha" 2>/dev/null || true)
log "upstream commit: $SHA (previous: ${PREVIOUS:-none})"

for required in mcp/package.json plugins/libs/plugin-types/package.json LICENSE; do
  if [[ ! -f "$SRC/$required" ]]; then
    log "ERROR: upstream is missing $required — layout changed, update this script"
    exit 1
  fi
done

# --- 2. build the snapshot in a scratch dir ---------------------------------

SNAP="$WORK/snapshot"
mkdir -p "$SNAP/vendor"
cp -a "$SRC/mcp/." "$SNAP/"
cp -a "$SRC/plugins/libs/plugin-types" "$SNAP/vendor/plugin-types"
cp "$SRC/LICENSE" "$SNAP/LICENSE"

# relink plugin-types to the vendored copy
for f in packages/plugin/package.json pnpm-lock.yaml; do
  if [[ -f "$SNAP/$f" ]]; then
    sed -i "s#${OLD_LINK}#${NEW_LINK}#g" "$SNAP/$f"
  fi
done

# drop the store dir that points outside the repo
if [[ -f "$SNAP/pnpm-workspace.yaml" ]]; then
  sed -i '/^storeDir:[[:space:]]*\.\.\//d' "$SNAP/pnpm-workspace.yaml"
fi

# fail loudly if anything in the package graph still reaches outside the repo
LEFTOVER=$(grep -rnE '(link|file|workspace):\.\./\.\./\.\./' \
  --include=package.json --include=pnpm-lock.yaml --include=pnpm-workspace.yaml \
  "$SNAP" 2>/dev/null | grep -v '/node_modules/' || true)
if [[ -n "$LEFTOVER" ]]; then
  log "ERROR: dependencies still point outside the repo:"
  echo "$LEFTOVER" >&2
  log "update the adjustments in this script"
  exit 1
fi

# --- 3. compare with the target and replace its content ---------------------

CHANGED=true
if diff -rq --exclude=.git --exclude=.upstream-sha "$SNAP" "$TARGET" >/dev/null 2>&1; then
  CHANGED=false
fi

if [[ "$CHANGED" == "true" ]]; then
  log "content changed, updating $TARGET"
  find "$TARGET" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  cp -a "$SNAP/." "$TARGET/"
  echo "$SHA" > "$TARGET/.upstream-sha"
else
  log "no content changes since ${PREVIOUS:-last sync}"
fi

output changed "$CHANGED"
output sha "$SHA"
output short "$SHORT"
output previous "$PREVIOUS"
