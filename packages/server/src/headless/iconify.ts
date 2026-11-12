/**
 * Iconify support for edit_page.
 *
 * Expands, before the ops reach the engine:
 *  - {"op":"create","type":"svg","icon":"material-symbols:add", ...}   → the same op with "svg" filled in
 *  - {"op":"importIcons","icons":["material-symbols:add", ...], "parent", "size"?, "color"?, "prefix"?,
 *     "components"?: true}                                              → per icon: a board, the svg inside it and,
 *     unless components is false, a makeComponent named "<prefix> / <set> / <name>".
 *
 * Icons are fetched from the Iconify API (one request per icon set), so the caller only sends names.
 */

export type Op = Record<string, unknown>;

export interface IconData {
    body: string;
    left?: number;
    top?: number;
    width?: number;
    height?: number;
}

/** Fetches icon data for one icon set; returns the icons found (missing names are left out). */
export type IconSetFetcher = (prefix: string, names: string[]) => Promise<Record<string, IconData>>;

const ICON_NAME = /^([a-z0-9]+(?:-[a-z0-9]+)*):([a-z0-9]+(?:-[a-z0-9]+)*)$/;
const MAX_ICONS = 500;

export function iconApiBase(): string {
    return (process.env.ICONIFY_API_URL || "https://api.iconify.design").replace(/\/+$/, "");
}

/** Default fetcher: Iconify JSON API, resolving aliases. */
export const fetchIconSet: IconSetFetcher = async (prefix, names) => {
    const found: Record<string, IconData> = {};
    // keep URLs reasonably short
    for (let i = 0; i < names.length; i += 100) {
        const chunk = names.slice(i, i + 100);
        const url = `${iconApiBase()}/${prefix}.json?icons=${encodeURIComponent(chunk.join(","))}`;
        const res = await fetch(url, { signal: AbortSignal.timeout(20_000) });
        if (!res.ok) throw new Error(`Iconify API ${res.status} for ${prefix}`);
        const data: any = await res.json();
        if (!data || typeof data !== "object" || !data.icons) continue;
        const defaults = { left: data.left ?? 0, top: data.top ?? 0, width: data.width ?? 16, height: data.height ?? 16 };
        for (const name of chunk) {
            let n = name;
            let extra: Record<string, unknown> = {};
            for (let hops = 0; hops < 5 && !data.icons[n] && data.aliases?.[n]; hops++) {
                const alias = data.aliases[n];
                extra = { ...alias, ...extra };
                n = alias.parent;
            }
            const icon = data.icons[n];
            if (!icon) continue;
            const merged = { ...defaults, ...icon, ...extra };
            found[name] = {
                body: merged.body,
                left: merged.left,
                top: merged.top,
                width: merged.width,
                height: merged.height,
            };
        }
    }
    return found;
};

export function iconToSvg(icon: IconData): string {
    const { left = 0, top = 0, width = 16, height = 16 } = icon;
    return (
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${left} ${top} ${width} ${height}" fill="currentColor">` +
        `${icon.body}</svg>`
    );
}

function parseIconName(value: unknown, where: string): [string, string] {
    const m = typeof value === "string" ? ICON_NAME.exec(value.trim()) : null;
    if (!m) throw new Error(`${where}: icon must be an Iconify name like "material-symbols:add", got ${JSON.stringify(value)}`);
    return [m[1], m[2]];
}

function hasIcons(ops: Op[]): boolean {
    return ops.some((op) => op.op === "importIcons" || (op.op === "create" && op.type === "svg" && "icon" in op));
}

/** Returns ops with Iconify references resolved. Ops without icons are returned unchanged. */
export async function expandIconOps(ops: Op[], fetcher: IconSetFetcher = fetchIconSet): Promise<Op[]> {
    if (!hasIcons(ops)) return ops;

    // 1. collect every icon name, grouped by set
    const wanted = new Map<string, Set<string>>();
    const want = (prefix: string, name: string) => {
        if (!wanted.has(prefix)) wanted.set(prefix, new Set());
        wanted.get(prefix)!.add(name);
    };
    let total = 0;
    ops.forEach((op, i) => {
        if (op.op === "importIcons") {
            if (!Array.isArray(op.icons) || op.icons.length === 0)
                throw new Error(`ops[${i}] importIcons: "icons" must be a non-empty list of Iconify names`);
            for (const v of op.icons) {
                const [p, n] = parseIconName(v, `ops[${i}] importIcons`);
                want(p, n);
                total++;
            }
        } else if (op.op === "create" && op.type === "svg" && "icon" in op) {
            if ("svg" in op) throw new Error(`ops[${i}]: give either "svg" or "icon", not both`);
            const [p, n] = parseIconName(op.icon, `ops[${i}]`);
            want(p, n);
            total++;
        }
    });
    if (total > MAX_ICONS) throw new Error(`Too many icons in one call (${total}); the limit is ${MAX_ICONS}.`);

    // 2. fetch each set once, in parallel
    const library = new Map<string, IconData>();
    await Promise.all(
        [...wanted].map(async ([prefix, names]) => {
            const found = await fetcher(prefix, [...names]);
            for (const [name, data] of Object.entries(found)) library.set(`${prefix}:${name}`, data);
        })
    );
    const missing = [...wanted].flatMap(([p, names]) => [...names].map((n) => `${p}:${n}`)).filter((k) => !library.has(k));
    if (missing.length) throw new Error(`Icons not found on Iconify: ${missing.join(", ")}`);

    // 3. rewrite
    const out: Op[] = [];
    let seq = 0;
    ops.forEach((op, i) => {
        if (op.op === "create" && op.type === "svg" && "icon" in op) {
            const key = String(op.icon).trim();
            const [, name] = parseIconName(key, `ops[${i}]`);
            const { icon: _icon, ...rest } = op;
            out.push({ name, width: 24, height: 24, ...rest, svg: iconToSvg(library.get(key)!) });
            return;
        }
        if (op.op !== "importIcons") {
            out.push(op);
            return;
        }
        const allowed = new Set(["op", "icons", "parent", "size", "color", "prefix", "components"]);
        const unknown = Object.keys(op).filter((k) => !allowed.has(k));
        if (unknown.length) throw new Error(`ops[${i}] importIcons: unknown properties ${unknown.join(", ")}`);
        const size = typeof op.size === "number" && op.size > 0 ? op.size : 24;
        const prefix = typeof op.prefix === "string" && op.prefix.trim() ? op.prefix.trim() : "Icon";
        const makeComponents = op.components !== false;
        for (const v of op.icons as string[]) {
            const key = v.trim();
            const [set, name] = parseIconName(key, `ops[${i}] importIcons`);
            const ref = `__iconify_${i}_${seq++}`;
            out.push({
                op: "create",
                type: "frame",
                ref,
                ...(op.parent !== undefined ? { parent: op.parent } : {}),
                name,
                width: size,
                height: size,
                fill: "none",
            });
            out.push({
                op: "create",
                type: "svg",
                parent: ref,
                name: "icon",
                svg: iconToSvg(library.get(key)!),
                width: size,
                height: size,
                ...(op.color !== undefined ? { color: op.color } : {}),
            });
            if (makeComponents) out.push({ op: "makeComponent", id: ref, name: `${prefix} / ${set} / ${name}` });
        }
    });
    return out;
}
