# Changelog

## Unreleased

## 0.6.0-alpha

Read-direction parity step 5 of 6: author mapping (P4 user → real name + email) and labels-as-annotated-tags. Both are wins over `p4-fusion`, which uses raw P4 usernames and ignores labels.

### Added

- **`:user-map` option on `clone!` / `sync!`.** Map of `p4-username → {:name :email}`; consulted when shaping each commit's `committer` line. Users not in the map keep the previous default `<user>@perforce` email and use the raw username as the name. Matches the spirit of `git-p4`'s `git-p4.mapUser` config and goes beyond `p4-fusion` (which uses the raw P4 username everywhere).
- **`:emit-labels?` option on `clone!` / `sync!`.** When true, walks `p4 labels` after the main import, fetches each spec via `p4 label -o`, and creates an annotated git tag for any label whose `Revision:` resolves to a changelist we just imported. Labels referencing unimported changes or unparseable revisions (e.g. file-revision-style `//depot/foo#3` rather than a CL) are skipped silently. Both `labels` and `label -o` are on the read-only allowlist landed in 0.2.0-alpha.
- **`clj-p4.shell.git/emit-tag!`** plus `clj-p4.shell.p4/labels` and `label-spec` — the wire functions that the new option surface relies on.

### Notes

- The roadmap's multi-stream / multi-ref single-repo feature (`:streams [s1 s2 ...]` producing parallel branches in one bare repo) is **deferred to a later release.** It cuts across the executor's per-stream view, the marks-file lifecycle, and the trailer's depot-paths field; landing it correctly is its own design pass and is decoupled from the metadata-fidelity wins shipping here.

## 0.5.0-alpha

Read-direction parity step 4 of 6: parallel `p4 print` per changelist, per-file size cap, and a fix that ensures move+edit commits keep the new content.

### Added

- **`:fetch-parallelism N` option on `clone!` / `sync!`.** When > 1, the executor fans out per-changelist `p4 print` calls across N parallel workers via `pmap` before serialising blob writes into the single fast-import stdin. Sequential by default (the change is opt-in). Mirrors `p4-fusion`'s `--networkThreads`.
- **`:max-print-bytes N` option on `clone!` / `sync!`.** Hard upper bound on bytes pulled from any single `p4 print` invocation. Replaces the old tacit-OOM behaviour for pathologically large files; throws `:clj-p4/error :max-print-bytes-exceeded` with the offending depot path so the caller can choose to skip or raise the cap.

### Changed

- **Executor refactored into three explicit phases per changelist:** `materialize-ops` (pure — decides the shape of every fast-import op without I/O), `fetch-blobs!` (the parallelisable I/O fan-out), `emit-ops!` (serial blob writes + commit-shape construction). Sequential behaviour is unchanged.

### Fixed

- **Move + edit commits no longer drop the new content.** v0.4.0's move-pair grouping emitted a single `R old new` rename op, which is correct for content-preserving moves but loses the new revision's bytes when a `p4 move` was followed by an edit in the same changelist. The executor now emits `R old new` followed by `M mode mark new`, so git's tree always reflects the move/add revision's content. The redundant `M` is harmless when content was preserved.

### Notes

- The roadmap's `:print-batch-size` (`p4-fusion`'s `--printBatch`) and `:lookahead` features are deferred to a later release. Per-file `p4 print` parallelism captures most of the wall-time gain on the multi-file-per-CL workload that motivated the roadmap entry; the batched-print and lookahead optimisations are second-order wins that can land independently once benchmarks are in place.

## 0.4.0-alpha

Read-direction parity step 3 of 6: rename pairs now travel as renames in git history.

### Added

- **Move-pair grouping.** A `move/delete` + `move/add` pair within the same changelist now produces a single fast-import `R old new` rename op instead of separate `D old` + `M new`. This makes `git log --follow <renamed-path>` work — and matches `p4-fusion`'s behaviour. `parse-describe` extracts the `movedFile<n>` field from `p4 describe -G` so pairs are identified by the server-supplied partner attribute, not heuristics. If only one half of the pair survives the view filter (e.g. the destination is excluded), the surviving half falls back to a plain `D` or `M` so nothing is silently dropped.

### Notes

- The roadmap's `--noMerge` / integrate-as-2-parent-merge feature is **deferred to a later release.** Reliable merge detection requires per-file `p4 integrated -F` lookups, which materially impact clone time on large changelists; landing it correctly needs the parallel-fetch infrastructure that's still pending. Until then, integrations remain visible only as the file-level `:integrate` actions in commit metadata, and history stays linear.

## 0.3.0-alpha

Read-direction parity step 2 of 6: classic (non-stream) depot paths and per-command retries.

### Added

