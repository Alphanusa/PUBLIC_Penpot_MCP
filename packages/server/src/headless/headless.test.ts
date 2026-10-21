import assert from "node:assert/strict";
import test from "node:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHeadlessTools } from "./tools";
import { HeadlessEngine } from "./HeadlessEngine";

/**
 * A fake engine module with the same JS API as engine/dist/engine.js, recording calls
 * and the maximum number of concurrent render/edit calls.
 */
function fakeEngineDir(): string {
    const dir = mkdtempSync(join(tmpdir(), "penpot-engine-"));
    writeFileSync(
        join(dir, "engine.js"),
        `
export const calls = [];
globalThis.__fakeEngine = { calls, active: 0, maxActive: 0 };
const track = async (name, opts) => {
  const s = globalThis.__fakeEngine;
  calls.push([name, opts]);
  s.active++; s.maxActive = Math.max(s.maxActive, s.active);
  await new Promise((r) => setTimeout(r, 20));
  s.active--;
};
export async function init(wasmDir, fontsDir) { calls.push(["init", wasmDir, fontsDir]); return true; }
export async function rpc(ctx, command, params) {
  calls.push(["rpc", command, params, ctx]);
  if (command === "get-all-projects") return [{ id: "p1", name: "Website", "team-name": "Alphanusa" }];
  if (command === "get-project-files") return [{ id: "f1", name: "Landing", "modified-at": "2026-10-20T10:00:00Z" },
                                               { id: "f2", name: "Pricing" }];
  throw new Error("unexpected " + command);
}
export async function pageSummary(opts) {
  calls.push(["pageSummary", opts]);
  return { pageId: "11111111-1111-1111-1111-111111111111", pageName: "Page 1",
           tree: { id: "root", type: "root", children: [{ id: "22222222-2222-2222-2222-222222222222", type: "frame" }] } };
}
export async function renderShape(opts) {
  await track("renderShape", opts);
  return new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
}
export async function applyOps(opts) {
  await track("applyOps", opts);
  return { changes: 3, created: { card: "33333333-3333-3333-3333-333333333333" }, saved: !opts.dryRun };
}
`
    );
    return dir;
}

const env = (dir: string, extra: Record<string, string> = {}) => ({
    PENPOT_MCP_ENGINE_DIR: dir,
    PENPOT_URL: "https://penpot.example.com",
    PENPOT_ACCESS_TOKEN: "test-token",
    ...extra,
});

const fakeServer = {} as any;
const byName = (tools: any[], name: string) => tools.find((t) => t.getToolName() === name);
const fake = () => (globalThis as any).__fakeEngine as { calls: any[]; maxActive: number };

test("no browser-free tools without an engine build", () => {
    assert.deepEqual(createHeadlessTools(fakeServer, { PENPOT_MCP_ENGINE_DIR: "/nonexistent" }), []);
});

test("browser-free tools can be disabled", () => {
    assert.deepEqual(createHeadlessTools(fakeServer, env(fakeEngineDir(), { PENPOT_MCP_HEADLESS: "false" })), []);
});

test("registers the four browser-free tools", () => {
    const names = createHeadlessTools(fakeServer, env(fakeEngineDir())).map((t) => t.getToolName());
    assert.deepEqual(names, ["list_files", "get_page_overview", "render_shape", "edit_page"]);
});

test("engine is located via PENPOT_MCP_ENGINE_DIR", () => {
    const dir = fakeEngineDir();
    assert.equal(HeadlessEngine.locate({ PENPOT_MCP_ENGINE_DIR: dir }), dir);
});

test("list_files lists projects and files with ids", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "list_files").execute({});
    const text = res.content[0].text as string;
    assert.match(text, /## Alphanusa \/ Website/);
    assert.match(text, /- Landing — fileId: f1 — modified 2026-10-20T10:00:00.000Z/);
    assert.match(text, /- Pricing — fileId: f2/);
    const rpcCall = fake().calls.find((c) => c[0] === "rpc");
    assert.deepEqual(rpcCall[3], { baseUri: "https://penpot.example.com", token: "test-token" });
});

test("list_files filters by search", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "list_files").execute({ search: "pric" });
    const text = res.content[0].text as string;
    assert.match(text, /Pricing/);
    assert.doesNotMatch(text, /Landing/);
});

test("render_shape defaults to the first board and returns an image", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "render_shape").execute({ fileId: "f1", scale: 1, format: "png" });
    assert.equal(res.content[0].type, "image");
    assert.equal(res.content[0].mimeType, "image/png");
    const render = fake().calls.find((c) => c[0] === "renderShape")[1];
    assert.equal(render.pageId, "11111111-1111-1111-1111-111111111111");
    assert.equal(render.objectId, "22222222-2222-2222-2222-222222222222");
});

test("edit_page passes ops and returns the engine result", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const ops = [{ op: "create", type: "frame", ref: "card" }];
    const res = await byName(tools, "edit_page").execute({ fileId: "f1", ops, dryRun: true });
    const result = JSON.parse(res.content[0].text);
    assert.equal(result.created.card, "33333333-3333-3333-3333-333333333333");
    assert.equal(result.saved, false);
    const call = fake().calls.find((c) => c[0] === "applyOps")[1];
    assert.deepEqual(call.ops, ops);
});

test("render and edit calls never overlap in the WASM engine", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const render = byName(tools, "render_shape");
    const edit = byName(tools, "edit_page");
    await Promise.all([
        render.execute({
            fileId: "f1",
            pageId: "11111111-1111-1111-1111-111111111111",
            shapeId: "22222222-2222-2222-2222-222222222222",
        }),
        edit.execute({ fileId: "f1", ops: [{ op: "delete", id: "x" }] }),
        render.execute({
            fileId: "f1",
            pageId: "11111111-1111-1111-1111-111111111111",
            shapeId: "22222222-2222-2222-2222-222222222222",
        }),
    ]);
    assert.equal(fake().maxActive, 1);
});

test("missing credentials produce a clear message", async () => {
    const tools = createHeadlessTools(fakeServer, { PENPOT_MCP_ENGINE_DIR: fakeEngineDir() });
    const res = await byName(tools, "list_files").execute({});
    assert.match(res.content[0].text, /PENPOT_URL and PENPOT_ACCESS_TOKEN/);
});
