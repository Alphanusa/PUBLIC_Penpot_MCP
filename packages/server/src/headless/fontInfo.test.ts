import assert from "node:assert/strict";
import test from "node:test";
import { makeFont } from "./fixtures";
import { normalizeWeight, readFontInfo, stripStyleWords } from "./fontInfo";

test("reads family, weight and style of a TTF", () => {
    const info = readFontInfo(makeFont({ family: "Brand Sans", weight: 600, italic: true }));
    assert.deepEqual(info, {
        format: "ttf",
        mimeType: "font/ttf",
        variable: false,
        family: "Brand Sans",
        weight: 600,
        style: "italic",
    });
});

test("prefers the typographic family and reads compressed WOFF tables", () => {
    const font = makeFont({
        family: "Brand Sans Medium",
        typographicFamily: "Brand Sans",
        subfamily: "Medium",
        weight: 500,
        woff: true,
    });
    const info = readFontInfo(font);
    assert.equal(info.format, "woff");
    assert.equal(info.mimeType, "font/woff");
    assert.equal(info.family, "Brand Sans");
    assert.equal(info.weight, 500);
    assert.equal(info.style, "normal");
});

test("drops weight words from a legacy family only when they match the font", () => {
    assert.equal(readFontInfo(makeFont({ family: "Brand Sans Medium", weight: 500 })).family, "Brand Sans");
    assert.equal(readFontInfo(makeFont({ family: "Archivo Black", weight: 400 })).family, "Archivo Black");
    assert.equal(stripStyleWords("Roboto Extra Bold Italic", 800, "italic"), "Roboto");
    assert.equal(stripStyleWords("Bold", 700, "normal"), "Bold");
});

test("normalizes weights to the ones Penpot accepts", () => {
    assert.deepEqual([250, 350, 449, 451, 900, 960, 50].map(normalizeWeight), [300, 400, 400, 500, 900, 950, 100]);
});

test("rejects files that are not fonts", () => {
    assert.throws(() => readFontInfo(Buffer.from("PK\x03\x04 not a font")), /Not a font file/);
});