- **Classic depot path support.** `clone!` and `sync!` now accept any depot path, not just stream depots. When the supplied path isn't a stream (detected by `p4 stream -o` reporting "not a stream"), the call is routed through a generic ephemeral client whose `View:` is generated as `<depot-path>/... //<client>/...`. Same locked-down `Options: noallwrite noclobber locked` lifecycle as the virtual-stream path. The `[git-p4: ...]` trailer carries the user-supplied depot path.
- **`clj-p4.shell.p4/with-classic-client`** plus `create-classic-client!` — sibling of `with-ephemeral-client` for non-stream depots.
- **`:p4/retries N` and `:p4/retry-backoff-ms M` on the connection spec.** Each `p4` invocation automatically retries up to N times on transient failure (stderr matching `connection|reset|timeout|temporarily|broken pipe|RPC`), with exponential backoff starting at M ms (default 100). `proc/run!` gained the same `:retries` / `:retry-backoff-ms` options for reuse outside the p4 path.

### Notes

- The roadmap's `:connection-refresh-every` flag is omitted: clj-p4 uses one-shot `p4` subprocesses authenticated by `P4PASSWD=<ticket>`, so each call is already a fresh connection. Long-lived libp4api session refresh (p4-fusion's `--refresh`) does not apply.
- `:stream` is kept as the parameter name on `clone!` / `sync!` even though the value is now "stream-or-depot". The roadmap's `:stream` → `:source` rename is deferred to avoid churn in downstream callers; the docstring spells out that any depot path works.

## 0.2.0-alpha

Read-direction parity step 1 of 6 toward `p4-fusion` parity: virtual streams, hard read-only enforcement, and a README that earns its paragraph count.

### Added

- **Virtual stream support.** `clone!` and `sync!` now accept a virtual stream depot path (e.g. `//stream/virtual`). Behind the scenes the library creates an ephemeral, locked-down P4 client bound to the stream so the server-resolved view is the source of truth, runs the import through that client, and tears the client down in a `finally` block. The `[git-p4: ...]` trailer keeps using the user-visible stream name, not the throwaway client name.
- **`clj-p4.shell.p4/with-ephemeral-client`** plus `create-stream-client!` / `delete-stream-client!`. Each ephemeral client is created with `Options: noallwrite noclobber locked` and a scratch root the library never writes to.
- **`clj-p4.view/client-view->view`** parses the auto-generated client `View:` block into the same value `effective-view` produces, so the executor's depot → local mapping is identical for virtual and non-virtual streams.

### Changed

- **Hard read-only allowlist on every `p4` invocation.** Anything outside an explicit allowlist (`info`, `streams`, `stream -o`, `changes`, `describe`, `print`, `files`, `fstat`, `integrated`, `labels`, `label -o`, `users`, `user -o`, `protects`, `where`, `dirs`, `sizes`, plus `client -o` / `-i` / `-d` for the ephemeral-client lifecycle) throws `:clj-p4/error :write-direction-refused` before the subprocess starts. clj-p4 cannot submit, edit, integrate, shelve, revert, populate, obliterate, or otherwise mutate depot file state. Documented exhaustively in `README.md` under `## Safety`.
- **Rewrote the README** to drop the "pre-alpha" banner (release tags carry the alpha signal), describe the read-only guarantee, and explain the virtual-stream auto-managed client.

## 0.1.0-alpha

First tagged release. Pre-alpha — the public API will move; pin a SHA, not a version.

### Added

