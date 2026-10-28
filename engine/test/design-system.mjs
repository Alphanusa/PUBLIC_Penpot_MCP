// Offline tests for the design system features of edit_page and friends:
//   - variants (makeVariants, setVariant), swap, detach, resetOverrides,
//     annotations, and copies following their main after an edit
// No Penpot backend needed.
//
// Usage: node test/design-system.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");
const engine = await import(resolve(dist, "engine.js"));
await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));
mkdirSync(resolve(here, "out"), { recursive: true });

const failures = [];
const check = (cond, msg) => cond || failures.push(msg);
const J = (v) => JSON.stringify(v);
const section = (name) => console.log(`--- ${name}`);

async function expectError(name, fn, pattern) {
    try {
        await fn();
        failures.push(`${name}: expected an error matching ${pattern}`);
    } catch (e) {
        check(pattern.test(e.message), `${name}: ${J(e.message)} does not match ${pattern}`);
    }
}

/** Minimal PNG decoder (8-bit RGB/RGBA, no interlace), as in features.mjs. */
function decodePng(bytes) {
    const buf = Buffer.from(bytes);
    let pos = 8;
    let width, height, colorType;
    const idat = [];
    while (pos < buf.length) {
        const len = buf.readUInt32BE(pos);
        const type = buf.toString("ascii", pos + 4, pos + 8);
        const data = buf.subarray(pos + 8, pos + 8 + len);
        if (type === "IHDR") {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            colorType = data[9];
        } else if (type === "IDAT") idat.push(data);
        else if (type === "IEND") break;
        pos += 12 + len;
    }
    const bpp = colorType === 6 ? 4 : 3;
    const raw = inflateSync(Buffer.concat(idat));
    const stride = width * bpp;
    const out = Buffer.alloc(height * stride);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        for (let x = 0; x < stride; x++) {
            const v = raw[y * (stride + 1) + 1 + x];
            const a = x >= bpp ? out[y * stride + x - bpp] : 0;
            const b = y > 0 ? out[(y - 1) * stride + x] : 0;
            const c = x >= bpp && y > 0 ? out[(y - 1) * stride + x - bpp] : 0;
            const p = a + b - c;
            const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
            const paeth = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            const pred = [0, a, b, (a + b) >> 1, paeth][filter];
            out[y * stride + x] = (v + pred) & 0xff;
        }
    }
    return { width, height, pixel: (x, y) => [...out.subarray(y * stride + x * bpp, y * stride + x * bpp + 3)] };
}

const pageNamed = (res, name) => res.pages.find((p) => p.name === name);
const objectsOf = (res, name) => pageNamed(res, name).objects;
const textOf = (shape) => {
    const walk = (n) => (typeof n?.text === "string" ? n.text : (n?.children ?? []).map(walk).join(""));
    return walk(shape?.content);
};
const childrenNamed = (objects, parentId, name) =>
    (objects[parentId]?.shapes ?? []).map((id) => objects[id]).filter((s) => s?.name === name);
const descendants = (objects, id) => {
    const out = [];
    const walk = (i) => (objects[i]?.shapes ?? []).forEach((c) => { out.push(objects[c]); walk(c); });
    walk(id);
    return out;
};

// ================================================================ variants
section("variants");

const button = (ref, name, x, fill, color, stroke) => [
    { op: "create", type: "frame", ref, name, x, y: 0, width: 10, height: 10, fill, ...(stroke ? { stroke } : {}),
      radius: 8, layout: { direction: "row", padding: [8, 16], alignItems: "center" }, sizing: { h: "hug", v: "hug" } },
    { op: "create", type: "text", parent: ref, name: "Label", text: "Button", textColor: color, fontSize: 14 },
];

const kit = await engine.libraryOffline([
    ...button("primary", "Primary", 0, "#3366ff", "#ffffff"),
    ...button("secondary", "Secondary", 200, "#ffffff", "#3366ff", { color: "#3366ff", width: 1 }),
    { op: "makeVariants", ref: "button", name: "Buttons / Button", variants: [
        { id: "primary", properties: { Type: "Primary" } },
        { id: "secondary", properties: { Type: "Secondary" } },
    ] },
    { op: "create", type: "frame", ref: "badge", name: "Badge", x: 0, y: 200, width: 40, height: 20, fill: "#e8593c" },
    { op: "makeComponent", id: "badge", annotation: "Counts unread items. Keep it under 3 digits." },
], [], { name: "Kit", pages: ["Screens"] });

const kitObjects = objectsOf(kit, "Components");
const setId = kit.created.button;
const set = kitObjects[setId];
check(set?.["is-variant-container"] === true, `variant set board: ${J(set && { name: set.name, v: set["is-variant-container"] })}`);
check(set?.layout === "flex" && set?.["layout-item-h-sizing"] === "auto", `the set board arranges the variants: ${J(set?.layout)}`);
check(set?.strokes?.[0]?.["stroke-color"] === "#bb97d8" && (set?.fills ?? []).length === 0, `set board looks like the editor's: ${J(set?.strokes)}`);
check(J(set?.shapes) === J([kit.created.secondary, kit.created.primary]), `variants in order (Primary first in the layout): ${J(set?.shapes)}`);
const comps = Object.values(kit.components);
const variantComps = comps.filter((c) => c.variantId === setId);
check(variantComps.length === 2, `two variant components: ${J(comps)}`);
check(variantComps.every((c) => c.name === "Buttons / Button"), `components named after the set: ${J(variantComps.map((c) => c.name))}`);
const primaryComp = variantComps.find((c) => c.mainInstanceId === kit.created.primary);
const secondaryComp = variantComps.find((c) => c.mainInstanceId === kit.created.secondary);
check(J(primaryComp?.variantProperties) === J([{ name: "Type", value: "Primary" }]), `Primary's properties: ${J(primaryComp)}`);
check(kitObjects[kit.created.primary]?.["variant-name"] === "Primary" && kitObjects[kit.created.primary]?.name === "Buttons / Button",
      `main shape: ${J({ n: kitObjects[kit.created.primary]?.name, v: kitObjects[kit.created.primary]?.["variant-name"] })}`);
