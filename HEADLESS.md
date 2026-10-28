# Browser-free mode

The official Penpot MCP edits designs through the Penpot MCP **plugin**, so someone has to
keep Penpot open in a browser. This repo adds tools that work **without the plugin and
without any browser**: they talk to the Penpot backend directly and run Penpot's own code
on the server — `common` (data model, changes, flex layout) and render-wasm (Skia: text
layout and rendering) — packaged as the [engine](engine/README.md).

| Tool | What it does |
|---|---|
| `list_files` | Teams, projects and files the user can edit, with ids (optionally each file's pages) |
| `get_page_overview` | Compact layer tree: ids, geometry, text, flex layout, sizing |
| `edit_page` | Create / update / delete boards, rectangles, ellipses, texts and images; color and image fills, strokes, radius, fonts, flex layout, sizing — saved in one `update-file`, live for people who have the file open. Unknown or misplaced properties are rejected, never ignored |
| `render_shape` | Renders a board with Penpot's renderer and returns it as an image for the AI to check |
| `create_project` | New project in a team (the account's default team when none is given) |
| `create_file` | New file in a project, optionally with named pages |
| `edit_pages` | Add, rename, delete and reorder pages |
| `list_fonts` | Font families for texts: the team's uploaded fonts, builtin Source Sans Pro, Google Fonts — with their weights |
| `upload_image` | Adds a PNG/JPEG/GIF/WebP/SVG to a file's media (from a URL, base64, or a local path when the server has file system access) for image fills |

With these an AI client can go from nothing to "project → file → named pages → boards"
without anyone opening Penpot.

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

`PENPOT_ACCESS_TOKEN` is for a single person running the server for themselves. A shared
server must not use it: the MCP endpoint would let anyone act as that account. Use the
built-in login instead ([docs/LOGIN.md](docs/LOGIN.md)): each person signs in with the
company identity provider and links their own Penpot account once; `PENPOT_ACCESS_TOKEN`
is then ignored.

## Limits (for now)

- Supported shapes/properties: see "Supported now" in [engine/README.md](engine/README.md);
  grid layout, components, groups, paths and SVG import as editable vectors are not yet
  supported (an SVG can be uploaded and used as an image fill).
- Google fonts are loaded through the Penpot instance's `/internal/gfonts/font` proxy, like
  Penpot's exporter; in the editor they show when the instance has the Google Fonts provider
  enabled (Penpot's default).
- Renaming or deleting projects and files is left to people in Penpot.
- Renders and edits share one render-wasm instance and run one at a time.

## Where the code lives

All additions are new files, so syncing with upstream stays easy:

- `engine/` — the browser-free engine (ClojureScript on top of Penpot's sources at
  `.upstream-sha`), built and tested by `.github/workflows/engine.yml`.
- `packages/server/src/headless/` — the MCP tools and engine loader.
- `packages/server/src/oauth/` — the built-in login (OAuth authorization server).
- One upstream file touched: `packages/server/src/PenpotMcpServer.ts` (registers the tools,
  appends a paragraph to the connection instructions, and checks the login on `/mcp` when
  it is enabled).
