// Live end-to-end test of linkAssets, the move op, move_shapes and design
// tokens against a real Penpot (2.18+; the deploy bundle uses 2.18.4):
//   - a design file with two pages and its own colors, a board drawn with raw
//     values, then linkAssets;
//   - shapes reordered and re-parented with the move op;
//   - the board moved to the second page, then copied (with an image) to a
//     new library file of the same project;
//   - token sets, themes and tokens, a theme switch, and tokens applied to a
//     board.
//
// It CREATES a project in a TEST account. Nothing is deleted afterwards, so the
// result can be opened in Penpot.
//
// Env:
//   PENPOT_URL     e.g. https://penpot.example.com
//   PENPOT_TOKEN   access token of a TEST account
//   TEAM_ID        optional team for the project
//
// Usage: node test/live-move-tokens.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");
const engine = await import(resolve(dist, "engine.js"));
await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));
mkdirSync(resolve(here, "out"), { recursive: true });

const ctx = { baseUri: process.env.PENPOT_URL, token: process.env.PENPOT_TOKEN };
if (!ctx.baseUri || !ctx.token) throw new Error("PENPOT_URL and PENPOT_TOKEN are required");

const failures = [];
const check = (cond, msg) => cond || failures.push(msg);
const J = (v) => JSON.stringify(v);
const stamp = new Date().toISOString().slice(0, 16).replace("T", " ");

const project = await engine.createProject({ ...ctx, name: `MCP move & tokens test ${stamp}`, teamId: process.env.TEAM_ID });
console.log("project:", project.projectId, project.teamName ?? project.teamId);

