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
| t9801 | N/A: architecture | `--detect-branches` heuristic — clj-p4 uses streams natively (covered by `virtual_stream_test`) |
| t9802 | implemented | filetypes (text/binary, +x, symlinks, +k/+ko, UTF-16 ±BOM, CRLF, broken-target invariant) |
| t9803 | implemented | shell-metachar filenames |
| t9804 | N/A: write-direction | label *export* (`git p4 submit`-side) — out of scope |
| t9805 | N/A: write-direction | `--skip-submit-edit` |
| t9806 | N/A: architecture | git-p4 CLI option handling — clj-p4 has a malli-validated options map (`api-test`, `schemas-test`) |
| t9807 | N/A: write-direction | `git p4 submit` |
| t9808 | N/A: architecture | `--chdir` CLI flag |
| t9809 | N/A: architecture | `--use-client-spec` — clj-p4 always synthesises ephemeral clients |
| t9810 | implemented | RCS keyword markers stay literal post-import |
| t9811 | implemented | label import (`:emit-labels?`) |
| t9812 | implemented | P4-wildcard chars (`@`, `#`, `*`, `%`) in filenames |
| t9813 | N/A: write-direction | `--preserve-user` on submit |
| t9814 | implemented | rename detection ↔ p4 move |
| t9815 | N/A: write-direction | submit-fail handling |
| t9816 | N/A: write-direction | locked files in submit flow |
| t9817 | implemented | path exclusion via `:excludes` |
| t9818 | implemented | block-mode change fetching (`:changes-block-size`) |
| t9819 | implemented | case-folding behaviour (lowercase round-trip; `-C1` rejection deferred — needs a third fixture) |
| t9820 | N/A: architecture | submit editor handling |
| t9821 | implemented | paths differing only in case (case-sensitive servers) |
| t9822 | implemented | non-ASCII / UTF-8 paths and contents |
| t9823 | N/A: write-direction | mock-LFS submit interactions |
| t9824 | N/A: write-direction | git-LFS submit interactions |
| t9825 | implemented | UTF-16 files without BOM |
| t9826 | implemented | `:keep-empty-commits?` (default skip; flag preserves) |
| t9827 | implemented | filetype change between revisions |
| t9828 | implemented | `:user-map` remaps committer identity |
| t9829 | feature gap | git-p4 jobs (CLs → git notes) — clj-p4 doesn't surface jobs |
| t9830 | implemented | symlinks pointing at directories |
| t9831 | N/A: write-direction | Perforce triggers (fire on submit) |
| t9832 | feature gap | `git p4 unshelve` — clj-p4 doesn't expose shelve-import |
| t9833 | N/A: architecture | git-p4 CLI error-message presentation |
| t9834 | implemented | a path that's a file then later a directory |
| t9835 | implemented | metadata encoding strategies (strict / fallback / passthrough) |
| t9836 | folded into t9835 | Python 2 vs 3 split doesn't apply on the JVM |
| t9850 | N/A: architecture | git-p4 launcher-shell metachars — clj-p4 has no launcher script |
| *p4-fusion #90* | implemented | broken-symlink target survival (in `t9802`) |
| *no t-number* | implemented | merge-detection: `p4 integrate` produces a 2-parent git commit (`merge_detection_test`) |

The `seed.bb` scripts prime each p4d server with the file shapes the
tests rely on; expand them (and add a new test ns) when implementing a
new port. Shared git-side assertions (`ls-tree`, `cat-blob`, `tmp-dir`,
`find-commit-sha`) live in `git_assert.clj`.

One read-side scenario from the canonical roadmap is tracked as a
follow-up:

- **t9819 `-C1` rejection sub-test** — verifies that cloning a stream
  with the wrong-cased name fails on a case-folding server. Needs a
  third docker fixture (`p4d -C1` is irreversible per the admin guide,
  so it can't share a volume with the case-sensitive default).

Every other read-side `t98xx` port from the canonical suite is either
implemented above, an architectural non-equivalent (clj-p4 isn't a CLI
and doesn't auto-detect branches), or a feature gap clj-p4 hasn't
exposed yet (jobs, unshelve). Write-direction ports are out of scope
by charter — clj-p4 is read-only against the user's p4 server.
