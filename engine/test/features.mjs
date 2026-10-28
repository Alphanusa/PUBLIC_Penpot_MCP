// Offline tests for the browser-free features added for issues #6–#9:
//   #9 unknown op properties are reported, not silently ignored
//   #7 font families (Google font loaded straight from fonts.gstatic.com)
//   #8 image fills (cover / contain / fill) and the "image" shape type
//   #6 page operations (add / rename / delete / move)
// No Penpot backend is needed. The font test needs network access to
// fonts.gstatic.com (GitHub runners have it).
//
// Usage: node test/features.mjs   (after `npm run build`)

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

async function expectError(name, ops, pattern, opts = {}) {
    try {
        await engine.applyOpsOffline(ops, opts);
        failures.push(`${name}: expected an error matching ${pattern}, got success`);
    } catch (e) {
        check(pattern.test(e.message), `${name}: error ${JSON.stringify(e.message)} does not match ${pattern}`);
    }
}

const byRef = (result, ref) => result.shapes.find((s) => s.id === result.created[ref]);
const objectOf = (result, ref) => result.objects[result.created[ref]];
const firstTextNode = (node) =>
    typeof node?.text === "string" ? node : (node?.children ?? []).reduce((hit, c) => hit ?? firstTextNode(c), undefined);

// ------------------------------------------------------------------ #9 validation

await expectError(
    "typo in a text prop",
    [{ op: "create", type: "text", text: "Hi", fontsize: 20 }],
    /ops\[0\]: Unknown property "fontsize" for text\. Did you mean "fontSize"\?/
);
await expectError(
    "padding outside layout",
    [
        { op: "create", type: "frame", ref: "b", width: 100, height: 100 },
        { op: "update", id: "b", stroke: "none", padding: [16, 0] },
    ],
    /ops\[1\]: Unknown property "padding" for board \(frame\)\. "padding" belongs inside "layout"/
);
await expectError(
    "unknown key inside layout",
    [{ op: "create", type: "frame", layout: { direction: "column", justify: "center" } }],
    /Unknown property "justify" in "layout"\. Did you mean "justifyContent"\?/
);
await expectError(
    "prop of another shape type",
    [{ op: "create", type: "ellipse", radius: 4 }],
    /"radius" is not supported on ellipse/
);
await expectError(
    "unknown key on delete",
    [{ op: "create", type: "rect", ref: "r" }, { op: "delete", id: "r", force: true }],
    /ops\[1\]: Unknown property "force" for delete\. Allowed: op, id\./
);
await expectError(
    "type cannot change on update",
    [{ op: "create", type: "rect", ref: "r" }, { op: "update", id: "r", type: "ellipse" }],
    /type of an existing shape cannot be changed/
);
await expectError("visible must be a boolean", [{ op: "create", type: "rect", visible: "false" }], /Invalid visible/);
await expectError(
    "fillOpacity without fill",
    [{ op: "create", type: "rect", fillOpacity: 0.5 }],
    /fillOpacity needs a fill/
);

// ------------------------------------------------------------------ #7 fonts

await expectError(
    "unknown font family",
    [{ op: "create", type: "text", text: "x", fontFamily: "IBM Plex Sanz" }],
    /Font family "IBM Plex Sanz" not found.*Closest: .*IBM Plex Sans/
);
await expectError(
    "weight the builtin font does not have",
    [{ op: "create", type: "text", text: "x", fontWeight: 500 }],
    /fontWeight 500 is not available for Source Sans Pro\. Available: 200, 200 italic, 300/
);
await expectError(
    "weight a Google font does not have",
    [{ op: "create", type: "text", text: "x", fontFamily: "IBM Plex Mono", fontWeight: 800 }],
    /fontWeight 800 is not available for IBM Plex Mono/
);

