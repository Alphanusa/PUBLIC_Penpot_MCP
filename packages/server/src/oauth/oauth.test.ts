import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import express from "express";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { isRedirectUriAllowed, loadOAuthConfig, DEFAULT_ALLOWED_REDIRECT_URIS } from "./config";
import { readRoles } from "./upstream";
import { Vault } from "./vault";

const SECRET = "x".repeat(40);
const s256 = (v: string) => createHash("sha256").update(v).digest("base64url");

// ------------------------------------------------------------------ units

describe("redirect URI allowlist", () => {
    test("accepts the Claude callbacks and any loopback port", () => {
        for (const uri of [
            "https://claude.ai/api/mcp/auth_callback",
            "https://claude.com/api/mcp/auth_callback",
            "http://localhost:53682/callback",
            "http://127.0.0.1:9000/",
        ]) {
            assert.ok(isRedirectUriAllowed(uri, DEFAULT_ALLOWED_REDIRECT_URIS), uri);
        }
    });

    test("rejects look-alikes", () => {
        for (const uri of [
            "https://claude.ai/api/mcp/auth_callback/../../evil",
            "https://evil.example/api/mcp/auth_callback",
            "http://localhost:80@evil.example/",
            "https://localhost:443/callback",
            "http://localhost.evil.example/",
            "https://claude.ai/api/mcp/auth_callback#frag",
            "not a url",
        ]) {
            assert.ok(!isRedirectUriAllowed(uri, DEFAULT_ALLOWED_REDIRECT_URIS), uri);
        }
    });
});

test("roles are read from arrays, strings and nested claims", () => {
    assert.deepEqual(readRoles({ roles: ["a", "b"] }, "roles"), ["a", "b"]);
    assert.deepEqual(readRoles({ roles: "a b" }, "roles"), ["a", "b"]);
    assert.deepEqual(readRoles({ org: { roles: ["x"] } }, "org.roles"), ["x"]);
    assert.deepEqual(readRoles({ organization_roles: ["o1:Developer"] }, "organization_roles"), ["o1:Developer"]);
    assert.deepEqual(readRoles({}, "roles"), []);
});

test("config fails fast when OAuth is half configured", () => {
    assert.equal(loadOAuthConfig({}), null);
    assert.throws(() => loadOAuthConfig({ PENPOT_MCP_OIDC_ISSUER: "https://id.example/oidc" }), /missing/);
    assert.throws(
        () =>
            loadOAuthConfig({
                PENPOT_MCP_OIDC_ISSUER: "https://id.example/oidc",
                PENPOT_MCP_PUBLIC_URL: "https://design.example",
                PENPOT_MCP_OIDC_CLIENT_ID: "c",
                PENPOT_MCP_OIDC_CLIENT_SECRET: "s",
                PENPOT_MCP_SECRET_KEY: "short",
                PENPOT_URL: "http://penpot",
            }),
        /at least 32/
    );
});

