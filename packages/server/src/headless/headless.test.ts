import assert from "node:assert/strict";
import test from "node:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer } from "node:http";
import { createHeadlessTools } from "./tools";
import { HeadlessEngine } from "./HeadlessEngine";
import { makeFont } from "./fixtures";

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
  if (command === "get-all-projects") return [{ id: "p1", name: "Website", "team-name": "Alphanusa", "team-id": "t1" },
                                              { id: "p2", name: "Drafts", "team-name": "Alphanusa", "team-id": "t1" }];
  if (command === "get-project-files") {
    if (params["project-id"] === "p2") return [];
    return [{ id: "f1", name: "Landing", "modified-at": "2026-10-20T10:00:00Z" }, { id: "f2", name: "Pricing", "is-shared": true }];
  }
  throw new Error("unexpected " + command);
}
export async function filePages(opts) {
  calls.push(["filePages", opts]);
  return [{ id: "pg-" + opts.fileId, name: "Page 1" }, { id: "pg2-" + opts.fileId, name: "Screens" }];
}
export async function createProject(opts) { calls.push(["createProject", opts]); return { projectId: "p9", name: opts.name, teamId: opts.teamId ?? "t1" }; }
export async function createFile(opts) { calls.push(["createFile", opts]); return { fileId: "f9", name: opts.name, pages: (opts.pages ?? ["Page 1"]).map((n, i) => ({ id: "pg" + i, name: n })) }; }
export async function editPages(opts) { calls.push(["editPages", opts]); return { pages: [], saved: !opts.dryRun }; }
export async function listFonts(opts) { calls.push(["listFonts", opts]); return { team: [], builtin: [], google: [{ family: "IBM Plex Sans" }], googleMatches: 1 }; }
export async function uploadFont(opts) {
  calls.push(["uploadFont", opts]);
  return { fontId: "custom-55555555-5555-5555-5555-555555555555", family: opts.family, weight: opts.weight, style: opts.style };
}
export async function importLibrary(opts) {
  calls.push(["importLibrary", opts]);
  return { files: [{ fileId: "66666666-6666-6666-6666-666666666666", name: opts.name, shared: opts.shared }], linked: [] };
}
export async function linkLibrary(opts) { await track("linkLibrary", opts); return { linked: !opts.unlink, synced: !!opts.sync }; }
export async function libraryAssets(opts) { calls.push(["libraryAssets", opts]); return [{ fileId: opts.fileId, components: [] }]; }
export async function editLibrary(opts) { await track("editLibrary", opts); return { changes: 1, saved: !opts.dryRun }; }
export async function moveShapes(opts) {
  await track("moveShapes", opts);
  return { mode: opts.mode, ids: Object.fromEntries(opts.ids.map((id) => [id, opts.mode === "copy" ? "new-" + id : id])), saved: !opts.dryRun };
}
export async function getTokens(opts) { calls.push(["getTokens", opts]); return { themes: [], sets: [{ name: "core" }], tokens: [], totalTokens: 0 }; }
export async function editTokens(opts) { calls.push(["editTokens", opts]); return { changes: opts.ops.length, saved: !opts.dryRun }; }
export async function uploadImage(opts) {
  calls.push(["uploadImage", opts]);
  return { id: "44444444-4444-4444-4444-444444444444", name: opts.name, width: 118, height: 32, mtype: opts.mimeType ?? "image/png" };
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

test("registers the browser-free tools", () => {
    const names = createHeadlessTools(fakeServer, env(fakeEngineDir())).map((t) => t.getToolName());
    assert.deepEqual(names, [
        "list_files",
        "get_page_overview",
        "render_shape",
        "edit_page",
        "create_project",
        "create_file",
        "edit_pages",
        "list_fonts",
        "upload_image",
        "upload_font",
        "import_library",
        "link_library",
        "get_library",
        "edit_library",
        "move_shapes",
        "get_tokens",
        "edit_tokens",
    ]);
});

test("engine is located via PENPOT_MCP_ENGINE_DIR", () => {
    const dir = fakeEngineDir();
    assert.equal(HeadlessEngine.locate({ PENPOT_MCP_ENGINE_DIR: dir }), dir);
});

test("list_files lists projects and files with ids", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "list_files").execute({});
    const text = res.content[0].text as string;
    assert.match(text, /## Alphanusa \/ Website — projectId: p1, teamId: t1/);
    assert.match(text, /- Landing — fileId: f1 — modified 2026-10-20T10:00:00.000Z/);
    assert.match(text, /- Pricing — fileId: f2 — shared library/);
    // empty projects are listed too: new files can go there
    assert.match(text, /## Alphanusa \/ Drafts — projectId: p2, teamId: t1\n\(no files\)/);
    assert.doesNotMatch(text, /pages:/);
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

test("list_files lists pages on request", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "list_files").execute({ search: "landing", pages: true });
    const text = res.content[0].text as string;
    assert.match(text, /- Landing — fileId: f1.*\n  pages: Page 1 \(pageId: pg-f1\), Screens \(pageId: pg2-f1\)/);
    assert.equal(fake().calls.filter((c) => c[0] === "filePages").length, 1);
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

test("in OAuth mode the request's own token is used, never a server-wide one", async () => {
    const env = {
        PENPOT_MCP_ENGINE_DIR: fakeEngineDir(),
        PENPOT_URL: "http://penpot",
        PENPOT_ACCESS_TOKEN: "shared-token",
        PENPOT_MCP_OIDC_ISSUER: "https://id.example/oidc",
    };
    let current: string | undefined;
    const server = { ...fakeServer, getSessionContext: () => ({ penpotToken: current }) } as any;
    const tools = createHeadlessTools(server, env);

    const anonymous = await byName(tools, "list_files").execute({});
    assert.match(anonymous.content[0].text, /No Penpot account is linked/);

    current = "user-token";
    await byName(tools, "list_files").execute({});
    const rpcCalls = fake().calls.filter((c) => c[0] === "rpc");
    assert.ok(rpcCalls.length > 0);
    assert.ok(rpcCalls.every((c) => c[3].token === "user-token"));
});

test("create_project, create_file, edit_pages and list_fonts pass their arguments to the engine", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const ctx = { baseUri: "https://penpot.example.com", token: "test-token" };

    const project = JSON.parse(
        (await byName(tools, "create_project").execute({ name: "Media Proxy" })).content[0].text
    );
    assert.equal(project.projectId, "p9");
    assert.deepEqual(fake().calls.find((c) => c[0] === "createProject")[1], {
        ...ctx,
        name: "Media Proxy",
        teamId: undefined,
    });

    const file = JSON.parse(
        (
            await byName(tools, "create_file").execute({
                projectId: "p9",
                name: "Media Proxy UI",
                pages: ["Design system", "Screens"],
            })
        ).content[0].text
    );
    assert.deepEqual(
        file.pages.map((p: any) => p.name),
        ["Design system", "Screens"]
    );
    assert.deepEqual(fake().calls.find((c) => c[0] === "createFile")[1].pages, ["Design system", "Screens"]);

    const ops = [{ op: "add", name: "Archive" }];
    const pages = JSON.parse(
        (await byName(tools, "edit_pages").execute({ fileId: "f1", ops, dryRun: true })).content[0].text
    );
    assert.equal(pages.saved, false);
    assert.deepEqual(fake().calls.find((c) => c[0] === "editPages")[1].ops, ops);

    await byName(tools, "list_fonts").execute({ search: "plex", fileId: "f1", limit: 5 });
    assert.deepEqual(fake().calls.find((c) => c[0] === "listFonts")[1], {
        ...ctx,
        fileId: "f1",
        search: "plex",
        limit: 5,
    });
});

const PNG_1x1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

test("upload_image takes a url", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const res = await byName(tools, "upload_image").execute({
        fileId: "f1",
        url: "https://example.com/assets/logo%20dark.png",
    });
    const out = JSON.parse(res.content[0].text);
    assert.equal(out.id, "44444444-4444-4444-4444-444444444444");
    assert.match(out.usage, /"fill": \{"image": "44444444-4444-4444-4444-444444444444", "fit": "contain"\}/);
    const call = fake().calls.find((c) => c[0] === "uploadImage")[1];
    assert.equal(call.url, "https://example.com/assets/logo%20dark.png");
    assert.equal(call.name, "logo dark.png");
    assert.equal(call.data, undefined);
});

test("upload_image takes base64 bytes and detects their type", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    await byName(tools, "upload_image").execute({
        fileId: "f1",
        dataBase64: `data:image/png;base64,${PNG_1x1}`,
        name: "Dot",
    });
    let call = fake().calls.find((c) => c[0] === "uploadImage")[1];
    assert.equal(call.mimeType, "image/png");
    assert.equal(call.name, "Dot");
    assert.equal(Buffer.from(call.data).subarray(1, 4).toString("latin1"), "PNG");

    // the declared type is corrected by the bytes
    await byName(tools, "upload_image").execute({ fileId: "f1", dataBase64: PNG_1x1, mimeType: "image/jpeg" });
    call = fake().calls.filter((c) => c[0] === "uploadImage")[1][1];
    assert.equal(call.mimeType, "image/png");

    const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="118" height="32"/>').toString("base64");
    await byName(tools, "upload_image").execute({ fileId: "f1", dataBase64: svg });
    call = fake().calls.filter((c) => c[0] === "uploadImage")[2][1];
    assert.equal(call.mimeType, "image/svg+xml");
});

