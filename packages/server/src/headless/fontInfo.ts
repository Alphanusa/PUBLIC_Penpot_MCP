import { brotliDecompressSync, inflateSync } from "node:zlib";

/**
 * Reads what Penpot needs to register a font variant — format, family name,
 * weight and style — from a font file (TTF, OTF, WOFF or WOFF2), the way
 * Penpot's font upload dialog does with opentype.js.
 *
 * Only the `name`, `OS/2`, `head` and `fvar` tables are read. Font collections
 * (TTC) are not supported, like in Penpot.
 */

export type FontFormat = "ttf" | "otf" | "woff" | "woff2";

export const FONT_MIME_TYPES: Record<FontFormat, string> = {
    ttf: "font/ttf",
    otf: "font/otf",
    woff: "font/woff",
    woff2: "font/woff2",
};

/** Weights Penpot accepts for a font variant. */
export const FONT_WEIGHTS = [100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;

export interface FontInfo {
    format: FontFormat;
    mimeType: string;
    /** Typographic family (name ID 16), else the legacy family (name ID 1). */
    family?: string;
    /** OS/2 usWeightClass, rounded to a weight Penpot accepts. */
    weight?: number;
    style?: "normal" | "italic";
    /** True for variable fonts: Penpot registers them as one weight. */
    variable: boolean;
}

export function fontFormat(bytes: Uint8Array): FontFormat | undefined {
    const tag = Buffer.from(bytes.subarray(0, 4)).toString("latin1");
    if (tag === "\x00\x01\x00\x00" || tag === "true") return "ttf";
    if (tag === "OTTO") return "otf";
    if (tag === "wOFF") return "woff";
    if (tag === "wOF2") return "woff2";
    return undefined;
}

/** Penpot's weight for an OS/2 usWeightClass (e.g. 350 -> 400, 980 -> 950). */
export function normalizeWeight(weight: number): number {
    if (weight >= 950) return 950;
    return Math.min(900, Math.max(100, Math.round(weight / 100) * 100));
}

const WANTED = new Set(["name", "OS/2", "head", "fvar"]);

type Tables = Map<string, Buffer>;

function sfntTables(buf: Buffer): Tables {
    const tables: Tables = new Map();
    const count = buf.readUInt16BE(4);
    for (let i = 0; i < count; i++) {
        const entry = 12 + i * 16;
        const tag = buf.toString("latin1", entry, entry + 4);
        if (!WANTED.has(tag)) continue;
        const offset = buf.readUInt32BE(entry + 8);
        const length = buf.readUInt32BE(entry + 12);
        tables.set(tag, buf.subarray(offset, offset + length));
    }
    return tables;
}

function woffTables(buf: Buffer): Tables {
    const tables: Tables = new Map();
    const count = buf.readUInt16BE(12);
    for (let i = 0; i < count; i++) {
        const entry = 44 + i * 20;
        const tag = buf.toString("latin1", entry, entry + 4);
        if (!WANTED.has(tag)) continue;
        const offset = buf.readUInt32BE(entry + 4);
        const compressed = buf.readUInt32BE(entry + 8);
        const length = buf.readUInt32BE(entry + 12);
        const data = buf.subarray(offset, offset + compressed);
        tables.set(tag, compressed < length ? inflateSync(data) : data);
    }
    return tables;
}

/** Table tags WOFF2 encodes by index (WOFF2 spec, "Known Table Tags"). */
const WOFF2_KNOWN_TAGS = [
    "cmap",
    "head",
    "hhea",
    "hmtx",
    "maxp",
    "name",
    "OS/2",
    "post",
    "cvt ",
    "fpgm",
    "glyf",
    "loca",
    "prep",
    "CFF ",
    "VORG",
    "EBDT",
    "EBLC",
    "gasp",
    "hdmx",
    "kern",
    "LTSH",
    "PCLT",
    "VDMX",
    "vhea",
    "vmtx",
    "BASE",
    "GDEF",
    "GPOS",
    "GSUB",
    "EBSC",
    "JSTF",
    "MATH",
    "CBDT",
    "CBLC",
    "COLR",
    "CPAL",
    "SVG ",
    "sbix",
    "acnt",
    "avar",
    "bdat",
    "bloc",
    "bsln",
    "cvar",
    "fdsc",
    "feat",
    "fmtx",
    "fvar",
    "gvar",
    "hsty",
    "just",
    "lcar",
    "mort",
    "morx",
    "opbd",
    "prop",
    "trak",
    "Zapf",
    "Silf",
    "Glat",
    "Gloc",
    "Feat",
    "Sill",
];

function woff2Tables(buf: Buffer): Tables {
    const count = buf.readUInt16BE(12);
    const compressedSize = buf.readUInt32BE(20);
    let pos = 48;
    const base128 = () => {
        let value = 0;
        for (let i = 0; i < 5; i++) {
            const byte = buf[pos++];
            value = value * 128 + (byte & 0x7f);
            if (!(byte & 0x80)) return value;
        }
        throw new Error("invalid WOFF2 table directory");
    };
    const entries: { tag: string; length: number }[] = [];
    for (let i = 0; i < count; i++) {
        const flags = buf[pos++];
        const tag = (flags & 0x3f) === 63 ? buf.toString("latin1", pos, (pos += 4)) : WOFF2_KNOWN_TAGS[flags & 0x3f];
        const version = (flags >> 6) & 3;
        const origLength = base128();
        // glyf and loca are transformed unless version 3; other tables only with a non-zero version
        const transformed = tag === "glyf" || tag === "loca" ? version !== 3 : version !== 0;
        const length = transformed ? base128() : origLength;
        entries.push({ tag, length });
    }
    if (buf.toString("latin1", 4, 8) === "ttcf") throw new Error("font collections are not supported");
    const data = brotliDecompressSync(buf.subarray(pos, pos + compressedSize));
    const tables: Tables = new Map();
    let offset = 0;
    for (const { tag, length } of entries) {
        if (WANTED.has(tag)) tables.set(tag, data.subarray(offset, offset + length));
        offset += length;
    }
    return tables;
}

function decodeName(record: Buffer, platform: number): string {
    if (platform === 1) return record.toString("latin1");
    // platforms 0 and 3 are UTF-16BE
    const swapped = Buffer.from(record);
    swapped.swap16();
    return swapped.toString("utf16le");
}

/** The best name record for `id`: Windows English, then any Windows or Unicode, then Mac. */
function nameOf(name: Buffer, id: number): string | undefined {
    const count = name.readUInt16BE(2);
    const strings = name.readUInt16BE(4);
    let best: { score: number; value: string } | undefined;
    for (let i = 0; i < count; i++) {
        const r = 6 + i * 12;
        if (name.readUInt16BE(r + 6) !== id) continue;
        const platform = name.readUInt16BE(r);
        const language = name.readUInt16BE(r + 4);
        const length = name.readUInt16BE(r + 8);
        const offset = name.readUInt16BE(r + 10);
        const score = platform === 3 ? (language === 0x409 ? 3 : 2) : platform === 0 ? 2 : platform === 1 ? 1 : 0;
        if (!score || (best && best.score >= score)) continue;
        const value = decodeName(name.subarray(strings + offset, strings + offset + length), platform).trim();
        if (value) best = { score, value };
    }
    return best?.value;
}

const WEIGHT_WORDS: Record<string, number> = {
    thin: 100,
    hairline: 100,
    extralight: 200,
    ultralight: 200,
    light: 300,
    regular: 400,
    normal: 400,
    book: 400,
    medium: 500,
    semibold: 600,
    demibold: 600,
    bold: 700,
    extrabold: 800,
    ultrabold: 800,
    black: 900,
    heavy: 900,
};

/**
 * Without a typographic family (name ID 16) the legacy family often carries the
 * weight ("IBM Plex Sans Medium"). Drops trailing weight/style words, but only when
 * they match the font's own weight and style, so "Archivo Black" (weight 400) stays.
 */
export function stripStyleWords(family: string, weight?: number, style?: string): string {
    const words = family.split(/\s+/);
    while (words.length > 1) {
        const last = words[words.length - 1].toLowerCase();
        const lastTwo = words.length > 2 ? (words[words.length - 2] + last).toLowerCase() : undefined;
        if (style === "italic" && (last === "italic" || last === "oblique")) words.pop();
        else if (lastTwo && WEIGHT_WORDS[lastTwo] !== undefined && WEIGHT_WORDS[lastTwo] === weight) words.splice(-2);
        else if (WEIGHT_WORDS[last] !== undefined && WEIGHT_WORDS[last] === weight) words.pop();
        else break;
    }
    return words.join(" ");
}

/** Format, family, weight and style of a font file. Throws for something that is not a font. */
export function readFontInfo(bytes: Uint8Array): FontInfo {
    const format = fontFormat(bytes);
    if (!format) throw new Error("Not a font file: expected TTF, OTF, WOFF or WOFF2.");
    const info: FontInfo = { format, mimeType: FONT_MIME_TYPES[format], variable: false };
    let tables: Tables;
    try {
        const buf = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        tables = format === "woff" ? woffTables(buf) : format === "woff2" ? woff2Tables(buf) : sfntTables(buf);
    } catch {
        // the format is known; family/weight/style must then be given explicitly
        return info;
    }
    const name = tables.get("name");
    const os2 = tables.get("OS/2");
    const head = tables.get("head");
    if (name && name.length >= 6) {
        info.family = nameOf(name, 16) ?? nameOf(name, 1);
    }
    let italic = false;
    if (os2 && os2.length >= 64) {
        info.weight = normalizeWeight(os2.readUInt16BE(4));
        const selection = os2.readUInt16BE(62);
        italic = !!(selection & 0x1) || !!(selection & 0x200);
    }
    if (head && head.length >= 46) italic ||= !!(head.readUInt16BE(44) & 0x2);
    if (name && name.length >= 6) {
        const subfamily = nameOf(name, 17) ?? nameOf(name, 2) ?? "";
        italic ||= /italic|oblique/i.test(subfamily);
    }
    info.style = italic ? "italic" : "normal";
    info.variable = tables.has("fvar");
    if (info.family && name && !nameOf(name, 16)) {
        info.family = stripStyleWords(info.family, info.weight, info.style);
    }
    return info;
}
