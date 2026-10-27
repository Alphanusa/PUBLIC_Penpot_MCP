import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import type { Express, Request, Response } from "express";
import { createLogger } from "../logger";
import { isRedirectUriAllowed, redirectUriMatches, type OAuthConfig } from "./config";
import { consentPage, messagePage } from "./pages";
import { fetchPenpotProfile } from "./penpot";
import { UpstreamOidc, type UpstreamIdentity } from "./upstream";
import { deriveKey, sha256, Vault, type LinkedUser, type RegisteredClient } from "./vault";

/** The caller of an MCP request, once its bearer token checked out. */
export interface AuthenticatedUser {
    sub: string;
    clientId: string;
    user: LinkedUser;
}

/** An authorization request waiting for the user to sign in and approve. */
interface PendingRequest {
    client: RegisteredClient;
    redirectUri: string;
    state?: string;
    codeChallenge: string;
    scope: string;
    upstreamVerifier: string;
    /** Set once the user signed in at the identity provider. */
    identity?: UpstreamIdentity;
    /** Value of the cookie that ties callback and consent to the browser that started the request. */
    browserKey: string;
    /** Whether the Penpot token already linked to this person still works. */
    linkedTokenValid?: boolean;
    expiresAt: number;
}

interface AuthorizationCode {
    clientId: string;
    redirectUri: string;
    codeChallenge: string;
    sub: string;
    scope: string;
    expiresAt: number;
}

const SCOPE = "penpot";
const PENDING_TTL_MS = 15 * 60 * 1000;
const CODE_TTL_MS = 5 * 60 * 1000;
const MAX_CLIENTS = 500;
const MAX_PENDING = 1000;
/** Clients that never obtained a token are forgotten after this long. */
const UNUSED_CLIENT_TTL_MS = 24 * 60 * 60 * 1000;
const REFRESH_GRACE_MS = 60 * 1000;

/** One cookie per authorization request, so parallel logins in one browser don't collide. */
const browserCookieName = (requestId: string) => `pmcp_${s256(requestId).slice(0, 12)}`;

const random = (bytes = 32) => randomBytes(bytes).toString("base64url");
const s256 = (verifier: string) => createHash("sha256").update(verifier).digest("base64url");

/** Keeps only string parameters (Express parses repeated or bracketed keys into arrays and objects). */
function strings(source: unknown): Record<string, string | undefined> {
    const out: Record<string, string | undefined> = Object.create(null);
    if (source && typeof source === "object") {
        for (const [key, value] of Object.entries(source)) if (typeof value === "string") out[key] = value;
    }
    return out;
}

function decodeOrUndefined(value: string): string | undefined {
    try {
        return decodeURIComponent(value);
    } catch {
        return undefined;
    }
}

function safeEqual(a: string, b: string): boolean {
    const left = Buffer.from(a);
    const right = Buffer.from(b);
    return left.length === right.length && timingSafeEqual(left, right);
}

function pruneExpired<T extends { expiresAt: number }>(map: Map<string, T>): void {
    const now = Date.now();
    for (const [key, value] of map) if (value.expiresAt <= now) map.delete(key);
}

/**
 * OAuth 2.1 authorization server for the MCP endpoint, in the shape MCP clients
 * (claude.ai, Claude Desktop, Claude Code) expect: protected resource metadata,
 * authorization server metadata, dynamic client registration, authorization code
 * with PKCE (S256), and rotating refresh tokens.
 *
 * Signing in is delegated to the company identity provider; on first use the
 * person links their Penpot account by pasting a Penpot access token, which is
 * kept encrypted and used for their browser-free tool calls.
 *
 * Routes (served by this process; the reverse proxy must send them here):
 *   /.well-known/oauth-protected-resource[/mcp/stream]
 *   /.well-known/oauth-authorization-server[/mcp/oauth]
 *   /mcp/oauth/...   (register, authorize, callback, consent, token)
 */
export class AuthServer {
    private readonly logger = createLogger("AuthServer");
    private readonly pending = new Map<string, PendingRequest>();
    private readonly codes = new Map<string, AuthorizationCode>();
    private readonly signingKey: Buffer;
    readonly upstream: UpstreamOidc;
    readonly vault: Vault;

