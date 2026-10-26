import type { PenpotContext } from "./HeadlessEngine";

/**
 * Resolves which Penpot backend the browser-free tools talk to, and as whom.
 *
 * - `PENPOT_URL`: base URL of the Penpot instance (e.g. https://penpot.example.com).
 * - `PENPOT_ACCESS_TOKEN`: a personal **access token** (Penpot → Your account →
 *   Access tokens; self-hosted instances need the `enable-access-tokens` flag).
 *   Penpot "MCP keys" are not accepted by Penpot's API, only access tokens are.
 *
 * Per-user credentials (OAuth with Logto, one stored token per user) will plug in here.
 */
export class PenpotCredentials {
    constructor(private readonly env: Record<string, string | undefined> = process.env) {}

    public baseUri(): string | undefined {
        return this.env.PENPOT_URL?.trim() || undefined;
    }

    public isConfigured(): boolean {
        return !!this.baseUri() && !!this.env.PENPOT_ACCESS_TOKEN?.trim();
    }

    /** @throws a message the AI can relay to the user when not configured */
    public context(): PenpotContext {
        const baseUri = this.baseUri();
        const token = this.env.PENPOT_ACCESS_TOKEN?.trim();
        if (!baseUri || !token) {
            throw new Error(
                "Browser-free Penpot access is not configured on the MCP server: set PENPOT_URL and " +
                    "PENPOT_ACCESS_TOKEN (a Penpot access token from Your account → Access tokens)."
            );
        }
        return { baseUri, token };
    }
}
