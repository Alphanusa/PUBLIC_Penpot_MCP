// Live test against a real Penpot: profile via access token, then render one
// shape to PNG through get-page + render-wasm. No browser.
//
// Env:
//   PENPOT_URL     e.g. https://penpot.example.com
//   PENPOT_TOKEN   access token / MCP key of a TEST account
//   FILE_ID, PAGE_ID, OBJECT_ID   shape to render (optional: if missing, the
//                  first board of the first page of the most recent file is used)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");
const engine = await import(resolve(dist, "engine.js"));

const ctx = { baseUri: process.env.PENPOT_URL, token: process.env.PENPOT_TOKEN };
if (!ctx.baseUri || !ctx.token) throw new Error("PENPOT_URL and PENPOT_TOKEN are required");

const profile = await engine.rpc(ctx, "get-profile", {});
console.log("profile:", profile.email ?? profile.fullname ?? profile.id);

let { FILE_ID: fileId, PAGE_ID: pageId, OBJECT_ID: objectId } = process.env;

if (!fileId) {
    const projects = await engine.rpc(ctx, "get-all-projects", {});
    for (const project of projects) {
        const files = await engine.rpc(ctx, "get-project-files", { "project-id": project.id });
        if (files.length) {
            fileId = files[0].id;
            console.log(`file: ${files[0].name} (${fileId}) in project ${project.name}`);
            break;
        }
    }
    if (!fileId) throw new Error("no files found for this account");
}

if (!pageId || !objectId) {
    // without page-id the backend returns the file's first page
    const page = await engine.rpc(ctx, "get-page", pageId ? { "file-id": fileId, "page-id": pageId } : { "file-id": fileId });
    pageId = pageId ?? page.id;
    const root = page.objects["00000000-0000-0000-0000-000000000000"];
    objectId = objectId ?? root?.shapes?.[0];
    if (!objectId) throw new Error("page has no shapes");
    console.log(`page ${pageId}, object ${objectId} (${page.objects[objectId]?.name})`);
}

await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));
const t0 = performance.now();
const png = await engine.renderShape({ ...ctx, fileId, pageId, objectId, scale: 1, format: "png" });
const ms = Math.round(performance.now() - t0);

mkdirSync(resolve(here, "out"), { recursive: true });
writeFileSync(resolve(here, "out/live.png"), Buffer.from(png));
console.log(JSON.stringify({ ok: true, bytes: png.length, ms, out: "test/out/live.png" }, null, 2));
