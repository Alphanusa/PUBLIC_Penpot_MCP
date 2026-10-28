import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createLogger } from "../logger";
import { Semaphore } from "../utils/Semaphore";

/** Penpot backend location + credentials for one call. */
export interface PenpotContext {
    baseUri: string;
    token: string;
}

/** The JS API of the browser-free engine (engine/dist/engine.js). */
export interface PenpotEngineModule {
    init(artifactDir: string, fontsDir?: string): Promise<boolean>;
    rpc(ctx: PenpotContext, command: string, params?: Record<string, unknown>): Promise<any>;
    renderShape(
        opts: PenpotContext & {
            fileId: string;
            pageId: string;
            objectId: string;
            scale?: number;
            format?: "png" | "jpeg" | "webp" | "svg" | "pdf";
            /** token theme(s) to preview, e.g. "Mode/Dark": applied to the render only */
            theme?: string | string[];
        }
    ): Promise<Uint8Array>;
    pageSummary(
        opts: PenpotContext & { fileId: string; pageId?: string; rootId?: string; depth?: number }
    ): Promise<any>;
    applyOps(opts: PenpotContext & { fileId: string; pageId?: string; ops: unknown[]; dryRun?: boolean }): Promise<any>;
    createProject(opts: PenpotContext & { name: string; teamId?: string }): Promise<any>;
    createFile(
        opts: PenpotContext & { projectId: string; name: string; pages?: string[]; shared?: boolean }
    ): Promise<any>;
    filePages(opts: PenpotContext & { fileId: string }): Promise<{ id: string; name: string }[]>;
    editPages(opts: PenpotContext & { fileId: string; ops: unknown[]; dryRun?: boolean }): Promise<any>;
    listFonts(
        opts: PenpotContext & { fileId?: string; teamId?: string; search?: string; limit?: number }
    ): Promise<any>;
    uploadImage(
        opts: PenpotContext & {
            fileId: string;
            name: string;
            url?: string;
            data?: Uint8Array;
            mimeType?: string;
        }
    ): Promise<{ id: string; name: string; width: number; height: number; mtype: string }>;
    uploadFont(
        opts: PenpotContext & {
            teamId?: string;
            fileId?: string;
            data: Uint8Array;
            mimeType: string;
            family: string;
            weight: number;
            style: string;
        }
    ): Promise<any>;
    importLibrary(
        opts: PenpotContext & { projectId: string; name: string; data: Uint8Array; shared?: boolean; linkTo?: string[] }
    ): Promise<any>;
    linkLibrary(
        opts: PenpotContext & { fileId: string; libraryId: string; unlink?: boolean; sync?: boolean; dryRun?: boolean }
    ): Promise<any>;
    libraryAssets(opts: PenpotContext & { fileId: string; search?: string; limit?: number }): Promise<any>;
    editLibrary(opts: PenpotContext & { fileId: string; ops: unknown[]; dryRun?: boolean }): Promise<any>;
    moveShapes(
        opts: PenpotContext & {
            fileId: string;
            pageId?: string;
            ids: string[];
            toPageId: string;
            toFileId?: string;
            mode: "move" | "copy";
            x?: number;
            y?: number;
            dryRun?: boolean;
        }
    ): Promise<any>;
    getTokens(
        opts: PenpotContext & {
            fileId: string;
            set?: string;
            type?: string;
            search?: string;
            limit?: number;
            format?: "list" | "dtcg";
        }
    ): Promise<any>;
    editTokens(opts: PenpotContext & { fileId: string; ops: unknown[]; dryRun?: boolean }): Promise<any>;
    auditDesign(
        opts: PenpotContext & { fileId: string; pageId?: string; checks?: string[]; limit?: number }
    ): Promise<any>;
    fileVersions(
        opts: PenpotContext & {
            fileId: string;
            action: "list" | "create" | "rename" | "delete" | "restore" | "lock" | "unlock";
            id?: string;
            label?: string;
        }
    ): Promise<any>;
    comments(
        opts: PenpotContext & {
            fileId: string;
            action: "list" | "create" | "reply" | "resolve" | "reopen" | "delete";
            pageId?: string;
            shapeId?: string;
            x?: number;
            y?: number;
            content?: string;
            threadId?: string;
            includeResolved?: boolean;
            limit?: number;
        }
    ): Promise<any>;
}

/**
 * Returns `engine[name]`, failing with a clear message when the engine build predates it
 * (server and engine are released together, but a local engine-dist can be older).
 */
export function engineFunction<K extends keyof PenpotEngineModule>(
    engine: PenpotEngineModule,
    name: K
): PenpotEngineModule[K] {
    const fn = engine[name];
    if (typeof fn !== "function") {
        throw new Error(
            `The browser-free engine build on this server is too old for this tool (it has no ${String(name)}). ` +
                "Rebuild the engine or fetch a newer engine-dist."
        );
    }
    return (fn as Function).bind(engine) as PenpotEngineModule[K];
}

/**
 * Lazily loads the browser-free Penpot engine (Penpot's `common` + render-wasm compiled
 * for Node, see `engine/` at the repository root) and serializes the calls that use its
 * single WASM render state.
 */
export class HeadlessEngine {
    private static readonly logger = createLogger("HeadlessEngine");

    private modulePromise?: Promise<PenpotEngineModule>;

    /** render-wasm keeps one design state per process: one render/edit at a time. */
    private readonly wasmGate = new Semaphore("headless-engine", 1);

    constructor(public readonly dir: string) {}

    /**
     * Finds the engine build: `PENPOT_MCP_ENGINE_DIR`, else `engine/dist` next to the server
     * package (repository layout), else `engine` next to the server bundle (Docker image).
     */
    public static locate(env: Record<string, string | undefined> = process.env): string | undefined {
        // an explicit setting wins, even when it points nowhere (no silent fallback)
        const candidates = env.PENPOT_MCP_ENGINE_DIR
            ? [env.PENPOT_MCP_ENGINE_DIR]
            : [resolve(process.cwd(), "../../engine/dist"), resolve(process.cwd(), "engine")];
        return candidates.find((dir) => existsSync(join(dir, "engine.js")));
    }

    private load(): Promise<PenpotEngineModule> {
        if (!this.modulePromise) {
            this.modulePromise = (async () => {
                const mod = (await import(pathToFileURL(join(this.dir, "engine.js")).href)) as PenpotEngineModule;
                await mod.init(join(this.dir, "wasm"), join(this.dir, "fonts"));
                HeadlessEngine.logger.info("Browser-free Penpot engine loaded from %s", this.dir);
                return mod;
            })();
            // a failed load must not be cached forever
            this.modulePromise.catch(() => (this.modulePromise = undefined));
        }
        return this.modulePromise;
    }

    /** Calls that only talk to the backend (no WASM state). */
    public async backend<T>(fn: (engine: PenpotEngineModule) => Promise<T>): Promise<T> {
        return fn(await this.load());
    }

    /** Calls that load a scene into render-wasm (render, edit). */
    public async wasm<T>(fn: (engine: PenpotEngineModule) => Promise<T>): Promise<T> {
        const engine = await this.load();
        return this.wasmGate.withPermit(() => fn(engine));
    }
}
