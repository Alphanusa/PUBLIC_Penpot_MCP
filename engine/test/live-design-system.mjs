// Live end-to-end test of the design system features against a real Penpot
// (2.18+; the deploy bundle uses 2.18.4):
//   - tokens (core + light/dark sets, Mode themes, typography and shadow
//     tokens), exported as DTCG and imported into a second file;
//   - a component library: a Button variant set built with tokens, an icon
//     imported from SVG, annotations;
//   - a screen with a grid layout, copies of the components, a variant swap,
//     effects and rich text, a prototype flow and export settings;
//   - copies following an edit of their main, audit_design, a themed render,
//     exports, a version and a comment.
//
// It CREATES a project in a TEST account. Nothing is deleted afterwards, so the
// result can be opened in Penpot.
//
// Env:
//   PENPOT_URL     e.g. https://penpot.example.com
//   PENPOT_TOKEN   access token of a TEST account
//   TEAM_ID        optional team for the project
//
// Usage: node test/live-design-system.mjs   (after `npm run build`)

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
const out = (name, bytes) => writeFileSync(resolve(here, "out", name), bytes);

const project = await engine.createProject({ ...ctx, name: `MCP design system test ${stamp}`, teamId: process.env.TEAM_ID });
console.log("project:", project.projectId, project.teamName ?? project.teamId);
const ds = await engine.createFile({ ...ctx, projectId: project.projectId, name: "Design system", pages: ["Components", "Screens"],
                                     shared: true });
const [componentsPage, screensPage] = ds.pages;

// tokens
const tokens = await engine.editTokens({ ...ctx, fileId: ds.fileId, ops: [
    { op: "addSet", name: "core" },
    { op: "addToken", set: "core", type: "color", name: "color.blue.500", value: "#3366ff" },
    { op: "addToken", set: "core", type: "color", name: "color.gray.900", value: "#111827" },
    { op: "addToken", set: "core", type: "spacing", name: "space.2", value: "8" },
    { op: "addToken", set: "core", type: "spacing", name: "space.4", value: "{space.2} * 2" },
    { op: "addToken", set: "core", type: "border-radius", name: "radius.md", value: "8" },
    { op: "addToken", set: "core", type: "typography", name: "type.label",
      value: { fontFamily: "Source Sans Pro", fontSize: "14", fontWeight: "700", lineHeight: "1.2", letterSpacing: "0.2" } },
    { op: "addToken", set: "core", type: "shadow", name: "elevation.1",
      value: [{ offsetX: "0", offsetY: "2", blur: "8", spread: "0", color: "#11182733" }] },
    { op: "addSet", name: "light" },
    { op: "addToken", set: "light", type: "color", name: "color.bg", value: "#ffffff" },
    { op: "addToken", set: "light", type: "color", name: "color.primary", value: "{color.blue.500}" },
    { op: "addSet", name: "dark" },
    { op: "addToken", set: "dark", type: "color", name: "color.bg", value: "{color.gray.900}" },
    { op: "addToken", set: "dark", type: "color", name: "color.primary", value: "#99bbff" },
    { op: "addTheme", group: "Mode", name: "Light", sets: ["core", "light"], active: true },
    { op: "addTheme", group: "Mode", name: "Dark", sets: ["core", "dark"] },
]});
console.log("tokens:", J(tokens.themes.map((t) => [t.name, t.active])));