test("upload_image rejects missing, double and non-image sources", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const upload = byName(tools, "upload_image");
    const uploads = () => (fake()?.calls ?? []).filter((c) => c[0] === "uploadImage").length;
    const before = uploads();
    assert.match(
        (await upload.execute({ fileId: "f1" })).content[0].text,
        /exactly one image source: url or dataBase64\./
    );
    assert.match(
        (await upload.execute({ fileId: "f1", url: "https://example.com/a.png", dataBase64: PNG_1x1 })).content[0].text,
        /exactly one image source/
    );
    assert.match(
        (await upload.execute({ fileId: "f1", dataBase64: Buffer.from("hello").toString("base64") })).content[0].text,
        /Unsupported image type/
    );
    // no file system access on this server: filePath is not offered
    assert.equal("filePath" in upload.getInputSchema(), false);
    assert.match((await upload.execute({ fileId: "f1", filePath: "/etc/passwd" })).content[0].text, /not available/);
    assert.equal(uploads(), before, "nothing was uploaded");
});

test("upload_image reads local files when the server has file system access", async () => {
    const dir = mkdtempSync(join(tmpdir(), "penpot-img-"));
    const path = join(dir, "logo.png");
    writeFileSync(path, Buffer.from(PNG_1x1, "base64"));
    const server = { ...fakeServer, isFileSystemAccessEnabled: () => true } as any;
    const tools = createHeadlessTools(server, env(fakeEngineDir()));
    const upload = byName(tools, "upload_image");
    assert.equal("filePath" in upload.getInputSchema(), true);
    assert.match((await upload.execute({ fileId: "f1", filePath: "logo.png" })).content[0].text, /absolute path/);
    await upload.execute({ fileId: "f1", filePath: path });
    const call = fake().calls.find((c) => c[0] === "uploadImage")[1];
    assert.equal(call.name, "logo");
    assert.equal(call.mimeType, "image/png");
});

