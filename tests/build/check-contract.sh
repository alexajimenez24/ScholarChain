#!/usr/bin/env bash
# Black-box test of the build's own contract: what `sbt check` and markTestRun promise.
# Owner: test-engineer. Not part of `sbt check` (it runs sbt recursively, several times).
# How and when to run it: tests/build/README.md.
#
# Every scenario runs in a throwaway copy of the repository under the system temp
# directory ($TMPDIR, default /tmp). The real working tree is never written: the copy
# holds only the files git sees (tracked plus untracked, not ignored), with no .git and no
# target/ folder, and each scenario adds its own fixture files to its own copy.
#
# Scenarios and the contract clause each one checks (spec = docs/spec/assignment.md,
# Phase 2 work order):
#   pass         `check` passes and touches .claude/.last-test-run (spec line 1021:
#                markTestRun updates the marker only after tests pass). A suite directly
#                in tests/contracts/ and tests/adversarial/ compiles and runs, and every
#                suite runs exactly once per `check` (spec line 1023: check runs
#                compilation, tests and contract tests; spec line 1046: `sbt check` has a
#                stable definition). A .scala file under a test resources/ folder, under
#                a stale tests/<folder>/target/ and directly in a module base folder is
#                never compiled (spec line 1046).
#   fail-contract   a failing MUnit test in tests/contracts/: `check` fails, the marker
#                keeps its old mtime, the failing test ran exactly once (spec lines 1021
#                and 1023).
#   fail-unit    the same with a failing module unit test in tests/module-unit/core/.
#   fail-format  a badly formatted but valid test source: `check` fails at the format
#                check, before any test runs, and the marker keeps its old mtime (spec
#                line 1023: check runs format checking; spec line 1021).
#
# Options (environment variables):
#   BUILD_CONTRACT_SCENARIOS  space-separated subset to run (default: all four)
#   BUILD_CONTRACT_KEEP=1     keep the temp copies and sbt logs for debugging
#   BUILD_CONTRACT_TIMEOUT    seconds allowed per sbt run (default 900)
#
# Exit status: 0 when every scenario passes, 1 when any assertion fails, 2 on a setup
# error (missing tool, bad repository, sbt not runnable).

set -Eeuo pipefail
# An unexpected command failure is a defect of this script, never a silent exit.
trap 'printf "build-contract: INTERNAL ERROR at line %s: %s\n" "$LINENO" \
  "$BASH_COMMAND" >&2; exit 2' ERR

readonly ALL_SCENARIOS="pass fail-contract fail-unit fail-format"
readonly OLD_MTIME="2001-01-01 00:00:00"

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scenarios="${BUILD_CONTRACT_SCENARIOS:-$ALL_SCENARIOS}"
keep="${BUILD_CONTRACT_KEEP:-0}"
sbt_timeout="${BUILD_CONTRACT_TIMEOUT:-900}"
work=""
failures=0
passed_scenarios=()
failed_scenarios=()

say() { printf '%s\n' "$*"; }
die() {
  printf 'build-contract: SETUP ERROR: %s\n' "$*" >&2
  exit 2
}

cleanup() {
  if [[ -n "$work" && -d "$work" ]]; then
    if [[ "$keep" == "1" ]]; then
      say "build-contract: kept temp copies and logs in $work"
    else
      rm -rf -- "$work"
    fi
  fi
}
trap cleanup EXIT
trap 'die "interrupted"' INT TERM

# Preconditions -------------------------------------------------------------------

[[ "$scenarios" =~ [^[:space:]] ]] ||
  die "BUILD_CONTRACT_SCENARIOS must contain at least one scenario name"
for tool in git tar timeout stat touch mktemp date grep wc tail sed tr; do
  command -v "$tool" >/dev/null 2>&1 || die "required tool '$tool' is not on PATH"
done
[[ -f "$repo/build.sbt" ]] || die "no build.sbt at $repo; run the script from the repository"
[[ -f "$repo/toolchain/env.sh" ]] || die "no toolchain/env.sh at $repo"
git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
  die "$repo is not a git work tree; the copy is built from git's file list"