// components: button variants with tokens, an icon from SVG
const ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
  stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>`;
const button = (ref, x, tokens, extra = {}) => [
    { op: "create", type: "frame", ref, name: ref, x, y: 0, width: 10, height: 10, sizing: { h: "hug", v: "hug" },
      layout: { direction: "row", gap: 8, alignItems: "center" }, tokens: { padding: "space.4", radius: "radius.md", ...tokens }, ...extra },
    { op: "create", type: "text", parent: ref, name: "Label", text: "Button", textColor: "#ffffff", tokens: { typography: "type.label" } },
];
const lib = await engine.applyOps({ ...ctx, fileId: ds.fileId, pageId: componentsPage.id, ops: [
    { op: "create", type: "frame", ref: "icon", name: "Icons / Check", x: 0, y: 200, width: 24, height: 24, fill: "none" },
    { op: "create", type: "svg", parent: "icon", svg: ICON, width: 24, height: 24, color: "#111827" },
    { op: "makeComponent", id: "icon", annotation: "Confirmation, success" },
    ...button("primary", 0, { fill: "color.primary", shadow: "elevation.1" }),
    ...button("secondary", 200, { fill: "color.bg" }, { stroke: { color: "#3366ff", width: 1 } }),
    { op: "makeVariants", ref: "button", name: "Buttons / Button", variants: [
        { id: "primary", properties: { Type: "Primary" } },
        { id: "secondary", properties: { Type: "Secondary" } }] },
    { op: "annotate", id: "primary", annotation: "One primary action per screen" },
]});
console.log("library:", lib.changes, "changes,", lib.components?.length, "components");
check(lib.saved, "library saved");

// screen: grid, copies, swap, effects, rich text, prototype
const screen = await engine.applyOps({ ...ctx, fileId: ds.fileId, pageId: screensPage.id, ops: [
    { op: "create", type: "frame", ref: "home", name: "Home", width: 390, height: 600, tokens: { fill: "color.bg" },
      layout: { type: "grid", columns: ["1fr", "1fr"], rows: [80, "auto", "1fr"], gap: 16, padding: 24 } },
    { op: "create", type: "text", parent: "home", name: "Title", cell: { row: 1, column: 1, columnSpan: 2 }, fontSize: 28,
      spans: [{ text: "Hello " }, { text: "Penpot", fontWeight: "700", textColor: "#3366ff" }] },
    { op: "create", type: "component", ref: "ok", parent: "home", component: "Buttons / Button", variant: { Type: "Primary" },
      texts: { Label: "Continue" }, cell: { row: 2, column: 1 } },
    { op: "create", type: "component", ref: "cancel", parent: "home", component: "Buttons / Button", variant: { Type: "Primary" },
      texts: { Label: "Cancel" }, cell: { row: 2, column: 2 } },
    { op: "swap", id: "cancel", variant: { Type: "Secondary" } },
    { op: "create", type: "rect", ref: "hero", parent: "home", name: "Hero", cell: { row: 3, column: 1, columnSpan: 2 },
      sizing: { h: "fill", v: "fill" }, radius: 16,
      fill: { gradient: { type: "linear", angle: 135, stops: ["#3366ff", "#00aa88"] } },
      shadow: { y: 8, blur: 24, color: "#0b1b3f", opacity: 0.15 }, exports: [{ type: "png", scale: 2, suffix: "@2x" }] },
    { op: "create", type: "frame", ref: "next", name: "Next", x: 450, width: 390, height: 600, fill: "#ffffff" },
    { op: "update", id: "ok", interactions: [{ trigger: "click", action: "navigate", destination: "next",
                                               animation: { type: "slide", direction: "left" } }] },
    { op: "flow", name: "Main", start: "home" },
]});
console.log("screen:", screen.changes, "changes; flows", J(screen.flows));
check(screen.saved, "screen saved");

// edit the main: copies follow
const mainEdit = await engine.applyOps({ ...ctx, fileId: ds.fileId, pageId: componentsPage.id, ops: [
    { op: "update", id: lib.created.primary, radius: 999 }]});
console.log("copies updated:", mainEdit.copiesUpdated);
check(mainEdit.copiesUpdated >= 1, "copies follow the main");

// checks, renders, exports
const audit = await engine.auditDesign({ ...ctx, fileId: ds.fileId });
console.log("audit:", audit.score, J(audit.summary));
out("live-ds-home.png", await engine.renderShape({ ...ctx, fileId: ds.fileId, pageId: screensPage.id, objectId: screen.created.home, scale: 1 }));
out("live-ds-home-dark.png", await engine.renderShape({ ...ctx, fileId: ds.fileId, pageId: screensPage.id, objectId: screen.created.home,
                                                         scale: 1, theme: "Mode/Dark" }));
out("live-ds-icon.svg", await engine.renderShape({ ...ctx, fileId: ds.fileId, pageId: componentsPage.id, objectId: lib.created.icon, format: "svg" }));
out("live-ds-hero.pdf", await engine.renderShape({ ...ctx, fileId: ds.fileId, pageId: screensPage.id, objectId: screen.created.hero, format: "pdf" }));

// tokens to code and into another file
const dtcg = await engine.getTokens({ ...ctx, fileId: ds.fileId, format: "dtcg" });
writeFileSync(resolve(here, "out/live-ds-tokens.json"), JSON.stringify(dtcg.json, null, 2));
const app = await engine.createFile({ ...ctx, projectId: project.projectId, name: "App" });
const imported = await engine.editTokens({ ...ctx, fileId: app.fileId, ops: [{ op: "import", tokens: dtcg.json, mode: "replace" }] });
check(imported.imported?.[0]?.tokens >= 10, `tokens imported: ${J(imported.imported)}`);

// versions and comments
const versions = await engine.fileVersions({ ...ctx, fileId: ds.fileId, action: "create", label: "Design system v1" });
check(versions.versions.some((v) => v.label === "Design system v1"), `version saved: ${J(versions.versions)}`);
const comments = await engine.comments({ ...ctx, fileId: ds.fileId, action: "create", pageId: screensPage.id,
                                         shapeId: screen.created.hero, content: "Hero uses the brand gradient (MCP test)" });
check(comments.threads.length >= 1, `comment created: ${J(comments.threads)}`);

console.log("open:", `${ctx.baseUri.replace(/\/$/, "")}/#/workspace?team-id=${project.teamId}&file-id=${ds.fileId}`);
console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
