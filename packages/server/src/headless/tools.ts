import { z } from "zod";
import { Tool } from "../Tool";
import { PNGResponse, TextResponse, ToolResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import { HeadlessEngine } from "./HeadlessEngine";
import { PenpotCredentials } from "./PenpotCredentials";

const BROWSER_FREE =
    "Works without the Penpot MCP plugin and without anyone opening Penpot in a browser: " +
    "it talks to the Penpot backend directly and uses Penpot's own layout and render engine on the server.";

/** Base for tools backed by the browser-free engine. */
abstract class HeadlessTool<TArgs extends object> extends Tool<TArgs> {
    protected constructor(
        mcpServer: PenpotMcpServer,
        inputSchema: z.ZodRawShape,
        protected readonly engine: HeadlessEngine,
        protected readonly credentials: PenpotCredentials
    ) {
        super(mcpServer, inputSchema);
    }
}

/** Penpot ids are uuids, but not always RFC 4122 variant ones: accept any 8-4-4-4-12 hex id. */
const penpotId = () =>
    z
        .string()
        .regex(
            /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/,
            "expected a Penpot id (uuid)"
        );

const FILE_ID = penpotId().describe("Penpot file id (from list_files).");
const PAGE_ID = penpotId().optional().describe("Page id. Optional: defaults to the first page of the file.");

// ---------------------------------------------------------------- list_files

export class ListFilesArgs {
    static schema = {
        search: z.string().optional().describe("Optional case-insensitive filter on project or file name."),
    };
    search?: string;
}

export class ListFilesTool extends HeadlessTool<ListFilesArgs> {
    private static readonly MAX_FILES = 200;

    constructor(
        mcpServer: PenpotMcpServer,
        engine: HeadlessEngine,
        credentials: PenpotCredentials,
        /** When set, only projects of this team are listed (PENPOT_MCP_TEAM). */
        private readonly team?: string
    ) {
        super(mcpServer, ListFilesArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "list_files";
    }

    public getToolDescription(): string {
        const scope = this.team ? ` Only the team "${this.team}" is listed.` : "";
        return `Lists the Penpot projects and files the user can access, with their ids.${scope} ${BROWSER_FREE}`;
    }

    protected async executeCore(args: ListFilesArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const needle = args.search?.toLowerCase();
        const lines: string[] = [];
        let count = 0;

        await this.engine.backend(async (engine) => {
            const projects: any[] = await engine.rpc(ctx, "get-all-projects", {});
            for (const project of projects ?? []) {
                if (project["deleted-at"]) continue;
                if (this.team && String(project["team-name"] ?? "").toLowerCase() !== this.team.toLowerCase()) continue;
                const files: any[] = await engine.rpc(ctx, "get-project-files", { "project-id": project.id });
                const matching = (files ?? []).filter(
                    (f) =>
                        !needle ||
                        String(f.name).toLowerCase().includes(needle) ||
                        String(project.name).toLowerCase().includes(needle)
                );
                if (!matching.length) continue;
                const team = project["team-name"] ? `${project["team-name"]} / ` : "";
                lines.push(`## ${team}${project.name}`);
                for (const f of matching) {
                    if (count++ >= ListFilesTool.MAX_FILES) break;
                    const modified = f["modified-at"] ? new Date(f["modified-at"]).toISOString() : "";
                    lines.push(`- ${f.name} — fileId: ${f.id}${modified ? ` — modified ${modified}` : ""}`);
                }
            }
        });

        if (!lines.length) {
            const where = this.team ? ` in the team "${this.team}"` : "";
            return new TextResponse(
                needle ? `No files match "${args.search}"${where}.` : `No files found${where || " for this account"}.`
            );
        }
        if (count > ListFilesTool.MAX_FILES) {
            lines.push(`(only the first ${ListFilesTool.MAX_FILES} files are listed; use search to narrow down)`);
        }
        return new TextResponse(lines.join("\n"));
    }
}

// --------------------------------------------------------- get_page_overview

export class PageOverviewArgs {
    static schema = {
        fileId: FILE_ID,
        pageId: PAGE_ID,
        rootId: penpotId().optional().describe("Optional shape id to describe only that subtree (e.g. one board)."),
        depth: z.number().int().min(1).max(12).default(3).describe("How many levels of children to include."),
    };
    fileId!: string;
    pageId?: string;
    rootId?: string;
    depth: number = 3;
}

export class PageOverviewTool extends HeadlessTool<PageOverviewArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, PageOverviewArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "get_page_overview";
    }

    public getToolDescription(): string {
        return (
            "Describes the layer tree of a page (or of one shape's subtree) as compact JSON: id, name, type, " +
            "x/y (relative to the parent), width/height, fill, text, flex layout and sizing, children. " +
            `Use it before editing to find ids and understand the structure. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: PageOverviewArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engine.pageSummary({
                ...ctx,
                fileId: args.fileId,
                pageId: args.pageId,
                rootId: args.rootId,
                depth: args.depth ?? 3,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// --------------------------------------------------------------- render_shape

export class RenderShapeArgs {
    static schema = {
        fileId: FILE_ID,
        pageId: PAGE_ID,
        shapeId: penpotId()
            .optional()
            .describe("Shape (usually a board) to render. Optional: defaults to the first board of the page."),
        scale: z.number().min(0.1).max(4).default(1).describe("Scale factor; keep at 1 or below for large boards."),
        format: z.enum(["png", "svg"]).default("png").describe("png (default, returned as an image) or svg (markup)."),
    };
    fileId!: string;
    pageId?: string;
    shapeId?: string;
    scale: number = 1;
    format: "png" | "svg" = "png";
}

export class RenderShapeTool extends HeadlessTool<RenderShapeArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, RenderShapeArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "render_shape";
    }

    public getToolDescription(): string {
        return (
            "Renders a shape (usually a board) to an image you can look at, with Penpot's own renderer " +
            "(real fonts, images, effects). Use it to check your edits visually — only when needed, " +
            `since images are expensive; prefer get_page_overview for structure. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: RenderShapeArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        let { pageId, shapeId } = args;
        if (!pageId || !shapeId) {
            const overview = await this.engine.backend((engine) =>
                engine.pageSummary({ ...ctx, fileId: args.fileId, pageId, depth: 1 })
            );
            pageId = pageId ?? overview.pageId;
            shapeId = shapeId ?? overview.tree?.children?.[0]?.id;
            if (!shapeId) {
                return new TextResponse("The page is empty: there is nothing to render.");
            }
        }
        const format = args.format ?? "png";
        const bytes = await this.engine.wasm((engine) =>
            engine.renderShape({
                ...ctx,
                fileId: args.fileId,
                pageId: pageId!,
                objectId: shapeId!,
                scale: args.scale ?? 1,
                format,
            })
        );
        return format === "svg" ? new TextResponse(Buffer.from(bytes).toString("utf8")) : new PNGResponse(bytes);
    }
}

// ------------------------------------------------------------------ edit_page

const OPS_DESCRIPTION = `Operations applied in order, as one atomic save. Each is an object with "op":

create — {"op":"create","type":"frame"|"rect"|"ellipse"|"text","ref":"myCard","parent":<id or ref>, ...props}
  - parent: a board (frame) or group id, or the ref of a shape created earlier in this batch; omit for the page root.
  - x, y are relative to the parent; width, height in px. Inside a flex layout, x/y are computed by the layout.
update — {"op":"update","id":<id or ref>, ...props}  (only the given props change; x/y/width/height move/resize)
delete — {"op":"delete","id":<id or ref>}  (children are deleted too)

Props:
  name; fill ("#rrggbb" or "none"); fillOpacity (0-1); stroke ({"color","width","align":"inner|center|outer"} or "none");
  radius (number or [tl,tr,br,bl]); opacity; visible (bool); clip (boards: clip content);
  layout (boards): {"type":"flex","direction":"row|column","gap","rowGap","columnGap",
    "padding": n | [vertical,horizontal] | [top,right,bottom,left],
    "alignItems":"start|center|end|stretch","justifyContent":"start|center|end|space-between|space-around|space-evenly",
    "wrap":bool} or false to remove;
  sizing (children of flex boards, or boards hugging content): {"h":"fix|fill|hug","v":"fix|fill|hug"};
  text props: text (use \\n for new paragraphs), fontSize, fontWeight (200|300|400|600|700|900),
    textColor (or fill), textAlign (left|center|right|justify), lineHeight (multiplier, e.g. 1.4), letterSpacing,
    growType ("auto-width" default, "auto-height" when width is given: wraps at that width, "fixed").
Text size, flex positions and hug sizes are computed automatically, as in the Penpot editor.`;

export class EditPageArgs {
    static schema = {
        fileId: FILE_ID,
        pageId: PAGE_ID,
        ops: z.array(z.record(z.string(), z.any())).min(1).max(300).describe(OPS_DESCRIPTION),
        dryRun: z
            .boolean()
            .default(false)
            .describe("When true, computes the result (ids, sizes, positions) without saving."),
    };
    fileId!: string;
    pageId?: string;
    ops!: Record<string, unknown>[];
    dryRun: boolean = false;
}

export class EditPageTool extends HeadlessTool<EditPageArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, EditPageArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "edit_page";
    }

    public getToolDescription(): string {
        return (
            "Creates, updates and deletes shapes on a Penpot page: boards, rectangles, ellipses and texts, with " +
            "fills, strokes, radius, flex layout and sizing. All operations are saved together, and people who " +
            "have the file open see the changes live. Returns the ids of created shapes (by ref) and their computed " +
            `geometry. Check the result with render_shape when the visual outcome matters. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: EditPageArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.wasm((engine) =>
            engine.applyOps({
                ...ctx,
                fileId: args.fileId,
                pageId: args.pageId,
                ops: args.ops,
                dryRun: args.dryRun ?? false,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ------------------------------------------------------------------ factory

/**
 * Creates the browser-free tools when the engine build is available.
 * Returns an empty list otherwise, so the server works exactly like upstream.
 */
export function createHeadlessTools(
    mcpServer: PenpotMcpServer,
    env: Record<string, string | undefined> = process.env
): Tool<any>[] {
    if (env.PENPOT_MCP_HEADLESS === "false") return [];
    const dir = HeadlessEngine.locate(env);
    if (!dir) return [];
    const engine = new HeadlessEngine(dir);
    const credentials = new PenpotCredentials(env, () => mcpServer.getSessionContext?.()?.penpotToken);
    return [
        new ListFilesTool(mcpServer, engine, credentials, env.PENPOT_MCP_TEAM?.trim() || undefined),
        new PageOverviewTool(mcpServer, engine, credentials),
        new RenderShapeTool(mcpServer, engine, credentials),
        new EditPageTool(mcpServer, engine, credentials),
    ];
}

/** Extra connection instructions when the browser-free tools are registered. */
export const HEADLESS_INSTRUCTIONS =
    "\n\nBrowser-free tools are also available: list_files, get_page_overview, render_shape and edit_page " +
    "work directly against the Penpot backend, without the Penpot MCP plugin and without opening Penpot in a " +
    "browser. Prefer them when no plugin session is connected. Typical flow: list_files → get_page_overview → " +
    "edit_page → render_shape (only when you need to see the result).";

/**
 * Connection instructions that keep the AI in one Penpot team (PENPOT_MCP_TEAM),
 * for both the browser-free and the plugin tools. Empty when no team is set.
 */
export function teamInstructions(env: Record<string, string | undefined> = process.env): string {
    const team = env.PENPOT_MCP_TEAM?.trim();
    if (!team) return "";
    return (
        `\n\nAlways work in the Penpot team "${team}": only open, edit or create files in that team's projects ` +
        `(never in Drafts or another team). If no file or project in "${team}" fits the request, ask the user ` +
        "instead of using another team."
    );
}