const badgeComp = comps.find((c) => c.name === "Badge");
check(badgeComp?.annotation === "Counts unread items. Keep it under 3 digits.", `annotation on makeComponent: ${J(badgeComp)}`);
check(kit.summary.components.some((c) => c.name === "Buttons / Button" && c.variant === "Type=Primary"),
      `get_library lists the variants: ${J(kit.summary.components)}`);
check(kit.summary.components.some((c) => c.name === "Badge" && c.annotation), `get_library shows annotations: ${J(kit.summary.components)}`);
writeFileSync(resolve(here, "out/ds-variants.png"), await engine.renderFileOffline(kit.library, setId, { scale: 1 }));

// properties: add one, rename, remove; set one variant's value
const props = await engine.editFileOffline(kit.library, [
    { op: "setVariant", id: kit.created.primary, properties: { Size: "M" } },
    { op: "setVariant", id: setId, rename: { Type: "Kind" } },
    { op: "annotate", id: kit.created.primary, annotation: "Main call to action" },
]);
{
    const vc = Object.values(props.components).filter((c) => c.variantId === setId);
    const p = vc.find((c) => c.mainInstanceId === kit.created.primary);
    const s = vc.find((c) => c.mainInstanceId === kit.created.secondary);
    check(J(p?.variantProperties) === J([{ name: "Kind", value: "Primary" }, { name: "Size", value: "M" }]), `Primary after setVariant: ${J(p)}`);
    check(J(s?.variantProperties) === J([{ name: "Kind", value: "Secondary" }, { name: "Size", value: "" }]), `Secondary gets the new property: ${J(s)}`);
    check(objectsOf(props, "Components")[kit.created.primary]?.["variant-name"] === "Primary, M", "variant name follows the values");
    check(p?.annotation === "Main call to action", `annotate: ${J(p?.annotation)}`);
}
const removed = await engine.editFileOffline(props.file, [{ op: "setVariant", id: setId, remove: ["Size"] }]);
check(Object.values(removed.components).filter((c) => c.variantId === setId).every((c) => c.variantProperties.length === 1),
      "remove drops a property from every variant");

await expectError("same values twice", () => engine.editFileOffline(kit.library, [
    { op: "setVariant", id: kit.created.secondary, properties: { Type: "Primary" } }]), /must differ/);
await expectError("variants without properties", () => engine.libraryOffline([
    ...button("a", "A", 0, "#000000", "#ffffff"),
    { op: "makeVariants", name: "X", variants: [{ id: "a" }] }], []), /needs properties/);
await expectError("setVariant on a plain board", () => engine.editFileOffline(kit.library, [
    { op: "setVariant", id: kit.created.badge, properties: { A: "b" } }]), /not a variant/);
await expectError("properties on the set", () => engine.editFileOffline(kit.library, [
    { op: "setVariant", id: setId, properties: { Type: "x" } }]), /one variant/);

// a third variant joins the existing set
const ghost = await engine.editFileOffline(kit.library, [
    ...button("ghost", "Ghost", 0, "none", "#3366ff").map((op) => (op.ref === "ghost" ? { ...op, y: 400 } : op)),
    { op: "makeVariants", container: setId, variants: [{ id: "ghost", properties: { Type: "Ghost" } }] },
]);
{
    const vc = Object.values(ghost.components).filter((c) => c.variantId === setId);
    check(vc.length === 3 && vc.some((c) => J(c.variantProperties) === J([{ name: "Type", value: "Ghost" }])),
          `Ghost joins the set: ${J(vc.map((c) => c.variantProperties))}`);
    const setNow = objectsOf(ghost, "Components")[setId];
    check(setNow.shapes.length === 3 && setNow.shapes[0] === ghost.result.created.ghost, `Ghost is the last variant: ${J(setNow.shapes)}`);
}

// ================================================= copies: swap, sync, detach, reset
section("copies");

const screen = await engine.editFileOffline(kit.library, [
    { op: "create", type: "frame", ref: "screen", name: "Screen", width: 400, height: 300, fill: "#f4f1ea",
      layout: { direction: "column", gap: 12, padding: 24 } },
    { op: "create", type: "component", ref: "save", parent: "screen", component: "Buttons / Button", variant: { Type: "Primary" },
      texts: { Label: "Save" } },
    { op: "create", type: "component", ref: "cancel", parent: "screen", component: "Buttons / Button", variant: { Type: "Primary" } },
    { op: "create", type: "component", ref: "plain", parent: "screen", component: "Buttons / Button", variant: { Type: "Primary" },
      texts: { Label: "Plain" } },
    { op: "create", type: "component", ref: "reset", parent: "screen", component: "Buttons / Button", variant: { Type: "Primary" },
      texts: { Label: "Changed" } },
    { op: "swap", id: "cancel", variant: { Type: "Secondary" } },
], { pageId: "Screens" });
const scr = objectsOf(screen, "Screens");
const ids = screen.result.created;
check(scr[ids.cancel]?.["component-id"] === secondaryComp.id && scr[ids.save]?.["component-id"] === primaryComp.id,
      `variant switch: ${J([scr[ids.cancel]?.["component-id"], secondaryComp.id])}`);
