# Changelog

## Unreleased

### Removed

- Babashka and ClojureScript runtime support. Source files are now `.clj`; `bb.edn` and `shadow-cljs.edn` are gone, along with the `:cljs` deps alias.
- `:stream`, `:streams`, `:stream->ref` aliases on `clone!` / `sync!`. Use `:source`, `:sources`, `:source->ref`.
- `docs/` directory (comparison and migration markdown).
- `bench/` directory (clone-comparison Babashka script).

## 0.16.0-alpha

### Added

- `docs/comparison.md` — clj-p4 vs. p4-fusion feature table.
- `docs/migrating-from-git-p4.md`, `docs/migrating-from-p4-fusion.md` — migration guides.
- `bench/clone.bb` — local benchmark script comparing clone wall-clock and peak RSS across `git-p4`, `p4-fusion`, and `clj-p4`.
- `bin/validate` — CLI wrapper around `validate-tip` and `validate-deep!`.

## 0.15.0-alpha

### Changed

- `:source` / `:sources` / `:source->ref` are the new names on `clone!` and `sync!`. `:clj-p4/error :stream-and-streams-set` is now `:source-and-sources-set`.

### Deprecated

- `:stream`, `:streams`, `:stream->ref` still accepted; each call emits one stderr deprecation line.

## 0.14.0-alpha

### Added

- `clj-p4.validate/validate-deep!` — walks each commit's tree and compares each file's bytes against `p4 print -k` of the source depot path at the matching changelist. `:sample N` (default 10) selects an evenly-spaced subset; `:sample :all` checks every commit. Returns on first divergence with `{:commit :path :change :git-sha :p4-sha :git-bytes :p4-bytes}`.

## 0.13.0-alpha

### Added

- `task`, `sparsedev`, `sparserel` recognised as `:stream/type` values by `parse-stream-spec`.

### Removed

- Unused `virtual-leaf?` helper in `clj-p4.api`.

## 0.12.0-alpha

### Added

- `clj-p4.shell.p4/with-stream-client-or-fallback` — like `with-ephemeral-client` but invokes a fallback when client creation is denied.

### Changed

- All stream clones route through an ephemeral client by default; the pure-data parent-chain merge is the fallback. `Components:` resolution now works for any stream that uses it.

## 0.11.0-alpha

### Added

- `:sources [s1 s2 ...]` and `:source->ref` on `clone!` / `sync!`. Each source clones into its own ref in one bare repo. Default ref is `refs/heads/<basename>`. Marks file is shared, so cross-source merge parents emit correctly.

## 0.10.0-alpha

### Added

- Integrate-as-2-parent merge detection. When a changelist has integrate files and a strict majority share a single source change in the imported set, the commit is emitted with that source as a 2nd parent.
- `:no-merge?` on `clone!` / `sync!` disables merge detection.
- `clj-p4.shell.p4/integrated` and `clj-p4.shell.p4/fstat`.

## 0.9.0-alpha

### Added

- `:lookahead N` on `clone!` / `sync!`. Up to N background `p4 describe` futures prefetch upcoming changelists.

### Changed

- `plan/operation-seq` `:process-change` ops carry `:op/idx`.

## 0.8.0-alpha

### Changed

- README expanded with a Quick start and safety surface; allowlist documentation refreshed for `sizes`, `labels`, `label -o`.

## 0.7.0-alpha

### Added

- `clj-p4.validate/validate-tip` — compares the git tip's tree count and total bytes against `p4 sizes -as <source>/...@<change>`. Returns `{:ok? :git :p4 :change}`.
- `clj-p4.shell.p4/sizes-summary`.

## 0.6.0-alpha

### Added

- `:user-map {<p4-user> {:name :email}}` on `clone!` / `sync!` — controls the git author/committer.
- `:emit-labels?` on `clone!` / `sync!` — walks `p4 labels` after import and creates an annotated git tag for any label whose `Revision:` resolves to an imported changelist.
- `clj-p4.shell.git/emit-tag!`, `clj-p4.shell.p4/labels`, `clj-p4.shell.p4/label-spec`.

## 0.5.0-alpha

### Added

- `:fetch-parallelism N` on `clone!` / `sync!`. Per-changelist `p4 print` calls run on N parallel workers via `pmap`.
- `:max-print-bytes N` on `clone!` / `sync!`. Hard cap on per-file `p4 print` size; throws `:clj-p4/error :max-print-bytes-exceeded`.

### Changed

- Executor split into three explicit phases per changelist: `materialize-ops` (pure), `fetch-blobs!` (parallel), `emit-ops!` (serial).

### Fixed

- Move + edit in the same changelist no longer drops the new content. The executor emits `R old new` followed by `M mode mark new`.

## 0.4.0-alpha

### Added

- Move-pair grouping. A `move/delete` + `move/add` pair within the same changelist emits a single fast-import `R old new`. `parse-describe` extracts the `movedFile<n>` field from `p4 describe`.

## 0.3.0-alpha

### Added

- Classic (non-stream) depot path support on `clone!` / `sync!`. Routed through a generic ephemeral client.
- `clj-p4.shell.p4/with-classic-client` and `create-classic-client!`.
- `:p4/retries N` and `:p4/retry-backoff-ms M` on the connection spec, plus the same options on `proc/run!`.

## 0.2.0-alpha

### Added

- Virtual stream support on `clone!` / `sync!`. Routed through an auto-managed locked-down ephemeral client; the trailer keeps the user-visible stream name.
- `clj-p4.shell.p4/with-ephemeral-client`, `create-stream-client!`, `delete-stream-client!`.
- `clj-p4.view/client-view->view` parses the auto-generated client `View:` block.

### Changed

- Hard read-only allowlist on every `p4` invocation. Anything outside `info`, `streams`, `stream -o`, `changes`, `describe`, `print`, `files`, `fstat`, `integrated`, `labels`, `label -o`, `users`, `user -o`, `protects`, `where`, `dirs`, `sizes`, plus `client -o` / `-i` / `-d` throws `:clj-p4/error :write-direction-refused`.

## 0.1.0-alpha

### Added

- `clj-p4.api`: `clone!`, `sync!`, `repo-state`, `available?`, `clone?`.
- Stream-aware view composition: parent-chain inheritance, `Paths:` / `Remapped:` / `Ignored:`, glob compilation, depot → local-path mapping.
- `git fast-import` writer emitting `[git-p4: depot-paths = "<source>/": change = N]` trailers.
- `-Mj -ztag` (JSON) on server ≥ 2024.1, falling back to `-G` (Python marshal).
- Resume after crash via `git fast-import` marks file at `<target>/clj-p4.marks`.
- Docker-based integration fixture under `test/fixtures/p4d/`.

### Fixed

- `depot-path?` rejects `...` and `*` in non-final segments.
- `stream-chain` detects cyclic Parent metadata.
- `clone!` refuses to overlay an existing non-empty target.
- `repo-state` walks past non-trailer commits at HEAD; `:last-change` regex anchors on the `[git-p4: ... change = N]` trailer.