{
    const t0 = performance.now();
    const result = await engine.applyOpsOffline(
        [
            {
                op: "create", type: "frame", ref: "card", width: 10, height: 10, fill: "#FFFFFF",
                layout: { direction: "column", gap: 8, padding: 16 }, sizing: { h: "hug", v: "hug" },
            },
            { op: "create", type: "text", ref: "monoI", parent: "card", text: "iiiiiiiiii", fontFamily: "IBM Plex Mono", fontWeight: 500, fontSize: 20 },
            { op: "create", type: "text", ref: "monoW", parent: "card", text: "WWWWWWWWWW", fontFamily: "IBM Plex Mono", fontWeight: 500, fontSize: 20 },
            { op: "create", type: "text", ref: "sansI", parent: "card", text: "iiiiiiiiii", fontSize: 20 },
            { op: "create", type: "text", ref: "sansW", parent: "card", text: "WWWWWWWWWW", fontSize: 20 },
            { op: "create", type: "text", ref: "swap", parent: "card", text: "WWWWWWWWWW", fontSize: 20, fontWeight: 600 },
            // family change on an existing text keeps the closest weight (600 -> 600 in Plex Mono)
            { op: "update", id: "swap", fontFamily: "ibm plex mono" },
            { op: "create", type: "text", ref: "italic", parent: "card", text: "Italic", fontFamily: "Inter", fontStyle: "italic", fontWeight: "bold" },
        ],
        { gfonts: true, renderRef: "card" }
    );
    const ms = Math.round(performance.now() - t0);
    writeFileSync(resolve(here, "out/fonts.png"), Buffer.from(result.png));

    const mono = firstTextNode(objectOf(result, "monoI").content);
    check(mono["font-id"] === "gfont-ibm-plex-mono", `mono font-id: ${mono["font-id"]}`);
    check(mono["font-family"] === "IBM Plex Mono", `mono font-family: ${mono["font-family"]}`);
    check(mono["font-variant-id"] === "500" && mono["font-weight"] === "500", `mono variant: ${mono["font-variant-id"]}/${mono["font-weight"]}`);
    const [mI, mW, sI, sW, swap] = ["monoI", "monoW", "sansI", "sansW", "swap"].map((r) => byRef(result, r));
    check(near(mI.width, mW.width, 1), `IBM Plex Mono is monospaced (font really loaded): ${mI.width} vs ${mW.width}`);
    check(sW.width > sI.width * 1.5, `Source Sans Pro is proportional: ${sI.width} vs ${sW.width}`);
    const swapNode = firstTextNode(objectOf(result, "swap").content);
    check(swapNode["font-id"] === "gfont-ibm-plex-mono" && swapNode["font-variant-id"] === "600",
          `family update keeps the weight: ${swapNode["font-id"]} ${swapNode["font-variant-id"]}`);
    check(near(swap.width, mW.width, 1), `family update re-measures the text: ${swap.width} vs ${mW.width}`);
    const italic = firstTextNode(objectOf(result, "italic").content);
    check(italic["font-variant-id"] === "700italic" && italic["font-style"] === "italic", `italic variant: ${italic["font-variant-id"]}`);
    const card = byRef(result, "card");
    const widest = Math.max(...["monoI", "monoW", "sansI", "sansW", "swap", "italic"].map((r) => byRef(result, r).width));
    check(near(card.width, widest + 32, 1), `hug board reflows to the widest text: ${card.width} vs ${widest + 32}`);
    console.log(JSON.stringify({ fonts: { ms, monoI: mI.width, monoW: mW.width, sansI: sI.width, sansW: sW.width, swap: swap.width } }));
}

// ------------------------------------------------------------------ #8 images

/** Minimal PNG decoder (8-bit RGB/RGBA, no interlace): enough to sample rendered pixels. */
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
            if (data[8] !== 8 || data[12] !== 0) throw new Error("unsupported PNG");
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

const demo = await engine.renderDemo(); // 640x400 PNG: beige board, blue rect, red circle
const info = engine.imageInfo(demo);
check(info?.mtype === "image/png" && info.width === 640 && info.height === 400, `imageInfo(png): ${JSON.stringify(info)}`);

