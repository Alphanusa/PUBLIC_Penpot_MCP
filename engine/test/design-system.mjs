// Offline tests for the design system features of edit_page and friends:
//   - variants (makeVariants, setVariant), swap, detach, resetOverrides,
//     annotations, and copies following their main after an edit
// No Penpot backend needed.
//
// Usage: node test/design-system.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

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

console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
