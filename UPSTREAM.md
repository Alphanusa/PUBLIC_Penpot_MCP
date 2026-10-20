# Upstream sync

This repository is a private, standalone copy of Penpot's official MCP server
— the [`mcp/`](https://github.com/penpot/penpot/tree/develop/mcp) folder of the
Penpot monorepo — kept in sync automatically.

## Branches

| Branch | Content |
|---|---|
| `upstream` | Exact snapshot of upstream `mcp/` plus the minimal adjustments below. **Never commit here by hand** — the sync workflow overwrites it. |
| `main` | `upstream` + our own changes (headless mode, CI, docs, …). Work happens here. |

The upstream commit currently synced is in [`.upstream-sha`](.upstream-sha).

## How the sync works

[`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml) runs daily
(06:23 WIB) and on demand (Actions → *Sync upstream Penpot MCP* → *Run workflow*,
optionally with a branch, tag or commit of penpot/penpot):

1. [`.github/scripts/sync-upstream.sh`](.github/scripts/sync-upstream.sh) fetches only
   `mcp/`, `plugins/libs/plugin-types/` and `LICENSE` from penpot/penpot and rebuilds
   the snapshot. If the content is unchanged, nothing happens.
2. If it changed, the snapshot is committed to `upstream`, test-merged into `main`,
   and `pnpm install --frozen-lockfile`, build and tests run on the merge result.
3. `upstream` is pushed and a PR `upstream → main` is opened (or updated) with the
   upstream compare link and the check results.

**Merge sync PRs with "Create a merge commit"**, not squash or rebase. The merge
commit is what lets the next sync merge cleanly on top of our changes.

## Adjustments applied to every snapshot

So the MCP builds outside the monorepo (done by the sync script, kept minimal):

- `plugins/libs/plugin-types/` is copied to `vendor/plugin-types/`, and the
  `link:../../../plugins/libs/plugin-types` dependency in `packages/plugin/package.json`
  and `pnpm-lock.yaml` points to `link:../../vendor/plugin-types`.
- `storeDir: ../.pnpm-store` is removed from `pnpm-workspace.yaml`.
- The monorepo `LICENSE` (MPL-2.0) is copied to the root.

If upstream changes its layout so these no longer apply, the sync script fails
loudly instead of producing a broken snapshot — update the script then.

Dev-only leftovers that still assume the monorepo (not needed at runtime):
`scripts/build-types` (set `PENPOT_PLUGINS_API_DOC_URL` to use it) and
`ImportPenpotFileTool` (devenv mode only).

## Keeping our changes easy to sync

- Prefer adding new files (e.g. a new dispatcher or tool) over editing upstream files.
- When an upstream file must change, keep the edit small and in its own commit.

## Setup notes

For the workflow to open PRs, enable **Settings → Actions → General → Workflow
permissions → "Allow GitHub Actions to create and approve pull requests"** (for an
organization repo, the organization setting must allow it too).

PRs opened by the workflow do not trigger other workflows, so the sync workflow
runs install/build/tests itself and reports the result in the PR. CI
([`ci.yml`](.github/workflows/ci.yml)) still runs on pushes to `main` and on
PRs opened by people.

## License

MPL-2.0. Internal use and modification is fine. If modified versions of MPL-covered
files are distributed outside the company, the source of those modified files must
be made available under MPL-2.0.
