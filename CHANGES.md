# Changelog

## Unreleased

### Changed (breaking)

- `:extra-excludes` renamed to `:excludes`. Pairs symmetrically with
  `:includes` and drops the now-misleading "extra" qualifier.
- `:includes` semantics changed. It used to remove pattern strings from
  the union of `:exclude-categories ∪ :extra-excludes` by exact string
  equality, which made it useless for path-level carve-outs. It is now
  a re-include carve-out evaluated at match time against each candidate
  path: a path is filtered out iff some `:excludes` pattern matches AND
  no `:includes` pattern matches (gitignore-style set difference).
  `:exclude-categories :all` + `:includes ["*.psd"]` still keeps PSDs
  (any path matching `*.psd` re-includes); the new shape additionally
  enables path-level use like `:excludes ["Content/"] :includes
  ["Content/keep/"]` to drop one subtree while keeping a nested
  carve-out.
- The low-level pre-compiled `:exclude` escape hatch (a vector of
  `[pattern regex]` pairs the caller built via
  `clj-p4.excludes/compile-patterns`) has been removed. The high-level
  string-pattern keys cover every reasonable use case; clj-p4 owns
  pattern compilation. The `:exclude-and-high-level-set` boundary
  check is gone.
- `clj-p4.excludes/exclude-patterns` now returns
  `{:excludes [str…] :includes [str…]}` instead of a single deduped
  vector with includes-removed.

## 0.5.1-alpha

Hardening pass driven by an adversarial-test sweep. Eleven boundary
fixes across the public API, the audit harness, the excludes layer,
the depot-path decoder, and the runner. Behaviour-preserving for the
happy path; previously-silent typos, malformed inputs, and exception
swallowing now fail loud at the boundary with `:clj-p4/error
:invalid-options` (or a more specific tag) instead of corrupting an
import or hiding a real bug.

### Fixed

- `:source->ref` keys are validated as depot paths. The schema previously
  allowed any string, so an empty / typo'd key silently failed to match
  any `:sources` entry and the default ref derivation kicked in. Now
  raises `:clj-p4/error :invalid-options` at the boundary, matching the
  treatment `:source` and `:sources` already get.
- `clj-p4.depot-path/unescape` rejects decoded paths containing control
  bytes (`< 0x20` or `0x7F`) with `:clj-p4/error :invalid-depot-path-byte`.
  Real P4 paths never contain control characters; rejecting them at the
  unescape boundary stops a hostile or corrupted server from smuggling
  NULs (or other garbage) into the `git fast-import` stream.
- `clj-p4.runner/merge-source-for-cl` and `emit-labels!` no longer
  swallow arbitrary exceptions during `p4 integrated` / `p4 fstat` /
  `p4 labels` / `p4 label -o` lookups. Only `:clj-p4/error :proc-failed`
  ex-info — the documented "transient subprocess failure" class — is
  silently downgraded; everything else (parser bugs, `NullPointerException`,
  unexpected exception classes) now propagates to the caller. Same
  behaviour for the common case, but real bugs no longer hide behind
  merge-detection / label-import silence.
- `clj-p4.excludes/pattern->re` rejects the degenerate `""` and `"/"`
  patterns with `:clj-p4/error :invalid-pattern` instead of crashing
  with a JVM `StringIndexOutOfBoundsException`.
- `clone!` / `fetch!` validate `:source` and `:sources` entries against
  `clj-p4.predicates/depot-path?`. The schema previously accepted any
  string, so `""` and `"//"` slipped through and produced malformed
  refs (`refs/heads/`) and trailers (`depot-paths = "/"`). Now raises
  `:clj-p4/error :invalid-options` at the boundary.
- `clone!` / `fetch!` reject `:sources` whose entries resolve to the
  same git ref. Two sources with the same basename (e.g.
  `["//d/main" "//e/main"]`) both default to `refs/heads/main` and the
  second `clone-one!` would silently clobber the first. Now raises
  `:clj-p4/error :colliding-source-refs` with the offending ref(s) and a
  hint to disambiguate via `:source->ref`. Explicit `:source->ref` entries
  participate in the check, so two sources mapped to the same ref still
  collide.
