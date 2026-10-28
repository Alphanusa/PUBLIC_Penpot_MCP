// Offline tests for libraries: an in-memory library file with colors, a
// typography and components made from boards (makeComponent), then a page that
// uses them — component instances (with text overrides, inside a flex board),
// library colors on fills/strokes/texts, a typography — and edit_library
// updates synced to the shapes that use them. No Penpot backend needed.
//
// Usage: node test/libraries.mjs   (after `npm run build`)

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
const near = (a, b, tol = 0.5) => Math.abs(a - b) <= tol;
const firstTextNode = (node) =>
    typeof node?.text === "string" ? node : (node?.children ?? []).reduce((hit, c) => hit ?? firstTextNode(c), undefined);

async function expectError(name, fn, pattern) {
    try {
        await fn();
        failures.push(`${name}: expected an error matching ${pattern}`);
    } catch (e) {
        check(pattern.test(e.message), `${name}: ${JSON.stringify(e.message)} does not match ${pattern}`);
    }
}

// ------------------------------------------------------------- the library file

const built = await engine.libraryOffline(
    [
        {
            op: "create", type: "frame", ref: "btn", name: "Button", width: 10, height: 10, fill: "Brand / Primary", radius: 8,
            layout: { direction: "row", padding: [8, 16], alignItems: "center" }, sizing: { h: "hug", v: "hug" },
        },
        { op: "create", type: "text", ref: "label", parent: "btn", name: "Label", text: "Button", fontSize: 14, fontWeight: 600, textColor: "#FFFFFF" },
        { op: "makeComponent", id: "btn", name: "Buttons / Primary" },
        { op: "create", type: "text", ref: "title", y: 100, text: "Library title", typography: "Heading / H1", textColor: "Brand / Ink" },
    ],
    [
        { op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" },
        { op: "add", type: "color", name: "Brand/Ink", color: "#1F1E1D" },
        { op: "add", type: "typography", name: "Heading / H1", fontFamily: "Source Sans Pro", fontWeight: 700, fontSize: 28, lineHeight: 1.2 },
    ]
);
const lib = built.library;
const summary = built.summary;
check(summary.components.length === 1 && summary.components[0].name === "Buttons / Primary", `components: ${JSON.stringify(summary.components)}`);
check(JSON.stringify(summary.colors.map((c) => c.name)) === JSON.stringify(["Brand / Ink", "Brand / Primary"]), `colors: ${JSON.stringify(summary.colors)}`);
check(summary.typographies[0]?.name === "Heading / H1" && summary.typographies[0].fontSize === "28" && summary.typographies[0].fontWeight === "700",
      `typographies: ${JSON.stringify(summary.typographies)}`);
check(summary.components[0].width > 40 && summary.components[0].height > 20, `component size: ${JSON.stringify(summary.components[0])}`);

await expectError(
    "duplicate library color",
    () => engine.libraryOffline([], [
        { op: "add", type: "color", name: "A", color: "#000000" },
        { op: "add", type: "color", name: "a", color: "#ffffff" },
    ]),
    /ops\[1\]: The library already has a library color named "A"/
);
await expectError(
    "unknown key in a library op",
    () => engine.libraryOffline([], [{ op: "add", type: "color", name: "A", colour: "#000000" }]),
    /Unknown property "colour" for add color\. Did you mean "color"\?/
);

// ------------------------------------------------------------- a page using it

const LIB_ID = summary.fileId;
const result = await engine.applyOpsOffline(
    [
        {
            op: "create", type: "frame", ref: "screen", name: "Screen", width: 320, height: 10, fill: "Brand / Ink",
            layout: { direction: "column", gap: 12, padding: 16 }, sizing: { v: "hug" },
        },
        { op: "create", type: "text", ref: "h1", parent: "screen", text: "Settings", typography: "heading/h1", textColor: "Brand / Primary" },
        { op: "create", type: "component", ref: "save", parent: "screen", component: "buttons/primary", texts: { Label: "Simpan perubahan" } },
        { op: "create", type: "component", ref: "plain", parent: "screen", component: "Buttons / Primary" },
        { op: "create", type: "rect", ref: "line", parent: "screen", width: 288, height: 2, fill: "#FFFFFF", stroke: { color: "Brand / Primary", width: 1 } },
    ],
    { libraries: [lib], renderRef: "screen" }
);
writeFileSync(resolve(here, "out/libraries.png"), Buffer.from(result.png));

const objects = result.objects;
const byRef = (ref) => objects[result.created[ref]];
const childrenOf = (id) => (objects[id]?.shapes ?? []).map((c) => [c, objects[c]]);
const fill0 = (o) => o?.fills?.[0] ?? {};

const screen = byRef("screen");
check(fill0(screen)["fill-color-ref-file"] === LIB_ID && fill0(screen)["fill-color"] === "#1f1e1d", `board fill linked to the library: ${JSON.stringify(screen?.fills)}`);
const h1 = firstTextNode(byRef("h1")?.content);
check(h1?.["typography-ref-file"] === LIB_ID && h1?.["font-size"] === "28" && h1?.["font-weight"] === "700", `typography applied: ${JSON.stringify(h1)}`);
check(h1?.fills?.[0]?.["fill-color-ref-file"] === LIB_ID && h1.fills[0]["fill-color"] === "#3366ff", `text color linked: ${JSON.stringify(h1?.fills)}`);
const line = byRef("line");
check(line?.strokes?.[0]?.["stroke-color-ref-file"] === LIB_ID, `stroke linked: ${JSON.stringify(line?.strokes)}`);

const save = byRef("save");
const plain = byRef("plain");
check(save?.["component-root"] === true && save["component-file"] === LIB_ID && save["component-id"], `instance root linked to the component: ${JSON.stringify({ root: save?.["component-root"], file: save?.["component-file"] })}`);
const [[saveLabelId, saveLabel]] = childrenOf(result.created.save);
const [[plainLabelId, plainLabel]] = childrenOf(result.created.plain);
check(saveLabel?.["shape-ref"] && saveLabel["shape-ref"] === plainLabel?.["shape-ref"], `copies reference the main's text: ${saveLabel?.["shape-ref"]}`);
check(firstTextNode(saveLabel?.content)?.text === "Simpan perubahan", `text override: ${firstTextNode(saveLabel?.content)?.text}`);
check((saveLabel?.touched ?? []).includes("content-group"), `text override marks the copy touched: ${JSON.stringify(saveLabel?.touched)}`);
check(firstTextNode(plainLabel?.content)?.text === "Button", `untouched copy keeps the main's text: ${firstTextNode(plainLabel?.content)?.text}`);
check(!(plainLabel?.touched ?? []).length && !(plain?.touched ?? []).length,
      `copy moved by the layout is not touched: root ${JSON.stringify(plain?.touched)}, label ${JSON.stringify(plainLabel?.touched)}`);
const shapes = new Map(result.shapes.map((s) => [s.id, s]));
const saveShape = shapes.get(result.created.save);
const plainShape = shapes.get(result.created.plain);
check(saveShape && plainShape && saveShape.width > plainShape.width, `override re-measures and the hug button grows: ${saveShape?.width} vs ${plainShape?.width}`);
check(near(plainShape?.x, 16) && plainShape.y > saveShape.y, `instances placed by the flex layout: ${JSON.stringify([saveShape?.x, saveShape?.y, plainShape?.x, plainShape?.y])}`);

// errors
const libOpts = { libraries: [lib] };
await expectError("unknown component", () => engine.applyOpsOffline([{ op: "create", type: "component", component: "Buttons/Secondary" }], libOpts),
                  /No component named "Buttons\/Secondary"\. Similar: Buttons \/ Primary/);
await expectError("typography with font props", () => engine.applyOpsOffline([{ op: "create", type: "text", text: "x", typography: "Heading / H1", fontSize: 12 }], libOpts),
                  /A typography sets the font.*remove "fontSize" or the typography/);
await expectError("library color with opacity", () => engine.applyOpsOffline([{ op: "create", type: "rect", fill: "Brand / Primary", fillOpacity: 0.5 }], libOpts),
                  /A library color sets its own opacity/);
await expectError("unknown text layer", () => engine.applyOpsOffline([{ op: "create", type: "component", component: "Buttons / Primary", texts: { Title: "x" } }], libOpts),
                  /No text layer named "Title" in "Buttons \/ Primary"\. Text layers: "Label"/);
await expectError("unknown color", () => engine.applyOpsOffline([{ op: "create", type: "rect", fill: "red" }], libOpts),
                  /No library color named "red".*Colors are "#rrggbb" or the name of a library color/);
await expectError("fill on an instance", () => engine.applyOpsOffline([{ op: "create", type: "component", component: "Buttons / Primary", fill: "#000000" }], libOpts),
                  /A component instance takes the component's look/);

// ------------------------------------------------------------- edit_library + sync

const edited = await engine.editLibraryOffline(lib, [
    { op: "update", type: "color", id: "Brand / Primary", color: "#FF0000" },
    { op: "update", type: "typography", id: "heading/h1", fontSize: 40 },
    { op: "add", type: "color", name: "Brand / Accent", color: "#00AA88", opacity: 0.8 },
    { op: "delete", type: "color", id: "Brand / Ink" },
]);
check(edited.valid === true, "edit_library changes pass the backend schema checks");
const types = edited.changes.map((c) => c.type);
check(types.includes("mod-color") && types.includes("mod-typography") && types.includes("add-color") && types.includes("del-color"),
      `library changes: ${types}`);
check(types.filter((t) => t === "mod-obj").length >= 2, `synced shapes: ${types}`);
const libObjects = Object.entries(edited.objects)
    .filter(([id]) => id !== "00000000-0000-0000-0000-000000000000")
    .map(([, o]) => o);
const button = libObjects.find((o) => o.type === "frame");
const title = libObjects.find((o) => o.type === "text" && firstTextNode(o.content)?.text === "Library title");
check(button?.fills?.[0]?.["fill-color"] === "#ff0000", `main component fill synced: ${JSON.stringify(button?.fills)}`);
check(firstTextNode(title?.content)?.["font-size"] === "40", `typography synced: ${firstTextNode(title?.content)?.["font-size"]}`);
check(title?.selrect?.height > 40, `synced text re-measured: ${title?.selrect?.height}`);
check(edited.summary.colors.map((c) => c.name).join(",") === "Brand / Accent,Brand / Primary" &&
      edited.summary.colors.find((c) => c.name === "Brand / Accent").opacity === 0.8, `colors after edit: ${JSON.stringify(edited.summary.colors)}`);

console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
