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

// offline: same ops on an empty in-memory page, rendered (no Penpot needed);
// `images` provides bytes for image fills, `gfonts` loads Google fonts from fonts.gstatic.com
await engine.applyOpsOffline(ops, { renderRef: "card", scale: 1, images: { [mediaId]: pngBytes }, gfonts: true });

// projects, files, pages
await engine.createProject({ ...ctx, name: "Media Proxy", teamId });          // teamId optional
await engine.createFile({ ...ctx, projectId, name: "UI", pages: ["Design system", "Screens"] });
await engine.filePages({ ...ctx, fileId });                                  // [{id, name}]
await engine.editPages({ ...ctx, fileId, dryRun: false, ops: [
  { op: "add", ref: "archive", name: "Archive" },
  { op: "rename", id: pageId, name: "Screens" },
  { op: "move", id: "archive", index: 0 },
  { op: "delete", id: otherPageId },
]});

// libraries
await engine.importLibrary({ ...ctx, projectId, name: "Kit", data: penpotBytes, shared: true, linkTo: [fileId] });
await engine.linkLibrary({ ...ctx, fileId, libraryId, sync: false });     // unlink: true to unlink
await engine.libraryAssets({ ...ctx, fileId, search: "button", limit: 50 });// own + linked libraries
await engine.editLibrary({ ...ctx, fileId, ops: [
  { op: "add", type: "color", name: "Brand / Primary", color: "#3366ff" },
  { op: "add", type: "typography", name: "Heading / H1", fontFamily: "IBM Plex Sans", fontWeight: 600, fontSize: 32 },
]});
// in applyOps: { op: "create", type: "component", component: "Buttons / Primary", texts: { Label: "Save" } },
//              fill: "Brand / Primary", typography: "Heading / H1", { op: "makeComponent", id: boardRef },
//              { op: "linkAssets", scope: "page", typographies: true, prefer: { "#ffffff": "Surface" } }
//              { op: "move", id: shapeId, parent: boardId, index: 0 }

// boards to another page (same ids), or copies to a page of another file of the team
await engine.moveShapes({ ...ctx, fileId, ids: [boardId], toPageId: "Archive", mode: "move" });
await engine.moveShapes({ ...ctx, fileId, ids: [boardId], toFileId, toPageId: "Components", mode: "copy", x: 0, y: 0 });

// design tokens
await engine.getTokens({ ...ctx, fileId, type: "color" });               // {themes, sets, tokens (value, resolved)}
await engine.editTokens({ ...ctx, fileId, ops: [
  { op: "addSet", name: "core", active: true },
  { op: "addToken", set: "core", type: "color", name: "color.primary", value: "#0066ff" },
  { op: "addToken", set: "core", type: "spacing", name: "space.lg", value: "{space.md} * 2" },
  { op: "addTheme", group: "Mode", name: "Light", sets: ["core"], active: true },
]});
// in applyOps: { op: "create", type: "frame", layout: {}, tokens: { fill: "color.primary", padding: "space.lg" } }
await engine.getTokens({ ...ctx, fileId, format: "dtcg" });                // {format, json}: W3C DTCG export
await engine.editTokens({ ...ctx, fileId, ops: [{ op: "import", tokens: dtcgJson, mode: "merge" }] });

// design system ops (applyOps)
//   { op: "makeVariants", name: "Buttons / Button", variants: [{ id: "primary", properties: { Type: "Primary" } }, …] }
//   { op: "setVariant", id: mainId, properties: { State: "Hover" } }   { op: "swap", id: copyId, variant: { Type: "Secondary" } }
//   { op: "detach", id }  { op: "resetOverrides", id }  { op: "annotate", id: mainId, annotation: "…" }
//   { op: "create", type: "svg", svg: "<svg …>", width: 24, height: 24, color: "#111827" }
//   { op: "create", type: "path", d: "M0 0 L24 12 L0 24 Z" }  { op: "group", ids }  { op: "boolean", ids, kind: "union" }
//   fill: { gradient: { angle: 90, stops: ["#3366ff", "#00aa88"] } }, shadow: { y: 4, blur: 12 }, blur: 4,
//   spans: [{ text: "Hi " }, { text: "there", fontWeight: "700" }], layout: { type: "grid", columns: ["1fr", 240] },
//   cell: { row: 1, column: 2 }, margin, minWidth, constraints: { h: "leftright" }, rotation: 15,
//   tokens: { typography: "type.body", shadow: "elevation.1", strokeWidth: "border.thin" },
//   interactions: [{ trigger: "click", action: "navigate", destination: boardId }], { op: "flow", name, start },
//   exports: [{ type: "png", scale: 2, suffix: "@2x" }]
// editing a main updates its copies on every page (result.copiesUpdated)

