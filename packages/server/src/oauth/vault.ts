import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";

/** A person who connected an AI client, keyed by the identity provider's `sub`. */
export interface LinkedUser {
    email: string;
    name?: string;
    /** Penpot access token (Your account → Access tokens). */
    penpotToken: string;
    /** Email of the Penpot profile the token belongs to. */
    penpotEmail: string;
    /** Penpot MCP key, for the plugin tools (optional). */
    mcpKey?: string;
    updatedAt: string;
}

/** An OAuth client registered through dynamic client registration. */
export interface RegisteredClient {
    clientId: string;
    /** sha256 of the client secret; absent for public clients. */
    clientSecretHash?: string;
    clientName?: string;
    redirectUris: string[];
    tokenEndpointAuthMethod: "none" | "client_secret_post" | "client_secret_basic";
    createdAt: number;
}

/** A refresh token, stored by its sha256. */
export interface RefreshGrant {
    sub: string;
    clientId: string;
    scope: string;
    expiresAt: number;
}

interface VaultData {
    version: 1;
    users: Record<string, LinkedUser>;
    clients: Record<string, RegisteredClient>;
    refreshTokens: Record<string, RefreshGrant>;
}

const emptyData = (): VaultData => ({ version: 1, users: {}, clients: {}, refreshTokens: {} });

export const sha256 = (value: string) => createHash("sha256").update(value).digest("base64url");

/** Derives an independent 32-byte key per purpose from the configured secret. */
export function deriveKey(secret: string, purpose: string): Buffer {
    return createHash("sha256").update(`penpot-mcp:${purpose}:${secret}`).digest();
}

/**
 * Small encrypted store for linked Penpot credentials, OAuth clients and refresh
 * tokens. The whole document is encrypted with AES-256-GCM and written atomically.
 * Sized for a handful of users: everything lives in memory and each change rewrites
 * the file.
 */
export class Vault {
    private data: VaultData = emptyData();
    private writes: Promise<void> = Promise.resolve();
    private readonly key: Buffer;
    private readonly file: string;

    constructor(dataDir: string, secret: string) {
        this.key = deriveKey(secret, "vault");
        this.file = path.join(dataDir, "oauth-vault.bin");
    }

    /** Loads the store; a missing file starts empty. */
    async load(): Promise<void> {
        let raw: Buffer;
        try {
            raw = await fs.readFile(this.file);
        } catch (error: any) {
            if (error?.code === "ENOENT") {
                await fs.mkdir(path.dirname(this.file), { recursive: true });
                this.data = emptyData();
                return;
            }
            throw error;
        }
        try {
            const iv = raw.subarray(0, 12);
            const tag = raw.subarray(12, 28);
            const decipher = createDecipheriv("aes-256-gcm", this.key, iv);
            decipher.setAuthTag(tag);
            const json = Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString("utf8");
            this.data = { ...emptyData(), ...JSON.parse(json) };
        } catch {
            throw new Error(
                `Cannot decrypt ${this.file}: PENPOT_MCP_SECRET_KEY differs from the one it was written with. ` +
                    "Restore the old key, or delete the file to start over (everyone then reconnects)."
            );
        }
    }

    private persist(): Promise<void> {
        const now = Date.now();
        for (const [hash, grant] of Object.entries(this.data.refreshTokens)) {
            if (grant.expiresAt <= now) delete this.data.refreshTokens[hash];
        }
        const snapshot = JSON.stringify(this.data);
        this.writes = this.writes.then(async () => {
            const iv = randomBytes(12);
            const cipher = createCipheriv("aes-256-gcm", this.key, iv);
            const body = Buffer.concat([cipher.update(snapshot, "utf8"), cipher.final()]);
            const tmp = `${this.file}.${process.pid}.tmp`;
            await fs.writeFile(tmp, Buffer.concat([iv, cipher.getAuthTag(), body]), { mode: 0o600 });
            await fs.rename(tmp, this.file);
        });
        return this.writes;
    }

    getUser(sub: string): LinkedUser | undefined {
        return this.data.users[sub];
    }

    async saveUser(sub: string, user: LinkedUser): Promise<void> {
        this.data.users[sub] = user;
        await this.persist();
    }

    getClient(clientId: string): RegisteredClient | undefined {
        return this.data.clients[clientId];
    }

    clientCount(): number {
        return Object.keys(this.data.clients).length;
    }

    async saveClient(client: RegisteredClient): Promise<void> {
        this.data.clients[client.clientId] = client;
        await this.persist();
    }

    getRefreshGrant(token: string): RefreshGrant | undefined {
        const grant = this.data.refreshTokens[sha256(token)];
        return grant && grant.expiresAt > Date.now() ? grant : undefined;
    }

    /** Replaces `previous` (when given) with `token` in one write: refresh tokens rotate. */
    async putRefreshGrant(token: string, grant: RefreshGrant, previous?: string): Promise<void> {
        if (previous) delete this.data.refreshTokens[sha256(previous)];
        this.data.refreshTokens[sha256(token)] = grant;
        await this.persist();
    }
}
