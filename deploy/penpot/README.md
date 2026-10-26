# Pasang Penpot (self-hosted) — design.alphanusa.id

Paket ini memasang **Penpot 2.18.4** (image resmi) di server yang sudah punya Docker dan
**Traefik**, dengan:

- login **hanya lewat Logto** (OIDC); tidak ada login/daftar dengan password;
- **tanpa SMTP** (email undangan ditulis ke log, lihat langkah 6);
- **MCP resmi Penpot** (mode plugin) di `https://design.alphanusa.id/mcp/stream`.

Mode MCP tanpa browser menyusul saat Penpot 2.19 final rilis (lihat bagian "Upgrade").

Isi folder:

| File | Fungsi |
|---|---|
| `docker-compose.yml` | Penpot (frontend, backend, exporter, MCP, Postgres, Valkey) |
| `.env.example` | Semua pengaturan; salin ke `.env` |
| `backup.sh` | Backup database + aset |

## 0. Prasyarat

- Server Linux, Docker + Docker Compose v2. Minimal **2 vCPU, 4 GB RAM, 20 GB disk**.
- **Traefik** sudah jalan di server itu, dengan entrypoint HTTPS dan certificate resolver.
  Catat: nama network Docker Traefik (`docker network ls`), nama entrypoint HTTPS
  (sering `websecure`) dan nama certresolver (sering `letsencrypt`) dari konfigurasi Traefik.
- DNS: record **A/CNAME `design.alphanusa.id`** mengarah ke server ini.
- **Logto** sudah jalan dan bisa diakses dari server ini.

## 1. Buat aplikasi Penpot di Logto

Di Logto Console → **Applications → Create application** → **Traditional web**, nama `Penpot`.

- **Redirect URIs**: `https://design.alphanusa.id/api/auth/oidc/callback`
- **Post sign-out redirect URIs**: `https://design.alphanusa.id/`
- Simpan, lalu catat **App ID**, **App secret** dan **Issuer endpoint**
  (bentuknya `https://<domain-logto>/oidc`).

Pastikan user Logto punya **email** (Sign-in experience → identifier email). Penpot
mengenali user dari emailnya.

Opsional — batasi siapa yang boleh masuk Penpot: buat role `penpot` di Logto
(**Roles**), berikan ke user yang boleh, lalu isi `PENPOT_OIDC_SCOPES=openid profile email roles`
dan `PENPOT_OIDC_ROLES=penpot` di `.env`.

## 2. Siapkan folder di server

```bash
sudo mkdir -p /opt/penpot && cd /opt/penpot
# salin docker-compose.yml, .env.example, backup.sh ke sini
cp .env.example .env
chmod 600 .env
```

Isi `.env`:

| Variabel | Isi |
|---|---|
| `PENPOT_SECRET_KEY` | hasil `python3 -c "import secrets; print(secrets.token_urlsafe(64))"` |
| `PENPOT_DB_PASSWORD` | hasil `python3 -c "import secrets; print(secrets.token_urlsafe(32))"` — jangan diubah setelah start pertama |
| `LOGTO_CLIENT_ID`, `LOGTO_CLIENT_SECRET` | App ID dan App secret dari langkah 1 |
| `LOGTO_ISSUER` | Issuer endpoint **dengan `/` di akhir**, mis. `https://auth.alphanusa.id/oidc/` |
| `TRAEFIK_NETWORK`, `TRAEFIK_ENTRYPOINT`, `TRAEFIK_CERTRESOLVER` | sesuai Traefik kalian (langkah 0) |

> `LOGTO_ISSUER` wajib diakhiri `/`: Penpot menambahkan `.well-known/openid-configuration`
> ke alamat itu, dan tanpa `/` path `/oidc` hilang.

## 3. Jalankan

```bash
docker compose pull
docker compose up -d
docker compose ps
docker compose logs penpot-backend | grep -i "provider initialized"
```

Baris `provider initialized ... :provider "oidc"` berarti Penpot berhasil membaca Logto.

## 4. Login pertama

Buka **https://design.alphanusa.id** → klik tombol login **Alphanusa** (OpenID) → login di Logto. Saat pertama
kali, Penpot menampilkan halaman konfirmasi pendaftaran; setujui, dan akun Penpot dibuat
otomatis dengan email dari Logto.

## 5. Akses token (untuk MCP tanpa browser nanti)

Sudah aktif (`enable-access-tokens`): **Your account → Access tokens**. Belum perlu dipakai
sekarang.

## 6. Tim dan undangan (tanpa SMTP)