// headers of the other formats
const gif = Buffer.from([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x20, 0x00, 0x10, 0x00]);
check(JSON.stringify(engine.imageInfo(gif)) === JSON.stringify({ mtype: "image/gif", width: 32, height: 16 }), `imageInfo(gif): ${JSON.stringify(engine.imageInfo(gif))}`);
const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x00, 0x30, 0x00, 0x50, 0x03, 0, 0, 0, 0]);
check(JSON.stringify(engine.imageInfo(jpeg)) === JSON.stringify({ mtype: "image/jpeg", height: 48, width: 80 }), `imageInfo(jpeg): ${JSON.stringify(engine.imageInfo(jpeg))}`);
const webp = Buffer.alloc(30);
webp.write("RIFF", 0); webp.write("WEBP", 8); webp.write("VP8X", 12);
webp.writeUIntLE(199, 24, 3); webp.writeUIntLE(99, 27, 3);
check(JSON.stringify(engine.imageInfo(webp)) === JSON.stringify({ mtype: "image/webp", width: 200, height: 100 }), `imageInfo(webp): ${JSON.stringify(engine.imageInfo(webp))}`);
const svg = Buffer.from('<?xml version="1.0"?>\n<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 118 32"><rect/></svg>');
check(JSON.stringify(engine.imageInfo(svg)) === JSON.stringify({ mtype: "image/svg+xml", width: 118, height: 32 }), `imageInfo(svg): ${JSON.stringify(engine.imageInfo(svg))}`);
check(engine.imageInfo(Buffer.from("not an image")) == null, "imageInfo(garbage) is null");

const MEDIA = "0f9e1c2a-1111-4222-8333-444455556666";
await expectError(
    "unknown media id",
    [{ op: "create", type: "rect", fill: { image: "11111111-2222-4333-8444-555555555555" } }],
    /Image 11111111-2222-4333-8444-555555555555 not found/
);
await expectError(
    "image fill on a text",
    [{ op: "create", type: "text", text: "x", fill: { image: MEDIA } }],
    /image fills are not supported on texts/,
    { images: { [MEDIA]: demo } }
);
await expectError(
    "fit on a rectangle outside fill",
    [{ op: "create", type: "rect", fit: "contain" }],
    /To fill a rectangle with an image use "fill"/
);

{
    const result = await engine.applyOpsOffline(
        [
            { op: "create", type: "frame", ref: "box", width: 200, height: 200, fill: "#FFFFFF" },
            { op: "create", type: "rect", ref: "contain", parent: "box", width: 200, height: 200, fill: { image: MEDIA, fit: "contain" } },
            { op: "create", type: "rect", ref: "cover", x: 300, width: 200, height: 200, fill: { image: MEDIA } },
            { op: "create", type: "ellipse", ref: "stretch", x: 600, width: 200, height: 200, fill: { image: MEDIA, fit: "fill", opacity: 0.5 } },
            { op: "create", type: "image", ref: "natural", image: MEDIA, x: 900 },
            { op: "create", type: "image", ref: "half", image: MEDIA, width: 320, y: 500, name: "Logo" },
            // the same media reused, then the fit changed by an update
            { op: "create", type: "rect", ref: "later", y: 800, width: 100, height: 100, fill: { image: MEDIA, fit: "contain" } },
            { op: "update", id: "later", fill: { image: MEDIA, fit: "cover" } },
        ],
        { images: { [MEDIA]: demo }, renderRef: "box" }
    );
    writeFileSync(resolve(here, "out/images.png"), Buffer.from(result.png));

    const fillImage = (ref) => objectOf(result, ref).fills[0]["fill-image"];
    const contain = fillImage("contain");
    check(contain.id === MEDIA && contain.mtype === "image/png" && contain.width === 640 && contain.height === 400,
          `image fill metadata: ${JSON.stringify(contain)}`);
    check(contain["keep-aspect-ratio"] === true && contain.transform &&
          near(contain.transform.x, 0, 1e-6) && near(contain.transform.y, 0.1875, 1e-6) &&
          near(contain.transform.width, 1, 1e-6) && near(contain.transform.height, 0.625, 1e-6),
          `contain transform: ${JSON.stringify(contain.transform)}`);
    check(fillImage("cover")["keep-aspect-ratio"] === true && !fillImage("cover").transform, `cover fill: ${JSON.stringify(fillImage("cover"))}`);
    check(fillImage("stretch")["keep-aspect-ratio"] === false && objectOf(result, "stretch").fills[0]["fill-opacity"] === 0.5,
          `fill (stretch) fill: ${JSON.stringify(objectOf(result, "stretch").fills[0])}`);
    check(!fillImage("later").transform, `update to cover drops the contain transform: ${JSON.stringify(fillImage("later"))}`);
    const natural = byRef(result, "natural");
    const half = byRef(result, "half");
    check(natural.width === 640 && natural.height === 400 && natural.type === "rect", `image at natural size: ${natural.width}x${natural.height}`);
    check(near(half.width, 320) && near(half.height, 200) && half.name === "Logo", `image keeps its aspect ratio: ${half.width}x${half.height}`);
    check(byRef(result, "contain").image === MEDIA, `summary shows the image: ${byRef(result, "contain").image}`);

    const png = decodePng(result.png);
    const [r1, g1, b1] = png.pixel(100, 10); // letterbox band above the contained image
    const [r2, g2, b2] = png.pixel(100, 100); // the demo's blue rectangle
    check(r1 > 240 && g1 > 240 && b1 > 240, `contain leaves the box background above the image: ${[r1, g1, b1]}`);
    check(b2 > 200 && r2 < 120, `contain shows the image (blue rect) in the middle: ${[r2, g2, b2]}`);
}