test("tools missing from an older engine build fail with a clear message", async () => {
    const dir = mkdtempSync(join(tmpdir(), "penpot-engine-old-"));
    writeFileSync(
        join(dir, "engine.js"),
        "export async function init() { return true; }\nexport async function rpc() { return []; }\n"
    );
    const tools = createHeadlessTools(fakeServer, env(dir));
    const res = await byName(tools, "create_project").execute({ name: "X" });
    assert.match(
        res.content[0].text,
        /engine build on this server is too old for this tool \(it has no createProject\)/
    );
});

test("upload_font reads family, weight and style from the font file", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const upload = byName(tools, "upload_font");
    const font = makeFont({ family: "Brand Sans SemiBold", weight: 600, italic: true });
    const out = JSON.parse(
        (await upload.execute({ teamId: "77777777-7777-7777-7777-777777777777", dataBase64: font.toString("base64") }))
            .content[0].text
    );
    const call = fake().calls.find((c) => c[0] === "uploadFont")[1];
    assert.equal(call.teamId, "77777777-7777-7777-7777-777777777777");
    assert.equal(call.family, "Brand Sans");
    assert.equal(call.weight, 600);
    assert.equal(call.style, "italic");
    assert.equal(call.mimeType, "font/ttf");
    assert.equal(Buffer.compare(Buffer.from(call.data), font), 0);
    assert.match(out.usage, /"fontFamily": "Brand Sans", "fontWeight": 600, "fontStyle": "italic"/);

    // explicit values win; WOFF keeps its type
    const woff = makeFont({ family: "Other", weight: 400, woff: true });
    await upload.execute({
        fileId: "f1",
        dataBase64: woff.toString("base64"),
        family: "Brand Sans",
        weight: 700,
        style: "normal",
    });
    const second = fake().calls.filter((c) => c[0] === "uploadFont")[1][1];
    assert.deepEqual(
        [second.fileId, second.family, second.weight, second.style, second.mimeType],
        ["f1", "Brand Sans", 700, "normal", "font/woff"]
    );
});