    constructor(
        private readonly config: OAuthConfig,
        private readonly fetchFn: typeof fetch = fetch
    ) {
        this.vault = new Vault(config.dataDir, config.secretKey);
        this.signingKey = deriveKey(config.secretKey, "access-token");
        this.upstream = new UpstreamOidc(config, fetchFn);
    }

    async init(): Promise<void> {
        await this.vault.load();
    }

    /** URL of the protected resource metadata, advertised in 401 responses. */
    get resourceMetadataUrl(): string {
        const resourcePath = new URL(this.config.resource).pathname;
        return `${this.config.publicUrl}/.well-known/oauth-protected-resource${resourcePath}`;
    }

    // ----------------------------------------------------------------- routes

    install(app: Express): void {
        const resourcePath = new URL(this.config.resource).pathname;
        const issuerPath = new URL(this.config.issuer).pathname;

        app.get(
            ["/.well-known/oauth-protected-resource", `/.well-known/oauth-protected-resource${resourcePath}`],
            (_req, res) => {
                res.json({
                    resource: this.config.resource,
                    authorization_servers: [this.config.issuer],
                    scopes_supported: [SCOPE],
                    bearer_methods_supported: ["header"],
                    resource_name: "Penpot",
                });
            }
        );

        app.get(
            [
                "/.well-known/oauth-authorization-server",
                `/.well-known/oauth-authorization-server${issuerPath}`,
                `${issuerPath}/.well-known/oauth-authorization-server`,
                `/.well-known/openid-configuration${issuerPath}`,
                `${issuerPath}/.well-known/openid-configuration`,
            ],
            (_req, res) => {
                res.json({
                    issuer: this.config.issuer,
                    authorization_endpoint: `${this.config.issuer}/authorize`,
                    token_endpoint: `${this.config.issuer}/token`,
                    registration_endpoint: `${this.config.issuer}/register`,
                    response_types_supported: ["code"],
                    response_modes_supported: ["query"],
                    grant_types_supported: ["authorization_code", "refresh_token"],
                    code_challenge_methods_supported: ["S256"],
                    token_endpoint_auth_methods_supported: ["none", "client_secret_post", "client_secret_basic"],
                    scopes_supported: [SCOPE],
                    authorization_response_iss_parameter_supported: true,
                });
            }
        );

        app.post(`${issuerPath}/register`, (req, res) => this.register(req, res));
        app.get(`${issuerPath}/authorize`, (req, res) => this.authorize(req, res));
        app.get(`${issuerPath}/callback`, (req, res) => this.callback(req, res));
        app.post(`${issuerPath}/consent`, (req, res) => this.consent(req, res));
        app.post(`${issuerPath}/token`, (req, res) => this.token(req, res));
    }

    // ------------------------------------------------------------ registration

    private async register(req: Request, res: Response): Promise<void> {
        const body = req.body ?? {};
        const redirectUris: unknown = body.redirect_uris;
        if (
            !Array.isArray(redirectUris) ||
            redirectUris.length === 0 ||
            redirectUris.some((u) => typeof u !== "string")
        ) {
            this.oauthError(res, 400, "invalid_redirect_uri", "redirect_uris is required");
            return;
        }
        const rejected = redirectUris.filter((uri) => !isRedirectUriAllowed(uri, this.config.allowedRedirectUris));
        if (rejected.length) {
            this.logger.warn(`Rejected client registration with redirect URIs ${rejected.join(", ")}`);
            this.oauthError(
                res,
                400,
                "invalid_redirect_uri",
                `redirect URI not allowed on this server: ${rejected.join(", ")}`
            );
            return;
        }
        const method = body.token_endpoint_auth_method ?? "client_secret_basic";
        if (!["none", "client_secret_post", "client_secret_basic"].includes(method)) {
            this.oauthError(res, 400, "invalid_client_metadata", `unsupported token_endpoint_auth_method ${method}`);
            return;
        }
        if (this.vault.clientCount() >= MAX_CLIENTS) await this.vault.pruneUnusedClients(UNUSED_CLIENT_TTL_MS);
        if (this.vault.clientCount() >= MAX_CLIENTS) {
            this.oauthError(res, 400, "invalid_client_metadata", "too many registered clients");
            return;
        }

        const clientId = `pmcp-${random(16)}`;
        const clientSecret = method === "none" ? undefined : random();
        const client: RegisteredClient = {
            clientId,
            clientSecretHash: clientSecret ? sha256(clientSecret) : undefined,
            clientName: typeof body.client_name === "string" ? body.client_name.slice(0, 100) : undefined,
            redirectUris,
            tokenEndpointAuthMethod: method,
            createdAt: Math.floor(Date.now() / 1000),
        };
        await this.vault.saveClient(client);
        this.logger.info(`Registered OAuth client ${clientId} (${client.clientName ?? "unnamed"})`);

        res.status(201).json({
            client_id: clientId,
            ...(clientSecret ? { client_secret: clientSecret, client_secret_expires_at: 0 } : {}),
            client_id_issued_at: client.createdAt,
            client_name: client.clientName,
            redirect_uris: redirectUris,
            token_endpoint_auth_method: method,
            grant_types: ["authorization_code", "refresh_token"],
            response_types: ["code"],
            scope: SCOPE,
        });
    }

