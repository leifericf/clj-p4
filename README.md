# clj-p4

Read-only Perforce-to-Git bridge written in Clojure, with first-class support for Perforce Streams (including virtual streams) and classic depot paths. It reads stream specs, classic depot paths, and changelist history through `p4`, then writes git history through a long-running `git fast-import` subprocess. Where [`git-p4`](https://git-scm.com/docs/git-p4) is unaware of streams and [`p4-fusion`](https://github.com/salesforce/p4-fusion) treats virtual streams as an operator chore, clj-p4 manages the ephemeral client lifecycle for you and falls through to `git-p4`-compatible commit trailers so existing tooling keeps working.

## What it does

- Clones any Perforce source — stream depots (mainline / development / release / virtual) **and** classic non-stream depot paths — into a fresh bare git repo.
- Resumes via the standard `[git-p4: depot-paths = "...": change = N]` trailer.
- Honours stream `Paths:`, `Remapped:`, `Ignored:`, and parent-chain inheritance.
- Handles binary, `+k` / `+ko` keyword-flagged, symlink, `apple`, `utf16`, and large files. Move pairs travel as `R old new` rename ops in fast-import so `git log --follow` works.
- Detects virtual streams and routes through an auto-managed, locked-down P4 client so the server-resolved view is the source of truth.
- Maps P4 usernames to real names and emails (`:user-map`).
- Optionally turns P4 labels into annotated git tags (`:emit-labels?`).
- Per-changelist parallel `p4 print` (`:fetch-parallelism N`), per-file size cap (`:max-print-bytes N`), per-call retry on transient failure (`:p4/retries N`).
- Sanity-check a clone with `clj-p4.validate/validate-tip` — compares git's file count and total bytes at the tip against `p4 sizes` at the matching changelist.

## What it does not do

clj-p4 is strictly **P4 → Git**. It will never issue a Perforce command that mutates depot file state — see [Safety](#safety) for the exhaustive allowlist. There is no submit-back, no integrate, no shelve. If you need to push commits back to Perforce, this is not the right tool.

## Quick start

```clojure
(require '[clj-p4.api :as clj-p4])

;; Stream clone — mainline / development / release / virtual all just work.
(clj-p4/clone! {:conn   {:p4/port "ssl:server:1666"
                         :p4/user "alice"
                         :p4/ticket "..."
                         :p4/retries 3}
                :stream "//stream/main"
                :target "/tmp/main.git"
                :fetch-parallelism 8
                :max-print-bytes (* 100 1024 1024)})

;; Classic depot path — same call shape, no stream needed.
(clj-p4/clone! {:conn   {:p4/port "ssl:server:1666" :p4/user "alice" ...}
                :stream "//depot/legacy/src"
                :target "/tmp/legacy.git"
                :user-map {"alice" {:name "Alice Engineer"
                                    :email "alice@example.com"}}
                :emit-labels? true})

;; Resume / catch up after upstream changes.
(clj-p4/sync! {:conn ... :stream "//stream/main" :target "/tmp/main.git"})

;; Sanity-check.
(require '[clj-p4.validate :as v])
(v/validate-tip {:conn ... :target "/tmp/main.git" :source "//stream/main"})
;; => {:ok? true :change 12345 :git {...} :p4 {...}}
```

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
| `integrated` | Read integration history (used by integrate-as-merge detection). |
| `labels`, `label -o` | Read labels (used by `:emit-labels?`). |
| `users`, `user -o` | Read user records. |
| `protects` | Read protections table. |
| `client -o` / `-i` / `-d` | **Metadata-only writes.** Required to create the locked-down ephemeral client used for virtual streams and classic-depot clones. Never touches depot file state. |

**3. Defense-in-depth on every ephemeral client.** Each client clj-p4 creates carries `Options: noallwrite noclobber locked` so the server itself refuses any depot-mutating operation routed through it. The client root is a scratch path under `/tmp/clj-p4-eph-<uuid>` that the library never writes to.

The metadata-write subcommands above (`client -i` and `client -d`) are the only deviation from pure read-only. They modify a server-side client spec; they do not modify any depot file. The library deletes every client it creates in a `finally` block so nothing survives a successful or failed run.

## Inspiration

- [`git-p4`](https://git-scm.com/docs/git-p4) — the `git fast-import` writing protocol and the `[git-p4: ...]` commit-trailer convention.
- [`p4-fusion`](https://github.com/salesforce/p4-fusion) — parallel-fetch design and the read-only scope.
- [Helix Core P4 Git Connector](https://www.perforce.com/products/helix-core-git-connector) — Perforce's reference for stream-aware ingestion.

## License

MIT. See [LICENSE](LICENSE).
