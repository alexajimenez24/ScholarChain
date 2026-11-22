HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier)

STATUS: COMPLETE
OWNER: toolchain-engineer
WORK ORDER: Phase 02 repair round 1, task R1-3 (review NITs N3 and N4, toolchain/install.sh)
FILES CREATED: none in the repo. Scratch test files: /tmp/claude-1000/-home-ajcreator-scholarchain/d5e0fa5a-7b55-44f5-9599-2d444a068812/scratchpad/f.sh and t.sh.
FILES MODIFIED: /home/ajcreator/scholarchain/toolchain/install.sh
- New helper `vmatch version text` (lines 17-23) does an exact version-token match. The version must not be preceded by a digit or dot. It must not be followed by a digit, or by a dot and a digit. Regex metacharacters in the pin are escaped.
- N3: the JDK "already installed" test now calls `vmatch "$JDK_VERSION"` on the `java -version` output. It no longer greps for any `"17.`.
- N4: the scalafmt "already installed" check and the verify-only `check()` (uv, uvx, terraform-mcp-server, aws-cli, claude, terraform, python3, codex) now use `vmatch`. They no longer use a substring grep.
- LEDGER.md was not changed. It does not describe the matching behavior.
COMMANDS RUN: `bash -n install.sh`; the scratch matcher test; `bash install.sh`; `git status --short toolchain`.
TEST RESULTS: Scratch test, 14 of 14 cases passed.
- Accepted:
  - `17.0.21.1.2` in the Corretto runtime line.
  - `0.13.9` in `uv 0.13.9 (abc 2025)` and in `uv 0.13.9`.
  - `1.17.2` in `Terraform v1.17.2`.
  - `3.11.5` in `scalafmt 3.11.5`.
  - `0.1x` in `0.1x` (metacharacter escape check).
- Rejected:
  - `17.0.2` in `Corretto-17.0.2`.
  - `17.0.21.1.2` in `Corretto-17.0.21.1.20` and in `Corretto-17.0.21.1.2.3`.
  - `0.13.9` in `uv 0.13.90`, `10.13.9` and `0.13.9.1`.
  - `1.17.2` in `Terraform v1.17.21`.
  - `0.1x` in `0.1y`.
VALIDATION RESULTS:
- `bash -n toolchain/install.sh` printed "syntax ok".
- `bash toolchain/install.sh` exited 0. JDK, sbt and scalafmt all printed "already installed, skipping", and nothing was downloaded or installed.
- Verify-only tools all printed ok:

| Tool | Version |
|---|---|
| uv | 0.13.9 |
| uvx | 0.13.9 |
| terraform-mcp-server | 1.4.1 |
| aws-cli | 2.35.21 |
| claude | 2.1.330 |
| terraform | 1.17.2 |
| python3 | 3.12.3 |

- Installed versions printed: `openjdk version "17.0.21.1"`, sbt 1.13.1, scalafmt 3.11.5.
- Pin values are unchanged, and checksums are unchanged because nothing was fetched.
- No mismatch with `project/build.properties` or `.scalafmt.conf` was newly found. The ledger records both as matching.
ASSUMPTIONS: The work order said to match "17.0.21.1", but the pin in versions.sh is `17.0.21.1.2`. `java -version` prints only `"17.0.21.1"`, which would make the match too loose. The matcher therefore runs against the full `-version` output, which contains `Corretto-17.0.21.1.2`. That matches the exact pin and is stricter than 17.0.21.1.
RISKS: I did not do the following.
- I did not run sbt.
- I did not reinstall anything.
- I did not exercise the install branches (fetch and extract), which are unchanged.
- The matcher also rejects a trailing `.digit` (for example `0.13.9.1`), which is intended.
- `toolchain/` is still untracked in git, as it was before this task.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of the NIT N3 and N4 fixes in the repair round, then the human ratification gate.

---
Orchestrator note (not part of the agent report): install.sh lines 17-23, 34, 59 and 72
were checked on disk and match this report. The work order's "17.0.21.1" was the
orchestrator's error; the ledger pin is 17.0.21.1.2.