test("the vault is encrypted and refuses a different key", async () => {
    const dir = mkdtempSync(path.join(tmpdir(), "vault-"));
    try {
        const vault = new Vault(dir, SECRET);
        await vault.load();
        await vault.saveUser("u1", {
            email: "a@example.com",
            penpotToken: "secret-penpot-token",
            penpotEmail: "a@example.com",
            updatedAt: "now",
        });
        const raw = (await import("node:fs")).readFileSync(path.join(dir, "oauth-vault.bin"));
        assert.ok(!raw.toString("latin1").includes("secret-penpot-token"));

        const again = new Vault(dir, SECRET);
        await again.load();
        assert.equal(again.getUser("u1")?.penpotToken, "secret-penpot-token");

        await assert.rejects(new Vault(dir, "y".repeat(40)).load(), /PENPOT_MCP_SECRET_KEY/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("a read-only data directory is reported at startup", { skip: process.getuid?.() === 0 }, async () => {
    const dir = mkdtempSync(path.join(tmpdir(), "vault-"));
    const fs = await import("node:fs");
    try {
        fs.chmodSync(dir, 0o500);
        await assert.rejects(new Vault(dir, SECRET).load(), /not writable/);
    } finally {
        fs.chmodSync(dir, 0o700);
        rmSync(dir, { recursive: true, force: true });
    }
});

test("one failed vault write does not block the following ones", async () => {
    const dir = mkdtempSync(path.join(tmpdir(), "vault-"));
    const fs = await import("node:fs");
    try {
        const vault = new Vault(dir, SECRET);
        await vault.load();
        const user = { email: "a@example.com", penpotToken: "t", penpotEmail: "a@example.com", updatedAt: "now" };
        const blocker = path.join(dir, `oauth-vault.bin.${process.pid}.tmp`);
        fs.mkdirSync(blocker); // the temp file cannot be written: EISDIR
        await assert.rejects(vault.saveUser("u1", user));
        fs.rmdirSync(blocker);
        await vault.saveUser("u2", user);

        const reloaded = new Vault(dir, SECRET);
        await reloaded.load();
        assert.ok(reloaded.getUser("u1") && reloaded.getUser("u2"), "the next write carries everything");
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

// ------------------------------------------------------------ end to end

/** Fake identity provider (Logto) and fake Penpot backend in one express app. */
function fakeUpstreams(state: { email: string; roles: string[]; penpotEmail: string }) {
    const app = express();
    app.use(express.urlencoded({ extended: false }));
    const codes = new Map<string, string>(); // code -> code_challenge
    let base = "";

    app.get("/oidc/.well-known/openid-configuration", (_req, res) => {
        res.json({
            authorization_endpoint: `${base}/oidc/auth`,
            token_endpoint: `${base}/oidc/token`,
            userinfo_endpoint: `${base}/oidc/me`,
        });
    });
    // Signs the user in at once and sends them back to the MCP server's callback.
    app.get("/oidc/auth", (req, res) => {
        const code = randomBytes(8).toString("hex");
        codes.set(code, String(req.query.code_challenge));
        const back = new URL(String(req.query.redirect_uri));
        back.searchParams.set("code", code);
        back.searchParams.set("state", String(req.query.state));
        res.redirect(back.toString());
    });
    app.post("/oidc/token", (req, res) => {
        const auth = Buffer.from(String(req.headers.authorization).slice(6), "base64").toString();
        const challenge = codes.get(req.body.code);
        if (auth !== "logto-client:logto-secret" || !challenge || s256(req.body.code_verifier) !== challenge) {
            res.status(400).json({ error: "invalid_grant" });
            return;
        }
        res.json({ access_token: "upstream-at", token_type: "Bearer" });
    });
    app.get("/oidc/me", (req, res) => {
        if (req.headers.authorization !== "Bearer upstream-at") return void res.status(401).end();
        res.json({ sub: "user-1", email: state.email, name: "Ana", roles: state.roles });
    });
    // Penpot: the valid token is "good-token"; anything else is the anonymous profile.
    app.post("/api/rpc/command/get-profile", (req, res) => {
        const ok = req.headers.authorization === "Token good-token";
        res.json(
            ok
                ? { id: "11111111-1111-1111-1111-111111111111", email: state.penpotEmail, fullname: "Ana" }
                : { id: "00000000-0000-0000-0000-000000000000", email: null }
        );
    });

    return new Promise<{ server: Server; base: string }>((resolve) => {
        const server = app.listen(0, "127.0.0.1", () => {
            base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
            resolve({ server, base });
        });
    });
}

describe("OAuth connect flow", () => {
    let upstream: { server: Server; base: string };
    let mcp: PenpotMcpServer;
    let mcpBase: string;
    let dataDir: string;
    let previousEnv: NodeJS.ProcessEnv;
    const state = { email: "ana@example.com", roles: ["org1:Developer"], penpotEmail: "ana@example.com" };
    let nextPort = 16_700;

    beforeEach(async () => {
        Object.assign(state, { email: "ana@example.com", roles: ["org1:Developer"], penpotEmail: "ana@example.com" });
        upstream = await fakeUpstreams(state);
        dataDir = mkdtempSync(path.join(tmpdir(), "oauth-"));
        previousEnv = { ...process.env };
        const port = nextPort++;
        mcpBase = `http://127.0.0.1:${port}`;
        Object.assign(process.env, {
            PENPOT_MCP_SERVER_HOST: "127.0.0.1",
            PENPOT_MCP_SERVER_PORT: String(port),
            PENPOT_MCP_WEBSOCKET_PORT: "0",
            PENPOT_MCP_DEVENV: "false",
            PENPOT_MCP_REPL_ENABLE: "false",
            PENPOT_MCP_HEADLESS: "false",
            PENPOT_MCP_PUBLIC_URL: mcpBase,
            PENPOT_MCP_RESOURCE_URL: `${mcpBase}/mcp`,
            PENPOT_MCP_OIDC_ISSUER: `${upstream.base}/oidc`,
            PENPOT_MCP_OIDC_CLIENT_ID: "logto-client",
            PENPOT_MCP_OIDC_CLIENT_SECRET: "logto-secret",
            PENPOT_MCP_OIDC_ROLES: "org1:Developer",
            PENPOT_MCP_OIDC_ROLES_ATTR: "roles",
            PENPOT_MCP_SECRET_KEY: SECRET,
            PENPOT_MCP_DATA_DIR: dataDir,
            PENPOT_URL: upstream.base,
        });
        delete process.env.PENPOT_MCP_REDIS_URI;
        mcp = new PenpotMcpServer(true);
        await mcp.start();
    });

    afterEach(async () => {
        await mcp?.stop();
        upstream?.server.close();
        rmSync(dataDir, { recursive: true, force: true });
        process.env = previousEnv;
    });

    const REDIRECT = "http://localhost:33418/callback";

    async function registerClient(method = "none") {
        const res = await fetch(`${mcpBase}/mcp/oauth/register`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                client_name: "Claude",
                redirect_uris: [REDIRECT],
                token_endpoint_auth_method: method,
            }),
        });
        assert.equal(res.status, 201);
        return (await res.json()) as { client_id: string; client_secret?: string };
    }

    /** Walks authorize → identity provider → callback; returns the consent page and its cookie. */
    async function startAuthorization(clientId: string, verifier: string, redirectUri = REDIRECT) {
        const authorize = new URL(`${mcpBase}/mcp/oauth/authorize`);
        authorize.search = new URLSearchParams({
            response_type: "code",
            client_id: clientId,
            redirect_uri: redirectUri,
            code_challenge: s256(verifier),
            code_challenge_method: "S256",
            state: "client-state",
            scope: "penpot",
            resource: `${mcpBase}/mcp`,
        }).toString();
        const toIdp = await fetch(authorize, { redirect: "manual" });
        assert.equal(toIdp.status, 302);
        const cookie = toIdp.headers.get("set-cookie")?.split(";")[0];
        const toCallback = await fetch(toIdp.headers.get("location")!, { redirect: "manual" });
        const callback = await fetch(toCallback.headers.get("location")!, {
            redirect: "manual",
            headers: cookie ? { Cookie: cookie } : {},
        });
        const html = await callback.text();
        const request = html.match(/name="request" value="([^"]+)"/)?.[1];
        return { status: callback.status, html, cookie, request };
    }

    async function submitConsent(request: string, cookie: string, fields: Record<string, string>) {
        return fetch(`${mcpBase}/mcp/oauth/consent`, {
            method: "POST",
            redirect: "manual",
            headers: { "Content-Type": "application/x-www-form-urlencoded", Cookie: cookie },
            body: new URLSearchParams({ request, ...fields }).toString(),
        });
    }

    async function exchange(params: Record<string, string>) {
        return fetch(`${mcpBase}/mcp/oauth/token`, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams(params).toString(),
        });
    }

    function listTools(accessToken?: string) {
        return fetch(`${mcpBase}/mcp`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json, text/event-stream",
                "MCP-Protocol-Version": "2026-07-28",
                "Mcp-Method": "tools/list",
                ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
            },
            body: JSON.stringify({
                jsonrpc: "2.0",
                id: 1,
                method: "tools/list",
                params: {
                    _meta: {
                        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                        "io.modelcontextprotocol/clientInfo": { name: "t", version: "1" },
                        "io.modelcontextprotocol/clientCapabilities": {},
                    },
                },
            }),
        });
    }

    test("unauthenticated MCP requests get a 401 that points at the metadata", async () => {
        const res = await listTools();
        assert.equal(res.status, 401);
        const header = res.headers.get("www-authenticate")!;
        assert.match(
            header,
            /resource_metadata="http:\/\/127\.0\.0\.1:\d+\/\.well-known\/oauth-protected-resource\/mcp"/
        );

        const prm: any = await (await fetch(`${mcpBase}/.well-known/oauth-protected-resource/mcp`)).json();
        assert.equal(prm.resource, `${mcpBase}/mcp`);
        assert.deepEqual(prm.authorization_servers, [`${mcpBase}/mcp/oauth`]);

        const asm: any = await (await fetch(`${mcpBase}/.well-known/oauth-authorization-server/mcp/oauth`)).json();
        assert.equal(asm.issuer, `${mcpBase}/mcp/oauth`);
        assert.deepEqual(asm.code_challenge_methods_supported, ["S256"]);

        const forged = await listTools("pmcp.e30.forged");
        assert.equal(forged.status, 401);
        assert.match(forged.headers.get("www-authenticate")!, /invalid_token/);
    });

    test("full flow: register, sign in, link Penpot, approve, call tools, refresh", async () => {
        const client = await registerClient();
        const verifier = randomBytes(32).toString("base64url");
        const consent = await startAuthorization(client.client_id, verifier);
        assert.equal(consent.status, 200);
        assert.match(consent.html, /ana@example\.com/);
        assert.match(consent.html, /name="penpot_token"[^>]*required/);

        const wrong = await submitConsent(consent.request!, consent.cookie!, {
            decision: "allow",
            penpot_token: "bad",
        });
        assert.equal(wrong.status, 400);
        assert.match(await wrong.text(), /Token tidak dikenali/);

        const approved = await submitConsent(consent.request!, consent.cookie!, {
            decision: "allow",
            penpot_token: "good-token",
            mcp_key: "plugin-key",
        });
        assert.equal(approved.status, 302);
        const back = new URL(approved.headers.get("location")!);
        assert.equal(`${back.origin}${back.pathname}`, REDIRECT);
        assert.equal(back.searchParams.get("state"), "client-state");
        assert.equal(back.searchParams.get("iss"), `${mcpBase}/mcp/oauth`);
        const code = back.searchParams.get("code")!;

        const badPkce = await exchange({
            grant_type: "authorization_code",
            code,
            redirect_uri: REDIRECT,
            client_id: client.client_id,
            code_verifier: "wrong",
        });
        assert.equal(badPkce.status, 400);

        // the code was burnt by the failed attempt: start again
        const second = await startAuthorization(client.client_id, verifier);
        assert.doesNotMatch(second.html, /name="penpot_token"[^>]*required/, "linked account is remembered");
        const again = await submitConsent(second.request!, second.cookie!, { decision: "allow" });
        const code2 = new URL(again.headers.get("location")!).searchParams.get("code")!;
        const tokenRes = await exchange({
            grant_type: "authorization_code",
            code: code2,
            redirect_uri: REDIRECT,
            client_id: client.client_id,
            code_verifier: verifier,
        });
        assert.equal(tokenRes.status, 200);
        const tokens = (await tokenRes.json()) as { access_token: string; refresh_token: string; expires_in: number };
        assert.equal(tokens.expires_in, 3600);

        const reused = await exchange({
            grant_type: "authorization_code",
            code: code2,
            redirect_uri: REDIRECT,
            client_id: client.client_id,
            code_verifier: verifier,
        });
        assert.equal(reused.status, 400, "codes are single use");

        const ok = await listTools(tokens.access_token);
        assert.equal(ok.status, 200);

        // the request context carries the user's own credentials
        let seen: unknown;
        const original = mcp.pluginBridge.executePluginTask.bind(mcp.pluginBridge);
        (mcp.pluginBridge as any).executePluginTask = async () => {
            seen = mcp.getSessionContext();
            return { data: 1 };
        };
        try {
            const call = await fetch(`${mcpBase}/mcp`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    Accept: "application/json, text/event-stream",
                    "MCP-Protocol-Version": "2026-07-28",
                    "Mcp-Method": "tools/call",
                    "Mcp-Name": "execute_code",
                    Authorization: `Bearer ${tokens.access_token}`,
                },
                body: JSON.stringify({
                    jsonrpc: "2.0",
                    id: 2,
                    method: "tools/call",
                    params: {
                        name: "execute_code",
                        arguments: { code: "return 1;" },
                        _meta: {
                            "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                            "io.modelcontextprotocol/clientInfo": { name: "t", version: "1" },
                            "io.modelcontextprotocol/clientCapabilities": {},
                        },
                    },
                }),
            });
            assert.equal(call.status, 200);
            assert.deepEqual(seen, { userToken: "plugin-key", penpotToken: "good-token" });
        } finally {
            (mcp.pluginBridge as any).executePluginTask = original;
        }

        const refreshed = await exchange({
            grant_type: "refresh_token",
            refresh_token: tokens.refresh_token,
            client_id: client.client_id,
        });
        assert.equal(refreshed.status, 200);
        const retried = await exchange({
            grant_type: "refresh_token",
            refresh_token: tokens.refresh_token,
            client_id: client.client_id,
        });
        assert.equal(retried.status, 200, "the previous refresh token survives a short grace period");

        const later = Date.now() + 61_000;
        const realNow = Date.now;
        Date.now = () => later;
        try {
            const rotated = await exchange({
                grant_type: "refresh_token",
                refresh_token: tokens.refresh_token,
                client_id: client.client_id,
            });
            assert.equal(rotated.status, 400, "refresh tokens rotate");

            const fresh = ((await refreshed.json()) as any).refresh_token;
            Date.now = () => realNow() + 31 * 24 * 60 * 60 * 1000;
            const tooOld = await exchange({
                grant_type: "refresh_token",
                refresh_token: fresh,
                client_id: client.client_id,
            });
            assert.equal(tooOld.status, 400, "the login expires after 30 days however often it is refreshed");
        } finally {
            Date.now = realNow;
        }
    });

    test("a confidential client must authenticate at the token endpoint", async () => {
        const client = await registerClient("client_secret_post");
        assert.ok(client.client_secret);
        const res = await exchange({
            grant_type: "refresh_token",
            refresh_token: "x",
            client_id: client.client_id,
            client_secret: "wrong",
        });
        assert.equal(res.status, 401);
    });

    test("users without the role are refused before linking anything", async () => {
        state.roles = ["org1:Viewer"];
        const client = await registerClient();
        const consent = await startAuthorization(client.client_id, "v".repeat(43));
        assert.equal(consent.status, 403);
        assert.match(consent.html, /Akses ditolak/);
    });

    test("a Penpot token of another account is refused", async () => {
        state.penpotEmail = "someone-else@example.com";
        const client = await registerClient();
        const consent = await startAuthorization(client.client_id, "v".repeat(43));
        const res = await submitConsent(consent.request!, consent.cookie!, {
            decision: "allow",
            penpot_token: "good-token",
        });
        assert.equal(res.status, 400);
        assert.match(await res.text(), /someone-else@example\.com/);
    });

    test("a login link forwarded to another browser cannot be completed", async () => {
        const client = await registerClient();
        const authorize = new URL(`${mcpBase}/mcp/oauth/authorize`);
        authorize.search = new URLSearchParams({
            response_type: "code",
            client_id: client.client_id,
            redirect_uri: REDIRECT,
            code_challenge: s256("v".repeat(43)),
            code_challenge_method: "S256",
            state: "attacker-state",
        }).toString();
        const toIdp = await fetch(authorize, { redirect: "manual" });
        // the victim opens the identity provider link without the starter's cookie
        const toCallback = await fetch(toIdp.headers.get("location")!, { redirect: "manual" });
        const callback = await fetch(toCallback.headers.get("location")!, { redirect: "manual" });
        assert.equal(callback.status, 400);
        assert.match(await callback.text(), /kedaluwarsa/);
    });

    test("loopback redirects may use another port, odd input is a 4xx not a 500", async () => {
        const client = await registerClient();
        const consent = await startAuthorization(client.client_id, "v".repeat(43), "http://localhost:40000/callback");
        assert.equal(consent.status, 200);

        const proto = await fetch(`${mcpBase}/mcp/oauth/authorize?client_id=__proto__&redirect_uri=x`);
        assert.equal(proto.status, 400);
        const repeated = await fetch(
            `${mcpBase}/mcp/oauth/authorize?client_id=${client.client_id}&redirect_uri=${encodeURIComponent(REDIRECT)}` +
                "&response_type=code&code_challenge=c&code_challenge_method=S256&resource=a&resource=b",
            { redirect: "manual" }
        );
        assert.equal(repeated.status, 302, "repeated parameters are ignored, not a crash");
        const badBasic = await fetch(`${mcpBase}/mcp/oauth/token`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
                Authorization: `Basic ${Buffer.from("%E0%A4%A:x").toString("base64")}`,
            },
            body: "grant_type=refresh_token&refresh_token=x",
        });
        assert.equal(badBasic.status, 401);
    });

    test("the consent form only works from the browser that signed in", async () => {
        const client = await registerClient();
        const consent = await startAuthorization(client.client_id, "v".repeat(43));
        const res = await submitConsent(consent.request!, `${consent.cookie!.split("=")[0]}=stolen`, {
            decision: "allow",
            penpot_token: "good-token",
        });
        assert.equal(res.status, 400);
        assert.match(await res.text(), /kedaluwarsa/);
    });

    test("registration rejects redirect URIs outside the allowlist", async () => {
        const res = await fetch(`${mcpBase}/mcp/oauth/register`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ redirect_uris: ["https://evil.example/cb"], token_endpoint_auth_method: "none" }),
        });
        assert.equal(res.status, 400);
        assert.equal(((await res.json()) as any).error, "invalid_redirect_uri");
    });

    test("denying access redirects back with access_denied", async () => {
        const client = await registerClient();
        const consent = await startAuthorization(client.client_id, "v".repeat(43));
        const res = await submitConsent(consent.request!, consent.cookie!, { decision: "deny" });
        assert.equal(res.status, 302);
        assert.equal(new URL(res.headers.get("location")!).searchParams.get("error"), "access_denied");
    });
});
