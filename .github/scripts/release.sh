#!/usr/bin/env bash
#
# Rilis berbasis tag untuk PUBLIC_Penpot_MCP (image MCP server Alphanusa).
# Diadopsi dari PRODUCT_Surveilance_Utility_BE/scripts/release.sh; logikanya sama.
#
#   .github/scripts/release.sh <level> [maturity] [--variant X] [--dry-run]
#   .github/scripts/release.sh describe <tag>
#
# Dipakai dua pemanggil: laptop, dan job `tag` di .github/workflows/release.yml.
# Keputusan yang dikodekan di sini dijelaskan di docs/RILIS.md — baca itu dulu
# sebelum mengubah apa pun di berkas ini.

set -euo pipefail

# ── Konfigurasi ───────────────────────────────────────────────────────────────

TAG_PREFIX=""               # artefak tunggal: v1.2.3, tanpa prefiks artefak
MATURITIES=(alpha beta rc)  # P4: daftar putih, dan urutannya = peringkatnya

# Regex resmi semver.org, diterjemahkan dari PCRE ke ERE — `[[ =~ ]]` memakai ERE
# dan tidak mengenal grup non-capturing `(?:`. Ini gerbang TERAKHIR (P2); ia tidak
# menegakkan P3 (`1.2.3-beta` lolos) maupun P5 (`1.2.3-hotfix` lolos), jadi kedua
# aturan itu punya gerbangnya sendiri di atas.
SEMVER_ERE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(\+([0-9a-zA-Z-]+(\.[0-9a-zA-Z-]+)*))?$'

# ── Dasar ─────────────────────────────────────────────────────────────────────

die() { printf 'release: %s\n' "$*" >&2; exit 1; }

# `sort -V` GNU. Tidak ada di sort bawaan macOS; Git Bash Windows membawanya.
SORT_V="sort"
if ! printf '1\n' | sort -V >/dev/null 2>&1; then
  command -v gsort >/dev/null 2>&1 || die "butuh 'sort -V' (GNU coreutils); di macOS: brew install coreutils"
  SORT_V=gsort
fi

# PERINGATAN: `sort -V` menaruh pra-rilis SESUDAH final-nya sendiri, kebalikan dari
# semver (`v1.3.0` vs `v1.3.0-rc.1`). Ia benar untuk daftar yang isinya final saja,
# dan untuk pra-rilis sesama basis. Semua pemanggil di bawah menyaring lebih dulu
# supaya kedua jenis itu tidak pernah masuk satu daftar. Jangan hapus penyaringnya.
semver_sort() { "$SORT_V" -V; }

maturity_rank() {
  local m i=1
  for m in "${MATURITIES[@]}"; do
    [ "$m" = "$1" ] && { echo "$i"; return 0; }
    i=$((i + 1))
  done
  echo 0
}

# Semua tag rilis, prefiks dibuang, urut menaik.
tags_all() { git tag --list "${TAG_PREFIX}v*" | sed "s|^$TAG_PREFIX||" | semver_sort; }

# Idem, tapi hanya yang leluhur HEAD (P6).
tags_merged() { git tag --merged HEAD --list "${TAG_PREFIX}v*" | sed "s|^$TAG_PREFIX||" | semver_sort; }

finals()      { grep -Ev -- '-' || true; }
prereleases() { grep -E   -- '-' || true; }

# Basis versi: tag final tertinggi yang leluhur HEAD (P6). Pra-rilis tidak pernah
# jadi basis promosi (P7). Belum ada tag → v0.0.0, jadi `major` pertama menghasilkan
# v1.0.0.
base_version() {
  local b
  b=$(tags_merged | finals | tail -1)
  echo "${b:-v0.0.0}"
}

# Pra-rilis "terbuka": pra-rilis tertinggi yang leluhur HEAD dan angka basisnya di
# atas basis final. Itulah garis yang sedang berjalan dan bisa dilanjutkan/dipromosikan.
open_prerelease() {
  local base="$1" cand cand_base best=""
  while read -r cand; do
    [ -z "$cand" ] && continue
    cand_base="${cand%%-*}"
    [ "$cand_base" = "$base" ] && continue
    # cand_base harus > base; keduanya final, jadi semver_sort aman di sini.
    [ "$(printf '%s\n%s\n' "$cand_base" "$base" | semver_sort | tail -1)" = "$base" ] && continue
    best="$cand"   # daftarnya menaik, jadi yang terakhir lolos adalah yang tertinggi
  done < <(tags_merged | prereleases)
  echo "$best"
}

bump() {
  local v="${1#v}" level="$2" major minor patch
  IFS=. read -r major minor patch <<<"$v"
  case "$level" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) die "level tidak dikenal: $level" ;;
  esac
  echo "v$major.$minor.$patch"
}

validate() {
  local v="${1#v}"
  [[ "$v" =~ $SEMVER_ERE ]] || die "bukan semver yang sah: $1"
}

