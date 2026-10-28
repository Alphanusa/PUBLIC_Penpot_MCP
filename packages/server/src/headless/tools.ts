import { readFile, stat } from "node:fs/promises";
import { basename, extname, isAbsolute, join } from "node:path";
import { z } from "zod";
import { Tool } from "../Tool";
import { ImageContent, PNGResponse, TextContent, TextResponse, ToolResponse } from "../ToolResponse";
import { FileUtils } from "../utils/FileUtils";
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
            "x/y (relative to the parent), width/height, fill, stroke, radius, opacity, text with its color and style " +
            "(font, weight, size, line height), flex layout and sizing, children; library colors and typographies " +
            "are named (fillAsset, stroke.asset, textColorAsset, textStyle.typography). " +
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
        theme: z
            .union([z.string(), z.array(z.string()).min(1).max(10)])
            .optional()
            .describe(
                'Design token theme(s) to preview, e.g. "Mode/Dark" (see get_tokens): shapes using tokens show that ' +
                    "theme's values in this render only; the file is not changed."
            ),
    };
    fileId!: string;
    pageId?: string;
    shapeId?: string;
    scale: number = 1;
    format: "png" | "svg" = "png";
    theme?: string | string[];
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
                ...(args.theme ? { theme: args.theme } : {}),
            })
        );
        return format === "svg" ? new TextResponse(Buffer.from(bytes).toString("utf8")) : new PNGResponse(bytes);
    }
}

// ------------------------------------------------------------------ edit_page

