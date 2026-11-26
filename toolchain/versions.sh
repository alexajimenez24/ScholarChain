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

# Infrastructure tools (Phase 12). Checksum sources and check dates are in LEDGER.md.
TERRAFORM_VERSION=1.17.2
TERRAFORM_URL=https://releases.hashicorp.com/terraform/1.17.2/terraform_1.17.2_linux_amd64.zip
TERRAFORM_SHA256=e6f16b2f0b137ee5729caa8c4e79a3bae4ee261acd198a1fcfa8584a6296605b

# AWS publishes a PGP signature, no sha256; this hash is of the signature-verified zip.
AWS_CLI_VERSION=2.35.21
AWS_CLI_URL=https://awscli.amazonaws.com/awscli-exe-linux-x86_64-2.35.21.zip
AWS_CLI_SHA256=1fe665267a6149dfb8551cec52b419fa6e82533fab6dd7678939209246e792ee

TRIVY_VERSION=0.76.2
TRIVY_URL=https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz
TRIVY_SHA256=bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf

# No vendor checksum file; this is the GitHub release asset digest.
SHELLCHECK_VERSION=0.11.0
SHELLCHECK_URL=https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.gz
SHELLCHECK_SHA256=b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6

# Pre-installed by the human, verified only, never reinstalled.
# An empty value means "not recorded yet": install.sh prints the observed version so it
# can be written here and in LEDGER.md. Codex is optional (only if that harness is used).
UV_VERSION=0.13.9
TERRAFORM_MCP_SERVER_VERSION=1.4.1
CLAUDE_CODE_VERSION=2.1.332
CODEX_VERSION=
# python3 runs every hook, the validator and the dataset scripts; 3.11+ (tomllib).
PYTHON3_VERSION=3.12.3
