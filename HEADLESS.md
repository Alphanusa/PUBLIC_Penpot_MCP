# Browser-free mode

The official Penpot MCP edits designs through the Penpot MCP **plugin**, so someone has to
keep Penpot open in a browser. This repo adds tools that work **without the plugin and
without any browser**: they talk to the Penpot backend directly and run Penpot's own code
on the server — `common` (data model, changes, flex layout) and render-wasm (Skia: text
layout and rendering) — packaged as the [engine](engine/README.md).

| Tool | What it does |
|---|---|
| `list_files` | Projects and files the user can access, with ids |
| `get_page_overview` | Compact layer tree: ids, geometry, text, flex layout, sizing |
| `edit_page` | Create / update / delete boards, rectangles, ellipses, texts; fills, strokes, radius, flex layout, sizing — saved in one `update-file`, live for people who have the file open |
| `render_shape` | Renders a board with Penpot's renderer and returns it as an image for the AI to check |

The plugin-based tools (`execute_code`, `export_shape`, …) keep working as upstream when a
plugin session is connected.

## Running

1. Get an engine build — from CI (no toolchain needed):
   ```bash
   git fetch origin engine-dist
   git archive origin/engine-dist dist | tar -x -C engine/
   ```
   or build it locally, see [engine/README.md](engine/README.md).
2. Create a Penpot **access token** for the account the AI acts as: Penpot → Your account →
   Access tokens (self-hosted: enable the `enable-access-tokens` flag). Penpot's API does not
   accept MCP keys, only access tokens.
3. Start the server:
   ```bash
   pnpm -r install && pnpm run build
   PENPOT_URL=https://penpot.example.com \
   PENPOT_ACCESS_TOKEN=<access token> \
   pnpm run start
   ```
   The browser-free tools are registered when `engine/dist/engine.js` is found (or
   `PENPOT_MCP_ENGINE_DIR` points to a build). `PENPOT_MCP_HEADLESS=false` disables them.

## Limits (for now)

- One Penpot account per server (`PENPOT_ACCESS_TOKEN`). Per-user login with Logto (OAuth)
  is the next step.
- Supported shapes/properties: see "Supported now" in [engine/README.md](engine/README.md);
  grid layout, components, images, groups and custom fonts for new text are not yet supported.
- Renders and edits share one render-wasm instance and run one at a time.

## Where the code lives

All additions are new files, so syncing with upstream stays easy:

- `engine/` — the browser-free engine (ClojureScript on top of Penpot's sources at
  `.upstream-sha`), built and tested by `.github/workflows/engine.yml`.
- `packages/server/src/headless/` — the MCP tools and engine loader.
- One upstream file touched: `packages/server/src/PenpotMcpServer.ts` (registers the tools and
  appends a paragraph to the connection instructions).
