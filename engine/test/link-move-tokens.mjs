// Offline tests for:
//   #12 linkAssets (raw colors and text styles linked to library assets) and
//       the richer shape summaries (strokes, radius, text styles, asset names)
//   #13 the move op of edit_page and move_shapes (to another page, or copied
//       to another file)
//   design tokens: sets, themes, tokens, activation and resolved values
// No Penpot backend needed.
//
// Usage: node test/link-move-tokens.mjs   (after `npm run build`)

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
const near = (a, b, tol = 0.5) => Math.abs(a - b) <= tol;
const firstTextNode = (node) =>
    typeof node?.text === "string" ? node : (node?.children ?? []).reduce((hit, c) => hit ?? firstTextNode(c), undefined);
const findNode = (node, id) =>
    node?.id === id ? node : (Array.isArray(node?.children) ? node.children : []).reduce((hit, c) => hit ?? findNode(c, id), undefined);
const J = (v) => JSON.stringify(v);

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
    return { width, height, data: out, bpp };
}

// ======================================================= #12 linkAssets

const LIB_OPS = [
    { op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" },
    { op: "add", type: "color", name: "Surface", color: "#FFFFFF" },
    { op: "add", type: "color", name: "On accent", color: "#FFFFFF" },
    { op: "add", type: "color", name: "Ink", color: "#1F1E1D", opacity: 0.5 },
    { op: "add", type: "typography", name: "Body", fontFamily: "Source Sans Pro", fontWeight: 400, fontSize: 16, lineHeight: 1.5 },
];
const kit = await engine.libraryOffline([], LIB_OPS, { name: "Kit" });
const KIT_ID = kit.summary.fileId;
const RAW_PAGE = [
    {
        op: "create", type: "frame", ref: "card", width: 300, height: 10, fill: "#ffffff", radius: 12,
        stroke: { color: "#3366ff", width: 2 }, layout: { direction: "column", gap: 8, padding: 16 }, sizing: { v: "hug" },
    },
    { op: "create", type: "text", ref: "body", parent: "card", text: "Hello", fontSize: 16, lineHeight: 1.5, textColor: "#3366FF" },
    { op: "create", type: "rect", ref: "stray", parent: "card", width: 40, height: 4, fill: "#123456" },
    { op: "create", type: "rect", ref: "linked", parent: "card", width: 40, height: 4, fill: "Ink" },
];
{
    const result = await engine.applyOpsOffline(
        [...RAW_PAGE, { op: "linkAssets", scope: "card", typographies: true }],
        { libraries: [kit.library], renderRef: "card" }
    );
    const [report] = result.linkAssets ?? [];
    const linked = Object.fromEntries((report?.colors?.linked ?? []).map((e) => [e.name, e]));
    check(linked["Brand / Primary"]?.shapes === 2 && linked["Brand / Primary"].value === "#3366ff" && linked["Brand / Primary"].library === "Kit",
          `linked colors: ${J(report?.colors?.linked)}`);
    check(J(report?.colors?.ambiguous) === J([{ value: "#ffffff", candidates: ["On accent (Kit)", "Surface (Kit)"], shapes: 1 }]),
          `ambiguous colors: ${J(report?.colors?.ambiguous)}`);
    check(J(report?.colors?.unmatched) === J([{ value: "#123456", shapes: 1 }]), `unmatched colors: ${J(report?.colors?.unmatched)}`);
    check(report?.typographies?.linked?.[0]?.name === "Body" && report.typographies.linked[0].shapes === 1,
          `linked typographies: ${J(report?.typographies)}`);
    check(report?.scope === result.created.card, `scope: ${report?.scope}`);

    const o = (ref) => result.objects[result.created[ref]];
    check(o("card").strokes[0]["stroke-color-ref-file"] === KIT_ID && o("card").strokes[0]["stroke-color"] === "#3366ff",
          `stroke linked, value unchanged: ${J(o("card").strokes)}`);
    check(!o("card").fills[0]["fill-color-ref-id"], `ambiguous fill stays raw: ${J(o("card").fills)}`);
    check(!o("stray").fills[0]["fill-color-ref-id"], `unmatched fill stays raw: ${J(o("stray").fills)}`);
    const body = firstTextNode(o("body").content);
    check(body?.fills?.[0]?.["fill-color-ref-file"] === KIT_ID && body?.["typography-ref-file"] === KIT_ID,
          `text color and typography linked: ${J(body)}`);

    // summaries name the assets and show strokes, radius and text styles
    const s = Object.fromEntries(result.shapes.map((x) => [x.id, x]));
    const card = s[result.created.card];
    check(J(card?.stroke) === J({ color: "#3366ff", width: 2, align: "inner", asset: "Brand / Primary" }), `stroke summary: ${J(card?.stroke)}`);
    check(card?.radius === 12 && card?.fill === "#ffffff" && card.fillAsset === undefined, `card summary: ${J(card)}`);
    const b = s[result.created.body];
    check(b?.textColor === "#3366ff" && b.textColorAsset === "Brand / Primary", `text color summary: ${J(b)}`);
    check(J(b?.textStyle) === J({ fontFamily: "Source Sans Pro", fontWeight: "400", fontSize: 16, lineHeight: 1.5, typography: "Body" }),
          `text style summary: ${J(b?.textStyle)}`);
    const l = s[result.created.linked];
    check(l?.fillAsset === "Ink" && l.fillOpacity === 0.5, `linked fill summary: ${J(l)}`);
}
{
    const result = await engine.applyOpsOffline([...RAW_PAGE, { op: "linkAssets", prefer: { "#FFFFFF": "Surface" } }], { libraries: [kit.library] });
    const [report] = result.linkAssets;
    check(report.scope === "page" && report.colors.ambiguous.length === 0 && report.colors.linked.some((e) => e.name === "Surface"),
          `prefer resolves the ambiguous value: ${J(report)}`);
    check(report.typographies === undefined, `typographies are off unless asked: ${J(report)}`);
}
const kitOpts = { libraries: [kit.library] };
await expectError("prefer with another value", () => engine.applyOpsOffline([...RAW_PAGE, { op: "linkAssets", prefer: { "#ffffff": "Brand / Primary" } }], kitOpts),
                  /prefer: "Brand \/ Primary" is #3366ff, not #ffffff/);
await expectError("typo in linkAssets", () => engine.applyOpsOffline([{ op: "linkAssets", scopes: "page" }], kitOpts),
                  /Unknown property "scopes" for linkAssets\. Did you mean "scope"\?/);
await expectError("nothing to link", () => engine.applyOpsOffline([{ op: "linkAssets", colors: false }], kitOpts),
                  /linkAssets with colors false needs typographies true/);
{
    // acceptance: after linkAssets, library edits update the shapes
    const own = await engine.libraryOffline(
        [
            { op: "create", type: "frame", ref: "swatch", width: 50, height: 50, fill: "#3366ff" },
            { op: "create", type: "text", ref: "caption", y: 60, text: "Primary", textColor: "#3366FF" },
            { op: "linkAssets" },
        ],
        [{ op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" }]
    );
    check(own.linkAssets?.[0]?.colors?.linked?.[0]?.shapes === 2 && own.linkAssets[0].colors.linked[0].library === undefined,
          `own library linked: ${J(own.linkAssets)}`);
    const synced = await engine.editLibraryOffline(own.library, [{ op: "update", type: "color", id: "Brand / Primary", color: "#00AA88" }]);
    check(synced.objects[own.created.swatch]?.fills?.[0]?.["fill-color"] === "#00aa88", `linked fill follows the library: ${J(synced.objects[own.created.swatch]?.fills)}`);
    check(firstTextNode(synced.objects[own.created.caption]?.content)?.fills?.[0]?.["fill-color"] === "#00aa88", "linked text color follows the library");
}

// ======================================================= #13 move op

{
    const result = await engine.applyOpsOffline(
        [
            { op: "create", type: "frame", ref: "row", width: 10, height: 10, layout: { direction: "row", gap: 10 }, sizing: { h: "hug", v: "hug" } },
            { op: "create", type: "rect", ref: "a", parent: "row", name: "A", width: 10, height: 10 },
            { op: "create", type: "rect", ref: "b", parent: "row", name: "B", width: 20, height: 10 },
            { op: "create", type: "rect", ref: "c", parent: "row", name: "C", width: 30, height: 10 },
            { op: "create", type: "frame", ref: "box", x: 200, y: 0, width: 100, height: 100 },
            { op: "move", id: "c", index: 0 },
            { op: "move", id: "a", parent: "box", x: 5, y: 6 },
            { op: "create", type: "rect", ref: "d", parent: "row", name: "D", width: 5, height: 5 },
            { op: "move", id: "d", parent: null, x: 400, y: 0 },
        ],
        { renderRef: "row" }
    );
    const row = findNode(result.tree, result.created.row);
    check(J(row?.children?.map((c) => c.name)) === J(["C", "B"]), `reordered row: ${J(row?.children?.map((c) => c.name))}`);
    check(near(row?.children?.[0]?.x, 0) && near(row?.children?.[1]?.x, 40), `layout reflowed: ${J(row?.children?.map((c) => c.x))}`);
    check(near(row?.width, 60), `hug row shrinks: ${row?.width}`);
    const box = findNode(result.tree, result.created.box);
    const a = box?.children?.[0];
    check(a?.name === "A" && near(a.x, 5) && near(a.y, 6), `moved into another board: ${J(a)}`);
    check(result.tree.children.some((c) => c.name === "D" && near(c.x, 400)), "moved to the page root");
}
await expectError("move into a child", () => engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "outer" },
    { op: "create", type: "frame", ref: "inner", parent: "outer" },
    { op: "move", id: "outer", parent: "inner" },
]), /can't be moved into itself or one of its children/);
await expectError("move without target", () => engine.applyOpsOffline([{ op: "create", type: "rect", ref: "r" }, { op: "move", id: "r" }]),
                  /move needs "parent"/);
await expectError("update with parent", () => engine.applyOpsOffline([
    { op: "create", type: "frame", ref: "f" }, { op: "create", type: "rect", ref: "r" }, { op: "update", id: "r", parent: "f" },
]), /use the move op/);

// ======================================================= #13 move_shapes

const LOGO = "0f9e1c2a-1111-4222-8333-444455556666";
const NEW_LOGO = "0f9e1c2a-2222-4222-8333-444455556666";
const png = await engine.renderDemo();
const images = { [LOGO]: png, [NEW_LOGO]: png };
const design = await engine.libraryOffline(
    [
        {
            op: "create", type: "frame", ref: "btn", name: "Button", width: 10, height: 10, fill: "Brand / Primary", radius: 8,
            layout: { direction: "row", padding: [8, 16] }, sizing: { h: "hug", v: "hug" },
        },
        { op: "create", type: "text", ref: "label", parent: "btn", name: "Label", text: "Button", textColor: "#FFFFFF" },
        { op: "makeComponent", id: "btn", name: "Buttons / Primary" },
        {
            op: "create", type: "frame", ref: "card", name: "Card", x: 0, y: 200, width: 320, height: 10, fill: "#FFFFFF",
            stroke: { color: "Brand / Ink", width: 1 }, layout: { direction: "column", gap: 12, padding: 16 }, sizing: { v: "hug" },
        },
        { op: "create", type: "text", ref: "title", parent: "card", text: "Card title", typography: "Heading / H1", textColor: "Brand / Ink" },
        { op: "create", type: "image", ref: "logo", parent: "card", image: LOGO, width: 64 },
        { op: "create", type: "component", ref: "cta", parent: "card", component: "Buttons / Primary", texts: { Label: "Go" } },
    ],
    [
        { op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" },
        { op: "add", type: "color", name: "Brand / Ink", color: "#1F1E1D" },
        { op: "add", type: "typography", name: "Heading / H1", fontFamily: "Source Sans Pro", fontWeight: 700, fontSize: 28 },
    ],
    { name: "Design", pages: ["Archive"], images }
);
const C = design.created;
const [componentsPage, archivePage] = design.pages;
const before = componentsPage.objects;

{
    const mv = await engine.moveShapesOffline(design.library, { ids: [C.card, C.btn], toPage: "Archive", mode: "move", x: 0, y: 0, images });
    check(mv.valid === true, "move: changes pass the backend schema checks");
    check(mv.result.ids[C.card] === C.card && mv.result.ids[C.btn] === C.btn, `move keeps ids: ${J(mv.result.ids)}`);
    const [p1, p2] = mv.pages;
    check(!p1.objects[C.card] && !p1.objects[C.btn] && !p1.objects[C.title], "moved shapes left the source page");
    const card = p2.objects[C.card];
    check(card && J(card.shapes) === J(before[C.card].shapes), `children and order kept: ${J(card?.shapes)} vs ${J(before[C.card].shapes)}`);
    check(card?.layout === "flex" && card.strokes[0]["stroke-color-ref-id"], "layout and library refs kept");
    check(near(card?.selrect?.y, 0) && near(p2.objects[C.btn]?.selrect?.y, before[C.btn].selrect.y - 200),
          `moved by the same offset: ${card?.selrect?.y}, ${p2.objects[C.btn]?.selrect?.y}`);
    check(p2.objects[C.logo]?.fills?.[0]?.["fill-image"]?.id === LOGO, "image fill kept");
    check(p2.objects[C.title]?.["frame-id"] === C.card && p2.objects[C.card]?.["parent-id"] === "00000000-0000-0000-0000-000000000000",
          "frames and parents set on the new page");
    const comp = Object.values(mv.components).find((c) => c.name === "Buttons / Primary");
    check(comp?.mainInstancePage === archivePage.id && !comp.deleted, `main component follows its board: ${J(comp)}`);
    check(mv.result.shapes.length === 2 && mv.result.shapes[0].name === "Card" && near(mv.result.shapes[0].y, 0),
          `result shapes: ${J(mv.result.shapes)}`);
}

const kit2 = await engine.libraryOffline([], [{ op: "add", type: "color", name: "Brand / Primary", color: "#3366FF" }], { name: "Library" });
const KIT2_ID = kit2.summary.fileId;
{
    const cp = await engine.moveShapesOffline(design.library, {
        ids: [C.card, C.btn], toPage: "Components", mode: "copy", target: kit2.library, x: 400, y: 0, media: { [LOGO]: NEW_LOGO }, images,
    });
    check(cp.valid === true, "copy: changes pass the backend schema checks");
    const ids = cp.result.ids;
    check(ids[C.card] && ids[C.card] !== C.card && ids[C.btn] && ids[C.btn] !== C.btn, `copy maps old ids to new ids: ${J(ids)}`);
    const objs = cp.pages[0].objects;
    const card = objs[ids[C.card]];
    check(near(card?.selrect?.x, 400) && near(card?.selrect?.y, 0), `copy placed at x/y: ${J(card?.selrect)}`);
    check(objs[ids[C.logo]]?.fills?.[0]?.["fill-image"]?.id === NEW_LOGO, `image fill points to the target's media: ${J(objs[ids[C.logo]]?.fills)}`);
    check(cp.result.media?.cloned === 1, `media: ${J(cp.result.media)}`);

    const assets = cp.result.assets ?? {};
    check(assets.remapped?.some((a) => a.kind === "color" && a.name === "Brand / Primary"), `remapped: ${J(assets.remapped)}`);
    check(assets.unlinked?.some((a) => a.kind === "color" && a.name === "Brand / Ink" && /no color with that name/.test(a.reason)),
          `unlinked color: ${J(assets.unlinked)}`);
    check(assets.unlinked?.some((a) => a.kind === "typography" && a.name === "Heading / H1"), `unlinked typography: ${J(assets.unlinked)}`);
    check(objs[ids[C.btn]]?.fills?.[0]?.["fill-color-ref-file"] === KIT2_ID, "remapped ref points to the target's color");
    check(!card?.strokes?.[0]?.["stroke-color-ref-id"] && card?.strokes?.[0]?.["stroke-color"] === "#1f1e1d", "unlinked stroke keeps its value");

    const comps = Object.entries(cp.components);
    const [newCompId] = comps.find(([, c]) => c.name === "Buttons / Primary") ?? [];
    check(cp.result.components?.created?.includes("Buttons / Primary") && newCompId, `component created in the target: ${J(cp.result.components)}`);
    check(cp.result.components?.relinked === 1, `copy relinked: ${J(cp.result.components)}`);
    const cta = objs[ids[C.cta]];
    check(cta?.["component-file"] === KIT2_ID && cta["component-id"] === newCompId, `copy linked to the new component: ${J([cta?.["component-file"], cta?.["component-id"]])}`);
    const ctaLabel = objs[cta?.shapes?.[0]];
    check(ctaLabel?.["shape-ref"] === ids[C.label], `copy's layers point to the new main: ${ctaLabel?.["shape-ref"]} vs ${ids[C.label]}`);
    check(firstTextNode(ctaLabel?.content)?.text === "Go", "override kept in the copy");

    // the copy renders like the original
    const a = decodePng(await engine.renderFileOffline(design.library, C.card, { images }));
    const b = decodePng(await engine.renderFileOffline(cp.file, ids[C.card], { images }));
    writeFileSync(resolve(here, "out/move-copy.png"), Buffer.from(await engine.renderFileOffline(cp.file, ids[C.card], { images })));
    // a hug board can come out 1px taller when it is first built than when its
    // copy is laid out again: compare the area both have
    const h = Math.min(a.height, b.height);
    const rowA = a.width * a.bpp, rowB = b.width * b.bpp;
    let same = 0, total = 0;
    for (let y = 0; y < h - 1; y++) {
        for (let x = 0; x < Math.min(rowA, rowB); x++) {
            total++;
            if (Math.abs(a.data[y * rowA + x] - b.data[y * rowB + x]) <= 2) same++;
        }
    }
    check(a.width === b.width && Math.abs(a.height - b.height) <= 1 && same / total > 0.99,
          `copy renders like the original: ${a.width}x${a.height} vs ${b.width}x${b.height}, ${(100 * same / total).toFixed(2)}% equal`);
}
await expectError("move to another file", () => engine.moveShapesOffline(design.library, { ids: [C.card], toPage: "Components", mode: "move", target: kit2.library }),
                  /mode move works within one file/);
await expectError("move to the same page", () => engine.moveShapesOffline(design.library, { ids: [C.card], toPage: "Components", mode: "move" }),
                  /already on page "Components".*edit_page with the move op/);
await expectError("unknown page", () => engine.moveShapesOffline(design.library, { ids: [C.card], toPage: "Nope", mode: "move" }),
                  /No page "Nope" in the file\. Pages: "Components"/);
await expectError("layer of a main", () => engine.moveShapesOffline(design.library, { ids: [C.label], toPage: "Archive", mode: "move" }),
                  /is a layer of a main component/);

// ======================================================= design tokens

const tk = await engine.tokensOffline(design.library, [
    { op: "addSet", name: "core" },
    { op: "addToken", set: "core", type: "color", name: "color.primary", value: "#0066ff" },
    { op: "addToken", set: "core", type: "spacing", name: "space.md", value: "8" },
    { op: "addToken", set: "core", type: "spacing", name: "space.lg", value: "{space.md} * 2" },
    { op: "addToken", set: "core", type: "borderRadius", name: "radius.md", value: "4px" },
    { op: "addSet", name: "light" },
    { op: "addToken", set: "light", type: "color", name: "color.bg", value: "{color.primary}" },
    { op: "addTheme", group: "Mode", name: "Light", sets: ["core", "light"], active: true },
    { op: "addTheme", group: "Mode", name: "Dark", sets: ["core"] },
    { op: "updateToken", set: "core", token: "space.md", value: 10 },
]);
{
    const t = tk.tokens;
    check(J(t.sets.map((s) => [s.name, s.active, s.tokens])) === J([["core", true, 4], ["light", true, 1]]), `sets: ${J(t.sets)}`);
    check(J(t.themes.map((th) => [th.group, th.name, th.active, th.sets])) === J([["Mode", "Light", true, ["core", "light"]], ["Mode", "Dark", false, ["core"]]]),
          `themes: ${J(t.themes)}`);
    const byName = Object.fromEntries(t.tokens.map((x) => [x.name, x]));
    check(byName["space.lg"]?.resolved === "20" && byName["space.lg"].value === "{space.md} * 2", `arithmetic on references: ${J(byName["space.lg"])}`);
    check(byName["color.bg"]?.resolved === "#0066ff", `reference resolved: ${J(byName["color.bg"])}`);
    check(byName["radius.md"]?.type === "border-radius" && byName["space.md"]?.value === "10", `types and values: ${J(t.tokens)}`);
    check(tk.created["set:core"] && tk.created["theme:Mode/Light"] && tk.created["token:core/color.primary"], `created ids: ${J(tk.created)}`);
}
{
    const tk2 = await engine.tokensOffline(tk.file, [{ op: "updateTheme", theme: "Mode/Dark", active: true }]);
    const t = tk2.tokens;
    check(J(t.themes.map((th) => th.active)) === J([false, true]) && J(t.sets.map((s) => s.active)) === J([true, false]),
          `switching themes in a group: ${J(t.themes)} ${J(t.sets)}`);
    const tk3 = await engine.tokensOffline(tk2.file, [{ op: "updateSet", set: "light", active: true }, { op: "deleteToken", set: "core", token: "radius.md" }],
                                           { type: "spacing" });
    check(J(tk3.tokens.themes.map((th) => th.active)) === J([false, false]) && tk3.tokens.sets.every((s) => s.active),
          `activating a set directly turns themes off: ${J(tk3.tokens)}`);
    check(J(tk3.tokens.tokens.map((x) => x.name)) === J(["space.md", "space.lg"]), `filtered by type, deleted token gone: ${J(tk3.tokens.tokens)}`);
}
const tokenError = (name, ops, pattern) => expectError(name, () => engine.tokensOffline(tk.file, ops), pattern);
await tokenError("duplicate token", [{ op: "addToken", set: "core", type: "color", name: "color.primary", value: "#000000" }],
                 /ops\[0\]: Set "core" already has a token named "color.primary"/);
await tokenError("unknown type", [{ op: "addToken", set: "core", type: "colour", name: "x", value: "#000" }], /Invalid token type "colour"/);
await tokenError("broken reference", [{ op: "addToken", set: "core", type: "color", name: "x", value: "{nope}" }],
                 /refers to \{nope\}, which doesn't exist/);
await tokenError("circular reference", [
    { op: "addToken", set: "core", type: "number", name: "n.a", value: "1" },
    { op: "addToken", set: "core", type: "number", name: "n.b", value: "{n.a}" },
    { op: "updateToken", set: "core", token: "n.a", value: "{n.b}" },
], /ops\[2\]: Token "n.a" makes a circular reference/);
await tokenError("opacity out of range", [{ op: "addToken", set: "core", type: "opacity", name: "o", value: "2" }],
                 /Invalid value for a opacity token/);
await tokenError("unknown set", [{ op: "addToken", set: "dark", type: "color", name: "c", value: "#000" }],
                 /No token set "dark"\. Sets: "core", "light"/);
await tokenError("typo in a token op", [{ op: "addSet", nme: "x" }], /Unknown property "nme" for addSet\. Did you mean "name"\?/);

// tokens applied to shapes, as from the Tokens panel
{
    const result = await engine.applyOpsOffline(
        [
            {
                op: "create", type: "frame", ref: "card", width: 200, height: 10, layout: { direction: "column" }, sizing: { v: "hug" },
                tokens: { fill: "color.bg", radius: "radius.md", padding: "space.md", gap: "space.lg" },
            },
            { op: "create", type: "text", ref: "t", parent: "card", text: "Hi", tokens: { textColor: "color.primary" } },
            { op: "create", type: "rect", ref: "r", parent: "card", width: 10, height: 10, fill: "#000000", stroke: { color: "#000000", width: 3 } },
            { op: "update", id: "r", tokens: { fill: "color.primary", stroke: "color.bg" } },
            { op: "update", id: "card", fill: "#ff0000" },
        ],
        { tokensFile: tk.file }
    );
    const o = (ref) => result.objects[result.created[ref]];
    const card = o("card");
    check(card.fills[0]["fill-color"] === "#ff0000" && !card["applied-tokens"]?.fill, `a value set by hand detaches the token: ${J(card["applied-tokens"])}`);
    const sorted = (m) => J(Object.entries(m ?? {}).sort());
    check(sorted(card["applied-tokens"]) === sorted({ r1: "radius.md", r2: "radius.md", r3: "radius.md", r4: "radius.md", p1: "space.md",
                                                     p2: "space.md", p3: "space.md", p4: "space.md", "row-gap": "space.lg", "column-gap": "space.lg" }),
          `applied tokens recorded: ${J(card["applied-tokens"])}`);
    check(card.r1 === 4 && card["layout-padding"]?.p1 === 10 && card["layout-gap"]?.["row-gap"] === 20,
          `token values applied: ${J([card.r1, card["layout-padding"], card["layout-gap"]])}`);
    check(firstTextNode(o("t").content)?.fills?.[0]?.["fill-color"] === "#0066ff" && o("t")["applied-tokens"]?.fill === "color.primary",
          `text color token: ${J(o("t")["applied-tokens"])}`);
    const r = o("r");
    check(r.fills[0]["fill-color"] === "#0066ff" && r.strokes[0]["stroke-color"] === "#0066ff" && r.strokes[0]["stroke-width"] === 3,
          `color tokens on fill and stroke (stroke width kept): ${J([r.fills, r.strokes])}`);
    check(sorted(r["applied-tokens"]) === sorted({ fill: "color.primary", "stroke-color": "color.bg" }), `applied on update: ${J(r["applied-tokens"])}`);
    const summary = result.shapes.find((x) => x.id === result.created.r);
    check(summary?.tokens?.fill === "color.primary", `summary lists applied tokens: ${J(summary?.tokens)}`);
}
{
    // shapes using a token follow its edits, as with the Tokens panel
    const withChip = await engine.tokensOffline(tk.file, [{ op: "updateToken", set: "core", token: "space.md", description: "medium" }], null,
                                                [{ op: "create", type: "frame", ref: "chip", width: 40, height: 40, tokens: { fill: "color.bg" } }]);
    const chipId = withChip.pageCreated.chip;
    check(withChip.pages[0].objects[chipId]?.fills?.[0]?.["fill-color"] === "#0066ff", "chip drawn with the token");
    check(withChip.shapesUpdated === 0, `a description change updates no shape: ${withChip.shapesUpdated}`);
    const edited = await engine.tokensOffline(withChip.file, [{ op: "updateToken", set: "core", token: "color.primary", value: "#FF0000" }]);
    const chip = edited.pages[0].objects[chipId];
    check(edited.shapesUpdated === 1 && chip?.fills?.[0]?.["fill-color"] === "#ff0000" && chip["applied-tokens"]?.fill === "color.bg",
          `token edit propagated through the reference: ${edited.shapesUpdated} ${J(chip?.fills)} ${J(chip?.["applied-tokens"])}`);
}
const tokenOpts = { tokensFile: tk.file };
await expectError("token of another type", () => engine.applyOpsOffline([{ op: "create", type: "rect", tokens: { fill: "space.md" } }], tokenOpts),
                  /tokens\.fill: "space\.md" is a spacing token; fill takes a color token/);
await expectError("prop and token", () => engine.applyOpsOffline([{ op: "create", type: "rect", fill: "#000000", tokens: { fill: "color.primary" } }], tokenOpts),
                  /Set either "fill" or tokens\.fill, not both/);
await expectError("unknown token", () => engine.applyOpsOffline([{ op: "create", type: "rect", tokens: { fill: "nope" } }], tokenOpts),
                  /No token named "nope"/);
await expectError("gap without layout", () => engine.applyOpsOffline([{ op: "create", type: "frame", tokens: { gap: "space.md" } }], tokenOpts),
                  /gap and padding tokens apply to boards with a flex layout/);
await expectError("unknown token prop", () => engine.applyOpsOffline([{ op: "create", type: "rect", tokens: { margin: "space.md" } }], tokenOpts),
                  /Unknown property "margin" in "tokens"/);

console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
