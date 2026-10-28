// Live end-to-end test of the browser-free "new design" flow against a real
// Penpot (issues #6, #7, #8, #9): project -> file with named pages -> page ops
// -> uploaded logo -> boards with an image fill and Google-font texts -> render.
//
// It CREATES a project in the account's default team (or TEAM_ID): use a TEST
// account. Nothing is deleted afterwards, so the result can be opened in Penpot.
//
// Env:
//   PENPOT_URL     e.g. https://penpot.example.com
//   PENPOT_TOKEN   access token of a TEST account
//   TEAM_ID        optional team for the project
//   LOGO_URL       optional image URL the Penpot backend can download
//                  (default: the engine's own demo render, uploaded as bytes)
//
// Usage: node test/live-create.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");
const engine = await import(resolve(dist, "engine.js"));
await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));

const ctx = { baseUri: process.env.PENPOT_URL, token: process.env.PENPOT_TOKEN };
if (!ctx.baseUri || !ctx.token) throw new Error("PENPOT_URL and PENPOT_TOKEN are required");

const failures = [];
const check = (cond, msg) => cond || failures.push(msg);
const stamp = new Date().toISOString().slice(0, 16).replace("T", " ");

// #6: project, file with named pages, page ops
const project = await engine.createProject({ ...ctx, name: `MCP live test ${stamp}`, teamId: process.env.TEAM_ID });
console.log("project:", project);
const file = await engine.createFile({ ...ctx, projectId: project.projectId, name: "Media Proxy UI", pages: ["Design system", "Screens"] });
console.log("file:", file.fileId, file.pages);
check(JSON.stringify(file.pages.map((p) => p.name)) === JSON.stringify(["Design system", "Screens"]), `file pages: ${JSON.stringify(file.pages)}`);

const pages = await engine.editPages({
    ...ctx,
    fileId: file.fileId,
    ops: [
        { op: "add", ref: "archive", name: "Archive (temp)" },
        { op: "move", id: "archive", index: 0 },
        { op: "rename", id: "archive", name: "Archive" },
    ],
});
check(pages.saved && pages.pages[0].name === "Archive", `page ops: ${JSON.stringify(pages.pages)}`);
const deleted = await engine.editPages({ ...ctx, fileId: file.fileId, ops: [{ op: "delete", id: pages.created.archive }] });
check(deleted.pages.length === 2, `page delete: ${JSON.stringify(deleted.pages)}`);
const listed = await engine.filePages({ ...ctx, fileId: file.fileId });
check(JSON.stringify(listed.map((p) => p.name)) === JSON.stringify(["Design system", "Screens"]), `filePages: ${JSON.stringify(listed)}`);

// #7: fonts available
const fonts = await engine.listFonts({ ...ctx, fileId: file.fileId, search: "IBM Plex", limit: 5 });
check(fonts.google.some((f) => f.family === "IBM Plex Mono"), `listFonts: ${JSON.stringify(fonts.google)}`);

// #8: upload a logo
const logo = process.env.LOGO_URL
    ? await engine.uploadImage({ ...ctx, fileId: file.fileId, name: "Logo", url: process.env.LOGO_URL })
    : await engine.uploadImage({ ...ctx, fileId: file.fileId, name: "Logo", data: Buffer.from(await engine.renderDemo()), mimeType: "image/png" });
console.log("logo:", logo);
check(logo.id && logo.width > 0 && logo.height > 0, `upload: ${JSON.stringify(logo)}`);

// #9: an unknown property is rejected before anything is saved
try {
    await engine.applyOps({ ...ctx, fileId: file.fileId, pageId: file.pages[1].id, dryRun: true,
                            ops: [{ op: "create", type: "text", text: "x", fontsize: 12 }] });
    failures.push("validation: unknown property accepted");
} catch (e) {
    check(/Unknown property "fontsize"/.test(e.message), `validation: ${e.message}`);
}

// boards on the "Screens" page: the logo reused in 3 headers, texts in IBM Plex
const screens = file.pages[1].id;
const ops = [0, 1, 2].flatMap((i) => [
    { op: "create", type: "frame", ref: `screen${i}`, name: `Screen ${i + 1}`, x: i * 420, y: 0, width: 390, height: 10,
      fill: "#FFFFFF", layout: { direction: "column", gap: 12, padding: 20 }, sizing: { v: "hug" } },
    { op: "create", type: "frame", ref: `header${i}`, parent: `screen${i}`, name: "Header", width: 350, height: 48, fill: "#F4F1EA",
      layout: { direction: "row", gap: 12, padding: [8, 12], alignItems: "center" }, sizing: { h: "fill" } },
    { op: "create", type: "rect", parent: `header${i}`, name: "Logo", width: 118, height: 32, fill: { image: logo.id, fit: "contain" } },
    { op: "create", type: "text", parent: `header${i}`, text: "Media Proxy", fontFamily: "IBM Plex Sans", fontWeight: 600, fontSize: 18 },
    { op: "create", type: "text", parent: `screen${i}`, text: `node-${i}.internal 10.0.0.${i + 10}`, fontFamily: "IBM Plex Mono", fontWeight: 500, fontSize: 14 },
]);
const result = await engine.applyOps({ ...ctx, fileId: file.fileId, pageId: screens, ops });
check(result.saved, `edit saved: ${JSON.stringify({ changes: result.changes, revn: result.revn })}`);

const png = await engine.renderShape({ ...ctx, fileId: file.fileId, pageId: screens, objectId: result.created.screen0, scale: 1, format: "png" });
mkdirSync(resolve(here, "out"), { recursive: true });
writeFileSync(resolve(here, "out/live-create.png"), Buffer.from(png));

console.log(JSON.stringify({ ok: failures.length === 0, failures, file: file.fileId, render: "test/out/live-create.png" }, null, 2));
if (failures.length) process.exit(1);