    // ----------------------------------------------------------- authorization

    private async authorize(req: Request, res: Response): Promise<void> {
        const q = strings(req.query);
        const client = q.client_id ? this.vault.getClient(q.client_id) : undefined;
        if (
            !client ||
            !q.redirect_uri ||
            !client.redirectUris.some((uri) => redirectUriMatches(uri, q.redirect_uri!))
        ) {
            // never redirect to an unverified URI
            this.html(
                res,
                400,
                messagePage("Permintaan tidak valid", "Aplikasi atau alamat kembalinya tidak dikenal.")
            );
            return;
        }
        const back = (error: string, description: string) =>
            this.redirectBack(res, q.redirect_uri!, { error, error_description: description, state: q.state });

        if (q.response_type !== "code") return back("unsupported_response_type", "only response_type=code");
        if (!q.code_challenge || q.code_challenge_method !== "S256") {
            return back("invalid_request", "PKCE with code_challenge_method=S256 is required");
        }
        if (q.resource && q.resource.replace(/\/+$/, "") !== this.config.resource) {
            return back("invalid_target", `unknown resource ${q.resource}`);
        }

        pruneExpired(this.pending);
        if (this.pending.size >= MAX_PENDING) {
            this.html(
                res,
                503,
                messagePage("Sedang sibuk", "Terlalu banyak login yang belum selesai. Coba lagi nanti.")
            );
            return;
        }
        const requestId = random();
        const upstreamVerifier = random();
        const browserKey = random();
        this.pending.set(requestId, {
            client,
            redirectUri: q.redirect_uri,
            state: q.state,
            codeChallenge: q.code_challenge,
            scope: SCOPE,
            upstreamVerifier,
            browserKey,
            expiresAt: Date.now() + PENDING_TTL_MS,
        });
        // Binds the rest of the flow to this browser: a login link forwarded to someone
        // else (whose browser lacks the cookie) cannot be completed with their account.
        // Lax, not Strict: the callback arrives as a cross-site redirect from the identity provider.
        res.cookie(browserCookieName(requestId), browserKey, {
            httpOnly: true,
            secure: this.config.publicUrl.startsWith("https:"),
            sameSite: "lax",
            path: new URL(this.config.issuer).pathname,
            maxAge: PENDING_TTL_MS,
        });

        try {
            res.redirect(302, await this.upstream.authorizationUrl(requestId, s256(upstreamVerifier), random(16)));
        } catch (error) {
            this.logger.error(error, "Cannot start the login at the identity provider");
            this.html(
                res,
                502,
                messagePage("Login tidak tersedia", "Penyedia login tidak bisa dihubungi. Coba lagi nanti.")
            );
        }
    }