check(scr[ids.screen].shapes.indexOf(ids.cancel) === scr[ids.screen].shapes.length - 2, `the swapped copy keeps its place: ${J(scr[ids.screen].shapes)}`);
{
    const label = descendants(scr, ids.cancel).find((s) => s.name === "Label");
    check(label && label.content && textOf(label) === "Button", `swapped copy has Secondary's label: ${textOf(label)}`);
    const swapped = screen.result.shapes.find((s) => s.id === ids.cancel);
    check(swapped?.component?.name === "Buttons / Button" && swapped?.component?.variant === "Type=Secondary",
          `summary names the component and variant: ${J(swapped?.component)}`);
}

// keep overrides on a variant switch
const switched = await engine.editFileOffline(screen.file, [{ op: "swap", id: ids.save, variant: { Type: "Secondary" } }], { pageId: "Screens" });
{
    const o = objectsOf(switched, "Screens");
    const label = descendants(o, ids.save).find((s) => s.name === "Label");
    check(o[ids.save]?.["component-id"] === secondaryComp.id && textOf(label) === "Save", `override kept on switch: ${textOf(label)}`);
}
const swapToBadge = await engine.editFileOffline(screen.file, [{ op: "swap", id: ids.plain, component: "Badge" }], { pageId: "Screens" });
check(objectsOf(swapToBadge, "Screens")[ids.plain]?.["component-id"] === badgeComp.id, "swap to another component");
await expectError("swap a plain board", () => engine.editFileOffline(screen.file, [{ op: "swap", id: ids.screen, component: "Badge" }], { pageId: "Screens" }),
                  /needs a component copy/);
await expectError("no such variant", () => engine.editFileOffline(screen.file, [{ op: "swap", id: ids.save, variant: { Type: "Huge" } }], { pageId: "Screens" }),
                  /No variant has Type=Huge/);

// editing a main updates its copies on other pages
const mainEdit = await engine.editFileOffline(screen.file, [{ op: "update", id: kit.created.primary, fill: "#00aa88" }]);
{
    const o = objectsOf(mainEdit, "Screens");
    check(o[ids.save]?.fills?.[0]?.["fill-color"] === "#00aa88", `copy follows its main: ${J(o[ids.save]?.fills)}`);
    check(o[ids.cancel]?.fills?.[0]?.["fill-color"] === "#ffffff", "copies of other variants unchanged");
    check(mainEdit.result.copiesUpdated >= 1, `copiesUpdated reported: ${mainEdit.result.copiesUpdated}`);
    const label = descendants(o, ids.save).find((s) => s.name === "Label");
    check(textOf(label) === "Save", "text override kept by the sync");
}

// detach and reset
const detached = await engine.editFileOffline(screen.file, [
    { op: "detach", id: ids.plain },
    { op: "resetOverrides", id: ids.reset },
], { pageId: "Screens" });
{
    const o = objectsOf(detached, "Screens");
    check(!o[ids.plain]?.["component-id"] && !o[ids.plain]?.["shape-ref"], `detached: ${J(o[ids.plain]?.["component-id"])}`);
    check(descendants(o, ids.plain).every((s) => !s["shape-ref"]), "detached layers are plain layers");
    const label = descendants(o, ids.reset).find((s) => s.name === "Label");
    check(textOf(label) === "Button", `reset drops the text override: ${textOf(label)}`);
}

// ================================================================ vectors
section("vectors");

const ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
  stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>`;
const vec = await engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "box", name: "Icons", width: 300, height: 80, fill: "#ffffff",
      layout: { direction: "row", gap: 8, padding: 8, alignItems: "center" } },
    { op: "create", type: "svg", ref: "check", parent: "box", name: "Check", svg: ICON, width: 48, height: 48, color: "#3366ff" },
    { op: "create", type: "path", ref: "tri", parent: "box", name: "Triangle", d: "M0 0 L24 12 L0 24 Z", fill: "#e8593c",
      width: 48, height: 48 },
    { op: "create", type: "path", ref: "line", parent: "box", name: "Line", d: "M0 0 L40 0" },
    { op: "create", type: "frame", ref: "plain", parent: "box", name: "Plain", width: 80, height: 60, fill: "#f4f1ea" },
    { op: "create", type: "rect", ref: "a", parent: "plain", x: 10, y: 10, width: 30, height: 30, fill: "#00aa88" },
    { op: "create", type: "ellipse", ref: "b", parent: "plain", x: 25, y: 20, width: 30, height: 30, fill: "#3366ff" },
    { op: "boolean", ref: "u", ids: ["a", "b"], kind: "union", name: "Blob" },
    { op: "create", type: "rect", ref: "c", parent: "box", width: 10, height: 10, fill: "#000000" },
    { op: "create", type: "rect", ref: "d", parent: "box", width: 10, height: 10, fill: "#000000" },
    { op: "group", ref: "g", ids: ["c", "d"], name: "Pair" },
], { renderRef: "box", scale: 2 });
writeFileSync(resolve(here, "out/ds-vectors.png"), vec.png);
{
    const o = vec.objects, c = vec.created;
    const icon = o[c.check];
    check(icon?.type === "group" && Math.round(icon.selrect.width) === 48 && Math.round(icon.selrect.height) === 48,
          `svg imported as a 48px group: ${J(icon && { t: icon.type, w: icon.selrect.width, h: icon.selrect.height })}`);
    const strokes = descendants(o, c.check).flatMap((s) => s?.strokes ?? []);
    check(strokes.length > 0 && strokes.every((s) => s["stroke-color"] === "#3366ff"), `icon tinted: ${J(strokes.map((s) => s["stroke-color"]))}`);
    check(descendants(o, c.check).some((s) => s?.type === "path"), "svg path became a Penpot path");
    const tri = o[c.tri];
    check(tri?.type === "path" && Math.round(tri.selrect.width) === 48 && tri.fills?.[0]?.["fill-color"] === "#e8593c",
          `path scaled and filled: ${J(tri && { t: tri.type, w: tri.selrect.width })}`);
    check(o[c.line]?.strokes?.[0]?.["stroke-alignment"] === "center", `a path without style gets a centered stroke: ${J(o[c.line]?.strokes)}`);
    const u = o[c.u];
    check(u?.type === "bool" && u["bool-type"] === "union" && J(u.shapes?.slice().sort()) === J([c.a, c.b].sort()),
          `boolean union: ${J(u && { t: u.type, k: u["bool-type"], s: u.shapes })}`);
    check(u?.fills?.[0]?.["fill-color"] === "#3366ff", `boolean takes the top shape's fill: ${J(u?.fills)}`);
    const g = o[c.g];
    check(g?.type === "group" && g.shapes.length === 2 && g.parent === undefined && g["parent-id"] === c.box,
          `group in the layout: ${J(g && { t: g.type, p: g["parent-id"] })}`);
    const summary = vec.shapes.find((s) => s.id === c.u);
    check(summary?.bool === "union", `summary shows the boolean kind: ${J(summary)}`);
}
const ungrouped = await engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "f", width: 100, height: 100 },
    { op: "create", type: "rect", ref: "x", parent: "f", width: 10, height: 10 },
    { op: "create", type: "rect", ref: "y", parent: "f", x: 20, width: 10, height: 10 },
    { op: "group", ref: "g", ids: ["x", "y"] },
    { op: "ungroup", id: "g" },
]);
check(!ungrouped.objects[ungrouped.created.g] && ungrouped.objects[ungrouped.created.x]["parent-id"] === ungrouped.created.f,
      "ungroup moves the layers back to the board");
await expectError("bad svg", () => engine.applyOpsOffline([{ op: "create", type: "svg", svg: "<svg><path></svg>" }]), /not valid SVG/);
await expectError("bad path", () => engine.applyOpsOffline([{ op: "create", type: "path", d: "hello" }]), /path data/i);
await expectError("boolean of boards", () => engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "a", width: 10, height: 10 }, { op: "create", type: "frame", ref: "b", width: 10, height: 10 },
    { op: "boolean", ids: ["a", "b"] }]), /Boards can't be combined/);

// ================================================================ effects
section("effects and rich text");

