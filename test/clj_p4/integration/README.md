# clj-p4 integration tests

Integration tests run against Docker-managed `p4d` containers under
`test/fixtures/p4d/` (primary, unicode-mode) and
`test/fixtures/p4d-mixed/` (sidecar, non-unicode for the metadata-
encoding ports). They are gated behind both:

- `CLJ_P4_INTEGRATION=1` in the environment, and
- the `^:integration` test selector.

The host machine needs a `p4` CLI on `PATH` to talk to the dockerized
servers (the test process spawns `p4` as a subprocess).

Run them with:

```sh
CLJ_P4_INTEGRATION=1 clojure -X:test :includes [:integration]
```

The first run builds the docker images (which pull Perforce's official
`helix-p4d` binaries). Subsequent runs reuse the images and the
seeded volumes.

## Test port roadmap (mirroring `git-p4`'s `t/t98xx-git-p4-*.sh`)

`git-p4` ships a battle-tested regression suite in the git source tree.
We mirror the read-direction subset as integration tests, one Clojure
namespace per test file. Submit-back-to-p4 tests (out of scope: clj-p4
is read-only) are skipped.

| Source | Status | Subject |
| ------ | ------ | ------- |
| t9800 | implemented | basic clone + sync |
| t9802 | implemented | filetypes (text/binary, +x, symlinks, +k/+ko, UTF-16 ±BOM, CRLF, broken-target invariant) |
| t9803 | implemented | shell-metachar filenames |
| t9810 | implemented | RCS keyword markers stay literal post-import |
| t9812 | implemented | P4-wildcard chars (`@`, `#`, `*`, `%`) in filenames |
| t9814 | implemented | rename detection ↔ p4 move |
| t9817 | implemented | path exclusion via `:exclude` |
| t9818 | implemented | block-mode change fetching (`:changes-block-size`) |
| t9819 | implemented | case-folding behaviour (lowercase round-trip; `-C1` rejection deferred — needs a third fixture) |
| t9821 | implemented | paths differing only in case (case-sensitive servers) |
| t9822 | implemented | non-ASCII / UTF-8 paths and contents |
| t9825 | implemented | UTF-16 files without BOM |
| t9826 | implemented | `:keep-empty-commits?` (default skip; flag preserves) |
| t9827 | implemented | filetype change between revisions |
| t9830 | implemented | symlinks pointing at directories |
| t9834 | folded into t9827 | path/filetype change between revisions (same fixture) |
| t9835 | implemented | metadata encoding strategies (strict / fallback / passthrough) |
| t9836 | folded into t9835 | Python 2 vs 3 split doesn't apply on the JVM |

The `seed.bb` scripts prime each p4d server with the file shapes the
tests rely on; expand them (and add a new test ns) when implementing a
new port. Shared git-side assertions (`ls-tree`, `cat-blob`, `tmp-dir`,
`find-commit-sha`) live in `git_assert.clj`.

Two test scenarios from the original audit are tracked as follow-ups:

- **t9819 `-C1` rejection sub-test** — verifies that cloning a stream
  with the wrong-cased name fails on a case-folding server. Needs a
  third docker fixture (`p4d -C1` is irreversible per the admin guide,
  so it can't share a volume with the case-sensitive default).
- **merge-detection integration test** — exercises
  `execute/merge-source-for-cl` end-to-end via a `p4 copy` from
  `//stream/dev` to `//stream/main`. Currently unit-tested only;
  producing a synthetic dev → main copy in the seed fights p4 stream
  lineage rules in a way that's beyond this PR's scope.
