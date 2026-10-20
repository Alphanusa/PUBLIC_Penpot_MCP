# Engine — Penpot without a browser

A small ClojureScript library, compiled to an ESM Node module, that runs
**Penpot's own code** on the server so the MCP can read, render and (next)
edit designs without anyone opening Penpot in a browser:

- **`common`** (Penpot's shared data model, geometry and layout code) for the data;
- **render-wasm** (Penpot's Skia renderer compiled to WebAssembly) for text layout
  and rendering, exactly as Penpot's exporter does headlessly.

Penpot's exporter headless pieces (`app.wasm`, `app.wasm.serialize`, `app.wasm.text`)
are reused **unchanged**. Only [`src/penpot_mcp/engine.cljs`](src/penpot_mcp/engine.cljs)
is ours; it differs from the exporter in three ways:

| | Exporter | Engine |
|---|---|---|
| Auth | internal management key + session | the user's access token (`Authorization: Token …`) |
| Backend URI | process config | passed per call |
| Output | temp files | bytes returned to the caller |

## JS API

```js
import * as engine from "./dist/engine.js";

await engine.init("dist/wasm");                    // load render-wasm once

const png = await engine.renderShape({             // Uint8Array
  baseUri: "https://penpot.example.com",
  token: "<user access token>",
  fileId, pageId, objectId,
  scale: 1, format: "png",                         // png | jpeg | webp | svg | pdf
});

const profile = await engine.rpc(                  // any backend RPC command
  { baseUri, token }, "get-profile", {});

const demo = await engine.renderDemo();            // offline: synthetic frame → PNG
```

## Building

The build needs Java 25, the Clojure CLI, Rust (rustup), emsdk 4.0.6, pnpm and jq
— see [`.github/workflows/engine.yml`](../.github/workflows/engine.yml), which is the
reference build. Locally:

```bash
bash engine/scripts/fetch-penpot.sh   # Penpot sources at .upstream-sha → engine/.penpot
cd engine && npm install
bash scripts/build.sh                 # render-wasm (export target) + shadow-cljs release
node test/smoke.mjs                   # offline render → test/out/demo.png
```

Every push that touches `engine/` builds in CI and publishes the result to the
**`engine-dist`** branch (`dist/` + smoke output), and the build logs to
**`engine-logs`**. To use a CI build without the toolchain:

```bash
git fetch origin engine-dist
git archive origin/engine-dist dist | tar -x -C engine/
```

## Tests

- `test/smoke.mjs` — offline. Boots render-wasm and renders a synthetic frame
  (rounded rect + circle) to a 640×400 PNG. Runs in CI.
- `test/live.mjs` — against a real Penpot. Needs `PENPOT_URL` and `PENPOT_TOKEN`
  (access token of a **test** account); optional `FILE_ID`, `PAGE_ID`, `OBJECT_ID`,
  otherwise it renders the first board of the first file it finds.

## Spike results (2026-10-20, Penpot `be2e117`)

| | |
|---|---|
| render-wasm build (CI, cold) | ~2 min |
| shadow-cljs release | ~25 s, 0 warnings |
| `dist/engine.js` | 7.7 MB (+ 10.3 MB `render-wasm.wasm`) |
| `init` (load wasm) | ~180 ms |
| render 320×200 frame @2× | ~60–90 ms |
| process RSS after render | ~230 MB — one shared worker, not per user |

Offline render verified visually (fills, corner radius, positions, 2× scale).

## Next

1. Live round-trip against the team's Penpot: `get-page` → render a real board
   (text with real fonts, images) with an access token.
2. Edits: build changes with `common` (`changes-builder`, layout modifiers), send
   them with `update-file`, re-render to verify.
3. Wire into the MCP server as a headless dispatcher, so `export_shape` and the
   structured tools work when no plugin/browser is connected.