const fx = await engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "card", name: "Card", width: 240, height: 140, radius: 16,
      fill: { gradient: { type: "linear", angle: 90, stops: ["#3366ff", "#00aa88"] } },
      shadow: [{ y: 8, blur: 24, color: "#0b1b3f", opacity: 0.18 }, { style: "inner", x: 0, y: 1, blur: 0, color: "#ffffff", opacity: 0.4 }],
      stroke: { color: "#ffffff", width: 1, style: "dashed" }, blendMode: "multiply" },
    { op: "create", type: "rect", ref: "glass", parent: "card", x: 20, y: 20, width: 80, height: 40, fill: ["#ffffff", { color: "#000000", opacity: 0.1 }],
      blur: 2, backgroundBlur: 8 },
    { op: "create", type: "text", ref: "t", parent: "card", x: 20, y: 80, width: 200, growType: "auto-height", fontSize: 16, textColor: "#ffffff",
      verticalAlign: "center",
      spans: [{ text: "Total " }, { text: "Rp 1.250.000", fontWeight: "700", textDecoration: "underline" }, { text: "\nper month", fontSize: 12 }] },
], { renderRef: "card", scale: 2 });
writeFileSync(resolve(here, "out/ds-effects.png"), fx.png);
{
    const o = fx.objects, c = fx.created;
    const card = o[c.card];
    const g = card?.fills?.[0]?.["fill-color-gradient"];
    check(g?.type === "linear" && g["start-x"] === 0 && g["end-x"] === 1 && g.stops.length === 2 && g.stops[1].offset === 1,
          `gradient at 90deg goes left to right: ${J(g)}`);
    check(card?.shadow?.length === 2 && card.shadow[0].style === "drop-shadow" && card.shadow[1].style === "inner-shadow"
          && card.shadow[0].color.opacity === 0.18, `shadows: ${J(card?.shadow)}`);
    check(card?.strokes?.[0]?.["stroke-style"] === "dashed" && card["blend-mode"] === "multiply", "dashed stroke and blend mode");
    const glass = o[c.glass];
    check(glass?.fills?.length === 2 && glass.blur?.value === 2 && glass["background-blur"]?.value === 8,
          `two fills, blur, background blur: ${J(glass && { f: glass.fills, b: glass.blur, bb: glass["background-blur"] })}`);
    const t = o[c.t];
    const paras = t?.content?.children?.[0]?.children ?? [];
    check(paras.length === 2, `"\\n" starts a paragraph: ${paras.length}`);
    const spans = paras[0]?.children ?? [];
    check(spans.length === 2 && spans[1]["font-weight"] === "700" && spans[1]["text-decoration"] === "underline"
          && spans[0]["font-weight"] === "400", `spans styled: ${J(spans.map((n) => [n.text, n["font-weight"], n["text-decoration"]]))}`);
    check(paras[1]?.children?.[0]?.["font-size"] === "12" && t?.content?.["vertical-align"] === "center",
          `second paragraph and vertical align: ${J([paras[1]?.children?.[0]?.["font-size"], t?.content?.["vertical-align"]])}`);
    const ts = fx.shapes.find((s) => s.id === c.t);
    check(ts?.mixedStyles && ts.spans?.length === 3 && ts.spans[1].fontWeight === "700", `summary lists the spans: ${J(ts?.spans)}`);
    const cs = fx.shapes.find((s) => s.id === c.card);
    check(cs?.shadow?.length === 2 && cs?.fillGradient?.type === "linear" && cs?.blendMode === "multiply", `summary shows effects: ${J(cs)}`);
}
// removing effects
const noFx = await engine.applyOpsOffline([
    { op: "create", type: "rect", ref: "r", width: 10, height: 10, shadow: { y: 2 }, blur: 3 },
    { op: "update", id: "r", shadow: "none", blur: 0 },
]);
check((noFx.objects[noFx.created.r].shadow ?? []).length === 0 && noFx.objects[noFx.created.r].blur === undefined, "shadow and blur removed");
await expectError("gradient with one stop", () => engine.applyOpsOffline([{ op: "create", type: "rect", fill: { gradient: { stops: ["#000000"] } } }]),
                  /two stops/);
await expectError("bad blend mode", () => engine.applyOpsOffline([{ op: "create", type: "rect", blendMode: "dodgy" }]), /blendMode/);

// ================================================================ grid and layout items
section("grid layout, layout items, constraints");

const grid = await engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "grid", name: "Dashboard", width: 400, height: 300, fill: "#f4f1ea",
      layout: { type: "grid", columns: ["1fr", "1fr", 100], rows: [60, "1fr"], gap: 10, padding: 10 } },
    { op: "create", type: "rect", ref: "header", parent: "grid", name: "Header", fill: "#3366ff", sizing: { h: "fill", v: "fill" },
      cell: { row: 1, column: 1, columnSpan: 3 } },
    { op: "create", type: "rect", ref: "main", parent: "grid", name: "Main", fill: "#00aa88", sizing: { h: "fill", v: "fill" },
      cell: { row: 2, column: 1, columnSpan: 2 } },
    { op: "create", type: "rect", ref: "side", parent: "grid", name: "Side", fill: "#e8593c", sizing: { h: "fill", v: "fill" } },
    { op: "create", type: "frame", ref: "row", name: "Row", x: 0, y: 320, width: 400, height: 60, layout: { direction: "row", gap: 10, padding: 10 } },
    { op: "create", type: "rect", ref: "m", parent: "row", width: 50, height: 40, fill: "#000000", margin: [0, 20, 0, 0] },
    { op: "create", type: "rect", ref: "f", parent: "row", width: 50, height: 40, fill: "#3366ff", sizing: { h: "fill" }, maxWidth: 120, alignSelf: "end" },
    { op: "create", type: "rect", ref: "abs", parent: "row", x: 380, y: 0, width: 20, height: 20, fill: "#e8593c", absolute: true, zIndex: 5 },
    { op: "create", type: "frame", ref: "pin", name: "Pinned", x: 0, y: 400, width: 200, height: 100 },
    { op: "create", type: "rect", ref: "bar", parent: "pin", x: 0, y: 80, width: 200, height: 20, constraints: { h: "leftright", v: "bottom" } },
    { op: "update", id: "pin", height: 150 },
], { renderRef: "grid", scale: 1 });
writeFileSync(resolve(here, "out/ds-grid.png"), grid.png);
{
    const o = grid.objects, c = grid.created;
    const gb = o[c.grid];
    check(gb?.layout === "grid" && gb["layout-grid-columns"]?.length === 3 && gb["layout-grid-rows"]?.length === 2,
          `grid tracks: ${J(gb && [gb["layout-grid-columns"], gb["layout-grid-rows"]])}`);
    const cells = Object.values(gb?.["layout-grid-cells"] ?? {});
    const cellOf = (id) => cells.find((cl) => cl.shapes.includes(id));
    check(cellOf(c.header)?.["column-span"] === 3 && cellOf(c.main)?.row === 2 && cellOf(c.main)?.["column-span"] === 2,
          `cells: ${J(cells.map((cl) => [cl.row, cl.column, cl["row-span"], cl["column-span"], cl.shapes.length]))}`);
    check(cellOf(c.side)?.row === 2 && cellOf(c.side)?.column === 3, `auto-placed in the free cell: ${J(cellOf(c.side))}`);
    const w = (id) => Math.round(o[id].selrect.width), h = (id) => Math.round(o[id].selrect.height);
    // 400 - 2*10 padding - 2*10 gaps = 360; 100 fixed; 260 / 2 = 130 per fr
    check(w(c.header) === 380 && h(c.header) === 60, `header spans the row: ${w(c.header)}x${h(c.header)}`);
    check(w(c.main) === 270 && w(c.side) === 100 && h(c.side) === 210, `main spans 2 fr columns: ${w(c.main)}, side ${w(c.side)}x${h(c.side)}`);
    const ms = grid.shapes.find((s) => s.id === c.main);
    check(ms?.cell?.row === 2 && ms?.cell?.columnSpan === 2, `summary shows the cell: ${J(ms?.cell)}`);
    const gs = grid.shapes.find((s) => s.id === c.grid);
    check(J(gs?.layout?.columns) === J(["1fr", "1fr", 100]), `summary shows the tracks: ${J(gs?.layout)}`);
    // flex item options
    const x = (id) => Math.round(o[id].selrect.x - o[o[id]["parent-id"]].selrect.x);
    check(o[c.m]?.["layout-item-margin"]?.m2 === 20 && x(c.f) === 10 + 50 + 20 + 10, `margin pushes the next item: f at ${x(c.f)}`);
    check(w(c.f) === 120 && o[c.f]["layout-item-align-self"] === "end", `fill capped by maxWidth: ${w(c.f)}`);
    check(o[c.abs]?.["layout-item-absolute"] === true && x(c.abs) === 380, `absolute child keeps its position: ${x(c.abs)}`);
    // constraints
    const bar = o[c.bar], pin = o[c.pin];
    check(bar?.["constraints-v"] === "bottom" && Math.round(bar.selrect.y - pin.selrect.y) === 130,
          `bottom constraint follows the resize: ${bar && Math.round(bar.selrect.y - pin.selrect.y)}`);
}
await expectError("cell outside a grid", () => engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "f", width: 10, height: 10 }, { op: "create", type: "rect", parent: "f", cell: { row: 1, column: 1 } }]),
                  /grid layout/);
