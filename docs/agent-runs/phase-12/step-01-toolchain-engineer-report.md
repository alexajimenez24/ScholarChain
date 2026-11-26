HARNESS: claude-code
MODEL: sonnet, effort medium (standard tier per .agents/models.json; the session identifies itself as claude-sonnet-5-5)

STATUS: COMPLETE
OWNER: toolchain-engineer
WORK ORDER: Phase 12 step 1: pin and install Terraform, AWS CLI v2, one IaC scanner and shellcheck.
FILES CREATED: none in the repository
FILES MODIFIED (under /home/ajcreator/scholarchain/toolchain/): versions.sh, install.sh, LEDGER.md
COMMANDS RUN (all in the foreground; network and install commands under timeout):
- curl/WebFetch of vendor release, checksum, signature and doc pages; gpg --verify in a scratchpad keyring; sha256sum; archive listings
- bash -n on install.sh, versions.sh, env.sh: ok
- install.sh run 1: 4 new tools installed, sha256 ok; exit 1, only because of Claude Code drift (2.1.330 pinned, 2.1.332 observed)
- Pin updated; run 2: all 7 installed tools skipped, 5 verify-only ok, exit 0. Run 3, after the ledger edit: exit 0
- After `source toolchain/env.sh`: version commands, `trivy config --help`, shellcheck on install.sh and env.sh (rc=1, see RISKS)
- One earlier edit command was blocked by guard-dangerous: an error message I was writing contained the root-escalation command name. Nothing ran. I reworded the message and used the Edit tool.
TEST RESULTS: no sbt or feature tests (no sbt source change). Verification was bash -n, install runs and version commands.
VALIDATION RESULTS (checked 2026-11-26; each new download matched its pinned sha256; full hashes are in versions.sh):
- terraform: ledger 1.17.2; observed "Terraform v1.17.2 on linux_amd64"; sha256 e6f16b2f...96605b from vendor SHA256SUMS, PGP good (C874 011F ... 72D7 468F). https://releases.hashicorp.com/terraform/1.17.2/
- aws: ledger 2.35.21; observed "aws-cli/2.35.21 Python/3.14.6 ... exe/x86_64.ubuntu.24"; AWS publishes no sha256. PGP good (FB5D B77F ... 4672 475C, expires 2027-07-01); sha256 1fe66526...e792ee computed from that verified zip. https://docs.aws.amazon.com/cli/latest/userguide/getting-started-version.html
- trivy: ledger 0.76.2; observed "Version: 0.76.2"; sha256 bc0fc726...089fdbf matches release checksums.txt and GitHub digest; sigstore NOT verified. https://github.com/aquasecurity/trivy/releases/tag/v0.76.2
- shellcheck: ledger 0.11.0; observed "version: 0.11.0"; NO official checksum or signature; pin b7af85e4...54fe6f6 is GitHub's release-asset digest and matches the download. https://github.com/koalaman/shellcheck/releases/tag/v0.11.0
- terraform-mcp-server: 1.4.1 observed, pin unchanged. uv/uvx 0.13.9 and python3 3.12.3 ok (install.sh check). claude 2.1.332 ok after the pin update. JDK, sbt and scalafmt match their pins.
- project/build.properties (sbt 1.13.1) and .scalafmt.conf (3.11.5) match. No mismatch found. No required_version found under infra/.
- Already installed and matching: Terraform 1.17.2 and AWS CLI 2.35.21 were present as snaps. Same versions are now also installed in user space; ~/.local/bin links resolve to them after sourcing env.sh. The snaps are untouched.
ASSUMPTIONS / CHOICES:
- Terraform 1.17.2: newest stable (2026-11-11) and already in use; use_lockfile is GA since 1.11.0 (https://raw.githubusercontent.com/hashicorp/terraform/v1.11/CHANGELOG.md). The S3 backend docs mark DynamoDB locking deprecated (https://developer.hashicorp.com/terraform/language/backend/s3).
- AWS CLI stays at 2.35.21, not the newer 2.42.4: no behavior change for the existing setup. Moved from verify-only to installed, since a snap install cannot be reproduced without root. Terraform likewise.
- trivy, so no tfsec. The advisory (https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23) lists binary 0.69.4 and Docker Hub images 0.69.5/0.69.6 as affected, not 0.76.2.
- shellcheck pinned: cheap, with the weaker checksum source stated above.
- install.sh checks sha256 only, not PGP; the ledger says to repeat the signature check at each version bump.
- Claude Code pin moved to 2.1.332, following the ledger's rule that harness drift is reported and the pin updated.
RISKS:
- NOTE FOR STEP 3 AND infra-engineer: run `trivy config --skip-check-update --disable-telemetry --skip-version-check <dir>`. All three flags exist in --help; no scan was run. Without --skip-check-update trivy fetches an unpinned checks bundle. How well the built-in checks cover this repo is untested.
- PATH: ~/.local/bin must precede /snap/bin or the self-refreshing snaps win. It does in this shell. Use `source toolchain/env.sh`; the `aws` link also changes which binary a plain `aws` runs for the human. I ran no AWS call or login and read no AWS config or credential files, so profile/login behavior with the new install is untested.
- No full clean-machine run (empty HOME). The four new tools were installed fresh here; JDK, sbt and scalafmt were skipped. unzip is now a prerequisite; install.sh stops with a message if it is missing.
- shellcheck on toolchain/env.sh reports SC2148 and SC2296 (no shell directive; zsh fallback syntax). env.sh was not changed. install.sh had no findings.
- When run with CHECKPOINT_DISABLE unset, `terraform version` may contact HashiCorp's version check; install.sh sets it for its own calls.
- `terraform init` and `use_lockfile` were not exercised.
- The aws-api MCP server's own instructions say it is deprecated (entering end of development); migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md. Please tell the human; any change to .mcp.json belongs to factory-engineer. Its pin was not touched.
- Nothing outside toolchain/ was edited. Outside the repository: tool directories and ~/.local/bin links, plus downloads and a throwaway GPG keyring in the session scratchpad. docs/agent-runs/phase-12/ was already untracked. No commit.
MANUAL ACTION REQUIRED: none. No root needed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 12 step 2 per the orchestrator's task list; code review of the toolchain/ diff before commit.
