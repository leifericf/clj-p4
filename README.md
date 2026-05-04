# clj-p4

Pure-Clojure Perforce-to-Git bridge with full Perforce Streams support. Reads stream specs and changelist history as data, writes git history via a long-running `git fast-import` subprocess. Stream-aware where [`git-p4`](https://git-scm.com/docs/git-p4) and [`p4-fusion`](https://github.com/salesforce/p4-fusion) aren't.

**Status: pre-alpha.** The public API will move. Pin a SHA, not a version.

Bottom-up data to answer top-down questions about your Perforce history.

## Design

Data-first, decomplected. Pure layers (`spec`, `parse.semantic`, `view`, `exclude`, `plan`) live in `.cljc` and run on JVM, Babashka, and ClojureScript. Side effects sit at a thin shell boundary (`shell.proc`, `shell.p4`, `shell.git`). The clone/sync pipeline is a lazy seq of operation maps reduced by an executor — the program is data.

## Inspiration

- [`git-p4`](https://git-scm.com/docs/git-p4) — the `git fast-import` writing protocol and the `[git-p4: ...]` commit-trailer convention.
- [`p4-fusion`](https://github.com/salesforce/p4-fusion) — parallel-fetch design.
- [Helix Core P4 Git Connector](https://www.perforce.com/products/helix-core-git-connector) — Perforce's reference for stream-aware ingestion.

## License

MIT. See [LICENSE](LICENSE).