await expectError("bad track", () => engine.applyOpsOffline([{ op: "create", type: "frame", layout: { type: "grid", columns: ["wide"] } }]),
                  /track/);

// ================================================================ tokens
section("tokens: typography, shadow, stroke width, rotation, sizing, DTCG");

const tokenFile = (await engine.libraryOffline([], [], { name: "Tokens" })).library;
const tk = await engine.tokensOffline(tokenFile, [
    { op: "addSet", name: "core" },
    { op: "addToken", set: "core", type: "typography", name: "type.label",
      value: { fontFamily: "Source Sans Pro", fontSize: "16", fontWeight: "700", lineHeight: "1.5", letterSpacing: "0.5", textCase: "uppercase" } },
    { op: "addToken", set: "core", type: "shadow", name: "elevation.1",
      value: [{ offsetX: "0", offsetY: "4", blur: "12", spread: "0", color: "#0b1b3f33", inset: false }] },
    { op: "addToken", set: "core", type: "stroke-width", name: "border.thin", value: "2" },
    { op: "addToken", set: "core", type: "rotation", name: "tilt", value: "15" },
    { op: "addToken", set: "core", type: "spacing", name: "space.sm", value: "8" },
    { op: "addToken", set: "core", type: "sizing", name: "size.max", value: "120" },
    { op: "addToken", set: "core", type: "color", name: "color.bg", value: "#ffffff" },
    { op: "addToken", set: "core", type: "color", name: "color.line", value: "#3366ff" },
    { op: "addSet", name: "dark" },
    { op: "addToken", set: "dark", type: "color", name: "color.bg", value: "#111111" },
    { op: "addTheme", group: "Mode", name: "Light", sets: ["core"], active: true },
    { op: "addTheme", group: "Mode", name: "Dark", sets: ["core", "dark"] },
], null, [
    { op: "create", type: "frame", ref: "card", name: "Card", width: 300, height: 200, stroke: "#3366ff",
      layout: { direction: "column", padding: 16, gap: 8 }, tokens: { fill: "color.bg", shadow: "elevation.1", strokeWidth: "border.thin" } },
    { op: "create", type: "text", ref: "t", parent: "card", text: "Hello", growType: "auto-width", tokens: { typography: "type.label" } },
    { op: "create", type: "rect", ref: "r", parent: "card", width: 50, height: 50, fill: "#e8593c",
      tokens: { rotation: "tilt", margin: "space.sm" } },
    { op: "create", type: "rect", ref: "f", parent: "card", height: 20, fill: "#00aa88", sizing: { h: "fill" }, tokens: { maxWidth: "size.max" } },
]);
{
    const o = tk.pages[0].objects, c = tk.pageCreated;
    const card = o[c.card], t = o[c.t], r = o[c.r], f = o[c.f];
    check(card?.shadow?.[0]?.["offset-y"] === 4 && card.shadow[0].blur === 12 && card.shadow[0].color.opacity === 0.2
          && card["applied-tokens"]?.shadow === "elevation.1", `shadow token: ${J(card?.shadow)}`);
    check(card?.strokes?.[0]?.["stroke-width"] === 2 && card.strokes[0]["stroke-color"] === "#3366ff"
          && card["applied-tokens"]?.["stroke-width-top"] === "border.thin", `stroke width token: ${J(card?.strokes)}`);
    const node = t?.content?.children?.[0]?.children?.[0]?.children?.[0];
    check(node?.["font-weight"] === "700" && node["font-size"] === "16" && node["line-height"] === "1.5"
          && node["letter-spacing"] === "0.5" && node["text-transform"] === "uppercase" && t["applied-tokens"]?.typography === "type.label",
          `typography token: ${J(node && [node["font-weight"], node["font-size"], node["line-height"], node["letter-spacing"], node["text-transform"]])}`);
    check(Math.round(r?.rotation) === 15 && r["applied-tokens"]?.rotation === "tilt", `rotation token: ${r?.rotation}`);
    check(r?.["layout-item-margin"]?.m1 === 8 && r["applied-tokens"]?.m1 === "space.sm", `margin token: ${J(r?.["layout-item-margin"])}`);
    check(f?.["layout-item-max-w"] === 120 && Math.round(f.selrect.width) === 120, `max width token caps a fill child: ${f && f.selrect.width}`);
}
// theme switch updates the shapes; a themed render previews it without saving
const light = decodePng(await engine.renderFileOffline(tk.file, tk.pageCreated.card, { scale: 1 }));
const dark = decodePng(await engine.renderFileOffline(tk.file, tk.pageCreated.card, { scale: 1, theme: "Mode/Dark" }));
check(light.pixel(150, 180)[0] > 240 && dark.pixel(150, 180)[0] < 40, `themed render: light ${light.pixel(150, 180)} dark ${dark.pixel(150, 180)}`);
writeFileSync(resolve(here, "out/ds-theme-dark.png"), await engine.renderFileOffline(tk.file, tk.pageCreated.card, { scale: 1, theme: "Mode/Dark" }));
const switched2 = await engine.tokensOffline(tk.file, [{ op: "updateTheme", theme: "Mode/Dark", active: true }]);
check(switched2.shapesUpdated >= 1 && switched2.pages[0].objects[tk.pageCreated.card].fills[0]["fill-color"] === "#111111",
      `theme switch updates the card: ${switched2.shapesUpdated}`);