const OPS_DESCRIPTION = `Operations applied in order, as one atomic save. Each is an object with "op".
design_guide explains each area in detail, with examples (topics: components, vectors, effects, text, layout,
tokens, prototypes, workflow).

create — {"op":"create","type":<type>,"ref":"myCard","parent":<board/group id or ref>, ...props}
  type: "frame" (board), "rect", "ellipse", "text", "image" ({"image":<media id>,"fit"?}), "svg" ({"svg":"<svg…>",
  "color"?} imported as editable vectors), "path" ({"d":"M0 0 L24 12 L0 24 Z"}), or "component" (a copy:
  {"component":"Buttons / Button","variant"?:{"Type":"Primary"},"texts"?:{"Label":"Save"}}).
  parent: omit for the page root. x, y are relative to the parent; inside a flex/grid layout the layout places it.
update — {"op":"update","id":<id or ref>, ...props}   (only the given props change)
delete — {"op":"delete","id"}   move — {"op":"move","id","parent"?:<id|null>,"index"?:0,"x"?,"y"?} (layers-panel order;
  0 = top = first in a layout; move_shapes moves boards to another page or file)
group — {"op":"group","ids":[…],"name"?,"ref"?}   ungroup — {"op":"ungroup","id"} (a group, boolean or board)
boolean — {"op":"boolean","ids":[…],"kind":"union|difference|intersection|exclude","name"?,"ref"?}
makeComponent — {"op":"makeComponent","id":<board>,"name"?:"Buttons / Primary","annotation"?:"usage notes"}
makeVariants — {"op":"makeVariants","name":"Buttons / Button","ref"?,"layout"?:{…}|false,
  "variants":[{"id":<board or main>,"properties":{"Type":"Primary","Size":"M"}}, …]}  (or "container":<set board> to
  add variants to an existing set) — combines boards/components into a variant set, like the editor.
setVariant — {"op":"setVariant","id":<variant main>,"properties":{"State":"Hover"}} (new names are added to every
  variant), or on any variant or the set board: "rename":{"Old":"New"}, "remove":["Prop"].
swap — {"op":"swap","id":<copy>,"component"?:"Icons / Close","variant"?:{"Type":"Secondary"},"keepOverrides"?:true}
  (variant alone switches to the nearest variant with those values, keeping text and other overrides)
detach — {"op":"detach","id":<copy>}   resetOverrides — {"op":"resetOverrides","id":<copy>}
annotate — {"op":"annotate","id":<main>,"annotation":"…"|null}
linkAssets — {"op":"linkAssets","scope"?:<board>|"page","colors"?:true,"typographies"?:false,"prefer"?:{"#fff":"Surface"}}
  links raw colors/text styles to library assets with exactly that value (reports ambiguous and unmatched values).
flow — {"op":"flow","name":"Onboarding","start":<top-level board>}   deleteFlow — {"op":"deleteFlow","name"}
Editing a main component updates its copies on every page of the file (copiesUpdated in the result).

Props (create and update):
  name; x; y; width; height; rotation (degrees); opacity (0-1); visible; clip (boards); radius (number or
  [tl,tr,br,bl]);
  fill: "#rrggbb" | library color name | "none" | {"color","opacity"} | {"gradient":{"type":"linear|radial",
    "angle":90,"stops":["#3366ff","#00aa88"]}} | {"image":<media id>,"fit":"cover|contain|fill"} | a list of
    those (first on top); fillOpacity;
  stroke: "#rrggbb" or {"color"|"gradient","width","align":"inner|center|outer","style":"solid|dashed|dotted",
    "opacity"} or "none";
  shadow: {"style":"drop|inner","x","y","blur","spread","color","opacity"} or a list, or "none";
  blur (layer blur px, 0 removes); backgroundBlur; blendMode ("multiply", "screen", "overlay", …);
  layout (boards): {"type":"flex","direction":"row|column","gap","rowGap","columnGap","padding":n|[v,h]|[t,r,b,l],
    "alignItems","justifyContent","alignContent","wrap"} or {"type":"grid","columns":["1fr","1fr",240],
    "rows":[64,"auto"],"gap","padding","alignItems","justifyItems","direction"} or false;
  children of layouts: sizing {"h":"fix|fill|hug","v":…}, margin, minWidth, maxWidth, minHeight, maxHeight,
    alignSelf, absolute (out of the flow), zIndex; grid children: cell {"row","column","rowSpan","columnSpan",
    "alignSelf","justifySelf"} (from 1);
  constraints (outside layouts): {"h":"left|right|leftright|center|scale","v":"top|bottom|topbottom|center|scale"};
  text: text (\\n = new paragraph) or spans [{"text","fontWeight","textColor","textDecoration",…}], typography (a
    library typography), fontFamily, fontWeight, fontStyle, fontSize, textColor, textAlign, verticalAlign
    (top|center|bottom), lineHeight, letterSpacing, textTransform, textDecoration (underline|line-through),
    growType (auto-width|auto-height|fixed);
  color (svg/group/path/boolean: paints every fill and stroke, e.g. icons); d (paths);
  tokens: {"fill","stroke","textColor","radius","opacity","width","height","gap","rowGap","columnGap","padding",
    "margin","minWidth","maxWidth","minHeight","maxHeight","strokeWidth","rotation","shadow","typography",
    "fontSize","fontFamily","fontWeight","lineHeight","letterSpacing","textCase","textDecoration": <token name>}
    applies design tokens like the Tokens panel (see get_tokens); a prop set by hand later unlinks its token;
  interactions (prototype): [{"trigger":"click|hover|mouse-enter|mouse-leave|press|after-delay","action":
    "navigate|open-overlay|toggle-overlay|close-overlay|back|open-url","destination":<board>,"delay","url",
    "overlay":{"position":"center|top-left|…|bottom-center","closeOnClickOutside","background"},
    "animation":{"type":"dissolve|slide|push","direction","duration","easing"}}] or "none";
  exports: [{"type":"png|jpeg|webp|svg|pdf","scale":2,"suffix":"@2x"}] (export settings, see export_assets).
Unknown or misplaced properties are rejected with an error naming them (dryRun too), never ignored.
Text size, layout positions and hug sizes are computed automatically, as in the Penpot editor.`;

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
            "Creates, updates, moves and deletes shapes on a Penpot page: boards, rectangles, ellipses, texts, " +
            "images, SVG vectors, paths, groups, boolean shapes and copies of components; fills (colors, gradients, " +
            "images, library colors), strokes, shadows, blur, rich text, flex and grid layouts, constraints, design " +
            "tokens, prototype interactions and export settings. Builds components and variant sets, swaps and " +
            "detaches copies (copies follow their main), links raw values to library assets. All operations are " +
            "saved together, and people who have the file open see the changes live. Returns the ids of created " +
            "shapes (by ref) and their computed geometry. Check the result with render_shape when the visual outcome " +
            `matters, and audit_design when building a design system. ${BROWSER_FREE}`
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

// ---------------------------------------------------------------- move_shapes

export class MoveShapesArgs {
    static schema = {
        fileId: FILE_ID,
        ids: z
            .array(penpotId())
            .min(1)
            .max(200)
            .describe(
                "Shapes to move or copy, usually top-level boards (children go with them), all on one page. " +
                    "A shape whose ancestor is also listed goes with that ancestor."
            ),
        pageId: penpotId().optional().describe("Page the shapes are on. Optional: found from the ids."),
        toPageId: z
            .string()
            .trim()
            .min(1)
            .describe("Target page: its id, or its name (see list_files with pages, or create pages with edit_pages)."),
        toFileId: penpotId()
            .optional()
            .describe("Another file of the same team to copy the shapes to (mode copy). Optional: the same file."),
        mode: z
            .enum(["move", "copy"])
            .default("move")
            .describe(
                "move: the shapes leave their page and keep their ids (same file only). copy: new shapes with new " +
                    "ids, the originals stay."
            ),
        x: z
            .number()
            .optional()
            .describe("New x of the first shape (on the target page); the others keep their offsets."),
        y: z.number().optional().describe("New y of the first shape; the others keep their offsets."),
        dryRun: z
            .boolean()
            .default(false)
            .describe("When true, checks and computes the result without saving (and without copying images)."),
    };
    fileId!: string;
    ids!: string[];
    pageId?: string;
    toPageId!: string;
    toFileId?: string;
    mode: "move" | "copy" = "move";
    x?: number;
    y?: number;
    dryRun: boolean = false;
}