[[ "$sbt_timeout" =~ ^[0-9]+$ ]] || die "BUILD_CONTRACT_TIMEOUT must be whole seconds"
for s in $scenarios; do
  case " $ALL_SCENARIOS " in
    *" $s "*) ;;
    *) die "unknown scenario '$s'; known: $ALL_SCENARIOS" ;;
  esac
done

work="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-build-contract.XXXXXX")"
work="$(cd "$work" && pwd -P)"
case "$work/" in
  "$(cd "$repo" && pwd -P)/"*) die "temp dir $work is inside the repository; set TMPDIR" ;;
esac

real_marker="$repo/.claude/.last-test-run"
mtime_of() { if [[ -e "$1" ]]; then stat -c %Y -- "$1"; else echo absent; fi; }
real_marker_before="$(mtime_of "$real_marker")"
real_status_before="$(git -C "$repo" status --porcelain)"

# Repository copy -----------------------------------------------------------------

# The file list git sees: tracked and untracked, not ignored. That leaves out .git,
# every target/ folder, .env files, secrets and the real marker. A belt-and-braces
# filter drops any path with a target or .git component and any .env file anyway.
copy_repo() {
  local dest="$1" list="$1.files"
  mkdir -p -- "$dest"
  : >"$list"
  while IFS= read -r -d '' f; do
    case "/$f/" in
      */target/* | */.git/*) continue ;;
    esac
    case "${f##*/}" in
      .env | .env.*) continue ;;
    esac
    [[ -f "$repo/$f" ]] || continue # tracked but deleted in the working tree
    printf '%s\0' "$f" >>"$list"
  done < <(git -C "$repo" ls-files -z --cached --others --exclude-standard)
  [[ -s "$list" ]] || die "git listed no files to copy"
  tar -C "$repo" --null -T "$list" -cf - | tar -C "$dest" -xf -
  rm -f -- "$list"
  [[ -f "$dest/build.sbt" ]] || die "the copy at $dest has no build.sbt"
}

# Fixtures ------------------------------------------------------------------------
# Test sources are scalafmt-clean on purpose, so only fail-format trips the format
# check. Each suite prints a token assembled at run time, so the token never appears
# in a source line that a compiler error could echo.

write_file() {
  mkdir -p -- "$(dirname "$1")"
  cat >"$1"
}

