# Toolchain ledger

Machine tools only.
Library versions live in `project/Versions.scala`, MCP server versions in `.mcp.json`.
The pins are stated once in `versions.sh`; this file gives the reason for each.
Platform: Linux x86_64, user space only, no sudo, no system package manager.

## Use

- `bash toolchain/install.sh` installs or verifies everything and is safe to rerun.
- `source toolchain/env.sh` sets `JAVA_HOME` and `PATH` for the current shell.
  No shell rc file is edited; add that line to your own rc if you want it permanent.
- Tools live under `~/.local/share/scholarchain-tools/`.
  `sbt`, `scalafmt`, `terraform`, `aws`, `aws_completer`, `trivy` and `shellcheck` are
  symlinked into `~/.local/bin`.
  That directory must come before `/snap/bin` on `PATH`, or older snap installs of
  `terraform` and `aws` (which refresh themselves) take over.
- The install needs `bash`, `curl`, `tar`, `sha256sum` and `unzip` on the machine.

### The `SCHOLARCHAIN_TOOLS` override

`SCHOLARCHAIN_TOOLS` overrides the tool install directory.
Its default is `$HOME/.local/share/scholarchain-tools`, written the same way in
`install.sh` and `env.sh`.
Leave it unset: the unset default is the reproducible install, and the paths in this
ledger assume it.

- It moves every installed tool (`jdk-*`, `sbt-*`, `scalafmt-*`, `terraform-*`,
  `aws-cli-*`, `trivy-*`, `shellcheck-*`) under the new directory.
- `env.sh` sets `JAVA_HOME` from it and puts `$JAVA_HOME/bin` first on `PATH`, so sbt
  runs on the JDK in that directory.
- The links in `~/.local/bin` point into that directory.
  Each `install.sh` run repoints them, so the last run wins.
- Set it in the same shell for `install.sh` and `env.sh`; a mismatch leaves
  `JAVA_HOME` on a JDK that was never installed.

### Environment inputs

The two scripts read these variables directly:

- `SCHOLARCHAIN_TOOLS`: the override above.
- `HOME`: the default tool directory and `~/.local/bin`.
- `PATH`: `env.sh` prepends `$JAVA_HOME/bin` and `~/.local/bin`; `install.sh` puts the
  installed JDK's `bin` first on `PATH` only for its sbt version check.

The tools the scripts run read more variables, which these scripts do not read:

- sbt launcher and JVM: `SBT_OPTS`, `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`,
  `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and `SBT_NATIVE_CLIENT`.
  `SBT_OPTS` can override the sbt version pinned in `project/build.properties`.
- curl (fetches the pinned downloads) may read proxy and CA variables such as
  `https_proxy`, `no_proxy` and `CURL_CA_BUNDLE`; this was not checked here.

For a reproducible install and build, unset these in the shell that runs the tools.

## Installed and pinned by this repository

| Tool | Pin | Checksum (sha256) | Observed version command |
|---|---|---|---|
| JDK, Amazon Corretto | 17.0.21.1.2 | `d8a350b5...5307e2c` | `java -version` |
| sbt launcher | 1.13.1 | `4a20e36c...4083be` | `sbt --script-version` |
| scalafmt CLI (native) | 3.11.5 | `4a29c365...fae290` | `scalafmt --version` |

Full checksums are in `versions.sh`.

- JDK: the Corretto checksum is the one Amazon publishes on its release page; the
  download matched it.
  Java 17 is the Amazon Corretto default for EMR 7.x (checked on the 7.14.0 release
  notes), and Spark 3.5 and Hadoop 3.4 on EMR 7.x run on it.
  Spark 4.x supports Java 17 and 21, so 17 is also the safe floor if EMR 8 is ever used.
  Corretto matches the EMR runtime vendor, so local test behavior matches the cluster.
  The system Java 25 is newer than anything the EMR stack supports and is not used.
- sbt launcher: 1.13.1 is the newest 1.x release.
  sbt 2.x (2.1.0) exists but is a new major line, and the plugin set this build needs
  (assembly, scalafmt, Scala 3 tooling) is proven on 1.x.
  The checksum is the `.sha256` file published with the release.
  build-engineer must set `sbt.version=1.13.1` in `project/build.properties`.
- scalafmt: the native `scalafmt-linux-glibc` binary starts in milliseconds and needs no
  JVM, unlike a coursier-launched jar, and it avoids installing coursier as one more
  tool.
  It downloads the `scalafmt-core` named in `.scalafmt.conf` on demand.
  The checksum is the SHA-256 digest GitHub publishes for the release asset; the
  scalafmt project publishes no separate checksum file.
  build-engineer must set `version = "3.11.5"` in `.scalafmt.conf`.

## Infrastructure tools, installed and pinned by this repository (Phase 12)

All release pages, checksum files and signatures below were checked on 2026-11-26.

| Tool | Pin | Checksum (sha256) | Observed version command |
|---|---|---|---|
| Terraform | 1.17.2 | `e6f16b2f...96605b` | `terraform version` |
| AWS CLI v2 | 2.35.21 | `1fe66526...e792ee` | `aws --version` |
| trivy | 0.76.2 | `bc0fc726...089fdbf` | `trivy --version` |
| shellcheck | 0.11.0 | `b7af85e4...54fe6f6` | `shellcheck --version` |

- Terraform: 1.17.2 is the newest stable release (published 2026-11-11) and the version
  the human already used (snap).
  The S3 backend's native locking (`use_lockfile`) is generally available since 1.11.0
  (<https://raw.githubusercontent.com/hashicorp/terraform/v1.11/CHANGELOG.md>);
  the backend docs mark DynamoDB locking deprecated
  (<https://developer.hashicorp.com/terraform/language/backend/s3>).
  Checksum: `terraform_1.17.2_SHA256SUMS` from
  <https://releases.hashicorp.com/terraform/1.17.2/>.
  Its `.sig` verified with the key from
  <https://www.hashicorp.com/.well-known/pgp-key.txt>, fingerprint
  `C874 011F 0AB4 0511 0D02 1055 3436 5D94 72D7 468F`; the download matched.
- AWS CLI: 2.35.21 is the version already recorded and in use (snap), so the move to a
  user-space install changes no CLI behavior.
  It is not the newest (the changelog top was 2.42.4); an upgrade is a separate change.
  Changelog: <https://raw.githubusercontent.com/aws/aws-cli/v2/CHANGELOG.rst>.
  AWS publishes no sha256, only a PGP signature
  (<https://docs.aws.amazon.com/cli/latest/userguide/getting-started-version.html>).
  `awscli-exe-linux-x86_64-2.35.21.zip.sig` from `https://awscli.amazonaws.com/`
  verified with the key on that page, fingerprint
  `FB5D B77F D5C1 18B8 0511 ADA8 A631 0ACC 4672 475C` (expires 2027-07-01); the pinned
  sha256 was computed from that verified zip.
  `install.sh` checks the sha256, not the signature, so repeat the signature check at
  every version bump.
