# Rilis

Rilis di repo ini berbasis **tag**, dengan pola yang sama seperti
PRODUCT_Surveilance_Utility_BE (skrip `release.sh` diadopsi apa adanya). Merge ke
`main` tidak menerbitkan apa pun; hanya tag rilis yang menghasilkan image di registry.

## Lembar keputusan

Ubah lembar ini dulu sebelum mengubah skrip atau workflow-nya.

```
Artefak            : satu image MCP server → Dockerfile di root
                     + engine tanpa browser, kalau ref yang dirilis punya engine/
Bentuk tag         : vX.Y.Z   (tanpa prefiks artefak)
Registry           : harbor.digicx.id/${PROJECT_NAME}
                     secret: HARBOR_USERNAME / HARBOR_PASSWORD
                     variable: PROJECT_NAME
Tag image          : vX.Y.Z  (immutable)  ← selalu
                     latest  (mutable)    ← hanya final DAN tertinggi
                     staging (mutable)    ← hanya pra-rilis
Versi Penpot       : kode MCP = folder mcp/ Penpot di commit .upstream-sha;
                     dicatat di label image id.alphanusa.penpot.upstream-sha
Produksi menarik   : tag bergerak `latest`; rollback dengan memaku vX.Y.Z
Branch build       : tidak ada yang didorong. PR yang menyentuh image hanya diuji
                     (image-check.yml: build + server MCP harus hidup)
Pemicu rilis       : formulir Actions + .github/scripts/release.sh, skrip sama.
                     Siapa pun dengan akses write
MAJOR artinya      : klien wajib ikut berubah — nama/argumen tool MCP berubah tidak
                     kompatibel, atau image butuh versi Penpot yang lebih baru
Variant            : di belakang nomor pra-rilis: v1.0.0-alpha.3-sso
Artefak non-image  : tidak ada
Migrasi manual     : tidak ada — server MCP tidak punya database
Tag benih          : tidak ada — rilis pertama dihitung dari v0.0.0
```

Rilis pertama: `minor` menghasilkan `v0.1.0`, `major` menghasilkan `v1.0.0`.

## Cara merilis

### Lewat formulir GitHub Actions

Actions → **Release** → Run workflow.

| Input | Isi |
|---|---|
| `level` | `major` / `minor` / `patch`, atau `-` untuk melanjutkan/mempromosikan pra-rilis |
| `maturity` | `-` untuk rilis final, atau `alpha` / `beta` / `rc` |
| `variant` | penanda tambahan, hanya bersama `maturity` |
| `ref` | kosong = ref dari dropdown. Isi untuk merilis dari branch hotfix |
| `dry_run` | **menyala secara default** |

`dry_run` yang menyala itu pengganti konfirmasi: jalankan sekali, baca tabel rencana
di ringkasan run, lalu jalankan ulang tanpa centang.

### Lewat CLI

```sh
./.github/scripts/release.sh minor --dry-run   # lihat rencananya
./.github/scripts/release.sh minor             # buat dan dorong tag; image menyusul otomatis
```

Tag yang didorong dari laptop memicu `release.yml` lewat `on: push: tags:`. Butuh
`sort -V` (GNU coreutils) — di macOS `brew install coreutils`.

### Contoh alur

```sh
./.github/scripts/release.sh minor beta   # v0.2.0-beta.1    → image :staging
./.github/scripts/release.sh - beta       # v0.2.0-beta.2    → image :staging
./.github/scripts/release.sh - rc         # v0.2.0-rc.1      → image :staging
./.github/scripts/release.sh - -          # v0.2.0 (promosi) → image :latest
```

Aturan versi (promosi, hotfix, variant, `latest` hanya untuk final tertinggi, push
atomik) sama persis dengan PRODUCT_Surveilance_Utility_BE — lihat `docs/RILIS.md` di
repo itu untuk penjelasan lengkap tiap aturan.

### Hotfix

```sh
git switch -c hotfix/v1.2.1 v1.2.0
# commit perbaikannya, push branch-nya
./.github/scripts/release.sh patch        # v1.2.1, walau main sudah di v1.3.0
```

`v1.2.1` tidak memindahkan `latest` kalau `v1.3.0` sudah ada; produksi yang butuh
hotfix itu memaku `v1.2.1`.

## Isi image

| | |
|---|---|
| Server | `node index.js --multi-user` di `/opt/penpot-mcp`, port `4401` (MCP, `/mcp`) dan `4402` (WebSocket plugin) |
| Engine tanpa browser | ikut kalau ref punya `engine/`: workflow membangunnya, menjalankan tes offline-nya, lalu menyalinnya ke `/opt/penpot-mcp/engine`. Tool `list_files`, `get_page_overview`, `render_shape`, `edit_page` aktif bila `PENPOT_URL` dan `PENPOT_ACCESS_TOKEN` diisi |
| Uji sebelum push | image dibangun, dijalankan, dan daftar tool MCP-nya diperiksa (`.github/scripts/smoke-image.mjs`); gagal → tidak ada yang didorong |
| Label | versi rilis, commit, dan `id.alphanusa.penpot.upstream-sha` (versi Penpot asal kode MCP) |

Ringkasan run Release mencatat apakah engine ikut.

## Produksi dan rollback

Di `deploy/penpot/docker-compose.yml`, service `penpot-mcp` diganti:

```yaml
  penpot-mcp:
    image: harbor.digicx.id/${PROJECT_NAME}:latest
```

```sh
docker compose pull penpot-mcp && docker compose up -d penpot-mcp
```

Untuk mundur, **pin versinya** (mis. `:v1.2.0`), lalu `docker compose up -d penpot-mcp`.
Server MCP tidak punya database, jadi rollback image cukup sampai di situ.

**Cocokkan versi Penpot.** Mode plugin butuh kode MCP yang cocok dengan versi Penpot
yang terpasang (pluginnya ada di frontend Penpot). Cek label
`id.alphanusa.penpot.upstream-sha` sebelum memakai image di produksi:

```sh
docker image inspect harbor.digicx.id/${PROJECT_NAME}:latest \
  --format '{{ index .Config.Labels "id.alphanusa.penpot.upstream-sha" }}'
```

## Setelan yang harus ada

Sekali saja:

- **Settings → Actions → General → Workflow permissions = Read and write.** Kalau
  masih read-only, job `tag` gagal saat push tag.
- **Variable `PROJECT_NAME`** (Settings → Secrets and variables → Actions → Variables):
  nama repositori image di Harbor, mis. `alphanusa/penpot-mcp`. Kalau kosong, workflow
  berhenti dengan pesan yang jelas.
- **Secret `HARBOR_USERNAME` dan `HARBOR_PASSWORD`**, di repo ini atau di tingkat
  organisasi dengan akses ke repo ini. Kalau tidak ada, tag tetap terbit tapi job
  `build` gagal saat login.

### Harbor

- **Tag immutability** untuk repositori `${PROJECT_NAME}`, pola `v*` (`latest` dan
  `staging` tetap mutable).
- **Retention** wajib **mengecualikan `v*`**, supaya instruksi rollback tidak menunjuk
  image yang sudah dihapus.
