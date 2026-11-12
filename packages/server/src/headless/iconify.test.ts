import assert from "node:assert/strict";
import test from "node:test";
import { createServer } from "node:http";
import { expandIconOps, fetchIconSet, iconToSvg, IconSetFetcher } from "./iconify";

const fake: IconSetFetcher = async (prefix, names) => {
    const out: Record<string, { body: string; width: number; height: number }> = {};
    for (const n of names) if (n !== "nope") out[n] = { body: `<path d='M0 0h1'/>`, width: prefix === "mdi" ? 24 : 20, height: 24 };
    return out;
};

test("ops without icons are returned untouched", async () => {
    const ops = [{ op: "create", type: "rect" }];
    assert.equal(await expandIconOps(ops, fake), ops);
});

test("create svg with icon fills in the markup", async () => {
    const [op] = await expandIconOps([{ op: "create", type: "svg", icon: "mdi:server-outline", color: "#fff" }], fake);
    assert.equal(op.icon, undefined);
    assert.equal(op.name, "server-outline");
    assert.equal(op.width, 24);
    assert.equal(op.color, "#fff");
    assert.match(String(op.svg), /viewBox="0 0 24 24"/);
});

test("importIcons builds board + svg + component per icon", async () => {
    const ops = await expandIconOps(
        [
            { op: "create", type: "frame", ref: "lib" },
            { op: "importIcons", icons: ["material-symbols:add", "mdi:dot"], parent: "lib", color: "#E6FBFF", size: 20 },
        ],
        fake
    );
    assert.equal(ops.length, 1 + 3 * 2);
    assert.deepEqual(
        ops.slice(1).map((o) => [o.op, o.type ?? null]),
        [["create", "frame"], ["create", "svg"], ["makeComponent", null], ["create", "frame"], ["create", "svg"], ["makeComponent", null]]
    );
    assert.equal(ops[1].parent, "lib");
    assert.equal(ops[1].width, 20);
    assert.equal(ops[2].parent, ops[1].ref);
    assert.equal(ops[2].color, "#E6FBFF");
    assert.equal(ops[3].name, "Icon / material-symbols / add");
    assert.equal(ops[6].name, "Icon / mdi / dot");
});

test("importIcons without components, custom prefix", async () => {
    const ops = await expandIconOps([{ op: "importIcons", icons: ["mdi:dot"], components: false }], fake);
    assert.equal(ops.length, 2);
    const ops2 = await expandIconOps([{ op: "importIcons", icons: ["mdi:dot"], prefix: "Icons" }], fake);
    assert.equal(ops2[2].name, "Icons / mdi / dot");
});

test("errors: bad names, missing icons, unknown props, svg+icon", async () => {
    await assert.rejects(expandIconOps([{ op: "importIcons", icons: ["add"] }], fake), /Iconify name/);
    await assert.rejects(expandIconOps([{ op: "importIcons", icons: ["mdi:nope"] }], fake), /not found.*mdi:nope/);
    await assert.rejects(expandIconOps([{ op: "importIcons", icons: ["mdi:dot"], fill: "#000" }], fake), /unknown properties fill/);
    await assert.rejects(
        expandIconOps([{ op: "create", type: "svg", icon: "mdi:dot", svg: "<svg/>" }], fake),
        /either "svg" or "icon"/
    );
});

test("fetchIconSet reads the Iconify JSON API and resolves aliases", async () => {
    const server = createServer((req, res) => {
        assert.match(req.url!, /^\/demo\.json\?icons=/);
        res.setHeader("content-type", "application/json");
        res.end(
            JSON.stringify({
                prefix: "demo",
                width: 24,
                height: 24,
                icons: { home: { body: "<path d='M1 1'/>" }, wide: { body: "<g/>", width: 32 } },
                aliases: { house: { parent: "home" } },
            })
        );
    });
    await new Promise<void>((r) => server.listen(0, r));
    const port = (server.address() as any).port;
    process.env.ICONIFY_API_URL = `http://127.0.0.1:${port}/`;
    try {
        const found = await fetchIconSet("demo", ["home", "house", "wide", "missing"]);
        assert.deepEqual(Object.keys(found).sort(), ["home", "house", "wide"]);
        assert.equal(found.house.body, "<path d='M1 1'/>");
        assert.match(iconToSvg(found.wide), /viewBox="0 0 32 24"/);
    } finally {
        delete process.env.ICONIFY_API_URL;
        server.close();
    }
});
