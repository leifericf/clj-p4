# clj-p4 integration tests

Integration tests run against a Docker-managed `p4d` container under
`test/fixtures/p4d/`. They are gated behind both:

- `CLJ_P4_INTEGRATION=1` in the environment, and
- the `^:integration` test selector.

Run them with:

```sh
CLJ_P4_INTEGRATION=1 clojure -X:test :includes [:integration]
```

The first run builds the docker image (which pulls Perforce's official
`helix-p4d` Debian package). Subsequent runs reuse the image and the
seeded volume.

## Test port roadmap (mirroring `git-p4`'s `t/t98xx-git-p4-*.sh`)

`git-p4` ships a battle-tested regression suite in the git source tree.
We mirror the read-direction subset as integration tests, one Clojure
namespace per test file. Submit-back-to-p4 tests (out of v0.1 scope),
labels tests (v0.2), and `--detect-branches` tests (replaced by stream
support) are skipped.

| Source | Status | Subject |
| ------ | ------ | ------- |
| t9800 | implemented | basic clone + sync |
| t9802 | implemented | filetypes (text/binary, +x, symlinks, +k/+ko) |
| t9803 | implemented | shell-metachar filenames |
| t9810 | skipped | RCS keyword expansion is suppressed by design (`p4 print -k`) |
| t9812 | skipped | needs depot-path unescape (`%40` → `@`, …) in the importer |
| t9814 | implemented | rename detection ↔ p4 move |
| t9817 | implemented | path exclusion via `:exclude` |
| t9818 | skipped | block-mode change fetching — server-config dependency |
| t9819 | implemented | case-folding behaviour (lowercase round-trip) |
| t9821 | implemented | paths differing only in case (case-sensitive servers) |
| t9822 | implemented | non-ASCII / UTF-8 paths and contents |
| t9825 | implemented | UTF-16 files without BOM (importer doesn't crash) |
| t9826 | skipped | `--keep-empty-commits` — no real empty-CL fixture today |
| t9827 | implemented | filetype change between revisions |
| t9830 | implemented | symlinks pointing at directories |
| t9834 | folded into t9827 | path/filetype change between revisions (same fixture) |
| t9835 | skipped | inconsistent metadata encodings (Python 2 era) |
| t9836 | skipped | inconsistent metadata encodings (Python 3 era) |

The `seed.bb` script primes the p4d server with the file shapes each
test relies on; expand it (and add a new test ns) when implementing a
new port. Shared git-side assertions (`ls-tree`, `cat-blob`, `tmp-dir`,
`find-commit-sha`) live in `git_assert.clj`.
