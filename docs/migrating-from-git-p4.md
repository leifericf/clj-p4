# Migrating from `git-p4` to `clj-p4`

If you have a `git-p4`-shaped clone today and you're considering `clj-p4`, this is the side-by-side. The good news: the on-disk format is compatible — the same `[git-p4: depot-paths = "...": change = N]` commit trailer, same `git fast-import` writing protocol — so an existing `git-p4` clone resumes through `clj-p4 sync!` without re-cloning.

## Configuration mapping

`git-p4` is configured through `git config` keys; `clj-p4` is configured through the options map you pass to `clone!` / `sync!`.

| `git-p4` config | `clj-p4` option | Notes |
| --- | --- | --- |
| (depot path argument) | `:source` | clj-p4 takes the source as a keyword arg, not positional. Streams, classic depots, and virtual streams all work — see below. |
| `--branch=<name>` | `:ref "refs/heads/<name>"` | Default `refs/heads/main` either way. |
| `--changesfile=<path>` | (n/a — derived from `[git-p4:]` trailer) | Resume on `clj-p4` reads the trailer; no separate file. |
| `--max-changes=N` | `:max-changes N` | |
| `--keep-path` | (always preserved) | clj-p4 keeps the depot-relative path under the view. |
| `git-p4.useClientSpec=true` | (auto, since 0.12.0) | clj-p4 always uses a server-resolved auto-view for streams. |
| `git-p4.skipSubmitEdit` | (n/a — read-only) | clj-p4 never submits. See `## Safety` in the README. |
| `git-p4.mapUser` | `:user-map {<p4-user> {:name :email}}` | Same idea, native data structure. |
| `git-p4.attemptRCSCleanup` | (handled per-file via `+k` / `+ko` flags) | `p4 print -k` for keyword-flagged files; bytes round-trip cleanly. |
| `--bare` | (always bare) | `clj-p4` only writes to bare repos. |
| `--detect-labels` | `:emit-labels? true` | clj-p4 walks `p4 labels` after import; tags only the labels whose `Revision:` resolves to an imported CL. |
| `--detect-branches` | (auto via `:source` chain walk + `:sources` for multi-ref) | clj-p4 derives stream parentage from `p4 stream -o` rather than commit-message heuristics. |
| `--use-client-spec` | (auto, since 0.12.0) | Same as above. |

## What `clj-p4` does that `git-p4` does not

- **Stream-aware.** `git-p4` treats stream depot paths as opaque path globs and ignores stream `Paths:` / `Remapped:` / `Ignored:` composition. `clj-p4` walks the parent chain via `p4 stream -o`, honours every stream rule the server applies, and falls through the auto-view (since 0.12.0) so even `Components:` resolution works.
- **Virtual streams without operator effort.** A virtual stream's depot path can't be queried directly; `git-p4` requires you to set up a workspace by hand and clone through it. `clj-p4` creates a locked-down ephemeral workspace (`Options: noallwrite noclobber locked`), runs the import, and tears the workspace down — all in a single call. Same for any depot path the server's auto-view rules touch.
- **Read-only by enforcement, not convention.** `git-p4` happens not to issue write commands; `clj-p4` rejects them at the call site via a runtime allowlist on every `p4` invocation. Cannot submit, edit, integrate, shelve, revert, populate, or obliterate even if a future bug attempted to.
- **Multi-source single repo.** `:sources [...]` clones N streams into one bare repo with a shared marks file, so an integrate detected in `//stream/dev` from `//stream/main` produces a 2-parent merge commit pointing at the right `//stream/main` commit. `git-p4` clones one stream per repo.
- **First-class validation harnesses.** `validate-tip` does a count + total-bytes spot check against `p4 sizes`; `validate-deep!` walks every commit's tree and compares each file's bytes against `p4 print -k`. `git-p4` ships with no equivalent.

## What `git-p4` does that `clj-p4` does not

- **Submit-back to P4** (`git p4 submit`). Permanently out of scope for `clj-p4` — see the runtime allowlist. If you need round-trip git → P4, `clj-p4` is not the right tool.
- **Live in the standard `git` distribution.** `clj-p4` is a separate library; you need a JVM and Clojure tooling. The pure layer also runs under Babashka and ClojureScript-Node, but the import itself is JVM-only.

## Resuming an existing `git-p4` clone with `clj-p4`

The trailer is identical, so `clj-p4 sync!` against an existing `git-p4` clone Just Works:

```clojure
(require '[clj-p4.api :as clj-p4])
(clj-p4/sync! {:conn   {:p4/port "ssl:server:1666" :p4/user "alice"}
               :source "//stream/main"
               :target "/path/to/existing-git-p4-clone.git"})
```

`clj-p4` reads the most recent `[git-p4: depot-paths = "...": change = N]` trailer, resumes from the next changelist, and emits future commits with the same trailer shape.
