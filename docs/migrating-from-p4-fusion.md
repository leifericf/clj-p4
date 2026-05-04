# Migrating from `p4-fusion` to `clj-p4`

`p4-fusion` is a fast P4 → Git importer written in C++ on top of the native libp4api. It is designed for one job — fast bulk imports — and does that job well. `clj-p4` is a different design point: pure-Clojure, subprocess-per-call (no libp4api binding), with a strict read-only enforcement layer and stream-aware view composition.

If you're choosing between them, this document maps the configuration knobs and lays out where each tool wins.

## Configuration mapping

| `p4-fusion` flag | `clj-p4` option | Notes |
| --- | --- | --- |
| `--port` | `:p4/port` on the connection spec | |
| `--user` | `:p4/user` on the connection spec | |
| `--client` | (auto, ephemeral) | clj-p4 creates / destroys an ephemeral client per call. |
| `--src` | `:source` | Stream depot path or classic depot path. |
| `--path` | `:source` | Same idea — `clj-p4` doesn't distinguish stream-vs-path at the API level (since 0.3.0). |
| `--lookahead` | `:lookahead N` | clj-p4's lookahead since 0.9.0. Both tools queue describe-ahead-of-emit. |
| `--networkThreads` | `:fetch-parallelism N` | Per-CL fan-out of `p4 print` calls. |
| `--printBatch` | (n/a; see Notes below) | libp4api connection-overhead optimisation; doesn't apply to a subprocess-per-call model. |
| `--refresh` | (n/a) | Same: libp4api session refresh. clj-p4 spawns fresh subprocesses every call. |
| `--retries` | `:p4/retries N` on the connection spec | Plus `:p4/retry-backoff-ms M`. |
| `--noColor` | (n/a) | clj-p4 doesn't colour its output. |
| `--noMerge` | `:no-merge? true` | clj-p4's integrate-as-2-parent-merge detection (since 0.10.0). |
| `--maxChanges` | `:max-changes N` | |
| `--branch` | `:source` of each ref + `:sources` for multi-ref | clj-p4's `:sources` (since 0.11.0) is the equivalent of cloning multiple branches in one repo. |
| `--includeBinaries` | (always included) | clj-p4 imports every file in view, binaries included. |

## What `clj-p4` does that `p4-fusion` does not

- **Read-only by enforcement.** `p4-fusion` is a read-direction tool but doesn't enforce that posture at runtime — if the same binary were patched to issue a `p4 submit`, nothing technically prevents it. `clj-p4` runs every `p4` invocation through a runtime allowlist that throws `:write-direction-refused` before the subprocess starts, plus every ephemeral client is created with `Options: noallwrite noclobber locked` so the server itself refuses depot mutation. The allowlist is in source code that takes minutes to audit.
- **Locked-down ephemeral clients with auto-managed lifecycle.** `p4-fusion` expects you to point it at an existing P4 client; that client's options and protections are the operator's problem. `clj-p4` creates a scratch client per call, locks it down server-side, and tears it down in a `finally` block. Nothing survives a successful or failed run.
- **Stream `Components:` resolution by default.** Since 0.12.0, every stream clone routes through the server-resolved auto-view, so any `Components:` the stream pulls in is honoured automatically. `p4-fusion`'s view handling is more limited.
- **Multi-source single repo.** `:sources [...]` clones N streams into one bare repo with a shared marks file. `p4-fusion` does one source per run.
- **P4 user → real name + email.** `:user-map` lets you map P4 usernames to git author names + emails. `p4-fusion` uses the raw P4 username everywhere.
- **P4 labels → annotated git tags.** `:emit-labels?` walks `p4 labels` after the import and emits a tag for any label whose `Revision:` resolves to an imported CL. `p4-fusion` does not.
- **First-class validation harnesses.** `validate-tip` for fast count + size sanity-check; `validate-deep!` for byte-level comparison with first-divergence reporting. `p4-fusion` ships no equivalents.
- **Pure-Clojure runtime.** The pure layer (depot-path validation, view composition, exclude rules, plan generation) runs under JVM, Babashka, and ClojureScript-Node. The import itself is JVM-only, but the data shapes are inspectable from any of those.

## What `p4-fusion` does that `clj-p4` does not

- **Native libp4api bindings.** No subprocess overhead per call. On a like-for-like clone, `p4-fusion` will be faster — that's the cost of the portability and audit-friendliness `clj-p4` chose. Benchmarks (forthcoming) will quantify the gap.
- **`--printBatch` for connection-overhead amortisation.** Doesn't apply to a subprocess-per-call model, so `clj-p4` doesn't expose it. The wall-clock gap is partly closed by `:fetch-parallelism` + `:lookahead` working in concert.

## When to pick which

- **You need raw speed on a known-good clone.** Pick `p4-fusion`. It's purpose-built for one job and does it fast.
- **You need stream-awareness, server-side `Components:`, multi-stream-in-one-repo, byte-level validation, or strict read-only enforcement.** Pick `clj-p4`. The features above are what motivate the project.
- **You need to round-trip changes back to P4.** Neither tool fits — both are strictly read direction. Use `git-p4 submit`.