// DTCG export and import
check(tk.dtcg?.core?.type?.label?.$type === "typography" && tk.dtcg?.$themes?.length === 2, `DTCG export: ${J(Object.keys(tk.dtcg ?? {}))}`);
const fresh = (await engine.libraryOffline([], [], { name: "Fresh" })).library;
const imported = await engine.tokensOffline(fresh, [{ op: "import", tokens: tk.dtcg, mode: "replace" }]);
check(J(imported.imported?.[0]?.sets) === J(["core", "dark"]) && imported.tokens.themes.length === 2
      && imported.tokens.tokens.some((x) => x.name === "type.label"), `DTCG import: ${J(imported.imported)}`);
const merged = await engine.tokensOffline(imported.file, [{ op: "import", setName: "core",
    tokens: JSON.stringify({ color: { bg: { $type: "color", $value: "#fafafa" }, accent: { $type: "color", $value: "#e8593c" } } }) }],
    { set: "core", type: "color" });
{
    const byName = Object.fromEntries(merged.tokens.tokens.map((x) => [x.name, x.value]));
    check(byName["color.bg"] === "#fafafa" && byName["color.accent"] === "#e8593c" && byName["color.line"] === "#3366ff",
          `merge import updates and adds: ${J(byName)}`);
}
await expectError("text token on a board", () => engine.tokensOffline(tk.file, [{ op: "updateSet", set: "core", description: "x" }], null,
    [{ op: "create", type: "frame", tokens: { typography: "type.label" } }]), /applies to texts/);
await expectError("bad import", () => engine.tokensOffline(fresh, [{ op: "import", tokens: "{nope" }]), /not valid JSON/);

// rotation by hand
const rot = await engine.applyOpsOffline([{ op: "create", type: "frame", ref: "f", width: 100, height: 100 },
    { op: "create", type: "rect", ref: "r", parent: "f", x: 25, y: 25, width: 50, height: 50, rotation: 45 }]);
check(Math.round(rot.objects[rot.created.r].rotation) === 45 && rot.shapes.find((x) => x.id === rot.created.r)?.rotation === 45,
      `rotation: ${rot.objects[rot.created.r].rotation}`);

// ================================================================ audit
section("audit_design");

