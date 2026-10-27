/** HTML pages of the connect flow. Texts are in Indonesian: the users are. */

export const escapeHtml = (value: string) => value.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);

const STYLE = `
:root { color-scheme: light dark; --bg:#f6f6f8; --card:#fff; --text:#1d1d22; --muted:#62626e;
  --line:#dcdce3; --accent:#6911d4; --accent-text:#fff; --danger:#b42318; }
@media (prefers-color-scheme: dark) { :root { --bg:#141418; --card:#1f1f25; --text:#ececf1;
  --muted:#a0a0ad; --line:#34343d; --accent:#9b5cf0; --danger:#f97066; } }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
main { max-width:460px; margin:48px auto; padding:0 16px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:24px; }
h1 { font-size:20px; margin:0 0 8px; }
p { margin:0 0 12px; }
.muted { color:var(--muted); font-size:13px; }
.error { color:var(--danger); border:1px solid var(--danger); border-radius:8px; padding:8px 12px; margin:0 0 16px; }
label { display:block; font-weight:600; margin:16px 0 4px; }
input { width:100%; padding:9px 10px; border:1px solid var(--line); border-radius:8px; background:transparent; color:inherit; font:inherit; }
details { margin:16px 0 0; } summary { cursor:pointer; color:var(--muted); }
.actions { display:flex; gap:8px; margin-top:24px; }
button { flex:1; padding:10px 12px; border-radius:8px; border:1px solid var(--line); background:transparent; color:inherit; font:inherit; cursor:pointer; }
button.primary { background:var(--accent); border-color:var(--accent); color:var(--accent-text); font-weight:600; }
code { font-size:13px; }
`;

function page(title: string, body: string): string {
    return `<!doctype html><html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title><style>${STYLE}</style></head>
<body><main><div class="card">${body}</div></main></body></html>`;
}

export interface ConsentView {
    requestId: string;
    clientName: string;
    redirectHost: string;
    userEmail: string;
    /** Email of the Penpot account already linked with a still-valid token, if any. */
    linkedPenpotEmail?: string;
    hasMcpKey: boolean;
    error?: string;
}

const tokenFields = (required: boolean) => `
<label for="penpot_token">Access token Penpot</label>
<input id="penpot_token" name="penpot_token" type="password" autocomplete="off" ${required ? "required" : ""}>
<p class="muted">Buat di Penpot: <b>Your account → Access tokens → Generate new token</b>.
Token ini dipakai Claude untuk membaca dan mengubah file Penpot atas nama kamu.</p>
<label for="mcp_key">MCP key (opsional)</label>
<input id="mcp_key" name="mcp_key" type="password" autocomplete="off">
<p class="muted">Hanya untuk mode plugin (Penpot terbuka di browser). Buat di
<b>Your account → Integrations → MCP Server</b>.</p>`;

export function consentPage(view: ConsentView): string {
    const linked = view.linkedPenpotEmail
        ? `<p>Akun Penpot tersambung: <b>${escapeHtml(view.linkedPenpotEmail)}</b>${
              view.hasMcpKey ? " (dengan MCP key)" : ""
          }.</p>
<details><summary>Ganti token</summary>${tokenFields(false)}</details>`
        : `<p>Sambungkan akun Penpot kamu sekali saja. Setelah ini tidak perlu lagi.</p>${tokenFields(true)}`;

    return page(
        "Izinkan akses ke Penpot",
        `<h1>Izinkan ${escapeHtml(view.clientName)} mengakses Penpot?</h1>
<p class="muted">Masuk sebagai <b>${escapeHtml(view.userEmail)}</b>. Setelah diizinkan kamu kembali ke
<b>${escapeHtml(view.redirectHost)}</b>.</p>
${view.error ? `<p class="error">${escapeHtml(view.error)}</p>` : ""}
<form method="post" action="consent">
<input type="hidden" name="request" value="${escapeHtml(view.requestId)}">
${linked}
<div class="actions">
<button type="submit" name="decision" value="deny" formnovalidate>Tolak</button>
<button type="submit" name="decision" value="allow" class="primary">Izinkan</button>
</div>
</form>`
    );
}

export function messagePage(title: string, message: string): string {
    return page(title, `<h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p>`);
}