- `clone!` / `fetch!` reject `:sources` containing duplicate entries
  with `:clj-p4/error :duplicate-sources`. Previously a duplicated source
  ran `clone-one!` / `fetch-one!` twice on the same source into the same
  ref, with undefined results.
- `clone!` / `fetch!` reject `:sources []` (an empty collection). The
  previous `(or source sources)` predicate counted `[]` as truthy, so
  the call returned `[]` having done no work — the user thought they
  had cloned. The check is now `(or source (seq sources))`, raising the
  existing `:clj-p4/error :no-source` instead.
- `clone!` / `fetch!` reject `:exclude-categories` keywords that aren't
  in `clj-p4.excludes/binary-categories`. A typo (e.g. `:image` for
  `:images`) used to silently produce zero patterns, leaving the user
  with an unfiltered import while believing they had filtering on. Now
  raises `:clj-p4/error :unknown-exclude-category` with the offending
  keys and the valid set.
- `clj-p4.audit/audit-tip` and `clj-p4.audit/audit-deep!` now validate
  their option maps against malli schemas (`audit-tip-options`,
  `audit-deep-options`) before any I/O. Missing `:conn`, missing /
  malformed `:target`, non-depot-path `:source`, and the
  previously-unguarded `:sample` values all raise
  `:clj-p4/error :invalid-options` with a humanized message — the same
  pattern `clone!` / `fetch!` already use. Subsumes the standalone
  `:sample` runtime check added earlier this section.

## 0.5.0-alpha

Built-in binary filtering. clj-p4 has no Git LFS support, and plain
Git handles binaries badly — no delta compression, repo bloat, slow
clones, history-rewrite hazards. Pulling raw binaries out of a
binary-heavy Perforce depot into git is rarely what users want, and
every clj-p4 user importing one was reinventing the same exclude
list. This release ships that list inside clj-p4 itself, plus a
type-based catch-all that consults Perforce's own per-revision file
type so unknown extensions are caught for free.

### Breaking

- **`clone!` and `fetch!` now drop binary-typed revisions by default.**
  Any revision whose Perforce `:rev/type` is `:binary`, `:apple`, or
  `:resource` is filtered out before `p4 print` runs. Pass
  `:exclude-binaries? false` to restore the previous (let-everything-
  through) behaviour. The type comes from `p4 describe -s` already
  parsed by `clj-p4.records/parse-file-type`, so no additional P4
  call is required.

### Added

- `clone!` / `fetch!` accept `:exclude-binaries?` (default `true`).
  See the breaking-change note above.
- `clone!` / `fetch!` accept `:exclude-categories` — `:all` or a set
  of category keywords (`:images`, `:audio`, `:video`, `:models`,
  `:archives`, `:fonts`, `:documents`, `:compiled`, `:engine-assets`)
  selecting from a built-in `binaries.edn` resource. Compiled
  internally.
- `clone!` / `fetch!` accept `:extra-excludes` and `:includes` (seq
  of pattern strings) — same gitignore-flavoured grammar as the
  built-in patterns. Compose with `:exclude-categories` to add or
  whitelist on top of a category selection; usable standalone for
  ad-hoc patterns. The pre-compiled `:exclude` is mutually exclusive
  with all three high-level pattern keys, throwing when both shapes
  are set.
- `clj-p4.excludes/binary-rev?` and `clj-p4.excludes/binary-rev-types`
  — public predicate and the source set of base types it consults.
  Designed for use as a runner `:exclude-fn`, but composable anywhere
  a FileRev predicate is useful.
- `clj-p4.excludes/exclude-patterns` accepts a new `:categories` key
  for selecting from clj-p4's built-in resource (or from a
  user-supplied `:resource` map, which still takes precedence).
