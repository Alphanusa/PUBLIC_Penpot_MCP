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

await engine.init("dist/wasm", "dist/fonts");      // load render-wasm (+ builtin fonts) once

const ctx = { baseUri: "https://penpot.example.com", token: "<access token>" };

// read
await engine.rpc(ctx, "get-profile", {});          // any backend RPC command → plain JS
await engine.pageSummary({ ...ctx, fileId, pageId, rootId, depth: 4 }); // compact layer tree

// render (Uint8Array)
await engine.renderShape({ ...ctx, fileId, pageId, objectId, scale: 1, format: "png" });

// edit: ops → Penpot changes → update-file (one save)
await engine.applyOps({ ...ctx, fileId, pageId, dryRun: false, ops: [
  { op: "create", type: "frame", ref: "card", x: 0, y: 0, width: 320, height: 10, fill: "#ffffff",
    layout: { direction: "column", gap: 8, padding: 16 }, sizing: { v: "hug" } },
  { op: "create", type: "text", parent: "card", text: "Hello", fontSize: 24, fontWeight: 700 },
]});

// offline: same ops on an empty in-memory page, rendered (no Penpot needed)
await engine.applyOpsOffline(ops, { renderRef: "card", scale: 1 });
```

`token` must be a Penpot **access token** (Your account → Access tokens; self-hosted
instances need the `enable-access-tokens` flag). Penpot's API rejects MCP keys.

### How edits are computed

[`src/penpot_mcp/edit.cljs`](src/penpot_mcp/edit.cljs) does what Penpot's workspace does,
with Penpot's own code:

1. ops → changes with `common`'s changes-builder (which also keeps a local copy of the page);
2. text sizes (auto-width / auto-height) from render-wasm's text layout with the real fonts;
3. layouts and constraints with `set-objects-modifiers` + `transform-shape`;
4. a second pass for auto-height texts whose width a layout just set (e.g. `sizing.h = fill`);
5. text position data from render-wasm.

The result is a list of changes sent with `update-file`, validated by the backend like any
edit made in the editor; people with the file open see it live.

Supported now: boards, rectangles, ellipses, texts (builtin font); fills, strokes, radius,
opacity, visibility, clip; flex layout and child sizing; create/update/delete.
Not yet: grid layout, components, images, custom fonts for new text, paths, groups.

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
- `test/edit-smoke.mjs` — offline. Builds a pricing section (flex layouts, fill/hug sizing,
  wrapping text, update + delete ops), checks the computed geometry and renders
  `test/out/edit.png`. Runs in CI.
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

1. Live round-trip against the team's Penpot (needs a test account access token).
2. Per-user credentials (OAuth with Logto) instead of one server-wide token.
3. More ops: grid layout, components, images, groups; custom team fonts for new text.
