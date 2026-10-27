# Login MCP (OAuth lewat Logto)

Server MCP punya server login sendiri, seperti Outline. Pengguna cukup memasang URL
`https://design.alphanusa.id/mcp/stream` di aplikasi AI. Aplikasi lalu membuka halaman
login, pengguna masuk lewat Logto, lalu menyetujui akses. Pada persetujuan pertama,
pengguna juga menyambungkan akun Penpot-nya sekali.

Login aktif kalau `PENPOT_MCP_OIDC_ISSUER` diisi. Dalam mode ini:

- Setiap request ke `/mcp` wajib membawa token login. Request tanpa token dijawab `401`,
  dan jawaban itulah yang membuat aplikasi AI membuka halaman login.
- Tool tanpa browser memakai token Penpot milik orang yang login. `PENPOT_ACCESS_TOKEN`
  diabaikan, jadi tidak ada akun bersama.
- Tool mode plugin memakai MCP key yang disimpan di halaman persetujuan. `?userToken=` di
  URL diabaikan.
- Server harus jalan dengan `--multi-user`. Ini sudah default di image; kalau tidak, server
  menolak start.

## Alurnya

```
Claude ──► /mcp/stream                         401 + alamat metadata
       ──► /.well-known/oauth-protected-resource/mcp/stream
       ──► /.well-known/oauth-authorization-server/mcp/oauth
       ──► /mcp/oauth/register                 Claude mendaftar sebagai klien
browser ─► /mcp/oauth/authorize ─► Logto (login) ─► /mcp/oauth/callback
        ─► halaman "Izinkan?" (+ token Penpot pertama kali) ─► kembali ke Claude
Claude ──► /mcp/oauth/token                    token akses 1 jam + token refresh
```

Yang diperiksa di halaman persetujuan:

- **Role.** Pengguna harus punya salah satu role di `PENPOT_MCP_OIDC_ROLES`. Kalau
  kosong, semua akun Logto boleh.
- **Token Penpot.** Token dicek langsung ke Penpot, dan email akun Penpot-nya harus sama
  dengan email Logto. Pengguna tidak bisa menyambungkan akun Penpot milik orang lain.
- **Browser.** Login hanya bisa diselesaikan di browser yang memulainya (dicek lewat
  cookie). Tautan login yang diteruskan ke orang lain tidak bisa dipakai.

Login berlaku **paling lama 30 hari**. Token refresh diputar setiap kali dipakai, tapi
tidak memperpanjang batas itu. Setelah 30 hari, Claude membuka halaman login lagi, dan
role di Logto diperiksa ulang saat itu.

## Pasang

### 1. Logto

Pakai aplikasi Logto yang sama dengan Penpot ("Design"). Tambahkan satu Redirect URI:

```
https://design.alphanusa.id/mcp/oauth/callback
```

Isi App ID dan App secret aplikasi itu ke variabel di bawah.

### 2. Service `penpot-mcp` di compose

```yaml
  penpot-mcp:
    image: "harbor.digicx.id/service/penpot-mcp:v1.0.0-alpha.2"
    restart: always
    networks:
      - penpot
    volumes:
      - penpot_mcp_data:/opt/penpot-mcp/data
    environment:
      PENPOT_MCP_PUBLIC_URL: https://${PENPOT_DOMAIN}
      PENPOT_URL: http://penpot-frontend:8080
      PENPOT_MCP_OIDC_ISSUER: ${LOGTO_ISSUER}
      PENPOT_MCP_OIDC_CLIENT_ID: ${LOGTO_CLIENT_ID}
      PENPOT_MCP_OIDC_CLIENT_SECRET: ${LOGTO_CLIENT_SECRET}
      PENPOT_MCP_OIDC_SCOPES: ${PENPOT_OIDC_SCOPES:-openid profile email}
      PENPOT_MCP_OIDC_ROLES: ${PENPOT_OIDC_ROLES:-}
      PENPOT_MCP_OIDC_ROLES_ATTR: ${PENPOT_OIDC_ROLES_ATTR:-roles}
      PENPOT_MCP_SECRET_KEY: ${PENPOT_MCP_SECRET_KEY:?set PENPOT_MCP_SECRET_KEY}
      PENPOT_MCP_TEAM: Alphanusa
    depends_on:
      penpot-mcp-init:
        condition: service_completed_successfully

  # Server MCP berjalan sebagai user node (uid 1000). Service ini memastikan volume
  # datanya milik user itu; tanpa ini server bisa menolak start ("not writable").
  penpot-mcp-init:
    image: alpine:3.20
    command: chown -R 1000:1000 /data
    volumes:
      - penpot_mcp_data:/data
    restart: "no"
```

Lalu tambahkan `penpot_mcp_data:` di bagian `volumes:` paling bawah.

Semua nilai kecuali `PENPOT_MCP_SECRET_KEY` sama dengan yang dipakai Penpot, jadi aturan
role-nya juga sama. Buat kuncinya sekali saja, lalu isi di Environment:

```sh
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

Jangan ganti `PENPOT_MCP_SECRET_KEY` setelah dipakai. Kunci ini mengenkripsi token yang
tersimpan. Kalau diganti, server menolak start sampai kunci lama dikembalikan, atau file
`oauth-vault.bin` di volume dihapus. Kalau file itu dihapus, semua orang harus
menyambungkan ulang.

### 3. Rute di Dokploy

Frontend Penpot hanya meneruskan `/mcp/stream` dan `/mcp/ws` ke server MCP. Halaman login
dan metadata perlu rute sendiri. Di tab **Domains**, tambahkan tiga entri:

| Host | Path | Service | Port |
|---|---|---|---|
| `design.alphanusa.id` | `/mcp/oauth` | `penpot-mcp` | `4401` |
| `design.alphanusa.id` | `/.well-known/oauth-protected-resource` | `penpot-mcp` | `4401` |
| `design.alphanusa.id` | `/.well-known/oauth-authorization-server` | `penpot-mcp` | `4401` |

Untuk ketiganya: HTTPS menyala, Let's Encrypt, dan **Strip Path mati**. Entri lama
(`/` → `penpot-frontend:8080`) tetap seperti semula.

### 4. Cek

```sh
curl -s https://design.alphanusa.id/.well-known/oauth-protected-resource/mcp/stream
# → JSON berisi "authorization_servers": ["https://design.alphanusa.id/mcp/oauth"]

curl -si -X POST https://design.alphanusa.id/mcp/stream | grep -i www-authenticate
# → WWW-Authenticate: Bearer resource_metadata="https://design.alphanusa.id/.well-known/..."
```

Kalau perintah pertama mengembalikan halaman HTML Penpot, rute di langkah 3 belum aktif.

Log `penpot-mcp` saat start harus berisi `OAuth (login with the identity provider): enabled`.

### 5. Sambungkan di Claude

Hapus connector lama, lalu tambahkan connector baru dengan URL
`https://design.alphanusa.id/mcp/stream` (tanpa `?userToken=`). Claude akan membuka
halaman login.

Di halaman persetujuan pertama, tempel:

- **Access token Penpot** (wajib): Penpot → Your account → Access tokens.
- **MCP key** (opsional, untuk mode plugin): Penpot → Your account → Integrations → MCP Server.

## Mencabut akses

| Mau apa | Caranya |
|---|---|
| Hentikan akses seseorang **sekarang juga** | Hapus access token-nya di Penpot (oleh orangnya, atau hapus/blokir akunnya). Semua tool tanpa browser langsung gagal untuk orang itu. |
| Cegah dia menyambung ulang | Cabut role-nya di Logto. Login yang sudah ada tetap berlaku sampai batas 30 harinya habis, jadi lakukan juga langkah di atas. |
| Ganti token Penpot sendiri | Sambungkan ulang connector, buka **Ganti token** di halaman persetujuan. |
| Putuskan semua orang | Hapus `oauth-vault.bin` di volume `penpot_mcp_data`, lalu restart `penpot-mcp`. |

## Variabel

| Variabel | Wajib | Isi |
|---|---|---|
| `PENPOT_MCP_OIDC_ISSUER` | ya (menyalakan login) | Issuer Logto, mis. `https://auth.alphanusa.id/oidc/` |
| `PENPOT_MCP_OIDC_CLIENT_ID`, `PENPOT_MCP_OIDC_CLIENT_SECRET` | ya | App ID dan App secret aplikasi Logto |
| `PENPOT_MCP_PUBLIC_URL` | ya | `https://design.alphanusa.id` |
| `PENPOT_URL` | ya | Alamat Penpot dari dalam Docker: `http://penpot-frontend:8080` |
| `PENPOT_MCP_SECRET_KEY` | ya | Minimal 32 karakter acak; jangan diganti |
| `PENPOT_MCP_OIDC_SCOPES` | tidak | Default `openid profile email`; samakan dengan Penpot kalau memakai role |
| `PENPOT_MCP_OIDC_ROLES` | tidak | Role yang boleh (cukup salah satu), mis. `nzk9krh9ex0c:Developer` |
| `PENPOT_MCP_OIDC_ROLES_ATTR` | tidak | Klaim role, default `roles`; untuk role organisasi `organization_roles` |
| `PENPOT_MCP_TEAM` | tidak | Team Penpot tempat AI bekerja, mis. `Alphanusa`. `list_files` hanya menampilkan team itu, dan AI diinstruksikan tidak memakai team lain atau Drafts. Berlaku untuk semua orang yang memakai server ini. |
| `PENPOT_MCP_DATA_DIR` | tidak | Default `/opt/penpot-mcp/data` di image |
| `PENPOT_MCP_OAUTH_REDIRECT_URIS` | tidak | Alamat kembali yang boleh didaftarkan klien. Default: callback claude.ai/claude.com dan `http://localhost` / `http://127.0.0.1` (port bebas, untuk Claude Code) |
| `PENPOT_MCP_RESOURCE_URL` | tidak | Default `${PENPOT_MCP_PUBLIC_URL}/mcp/stream` |

**Batasan mode plugin.** MCP key tidak dicek ke akun Penpot mana pun (Penpot tidak
menyediakan cara mengeceknya). Orang yang tahu MCP key orang lain bisa mengarahkan tool
plugin ke tab Penpot orang itu. Perlakukan MCP key seperti kata sandi. Tool tanpa browser
tidak terkena batasan ini.

Kalau login aktif tapi ada variabel wajib yang kosong, server berhenti saat start dengan
pesan yang menyebut variabel mana yang kurang. Server tidak akan jalan dalam keadaan
terbuka.