- `resources/clj_p4/excludes/binaries.edn` — nine generic categories
  totalling ~77 patterns. Curation principle: an extension only
  appears here if it is *always* a binary container, regardless of
  variant. Source-form text and config (`.svg` XML, `.gltf` JSON,
  `.dae` COLLADA, `.obj` text Wavefront, Unity scene/prefab/asset
  YAML, Unreal `.upluginmanifest` JSON) is intentionally omitted —
  those are code, not assets, and the type filter catches their
  binary cousins (`.glb`, etc.) automatically.

### Changed

- `merge-source-for-cl` now pre-filters `:integrate` files through
  the same view + path-pattern + `:exclude-fn` check as
  `materialize-ops` before issuing per-file `p4 integrated` /
  `p4 fstat` lookups. A changelist whose integrates are all excluded
  (e.g. a binary asset re-integration) costs zero merge-source RPCs
  — and the merge-parent decision now considers only files that
  survive into git, which is the right semantics anyway.

## 0.4.0-alpha

Vocabulary alignment + structural cleanup. The codebase now follows a
deliberate gradient: Git terms at the public API, Perforce terms on the
P4-side internals, neutral terms in the middle. The directory layout
flattens the topical `parse/` sub-dir, renames `shell/` to `io/` (the
Clojure-idiomatic name for the I/O boundary), and gives every namespace
a noun-shaped name. No backwards-compatibility shims — pin the previous
tag if you need the old names.

### Breaking

- **`sync!` is now `fetch!`** at the public API. Semantics are
  unchanged (incremental, append-only history pull from the depot — no
  merge, no working tree, no submit-back). The new name matches `git
  fetch` more accurately and avoids collision with `p4 sync`. The
  result key `:synced N` is now `:fetched N`. The starting point is
  still the `[git-p4: ... change = N]` trailer on the most recent
  commit reachable from `ref`.
- **`clj-p4.validate` → `clj-p4.audit`** with `validate-tip` →
  `audit-tip` and `validate-deep!` → `audit-deep!`. Behaviour
  unchanged.
- **Namespace renames** (file paths follow):
  | Old | New |
  | --- | --- |
  | `clj-p4.execute` | `clj-p4.runner` |
  | `clj-p4.validate` | `clj-p4.audit` |
  | `clj-p4.exclude` | `clj-p4.excludes` |
  | `clj-p4.schema` | `clj-p4.schemas` |
  | `clj-p4.spec` | `clj-p4.predicates` |
  | `clj-p4.parse.marshal` | `clj-p4.marshal` |
  | `clj-p4.parse.semantic` | `clj-p4.records` |
  | `clj-p4.parse.depot-path` | `clj-p4.depot-path` |
  | `clj-p4.shell.proc` | `clj-p4.io.subprocess` |
  | `clj-p4.shell.p4` | `clj-p4.io.p4` |
  | `clj-p4.shell.git` | `clj-p4.io.git` |
  Unchanged: `clj-p4.api`, `clj-p4.plan`, `clj-p4.view`.
- `clj-p4.schemas/sync-options` → `clj-p4.schemas/fetch-options`.

### Changed

- README has a new "Naming conventions" section explaining the gradient
  (Git ↔ neutral ↔ Perforce) and why the API exposes `fetch!` while the
  internal plan-builder is still called `sync-plan`.

## 0.3.0-alpha

Third tagged release. Closes the read-side `t98xx` port roadmap from
`git-p4`'s regression suite — every read-direction scenario that's not
a deliberate architectural divergence (CLI shapes, branch detection)
or a feature gap clj-p4 hasn't exposed yet (jobs, unshelve) now has
integration coverage. Also surfaces and fixes a long-standing bug
class around merge-parent detection. Same pre-alpha caveat applies —
the public API will continue to move; pin a SHA, not a version.

### Added

- `clone!` / `sync!` accept `:keep-empty-commits?` (default `false`).
  Mirrors git-p4's `--keep-empty-commits`: when a changelist's only
  files are filtered out by view or `:exclude`, the default is now to
  *skip* the resulting empty commit so the imported history matches
  the touching CLs. Set to `true` to preserve every CL as a commit
  regardless of its post-filter file count.