# ── describe ──────────────────────────────────────────────────────────────────
#
# Menjawab "ini final?" dan "ini final tertinggi?" untuk sebuah tag yang SUDAH ada.
# Dipakai job `build` di kedua jalur pemicu, supaya alias latest/staging ditentukan
# satu tempat saja.

emit() {
  printf '%s=%s\n' "$1" "$2"
  [ -n "${GITHUB_OUTPUT:-}" ] && printf '%s=%s\n' "$1" "$2" >>"$GITHUB_OUTPUT"
  return 0
}

cmd_describe() {
  local tag="${1:-}"
  [ -n "$tag" ] || die "describe butuh sebuah tag, mis. ${TAG_PREFIX}v1.2.3"
  [[ "$tag" == "$TAG_PREFIX"v* ]] || die "bukan tag rilis: $tag"

  local version="${tag#"$TAG_PREFIX"}"
  validate "$version"

  local is_final=false
  [[ "$version" != *-* ]] && is_final=true

  # Penjaga P8: dibandingkan ke SELURUH tag, bukan hanya leluhur HEAD. Itu yang
  # membuat hotfix benar — merilis v1.1.1 saat v1.2.0 sudah ada tidak boleh
  # memindahkan `latest` ke kode lama.
  local highest
  highest=$(tags_all | finals | tail -1)

  local write_latest=false
  [ "$is_final" = true ] && [ "$version" = "$highest" ] && write_latest=true

  local write_staging=false
  [ "$is_final" = false ] && write_staging=true

  emit version       "$version"
  emit is_final      "$is_final"
  emit highest_final "${highest:-}"
  emit write_latest  "$write_latest"
  emit write_staging "$write_staging"
}

# ── Rencana rilis ─────────────────────────────────────────────────────────────

usage() {
  cat >&2 <<EOF
Pemakaian:
  release.sh <level> [maturity] [--variant X] [--dry-run]
  release.sh describe <tag>

  level     : major | minor | patch | -     ('-' = lanjutkan/promosikan pra-rilis terbuka)
  maturity  : $(printf '%s | ' "${MATURITIES[@]}" | sed 's/ | $//') | -
  --variant : penanda tambahan di belakang nomor pra-rilis, hanya bersama maturity
  --dry-run : cetak rencana, jangan buat tag

Contoh:
  release.sh minor --dry-run          rencana saja
  release.sh minor beta               v1.3.0-beta.1
  release.sh - beta                   v1.3.0-beta.2   (lanjutkan)
  release.sh - beta --variant sso     v1.3.0-beta.3-sso
  release.sh - -                      v1.3.0          (promosikan)
EOF
  exit 2
}

# ── Hitungan versi ────────────────────────────────────────────────────────────

# compute_next TIDAK menyentuh apa pun — ia hanya mencetak versi berikutnya.
compute_next() {
  local level="$1" maturity="$2" variant="$3"
  local base open next
  base=$(base_version)
  open=$(open_prerelease "$base")

  if [ "$level" = "-" ]; then
    [ -n "$open" ] || die "level '-' butuh pra-rilis terbuka, tapi tidak ada. Sebutkan level (major/minor/patch)."
    local open_base="${open%%-*}" open_suffix="${open#*-}"
    local open_maturity="${open_suffix%%.*}"

    if [ "$maturity" = "-" ]; then
      # Promosi: angka basis pra-rilis itu sendiri, BUKAN kenaikan lagi (P7).
      next="$open_base"
    else
      local r_new r_open
      r_new=$(maturity_rank "$maturity"); r_open=$(maturity_rank "$open_maturity")
      if [ "$maturity" = "$open_maturity" ]; then
        # `beta.3-sso` → 3: variant tidak ikut dihitung, nomornya satu deret.
        local n="${open_suffix#*.}"; n="${n%%-*}"
        next="$open_base-$maturity.$((n + 1))"
      elif [ "$r_new" -gt "$r_open" ]; then
        next="$open_base-$maturity.1"
      else
        die "tidak bisa mundur dari '$open_maturity' ke '$maturity' (pra-rilis terbuka: $open)"
      fi
    fi
  else
    # Naik dari garis tertinggi yang sudah ada: basis pra-rilis terbuka kalau ada
    # (`minor` saat v1.1.0-beta.1 terbuka → v1.2.0, garis 1.1.0 ditinggal), final
    # kalau tidak. Tanpa ini kenaikan mendarat di garis terbuka dan tag-nya bentrok.
    local from="$base"; [ -n "$open" ] && from="${open%%-*}"
    local bumped; bumped=$(bump "$from" "$level")
    if [ "$maturity" = "-" ]; then next="$bumped"; else next="$bumped-$maturity.1"; fi
  fi

  # Bentuk variant disepakati `-alpha.3-sso` (lihat docs/RILIS.md).
  if [ -n "$variant" ]; then next="$next-$variant"; fi

  # P3: pra-rilis selalu diawali tanda hubung DAN bernomor. Regex semver di bawah
  # meloloskan `-beta` polos, jadi pemeriksaan ini harus berdiri sendiri.
  if [[ "$next" == *-* ]]; then
    [[ "$next" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+(-[0-9A-Za-z-]+)?$ ]] \
      || die "sufiks pra-rilis harus '-<maturity>.<n>[-<variant>]': $next"
  fi
  validate "$next"   # P2, gerbang terakhir
  echo "$next"
}