- trivy: the one infrastructure security scanner (config scanning), so tfsec is not
  installed.
  0.76.2 is the newest release (2026-11-05,
  <https://github.com/aquasecurity/trivy/releases/tag/v0.76.2>).
  The March 2026 compromise lists the binary 0.69.4 and Docker Hub images 0.69.5 and
  0.69.6 as affected
  (<https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23>).
  Checksum: `trivy_0.76.2_checksums.txt` from the release, equal to GitHub's asset
  digest and to the download.
  The sigstore bundle was not verified (no cosign here).
  For scans tied to this pin run
  `trivy config --skip-check-update --disable-telemetry --skip-version-check <dir>`;
  without `--skip-check-update` trivy fetches a checks bundle that is not pinned.
- shellcheck: 0.11.0 is the newest release (2025-08-04,
  <https://github.com/koalaman/shellcheck/releases/tag/v0.11.0>).
  The project publishes no checksum file or signature.
  The pin is the SHA-256 digest GitHub reports for the release asset, equal to the
  download.

## Recorded only, pre-installed by the human and never reinstalled here

| Tool | Version | Observed version command and output |
|---|---|---|
| uv, uvx | 0.13.9 | `uv --version`, `uvx --version` (`uv 0.13.9 (x86_64-unknown-linux-gnu)`) |
| terraform-mcp-server | 1.4.1 (installed by the human, checksum verified against HashiCorp SHA256SUMS) | `terraform-mcp-server --version` |
| Claude Code | 2.1.332 (was 2.1.330; self-update observed 2026-11-26) | `claude --version` (`2.1.332 (Claude Code)`) |
| Python 3 | 3.12.3 (3.11 or later required) | `python3 --version` (`Python 3.12.3`) |
| Codex (optional) | to record if used | `codex --version` |

- Run `toolchain/install.sh`; its verify step prints each observed version. Copy the
  observed values into this table and into `toolchain/versions.sh`; from then on the
  verify step fails on a drift.
- uv and uvx start the `aws-api` and `aws-docs` MCP servers.
- terraform-mcp-server is pinned to 1.4.1 by `docs/agents.md` section 5.
- The AWS CLI (now installed by this repository) is for the human and for the gated
  deploy scripts.
- Claude Code (and Codex, if used) are the harnesses.
  They update themselves, so a drift is reported, not repaired; the ledger is then
  updated by this role.
- Terraform is now installed by this repository; infra-engineer adds the
  `required_version` and provider constraints, which must admit 1.17.2.
- Python 3.11 or later runs every hook, the agent-file validator (tomllib) and the
  dataset scripts. Without it the guards fail open (Phase 01 audit defect 3), so the
  install step fails if it is older or missing.

## Mismatches with other files

Check with grep before ratifying phase 02:

- `project/build.properties` states `sbt.version=1.13.1`, which matches the sbt pin.
- `.scalafmt.conf` states `version = "3.11.5"`, which matches the scalafmt pin.

No mismatch is open.
A later change to either file is reported to build-engineer, the file's owner.