- `clone!` / `sync!` accept `:changes-block-size` (positive int, no
  default — present means block-mode). Mirrors git-p4's
  `--changes-block-size`: walks `p4 changes` in fixed-size changelist
  windows so each individual call returns at most N records, staying
  under per-group `MaxResults` / `MaxScanRows` server limits. The
  head probe uses `p4 counter change` (not `p4 changes -m 1`) so it
  isn't itself bound by MaxResults.
- `clj-p4.shell.p4/changes-blocked` — companion to `changes` that walks
  `[since+1, +block-size]`, `[+1, +2*block-size]`, … windows up to
  the server head. Aggregate is identical to an unbounded `changes`
  call; intended purely as a fetch strategy for restricted users.
- `counter` is now on the `p4` read-only allowlist (was missing).
- `clone!` / `sync!` accept `:metadata-decoding-strategy` (default
  `:strict`) and `:metadata-fallback-encoding` (default `"CP1252"`).
  Mirror of git-p4's `metadataDecodingStrategy` / `metadataFallbackEncoding`:
  control how the importer decodes byte payloads coming back from p4
  (descriptions, usernames, etc.) into strings.
  - `:strict` — UTF-8 only; throw `ex-info :metadata-decode-failed`
    on malformed bytes.
  - `:fallback` — try UTF-8; fall back to `:metadata-fallback-encoding`
    on failure (CP-1252 by default).
  - `:passthrough` — decode as ISO-8859-1 (every byte → one char);
    never throws.
  Required to clone non-unicode-mode p4 servers whose metadata may
  carry CP-1252, Latin-1, or mixed bytes.
- `clj-p4.parse.depot-path/unescape` — decode `%XX` percent-escapes in
  Perforce depot paths back to literal characters (`%40`→`@`, `%23`→`#`,
  `%2A`→`*`, `%25`→`%`, plus any other `%XX`). Applied automatically by
  the importer when materialising local paths so filenames containing
  the four P4-reserved wildcard characters round-trip into git intact.

### Changed

- **Breaking:** `clone!` / `sync!` now skip empty commits by default
  (matches upstream git-p4). Callers that relied on every CL becoming
  a commit even when filtered to zero files must pass
  `:keep-empty-commits? true` to restore the previous behaviour.
- `init-bare!` forces `core.ignorecase=false` on the new repo. Without
  this, hosts whose filesystem auto-sets `ignorecase=true` (notably
  macOS/APFS) silently merged case-only sibling paths in the imported
  tree, producing wrong output. The bare repo has no working tree, so
  there's no upside to the auto-detection.

### Fixed

- `clj-p4.shell.p4/integrated` previously called `p4 integrated -F
  change=N <to-file>`; that flag isn't valid on `p4 integrated` (only
  on `fstat` / `sizes`) and the call always failed with empty stderr,
  silently disabling merge-parent detection on every multi-source
  clone since this code was written. Replaced with an unfiltered call
  + clojure-side `:integ/change` filter. Rev fields like `endFromRev`
  also come back as `#2` in `-ztag` mode, not `2`; added a `parse-rev`
  helper that strips the leading `#`.
- Multi-source `clone!` now threads a *cross-source* `:already-imported`
  set (read from the shared marks file) into the executor's `imported?`
  predicate. Without this, `merge-source-for-cl` never saw the other
  source's CLs as imported and 2-parent commits were never emitted
  across sources.
- Symlink blob bytes have any trailing `\n` (or `\r\n`) stripped before
  being written to git. p4 ships symlink content with a newline
  terminator; git's symlink convention is for the blob content to be
  exactly the target path.
- Ephemeral clients are now torn down with `client -d -f`. The
  importer creates them with `Options: locked` so the server itself
  refuses depot mutations during import; without `-f`, the matching
  delete refused too and clients accumulated on the server.

## 0.2.0-alpha

Second tagged release. Adds boundary validation and parser coercion via
malli, and replaces the `pmap`-based fan-out with a bounded
`core.async/pipeline-blocking` helper that actually honours
`:fetch-parallelism`. Same pre-alpha caveat applies — the public API
will continue to move; pin a SHA, not a version.

