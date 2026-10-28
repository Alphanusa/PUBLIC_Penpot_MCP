// Live end-to-end test of team fonts and libraries against a real Penpot
// (2.15+; the deploy bundle uses 2.18.4): a library file with colors, a
// typography and a component made from a board, a design file linked to it
// that uses them, a library change synced to the design file, and optionally
// a team font and a .penpot package import.
//
// It CREATES a project (and a font, when FONT_FILE is set) in a TEST account.
// Nothing is deleted afterwards, so the result can be opened in Penpot.
//
// Env:
//   PENPOT_URL     e.g. https://penpot.example.com
//   PENPOT_TOKEN   access token of a TEST account
//   TEAM_ID        optional team for the project
//   FONT_FILE      optional TTF/OTF to upload as a team font (family/weight/style
//                  given in FONT_FAMILY, FONT_WEIGHT, FONT_STYLE)
//   PENPOT_PACKAGE optional .penpot file to import as a shared library
//
// Usage: node test/live-library.mjs   (after `npm run build`)

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
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

const project = await engine.createProject({ ...ctx, name: `MCP library test ${stamp}`, teamId: process.env.TEAM_ID });
console.log("project:", project.projectId, project.teamName ?? project.teamId);

// optional team font
let fontFamily = "Source Sans Pro";
if (process.env.FONT_FILE) {
    const font = await engine.uploadFont({
        ...ctx,
        teamId: project.teamId,
        data: readFileSync(process.env.FONT_FILE),
        mimeType: process.env.FONT_FILE.toLowerCase().endsWith(".otf") ? "font/otf" : "font/ttf",
        family: process.env.FONT_FAMILY ?? basename(process.env.FONT_FILE).replace(/\.\w+$/, ""),
        weight: Number(process.env.FONT_WEIGHT ?? 400),
        style: process.env.FONT_STYLE ?? "normal",
    });
    console.log("font:", font);
    fontFamily = font.family;
}

// a library file of our own
const library = await engine.createFile({ ...ctx, projectId: project.projectId, name: "Design system", pages: ["Components"], shared: true });
await engine.editLibrary({ ...ctx, fileId: library.fileId, ops: [
    { op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" },
    { op: "add", type: "color", name: "Brand / Ink", color: "#1F1E1D" },
    { op: "add", type: "typography", name: "Heading / H1", fontFamily, fontWeight: 400, fontSize: 28 },
] });
await engine.applyOps({ ...ctx, fileId: library.fileId, ops: [
    { op: "create", type: "frame", ref: "btn", name: "Button", width: 10, height: 10, fill: "Brand / Primary", radius: 8,
      layout: { direction: "row", padding: [8, 16], alignItems: "center" }, sizing: { h: "hug", v: "hug" } },
    { op: "create", type: "text", parent: "btn", name: "Label", text: "Button", fontWeight: 600, textColor: "#FFFFFF" },
    { op: "makeComponent", id: "btn", name: "Buttons / Primary" },
] });

// a design file using it
const app = await engine.createFile({ ...ctx, projectId: project.projectId, name: "App", pages: ["Screens"] });
await engine.linkLibrary({ ...ctx, fileId: app.fileId, libraryId: library.fileId });
const assets = await engine.libraryAssets({ ...ctx, fileId: app.fileId });
check(assets.some((a) => a.components?.some((c) => c.name === "Buttons / Primary")), `get_library: ${JSON.stringify(assets)}`);
const edit = await engine.applyOps({ ...ctx, fileId: app.fileId, ops: [
    { op: "create", type: "frame", ref: "screen", name: "Settings", width: 360, height: 10, fill: "Brand / Ink",
      layout: { direction: "column", gap: 12, padding: 16 }, sizing: { v: "hug" } },
    { op: "create", type: "text", parent: "screen", text: "Settings", typography: "Heading / H1", textColor: "#FFFFFF" },
    { op: "create", type: "component", parent: "screen", component: "Buttons / Primary", texts: { Label: "Simpan perubahan" } },
    { op: "create", type: "component", parent: "screen", component: "Buttons / Primary" },
] });
check(edit.saved, `edit saved: ${JSON.stringify(edit)}`);

// a library change, synced to the design file
await engine.editLibrary({ ...ctx, fileId: library.fileId, ops: [{ op: "update", type: "color", id: "Brand / Primary", color: "#E8593C" }] });
const synced = await engine.linkLibrary({ ...ctx, fileId: app.fileId, libraryId: library.fileId, sync: true });
check(synced.synced && synced.changes > 0, `sync: ${JSON.stringify(synced)}`);

const png = await engine.renderShape({ ...ctx, fileId: app.fileId, pageId: app.pages[0].id, objectId: edit.created.screen, scale: 1, format: "png" });
mkdirSync(resolve(here, "out"), { recursive: true });
writeFileSync(resolve(here, "out/live-library.png"), Buffer.from(png));

// optional .penpot import
if (process.env.PENPOT_PACKAGE) {
    const imported = await engine.importLibrary({
        ...ctx,
        projectId: project.projectId,
        name: basename(process.env.PENPOT_PACKAGE).replace(/\.penpot$/i, ""),
        data: readFileSync(process.env.PENPOT_PACKAGE),
        linkTo: [app.fileId],
    });
    console.log("imported:", imported);
    const kit = await engine.libraryAssets({ ...ctx, fileId: app.fileId, limit: 10 });
    console.log("assets now:", kit.map((l) => `${l.name}: ${l.totals.components} components, ${l.totals.colors} colors, ${l.totals.typographies} typographies`));
}

console.log(JSON.stringify({ ok: failures.length === 0, failures, library: library.fileId, app: app.fileId, render: "test/out/live-library.png" }, null, 2));
if (failures.length) process.exit(1);