// ------------------------------------------------------------------ #6 pages

{
    const P1 = "aaaaaaaa-0000-4000-8000-000000000001";
    const P2 = "aaaaaaaa-0000-4000-8000-000000000002";
    const result = await engine.pagesOffline(
        [{ id: P1, name: "Page 1" }, { id: P2, name: "Old" }],
        [
            { op: "rename", id: P1, name: "Design system" },
            { op: "add", ref: "screens", name: "Screens" },
            { op: "add", ref: "first", name: "Cover", index: 0 },
            { op: "move", id: P1, index: 3 },
            { op: "delete", id: P2 },
        ]
    );
    const names = result.pages.map((p) => p.name);
    check(JSON.stringify(names) === JSON.stringify(["Cover", "Screens", "Design system"]), `pages after ops: ${names}`);
    check(result.created.screens && result.created.first && result.addedIds.length === 2, `refs of added pages: ${JSON.stringify(result.created)}`);
    check(result.deleted.length === 1 && result.deleted[0] === P2, `deleted: ${result.deleted}`);
    const types = result.changes.map((c) => c.type);
    check(JSON.stringify(types) === JSON.stringify(["mod-page", "add-page", "add-page", "mov-page", "mov-page", "del-page"]),
          `changes: ${types}`);

    const expectPagesError = async (name, pages, ops, pattern) => {
        try {
            await engine.pagesOffline(pages, ops);
            failures.push(`${name}: expected an error`);
        } catch (e) {
            check(pattern.test(e.message), `${name}: ${JSON.stringify(e.message)} does not match ${pattern}`);
        }
    };
    await expectPagesError("last page", [{ id: P1, name: "Only" }], [{ op: "delete", id: P1 }], /at least one page/);
    await expectPagesError("main components", [{ id: P1, name: "Lib", mainComponent: true }, { id: P2, name: "B" }],
                           [{ op: "delete", id: P1 }], /holds main components/);
    await expectPagesError("unknown page", [{ id: P1, name: "A" }], [{ op: "rename", id: P2, name: "x" }], /not found in this file/);
    await expectPagesError("unknown key", [{ id: P1, name: "A" }], [{ op: "add", name: "B", title: "B" }],
                           /ops\[0\]: Unknown property "title" for add/);
    await expectPagesError("blank name", [{ id: P1, name: "A" }], [{ op: "add", name: "  " }], /Invalid name/);
}

console.log(JSON.stringify({ ok: failures.length === 0, failures }, null, 2));
if (failures.length) process.exit(1);