// checks, previews, history, comments
await engine.auditDesign({ ...ctx, fileId, pageId, checks: ["contrast", "colors"] });
await engine.renderShape({ ...ctx, fileId, pageId, objectId, theme: "Mode/Dark" });   // token theme preview
await engine.fileVersions({ ...ctx, fileId, action: "create", label: "Before redesign" });
await engine.comments({ ...ctx, fileId, action: "create", shapeId, content: "Why this spacing?" });

// fonts and images
await engine.uploadFont({ ...ctx, teamId, data: ttfBytes, mimeType: "font/ttf", family: "Brand Sans", weight: 700, style: "normal" });
await engine.listFonts({ ...ctx, fileId, search: "plex", limit: 20 });      // team / builtin / google
await engine.uploadImage({ ...ctx, fileId, name: "Logo", url });            // or data + mimeType
// -> {id, name, width, height, mtype}; then in applyOps:
//    { op: "create", type: "rect", fill: { image: id, fit: "contain" } } or { op: "create", type: "image", image: id }
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
5. `contain` image fills sized from the shapes' final size;
6. text position data from render-wasm.

Before step 1 every op is checked against the properties the engine reads
([`props.cljs`](src/penpot_mcp/props.cljs)): an unknown or misplaced key (`fontsize`,
`padding` outside `layout`, `radius` on an ellipse, …) is an error naming the key, with a
hint, in dry runs too — never silently dropped.

The result is a list of changes sent with `update-file`, validated by the backend like any
edit made in the editor; people with the file open see it live.

