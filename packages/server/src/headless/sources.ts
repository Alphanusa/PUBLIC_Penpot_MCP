import { readFile, stat } from "node:fs/promises";
import { basename, extname, isAbsolute } from "node:path";
import { z } from "zod";

/** Where a tool takes a binary file from: exactly one of these. */
export interface BinarySource {
    url?: string;
    dataBase64?: string;
    filePath?: string;
}

export interface LoadedBinary {
    bytes: Buffer;
    /** File name without extension, from the URL or path (undefined for base64). */
    name?: string;
    /** Lower-case extension with the dot, from the URL or path. */
    extension?: string;
    /** Media type of a data: URL. */
    declaredType?: string;
}

/** Schema fields for a binary source; `filePath` only when the server may read local files. */
export function binarySourceSchema(what: string, maxMb: number, fileSystem: boolean): z.ZodRawShape {
    return {
        url: z.string().url().optional().describe(`http(s) URL of the ${what}; the MCP server downloads it.`),
        dataBase64: z.string().optional().describe(`The ${what} in base64 (a data: URL works too), up to ${maxMb} MB.`),
        ...(fileSystem
            ? {
                  filePath: z
                      .string()
                      .optional()
                      .describe(`Absolute path of the ${what} on the machine running the MCP server.`),
              }
            : {}),
    };
}

function splitName(path: string): { name?: string; extension?: string } {
    const file = basename(path);
    const extension = extname(file).toLowerCase() || undefined;
    const name = (extension ? file.slice(0, -extension.length) : file) || undefined;
    return { name, extension };
}

async function download(url: string, maxBytes: number, what: string): Promise<Buffer> {
    const response = await fetch(url, { redirect: "follow" });
    if (!response.ok) throw new Error(`Downloading the ${what} failed: HTTP ${response.status} from ${url}`);
    const declared = Number(response.headers.get("content-length") ?? 0);
    if (declared > maxBytes) throw new Error(`The ${what} is larger than ${Math.round(maxBytes / 1048576)} MB.`);
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
        size += chunk.byteLength;
        if (size > maxBytes) throw new Error(`The ${what} is larger than ${Math.round(maxBytes / 1048576)} MB.`);
        chunks.push(Buffer.from(chunk));
    }
    return Buffer.concat(chunks);
}

/** Reads the one source given, enforcing `maxBytes`. */
export async function loadBinary(
    source: BinarySource,
    options: { what: string; maxBytes: number; fileSystem: boolean }
): Promise<LoadedBinary> {
    const { what, maxBytes, fileSystem } = options;
    const given = [source.url, source.dataBase64, source.filePath].filter((v) => v !== undefined && v !== "");
    if (given.length !== 1) {
        throw new Error(`Give exactly one ${what} source: url or dataBase64${fileSystem ? " or filePath" : ""}.`);
    }
    let loaded: LoadedBinary;
    if (source.url) {
        const url = new URL(source.url);
        if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error("url must be an http(s) URL.");
        loaded = { bytes: await download(source.url, maxBytes, what), ...splitName(decodeURIComponent(url.pathname)) };
    } else if (source.dataBase64) {
        const match = /^data:([^;,]+)?(;base64)?,/.exec(source.dataBase64);
        const bytes = Buffer.from(match ? source.dataBase64.slice(match[0].length) : source.dataBase64, "base64");
        loaded = { bytes, declaredType: match?.[1] };
    } else {
        if (!fileSystem) throw new Error("filePath is not available on this server: use url or dataBase64.");
        const path = source.filePath!;
        if (!isAbsolute(path)) throw new Error("filePath must be an absolute path.");
        const info = await stat(path);
        if (info.size > maxBytes) throw new Error(`The ${what} is larger than ${Math.round(maxBytes / 1048576)} MB.`);
        loaded = { bytes: await readFile(path), ...splitName(path) };
    }
    if (loaded.bytes.length === 0) throw new Error(`The ${what} is empty.`);
    if (loaded.bytes.length > maxBytes) {
        throw new Error(`The ${what} is larger than ${Math.round(maxBytes / 1048576)} MB.`);
    }
    return loaded;
}