### Changed

- `clj-p4.parse.semantic` delegates value-level coercion (string→long
  for `:p4/change` / `:rev/rev` / `:rev/size`; string→keyword for
  `:rev/action` / `:p4/status` / `:stream/type` / `:p4/case-handling`;
  epoch-seconds→epoch-ms for `:p4/time`; `\"enabled\"`→boolean for
  `:p4/unicode?`) to `clj-p4.schema/record-transformer` via
  `malli.core/decode`. The structural helpers (`indexed-values`,
  `parse-path-line`, `parse-remap-line`, `parse-flags`,
  `parse-file-type`, `file-indices`) remain. Output shape is
  unchanged; tests in `clj-p4.parse.semantic-test` are the regression
  guard.

### Added

- `clone!` and `sync!` validate their options map against the malli
  schemas in `clj-p4.schema` before any side effects. Malformed options
  throw `ex-info` with `:clj-p4/error :invalid-options` and a
  `humanize`d message naming the offending field — e.g.
  `:fetch-parallelism 0` is rejected with the field name in the
  message, instead of silently misbehaving deeper in the pipeline.
- `clj-p4.schema` — malli schemas for the parser-output domain records
  (`changelist-record`, `file-rev`, `stream-spec`, `server-info`),
  `connection-spec`, and the public `clone!` / `sync!` option maps.
  Also exports `record-transformer` for declarative wire-format
  coercion (string→long, string→keyword, epoch-seconds→ms,
  `\"enabled\"`→boolean). Distinct from `clj-p4.spec`, which keeps its
  hand-rolled depot-path predicates.

### Fixed

- `bounded-pmap` short-circuits remaining work after the first
  exception. Previously, a failure mid-changelist would leave workers
  to drain the rest of the input — issuing wasted `p4 print` /
  `p4 fstat` calls before the failure surfaced. Now subsequent items
  skip `f` entirely once the first error is captured.
- Parser preserves the closed-table semantics for `:rev/action`:
  unknown wire tokens (e.g. `"archive"`, `"modify"`) decode to nil
  instead of being smuggled through as a fresh keyword. Restores the
  pre-V3 observability property — if p4 emits a new action, it
  surfaces as an invariant break rather than a silent passthrough.
- Boundary validation tolerates the same shapes the implementation
  always handled gracefully: `:p4/timeout-ms` / `:p4/retry-backoff-ms`
  accept any positive number (not only ints — JSON sources often
  produce doubles); explicit `nil` for any optional option field
  (`:fetch-parallelism`, `:max-changes`, `:max-print-bytes`,
  `:lookahead`, etc.) is treated as absent; and `:user-map` entries
  may carry `nil` for `:name` / `:email`.
- `clone!` / `sync!` `:progress-fn` and `:stop?` accept any `IFn`
  (Vars, keywords, sets, maps, etc.), not only literal `(fn …)`
  values. The implementation just calls them; the schema now matches.
- `clone!` / `sync!` `:sources` accepts any sequential collection of
  strings, not only literal vectors — restores compatibility with the
  common idiom of building sources via `(map …)` / `(filter …)` /
  `(for …)`, whose results are lazy seqs.
- `clone!` / `sync!` boundary accepts any file-coercible `:target` —
  string, `java.io.File`, `java.nio.file.Path`, `java.net.URI`,
  `java.net.URL`, etc. — matching what the implementation already did
  via `(io/file (str target))`. Previously the schema only allowed
  `String` or `File`, which broke callers passing modern `java.nio`
  paths.
- Parser fields typed as longs (`:p4/change`, `:p4/time`, `:rev/rev`,
  `:rev/size`, `:p4/server-version-major` / `-minor`) decode to nil
  when the wire string can't be parsed, instead of leaking the raw
  string into a long-typed field. Restores the pre-V3
  `(when (parse-long s))` semantics that downstream code in
  `clj-p4.execute` depends on.
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
