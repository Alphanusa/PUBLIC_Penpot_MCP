import path from "node:path";

/**
 * Settings of the built-in OAuth authorization server (Outline-style connect flow:
 * the AI client opens a login page, the user signs in with the company identity
 * provider, approves access and links their Penpot account once).
 *
 * Enabled when `PENPOT_MCP_OIDC_ISSUER` is set; every other required setting is then
 * checked at startup so a half-configured server fails fast instead of running open.
 */
export interface OAuthConfig {
    /** Public origin of the Penpot instance, e.g. https://design.example.com (no trailing slash). */
    publicUrl: string;
    /** The protected resource: the public MCP endpoint, e.g. https://design.example.com/mcp/stream. */
    resource: string;
    /** Issuer of this authorization server: `${publicUrl}/mcp/oauth`. */
    issuer: string;

    /** Upstream OpenID Connect provider (e.g. Logto) used to sign users in. */
    oidc: {
        issuer: string;
        clientId: string;
        clientSecret: string;
        scopes: string;
        /** When non-empty, the user must have at least one of these roles. */
        roles: string[];
        /** Claim holding the roles; dots address nested claims. */
        rolesAttr: string;
    };

    /** Internal URL of Penpot, used to check the access tokens users link. */
    penpotUrl: string;
    /** Secret for encrypting stored tokens and signing access tokens. */
    secretKey: string;
    /** Directory of the encrypted store. */
    dataDir: string;
    /** Redirect URIs clients may register (see {@link isRedirectUriAllowed}). */
    allowedRedirectUris: string[];

    accessTokenTtlSeconds: number;
    refreshTokenTtlSeconds: number;
}

export const DEFAULT_ALLOWED_REDIRECT_URIS = [
    "https://claude.ai/api/mcp/auth_callback",
    "https://claude.com/api/mcp/auth_callback",
    "http://localhost",
    "http://127.0.0.1",
];

const trimSlash = (url: string) => url.replace(/\/+$/, "");
const list = (value: string | undefined) =>
    (value ?? "")
        .split(/[\s,]+/)
        .map((item) => item.trim())
        .filter(Boolean);

/**
 * Reads the OAuth settings from the environment.
 *
 * @returns null when OAuth is not enabled (`PENPOT_MCP_OIDC_ISSUER` unset)
 * @throws when OAuth is enabled but a required setting is missing or invalid
 */
export function loadOAuthConfig(
    env: Record<string, string | undefined> = process.env,
    cwd: string = process.cwd()
): OAuthConfig | null {
    const oidcIssuer = env.PENPOT_MCP_OIDC_ISSUER?.trim();
    if (!oidcIssuer) return null;

    const missing = [
        "PENPOT_MCP_PUBLIC_URL",
        "PENPOT_MCP_OIDC_CLIENT_ID",
        "PENPOT_MCP_OIDC_CLIENT_SECRET",
        "PENPOT_MCP_SECRET_KEY",
        "PENPOT_URL",
    ].filter((name) => !env[name]?.trim());
    if (missing.length) {
        throw new Error(
            `OAuth is enabled (PENPOT_MCP_OIDC_ISSUER is set) but these are missing: ${missing.join(", ")}`
        );
    }

    const secretKey = env.PENPOT_MCP_SECRET_KEY!.trim();
    if (secretKey.length < 32) {
        throw new Error("PENPOT_MCP_SECRET_KEY must be at least 32 characters long");
    }

    const publicUrl = trimSlash(env.PENPOT_MCP_PUBLIC_URL!.trim());
    const parsed = new URL(publicUrl);
    if (parsed.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(parsed.hostname)) {
        throw new Error("PENPOT_MCP_PUBLIC_URL must use https");
    }

    const allowed = list(env.PENPOT_MCP_OAUTH_REDIRECT_URIS);

    return {
        publicUrl,
        resource: trimSlash(env.PENPOT_MCP_RESOURCE_URL?.trim() || `${publicUrl}/mcp/stream`),
        issuer: `${publicUrl}/mcp/oauth`,
        oidc: {
            issuer: trimSlash(oidcIssuer),
            clientId: env.PENPOT_MCP_OIDC_CLIENT_ID!.trim(),
            clientSecret: env.PENPOT_MCP_OIDC_CLIENT_SECRET!.trim(),
            scopes: env.PENPOT_MCP_OIDC_SCOPES?.trim() || "openid profile email",
            roles: list(env.PENPOT_MCP_OIDC_ROLES),
            rolesAttr: env.PENPOT_MCP_OIDC_ROLES_ATTR?.trim() || "roles",
        },
        penpotUrl: trimSlash(env.PENPOT_URL!.trim()),
        secretKey,
        dataDir: env.PENPOT_MCP_DATA_DIR?.trim() || path.join(cwd, "data"),
        allowedRedirectUris: allowed.length ? allowed : DEFAULT_ALLOWED_REDIRECT_URIS,
        accessTokenTtlSeconds: 60 * 60,
        refreshTokenTtlSeconds: 30 * 24 * 60 * 60,
    };
}

/**
 * Whether a client may register this redirect URI.
 *
 * An allowed entry for `http://localhost` or `http://127.0.0.1` accepts any port and
 * path on that loopback host (native clients such as Claude Code pick a free port).
 * Every other entry must match exactly.
 */
export function isRedirectUriAllowed(candidate: string, allowed: string[]): boolean {
    let url: URL;
    try {
        url = new URL(candidate);
    } catch {
        return false;
    }
    if (url.username || url.password || url.hash) return false;

    return allowed.some((entry) => {
        let allowedUrl: URL;
        try {
            allowedUrl = new URL(entry);
        } catch {
            return false;
        }
        const loopback = ["localhost", "127.0.0.1"].includes(allowedUrl.hostname);
        if (
            loopback &&
            allowedUrl.protocol === "http:" &&
            (allowedUrl.pathname === "/" || allowedUrl.pathname === "")
        ) {
            return url.protocol === "http:" && url.hostname === allowedUrl.hostname;
        }
        return candidate === entry;
    });
}

const isLoopback = (url: URL) => url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname);

/**
 * Whether a redirect URI sent in a request matches one the client registered: exactly,
 * or for loopback redirects with any port (RFC 8252 §7.3; native clients pick a free port).
 */
export function redirectUriMatches(registered: string, requested: string): boolean {
    if (registered === requested) return true;
    try {
        const a = new URL(registered);
        const b = new URL(requested);
        return (
            isLoopback(a) &&
            isLoopback(b) &&
            a.hostname === b.hostname &&
            a.pathname === b.pathname &&
            a.search === b.search &&
            !b.username &&
            !b.password &&
            !b.hash
        );
    } catch {
        return false;
    }
}
