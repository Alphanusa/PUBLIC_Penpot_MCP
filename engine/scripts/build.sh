#!/usr/bin/env bash
#
# Builds the engine:
#   1. render-wasm for the headless (export) target, with Penpot's own build
#      script — needs Rust (rustup), emsdk at $EMSDK_DIR (default /opt/emsdk),
#      pnpm and jq;
#   2. the ClojureScript engine with shadow-cljs — needs Java + Clojure CLI;
#   3. copies the wasm artifacts to dist/wasm and Penpot's builtin font
#      (Source Sans Pro TTFs) to dist/fonts.
#
# Output: engine/dist/engine.js, dist/wasm/render-wasm.{js,wasm}, dist/fonts/*.ttf

set -euo pipefail

ENGINE=$(cd "$(dirname "$0")/.." && pwd)
PENPOT="$ENGINE/.penpot"
EMSDK_DIR=${EMSDK_DIR:-/opt/emsdk}

if [[ ! -d "$PENPOT/render-wasm" || ! -d "$PENPOT/frontend/resources/fonts" ]]; then
  bash "$ENGINE/scripts/fetch-penpot.sh"
fi

# Penpot's render-wasm/build sources /opt/emsdk/emsdk_env.sh directly.
if [[ "$EMSDK_DIR" != "/opt/emsdk" && ! -e /opt/emsdk ]]; then
  ln -s "$EMSDK_DIR" /opt/emsdk
fi

echo "[engine] building render-wasm (export target)"
(
  cd "$PENPOT/render-wasm"
  NODE_ENV=production ./build export
)

echo "[engine] building ClojureScript engine"
(
  cd "$ENGINE"
  rm -rf dist .shadow-cljs
  clojure -M:dev:shadow-cljs release engine
)

mkdir -p "$ENGINE/dist/wasm" "$ENGINE/dist/fonts"
cp "$PENPOT/exporter/resources/wasm/"render-wasm.* "$ENGINE/dist/wasm/"
cp "$PENPOT/frontend/resources/fonts/"sourcesanspro-*.ttf "$ENGINE/dist/fonts/"
git -C "$PENPOT" rev-parse HEAD > "$ENGINE/dist/PENPOT_SHA"
ls -la "$ENGINE/dist" "$ENGINE/dist/wasm" "$ENGINE/dist/fonts"
