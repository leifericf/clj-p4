# Changelog

## Unreleased

### Fixes

- **`repo-state`'s `:last-change` now matches only the `git-p4:` trailer** — the previous regex `change\s*=\s*(\d+)` matched any commit message containing those characters, including user prose like "Fixed change = 42 in config". Subsequent `sync!` calls would then skip legitimately new changelists. The regex is now anchored on the formal `[git-p4: ... change = N]` trailer, so unrelated commit text no longer poisons the resume point.