- **Pure-Clojure Perforce-to-Git library, stream-aware where `git-p4` and `p4-fusion` are not.** The whole pipeline is `clone!` / `sync!` / `repo-state` over a `clj-p4.api` facade, with no opts god-map and no `core.async` in any public signature.
- **Pure layer (`.cljc`, runs on JVM, Babashka, ClojureScript-Node, and aspirationally mino-lang).** `clj-p4.spec` (depot-path validation), `clj-p4.parse.semantic` (generic record-maps → typed `StreamSpec`/`ChangelistRecord`/`FileRev`), `clj-p4.view` (Paths/Remapped/Ignored composition with parent inheritance, glob compilation, depot → local-path mapping, P4's last-match-wins rule), `clj-p4.exclude` (gitignore-flavoured client-side filtering), `clj-p4.plan` (clone/sync plan values + lazy operation-seq).
- **Host-bound shell layer (JVM via `babashka.process`).** `clj-p4.parse.marshal` decodes both Python-2 marshal (`p4 -G`) and JSON (`p4 -Mj -ztag`); `clj-p4.shell.proc` wraps subprocess invocation; `clj-p4.shell.p4` is one fn per `p4` invocation returning parsed data; `clj-p4.shell.git` wraps `git fast-import` as a single long-lived subprocess for writes.
- **Orchestration: `clj-p4.execute` reduces a plan's lazy op-seq into a populated bare git repo,** emitting commits with a `git-p4`-compatible trailer (`[git-p4: depot-paths = "<stream>/": change = N]`) for parity with existing `git-p4` clones.
- **Auto-selects `-Mj` (tagged JSON) on server ≥ 2024.1, falling back to `-G` (Python marshal) on older Helix p4d.** Both wire formats are exercised in the test suite.
- **Resume on crash via `git fast-import` marks file** (`<target>/clj-p4.marks`, lifecycle bound to the bare repo so `rm -rf <target>` cleans it up).
- **Refuses virtual streams** with a clear `ex-info` (`:clj-p4/error :virtual-stream-unsupported`); virtual stream support is deferred to v0.2.
- **Docker-based integration fixture** under `test/fixtures/p4d/` — a self-contained Helix p4d r24.1 image that seeds nine changelists across `//stream/main` (mainline), `//stream/dev`, `//stream/release`, and `//stream/virtual` exercising binary, `+x`, symlink, `+k`/`+ko`, UTF-8 paths, special-character filenames, rename pairs, type morphs, and a 2 MiB binary blob. Tests gated behind `CLJ_P4_INTEGRATION=1` and the `^:integration` selector.
- **`t9800-basic-test` port** mirrors `git-p4`'s basic-clone regression (clone + sync + new-change roundtrip). Roadmap for the rest of the t98xx read-direction suite (t9802/t9803/t9810/t9812/t9814/t9817-9/t9821-2/t9825-7/t9830/t9834-6) lives in `test/clj_p4/integration/README.md`.
- **CI runs three jobs:** JVM (`clojure -X:test`), ClojureScript-Node (shadow-cljs), and Babashka (`bb test` against the pure layers).

### Fixes

- **`depot-path?` rejects ellipsis (`...`) and `*` in non-final segments** — the validator's regex permitted `//foo/.../bar` and the parser treated the embedded `...` as a literal segment, so `depot-path?` and `parse-depot-path` disagreed and a clone caller could pass a path that real P4 servers reject. Wildcards are now only legal as the FINAL path segment; mid-path `...` and `*` are rejected up front.
- **`stream-chain` detects cyclic Parent metadata instead of looping forever** — a malformed (or malicious) P4 server reporting `//s/A`'s parent as `//s/B` and `//s/B`'s parent as `//s/A` would make `stream-chain` walk indefinitely, hanging `clone!` and running unbounded RPCs against the server. The walk now tracks visited stream names and throws `ex-info` with `:clj-p4/error :stream-chain-cycle` as soon as a name is re-encountered.
- **`clone!` refuses to overlay an existing non-empty target** — `git init --bare` is idempotent, so a `clone!` against a directory that already contained user files would silently create a bare repo right next to them and proceed. The clone "worked" but left the directory in an incoherent half-user-half-bare-repo state, and a later sync would inherit whatever happened to be there. `clone!` now classifies the target up front: empty (or non-existent) is allowed; an existing clj-p4 clone throws asking the caller to use `sync!`; anything else throws asking the caller to delete the directory or pick a fresh path. The user's prior files are never touched.
- **`repo-state` walks past non-trailer commits at HEAD** — when a user committed on top of a clj-p4 history (e.g., a manual cherry-pick or rebase), `:last-change` came back nil even though the chain below carried valid `[git-p4: ... change = N]` trailers, and `sync!` would then re-import the entire history from change 0. The lookup now uses `git log --grep` to find the most recent commit whose message matches the trailer pattern, so manual commits at HEAD no longer hide the resume point.
- **`repo-state`'s `:last-change` now matches only the `git-p4:` trailer** — the previous regex `change\s*=\s*(\d+)` matched any commit message containing those characters, including user prose like "Fixed change = 42 in config". Subsequent `sync!` calls would then skip legitimately new changelists. The regex is now anchored on the formal `[git-p4: ... change = N]` trailer, so unrelated commit text no longer poisons the resume point.
- **Real-p4d clone bugs found in Phase 6 end-to-end testing** — `-Mj` alone gave command-output JSON instead of tagged-record JSON (need `-Mj -ztag` together); `print-bytes!` deadlocked using a `:in :stream` pipe against `p4 print` which doesn't read stdin (now uses one-shot `run!`); the commit trailer pulled `:p4/stream` from the changelist record, which `p4 describe -s` doesn't reliably emit (now sourced from the plan); the marks file at `<target>/../clj-p4.marks` survived `rm -rf <target>` and `--import-marks-if-exists` repopulated marks pointing at deleted SHAs (now lives inside the bare repo dir).

### Out of scope for v0.1

Submit-back-to-p4 (`git → p4`), virtual streams (refused at clone time), task streams, server-side filtering via ephemeral `p4 client -i`, multi-stream-per-repo, labels-as-tags. See the project plan for rationale.
