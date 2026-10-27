# Penpot MCP server (Alphanusa build).
#
# Image contents:
#   - the MCP server from packages/ (Penpot's official MCP, see UPSTREAM.md),
#     bundled the same way as Penpot's own image (mcp/scripts/build);
#   - the browser-free engine in ./engine when the release workflow provides one
#     in .docker/engine (see docs/RILIS.md). Without it the server behaves like
#     the official image (plugin mode only).
#
# Built by .github/workflows/release.yml from release tags only.

# --- 1. build the server bundle -------------------------------------------------
FROM node:22-bookworm-slim AS build

ARG PNPM_VERSION=12.14.0
RUN npm install -g "pnpm@${PNPM_VERSION}"

WORKDIR /src
COPY . .

RUN pnpm install --frozen-lockfile \
 && pnpm run build

# Same bundle layout as Penpot's mcp/scripts/build: server dist + production deps.
RUN mkdir -p /out \
 && cp -a packages/server/dist/. /out/ \
 && cp packages/server/package.json pnpm-lock.yaml /out/ \
 && touch /out/pnpm-workspace.yaml \
 && cd /out \
 && pnpm install -P

# --- 2. runtime -------------------------------------------------------------------
FROM node:22-bookworm-slim

ARG VERSION=dev
ENV NODE_ENV=production \
    PENPOT_MCP_SERVER_HOST=0.0.0.0 \
    PENPOT_MCP_IMAGE_VERSION=${VERSION}

WORKDIR /opt/penpot-mcp
COPY --from=build /out/ ./

# Browser-free engine (empty unless the release workflow filled .docker/engine).
COPY .docker/engine/ ./engine/
RUN if [ -f engine/package.json ]; then \
      cd engine && npm ci --omit=dev --no-audit --no-fund; \
    fi \
 && rm -f engine/.keep \
 && mkdir -p data && chown node:node data

# Encrypted store of the OAuth login (linked Penpot tokens, clients, refresh tokens).
# Mount a volume here so people stay connected across container updates.
ENV PENPOT_MCP_DATA_DIR=/opt/penpot-mcp/data
VOLUME ["/opt/penpot-mcp/data"]

USER node

# 4401: MCP (Streamable HTTP, /mcp)   4402: WebSocket for the Penpot plugin
EXPOSE 4401 4402

CMD ["node", "index.js", "--multi-user"]