Supported now: boards, rectangles, ellipses, texts, images; color and image fills (cover,
contain, fill), strokes, radius, opacity, visibility, clip; fonts (team fonts, builtin Source
Sans Pro, Google Fonts — [`fonts.cljs`](src/penpot_mcp/fonts.cljs)) with real weights and
styles; flex layout and child sizing; create/update/delete; pages (add, rename, delete,
move — [`pages.cljs`](src/penpot_mcp/pages.cljs)); new projects and files; team font
uploads; libraries ([`library.cljs`](src/penpot_mcp/library.cljs)): `.penpot` imports,
link/unlink/sync, library colors and typographies (used by name, linked), component copies
with overrides (Penpot's `make-component-instance`), boards made components
(`generate-add-component`), and library color/typography edits synced to the shapes that
use them (`generate-sync-file-changes`, then texts re-measured and layouts reflowed).
Shapes are moved and reordered with Penpot's `generate-relocate` (the move op). Raw colors
and text styles are linked to library assets with the same values by
[`link.cljs`](src/penpot_mcp/link.cljs) (`linkAssets`; ambiguous and unmatched values are
reported, never guessed). [`move.cljs`](src/penpot_mcp/move.cljs) moves subtrees to another
page in one update-file (ids kept; main components, flows and guides follow), or copies them
with `generate-duplicate-changes` like paste — to another file with media cloned
(`clone-file-media-object`), library refs remapped by name and main components recreated.
Design tokens ([`tokens.cljs`](src/penpot_mcp/tokens.cljs)) are read and edited with Penpot's
tokens library and changes (sets, themes, tokens, activation, checked with Penpot's schemas),
resolved here for references and arithmetic, and applied to shapes by recording
`applied-tokens` as the Tokens panel does (colors, dimensions, spacing, sizing, radius,
opacity, stroke width, rotation, shadow and typography tokens), imported and exported as
DTCG JSON with Penpot's tokens library.

Design systems: variant sets are built like the editor's "Combine as variants" (the set
board, `:variant-id` / `:variant-properties` on the components and their mains, names from
the set), copies are swapped with `generate-component-swap` keeping overrides
(`generate-keep-touched`), detached and reset with Penpot's library logic, and after every
edit the copies of changed mains are synced with `generate-sync-file-changes` on every page.
[`vector.cljs`](src/penpot_mcp/vector.cljs) imports SVG markup with Penpot's
`create-svg-shapes` (parsed with sax, like tubax), creates paths from SVG path data, groups,
ungroups and makes boolean shapes (ports of the workspace's group and bool events). Fills
take gradients and several layers, strokes gradients and dash styles; shadows, layer and
background blur and blend modes are set like the design tab; texts take spans (rich text)
and vertical alignment. Grid layouts (tracks, cells, spans) use `common`'s grid helpers
(`add-grid-column`, `push-into-cell`, `resize-cell-area`, `assign-cells`) and are laid out by
the same modifiers code as flex; layout item options, constraints and rotation too. Groups
and booleans are recomputed after geometry changes (`reg-objects`). Deletes use
`generate-delete-shapes` (components, flows and prototype links follow). Prototype
interactions are checked with Penpot's interaction schema; flows and export settings are
plain changes. [`audit.cljs`](src/penpot_mcp/audit.cljs) runs the design checks (WCAG
contrast with alpha blending over the solid background behind each text, CIE76 color
distance to library colors, token scales).

Component copies moved by a layout keep following their main component: like the
workspace, geometry changes that keep a copy's shapes in place relative to its root are
saved with `ignore-geometry` ([`touched.cljs`](src/penpot_mcp/touched.cljs)).

Image fills need the media's size and type, which no backend RPC returns for an id: the
engine reads them from the image header ([`media.cljs`](src/penpot_mcp/media.cljs)) when it
fetches the image to render it.

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
- `test/features.mjs` — offline. Property validation errors, Google font loading and
  measuring (IBM Plex Mono must come out monospaced), image fills (cover/contain/fill, the
  `image` type, rendered pixels of a `contain` fill), image header parsing and page ops.
  Needs network access to fonts.gstatic.com. Runs in CI.
- `test/libraries.mjs` — offline. A library built in memory (colors, a typography, a
  component made from a board), a page using it (component copies with text overrides in a
  flex board, library colors on fills/strokes/texts, a typography), checks that copies moved
  by the layout are not marked as overridden, and edit_library updates synced to the shapes
  using them, with the changes checked against Penpot's change schemas. Runs in CI.
- `test/link-move-tokens.mjs` — offline. linkAssets (linked, ambiguous with `prefer`,
  unmatched, typographies; library edits then follow), the summaries (strokes, radius, text
  styles, asset names), the move op (reorder in a flex row, re-parent, page root), move_shapes
  (to another page keeping ids, layouts, images and the component; copied to another file with
  media, refs remapped or unlinked, the component recreated and its copy relinked, rendering
  like the original), design tokens (sets, themes, activation, resolved values, validation
  errors) and tokens applied to shapes. Runs in CI.
- `test/design-system.mjs` — offline. Variant sets (make, add, set/rename/remove
  properties, errors), annotations, copies (variant switch keeping overrides, swap to another
  component, detach, reset, copies following an edit of their main on another page), SVG
  import with tinting, paths, booleans, groups, gradients, shadows, blur, multiple fills,
  rich text, grid layouts with spans and auto placement, layout item options, constraints,
  rotation, typography/shadow/stroke/rotation/margin/sizing tokens, theme switch and themed
  render, DTCG export/import (replace and merge), audit_design findings, prototype
  interactions, flows, export settings, and editor-like deletes. Runs in CI.
- `test/live.mjs` — against a real Penpot. Needs `PENPOT_URL` and `PENPOT_TOKEN`
  (access token of a **test** account); optional `FILE_ID`, `PAGE_ID`, `OBJECT_ID`,
  otherwise it renders the first board of the first file it finds.
- `test/live-create.mjs` — against a real Penpot, **creates** a project in a test account:
  project → file with named pages → page ops → uploaded logo → boards with the logo as a
  `contain` image fill and IBM Plex texts → render (`test/out/live-create.png`).
- `test/live-library.mjs` — against a real Penpot, **creates** a project in a test account:
  a library file (colors, typography, a component), a design file linked to it using them,
  a library change synced to it, optionally a team font (`FONT_FILE`) and a `.penpot`
  import (`PENPOT_PACKAGE`); renders `test/out/live-library.png`.
- `test/live-move-tokens.mjs` — against a real Penpot, **creates** a project in a test
  account: linkAssets, the move op, move_shapes and design tokens.
- `test/live-design-system.mjs` — against a real Penpot, **creates** a project in a test
  account: tokens with light/dark themes, a button variant set and an icon from SVG, a grid
  screen with copies, a swap, effects, rich text and a prototype flow, copies following a
  main edit, audit_design, light and dark renders, SVG and PDF exports, DTCG export and import
  into a second file, a version and a comment.

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
3. Masks, path editing beyond replacing `d`, texts on paths.