    private async callback(req: Request, res: Response): Promise<void> {
        const q = strings(req.query);
        const pending = q.state ? this.pending.get(q.state) : undefined;
        if (
            !pending ||
            pending.expiresAt <= Date.now() ||
            pending.identity ||
            !this.sameBrowser(req, q.state!, pending)
        ) {
            this.html(res, 400, messagePage("Sesi login kedaluwarsa", "Ulangi menyambungkan dari aplikasi AI kamu."));
            return;
        }
        if (q.error || !q.code) {
            this.pending.delete(q.state!);
            this.redirectBack(res, pending.redirectUri, {
                error: "access_denied",
                error_description: q.error_description ?? q.error ?? "login cancelled",
                state: pending.state,
            });
            return;
        }

        let identity: UpstreamIdentity;
        try {
            identity = await this.upstream.identify(q.code, pending.upstreamVerifier);
        } catch (error) {
            this.pending.delete(q.state!);
            this.logger.error(error, "Login at the identity provider failed");
            this.html(
                res,
                502,
                messagePage("Login gagal", "Login tidak bisa diselesaikan. Ulangi dari aplikasi AI kamu.")
            );
            return;
        }
        if (!this.upstream.isAllowed(identity)) {
            this.pending.delete(q.state!);
            this.logger.warn(`Denied ${identity.email}: none of the required roles`);
            this.html(
                res,
                403,
                messagePage("Akses ditolak", `Akun ${identity.email} tidak punya izin memakai Penpot lewat AI.`)
            );
            return;
        }

        pending.identity = identity;
        await this.showConsent(res, q.state!, pending);
    }

    private sameBrowser(req: Request, requestId: string, pending: PendingRequest): boolean {
        const cookie = readCookie(req, browserCookieName(requestId));
        return !!cookie && safeEqual(cookie, pending.browserKey);
    }

    private async showConsent(res: Response, requestId: string, pending: PendingRequest, error?: string) {
        const linked = this.vault.getUser(pending.identity!.sub);
        let linkedPenpotEmail: string | undefined;
        if (linked) {
            try {
                linkedPenpotEmail = (await fetchPenpotProfile(this.config.penpotUrl, linked.penpotToken, this.fetchFn))
                    ?.email;
            } catch (cause) {
                this.logger.warn(`Cannot check the stored Penpot token: ${cause}`);
                linkedPenpotEmail = linked.penpotEmail; // Penpot unreachable: keep it, tools will report
            }
        }
        pending.linkedTokenValid = !!linkedPenpotEmail;
        this.html(
            res,
            error ? 400 : 200,
            consentPage({
                requestId,
                clientName: pending.client.clientName ?? "Aplikasi AI",
                redirectHost: new URL(pending.redirectUri).host,
                userEmail: pending.identity!.email,
                linkedPenpotEmail,
                hasMcpKey: !!linked?.mcpKey,
                error,
            })
        );
    }

    private async consent(req: Request, res: Response): Promise<void> {
        const body = strings(req.body);
        const requestId = body.request ?? "";
        const pending = this.pending.get(requestId);
        if (!pending?.identity || pending.expiresAt <= Date.now() || !this.sameBrowser(req, requestId, pending)) {
            this.html(res, 400, messagePage("Sesi login kedaluwarsa", "Ulangi menyambungkan dari aplikasi AI kamu."));
            return;
        }
        const identity = pending.identity;

        if (body.decision !== "allow") {
            this.pending.delete(requestId);
            this.redirectBack(res, pending.redirectUri, {
                error: "access_denied",
                error_description: "the user denied access",
                state: pending.state,
            });
            return;
        }

        const existing = this.vault.getUser(identity.sub);
        const newToken = body.penpot_token?.trim();
        const newMcpKey = body.mcp_key?.trim();
        let user = existing;

        if (newToken || !existing || !pending.linkedTokenValid) {
            if (!newToken) return this.showConsent(res, requestId, pending, "Isi access token Penpot dulu.");
            let profile;
            try {
                profile = await fetchPenpotProfile(this.config.penpotUrl, newToken, this.fetchFn);
            } catch (cause) {
                this.logger.error(cause, "Cannot reach Penpot to check a token");
                return this.showConsent(res, requestId, pending, "Penpot tidak bisa dihubungi. Coba lagi sebentar.");
            }
            if (!profile) {
                return this.showConsent(res, requestId, pending, "Token tidak dikenali Penpot. Periksa lagi tokennya.");
            }
            if (profile.email.toLowerCase() !== identity.email.toLowerCase()) {
                return this.showConsent(
                    res,
                    requestId,
                    pending,
                    `Token itu milik akun Penpot ${profile.email}, bukan ${identity.email}. Pakai token dari akun Penpot kamu sendiri.`
                );
            }
            user = {
                email: identity.email,
                name: identity.name,
                penpotToken: newToken,
                penpotEmail: profile.email,
                mcpKey: newMcpKey || existing?.mcpKey,
                updatedAt: new Date().toISOString(),
            };
        } else if (newMcpKey) {
            user = { ...existing, mcpKey: newMcpKey, updatedAt: new Date().toISOString() };
        }
        if (user !== existing) await this.vault.saveUser(identity.sub, user!);

        this.pending.delete(requestId);
        res.clearCookie(browserCookieName(requestId), { path: new URL(this.config.issuer).pathname });
        pruneExpired(this.codes);
        const code = random();
        this.codes.set(code, {
            clientId: pending.client.clientId,
            redirectUri: pending.redirectUri,
            codeChallenge: pending.codeChallenge,
            sub: identity.sub,
            scope: pending.scope,
            expiresAt: Date.now() + CODE_TTL_MS,
        });
        this.logger.info(`${identity.email} approved access for client ${pending.client.clientId}`);
        this.redirectBack(res, pending.redirectUri, { code, state: pending.state });
    }

