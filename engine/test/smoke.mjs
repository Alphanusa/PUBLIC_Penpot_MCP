// Offline smoke test: loads the built engine, boots render-wasm and renders a
// synthetic frame to PNG without any Penpot backend or browser.
//
// Usage: node test/smoke.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");

const engine = await import(resolve(dist, "engine.js"));

const t0 = performance.now();
await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));
const t1 = performance.now();
const png = await engine.renderDemo();
const t2 = performance.now();

const bytes = Buffer.from(png);
const signature = bytes.subarray(0, 8).toString("hex");
if (signature !== "89504e470d0a1a0a") {
    throw new Error(`not a PNG (signature ${signature})`);
}
// IHDR: width/height are the first two big-endian u32 after the chunk header.
const width = bytes.readUInt32BE(16);
const height = bytes.readUInt32BE(20);
if (width !== 640 || height !== 400) {
    throw new Error(`unexpected size ${width}x${height}, expected 640x400 (320x200 @2x)`);
}

mkdirSync(resolve(here, "out"), { recursive: true });
writeFileSync(resolve(here, "out/demo.png"), bytes);

const mem = process.memoryUsage();
console.log(
    JSON.stringify(
        {
            ok: true,
            png: { width, height, bytes: bytes.length },
            ms: { init: Math.round(t1 - t0), render: Math.round(t2 - t1) },
            rssMB: Math.round(mem.rss / 1024 / 1024),
        },
        null,
        2
    )
);
