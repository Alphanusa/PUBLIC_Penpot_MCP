/** The Penpot profile an access token belongs to. */
export interface PenpotProfile {
    id: string;
    email: string;
    fullname?: string;
}

const ANONYMOUS = "00000000-0000-0000-0000-000000000000";

/**
 * Checks a Penpot access token by asking Penpot who it belongs to.
 *
 * `get-profile` answers an unknown or revoked token with the anonymous profile
 * (zero id) rather than an error, so that case is treated as invalid too.
 *
 * @returns the profile, or null when the token is not valid
 * @throws when Penpot cannot be reached
 */
export async function fetchPenpotProfile(
    penpotUrl: string,
    token: string,
    fetchFn: typeof fetch = fetch
): Promise<PenpotProfile | null> {
    const res = await fetchFn(`${penpotUrl}/api/rpc/command/get-profile`, {
        method: "POST",
        signal: AbortSignal.timeout(10_000),
        headers: {
            Authorization: `Token ${token}`,
            Accept: "application/json",
            "Content-Type": "application/json",
        },
        body: "{}",
    });
    if (res.status === 401 || res.status === 403) return null;
    if (!res.ok) throw new Error(`Penpot answered HTTP ${res.status} to get-profile`);
    const profile: any = await res.json();
    if (!profile?.id || profile.id === ANONYMOUS || !profile.email) return null;
    return { id: String(profile.id), email: String(profile.email), fullname: profile.fullname };
}