    // ------------------------------------------------------------------- token

    private async token(req: Request, res: Response): Promise<void> {
        res.setHeader("Cache-Control", "no-store");
        const body = strings(req.body);
        const client = this.authenticateClient(req, body);
        if (!client) {
            this.oauthError(res, 401, "invalid_client", "client authentication failed");
            return;
        }

        if (body.grant_type === "authorization_code") {
            const code = body.code ? this.codes.get(body.code) : undefined;
            if (body.code) this.codes.delete(body.code); // single use, even when the checks below fail
            if (
                !code ||
                code.expiresAt <= Date.now() ||
                code.clientId !== client.clientId ||
                !body.redirect_uri ||
                !redirectUriMatches(code.redirectUri, body.redirect_uri) ||
                !body.code_verifier ||
                !safeEqual(s256(body.code_verifier), code.codeChallenge)
            ) {
                this.oauthError(res, 400, "invalid_grant", "invalid, expired or already used authorization code");
                return;
            }
            if (!this.vault.getUser(code.sub)) {
                this.oauthError(res, 400, "invalid_grant", "the account is no longer linked");
                return;
            }
            await this.issueTokens(res, code.sub, client.clientId, code.scope, Date.now());
            return;
        }

        if (body.grant_type === "refresh_token") {
            const grant = body.refresh_token ? this.vault.getRefreshGrant(body.refresh_token) : undefined;
            const loginExpired = !grant || Date.now() - grant.authTime > this.config.refreshTokenTtlSeconds * 1000;
            if (!grant || loginExpired || grant.clientId !== client.clientId || !this.vault.getUser(grant.sub)) {
                this.oauthError(res, 400, "invalid_grant", "invalid or expired refresh token, sign in again");
                return;
            }
            await this.issueTokens(res, grant.sub, client.clientId, grant.scope, grant.authTime, body.refresh_token);
            return;
        }

        this.oauthError(res, 400, "unsupported_grant_type", `unsupported grant_type ${body.grant_type ?? ""}`);
    }

    private authenticateClient(req: Request, body: Record<string, string | undefined>): RegisteredClient | undefined {
        let clientId = body.client_id;
        let secret = body.client_secret;
        const header = req.headers.authorization;
        if (header?.startsWith("Basic ")) {
            const decoded = Buffer.from(header.slice(6), "base64").toString("utf8");
            const separator = decoded.indexOf(":");
            if (separator > 0) {
                clientId = decodeOrUndefined(decoded.slice(0, separator));
                secret = decodeOrUndefined(decoded.slice(separator + 1));
            }
        }
        const client = clientId ? this.vault.getClient(clientId) : undefined;
        if (!client) return undefined;
        if (client.tokenEndpointAuthMethod === "none") return client;
        return secret && client.clientSecretHash && safeEqual(sha256(secret), client.clientSecretHash)
            ? client
            : undefined;
    }

