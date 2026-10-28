import { deflateSync } from "node:zlib";

// Test fixtures for the browser-free tools (not part of the server bundle).

/** A minimal sfnt with only name, OS/2 and head tables: enough for readFontInfo. */
export function makeFont({
    family,
    typographicFamily,
    subfamily = "Regular",
    weight = 400,
    italic = false,
    woff = false,
}: {
    family: string;
    typographicFamily?: string;
    subfamily?: string;
    weight?: number;
    italic?: boolean;
    woff?: boolean;
}): Buffer {
    const utf16 = (s: string) => Buffer.from(s, "utf16le").swap16();
    const names: [number, Buffer][] = [
        [1, utf16(family)],
        [2, utf16(subfamily)],
        ...(typographicFamily ? ([[16, utf16(typographicFamily)]] as [number, Buffer][]) : []),
    ];
    const header = Buffer.alloc(6 + names.length * 12);
    header.writeUInt16BE(0, 0);
    header.writeUInt16BE(names.length, 2);
    header.writeUInt16BE(header.length, 4);
    let offset = 0;
    names.forEach(([id, value], i) => {
        const r = 6 + i * 12;
        header.writeUInt16BE(3, r); // Windows
        header.writeUInt16BE(1, r + 2); // Unicode BMP
        header.writeUInt16BE(0x409, r + 4); // English
        header.writeUInt16BE(id, r + 6);
        header.writeUInt16BE(value.length, r + 8);
        header.writeUInt16BE(offset, r + 10);
        offset += value.length;
    });
    const name = Buffer.concat([header, ...names.map(([, v]) => v)]);
    const os2 = Buffer.alloc(78);
    os2.writeUInt16BE(weight, 4);
    os2.writeUInt16BE(italic ? 0x1 : 0x40, 62);
    const head = Buffer.alloc(54);
    head.writeUInt16BE(italic ? 0x2 : 0, 44);
    const tables: [string, Buffer][] = [
        ["OS/2", os2],
        ["head", head],
        ["name", name],
    ];
    const pad = (b: Buffer) => Buffer.concat([b, Buffer.alloc((4 - (b.length % 4)) % 4)]);

    if (!woff) {
        const dir = Buffer.alloc(12 + tables.length * 16);
        dir.writeUInt32BE(0x00010000, 0);
        dir.writeUInt16BE(tables.length, 4);
        let at = dir.length;
        const bodies: Buffer[] = [];
        tables.forEach(([tag, data], i) => {
            const e = 12 + i * 16;
            dir.write(tag, e, "latin1");
            dir.writeUInt32BE(at, e + 8);
            dir.writeUInt32BE(data.length, e + 12);
            const padded = pad(data);
            bodies.push(padded);
            at += padded.length;
        });
        return Buffer.concat([dir, ...bodies]);
    }
    const dir = Buffer.alloc(44 + tables.length * 20);
    dir.write("wOFF", 0, "latin1");
    dir.writeUInt32BE(0x00010000, 4);
    dir.writeUInt16BE(tables.length, 12);
    let at = dir.length;
    const bodies: Buffer[] = [];
    tables.forEach(([tag, data], i) => {
        const e = 44 + i * 20;
        const compressed = deflateSync(data);
        const stored = compressed.length < data.length ? compressed : data;
        dir.write(tag, e, "latin1");
        dir.writeUInt32BE(at, e + 4);
        dir.writeUInt32BE(stored.length, e + 8);
        dir.writeUInt32BE(data.length, e + 12);
        const padded = pad(stored);
        bodies.push(padded);
        at += padded.length;
    });
    return Buffer.concat([dir, ...bodies]);
}
