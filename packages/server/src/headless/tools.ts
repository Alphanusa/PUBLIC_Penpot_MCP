import { readFile, stat } from "node:fs/promises";
import { basename, extname, isAbsolute } from "node:path";
import { z } from "zod";
import { Tool } from "../Tool";
import { PNGResponse, TextResponse, ToolResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import { engineFunction, HeadlessEngine } from "./HeadlessEngine";
import { FONT_WEIGHTS, readFontInfo } from "./fontInfo";
import { binarySourceSchema, loadBinary } from "./sources";
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
        pages: z
            .boolean()
            .default(false)
            .describe(
                "Also list each file's pages (id and name). Slower (reads each file): combine with search; " +
                    "at most 20 files get their pages listed."
            ),
    };
    search?: string;
    pages: boolean = false;
}

export class ListFilesTool extends HeadlessTool<ListFilesArgs> {
    private static readonly MAX_FILES = 200;
    private static readonly MAX_FILES_WITH_PAGES = 20;

    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, ListFilesArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "list_files";
    }

    public getToolDescription(): string {
        return (
            "Lists the Penpot teams, projects and files the user can edit, with their ids (teamId and projectId " +
            `are what create_project and create_file take), optionally with each file's pages. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: ListFilesArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const needle = args.search?.toLowerCase();
        const lines: string[] = [];
        let count = 0;
        let withPages = 0;

        await this.engine.backend(async (engine) => {
            const projects: any[] = await engine.rpc(ctx, "get-all-projects", {});
            for (const project of projects ?? []) {
                if (project["deleted-at"]) continue;
                const files: any[] = await engine.rpc(ctx, "get-project-files", { "project-id": project.id });
                const projectMatches =
                    !needle ||
                    String(project.name).toLowerCase().includes(needle) ||
                    String(project["team-name"] ?? "")
                        .toLowerCase()
                        .includes(needle);
                const matching = (files ?? []).filter(
                    (f) => projectMatches || String(f.name).toLowerCase().includes(needle!)
                );
                // an empty project still matters: it is where new files go
                if (!matching.length && !projectMatches) continue;
                const team = project["team-name"] ? `${project["team-name"]} / ` : "";
                const ids = [`projectId: ${project.id}`, project["team-id"] ? `teamId: ${project["team-id"]}` : ""]
                    .filter(Boolean)
                    .join(", ");
                lines.push(`## ${team}${project.name} — ${ids}`);
                if (!matching.length) lines.push("(no files)");
                for (const f of matching) {
                    if (count++ >= ListFilesTool.MAX_FILES) break;
                    const modified = f["modified-at"] ? new Date(f["modified-at"]).toISOString() : "";
                    const library = f["is-shared"] ? " — shared library" : "";
                    lines.push(`- ${f.name} — fileId: ${f.id}${library}${modified ? ` — modified ${modified}` : ""}`);
                    if (args.pages && withPages++ < ListFilesTool.MAX_FILES_WITH_PAGES) {
                        const pages = await engineFunction(engine, "filePages")({ ...ctx, fileId: f.id });
                        lines.push(`  pages: ${pages.map((p) => `${p.name} (pageId: ${p.id})`).join(", ")}`);
                    }
                }
            }
        });

        if (!lines.length) {
            return new TextResponse(needle ? `No files match "${args.search}".` : "No files found for this account.");
        }
        if (count > ListFilesTool.MAX_FILES) {
            lines.push(`(only the first ${ListFilesTool.MAX_FILES} files are listed; use search to narrow down)`);
        }
        if (args.pages && withPages > ListFilesTool.MAX_FILES_WITH_PAGES) {
            lines.push(
                `(pages are listed for the first ${ListFilesTool.MAX_FILES_WITH_PAGES} files only; use search to narrow down)`
            );
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

create — {"op":"create","type":"frame"|"rect"|"ellipse"|"text"|"image"|"component","ref":"myCard","parent":<id or ref>, ...props}
  - parent: a board (frame) or group id, or the ref of a shape created earlier in this batch; omit for the page root.
  - x, y are relative to the parent; width, height in px. Inside a flex layout, x/y are computed by the layout.
  - "image": a rectangle filled with an uploaded image: {"op":"create","type":"image","image":<media id>,"fit"?}
    sized to the image's natural size, or to width/height (give one of them to keep the aspect ratio).
  - "component": a copy of a component of the file or of a linked library (see get_library), linked to it like
    one dragged from the assets panel: {"op":"create","type":"component","component":"Buttons / Primary",
    "variant"?:{"Size":"L"}, "texts"?:{"<text layer name>":"Save"}, "parent", "x", "y", "width", "height", "sizing",
    "name", "visible", "opacity"}. "texts" and the other props are overrides of the copy; change other layers of
    the copy with update ops on their ids (see get_page_overview).
update — {"op":"update","id":<id or ref>, ...props}  (only the given props change; x/y/width/height move/resize)
delete — {"op":"delete","id":<id or ref>}  (children are deleted too)
makeComponent — {"op":"makeComponent","id":<board id or ref>,"name"?:"Buttons / Primary"}  turns a board into a main
  component of this file's library (use the file as a library: share it with create_file shared, or link it).

Props:
  Colors ("fill", "stroke", "textColor") are "#rrggbb" or the name of a library color ("Brand / Primary"), which
  keeps the shape linked to it.
  name; fill: a color, "none", or an image {"image": <media id from upload_image>, "fit": "cover"|"contain"|"fill",
    "opacity"?} (cover: fills the shape, cropped — Penpot's default; contain: whole image, centered; fill: stretched);
  fillOpacity (0-1, with fill); stroke ({"color","width","align":"inner|center|outer","opacity"} or "none");
  radius (rect/board: number or [tl,tr,br,bl]); opacity (0-1); visible (bool); clip (boards: clip content);
  layout (boards): {"type":"flex","direction":"row|column","gap","rowGap","columnGap",
    "padding": n | [vertical,horizontal] | [top,right,bottom,left],
    "alignItems":"start|center|end|stretch","justifyContent":"start|center|end|space-between|space-around|space-evenly",
    "wrap":bool} or false to remove;
  sizing (children of flex boards, or boards hugging content): {"h":"fix|fill|hug","v":"fix|fill|hug"};
  text props: text (use \\n for new paragraphs), typography (a library typography, e.g. "Heading / H1": sets the
    font, size, line height, letter spacing and text transform, linked), fontFamily (a team font, the builtin "Source Sans Pro" or any
    Google font, e.g. "IBM Plex Sans"; see list_fonts), fontWeight (100-900 or "bold"; must exist for the family),
    fontStyle ("normal"|"italic"), fontSize, textColor (or fill), textAlign (left|center|right|justify),
    lineHeight (multiplier, e.g. 1.4), letterSpacing, textTransform (none|uppercase|lowercase|capitalize),
    growType ("auto-width" default, "auto-height" when width is given: wraps at that width, "fixed").
Unknown or misplaced properties are rejected with an error naming them (dryRun too), never ignored.
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
            "Creates, updates and deletes shapes on a Penpot page: boards, rectangles, ellipses, texts, images and " +
            "copies of library components, with fills (colors, library colors or uploaded images), strokes, radius, " +
            "fonts and library typographies, flex layout and sizing; turns boards into components. All operations " +
            "are saved together, and people who have the file open see the changes live. Returns the ids of created " +
            "shapes (by ref) and their computed geometry. Check the result with render_shape when the visual outcome " +
            `matters. ${BROWSER_FREE}`
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

// ------------------------------------------------------------- create_project

export class CreateProjectArgs {
    static schema = {
        name: z.string().trim().min(1).max(250).describe("Project name."),
        teamId: penpotId()
            .optional()
            .describe(
                "Team to create the project in (teamId from list_files). Optional: defaults to the account's own " +
                    "default team. When the user belongs to several teams and has not said which, ask."
            ),
    };
    name!: string;
    teamId?: string;
}

export class CreateProjectTool extends HeadlessTool<CreateProjectArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, CreateProjectArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "create_project";
    }

    public getToolDescription(): string {
        return (
            "Creates a Penpot project (a folder of files in a team) and returns its projectId. Only create one " +
            `when the user wants a new project; to add a file to an existing project use create_file. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: CreateProjectArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(engine, "createProject")({ ...ctx, name: args.name, teamId: args.teamId })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ---------------------------------------------------------------- create_file

export class CreateFileArgs {
    static schema = {
        projectId: penpotId().describe("Project to create the file in (projectId from list_files or create_project)."),
        name: z.string().trim().min(1).max(250).describe("File name."),
        pages: z
            .array(z.string().trim().min(1).max(250))
            .min(1)
            .max(50)
            .optional()
            .describe(
                'Optional page names, in order, e.g. ["Design system", "Screens"]. The first one renames the new ' +
                    'file\'s first page ("Page 1"), the others are added after it.'
            ),
        shared: z
            .boolean()
            .optional()
            .describe("Publish the file as a shared library, so other files can link it and use its assets."),
    };
    projectId!: string;
    name!: string;
    pages?: string[];
    shared?: boolean;
}

export class CreateFileTool extends HeadlessTool<CreateFileArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, CreateFileArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "create_file";
    }

    public getToolDescription(): string {
        return (
            "Creates a Penpot design file in a project, optionally with named pages, and returns its fileId and " +
            `pages (id and name), ready for edit_page. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: CreateFileArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "createFile"
            )({
                ...ctx,
                projectId: args.projectId,
                name: args.name,
                pages: args.pages,
                shared: args.shared,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ----------------------------------------------------------------- edit_pages

const PAGE_OPS_DESCRIPTION = `Page operations applied in order, saved together. Each is an object with "op":
  add    — {"op":"add","name":"Screens","ref"?:"screens","index"?:1}  (appended unless index is given)
  rename — {"op":"rename","id":<page id or ref>,"name":"Design system"}
  delete — {"op":"delete","id":<page id or ref>}  (deletes the page and everything on it; a file keeps at least one page)
  move   — {"op":"move","id":<page id or ref>,"index":0}  (index: 0 = first page)`;

export class EditPagesArgs {
    static schema = {
        fileId: FILE_ID,
        ops: z.array(z.record(z.string(), z.any())).min(1).max(100).describe(PAGE_OPS_DESCRIPTION),
        dryRun: z
            .boolean()
            .default(false)
            .describe("When true, checks the operations and returns the result without saving."),
    };
    fileId!: string;
    ops!: Record<string, unknown>[];
    dryRun: boolean = false;
}

export class EditPagesTool extends HeadlessTool<EditPagesArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, EditPagesArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "edit_pages";
    }

    public getToolDescription(): string {
        return (
            "Adds, renames, deletes and reorders the pages of a Penpot file. Returns the pages in their new order " +
            "(id and name) and the ids of added pages by ref. Delete pages only when the user asked for it. " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: EditPagesArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "editPages"
            )({
                ...ctx,
                fileId: args.fileId,
                ops: args.ops,
                dryRun: args.dryRun ?? false,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ----------------------------------------------------------------- list_fonts

export class ListFontsArgs {
    static schema = {
        search: z.string().optional().describe('Family name or part of it, e.g. "plex" or "IBM Plex Sans".'),
        fileId: penpotId()
            .optional()
            .describe("A file of the team whose uploaded fonts should be included (recommended)."),
        limit: z.number().int().min(1).max(100).default(20).describe("Maximum number of Google Fonts families listed."),
    };
    search?: string;
    fileId?: string;
    limit: number = 20;
}

export class ListFontsTool extends HeadlessTool<ListFontsArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, ListFontsArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "list_fonts";
    }

    public getToolDescription(): string {
        return (
            "Lists the font families available for texts in edit_page (fontFamily) with their weights: the team's " +
            "uploaded fonts, the builtin Source Sans Pro and the Google Fonts catalog. Use it to check that a " +
            `design system's fonts and weights exist before using them. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: ListFontsArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "listFonts"
            )({
                ...ctx,
                fileId: args.fileId,
                search: args.search,
                limit: args.limit ?? 20,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// --------------------------------------------------------------- upload_image

const IMAGE_TYPES: Record<string, string> = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
};

const IMAGE_MIME_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml"] as const;

/** Image type from the first bytes, for uploads that don't say. */
export function sniffImageType(bytes: Uint8Array): string | undefined {
    const ascii = (from: number, to: number) => Buffer.from(bytes.subarray(from, to)).toString("latin1");
    if (bytes[0] === 0x89 && ascii(1, 4) === "PNG") return "image/png";
    if (bytes[0] === 0xff && bytes[1] === 0xd8) return "image/jpeg";
    if (ascii(0, 4) === "GIF8") return "image/gif";
    if (ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP") return "image/webp";
    if (/<svg[\s>]/.test(Buffer.from(bytes.subarray(0, 4096)).toString("utf8"))) return "image/svg+xml";
    return undefined;
}

export class UploadImageArgs {
    static readonly MAX_BYTES = 20 * 1024 * 1024;

    static schema(fileSystem: boolean): z.ZodRawShape {
        return {
            fileId: penpotId().describe("File the image is for (images belong to a file's media)."),
            name: z.string().trim().min(1).max(250).optional().describe("Image name. Optional: taken from the source."),
            url: z.string().url().optional().describe("http(s) URL of the image; the Penpot server downloads it."),
            dataBase64: z
                .string()
                .optional()
                .describe('The image bytes in base64 (a "data:image/png;base64,…" URL works too), up to 20 MB.'),
            mimeType: z
                .enum(IMAGE_MIME_TYPES)
                .optional()
                .describe("Type of dataBase64. Optional: detected from the bytes."),
            ...(fileSystem
                ? {
                      filePath: z
                          .string()
                          .optional()
                          .describe("Absolute path of an image file on the machine running the MCP server."),
                  }
                : {}),
        };
    }
    fileId!: string;
    name?: string;
    url?: string;
    dataBase64?: string;
    mimeType?: (typeof IMAGE_MIME_TYPES)[number];
    filePath?: string;
}

export class UploadImageTool extends HeadlessTool<UploadImageArgs> {
    private readonly fileSystem: boolean;

    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        const fileSystem = mcpServer.isFileSystemAccessEnabled?.() ?? false;
        super(mcpServer, UploadImageArgs.schema(fileSystem), engine, credentials);
        this.fileSystem = fileSystem;
    }

    public getToolName(): string {
        return "upload_image";
    }

    public getToolDescription(): string {
        return (
            "Uploads an image (PNG, JPEG, GIF, WebP or SVG — e.g. a logo, avatar or screenshot) to a Penpot file's " +
            "media and returns its media id, width and height. Give exactly one source: url or dataBase64" +
            (this.fileSystem ? " or filePath" : "") +
            '. Upload once and reuse the id in edit_page as many times as needed: {"fill": {"image": <id>, "fit": ' +
            '"contain"}} on a rect/board/ellipse, or {"op":"create","type":"image","image":<id>}. ' +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: UploadImageArgs): Promise<ToolResponse> {
        const sources = [args.url, args.dataBase64, args.filePath].filter((v) => v !== undefined && v !== "");
        if (sources.length !== 1) {
            throw new Error(
                `Give exactly one image source: url or dataBase64${this.fileSystem ? " or filePath" : ""}.`
            );
        }
        const ctx = this.credentials.context();
        let upload: { name: string; url?: string; data?: Uint8Array; mimeType?: string };

        if (args.url) {
            const url = new URL(args.url);
            if (url.protocol !== "http:" && url.protocol !== "https:") {
                throw new Error("url must be an http(s) URL.");
            }
            upload = { name: args.name ?? (decodeURIComponent(basename(url.pathname)) || "Image"), url: args.url };
        } else if (args.dataBase64) {
            const match = /^data:([^;,]+)?(;base64)?,/.exec(args.dataBase64);
            const data = Buffer.from(match ? args.dataBase64.slice(match[0].length) : args.dataBase64, "base64");
            upload = this.bytes(data, args.mimeType ?? match?.[1], args.name ?? "Image");
        } else {
            if (!this.fileSystem) throw new Error("filePath is not available on this server: use url or dataBase64.");
            const path = args.filePath!;
            if (!isAbsolute(path)) throw new Error("filePath must be an absolute path.");
            const info = await stat(path);
            if (info.size > UploadImageArgs.MAX_BYTES) throw new Error("The image is larger than 20 MB.");
            const data = await readFile(path);
            const name = args.name ?? basename(path, extname(path));
            upload = this.bytes(data, args.mimeType ?? IMAGE_TYPES[extname(path).toLowerCase()], name);
        }

        const media = await this.engine.backend((engine) =>
            engineFunction(engine, "uploadImage")({ ...ctx, fileId: args.fileId, ...upload })
        );
        return new TextResponse(
            JSON.stringify({
                ...media,
                usage: `edit_page: {"fill": {"image": "${media.id}", "fit": "contain"}} or {"op": "create", "type": "image", "image": "${media.id}"}`,
            })
        );
    }

    private bytes(data: Uint8Array, declared: string | undefined, name: string) {
        if (data.length === 0) throw new Error("The image is empty.");
        if (data.length > UploadImageArgs.MAX_BYTES) throw new Error("The image is larger than 20 MB.");
        const mimeType = sniffImageType(data) ?? declared;
        if (!mimeType || !(IMAGE_MIME_TYPES as readonly string[]).includes(mimeType)) {
            throw new Error(
                `Unsupported image type${mimeType ? ` ${mimeType}` : ""}: use PNG, JPEG, GIF, WebP or SVG.`
            );
        }
        return { name, data, mimeType };
    }
}

// ---------------------------------------------------------------- upload_font

export class UploadFontArgs {
    static readonly MAX_BYTES = 20 * 1024 * 1024;

    static schema(fileSystem: boolean): z.ZodRawShape {
        return {
            teamId: penpotId().optional().describe("Team that gets the font (teamId from list_files)."),
            fileId: penpotId().optional().describe("Or a file of that team: the font goes to the file's team."),
            ...binarySourceSchema("font file (TTF, OTF, WOFF or WOFF2)", 20, fileSystem),
            family: z
                .string()
                .trim()
                .min(1)
                .max(250)
                .optional()
                .describe("Family name. Optional: read from the font file."),
            weight: z
                .number()
                .int()
                .min(100)
                .max(950)
                .optional()
                .describe("Weight: 100, 200, …, 900 or 950. Optional: read from the font file."),
            style: z.enum(["normal", "italic"]).optional().describe("Style. Optional: read from the font file."),
        };
    }
    teamId?: string;
    fileId?: string;
    url?: string;
    dataBase64?: string;
    filePath?: string;
    family?: string;
    weight?: number;
    style?: "normal" | "italic";
}

export class UploadFontTool extends HeadlessTool<UploadFontArgs> {
    private readonly fileSystem: boolean;

    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        const fileSystem = mcpServer.isFileSystemAccessEnabled?.() ?? false;
        super(mcpServer, UploadFontArgs.schema(fileSystem), engine, credentials);
        this.fileSystem = fileSystem;
    }

    public getToolName(): string {
        return "upload_font";
    }

    public getToolDescription(): string {
        return (
            "Adds a font variant (one weight and style per file: TTF, OTF, WOFF or WOFF2) to a team's fonts, like " +
            "the Fonts section of the dashboard. Family, weight and style are read from the file. Everyone in the " +
            "team can then use it, and edit_page takes it as fontFamily. Only upload fonts the user provided or " +
            "asked for, which they may use; Google Fonts need no upload (see list_fonts). " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: UploadFontArgs): Promise<ToolResponse> {
        if (!args.teamId === !args.fileId) throw new Error("Give teamId or fileId (the font goes to that team).");
        const ctx = this.credentials.context();
        const { bytes } = await loadBinary(args, {
            what: "font file",
            maxBytes: UploadFontArgs.MAX_BYTES,
            fileSystem: this.fileSystem,
        });
        const info = readFontInfo(bytes);
        const family = args.family ?? info.family;
        const weight = args.weight ?? info.weight;
        const style = args.style ?? info.style ?? "normal";
        if (!family) throw new Error("The font file has no readable family name: give family.");
        if (!weight || !(FONT_WEIGHTS as readonly number[]).includes(weight)) {
            throw new Error("The font file has no readable weight: give weight (100-900 or 950).");
        }
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "uploadFont"
            )({
                ...ctx,
                teamId: args.teamId,
                fileId: args.fileId,
                data: bytes,
                mimeType: info.mimeType,
                family,
                weight,
                style,
            })
        );
        return new TextResponse(
            JSON.stringify({
                ...result,
                format: info.format,
                ...(info.variable
                    ? {
                          note: "Variable font: Penpot registers it as this one weight; upload static files per weight for others.",
                      }
                    : {}),
                usage: `edit_page text props: "fontFamily": "${result.family}", "fontWeight": ${weight}${style === "italic" ? ', "fontStyle": "italic"' : ""}`,
            })
        );
    }
}

// ------------------------------------------------------------- import_library

export class ImportLibraryArgs {
    static readonly MAX_BYTES = 300 * 1024 * 1024;

    static schema(fileSystem: boolean): z.ZodRawShape {
        return {
            projectId: penpotId().describe("Project the package is imported into (projectId from list_files)."),
            ...binarySourceSchema(".penpot file", 300, fileSystem),
            name: z
                .string()
                .trim()
                .min(1)
                .max(250)
                .optional()
                .describe("File name for the import. Optional: taken from the source."),
            shared: z
                .boolean()
                .default(true)
                .describe("Publish the imported files as shared libraries (default), so other files can link them."),
            linkTo: z
                .array(penpotId())
                .max(50)
                .optional()
                .describe("Files (fileIds) to link the imported libraries to right away."),
        };
    }
    projectId!: string;
    url?: string;
    dataBase64?: string;
    filePath?: string;
    name?: string;
    shared: boolean = true;
    linkTo?: string[];
}

export class ImportLibraryTool extends HeadlessTool<ImportLibraryArgs> {
    private readonly fileSystem: boolean;

    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        const fileSystem = mcpServer.isFileSystemAccessEnabled?.() ?? false;
        super(mcpServer, ImportLibraryArgs.schema(fileSystem), engine, credentials);
        this.fileSystem = fileSystem;
    }

    public getToolName(): string {
        return "import_library";
    }

    public getToolDescription(): string {
        return (
            "Imports a .penpot file (a UI kit, icon set or design system exported from Penpot, e.g. from " +
            "penpot.app/libraries-templates) into a project, publishes it as a shared library and optionally links it " +
            "to files. Its components, colors and typographies can then be used in edit_page (see get_library). " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: ImportLibraryArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const loaded = await loadBinary(args, {
            what: ".penpot file",
            maxBytes: ImportLibraryArgs.MAX_BYTES,
            fileSystem: this.fileSystem,
        });
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "importLibrary"
            )({
                ...ctx,
                projectId: args.projectId,
                name: args.name ?? loaded.name ?? "Library",
                data: loaded.bytes,
                shared: args.shared ?? true,
                linkTo: args.linkTo,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// --------------------------------------------------------------- link_library

export class LinkLibraryArgs {
    static schema = {
        fileId: FILE_ID,
        libraryId: penpotId().describe("The shared library file (fileId of a shared file, see list_files)."),
        unlink: z.boolean().default(false).describe("Unlink instead of link."),
        sync: z
            .boolean()
            .default(false)
            .describe(
                "Also bring the file up to date with the library's current components, colors and typographies, " +
                    'like accepting "library updates" in the editor (overrides in copies are kept).'
            ),
        dryRun: z.boolean().default(false).describe("With sync: compute the changes without saving them."),
    };
    fileId!: string;
    libraryId!: string;
    unlink: boolean = false;
    sync: boolean = false;
    dryRun: boolean = false;
}

export class LinkLibraryTool extends HeadlessTool<LinkLibraryArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, LinkLibraryArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "link_library";
    }

    public getToolDescription(): string {
        return (
            "Links a shared library to a file so its components, colors and typographies can be used there " +
            "(edit_page), or unlinks it. With sync, applies the library's latest changes to the file. " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: LinkLibraryArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.wasm((engine) =>
            engineFunction(
                engine,
                "linkLibrary"
            )({
                ...ctx,
                fileId: args.fileId,
                libraryId: args.libraryId,
                unlink: args.unlink ?? false,
                sync: args.sync ?? false,
                dryRun: args.dryRun ?? false,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ---------------------------------------------------------------- get_library

export class GetLibraryArgs {
    static schema = {
        fileId: FILE_ID,
        search: z.string().optional().describe('Only assets whose name contains this, e.g. "button" or "brand /".'),
        limit: z.number().int().min(1).max(500).default(50).describe("Maximum assets listed per kind and library."),
    };
    fileId!: string;
    search?: string;
    limit: number = 50;
}

export class GetLibraryTool extends HeadlessTool<GetLibraryArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, GetLibraryArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "get_library";
    }

    public getToolDescription(): string {
        return (
            "Lists the components (with variants and size), colors and typographies a file can use: its own library " +
            "and the libraries linked to it, by the names edit_page takes. Use search for big libraries. " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: GetLibraryArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "libraryAssets"
            )({
                ...ctx,
                fileId: args.fileId,
                search: args.search,
                limit: args.limit ?? 50,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// --------------------------------------------------------------- edit_library

const LIBRARY_OPS_DESCRIPTION = `Library operations applied in order, saved together. Each is an object with "op" and "type":
  {"op":"add","type":"color","name":"Brand / Primary","color":"#3366ff","opacity"?:1}
  {"op":"update","type":"color","id":<asset id or current name>,"name"?,"color"?,"opacity"?}
  {"op":"add","type":"typography","name":"Heading / H1","fontFamily","fontWeight","fontStyle","fontSize","lineHeight",
    "letterSpacing","textTransform"}  (font props as in edit_page; unset ones default to Source Sans Pro 14/1.2)
  {"op":"update","type":"typography","id":<asset id or current name>, ...the props to change}
  {"op":"delete","type":"color"|"typography","id":<asset id or name>}
Names use " / " for groups, as in the assets panel.`;

export class EditLibraryArgs {
    static schema = {
        fileId: FILE_ID,
        ops: z.array(z.record(z.string(), z.any())).min(1).max(200).describe(LIBRARY_OPS_DESCRIPTION),
        dryRun: z.boolean().default(false).describe("When true, checks and computes the result without saving."),
    };
    fileId!: string;
    ops!: Record<string, unknown>[];
    dryRun: boolean = false;
}

export class EditLibraryTool extends HeadlessTool<EditLibraryArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, EditLibraryArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "edit_library";
    }

    public getToolDescription(): string {
        return (
            "Adds, updates and deletes the colors and typographies of a file's own library (to build a design " +
            "system; components are made with makeComponent in edit_page). Shapes of the file that use an updated " +
            "asset follow it, as in the editor; files linking the library get the change with link_library sync. " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: EditLibraryArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.wasm((engine) =>
            engineFunction(
                engine,
                "editLibrary"
            )({
                ...ctx,
                fileId: args.fileId,
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
        new ListFilesTool(mcpServer, engine, credentials),
        new PageOverviewTool(mcpServer, engine, credentials),
        new RenderShapeTool(mcpServer, engine, credentials),
        new EditPageTool(mcpServer, engine, credentials),
        new CreateProjectTool(mcpServer, engine, credentials),
        new CreateFileTool(mcpServer, engine, credentials),
        new EditPagesTool(mcpServer, engine, credentials),
        new ListFontsTool(mcpServer, engine, credentials),
        new UploadImageTool(mcpServer, engine, credentials),
        new UploadFontTool(mcpServer, engine, credentials),
        new ImportLibraryTool(mcpServer, engine, credentials),
        new LinkLibraryTool(mcpServer, engine, credentials),
        new GetLibraryTool(mcpServer, engine, credentials),
        new EditLibraryTool(mcpServer, engine, credentials),
    ];
}

/** Extra connection instructions when the browser-free tools are registered. */
export const HEADLESS_INSTRUCTIONS =
    "\n\nBrowser-free tools are also available: list_files, get_page_overview, render_shape, edit_page, " +
    "create_project, create_file, edit_pages, list_fonts, upload_image, upload_font, import_library, link_library, " +
    "get_library and edit_library work directly against the Penpot " +
    "backend, without the Penpot MCP plugin and without opening Penpot in a browser. Prefer them when no plugin " +
    "session is connected. Typical flow: list_files → get_page_overview → edit_page → render_shape (only when you " +
    "need to see the result). For a new design: create_project (only if the user wants a new project) → " +
    "create_file with named pages → list_fonts / upload_font / upload_image for the design system's fonts and " +
    "images → import_library or link_library for a .penpot UI kit, get_library to see its components, colors and " +
    "typographies → edit_page using them by name.";
