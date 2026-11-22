# Pinned machine tool versions, the single source for install.sh and env.sh.
# Reasons live in LEDGER.md.  Edit both in one change.

JDK_VERSION=17.0.21.1.2
JDK_URL=https://corretto.aws/downloads/resources/17.0.21.1.2/amazon-corretto-17.0.21.1.2-linux-x64.tar.gz
JDK_SHA256=d8a350b5988f58e6186ddc1db584af170e9d56862816d4a578e9570d85307e2c

SBT_VERSION=1.13.1
SBT_URL=https://github.com/sbt/sbt/releases/download/v1.13.1/sbt-1.13.1.tgz
SBT_SHA256=4a20e36c1642b6d5be34ef641d2ed6c8c0d464e6145283730d5566716e4083be

SCALAFMT_VERSION=3.11.5
SCALAFMT_URL=https://github.com/scalameta/scalafmt/releases/download/v3.11.5/scalafmt-linux-glibc
SCALAFMT_SHA256=4a29c3651f64fbaf9adcca01c6ac2ba0e8281a3d20e08c12b152582139fae290

# Pre-installed by the human, verified only, never reinstalled.
# An empty value means "not recorded yet": install.sh prints the observed version so it
# can be written here and in LEDGER.md. Codex is optional (only if that harness is used).
UV_VERSION=0.13.9
TERRAFORM_MCP_SERVER_VERSION=1.4.1
AWS_CLI_VERSION=2.35.21
CLAUDE_CODE_VERSION=2.1.330
CODEX_VERSION=
TERRAFORM_VERSION=1.17.2
# python3 runs every hook, the validator and the dataset scripts; 3.11+ (tomllib).
PYTHON3_VERSION=3.12.3
