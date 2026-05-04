# clj-p4 vs. p4-fusion

A read-direction (Perforce → Git) feature comparison as of v0.15.0-alpha. Both tools target the same use case — long-lived P4 → git import — but they differ in scope, safety posture, and which fidelity wins they go after.

| Capability | clj-p4 | p4-fusion |
| --- | --- | --- |
| **Streams (mainline / development / release)** | ✅ Server-resolved auto-view (since 0.12.0) | ✅ |
| **Virtual streams** | ✅ Auto-managed locked-down ephemeral client (since 0.2.0) | ⚠️ Operator must hand-manage a workspace |
| **Task / sparsedev / sparserel streams** | ✅ Same dispatch as the rest (since 0.13.0) | ⚠️ Limited |
| **Classic non-stream depot paths** | ✅ Generic ephemeral client (since 0.3.0) | ✅ |
| **Stream `Components:` resolution** | ✅ Free since the auto-view refactor (0.12.0) | ⚠️ |
| **`Paths:` / `Remapped:` / `Ignored:` parent inheritance** | ✅ | ✅ |
| **Move pairs as `R old new`** | ✅ Move + edit preserves new content via `R + M` (since 0.5.0) | ✅ |
| **Integrate as 2-parent merge commit** | ✅ Per-CL majority-vote source detection (since 0.10.0); `:no-merge?` opt-out | ✅ `--noMerge` opt-out |
| **Multi-stream / multi-ref single repo** | ✅ Shared marks file → `merge :<C>` parents cross stream boundaries (since 0.11.0) | ⚠️ |
| **`p4 print` parallelism inside a CL** | ✅ `:fetch-parallelism N` (since 0.5.0) | ✅ `--networkThreads` |
| **`p4 describe` lookahead across CLs** | ✅ `:lookahead N` (since 0.9.0) | ✅ `--lookahead` |
| **`--printBatch` (libp4api connection batching)** | ❌ Not applicable to subprocess-per-call model | ✅ |
| **Per-call retry on transient failure** | ✅ `:p4/retries N` + exponential backoff (since 0.3.0) | ✅ |
| **Per-file size cap** | ✅ `:max-print-bytes N` (since 0.5.0) | ⚠️ Indirect via memory limit |
| **Resume after crash** | ✅ `git fast-import` marks file lifecycle-bound to bare repo (since 0.1.0) | ✅ |
| **P4 user → real name + email** | ✅ `:user-map` (since 0.6.0) — beyond `git-p4`'s `git-p4.mapUser` | ⚠️ Raw P4 username everywhere |
| **P4 labels → annotated git tags** | ✅ `:emit-labels?` (since 0.6.0) | ❌ |
| **Validation: count + size summary** | ✅ `validate-tip` (since 0.7.0) | ❌ |
| **Validation: byte-level deep harness** | ✅ `validate-deep!` (since 0.14.0) | ❌ |
| **Read-only allowlist (no submit / edit / integrate)** | ✅ Runtime gate on every `p4` invocation (since 0.2.0) | ❌ |
| **Locked-down ephemeral clients** | ✅ `Options: noallwrite noclobber locked` enforced server-side | ❌ |
| **Submit-back-to-P4 (write direction)** | ❌ Permanently out of scope by design | ❌ |
| **Native libp4api bindings** | ❌ Subprocess overhead is the cost of portability | ✅ |
| **Implementation language** | Clojure (JVM) | C++ |

## Why the per-knob differences

The differences below the line aren't accidents — they reflect different design points:

- **`--printBatch` does not apply.** It's a libp4api connection-overhead optimisation: p4-fusion holds a long-lived RPC connection and `--printBatch` amortises per-call setup by sending many `print` requests on one connection. clj-p4 uses one-shot `p4` subprocesses authenticated by `P4PASSWD=<ticket>`, so each call is already a fresh process and there is no connection setup to amortise. `:fetch-parallelism` and `:lookahead` are the right knobs for this design — together they cover the wall-clock gap that motivated `--printBatch`.

- **`--refresh` does not apply** for the same reason. p4-fusion's `--refresh` cycles the libp4api session every N calls; with subprocess-per-call, every call is already a fresh session.

- **The safety posture is intentional.** p4-fusion is a fast importer with no built-in restrictions on the underlying client; if the operator pointed it at a writable client and a buggy patch landed, theoretically nothing prevents a stray write. clj-p4's runtime allowlist + locked-down ephemeral clients make depot mutation a server-rejected operation, not a "trust the operator" matter. The cost is a few extra round trips at clone time; the benefit is a tool that can't write back even by accident.

- **Native vs. JVM Clojure.** clj-p4 will be slower than p4-fusion on a like-for-like clone — subprocess overhead dominates per-call time. The trade is an audit-friendly read-only enforcement layer in the same language as the rest of the tool, plus the Clojure ecosystem for downstream tooling.