const design = await engine.createFile({ ...ctx, projectId: project.projectId, name: "Design", pages: ["Screens", "Archive"] });
const [screens, archive] = design.pages;
await engine.editLibrary({ ...ctx, fileId: design.fileId, ops: [
    { op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" },
    { op: "add", type: "color", name: "Surface", color: "#FFFFFF" },
]});

// a logo for the image fill
const logo = await engine.uploadImage({ ...ctx, fileId: design.fileId, name: "Logo", data: await engine.renderDemo(), mimeType: "image/png" });

// #12: drawn with raw values, then linked
const drawn = await engine.applyOps({ ...ctx, fileId: design.fileId, pageId: screens.id, ops: [
    { op: "create", type: "frame", ref: "card", name: "Card", width: 320, height: 10, fill: "#ffffff", stroke: { color: "#3366ff", width: 2 },
      layout: { direction: "column", gap: 12, padding: 16 }, sizing: { v: "hug" } },
    { op: "create", type: "text", ref: "a", parent: "card", name: "First", text: "First", textColor: "#3366ff" },
    { op: "create", type: "text", ref: "b", parent: "card", name: "Second", text: "Second" },
    { op: "create", type: "image", ref: "logo", parent: "card", image: logo.id, width: 96 },
    { op: "linkAssets" },
]});
const report = drawn.linkAssets[0];
console.log("linkAssets:", J(report));
check(report.colors.linked.some((e) => e.name === "Brand / Primary" && e.shapes === 2), "raw blue linked to Brand / Primary");
check(report.colors.linked.some((e) => e.name === "Surface"), "raw white linked to Surface");

// #13: move op
const reordered = await engine.applyOps({ ...ctx, fileId: design.fileId, pageId: screens.id, ops: [
    { op: "move", id: drawn.created.b, index: 0 },
]});
const overview = await engine.pageSummary({ ...ctx, fileId: design.fileId, pageId: screens.id, depth: 3 });
const card = overview.tree.children.find((c) => c.id === drawn.created.card);
check(card?.children?.[0]?.name === "Second", `reordered: ${J(card?.children?.map((c) => c.name))}`);
check(card?.stroke?.asset === "Brand / Primary" && card?.fillAsset === "Surface", `overview names the assets: ${J(card)}`);
console.log("move op:", reordered.changes, "changes");

// #13: move_shapes to the other page, then copy to a library file
const moved = await engine.moveShapes({ ...ctx, fileId: design.fileId, ids: [drawn.created.card], toPageId: "Archive", mode: "move", x: 0, y: 0 });
check(moved.saved && moved.ids[drawn.created.card] === drawn.created.card, `moved: ${J(moved)}`);
const library = await engine.createFile({ ...ctx, projectId: project.projectId, name: "Library", pages: ["Components"], shared: true });
await engine.editLibrary({ ...ctx, fileId: library.fileId, ops: [{ op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" }] });
const copied = await engine.moveShapes({ ...ctx, fileId: design.fileId, ids: [drawn.created.card], toFileId: library.fileId,
                                         toPageId: "Components", mode: "copy", x: 0, y: 0 });
console.log("copy:", J({ media: copied.media, assets: copied.assets, ids: copied.ids }));
check(copied.saved && copied.media?.cloned === 1, "image copied to the library file");
check(copied.assets?.remapped?.some((a) => a.name === "Brand / Primary"), "Brand / Primary relinked by name");
check(copied.assets?.unlinked?.some((a) => a.name === "Surface"), "Surface reported as missing in the library");
const png = await engine.renderShape({ ...ctx, fileId: library.fileId, pageId: library.pages[0].id, objectId: copied.ids[drawn.created.card], scale: 1 });
writeFileSync(resolve(here, "out/live-move-copy.png"), png);

// design tokens
const edited = await engine.editTokens({ ...ctx, fileId: design.fileId, ops: [
    { op: "addSet", name: "core" },
    { op: "addToken", set: "core", type: "color", name: "color.primary", value: "#0066ff" },
    { op: "addToken", set: "core", type: "spacing", name: "space.md", value: "12" },
    { op: "addToken", set: "core", type: "spacing", name: "space.lg", value: "{space.md} * 2" },
    { op: "addSet", name: "dark" },
    { op: "addToken", set: "dark", type: "color", name: "color.primary", value: "#99bbff" },
    { op: "addTheme", group: "Mode", name: "Light", sets: ["core"], active: true },
    { op: "addTheme", group: "Mode", name: "Dark", sets: ["core", "dark"] },
]});
console.log("tokens:", J(edited.themes), J(edited.sets));
const tokens = await engine.getTokens({ ...ctx, fileId: design.fileId });
check(tokens.tokens.find((t) => t.name === "space.lg")?.resolved === "24", `resolved: ${J(tokens.tokens)}`);
const themed = await engine.applyOps({ ...ctx, fileId: design.fileId, pageId: archive.id, ops: [
    { op: "create", type: "frame", ref: "chip", name: "Token chip", x: 400, y: 0, width: 10, height: 10, layout: { direction: "row" },
      sizing: { h: "hug", v: "hug" }, tokens: { fill: "color.primary", padding: "space.lg" } },
    { op: "create", type: "text", parent: "chip", text: "Tokens", textColor: "#ffffff" },
]});
const chip = themed.shapes.find((s) => s.id === themed.created.chip);
check(chip?.fill === "#0066ff" && chip?.tokens?.fill === "color.primary", `tokens applied: ${J(chip)}`);
await engine.editTokens({ ...ctx, fileId: design.fileId, ops: [{ op: "updateTheme", theme: "Mode/Dark", active: true }] });
const afterSwitch = await engine.getTokens({ ...ctx, fileId: design.fileId, search: "color.primary" });
console.log("after switching to Dark:", J(afterSwitch.themes.map((t) => [t.name, t.active])));
writeFileSync(resolve(here, "out/live-move-tokens.png"),
              await engine.renderShape({ ...ctx, fileId: design.fileId, pageId: archive.id, objectId: themed.created.chip, scale: 2 }));

console.log("open:", `${ctx.baseUri.replace(/\/$/, "")}/#/workspace?team-id=${project.teamId}&file-id=${design.fileId}`);
console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