not_scala() {
  write_file "$1" <<'EOF'
this file is deliberately not Scala {{{ and must never be compiled or format-checked
EOF
}

passing_suite() { # path class-name token-suffix
  write_file "$1" <<EOF
class $2 extends munit.FunSuite {
  test("build contract: $3 runs") {
    println("BCT-RAN-" + "$3")
  }
}
EOF
}

resource_suite() { # path class-name token-suffix
  write_file "$1" <<EOF
class $2 extends munit.FunSuite {
  test("build contract: $3 runs and sees resources/ as resources") {
    println("BCT-RAN-" + "$3")
    val res = getClass.getResource("/BctNotCompiled.scala")
    assert(res != null, "resources/BctNotCompiled.scala must be on the test classpath")
  }
}
EOF
}

failing_suite() { # path class-name token-suffix
  write_file "$1" <<EOF
class $2 extends munit.FunSuite {
  test("build contract: $3 fails on purpose") {
    println("BCT-RAN-" + "$3")
    assertEquals(1 + 1, 3)
  }
}
EOF
}

badly_formatted_suite() { # path class-name
  write_file "$1" <<EOF
class $2    extends munit.FunSuite {
  test("build contract: badly formatted suite runs") {
    println("BCT-RAN-" + "badly-formatted")
  }
}
EOF
}

fixtures_pass() {
  local c="$1"
  resource_suite "$c/tests/contracts/BctContractsTopSuite.scala" \
    BctContractsTopSuite contracts-top
  passing_suite "$c/tests/adversarial/BctAdversarialTopSuite.scala" \
    BctAdversarialTopSuite adversarial-top
  passing_suite "$c/tests/module-unit/core/BctCoreUnitSuite.scala" \
    BctCoreUnitSuite core-unit
  not_scala "$c/tests/contracts/resources/BctNotCompiled.scala"
  not_scala "$c/tests/adversarial/resources/BctNotCompiled.scala"
  not_scala "$c/tests/module-unit/core/resources/BctNotCompiled.scala"
  not_scala "$c/tests/contracts/target/stale/BctStale.scala"
  not_scala "$c/tests/adversarial/target/stale/BctStale.scala"
  not_scala "$c/modules/core/BctStrayInBase.scala"
}

fixtures_fail_contract() {
  failing_suite "$1/tests/contracts/BctFailingContractSuite.scala" \
    BctFailingContractSuite failing-contract
  passing_suite "$1/tests/adversarial/BctAdversarialTopSuite.scala" \
    BctAdversarialTopSuite adversarial-top
}

fixtures_fail_unit() {
  failing_suite "$1/tests/module-unit/core/BctFailingUnitSuite.scala" \
    BctFailingUnitSuite failing-unit
  passing_suite "$1/tests/contracts/BctContractsTopSuite.scala" \
    BctContractsTopSuite contracts-top
}

fixtures_fail_format() {
  badly_formatted_suite "$1/tests/contracts/BctBadFormatSuite.scala" BctBadFormatSuite
  passing_suite "$1/tests/adversarial/BctAdversarialTopSuite.scala" \
    BctAdversarialTopSuite adversarial-top
}

# Running sbt and asserting -------------------------------------------------------

scenario_failed=0
fail() {
  printf '  FAIL: %s\n' "$*"
  scenario_failed=1
}
ok() { printf '  ok:   %s\n' "$*"; }

count() { # count occurrences of a fixed string in the log (0 is a valid answer)
  { grep -o -F -- "$1" "$log" || true; } | wc -l | tr -d ' '
}

expect_count() { # description token expected
  local n
  n="$(count "$2")"
  if [[ "$n" == "$3" ]]; then ok "$1 ($2 seen $n times)"; else
    fail "$1: expected '$2' $3 times, saw $n"
  fi
}

expect_present() { # description fixed-string
  if grep -q -F -- "$2" "$log"; then ok "$1"; else fail "$1: '$2' not in the sbt output"; fi
}

expect_absent() { # description fixed-string
  if grep -q -F -- "$2" "$log"; then fail "$1: '$2' is in the sbt output"; else ok "$1"; fi
}

run_check() { # copy-dir log-file; sets rc
  local c="$1"
  rc=0
  (
    cd "$c"
    # shellcheck disable=SC1091
    . toolchain/env.sh
    command -v sbt >/dev/null 2>&1 || {
      echo "build-contract: sbt is not on PATH after sourcing toolchain/env.sh"
      exit 97
    }
    timeout "$sbt_timeout" sbt -batch --no-colors -Dsbt.supershell=false check
  ) >"$2" 2>&1 || rc=$?
  if [[ "$rc" == 97 ]]; then die "sbt not found after sourcing toolchain/env.sh"; fi
  if [[ "$rc" == 124 ]]; then fail "sbt check timed out after ${sbt_timeout}s"; fi
}

run_scenario() {
  local name="$1" c="$work/$1" marker before after start
  log="$work/$1.log"
  scenario_failed=0
  say ""
  say "== scenario $name"
  copy_repo "$c"
  case "$name" in
    pass) fixtures_pass "$c" ;;
    fail-contract) fixtures_fail_contract "$c" ;;
    fail-unit) fixtures_fail_unit "$c" ;;
    fail-format) fixtures_fail_format "$c" ;;
  esac
  marker="$c/.claude/.last-test-run"
  mkdir -p -- "$c/.claude"
  touch -d "$OLD_MTIME" -- "$marker"
  before="$(mtime_of "$marker")"
  start="$(date +%s)"
  say "  running: sbt -batch check in $c (log $log)"
  run_check "$c" "$log"
  after="$(mtime_of "$marker")"
  say "  sbt exit code $rc; marker mtime before $before, after $after"

  expect_absent "no tool touched a resources/ .scala file" "/resources/BctNotCompiled.scala"
  expect_absent "no tool touched a stale target/ source" "BctStale.scala"
  expect_absent "no tool touched a module base-folder source" "BctStrayInBase.scala"

  case "$name" in
    pass)
      if [[ "$rc" == 0 ]]; then ok "sbt check exited 0"; else fail "sbt check exit $rc"; fi
      if [[ "$after" != absent && "$after" -gt "$before" && "$after" -ge $((start - 1)) ]]
      then ok "marker touched by this run"; else
        fail "marker not touched: before $before, after $after, run start $start"
      fi
      expect_count "top-level contracts suite ran once" "BCT-RAN-contracts-top" 1
      expect_count "top-level adversarial suite ran once" "BCT-RAN-adversarial-top" 1
      expect_count "module unit suite ran once" "BCT-RAN-core-unit" 1
      expect_count "markTestRun ran once" "markTestRun:" 1
      expect_absent "no test failed" "Failed: Total"
      ;;
    fail-contract | fail-unit)
      local token="BCT-RAN-${name/fail-/failing-}"
      if [[ "$rc" != 0 && "$rc" != 124 ]]; then ok "sbt check failed (exit $rc)"; else
        fail "sbt check exit $rc, expected a test failure"
      fi
      if [[ "$after" == "$before" ]]; then ok "marker mtime unchanged"; else
        fail "marker touched on a failing test: before $before, after $after"
      fi
      expect_count "the failing test ran exactly once" "$token" 1
      expect_present "sbt reported a test failure, not a compile error" "Failed: Total"
      expect_present "sbt raised TestsFailedException" "TestsFailedException"
      expect_absent "markTestRun body did not run" "markTestRun:"
      ;;
    fail-format)
      if [[ "$rc" != 0 && "$rc" != 124 ]]; then ok "sbt check failed (exit $rc)"; else
        fail "sbt check exit $rc, expected a format-check failure"
      fi
      if [[ "$after" == "$before" ]]; then ok "marker mtime unchanged"; else
        fail "marker touched despite a format violation: before $before, after $after"
      fi
      expect_present "the format check names the badly formatted file" "BctBadFormatSuite"
      expect_count "no test ran after the format failure" "BCT-RAN-" 0
      expect_absent "markTestRun body did not run" "markTestRun:"
      ;;
  esac

  if [[ "$scenario_failed" == 0 ]]; then
    say "  PASS $name"
    passed_scenarios+=("$name")
  else
    failures=$((failures + 1))
    failed_scenarios+=("$name")
    say "  FAILED $name; last 40 lines of $log:"
    tail -n 40 -- "$log" | sed 's/^/    | /'
  fi
  if [[ "$keep" != "1" ]]; then rm -rf -- "$c"; fi
}

say "build-contract: repository $repo"
say "build-contract: temp dir $work"
say "build-contract: scenarios: $scenarios"
for s in $scenarios; do run_scenario "$s"; done

# Isolation self-check: the real tree must look exactly as before. A concurrent edit or
# sbt run in the real repository would also trip this, so it is reported, not hidden.
say ""
if [[ "$(mtime_of "$real_marker")" != "$real_marker_before" ]]; then
  say "build-contract: WARNING the real marker $real_marker changed during the run"
  say "  (this script never writes it; check for a concurrent sbt run in the repository)"
fi
if [[ "$(git -C "$repo" status --porcelain)" != "$real_status_before" ]]; then
  say "build-contract: WARNING git status of the real repository changed during the run"
  say "  (this script never writes it; check for concurrent edits)"
fi

summary="${#passed_scenarios[@]} passed (${passed_scenarios[*]:-none})"
summary+=", $failures failed (${failed_scenarios[*]:-none})"
say "build-contract: $summary"
if [[ "$failures" -gt 0 ]]; then exit 1; fi
