# `bench/` — local benchmarks

Wall-clock + peak-RSS comparison of three P4 → Git importers (`git-p4`, `p4-fusion`, `clj-p4`) cloning the same source. Designed to be run locally against a real P4 server (the `test/fixtures/p4d` Docker image, or a production server you have read access to). Not run in CI.

## Why local-only

CI runs in a known-clean environment but with no concept of "warm vs. cold P4 server" or "real network round-trip latency." Wall-clock comparisons are sensitive to both, so the numbers are only meaningful on a stable host you control. The script is designed for the operator to run a few times on their own laptop or build server, average the results, and publish them once.

## Running

```bash
# Bring up the Docker fixture
cd test/fixtures/p4d
./up.sh

# Run the benchmark
cd ../../..
bb bench/clone.bb --source //stream/main --repeat 3
```

Output is a markdown row per tool, suitable for pasting into `docs/bench-results.md`.

## What the benchmark does NOT measure

- **Cold-cache vs. warm-cache.** The first run is slower; subsequent runs hit the OS file-cache. The `--repeat 3` averaging hides some of this but doesn't eliminate it. Run several rounds and discard the first if you want consistent numbers.
- **Server-side load.** Running this against a busy production P4 server gives noisy numbers. Run against an idle server or the Docker fixture.
- **Network jitter.** Same caveat. SSH-tunnelled connections add their own variability.
- **Tool versions.** The script doesn't pin versions — it uses whatever `git-p4`, `p4-fusion`, and `clj-p4` are on PATH. Record the versions in the markdown header before publishing numbers.