test("upload_font rejects missing teams, non-fonts and unknown weights", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const upload = byName(tools, "upload_font");
    const font = makeFont({ family: "Brand Sans" }).toString("base64");
    assert.match((await upload.execute({ dataBase64: font })).content[0].text, /Give teamId or fileId/);
    assert.match(
        (
            await upload.execute({
                teamId: "77777777-7777-7777-7777-777777777777",
                dataBase64: Buffer.from("hello").toString("base64"),
            })
        ).content[0].text,
        /Not a font file/
    );
    assert.match(
        (await upload.execute({ teamId: "77777777-7777-7777-7777-777777777777", dataBase64: font, weight: 450 }))
            .content[0].text,
        /give weight \(100-900 or 950\)/
    );
});

test("import_library downloads a .penpot file and passes the import options", async () => {
    const pkg = Buffer.from("PK\x03\x04fake penpot package");
    const http = createServer((req, res) => {
        res.writeHead(200, { "content-type": "application/zip" });
        res.end(pkg);
    });
    await new Promise<void>((r) => http.listen(0, "127.0.0.1", () => r()));
    try {
        const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
        const url = `http://127.0.0.1:${(http.address() as any).port}/kits/Material%20UI.penpot`;
        const out = JSON.parse(
            (
                await byName(tools, "import_library").execute({
                    projectId: "p1",
                    url,
                    linkTo: ["f1"],
                })
            ).content[0].text
        );
        const call = fake().calls.find((c) => c[0] === "importLibrary")[1];
        assert.equal(call.projectId, "p1");
        assert.equal(call.name, "Material UI");
        assert.equal(call.shared, true);
        assert.deepEqual(call.linkTo, ["f1"]);
        assert.equal(Buffer.compare(Buffer.from(call.data), pkg), 0);
        assert.equal(out.files[0].name, "Material UI");

        await byName(tools, "import_library").execute({
            projectId: "p1",
            dataBase64: pkg.toString("base64"),
            shared: false,
        });
        const second = fake().calls.filter((c) => c[0] === "importLibrary")[1][1];
        assert.equal(second.name, "Library");
        assert.equal(second.shared, false);
    } finally {
        http.close();
    }
});

