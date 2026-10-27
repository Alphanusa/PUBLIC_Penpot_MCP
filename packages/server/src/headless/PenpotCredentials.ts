import type { PenpotContext } from "./HeadlessEngine";

/**
 * Resolves which Penpot backend the browser-free tools talk to, and as whom.
 *
 * - `PENPOT_URL`: base URL of the Penpot instance (e.g. https://penpot.example.com,
 *   or the internal http://penpot-frontend:8080 next to Penpot).
 * - As whom:
 *   - OAuth mode (`PENPOT_MCP_OIDC_ISSUER` set): the Penpot access token the signed-in
 *     user linked when connecting their AI client, taken from the request context.
 *     A server-wide token is never used then, so nobody can act as somebody else.
 *   - Otherwise `PENPOT_ACCESS_TOKEN`: one personal **access token** (Penpot → Your
 *     account → Access tokens; self-hosted instances need the `enable-access-tokens`
 *     flag), for single-user setups.
 *   Penpot "MCP keys" are not accepted by Penpot's API, only access tokens are.
 */
export class PenpotCredentials {
    constructor(
        private readonly env: Record<string, string | undefined> = process.env,
        /** Penpot access token of the current request's user, if any. */
        private readonly requestToken: () => string | undefined = () => undefined
    ) {}

    public baseUri(): string | undefined {
        return this.env.PENPOT_URL?.trim() || undefined;
    }

    private oauthMode(): boolean {
        return !!this.env.PENPOT_MCP_OIDC_ISSUER?.trim();
    }

    private token(): string | undefined {
        if (this.oauthMode()) return this.requestToken();
        return this.env.PENPOT_ACCESS_TOKEN?.trim() || undefined;
    }

    public isConfigured(): boolean {
        return !!this.baseUri() && !!this.token();
    }

    /** @throws a message the AI can relay to the user when not configured */
    public context(): PenpotContext {
        const baseUri = this.baseUri();
        const token = this.token();
        if (baseUri && token) return { baseUri, token };
        if (this.oauthMode()) {
            throw new Error(
                baseUri
                    ? "No Penpot account is linked to this connection. Reconnect the Penpot connector in your AI client and paste a Penpot access token on the approval page."
                    : "Browser-free Penpot access is not configured on the MCP server: set PENPOT_URL."
            );
        }
        throw new Error(
            "Browser-free Penpot access is not configured on the MCP server: set PENPOT_URL and " +
                "PENPOT_ACCESS_TOKEN (a Penpot access token from Your account → Access tokens)."
        );
    }
}
