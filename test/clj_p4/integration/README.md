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
| t9802 | TODO | filetypes (text/binary, +x, symlinks, apple, +k/+ko) |
| t9803 | TODO | shell-metachar filenames |
| t9810 | TODO | RCS keyword expansion (read side) |
| t9812 | TODO | P4-wildcard chars (`@`, `#`, `*`, `%`) in filenames |
| t9814 | TODO | rename detection ↔ p4 move |
| t9817 | TODO | path exclusion via `-//path` |
| t9818 | TODO | block-mode change fetching |
| t9819 | TODO | case-folding behaviour |
| t9821 | TODO | paths differing only in case |
| t9822 | TODO | non-ASCII / UTF-8 paths and contents |
| t9825 | TODO | UTF-16 files without BOM |
| t9826 | TODO | `--keep-empty-commits` |
| t9827 | TODO | filetype change between revisions |
| t9830 | TODO | symlinks pointing at directories |
| t9834 | TODO | path existing as both file and directory |
| t9835 | TODO | inconsistent metadata encodings (Python 2 era) |
| t9836 | TODO | inconsistent metadata encodings (Python 3 era) |

The `seed.sh` script primes the p4d server with the file shapes each
test relies on; expand it (and add a new test ns) when implementing a
new port.
