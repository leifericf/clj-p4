# Changelog

## Unreleased

### Fixes

- **`repo-state` walks past non-trailer commits at HEAD** — when a user committed on top of a clj-p4 history (e.g., a manual cherry-pick or rebase), `:last-change` came back nil even though the chain below carried valid `[git-p4: ... change = N]` trailers, and `sync!` would then re-import the entire history from change 0. The lookup now uses `git log --grep` to find the most recent commit whose message matches the trailer pattern, so manual commits at HEAD no longer hide the resume point.
- **`repo-state`'s `:last-change` now matches only the `git-p4:` trailer** — the previous regex `change\s*=\s*(\d+)` matched any commit message containing those characters, including user prose like "Fixed change = 42 in config". Subsequent `sync!` calls would then skip legitimately new changelists. The regex is now anchored on the formal `[git-p4: ... change = N]` trailer, so unrelated commit text no longer poisons the resume point.