test("link_library, get_library and edit_library pass their arguments; sync and library edits use the WASM gate", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const ctx = { baseUri: "https://penpot.example.com", token: "test-token" };
    await byName(tools, "link_library").execute({ fileId: "f1", libraryId: "f2", sync: true });
    assert.deepEqual(fake().calls.find((c) => c[0] === "linkLibrary")[1], {
        ...ctx,
        fileId: "f1",
        libraryId: "f2",
        unlink: false,
        sync: true,
        dryRun: false,
    });
    await byName(tools, "get_library").execute({ fileId: "f1", search: "button", limit: 10 });
    assert.deepEqual(fake().calls.find((c) => c[0] === "libraryAssets")[1], {
        ...ctx,
        fileId: "f1",
        search: "button",
        limit: 10,
    });
    const ops = [{ op: "add", type: "color", name: "Brand / Primary", color: "#3366ff" }];
    await Promise.all([
        byName(tools, "edit_library").execute({ fileId: "f1", ops }),
        byName(tools, "edit_page").execute({ fileId: "f1", ops: [{ op: "delete", id: "x" }] }),
    ]);
    assert.deepEqual(fake().calls.find((c) => c[0] === "editLibrary")[1].ops, ops);
    assert.equal(fake().maxActive, 1);
});

test("move_shapes passes its arguments and uses the WASM gate", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const ctx = { baseUri: "https://penpot.example.com", token: "test-token" };
    const [res] = await Promise.all([
        byName(tools, "move_shapes").execute({
            fileId: "f1",
            ids: ["b1", "b2"],
            toPageId: "Archive",
            toFileId: "f2",
            mode: "copy",
            x: 0,
            y: 40,
        }),
        byName(tools, "render_shape").execute({ fileId: "f1", pageId: "p", shapeId: "s" }),
    ]);
    assert.deepEqual(fake().calls.find((c) => c[0] === "moveShapes")[1], {
        ...ctx,
        fileId: "f1",
        pageId: undefined,
        ids: ["b1", "b2"],
        toPageId: "Archive",
        toFileId: "f2",
        mode: "copy",
        x: 0,
        y: 40,
        dryRun: false,
    });
    assert.deepEqual(JSON.parse(res.content[0].text).ids, { b1: "new-b1", b2: "new-b2" });
    assert.equal(fake().maxActive, 1);
    await byName(tools, "move_shapes").execute({ fileId: "f1", ids: ["b1"], toPageId: "p2" });
    assert.equal(fake().calls.filter((c) => c[0] === "moveShapes")[1][1].mode, "move");
});

test("get_tokens and edit_tokens pass their arguments", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    const ctx = { baseUri: "https://penpot.example.com", token: "test-token" };
    const res = await byName(tools, "get_tokens").execute({ fileId: "f1", type: "color", search: "brand" });
    assert.deepEqual(fake().calls.find((c) => c[0] === "getTokens")[1], {
        ...ctx,
        fileId: "f1",
        set: undefined,
        type: "color",
        search: "brand",
        limit: 200,
    });
    assert.equal(JSON.parse(res.content[0].text).sets[0].name, "core");
    const ops = [
        { op: "addSet", name: "core", active: true },
        { op: "addToken", set: "core", type: "color", name: "color.primary", value: "#0066ff" },
    ];
    await byName(tools, "edit_tokens").execute({ fileId: "f1", ops, dryRun: true });
    assert.deepEqual(fake().calls.find((c) => c[0] === "editTokens")[1], { ...ctx, fileId: "f1", ops, dryRun: true });
});

test("create_file can publish the new file as a shared library", async () => {
    const tools = createHeadlessTools(fakeServer, env(fakeEngineDir()));
    await byName(tools, "create_file").execute({ projectId: "p1", name: "Design system", shared: true });
    assert.equal(fake().calls.find((c) => c[0] === "createFile")[1].shared, true);
});
