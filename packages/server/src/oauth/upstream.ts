import type { OAuthConfig } from "./config";

/** Who signed in at the identity provider. */
export interface UpstreamIdentity {
    sub: string;
    email: string;
    name?: string;
    roles: string[];
}

interface Discovery {
    authorization_endpoint: string;
    token_endpoint: string;
    userinfo_endpoint: string;
}

/**
 * Minimal OpenID Connect client (authorization code + PKCE, confidential client)
 * for signing users in with the company identity provider (Logto).
 */
export class UpstreamOidc {
    private discovery?: Promise<Discovery>;

    constructor(
        private readonly config: OAuthConfig,
        private readonly fetchFn: typeof fetch = fetch
    ) {}

    get callbackUrl(): string {
        return `${this.config.issuer}/callback`;
    }

    private discover(): Promise<Discovery> {
        if (!this.discovery) {
            const url = `${this.config.oidc.issuer}/.well-known/openid-configuration`;
            this.discovery = this.fetchFn(url)
                .then(async (res) => {
                    if (!res.ok) throw new Error(`OpenID discovery failed: HTTP ${res.status} from ${url}`);
                    return (await res.json()) as Discovery;
                })
                .catch((error) => {
                    this.discovery = undefined; // retry on the next login
                    throw error;
                });
        }
        return this.discovery;
    }

    async authorizationUrl(state: string, codeChallenge: string, nonce: string): Promise<string> {
        const { authorization_endpoint } = await this.discover();
        const url = new URL(authorization_endpoint);
        url.search = new URLSearchParams({
            response_type: "code",
            client_id: this.config.oidc.clientId,
            redirect_uri: this.callbackUrl,
            scope: this.config.oidc.scopes,
            state,
            nonce,
            code_challenge: codeChallenge,
            code_challenge_method: "S256",
        }).toString();
        return url.toString();
    }

    /** Exchanges the callback code and reads the user's claims from the userinfo endpoint. */
    async identify(code: string, codeVerifier: string): Promise<UpstreamIdentity> {
        const { token_endpoint, userinfo_endpoint } = await this.discover();
        const basic = Buffer.from(
            `${encodeURIComponent(this.config.oidc.clientId)}:${encodeURIComponent(this.config.oidc.clientSecret)}`
        ).toString("base64");
        const tokenRes = await this.fetchFn(token_endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
                Accept: "application/json",
                Authorization: `Basic ${basic}`,
            },
            body: new URLSearchParams({
                grant_type: "authorization_code",
                code,
                redirect_uri: this.callbackUrl,
                code_verifier: codeVerifier,
            }).toString(),
        });
        const tokens: any = await tokenRes.json().catch(() => ({}));
        if (!tokenRes.ok || !tokens.access_token) {
            throw new Error(
                `token exchange failed: HTTP ${tokenRes.status} ${tokens.error ?? ""} ${tokens.error_description ?? ""}`.trim()
            );
        }

        const infoRes = await this.fetchFn(userinfo_endpoint, {
            headers: { Authorization: `Bearer ${tokens.access_token}`, Accept: "application/json" },
        });
        if (!infoRes.ok) throw new Error(`userinfo failed: HTTP ${infoRes.status}`);
        const claims: any = await infoRes.json();
        if (!claims.sub) throw new Error("userinfo has no sub claim");
        if (!claims.email) {
            throw new Error("the identity provider sent no email: add the 'email' scope and give the user an email");
        }
        return {
            sub: String(claims.sub),
            email: String(claims.email),
            name: claims.name ?? claims.username ?? undefined,
            roles: readRoles(claims, this.config.oidc.rolesAttr),
        };
    }

    /** Whether the user may connect: any one of the configured roles, or anyone when none are configured. */
    isAllowed(identity: UpstreamIdentity): boolean {
        const required = this.config.oidc.roles;
        return required.length === 0 || required.some((role) => identity.roles.includes(role));
    }
}

/** Reads a roles claim (array or space-separated string); dots address nested claims. */
export function readRoles(claims: Record<string, unknown>, attr: string): string[] {
    let value: unknown = claims[attr];
    if (value === undefined && attr.includes(".")) {
        value = attr.split(".").reduce<unknown>((node, key) => (node as any)?.[key], claims);
    }
    if (Array.isArray(value)) return value.map(String);
    if (typeof value === "string") return value.split(/\s+/).filter(Boolean);
    return [];
}
