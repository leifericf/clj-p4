# Changelog

## Unreleased

### Fixes

- **`clone!` refuses to overlay an existing non-empty target** — `git init --bare` is idempotent, so a `clone!` against a directory that already contained user files would silently create a bare repo right next to them and proceed. The clone "worked" but left the directory in an incoherent half-user-half-bare-repo state, and a later sync would inherit whatever happened to be there. `clone!` now classifies the target up front: empty (or non-existent) is allowed; an existing clj-p4 clone throws asking the caller to use `sync!`; anything else throws asking the caller to delete the directory or pick a fresh path. The user's prior files are never touched.
- **`repo-state` walks past non-trailer commits at HEAD** — when a user committed on top of a clj-p4 history (e.g., a manual cherry-pick or rebase), `:last-change` came back nil even though the chain below carried valid `[git-p4: ... change = N]` trailers, and `sync!` would then re-import the entire history from change 0. The lookup now uses `git log --grep` to find the most recent commit whose message matches the trailer pattern, so manual commits at HEAD no longer hide the resume point.
- **`repo-state`'s `:last-change` now matches only the `git-p4:` trailer** — the previous regex `change\s*=\s*(\d+)` matched any commit message containing those characters, including user prose like "Fixed change = 42 in config". Subsequent `sync!` calls would then skip legitimately new changelists. The regex is now anchored on the formal `[git-p4: ... change = N]` trailer, so unrelated commit text no longer poisons the resume point.
