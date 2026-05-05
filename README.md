# clj-p4

A Perforce-to-Git bridge in Clojure. Reads stream specs, classic depot paths, and changelist history through `p4`, then writes git history through a long-running `git fast-import` subprocess. Output uses the standard `[git-p4: depot-paths = "...": change = N]` commit-trailer convention, so existing tooling that parses these trailers works without modification.

clj-p4 is strictly P4 to Git. It will never issue a Perforce command that mutates depot file state. There is no submit-back, no integrate, no shelve. See [Safety](#safety) for the full allowlist.

## Capabilities

### Public functions

| Function | Purpose |
| --- | --- |
| `clj-p4.api/available?` | Returns true if `p4` is on `PATH` and runs. |
| `clj-p4.api/clone?` | Returns true if a target directory looks like a clj-p4 clone. |
| `clj-p4.api/clone!` | Clone one or more sources into a fresh bare repo. |
| `clj-p4.api/fetch!` | Pull new changelists from the depot and append them to an existing clone. Append-only, no merge, no submit-back — semantically `git fetch`, not `git pull`. |
| `clj-p4.api/repo-state` | Returns `{:target :head-sha :commit-count :last-change}` for an existing clone. |
| `clj-p4.audit/audit-tip` | Compare the git tip's count and total bytes against `p4 sizes` at the matching changelist. |
| `clj-p4.audit/audit-deep!` | Byte-level compare of a sampled subset of commits against `p4 print -k`. |

### Source types

| Source | Mechanism |
| --- | --- |
| Mainline / development / release streams | parent-chain merge of stream specs |
| Virtual / task / sparsedev / sparserel streams | auto-managed locked-down ephemeral client |
| Classic non-stream depot paths | auto-managed ephemeral client |

### `clone!` and `fetch!` options

| Option | Effect |
| --- | --- |
| `:conn` | Connection spec (see below). Required. |
| `:target` | Absolute path for the bare repo. Required. |
| `:source` | Single depot path or stream. One of `:source` / `:sources` is required. |
| `:sources` | Vector of depot paths cloned into one repo, each on its own ref, sharing one marks file. |
| `:source->ref` | Per-source ref override map. Default ref derives from the source's last path segment (`//stream/dev` ⇒ `refs/heads/dev`). |
| `:ref` | Single-source destination ref. Default `refs/heads/main`. |
| `:max-changes` | Cap on number of changelists imported. *(`clone!` only.)* |
| `:exclude` | Vector of `[pattern regex]` filtering files at clone time. |
| `:fetch-parallelism` | Parallel `p4 print` workers per changelist (`pmap`). |
| `:max-print-bytes` | Per-file `p4 print` cap; throws `:clj-p4/error :max-print-bytes-exceeded` above. |
| `:lookahead` | Background `p4 describe` futures prefetching upcoming changelists. |
| `:no-merge?` | Disable integrate-as-2-parent merge detection. |
| `:keep-empty-commits?` | Default `false` — skip the commit when a CL produces zero file ops after view + `:exclude` filtering. Set to `true` to emit empty commits. |
| `:changes-block-size` | Walk `p4 changes` in fixed-size CL windows. Required when the user is in a group with `MaxResults` / `MaxScanRows` low enough to refuse an unbounded query. |
| `:metadata-decoding-strategy` | `:strict` (default), `:fallback`, or `:passthrough`. Controls how marshal/JSON byte payloads (descriptions, usernames) decode into strings. Only matters against non-unicode-mode p4d. |
| `:metadata-fallback-encoding` | Charset name (default `"CP1252"`). Used by the `:fallback` strategy when UTF-8 decoding fails. |
| `:user-map` | `{<p4-user> {:name :email}}` for the git author/committer line. Users absent from the map fall back to `<user>@perforce`. |
| `:emit-labels?` | Walk `p4 labels` after import and emit annotated git tags for labels whose `Revision:` resolves to an imported changelist. *(`clone!` only.)* |
| `:progress-fn` | `(fn [op])` invoked before each emitted operation. |
| `:stop?` | `(fn [])` abort predicate. |

### Connection spec keys

| Key | Effect |
| --- | --- |
| `:p4/port` | Server endpoint, e.g. `"ssl:server:1666"`. Required. |
| `:p4/user` | Perforce user. |
| `:p4/client` | Workspace name (when calling against an existing client). |
| `:p4/ticket` | Authentication ticket. |
| `:p4/charset` | Character set keyword for `i18n` servers. |
| `:p4/timeout-ms` | Per-call timeout. |
| `:p4/retries` | Retries on transient network or server failure. |
| `:p4/retry-backoff-ms` | Backoff between retries. |

### `audit-tip` and `audit-deep!` options

| Option | Effect |
| --- | --- |
| `:conn` | Connection spec. Required. |
| `:target` | Existing clj-p4 clone. Required. |
| `:source` | Depot path the clone was made from. Required. |
| `:ref` | Branch to validate. Default `refs/heads/main`. |
| `:sample` | Number of commits to sample, or `:all`. Default `10`. *(`audit-deep!` only.)* |

### Output

| Artefact | Notes |
| --- | --- |
| Commit trailer | `[git-p4: depot-paths = "<source>/": change = N]` on every commit. |
| Marks file | `<target>/clj-p4.marks` survives across runs; `fetch!` reads it to know where to resume. |
| Move pairs | Emitted as fast-import `R old new` followed by an `M` op so move + edit in the same changelist preserves content. |
| Integrate-as-merge | When a strict majority of integrate files in a changelist share one source change, the commit gains that source as a 2nd parent. |
| Wire format | `-Mj -ztag` (JSON) on Perforce server `≥ 2024.1`, falling back to `-G` (Python marshal) on older servers. Selected automatically. |
| Existing target | `clone!` refuses to overlay a non-empty target. Call `fetch!` instead, or remove the target first. |

## Quick start

```clojure
(require '[clj-p4.api :as clj-p4])

;; Stream clone.
(clj-p4/clone! {:conn   {:p4/port "ssl:server:1666"
                         :p4/user "alice"
                         :p4/ticket "..."
                         :p4/retries 3}
                :source "//stream/main"
                :target "/tmp/main.git"
                :fetch-parallelism 8
                :lookahead 4
                :max-print-bytes (* 100 1024 1024)})

;; Classic depot path. Same call shape, no stream needed.
(clj-p4/clone! {:conn   {:p4/port "ssl:server:1666" :p4/user "alice" ...}
                :source "//depot/legacy/src"
                :target "/tmp/legacy.git"
                :user-map {"alice" {:name "Alice Engineer"
                                    :email "alice@example.com"}}
                :emit-labels? true})

;; Multi-source. Two streams in one repo, each on its own ref.
(clj-p4/clone! {:conn ...
                :sources ["//stream/main" "//stream/dev"]
                :target  "/tmp/repo.git"})
;; refs/heads/main and refs/heads/dev share a marks file, so
;; integrate-as-merge parents cross stream boundaries.

;; Resume after upstream changes. Append-only — semantically `git fetch`,
;; not `git pull`. Talks to the configured Perforce depot, not a git remote.
(clj-p4/fetch! {:conn ... :source "//stream/main" :target "/tmp/main.git"})

;; Sanity-check at tip (cheap).
(require '[clj-p4.audit :as a])
(a/audit-tip {:conn ... :target "/tmp/main.git" :source "//stream/main"})
;; => {:ok? true :change 12345 :git {...} :p4 {...}}

;; Byte-level deep audit. Expensive; sample a subset of commits.
(a/audit-deep! {:conn ... :target "/tmp/main.git" :source "//stream/main"
                :sample 10})
;; => {:ok? true :commits-checked 10 :files-checked 4321}
;; or {:ok? false :divergence {:commit ... :path ... :git-sha ... :p4-sha ...} ...}
```

## Safety

clj-p4 only reads from Perforce. The codebase contains no write-direction commands; `src/` has no matches for `submit`, `edit`, `add`, `delete`, `integrate`, `merge`, `resolve`, `revert`, `lock`, `unlock`, `shelve`, `unshelve`, `obliterate`, `populate`.

Every `p4` invocation also passes through a runtime allowlist. Anything else throws `:clj-p4/error :write-direction-refused` before the subprocess starts:

| Subcommand | Notes |
| --- | --- |
| `info` | Server identification and capability detection. |
| `streams`, `stream -o` | List streams; read one stream spec. |
| `changes`, `describe`, `print` | Walk changelists, fetch file content. |
| `files`, `fstat`, `dirs`, `sizes`, `where` | Read file metadata. |
| `integrated` | Read integration history (used by integrate-as-merge detection). |
| `labels`, `label -o` | Read labels (used by `:emit-labels?`). |
| `users`, `user -o` | Read user records. |
| `protects` | Read protections table. |
| `counter` | Read the server-wide head changelist counter (used by block-mode head probe, not subject to `MaxResults`). |
| `client -o` / `-i` / `-d` / `-d -f` | Metadata-only writes. Required to create the ephemeral client used for virtual streams and classic-depot clones; `-d -f` force-deletes the import-locked client on cleanup. Never touches depot file state. |

Ephemeral clients are created with `Options: noallwrite noclobber locked`, so the server refuses any depot-mutating operation routed through them. The client root is a scratch path under `/tmp/clj-p4-eph-<uuid>`. Each client is deleted in a `finally` block, whether the run succeeds or fails. `client -i` and `client -d` are the only allowed metadata writes; they modify a server-side client spec, not depot files.

## Naming conventions

Vocabulary in this codebase follows a gradient: Git terms closer to the user/API, Perforce terms closer to the Perforce side, neutral terms in the middle. The public API speaks Git (`clone!`, `fetch!`, `:head-sha`, `:commit-count`) because the people using clj-p4 are git-users importing from Perforce — that's the language they already think in. The Perforce-side internals (`clj-p4.io.p4`, `clj-p4.marshal`, `clj-p4.records`, `clj-p4.view`, `clj-p4.depot-path`) speak Perforce — `marshal` is the wire format p4 calls it; `view` is the P4 word for a depot-to-local mapping spec; `:last-change` is a P4 changelist number, not a git commit. The middle layers (`clj-p4.plan`, `clj-p4.runner`, `clj-p4.audit`, `clj-p4.schemas`, `clj-p4.predicates`, `clj-p4.excludes`, `clj-p4.io.subprocess`) use neutral Clojure-idiomatic names because forcing either side's vocabulary onto generic plumbing makes it harder, not easier, to read.

A consequence: the API exposes `fetch!` while the internal plan-builder is called `sync-plan`. That is intentional — `p4 sync` is the P4 verb for "bring a workspace up to date with the depot," and the plan layer sits closer to the P4 side. The same operation crosses the boundary, but each side names it in its native vocabulary.

## Inspiration

- [`git-p4`](https://git-scm.com/docs/git-p4): the `git fast-import` writing protocol and the `[git-p4: ...]` commit-trailer convention.
- [`p4-fusion`](https://github.com/salesforce/p4-fusion): parallel-fetch design and the read-only scope.
- [Helix Core P4 Git Connector](https://www.perforce.com/products/helix-core-git-connector): Perforce's reference for stream-aware ingestion.

## License

MIT. See [LICENSE](LICENSE).
