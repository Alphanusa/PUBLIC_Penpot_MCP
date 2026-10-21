// Offline edit test: builds a small pricing section with flex layouts, text
// and update/delete ops on an empty in-memory page — no Penpot, no browser —
// then checks the computed layout and renders it to test/out/edit.png.
//
// Usage: node test/edit-smoke.mjs   (after `npm run build`)

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, "../dist");
const engine = await import(resolve(dist, "engine.js"));
await engine.init(resolve(dist, "wasm"), resolve(dist, "fonts"));

const plans = [
    ["Basic", "Rp 49rb", "Untuk mencoba. Satu proyek dan sampai tiga anggota tim."],
    ["Pro", "Rp 149rb", "Untuk tim kecil yang butuh proyek tanpa batas dan riwayat versi."],
    ["Bisnis", "Rp 0", "Untuk organisasi: SSO, audit log, dukungan prioritas, dan SLA."],
];

const ops = [
    {
        op: "create", type: "frame", ref: "section", name: "Pricing", x: 0, y: 0, width: 720, height: 10,
        fill: "#F4F1EA", layout: { direction: "column", gap: 24, padding: 32 }, sizing: { v: "auto" },
    },
    { op: "create", type: "text", ref: "title", parent: "section", text: "Pilih paket", fontSize: 32, fontWeight: 700, textColor: "#1F1E1D" },
    {
        op: "create", type: "frame", ref: "row", parent: "section", name: "Cards", width: 656, height: 10, fill: "none",
        // note: with alignItems "stretch", Penpot does not let fill-width children size a
        // hugging row (they only contribute their min height) — same as in the editor
        layout: { direction: "row", gap: 16, alignItems: "start" }, sizing: { h: "fix", v: "auto" },
    },
    ...plans.flatMap(([name, price, desc], i) => [
        {
            op: "create", type: "frame", ref: `card${i}`, parent: "row", name: `Card ${name}`, width: 200, height: 10,
            fill: "#FFFFFF", radius: 12, stroke: { color: "#E5E2DA", width: 1 },
            layout: { direction: "column", gap: 8, padding: 20 }, sizing: { h: "fill", v: "auto" },
        },
        { op: "create", type: "text", ref: `name${i}`, parent: `card${i}`, text: name, fontSize: 18, fontWeight: 600, textColor: "#1F1E1D" },
        { op: "create", type: "text", ref: `price${i}`, parent: `card${i}`, text: price, fontSize: 28, fontWeight: 700, textColor: "#3366FF" },
        {
            op: "create", type: "text", ref: `desc${i}`, parent: `card${i}`, text: desc, fontSize: 14, textColor: "#5F5E5A",
            width: 100, growType: "auto-height", sizing: { h: "fill" },
        },
    ]),
    // exercise update + delete in the same batch
    { op: "create", type: "rect", ref: "tmp", parent: "section", width: 50, height: 50, fill: "#FF0000" },
    { op: "update", id: "price2", text: "Hubungi kami", fontSize: 22 },
    { op: "delete", id: "tmp" },
];

const t0 = performance.now();
const result = await engine.applyOpsOffline(ops, { scale: 1, renderRef: "section" });
const ms = Math.round(performance.now() - t0);

mkdirSync(resolve(here, "out"), { recursive: true });
writeFileSync(resolve(here, "out/edit.png"), Buffer.from(result.png));
const { png, ...json } = result;
writeFileSync(resolve(here, "out/edit.json"), JSON.stringify(json, null, 2));

// --- checks
const byId = new Map(result.shapes.map((s) => [s.id, s]));
const shape = (ref) => {
    const s = byId.get(result.created[ref]);
    if (!s) throw new Error(`missing shape for ref ${ref}`);
    return s;
};
const findNode = (node, id) =>
    node.id === id ? node : (Array.isArray(node.children) ? node.children : []).reduce((hit, c) => hit ?? findNode(c, id), undefined);
const fail = [];
const check = (cond, msg) => cond || fail.push(msg);

const section = shape("section");
const title = shape("title");
const row = shape("row");
const cards = [0, 1, 2].map((i) => shape(`card${i}`));
const descs = [0, 1, 2].map((i) => shape(`desc${i}`));

check(title.width > 100 && title.height >= 30, `title measured: ${title.width}x${title.height}`);
check(Math.abs(title.x - 32) < 0.5 && Math.abs(title.y - 32) < 0.5, `title placed at padding: ${title.x},${title.y}`);
check(Math.abs(row.y - (32 + title.height + 24)) < 0.5, `row below title with gap: ${row.y}`);
const w = cards[0].width;
check(cards.every((c) => Math.abs(c.width - w) < 0.5), `cards same width: ${cards.map((c) => c.width)}`);
check(Math.abs(w - (656 - 2 * 16) / 3) < 0.5, `cards fill the row: ${w}`);
for (let i = 1; i < 3; i++) {
    check(Math.abs(cards[i].x - (cards[i - 1].x + w + 16)) < 0.5, `card${i} after card${i - 1} with gap`);
}
check(descs.every((d) => Math.abs(d.width - (w - 40)) < 0.5), `desc fills card width: ${descs.map((d) => d.width)}`);
check(descs.every((d) => d.height > 20), `desc wraps to multiple lines: ${descs.map((d) => d.height)}`);
check(Math.abs(row.height - Math.max(...cards.map((c) => c.height))) < 0.5, `row hugs the tallest card: ${row.height}`);
check(cards.every((c) => Math.abs(c.y) < 0.5), `cards aligned to the top: ${cards.map((c) => c.y)}`);
check(Math.abs(section.height - (32 + title.height + 24 + row.height + 32)) < 0.5, `section hugs content: ${section.height}`);
const childNames = (ref) => (findNode(result.tree, result.created[ref])?.children ?? []).map((c) => c.name);
check(JSON.stringify(childNames("row")) === JSON.stringify(["Card Basic", "Card Pro", "Card Bisnis"]),
      `tree lists flex children in visual order: ${childNames("row")}`);
check(!result.createdIds.includes(undefined) && result.deleted.length === 1, `delete applied: ${result.deleted}`);
check(shape("price2").text === "Hubungi kami", `update applied: ${shape("price2").text}`);

console.log(JSON.stringify({ ok: fail.length === 0, ms, changes: result.changes, failures: fail }, null, 2));
if (fail.length) process.exit(1);
