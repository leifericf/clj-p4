# clj-p4

Read-only Perforce-to-Git bridge written in Clojure, with first-class support for Perforce Streams (including virtual streams). It reads stream specs and changelist history through `p4`, then writes git history through a long-running `git fast-import` subprocess. Where [`git-p4`](https://git-scm.com/docs/git-p4) is unaware of streams and [`p4-fusion`](https://github.com/salesforce/p4-fusion) treats virtual streams as an operator chore, clj-p4 manages the ephemeral client lifecycle for you and falls through to `git-p4`-compatible commit trailers so existing tooling keeps working.

## What it does

- Clones a Perforce stream into a fresh bare git repo.
- Resumes via the standard `[git-p4: depot-paths = "...": change = N]` trailer.
- Honours stream `Paths:`, `Remapped:`, `Ignored:`, and parent-chain inheritance.
- Handles binary, `+k` / `+ko` keyword-flagged, symlink, `apple`, `utf16`, and large files.
- Detects virtual streams and routes through an auto-managed, locked-down P4 client so the server-resolved view is the source of truth.

## What it does not do

clj-p4 is strictly **P4 → Git**. It will never issue a Perforce command that mutates depot file state — see [Safety](#safety) for the exhaustive allowlist. There is no submit-back, no integrate, no shelve. If you need to push commits back to Perforce, this is not the right tool.

## Safety

A Perforce server is the single source of truth for whole engineering organisations, so the cost of a stray write from an automation tool is high. clj-p4 enforces read-only-from-Perforce in three layers.

**1. The codebase contains no write-direction commands.** Grep `src/` for `submit`, `edit`, `add`, `delete`, `integrate`, `merge`, `resolve`, `revert`, `lock`, `unlock`, `shelve`, `unshelve`, `obliterate`, `populate` — zero hits.

**2. A runtime allowlist gates every `p4` invocation.** Anything not on this list throws `:clj-p4/error :write-direction-refused` before reaching a subprocess:

| Subcommand | Notes |
| --- | --- |
| `info` | Server identification & capability detection. |
| `streams`, `stream -o` | List streams; read one stream spec. |
| `changes`, `describe`, `print` | Walk changelists, fetch file content. |
| `files`, `fstat`, `dirs`, `sizes`, `where` | Read file metadata. |
| `integrated` | Read integration history (used for merge-pair detection). |
| `labels`, `label -o` | Read labels (for label → tag conversion). |
| `users`, `user -o` | Read user records (for author mapping). |
| `protects` | Read protections table. |
| `client -o` / `-i` / `-d` | **Metadata-only writes.** Required to create the locked-down ephemeral client used for virtual streams. Never touches depot file state. |

**3. Defense-in-depth on every ephemeral client.** Each client clj-p4 creates carries `Options: noallwrite noclobber locked` so the server itself refuses any depot-mutating operation routed through it. The client root is a scratch path under `/tmp/clj-p4-eph-<uuid>` that the library never writes to.

The metadata-write subcommands above (`client -i` and `client -d`) are the only deviation from pure read-only. They modify a server-side client spec; they do not modify any depot file. The library deletes every client it creates in a `finally` block so nothing survives a successful or failed run.

## Inspiration

- [`git-p4`](https://git-scm.com/docs/git-p4) — the `git fast-import` writing protocol and the `[git-p4: ...]` commit-trailer convention.
- [`p4-fusion`](https://github.com/salesforce/p4-fusion) — parallel-fetch design and the read-only scope.
- [Helix Core P4 Git Connector](https://www.perforce.com/products/helix-core-git-connector) — Perforce's reference for stream-aware ingestion.

## License

MIT. See [LICENSE](LICENSE).