const card = (ref, y) => [
    { op: "create", type: "frame", ref, name: "Card", x: 500, y, width: 200, height: 80, fill: "#ffffff" },
    { op: "create", type: "text", parent: ref, x: 10, y: 10, text: "Title", textColor: "#111111" },
    { op: "create", type: "rect", parent: ref, x: 10, y: 40, width: 30, height: 30, fill: "#3366ff" },
];
const messy = await engine.libraryOffline([
    { op: "create", type: "frame", ref: "screen", name: "Board", width: 400, height: 300, fill: "#ffffff",
      layout: { direction: "column", gap: 13, padding: 16 } },
    { op: "create", type: "text", parent: "screen", text: "Low contrast", textColor: "#cccccc", fontSize: 14 },
    { op: "create", type: "text", parent: "screen", text: "Fine", textColor: "#111111" },
    { op: "create", type: "rect", parent: "screen", width: 50, height: 50, fill: "#3366ff" },
    { op: "create", type: "rect", parent: "screen", width: 50, height: 50, fill: "#3467fe" },
    { op: "create", type: "ellipse", x: 0, y: 400, width: 10, height: 10, fill: "#e8593c" },
    ...card("c1", 0), ...card("c2", 100), ...card("c3", 200),
    { op: "create", type: "frame", ref: "row", name: "Toolbar", x: 0, y: 500, width: 300, height: 40 },
    { op: "create", type: "rect", parent: "row", x: 0, y: 0, width: 40, height: 40, fill: "#3366ff" },
    { op: "create", type: "rect", parent: "row", x: 50, y: 0, width: 40, height: 40, fill: "#3366ff" },
    { op: "create", type: "rect", parent: "row", x: 100, y: 0, width: 40, height: 40, fill: "#3366ff" },
], [{ op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" }], { name: "Messy" });
const report = await engine.auditOffline(messy.library, {});
{
    const has = (cat, re) => report.issues.some((i) => i.category === cat && re.test(i.message + " " + (i.fix ?? "")));
    check(has("contrast", /Low contrast.*AA needs 4.5/), `contrast: ${J(report.issues.filter((i) => i.category === "contrast"))}`);
    check(has("colors", /#3366FF[\s\S]*linkAssets links them to "Brand \/ Primary"/), "raw color with the library match");
    check(has("colors", /almost the same/), "near-duplicate colors");
    check(has("spacing", /13px gap is not a multiple of 4/), `spacing: ${J(report.issues.filter((i) => i.category === "spacing"))}`);
    check(has("naming", /top-level boards have default names/), "default board name");
    check(has("structure", /outside any board/), "floating layer");
    check(has("structure", /"Toolbar" places its children in a row/), "row without layout");
    check(has("components", /3 layers share the structure of "Card"/), `repeated structure: ${J(report.issues.filter((i) => i.category === "components"))}`);
    check(report.score < 100 && report.coverage.colors.total > 0 && report.palette.length > 0, `score and coverage: ${report.score}`);
    const only = await engine.auditOffline(messy.library, { checks: ["contrast"] });
    check(only.issues.every((i) => i.category === "contrast") && only.issues.length >= 1, "checks filter");
}
await expectError("unknown check", () => engine.auditOffline(messy.library, { checks: ["vibes"] }), /Unknown checks/);

// ================================================================ prototypes and exports
section("prototypes, flows, export settings");

const proto = await engine.editFileOffline(kit.library, [
    { op: "create", type: "frame", ref: "home", name: "Home", x: 1000, y: 0, width: 360, height: 640, fill: "#ffffff" },
    { op: "create", type: "frame", ref: "detail", name: "Detail", x: 1400, y: 0, width: 360, height: 640, fill: "#ffffff" },
    { op: "create", type: "frame", ref: "sheet", name: "Sheet", x: 1800, y: 0, width: 360, height: 300, fill: "#ffffff" },
    { op: "create", type: "rect", ref: "cta", parent: "home", width: 100, height: 40, fill: "#3366ff",
      interactions: [
          { trigger: "click", action: "navigate", destination: "detail", animation: { type: "slide", direction: "left" } },
          { trigger: "hover", action: "open-overlay", destination: "sheet", overlay: { position: "bottom-center", closeOnClickOutside: true, background: true } },
      ],
      exports: [{ type: "png", scale: 2, suffix: "@2x" }, "svg"] },
    { op: "update", id: "detail", interactions: [{ trigger: "after-delay", delay: 1500, action: "back" }] },
    { op: "flow", name: "Onboarding", start: "home" },
], { pageId: "Screens" });
{
    const page = pageNamed(proto, "Screens"), o = page.objects, c = proto.result.created;
    const its = o[c.cta]?.interactions ?? [];
    check(its.length === 2 && its[0]["action-type"] === "navigate" && its[0].destination === c.detail
          && its[0].animation?.["animation-type"] === "slide" && its[1]["event-type"] === "mouse-over"
          && its[1]["overlay-pos-type"] === "bottom-center" && its[1]["background-overlay"] === true, `interactions: ${J(its)}`);
    check(o[c.detail]?.interactions?.[0]?.delay === 1500 && o[c.detail].interactions[0]["action-type"] === "prev-screen", "after-delay back");
    check(J(o[c.cta]?.exports) === J([{ type: "png", scale: 2, suffix: "@2x" }, { type: "svg", scale: 1, suffix: "" }]), `exports: ${J(o[c.cta]?.exports)}`);
    check(page.flows.some((f) => f.name === "Onboarding" && f["starting-frame"] === c.home)
          && proto.result.flows?.some((f) => f.name === "Onboarding"), `flow: ${J(page.flows)}`);
    const sum = proto.result.shapes.find((x) => x.id === c.cta);
    check(sum?.interactions?.[0]?.destinationName === "Detail" && sum?.exports?.length === 2, `summary: ${J(sum?.interactions)}`);
}
await expectError("navigate to a nested board", () => engine.editFileOffline(kit.library, [
    { op: "create", type: "frame", ref: "a", width: 10, height: 10 },
    { op: "create", type: "frame", ref: "b", parent: "a", width: 5, height: 5 },
    { op: "update", id: "a", interactions: [{ action: "navigate", destination: "b" }] }], { pageId: "Screens" }), /top-level board/);

console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
