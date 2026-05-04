# Changelog

## Unreleased

### Added

- `clj-p4.schema` — malli schemas for the parser-output domain records
  (`changelist-record`, `file-rev`, `stream-spec`, `server-info`),
  `connection-spec`, and the public `clone!` / `sync!` option maps.
  Also exports `record-transformer` for declarative wire-format
  coercion (string→long, string→keyword, epoch-seconds→ms,
  `\"enabled\"`→boolean). Distinct from `clj-p4.spec`, which keeps its
  hand-rolled depot-path predicates.

### Fixed

- `:fetch-parallelism` now actually bounds concurrency. The two parallel
  fetch sites in `clj-p4.execute` (per-changelist `p4 print` and
  merge-source `p4 integrated`/`p4 fstat` lookups) previously used
  `pmap`, whose parallelism is hardcoded to `(+ 2 ncpus)` regardless of
  the configured value. Replaced with a `bounded-pmap` helper backed by
  `core.async/pipeline-blocking` — ordered, bounded, and uses dedicated
  worker threads suitable for blocking I/O.

## 0.1.0-alpha

First tagged release. Pre-alpha: the public API will move; pin a SHA, not
a version. Repo stays private until the API has stabilised and the t98xx
port suite catches the obvious P4-server-version variance.

### Added

- `clj-p4.api`: `clone!`, `sync!`, `repo-state`, `available?`, `clone?`.
- `:source`, `:sources`, `:source->ref` on `clone!` / `sync!`. `:source`
  is a single source path; `:sources` is a vector of paths cloned into
  the same bare repo, each on its own ref. Default ref derives from the
  source's last path segment (`//stream/main` → `refs/heads/main`);
  override per-source with `:source->ref`. The marks file is shared, so
  cross-source merge parents emit correctly.
- Stream-aware view composition: parent-chain inheritance, `Paths:` /
  `Remapped:` / `Ignored:`, glob compilation, depot → local-path
  mapping. `task`, `sparsedev`, `sparserel` recognised alongside the
  standard stream types.
- Virtual stream support, routed through an auto-managed locked-down
  ephemeral client (`Options: noallwrite noclobber locked`). The
  `git-p4:` trailer keeps the user-supplied source path, not the
  ephemeral client name.
- Classic (non-stream) depot path support, routed through a generic
  ephemeral client.
- Ephemeral-first dispatch: all stream clones route through an
  ephemeral client by default; the pure-data parent-chain merge is the
  fallback. `Components:` resolution works for any stream that uses it.
- Hard read-only allowlist on every `p4` invocation. Anything outside
  `info`, `streams`, `stream -o`, `changes`, `describe`, `print`,
  `files`, `fstat`, `integrated`, `labels`, `label -o`, `users`,
  `user -o`, `protects`, `where`, `dirs`, `sizes`, plus `client -o` /
  `-i` / `-d` throws `:clj-p4/error :write-direction-refused`.
- `git fast-import` writer emitting
  `[git-p4: depot-paths = "<source>/": change = N]` trailers.
- `-Mj -ztag` (JSON) wire format on server ≥ 2024.1, falling back to
  `-G` (Python marshal).
- Resume after crash via `git fast-import` marks file at
  `<target>/clj-p4.marks`.
- Move-pair grouping. A `move/delete` + `move/add` pair within the same
  changelist emits a single fast-import `R old new`.
- Integrate-as-2-parent merge detection. When a changelist has integrate
  files and a strict majority share a single source change in the
  imported set, the commit is emitted with that source as a 2nd parent.
  `:no-merge?` on `clone!` / `sync!` disables.
- `:fetch-parallelism N` — per-changelist `p4 print` calls run on N
  parallel workers via `pmap`.
- `:max-print-bytes N` — hard cap on per-file `p4 print` size; throws
  `:clj-p4/error :max-print-bytes-exceeded`.
- `:lookahead N` — up to N background `p4 describe` futures prefetch
  upcoming changelists.
- `:user-map {<p4-user> {:name :email}}` — controls the git
  author/committer.
- `:emit-labels?` — walks `p4 labels` after import and creates an
  annotated git tag for any label whose `Revision:` resolves to an
  imported changelist.
- `:p4/retries N`, `:p4/retry-backoff-ms M`, and `:p4/timeout-ms` on
  the connection spec.
- `clj-p4.validate/validate-tip` — compares the git tip's tree count
  and total bytes against `p4 sizes -as <source>/...@<change>`. Returns
  `{:ok? :git :p4 :change}`.
- `clj-p4.validate/validate-deep!` — walks each commit's tree and
  compares each file's bytes against `p4 print -k` of the source depot
  path at the matching changelist. `:sample N` (default 10) selects an
  evenly-spaced subset; `:sample :all` checks every commit. Returns on
  first divergence with
  `{:commit :path :change :git-sha :p4-sha :git-bytes :p4-bytes}`.
- Docker-based integration fixture under `test/fixtures/p4d/`.

### Fixed

- `depot-path?` rejects `...` and `*` in non-final segments.
- `stream-chain` detects cyclic `Parent` metadata.
- `clone!` refuses to overlay an existing non-empty target.
- `repo-state` walks past non-trailer commits at HEAD; `:last-change`
  regex anchors on the `[git-p4: ... change = N]` trailer.
- Move + edit in the same changelist no longer drops the new content.
  The executor emits `R old new` followed by `M mode mark new`.
