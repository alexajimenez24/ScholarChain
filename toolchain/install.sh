#!/usr/bin/env bash
# Idempotent user-space installer for the pinned toolchain (see LEDGER.md).
# No sudo, no system package manager; writes only under $HOME.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "$here/versions.sh"

tools="${SCHOLARCHAIN_TOOLS:-$HOME/.local/share/scholarchain-tools}"
bin="$HOME/.local/bin"
mkdir -p "$tools" "$bin"
tmp="$(mktemp -d "$tools/.tmp.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
failures=0

# Exact version-token match: ver must not be preceded by a digit or dot, nor followed by
# a digit or by a dot and a digit (so 0.13.9 does not match 0.13.90 or 0.13.9.1).
vmatch() { # version text
  local re
  re="$(printf '%s' "$1" | sed 's/[.[\*^$+?(){}|]/\\&/g')"
  printf '%s' "$2" | grep -Eq "(^|[^0-9.])${re}(\$|[^0-9.]|\.([^0-9]|\$))"
}

fetch() { # url dest sha256
  curl -fsSL --retry 3 "$1" -o "$2"
  echo "$3  $2" | sha256sum -c --status \
    || { echo "checksum mismatch for $1" >&2; exit 1; }
  echo "    sha256 ok: $3"
}

echo "==> JDK Corretto $JDK_VERSION"
jdk_dir="$tools/jdk-$JDK_VERSION"
if [ -x "$jdk_dir/bin/java" ] && vmatch "$JDK_VERSION" "$("$jdk_dir/bin/java" -version 2>&1)"; then
  echo "    already installed, skipping"
else
  fetch "$JDK_URL" "$tmp/jdk.tgz" "$JDK_SHA256"
  mkdir "$tmp/jdk"
  tar -xzf "$tmp/jdk.tgz" -C "$tmp/jdk" --strip-components=1
  rm -rf "$jdk_dir"
  mv "$tmp/jdk" "$jdk_dir"
fi

echo "==> sbt launcher $SBT_VERSION"
sbt_dir="$tools/sbt-$SBT_VERSION"
if [ -x "$sbt_dir/bin/sbt" ]; then
  echo "    already installed, skipping"
else
  fetch "$SBT_URL" "$tmp/sbt.tgz" "$SBT_SHA256"
  mkdir "$tmp/sbt"
  tar -xzf "$tmp/sbt.tgz" -C "$tmp/sbt" --strip-components=1
  rm -rf "$sbt_dir"
  mv "$tmp/sbt" "$sbt_dir"
fi
ln -sfn "$sbt_dir/bin/sbt" "$bin/sbt"

echo "==> scalafmt CLI $SCALAFMT_VERSION"
sf="$tools/scalafmt-$SCALAFMT_VERSION"
if [ -x "$sf" ] && vmatch "$SCALAFMT_VERSION" "$("$sf" --version 2>&1)"; then
  echo "    already installed, skipping"
else
  fetch "$SCALAFMT_URL" "$tmp/scalafmt" "$SCALAFMT_SHA256"
  chmod +x "$tmp/scalafmt"
  mv "$tmp/scalafmt" "$sf"
fi
ln -sfn "$sf" "$bin/scalafmt"

echo "==> verify-only tools (pre-installed by the human, never reinstalled)"
check() { # name expected observed
  if [ -z "$2" ]; then
    echo "    record   $1 (no pin yet), observed: $3"
  elif vmatch "$2" "$3"; then
    echo "    ok       $1 $2"
  else
    echo "    MISMATCH $1 expected $2, observed: $3" >&2
    failures=$((failures + 1))
  fi
}
# Tools are looked up on PATH (they may live in ~/.local/bin, /snap/bin or /usr/local/bin).
obs() {
  if command -v "$1" >/dev/null 2>&1; then "$@" 2>&1 | head -n 3 | tr '\n' ' ' || true
  else echo "not on PATH"; fi
}
check uv "$UV_VERSION" "$(obs uv --version)"
check uvx "$UV_VERSION" "$(obs uvx --version)"
check terraform-mcp-server "$TERRAFORM_MCP_SERVER_VERSION" "$(obs terraform-mcp-server --version)"
check aws-cli "$AWS_CLI_VERSION" "$(obs aws --version)"
check claude "$CLAUDE_CODE_VERSION" "$(obs claude --version)"
check terraform "$TERRAFORM_VERSION" "$(obs terraform version)"
check python3 "$PYTHON3_VERSION" "$(obs python3 --version)"
if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
  echo "    MISMATCH python3: 3.11 or later is required (hooks and validator use tomllib)" >&2
  failures=$((failures + 1))
fi
if [ -n "$CODEX_VERSION" ]; then check codex "$CODEX_VERSION" "$(obs codex --version)"; fi

echo "==> installed versions"
JAVA_HOME="$jdk_dir" "$jdk_dir/bin/java" -version 2>&1 | head -n 1 | sed 's/^/    /'
JAVA_HOME="$jdk_dir" PATH="$jdk_dir/bin:$PATH" "$sbt_dir/bin/sbt" --script-version 2>&1 | tail -n 1 | sed 's/^/    sbt /'
"$sf" --version | sed 's/^/    /'

if [ "$failures" -gt 0 ]; then
  echo "==> $failures verify-only mismatch(es)" >&2
  exit 1
fi
echo "==> done; run: source toolchain/env.sh"