plan_table() {
  local next="$1"
  local base open highest kind="final" latest_note
  base=$(base_version)
  open=$(open_prerelease "$base")
  highest=$(tags_all | finals | tail -1)
  [[ "$next" == *-* ]] && kind="pra-rilis"
  if [ "$kind" = "final" ] && { [ -z "$highest" ] || \
       [ "$(printf '%s\n%s\n' "$next" "$highest" | semver_sort | tail -1)" = "$next" ]; }; then
    latest_note="ya"
  elif [ "$kind" = "pra-rilis" ]; then
    latest_note="tidak (pra-rilis; menggerakkan 'staging')"
  else
    latest_note="tidak (final tertinggi tetap $highest)"
  fi
  cat <<EOF
| | |
|---|---|
| basis (final leluhur HEAD) | \`$base\` |
| pra-rilis terbuka | \`${open:-—}\` |
| ref | \`$(git rev-parse --short HEAD)\` di \`$(git rev-parse --abbrev-ref HEAD)\` |
| **tag baru** | **\`$TAG_PREFIX$next\`** |
| jenis | $kind |
| pindahkan \`latest\` | $latest_note |
EOF
}

main() {
  [ $# -ge 1 ] || usage
  [ "$1" = "describe" ] && { shift; cmd_describe "$@"; return; }

  local level="$1"; shift
  local maturity="-" variant="" dry_run=false

  if [ $# -gt 0 ] && [[ "$1" != --* ]]; then maturity="$1"; shift; fi
  while [ $# -gt 0 ]; do
    case "$1" in
      --variant) variant="${2:-}"; shift 2 || usage ;;
      --variant=*) variant="${1#--variant=}"; shift ;;
      --dry-run) dry_run=true; shift ;;
      *) usage ;;
    esac
  done

  case "$level" in major|minor|patch|-) ;; *) die "level tidak sah: $level" ;; esac
  # P4: hanya alpha/beta/rc. Ini juga yang menutup P5 — 'hotfix' bukan tingkat
  # kematangan; hotfix adalah kenaikan patch dari tag produksinya.
  if [ "$maturity" != "-" ] && [ "$(maturity_rank "$maturity")" = 0 ]; then
    die "maturity tidak sah: $maturity (hanya: ${MATURITIES[*]}). Hotfix = level 'patch' dari tag produksinya, bukan sebuah label."
  fi
  if [ -n "$variant" ]; then
    [ "$maturity" != "-" ] || die "--variant hanya boleh bersama maturity: ia menambah penanda pra-rilis, dan pada rilis final justru akan menurunkan peringkatnya di bawah versi polosnya"
    [[ "$variant" =~ ^[0-9A-Za-z-]+$ ]] || die "--variant hanya boleh [0-9A-Za-z-]: $variant"
  fi

  cd "$(git rev-parse --show-toplevel)"

  # P11: tanpa riwayat tag, hitungan diam-diam mulai dari nol dan rilis pertama
  # bernomor v0.1.0 tanpa mengeluh.
  [ "$(git rev-parse --is-shallow-repository)" = false ] \
    || die "klon shallow — tag tidak lengkap. Checkout dengan fetch-depth: 0"
  [ -z "$(git status --porcelain)" ] || die "worktree kotor; commit atau stash dulu"
  git fetch --tags --quiet origin

  local next tag
  next=$(compute_next "$level" "$maturity" "$variant")
  tag="$TAG_PREFIX$next"
  git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag sudah ada: $tag"
  if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    die "tag sudah ada di origin: $tag"
  fi

  # P12: tidak ada berkas sementara sama sekali — tabel langsung ke stdout, dan ke
  # ringkasan run bila ada. Tidak ada yang bisa mengotori worktree.
  local plan
  plan=$(plan_table "$next")
  printf '%s\n\n' "$plan"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### Rencana rilis\n\n%s\n\n' "$plan" >>"$GITHUB_STEP_SUMMARY"
  fi

  if [ "$dry_run" = true ]; then
    printf '(dry-run: tidak ada tag yang dibuat)\n'
    [ -n "${GITHUB_OUTPUT:-}" ] && printf 'tag=\n' >>"$GITHUB_OUTPUT"
    return 0
  fi

  git tag -a "$tag" -m "release $tag"
  # P13: push atomik; gagal → tag lokal dihapus lagi, supaya percobaan berikutnya
  # tidak tersandung tag yang tidak pernah sampai ke origin.
  if ! git push --atomic origin "refs/tags/$tag"; then
    git tag -d "$tag" >/dev/null
    die "push tag gagal; tag lokal $tag dihapus lagi"
  fi

  printf 'terbit: %s\n' "$tag"
  [ -n "${GITHUB_OUTPUT:-}" ] && printf 'tag=%s\n' "$tag" >>"$GITHUB_OUTPUT"
  return 0
}

main "$@"
