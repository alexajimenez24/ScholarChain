HARNESS: claude-code
MODEL: standard tier (Sonnet 5.5), the default for this role

STATUS: COMPLETE
OWNER: toolchain-engineer
WORK ORDER: Phase 02 step 1b. Rename every versions.env reference to versions.sh inside toolchain/, pin the observed verify-only tool versions, re-run install.sh.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/toolchain/env.sh (versions.env -> versions.sh)
- /home/ajcreator/scholarchain/toolchain/install.sh (versions.env -> versions.sh)
- /home/ajcreator/scholarchain/toolchain/LEDGER.md (3 renamed references; the verify-only table now holds the observed versions)
- /home/ajcreator/scholarchain/toolchain/versions.sh (pins for UV_VERSION, AWS_CLI_VERSION, CLAUDE_CODE_VERSION, TERRAFORM_VERSION and PYTHON3_VERSION)
COMMANDS RUN:
- sed rename of versions.env to versions.sh in env.sh, install.sh and LEDGER.md
- sed pins in versions.sh
- python3 edit of the LEDGER.md table
- `grep -rn "versions.env" toolchain` (no matches)
- `bash -n toolchain/install.sh` (clean)
- `bash toolchain/install.sh` (exit 0)
TEST RESULTS: none apply; this is a toolchain change with no sbt source.
VALIDATION RESULTS:
Verbatim install.sh output:
```
==> JDK Corretto 17.0.21.1.2
    already installed, skipping
==> sbt launcher 1.13.1
    already installed, skipping
==> scalafmt CLI 3.11.5
    already installed, skipping
==> verify-only tools (pre-installed by the human, never reinstalled)
    ok       uv 0.13.9
    ok       uvx 0.13.9
    ok       terraform-mcp-server 1.4.1
    ok       aws-cli 2.35.21
    ok       claude 2.1.330
    ok       terraform 1.17.2
    ok       python3 3.12.3
==> installed versions
    openjdk version "17.0.21.1" 2026-11-17 LTS
    sbt 1.13.1
    scalafmt 3.11.5
==> done; run: source toolchain/env.sh
```
- No mismatches. terraform-mcp-server now verifies as 1.4.1.
- No versions.env reference remains in toolchain/**.
- The JDK, sbt and scalafmt checksums were verified in the earlier run, and this run skipped them as already installed.
- uv, aws-cli, claude, terraform and python3 have no vendor checksum recorded because the human pre-installed them. The terraform-mcp-server checksum was verified by the human against HashiCorp's SHA256SUMS; I did not re-verify it.
- The sbt and scalafmt pins still match project/build.properties and .scalafmt.conf, as checked in the earlier run. I did not re-check them this run.
ASSUMPTIONS:
- The pins use the exact observed strings from output-toolchain-install.txt.
- install.sh matches pins by substring, so a pin of 0.13.9 also matches 0.13.90. I left that as it was.
- CODEX_VERSION stays empty because Codex is out of scope.
RISKS:
- I did not edit references to versions.env outside toolchain/** (other owners handle those). I did not search outside toolchain/, so I don't know whether any exist.
- Claude Code updates itself, so the claude pin will report a mismatch on the next update.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes step 2. The human ratifies at the phase gate.
