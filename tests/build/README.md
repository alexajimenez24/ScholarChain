# Build contract test

Owner: test-engineer.
`check-contract.sh` is a black-box test of the build's own contract: what `sbt check`
and `markTestRun` promise in the spec and in `build.sbt`.
It is the marker test the spec's Phase 2 work order names ("sbt check once the marker
test exists", `docs/spec/assignment.md` line 1038), as
`docs/agent-runs/phase-02/review-report.md` (MINOR-6) records.
Each scenario checks a Phase 2 clause of the spec: line 1021 (`markTestRun` updates
`.claude/.last-test-run` only after tests pass), line 1023 (`check` runs format
checking, compilation, tests and contract tests) and line 1046 (`sbt check` has a
stable definition).

## When to run it

- Before any change to `build.sbt`, `project/**`, `.scalafmt.conf` or the test layout
  is reported done, and again after the change.
- After a bump of sbt, the sbt plugins or MUnit.
- It is not part of `sbt check`: it runs sbt several times in separate copies and takes
  minutes.

## How to run it

From the repository root, after the pinned toolchain is installed (`toolchain/`):

```bash
tests/build/check-contract.sh
```

The script sources `toolchain/env.sh` inside each copy itself.
Options, as environment variables:

- `BUILD_CONTRACT_SCENARIOS="pass fail-unit"` runs a subset.
  Leaving it unset or setting it to an empty string runs all four scenarios.
  A whitespace-only value exits with a setup error.
- `BUILD_CONTRACT_KEEP=1` keeps the temp copies and sbt logs for debugging.
- `BUILD_CONTRACT_TIMEOUT=900` sets the seconds allowed per sbt run.
- `TMPDIR` picks where the temp copies go (default `/tmp`).

Exit status: 0 when every scenario passes, 1 when any assertion fails, 2 on a setup
error.
A failing scenario prints the failed assertions and the last 40 lines of its sbt log.

Run the scenario-selection regression without sbt with
`bash tests/build/check-scenario-selection.sh`.

## What it does

- Copies the files git sees (tracked plus untracked, not ignored) into a fresh temp
  directory per scenario: no `.git`, no `target/`, no `.env` files.
- Adds the scenario's fixture files to the copy, presets the copy's
  `.claude/.last-test-run` to 2001-01-01, runs `sbt -batch check` there and checks the
  exit code, the marker mtime and the sbt output.
- Never writes the real working tree; it warns if the real marker or `git status`
  changed during the run (a concurrent edit or sbt run would do that).
- Deletes the copies on exit unless `BUILD_CONTRACT_KEEP=1`.
- Needs no AWS; network use is only what sbt dependency resolution needs, and none once
  the coursier cache is warm.

## Scenarios

- `pass`
  - Fixtures: passing suites directly in `tests/contracts/`, `tests/adversarial/` and
    `tests/module-unit/core/`.
    Non-Scala `.scala` files under each `resources/`, under a stale
    `tests/<folder>/target/` and directly in `modules/core/`.
  - Expected: exit 0 and the marker touched.
    Every suite and `markTestRun` run exactly once.
    No tool reads the non-Scala files, and the `resources/` file is on the test
    classpath.
- `fail-contract`
  - Fixtures: a failing MUnit test in `tests/contracts/`.
  - Expected: exit not 0 from a test failure, not a compile error.
    The marker keeps its old mtime and the failing test ran exactly once.
- `fail-unit`: the same with a failing MUnit test in `tests/module-unit/core/`.
- `fail-format`
  - Fixtures: a valid but badly formatted suite in `tests/contracts/`.
  - Expected: exit not 0 at the format check, no test ran and the marker keeps its old
    mtime.

## Limits

- Linux only: it uses GNU `stat -c` and `touch -d`.
- It proves the contract for the layout and versions in the tree it copies; it does not
  cover `packageJobs` or `dockerLocal`.
