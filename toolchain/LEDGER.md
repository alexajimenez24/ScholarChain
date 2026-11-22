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
  `sbt` and `scalafmt` are symlinked into `~/.local/bin`.

### The `SCHOLARCHAIN_TOOLS` override

`SCHOLARCHAIN_TOOLS` overrides the tool install directory.
Its default is `$HOME/.local/share/scholarchain-tools`, written the same way in
`install.sh` and `env.sh`.
Leave it unset: the unset default is the reproducible install, and the paths in this
ledger assume it.

- It moves `jdk-17.0.21.1.2/`, `sbt-1.13.1/` and `scalafmt-3.11.5` under the new
  directory.
- `env.sh` sets `JAVA_HOME` from it and puts `$JAVA_HOME/bin` first on `PATH`, so sbt
  runs on the JDK in that directory.
- The `sbt` and `scalafmt` links in `~/.local/bin` point into that directory.
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

## Recorded only, pre-installed by the human and never reinstalled here

| Tool | Version | Observed version command and output |
|---|---|---|
| uv, uvx | 0.13.9 | `uv --version`, `uvx --version` (`uv 0.13.9 (x86_64-unknown-linux-gnu)`) |
| terraform-mcp-server | 1.4.1 (installed by the human, checksum verified against HashiCorp SHA256SUMS) | `terraform-mcp-server --version` |
| AWS CLI | 2.35.21 | `aws --version` (`aws-cli/2.35.21 Python/3.14.6 Linux/5.15.167.4-microsoft-standard-WSL2 exe/x86_64.ubuntu.24`) |
| Claude Code | 2.1.330 | `claude --version` (`2.1.330 (Claude Code)`) |
| Terraform | 1.17.2 | `terraform version` (`Terraform v1.17.2 on linux_amd64`) |
| Python 3 | 3.12.3 (3.11 or later required) | `python3 --version` (`Python 3.12.3`) |
| Codex (optional) | to record if used | `codex --version` |

- Run `toolchain/install.sh`; its verify step prints each observed version. Copy the
  observed values into this table and into `toolchain/versions.sh`; from then on the
  verify step fails on a drift.
- uv and uvx start the `aws-api` and `aws-docs` MCP servers.
- terraform-mcp-server is pinned to 1.4.1 by `docs/agents.md` section 5.
- The AWS CLI is for the human and for the gated deploy scripts.
- Claude Code (and Codex, if used) are the harnesses.
  They update themselves, so a drift is reported, not repaired; the ledger is then
  updated by this role.
- Terraform is installed by the human; infra-engineer adds the provider constraints.
- Python 3.11 or later runs every hook, the agent-file validator (tomllib) and the
  dataset scripts. Without it the guards fail open (Phase 01 audit defect 3), so the
  install step fails if it is older or missing.

## Mismatches with other files

Check with grep before ratifying phase 02:

- `project/build.properties` states `sbt.version=1.13.1`, which matches the sbt pin.
- `.scalafmt.conf` states `version = "3.11.5"`, which matches the scalafmt pin.

No mismatch is open.
A later change to either file is reported to build-engineer, the file's owner.