    /**
     * Issues an access token and a rotated refresh token. The login itself (`authTime`)
     * expires after the refresh token lifetime however often the client refreshes, so
     * people sign in with the identity provider again at least every 30 days (and a
     * removed role then takes effect).
     */
    private async issueTokens(
        res: Response,
        sub: string,
        clientId: string,
        scope: string,
        authTime: number,
        previousRefresh?: string
    ) {
        const expiresAt = Math.floor(Date.now() / 1000) + this.config.accessTokenTtlSeconds;
        const accessToken = this.signAccessToken({ sub, cid: clientId, exp: expiresAt });
        const refreshToken = random();
        await this.vault.putRefreshGrant(
            refreshToken,
            { sub, clientId, scope, authTime, expiresAt: authTime + this.config.refreshTokenTtlSeconds * 1000 },
            previousRefresh,
            REFRESH_GRACE_MS
        );
        res.json({
            access_token: accessToken,
            token_type: "Bearer",
            expires_in: this.config.accessTokenTtlSeconds,
            refresh_token: refreshToken,
            scope,
        });
    }

    // ---------------------------------------------------------- access tokens

    /** Access tokens are signed, so they survive restarts without being stored. */
    signAccessToken(claims: { sub: string; cid: string; exp: number }): string {
        const payload = Buffer.from(JSON.stringify({ ...claims, aud: this.config.resource })).toString("base64url");
        const signature = createHmac("sha256", this.signingKey).update(payload).digest("base64url");
        return `pmcp.${payload}.${signature}`;
    }

    /**
     * Checks the bearer token of an MCP request.
     *
     * @returns the caller, or undefined after answering 401 with the metadata
     *     pointer that starts the client's login flow
     */
    authenticate(req: Request, res: Response): AuthenticatedUser | undefined {
        const header = req.headers.authorization;
        const token = header?.match(/^Bearer\s+(.+)$/i)?.[1];
        const caller = token ? this.verifyAccessToken(token) : undefined;
        if (caller) return caller;

        const params = [`resource_metadata="${this.resourceMetadataUrl}"`, `scope="${SCOPE}"`];
        if (token) params.unshift('error="invalid_token"');
        res.setHeader("WWW-Authenticate", `Bearer ${params.join(", ")}`);
        res.status(401).json({
            jsonrpc: "2.0",
            error: { code: -32001, message: "Authentication required: connect this server from your AI client." },
            id: null,
        });
        return undefined;
    }

    private verifyAccessToken(token: string): AuthenticatedUser | undefined {
        const [prefix, payload, signature] = token.split(".");
        if (prefix !== "pmcp" || !payload || !signature) return undefined;
        const expected = createHmac("sha256", this.signingKey).update(payload).digest("base64url");
        if (!safeEqual(signature, expected)) return undefined;
        let claims: { sub: string; cid: string; exp: number; aud: string };
        try {
            claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
        } catch {
            return undefined;
        }
        if (claims.aud !== this.config.resource || claims.exp * 1000 <= Date.now()) return undefined;
        if (!this.vault.getClient(claims.cid)) return undefined;
        const user = this.vault.getUser(claims.sub);
        return user ? { sub: claims.sub, clientId: claims.cid, user } : undefined;
    }

    // ----------------------------------------------------------------- helpers

    private redirectBack(res: Response, redirectUri: string, params: Record<string, string | undefined>): void {
        const url = new URL(redirectUri);
        for (const [key, value] of Object.entries({ ...params, iss: this.config.issuer })) {
            if (value !== undefined) url.searchParams.set(key, value);
        }
        res.redirect(302, url.toString());
    }

    private oauthError(res: Response, status: number, error: string, description: string): void {
        res.status(status).json({ error, error_description: description });
    }

    private html(res: Response, status: number, body: string): void {
        res.status(status)
            .set({
                "Content-Type": "text/html; charset=utf-8",
                "Cache-Control": "no-store",
                "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",
                "X-Frame-Options": "DENY",
                "Referrer-Policy": "no-referrer",
            })
            .send(body);
    }
}

function readCookie(req: Request, name: string): string | undefined {
    for (const part of (req.headers.cookie ?? "").split(";")) {
        const [key, ...rest] = part.trim().split("=");
        if (key === name) return decodeOrUndefined(rest.join("="));
    }
    return undefined;
}