export class MoveShapesTool extends HeadlessTool<MoveShapesArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, MoveShapesArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "move_shapes";
    }

    public getToolDescription(): string {
        return (
            "Moves boards (or other shapes, with their children) to another page of the same file, or copies them to " +
            "a page of the same or another file of the team — e.g. to sort screens into pages, or to put a design " +
            "system's boards into a library file. move keeps ids, child order, layouts, images and library links; main " +
            "components keep working; flows and guides of moved boards move too; prototype links that would cross " +
            "pages are removed. copy duplicates like paste: in another file, images are copied into it, library colors " +
            "and typographies of the source file are relinked to the target's assets with the same names (others keep " +
            "their raw value and are reported under assets.unlinked), main components become components of the target " +
            "and copies of them are relinked; copies of components the target can't reach are detached. Returns the " +
            "map of old ids to new ids, for later edit_page calls. Within one page use edit_page's move op instead. " +
            BROWSER_FREE
        );
    }

    protected async executeCore(args: MoveShapesArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.wasm((engine) =>
            engineFunction(
                engine,
                "moveShapes"
            )({
                ...ctx,
                fileId: args.fileId,
                pageId: args.pageId,
                ids: args.ids,
                toPageId: args.toPageId,
                toFileId: args.toFileId,
                mode: args.mode ?? "move",
                x: args.x,
                y: args.y,
                dryRun: args.dryRun ?? false,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ----------------------------------------------------------------- get_tokens

export class GetTokensArgs {
    static schema = {
        fileId: FILE_ID,
        set: z.string().optional().describe("Only the tokens of this set (name)."),
        type: z
            .string()
            .optional()
            .describe('Only tokens of this type, e.g. "color", "spacing", "border-radius", "typography".'),
        search: z.string().optional().describe("Only tokens whose name contains this."),
        limit: z
            .number()
            .int()
            .min(0)
            .max(2000)
            .default(200)
            .describe("Maximum tokens listed (themes and sets are all listed)."),
        format: z
            .enum(["list", "dtcg"])
            .default("list")
            .describe(
                "list (default): themes, sets and tokens with resolved values; dtcg: every set, theme and token as " +
                    "W3C design tokens (DTCG) JSON, like the Tokens panel's export (for code, Style Dictionary, or " +
                    "edit_tokens import in another file)."
            ),
    };
    fileId!: string;
    set?: string;
    type?: string;
    search?: string;
    limit: number = 200;
    format: "list" | "dtcg" = "list";
}

export class GetTokensTool extends HeadlessTool<GetTokensArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, GetTokensArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "get_tokens";
    }

    public getToolDescription(): string {
        return (
            "Lists the design tokens of a file, as in the Tokens panel: themes (group, name, sets, active), sets " +
            "(active, token count) and tokens (set, name, type, value as written and the value it resolves to — " +
            "references and arithmetic computed). When the file takes its tokens from a library, they are that " +
            `library's (tokensSource). ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: GetTokensArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "getTokens"
            )({
                ...ctx,
                fileId: args.fileId,
                set: args.set,
                type: args.type,
                search: args.search,
                limit: args.limit ?? 200,
                format: args.format ?? "list",
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ---------------------------------------------------------------- edit_tokens

const TOKEN_OPS_DESCRIPTION = `Token operations applied in order, saved together. Each is an object with "op":
  {"op":"addSet","name":"core","description"?,"active"?:true}   (a name with "/" puts the set in a group: "brand/light")
  {"op":"updateSet","set":<name or id>,"name"?,"description"?,"active"?}
  {"op":"deleteSet","set":<name or id>}
  {"op":"addTheme","group"?:"Mode","name":"Light","sets"?:["core","light"],"description"?,"active"?:true}
  {"op":"updateTheme","theme":<"group/name", name or id>,"group"?,"name"?,"sets"?,"description"?,"active"?}
  {"op":"deleteTheme","theme":<"group/name", name or id>}
  {"op":"addToken","set":<name or id>,"type":"color","name":"color.primary","value":"#0066ff","description"?}
  {"op":"updateToken","set","token":<name or id>,"name"?,"value"?,"description"?}   (the type can't change)
  {"op":"deleteToken","set","token"}
  {"op":"import","tokens":<DTCG JSON object or text>,"mode"?:"merge"|"replace","setName"?:"core"}  imports W3C design
    tokens (DTCG) or Tokens Studio JSON like the panel: several sets with $themes/$metadata, or one set (named
    setName). merge (default) adds and updates by name; replace makes the import the file's tokens.
Types: color, dimensions, spacing, sizing, border-radius, stroke-width, opacity (0-1), rotation, number, font-family
  (["Inter", "sans-serif"] or "Inter, sans-serif"), font-size, font-weight, letter-spacing, text-case,
  text-decoration, typography ({"fontFamily","fontSize","fontWeight","lineHeight","letterSpacing",...}),
  shadow ([{"offsetX","offsetY","blur","spread","color","inset"?}]), boolean, string, other (DTCG names like
  "borderRadius" or "fontSizes" work too). Names use dots for groups ("space.md").
Values may refer to other tokens and compute: "{color.primary}", "{space.md} * 2".
Activating a theme activates its sets and turns off the other themes of its group; activating a set directly turns
all themes off, as in the Tokens panel.`;

export class EditTokensArgs {
    static schema = {
        fileId: FILE_ID,
        ops: z.array(z.record(z.string(), z.any())).min(1).max(500).describe(TOKEN_OPS_DESCRIPTION),
        dryRun: z.boolean().default(false).describe("When true, checks the operations without saving."),
    };
    fileId!: string;
    ops!: Record<string, unknown>[];
    dryRun: boolean = false;
}

export class EditTokensTool extends HeadlessTool<EditTokensArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, EditTokensArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "edit_tokens";
    }

    public getToolDescription(): string {
        return (
            "Creates, updates and deletes design token sets, themes and tokens of a file, and switches themes and sets " +
            "on or off, as in the Tokens panel; shapes using a token whose value changes follow it. Names, values " +
            "and references are checked like the panel does " +
            "(duplicate names, broken or circular references, invalid values). Returns the ids of what was created " +
            `and the themes and sets after the change. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: EditTokensArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.wasm((engine) =>
            engineFunction(
                engine,
                "editTokens"
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

// --------------------------------------------------------------- audit_design

export class AuditDesignArgs {
    static schema = {
        fileId: FILE_ID,
        pageId: PAGE_ID.describe("Only this page. Optional: all pages of the file."),
        checks: z
            .array(
                z.enum(["colors", "typography", "spacing", "radius", "contrast", "components", "naming", "structure"])
            )
            .min(1)
            .optional()
            .describe("Only these checks. Optional: all of them."),
        limit: z.number().int().min(1).max(300).default(60).describe("Maximum issues listed (worst first)."),
    };
    fileId!: string;
    pageId?: string;
    checks?: string[];
    limit: number = 60;
}

export class AuditDesignTool extends HeadlessTool<AuditDesignArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, AuditDesignArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "audit_design";
    }

    public getToolDescription(): string {
        return (
            "Checks a design against its design system and reports what to fix, worst first: colors and text styles " +
            "used raw instead of library assets or tokens (with the asset to link), spacing and radius off the token " +
            "scale, text contrast below WCAG AA, repeated layer structures that should be components, variants with " +
            "empty properties, default layer names, layers outside boards, rows and columns placed by hand. Each " +
            "issue names a few of the shapes and how to fix it; the report also gives a score, the palette in use and " +
            "how much is linked to assets or tokens. Run it after building or changing screens and fix with edit_page " +
            `(e.g. linkAssets). ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: AuditDesignArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "auditDesign"
            )({ ...ctx, fileId: args.fileId, pageId: args.pageId, checks: args.checks, limit: args.limit ?? 60 })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// -------------------------------------------------------------- file_versions

export class FileVersionsArgs {
    static schema = {
        fileId: FILE_ID,
        action: z
            .enum(["list", "create", "rename", "delete", "restore", "lock", "unlock"])
            .default("list")
            .describe(
                "list the versions; create one (a named checkpoint, e.g. before a big change); rename; delete; " +
                    "restore one (the file goes back to it — Penpot first saves the current state as a version); " +
                    "lock/unlock (a locked version can't be deleted by others)."
            ),
        id: penpotId().optional().describe("Version id (from list), for rename, delete, restore, lock and unlock."),
        label: z.string().trim().min(1).max(250).optional().describe("Version name, for create and rename."),
    };
    fileId!: string;
    action: "list" | "create" | "rename" | "delete" | "restore" | "lock" | "unlock" = "list";
    id?: string;
    label?: string;
}

export class FileVersionsTool extends HeadlessTool<FileVersionsArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, FileVersionsArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "file_versions";
    }

    public getToolDescription(): string {
        return (
            "Manages the versions of a file, as in Penpot's History panel: list them, save the current state as a " +
            "named version (do this before large or risky edits), rename, delete, lock, or restore a version. " +
            `Restoring replaces the file's content: confirm with the user first. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: FileVersionsArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "fileVersions"
            )({ ...ctx, fileId: args.fileId, action: args.action ?? "list", id: args.id, label: args.label })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// ------------------------------------------------------------------- comments

export class CommentsArgs {
    static schema = {
        fileId: FILE_ID,
        action: z
            .enum(["list", "create", "reply", "resolve", "reopen", "delete"])
            .default("list")
            .describe("list threads (with their comments); create a thread; reply; resolve; reopen; delete a thread."),
        pageId: PAGE_ID.describe("Page of a new thread, or the page to list. Optional: the first page / all pages."),
        shapeId: penpotId().optional().describe("For create: pin the comment next to this shape (its board)."),
        x: z.number().optional().describe("For create without shapeId: position on the page."),
        y: z.number().optional(),
        content: z.string().trim().min(1).max(2000).optional().describe("Comment text, for create and reply."),
        threadId: penpotId().optional().describe("Thread id (from list), for reply, resolve, reopen and delete."),
        includeResolved: z.boolean().default(false).describe("list: include resolved threads."),
        limit: z.number().int().min(1).max(100).default(30).describe("list: maximum threads."),
    };
    fileId!: string;
    action: "list" | "create" | "reply" | "resolve" | "reopen" | "delete" = "list";
    pageId?: string;
    shapeId?: string;
    x?: number;
    y?: number;
    content?: string;
    threadId?: string;
    includeResolved: boolean = false;
    limit: number = 30;
}

export class CommentsTool extends HeadlessTool<CommentsArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, CommentsArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "comments";
    }

    public getToolDescription(): string {
        return (
            "Reads and writes the comments of a Penpot file: lists open threads with their comments (feedback to act " +
            "on), starts a thread pinned to a shape (e.g. to explain a design decision or ask for review), replies, " +
            `resolves, reopens and deletes threads. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: CommentsArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const result = await this.engine.backend((engine) =>
            engineFunction(
                engine,
                "comments"
            )({
                ...ctx,
                fileId: args.fileId,
                action: args.action ?? "list",
                pageId: args.pageId,
                shapeId: args.shapeId,
                x: args.x,
                y: args.y,
                content: args.content,
                threadId: args.threadId,
                includeResolved: args.includeResolved ?? false,
                limit: args.limit ?? 30,
            })
        );
        return new TextResponse(JSON.stringify(result));
    }
}

// -------------------------------------------------------------- export_assets

const EXPORT_TYPES = ["png", "jpeg", "webp", "svg", "pdf"] as const;
type ExportType = (typeof EXPORT_TYPES)[number];
const MIME: Record<ExportType, string> = {
    png: "image/png",
    jpeg: "image/jpeg",
    webp: "image/webp",
    svg: "image/svg+xml",
    pdf: "application/pdf",
};

export class ExportAssetsArgs {
    static schema = {
        fileId: FILE_ID,
        pageId: PAGE_ID,
        shapeId: penpotId().describe("Shape to export: a board, a component's main, an icon…"),
        exports: z
            .array(
                z.object({
                    type: z.enum(EXPORT_TYPES),
                    scale: z.number().min(0.1).max(8).default(1),
                    suffix: z.string().max(40).default(""),
                })
            )
            .min(1)
            .max(10)
            .optional()
            .describe(
                'Formats, e.g. [{"type":"png","scale":2,"suffix":"@2x"},{"type":"svg"}]. Optional: the shape\'s ' +
                    "export settings (the exports prop of edit_page), else PNG at 1x."
            ),
        directory: z
            .string()
            .optional()
            .describe("Absolute directory to write the files to (named after the shape plus the suffix)."),
    };
    fileId!: string;
    pageId?: string;
    shapeId!: string;
    exports?: { type: ExportType; scale: number; suffix: string }[];
    directory?: string;
}

export class ExportAssetsTool extends HeadlessTool<ExportAssetsArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        let schema: any = ExportAssetsArgs.schema;
        if (!mcpServer.isFileSystemAccessEnabled?.()) {
            schema = { ...schema };
            delete schema.directory;
        }
        super(mcpServer, schema, engine, credentials);
    }

    public getToolName(): string {
        return "export_assets";
    }

    public getToolDescription(): string {
        return (
            "Exports a shape as PNG, JPEG, WebP, SVG or PDF at any scale, like the Export section of the design tab, " +
            "using the shape's export settings when no formats are given — e.g. icons as SVG and @2x PNG for " +
            "developers. Images are returned inline" +
            (this.mcpServer.isFileSystemAccessEnabled?.() ? " or written to a directory" : "") +
            `. ${BROWSER_FREE}`
        );
    }

    protected async executeCore(args: ExportAssetsArgs): Promise<ToolResponse> {
        const ctx = this.credentials.context();
        const summary = await this.engine.backend((engine) =>
            engine.pageSummary({ ...ctx, fileId: args.fileId, pageId: args.pageId, rootId: args.shapeId, depth: 1 })
        );
        const pageId = args.pageId ?? summary.pageId;
        const name = String(summary.tree?.name ?? "export").replace(/[\\/:*?"<>|]+/g, "-");
        const exports = args.exports ?? summary.tree?.exports ?? [{ type: "png", scale: 1, suffix: "" }];
        if (args.directory) FileUtils.checkPathIsAbsolute(args.directory);
        const content: any[] = [];
        const written: string[] = [];
        for (const e of exports) {
            const type = (e.type ?? "png") as ExportType;
            const scale = e.scale ?? 1;
            const bytes = await this.engine.wasm((engine) =>
                engine.renderShape({ ...ctx, fileId: args.fileId, pageId, objectId: args.shapeId, scale, format: type })
            );
            const fileName = `${name}${e.suffix ?? ""}.${type === "jpeg" ? "jpg" : type}`;
            if (args.directory) {
                const path = join(args.directory, fileName);
                FileUtils.writeBinaryFile(path, bytes);
                written.push(path);
            } else if (type === "svg") {
                content.push(new TextContent(`${fileName}:\n${Buffer.from(bytes).toString("utf8")}`));
            } else if (type === "pdf") {
                content.push(new TextContent(fileName));
                content.push({
                    type: "resource",
                    resource: {
                        uri: `penpot://export/${encodeURIComponent(fileName)}`,
                        mimeType: MIME.pdf,
                        blob: Buffer.from(bytes).toString("base64"),
                    },
                });
            } else {
                content.push(new TextContent(fileName));
                content.push(new ImageContent(Buffer.from(bytes).toString("base64"), MIME[type]));
            }
        }
        if (args.directory) return new TextResponse(`Exported:\n${written.join("\n")}`);
        return new ToolResponse(content);
    }
}

// ---------------------------------------------------------------- design_guide

const GUIDES: Record<string, string> = {
    workflow: `Building a design system with these tools
1. Tokens first (edit_tokens): sets "core" (raw values: color.blue.500, space.4, radius.md, font sizes) and
   semantic sets ("light"/"dark": color.bg, color.text, color.primary = "{color.blue.500}"), themes Mode/Light and
   Mode/Dark. Or import existing DTCG JSON (edit_tokens import).
2. Library assets (edit_library): colors and typographies for the styles that must be linkable from the assets
   panel. Fonts: list_fonts / upload_font.
3. A "Components" page (create_file with pages, or edit_pages): build each component as a board with a flex or grid
   layout, applying tokens ("tokens": {"fill": "color.primary", "padding": "space.4", "radius": "radius.md",
   "typography": "type.label"}), then makeComponent with an annotation. Icons: create type "svg" with the icon
   markup and "color", inside a 24px board, then makeComponent ("Icons / Check").
4. Variants: build one board per state/size, then makeVariants {"name": "Buttons / Button", "variants": [{"id":
   …, "properties": {"Type": "Primary", "State": "Default"}}, …]}. Add states later with makeVariants "container".
5. Screens: copies of components (create type "component" with "variant" and "texts"), swap to switch variants,
   layouts with sizing fill/hug so screens adapt.
6. Check: render_shape (also with theme "Mode/Dark"), audit_design, then fix (linkAssets, tokens, contrast).
7. Share: file_versions create before big changes; create_file shared or import_library; link_library in other
   files; get_tokens format "dtcg" to hand tokens to developers; export_assets for icons and images; comments to
   explain decisions.`,
    components: `Components, variants and copies
- makeComponent {"id": <board>, "name": "Forms / Input", "annotation": "When to use it"}: the board stays as the main.
- makeVariants {"name": "Buttons / Button", "variants": [{"id": "primary", "properties": {"Type": "Primary",
  "Size": "M"}}, {"id": "secondary", "properties": {"Type": "Secondary", "Size": "M"}}], "layout": {"direction":
  "column", "gap": 24}} creates the purple variant set board (flex row by default; "layout": false keeps positions).
  Boards that aren't components become components. Every variant must differ in at least one value.
- makeVariants {"container": <set board>, "variants": [{"id": <new board>, "properties": {"Type": "Ghost"}}]} adds
  variants; setVariant {"id": <variant main>, "properties": {"State": "Hover"}} sets values (a new property gets
  "" on the other variants: set them too); setVariant {"id": <set or any variant>, "rename": {"Type": "Kind"}} or
  {"remove": ["Size"]}.
- Copies: {"op": "create", "type": "component", "component": "Buttons / Button", "variant": {"Type": "Primary"},
  "texts": {"Label": "Save"}, "parent": <board>}. Change other layers of a copy with update on their ids (overrides).
- swap {"id": <copy>, "variant": {"Type": "Secondary"}} switches variant keeping overrides; {"component":
  "Icons / Close"} swaps a nested icon copy for another component.
- detach turns a copy into plain layers; resetOverrides drops its overrides; annotate sets a main's note.
- Editing a main (any of its layers) updates every copy in the file; other files get it with link_library sync.`,
    vectors: `SVG, paths, groups and boolean shapes
- {"op": "create", "type": "svg", "svg": "<svg viewBox='0 0 24 24' …>…</svg>", "width": 24, "height": 24,
  "color": "#1f2937", "parent": <board>} imports the markup as editable shapes in a group (like pasting an SVG).
  "color" paints every fill and stroke (icon tinting; a library color name keeps it linked). Embedded bitmaps
  are skipped.
- {"op": "create", "type": "path", "d": "M0 0 L24 12 L0 24 Z", "fill": "#e8593c"}: d is in the parent's
  coordinates; x/y move it, width/height scale it. Without fill or stroke it gets a 1px black centered stroke.
- update "color" on an svg group recolors it; update "d" changes a path.
- group {"ids": [...], "name": "Logo"} / ungroup {"id"} (also removes a board, keeping its children).
- boolean {"ids": [...], "kind": "union|difference|intersection|exclude"}: the result takes the style of the top
  shape (the bottom one for difference). Boards and texts can't be combined.`,
    effects: `Fills, strokes and effects
- fill: "#3366ff", a library color name, {"gradient": {"type": "linear", "angle": 135, "stops": ["#3366ff",
  {"color": "#00aa88", "opacity": 0.8, "offset": 1}]}} (angle like CSS: 90 = left to right, 180 = top to bottom;
  or "start"/"end": [x, y] fractions), {"gradient": {"type": "radial", "stops": [...]}}, an image, or a list of
  fills (first on top).
- stroke: {"color", "width", "align": "inner|center|outer", "style": "solid|dashed|dotted", "opacity"} or
  {"gradient": {...}, "width"}.
- shadow: {"y": 4, "blur": 12, "color": "#0b1b3f", "opacity": 0.12} (style "drop" by default, "inner" for inner
  shadows; spread), or a list for layered elevation; "none" removes.
- blur: 8 (layer blur), backgroundBlur: 16 (frosted glass, with a semi-transparent fill), blendMode: "multiply".
- Prefer tokens for repeatable values: tokens {"shadow": "elevation.2", "strokeWidth": "border.thin"}.`,
    text: `Text
- text: "Hello\\nWorld" (\\n = new paragraph). spans: [{"text": "Total "}, {"text": "Rp 1.250.000", "fontWeight":
  "700"}, {"text": " /mo", "textColor": "#6b7280", "fontSize": 12}] for mixed styles; each span takes text props
  (fontFamily, fontWeight, fontStyle, fontSize, textColor, lineHeight, letterSpacing, textTransform,
  textDecoration, typography) over the text's own.
- typography: a library typography name ("Heading / H1"), or tokens {"typography": "type.heading.h1"}.
- verticalAlign: top|center|bottom (fixed-height texts); textDecoration: underline|line-through; growType:
  auto-width, auto-height (give width), fixed.`,
    layout: `Layout
- Flex: layout {"type": "flex", "direction": "row|column", "gap": 8, "padding": [12, 16], "alignItems": "center",
  "justifyContent": "space-between", "wrap": true}. Children: sizing {"h": "fill", "v": "hug"}, minWidth,
  maxWidth, minHeight, maxHeight, margin, alignSelf, absolute (taken out of the flow, positioned with x/y),
  zIndex.
- Grid: layout {"type": "grid", "columns": ["1fr", "1fr", 280], "rows": [64, "auto", "1fr"], "gap": 16,
  "padding": 24}. Tracks: number = px, "Nfr", "N%", "auto". Children fill cells in order, or go to one:
  cell {"row": 1, "column": 1, "columnSpan": 3, "alignSelf": "center"} (rows and columns from 1; missing tracks
  are added). Changing columns/rows places the children again in order.
- Outside layouts: constraints {"h": "leftright", "v": "bottom"} make a shape follow its board's resizes.
- Boards hug their content with sizing {"h": "hug", "v": "hug"} (flex/grid boards).`,
    tokens: `Design tokens
- get_tokens lists themes, sets and tokens with resolved values; format "dtcg" exports W3C DTCG JSON.
- edit_tokens: addSet/addTheme/addToken…, "import" (DTCG or Tokens Studio JSON, merge or replace).
- Apply on shapes with edit_page "tokens": {"fill", "stroke", "textColor", "radius", "opacity", "width", "height",
  "gap", "rowGap", "columnGap", "padding", "margin", "minWidth", "maxWidth", "minHeight", "maxHeight",
  "strokeWidth", "rotation", "shadow", "typography", "fontSize", "fontFamily", "fontWeight", "lineHeight",
  "letterSpacing", "textCase", "textDecoration"}. Shapes follow token edits and theme switches automatically.
- Preview a theme without switching it: render_shape {"theme": "Mode/Dark"}.
- Values: references "{color.blue.500}", math "{space.2} * 2", rem (16px). Typography tokens are objects
  {"fontFamily", "fontSize", "fontWeight", "lineHeight", "letterSpacing", "textCase", "textDecoration"}; shadow
  tokens lists of {"offsetX", "offsetY", "blur", "spread", "color" ("#rrggbbaa" for alpha), "inset"}.`,
    prototypes: `Prototypes, export settings, versions, comments
- interactions on any shape: [{"trigger": "click", "action": "navigate", "destination": <top-level board>,
  "animation": {"type": "slide", "direction": "left", "duration": 300}}, {"trigger": "hover", "action":
  "open-overlay", "destination": <board>, "overlay": {"position": "bottom-center", "closeOnClickOutside": true,
  "background": true}}, {"trigger": "after-delay", "delay": 1500, "action": "back"}, {"action": "open-url",
  "url": "https://…"}]. Destinations must exist (create them earlier in the batch).
- flow {"name": "Checkout", "start": <top-level board>} adds a prototype flow; deleteFlow {"name"}.
- exports: [{"type": "svg"}, {"type": "png", "scale": 2, "suffix": "@2x"}] stores export settings; export_assets
  exports them.
- file_versions: create a named version before big changes, restore if needed (confirm with the user).
- comments: list feedback, start a thread on a shape, reply, resolve.`,
};

export class DesignGuideArgs {
    static schema = {
        topic: z
            .enum(["workflow", "components", "vectors", "effects", "text", "layout", "tokens", "prototypes"])
            .default("workflow")
            .describe("What to explain."),
    };
    topic: keyof typeof GUIDES = "workflow";
}

export class DesignGuideTool extends HeadlessTool<DesignGuideArgs> {
    constructor(mcpServer: PenpotMcpServer, engine: HeadlessEngine, credentials: PenpotCredentials) {
        super(mcpServer, DesignGuideArgs.schema, engine, credentials);
    }

    public getToolName(): string {
        return "design_guide";
    }

    public getToolDescription(): string {
        return (
            "Explains how to build with the browser-free tools, with examples: workflow (building a complete design " +
            "system: tokens, library, components, variants, screens, checks), components (variants, copies, swap), " +
            "vectors (SVG, paths, booleans), effects (gradients, shadows, blur), text (rich text), layout (flex, " +
            "grid, constraints), tokens, prototypes (interactions, flows, exports, versions, comments). Read " +
            "workflow before building a design system."
        );
    }

    protected async executeCore(args: DesignGuideArgs): Promise<ToolResponse> {
        return new TextResponse(GUIDES[args.topic ?? "workflow"] ?? GUIDES.workflow);
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
        new MoveShapesTool(mcpServer, engine, credentials),
        new GetTokensTool(mcpServer, engine, credentials),
        new EditTokensTool(mcpServer, engine, credentials),
        new AuditDesignTool(mcpServer, engine, credentials),
        new ExportAssetsTool(mcpServer, engine, credentials),
        new FileVersionsTool(mcpServer, engine, credentials),
        new CommentsTool(mcpServer, engine, credentials),
        new DesignGuideTool(mcpServer, engine, credentials),
    ];
}

/** Extra connection instructions when the browser-free tools are registered. */
export const HEADLESS_INSTRUCTIONS =
    "\n\nBrowser-free tools are also available: list_files, get_page_overview, render_shape, edit_page, " +
    "create_project, create_file, edit_pages, list_fonts, upload_image, upload_font, import_library, link_library, " +
    "get_library, edit_library, move_shapes, get_tokens, edit_tokens, audit_design, export_assets, file_versions, " +
    "comments and design_guide work directly against the Penpot " +
    "backend, without the Penpot MCP plugin and without opening Penpot in a browser. Prefer them when no plugin " +
    "session is connected. Typical flow: list_files → get_page_overview → edit_page → render_shape (only when you " +
    "need to see the result). For a new design: create_project (only if the user wants a new project) → " +
    "create_file with named pages → list_fonts / upload_font / upload_image for the design system's fonts and " +
    "images → import_library or link_library for a .penpot UI kit, get_library to see its components, colors and " +
    "typographies → edit_page using them by name. Design tokens: get_tokens / edit_tokens. To sort boards into " +
    "pages or put them in a library file: move_shapes. To build a design system (tokens, components, variants, " +
    "icons, screens) read design_guide first, and check the result with audit_design.";