Setiap user otomatis punya tim pribadi. Untuk bekerja bersama, buat tim (mis. *Alphanusa*)
dan undang anggota lewat email seperti biasa. Karena tidak ada SMTP, emailnya tidak terkirim;
link undangan ditulis ke log backend:

```bash
docker compose logs penpot-backend | grep -iE "invitation|email" | tail -20
```

Isi email undangan (dengan link-nya) muncul di log karena `enable-log-emails`; tokennya juga
ditulis sendiri (`invitation token`) karena `enable-log-invitation-tokens`. Bentuk linknya:

```
https://design.alphanusa.id/#/auth/verify-token?token=<token>
```

Kirim link itu ke anggota lewat chat internal. Anggota login lewat Logto dulu, lalu buka link.
Kalau nanti punya SMTP, tambahkan `enable-smtp` di `PENPOT_EXTRA_FLAGS` dan variabel
`PENPOT_SMTP_*` (lihat docker-compose resmi Penpot).

## 7. Hubungkan Claude lewat MCP (mode plugin)

Tiap user:

1. Penpot → **Your account → Integrations → MCP Server** → aktifkan → **Generate MCP key**.
   Salin **server URL**: `https://design.alphanusa.id/mcp/stream?userToken=...`
   (URL ini berisi kunci — perlakukan seperti password).
2. Claude (claude.ai atau Claude Desktop) → **Settings → Connectors → Add custom connector**
   → tempel URL tadi.
3. Di Penpot, buka file desain → **File → MCP Server → Connect**. Biarkan tab itu terbuka
   (mode plugin bekerja lewat tab browser tersebut).

## 8. Backup

```bash
./backup.sh            # backups/penpot-db-*.sql.gz dan backups/penpot-assets-*.tar.gz
```

Jadwalkan harian (`crontab -e`):

```
15 2 * * * cd /opt/penpot && ./backup.sh >> backups/backup.log 2>&1
```

Salin folder `backups/` ke tempat lain (NAS/cloud) — backup di server yang sama tidak
melindungi dari server rusak.

**Restore** (ke instalasi kosong dengan `.env` yang sama):

```bash
docker compose up -d penpot-postgres
gunzip -c backups/penpot-db-XXXX.sql.gz | docker compose exec -T penpot-postgres psql -U penpot -d penpot
docker compose up -d
docker compose exec -T penpot-backend sh -c 'rm -rf /opt/data/assets/* && tar -xzf - -C /opt/data' \
  < backups/penpot-assets-XXXX.tar.gz
```

## 9. Upgrade

1. `./backup.sh`
2. Ganti `PENPOT_VERSION` di `.env` (baca catatan rilis Penpot dulu).
3. `docker compose pull && docker compose up -d` — migrasi data berjalan otomatis.

**Ke 2.19 (MCP tanpa browser):** saat Penpot 2.19 final rilis, naikkan `PENPOT_VERSION`,
lalu service `penpot-mcp` diganti image MCP kita (repo ini) yang dikunci ke versi yang sama.
Panduannya akan ditambahkan di sini.

## Troubleshooting

| Gejala | Penyebab / solusi |
|---|---|
| Tombol login Alphanusa (OpenID) tidak muncul | `enable-login-with-oidc` harus ada di `PENPOT_FLAGS` (sudah di compose); cek log backend: `provider initialized` |
| Log backend: `unable to initialize auth provider` / `invalid-sso-config` | `LOGTO_ISSUER` tanpa `/` di akhir, Logto tidak terjangkau dari container, atau domain Logto mengarah ke IP privat → isi `PENPOT_SSRF_ALLOWED_HOSTS=<domain-logto>` |
| Logto: `redirect_uri did not match` | Redirect URI di Logto harus persis `https://design.alphanusa.id/api/auth/oidc/callback` |
| Penpot: `registration-disabled` saat login pertama | `enable-oidc-registration` hilang dari flags |
| Login ditolak "not enough permissions" | `PENPOT_OIDC_ROLES` diisi tapi user tidak punya role itu, atau scope `roles` tidak diminta |
| Traefik 404 / sertifikat salah | Cek `TRAEFIK_NETWORK`, `TRAEFIK_ENTRYPOINT`, `TRAEFIK_CERTRESOLVER`; `docker compose logs penpot-frontend` |
| Claude: tidak ada tool Penpot / koneksi gagal | Pastikan MCP diaktifkan di Integrations, URL lengkap dengan `userToken`, dan file terbuka dengan **File → MCP Server → Connect** |

Konfigurasi lengkap Penpot: https://help.penpot.app/technical-guide/configuration/
