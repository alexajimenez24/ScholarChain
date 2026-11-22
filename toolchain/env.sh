# Source this file; do not execute it.  Usage: source toolchain/env.sh
# Sets JAVA_HOME and PATH for the pinned toolchain.  Edits no shell rc file.
_tc_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$_tc_dir/versions.sh"
export SCHOLARCHAIN_TOOLS="${SCHOLARCHAIN_TOOLS:-$HOME/.local/share/scholarchain-tools}"
export JAVA_HOME="$SCHOLARCHAIN_TOOLS/jdk-$JDK_VERSION"
case ":$PATH:" in
  *":$JAVA_HOME/bin:"*) ;;
  *) export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH" ;;
esac
unset _tc_dir
