#!/usr/bin/env bash
# Phase 2 static analysis runner: unstrip invocations against the verified
# target binary.
#
# The target is NEVER executed - unstrip only read-parses it.
#
# This script is a SEPARATE bash process because GitHub Actions runs steps
# with an implicit "bash -e": unstrip exits nonzero BY DESIGN for some
# invocations (--detect-garble: 0 = garbled, 1 = clean, 2 = indeterminate,
# per the pinned v1.2.0 README). Every invocation's stdout, stderr and true
# exit code is preserved; only infrastructure problems exit nonzero:
#   exit 2  usage error
#   exit 3  unstrip not executable / target not readable
#
# Order of operations (per the Phase 2 directive):
#   1. unstrip --help is recorded FIRST - the tool's actual supported
#      arguments are the authority, not assumptions.
#   2. The bare invocation (`unstrip TARGET`) always runs.
#   3. Optional invocations run ONLY when their flag appears in the
#      recorded help text; otherwise the fact is recorded, never
#      substituted with invented syntax.
#
# Outputs in <output_dir>:
#   unstrip-help.txt            + unstrip-help.stderr.txt
#   unstrip-functions.txt       + .stderr.txt   (bare invocation listing)
#   unstrip-info.txt            + .stderr.txt   (--info, when available)
#   unstrip-buildinfo.txt       + .stderr.txt   (--buildinfo, when available)
#   unstrip-capabilities.txt    + .stderr.txt   (--capabilities, when available)
#   unstrip-garble.txt          + .stderr.txt   (--detect-garble, when available)
#   exit-codes-summary.txt      name|rc|args for every invocation
#   unstrip-garble-interpretation.txt   exit-code semantics applied

set -uo pipefail

if [ $# -ne 3 ]; then
  echo "usage: unstrip-analysis.sh <unstrip_exe> <target_binary> <output_dir>" >&2
  exit 2
fi

tool="$1"
target="$2"
out="$3"

if [ ! -x "$tool" ]; then
  echo "INFRA-FAILURE: unstrip not executable: $tool" >&2
  exit 3
fi
if [ ! -r "$target" ]; then
  echo "INFRA-FAILURE: target not readable: $target" >&2
  exit 3
fi
mkdir -p "$out" || exit 3

for n in help functions info buildinfo capabilities garble; do
  : > "$out/unstrip-$n.txt"
  : > "$out/unstrip-$n.stderr.txt"
done
: > "$out/exit-codes-summary.txt"
: > "$out/unstrip-garble-interpretation.txt"

# Runs: unstrip <target> <args...>. Echoes the true exit code on stdout and
# records it in the summary. Never aborts on nonzero.
run_invocation() {
  local name="$1"
  shift
  local rc=0
  if "$tool" "$target" "$@" > "$out/unstrip-$name.txt" 2> "$out/unstrip-$name.stderr.txt"; then
    rc=0
  else
    rc=$?
  fi
  printf '%s|rc=%s|args=%s\n' "$name" "$rc" "$*" >> "$out/exit-codes-summary.txt"
  echo "$rc"
}

has_flag() {
  grep -q -- "$1" "$out/unstrip-help.txt"
}

# 1) The tool's actual supported arguments, recorded first.
help_rc=$(run_invocation help --help)
echo "# unstrip --help exit code: $help_rc" >> "$out/exit-codes-summary.txt"

# 2) The default listing (function names, files, lines) - always available.
functions_rc=$(run_invocation functions)

# 3) Optional invocations - gated on the ACTUAL help output.
if has_flag -- "--info"; then
  info_rc=$(run_invocation info --info)
else
  echo "info|skipped|--info not present in this build's recorded --help output" >> "$out/exit-codes-summary.txt"
fi

if has_flag -- "--buildinfo"; then
  buildinfo_rc=$(run_invocation buildinfo --buildinfo)
else
  echo "buildinfo|skipped|--buildinfo not present in this build's recorded --help output" >> "$out/exit-codes-summary.txt"
fi

if has_flag -- "--capabilities"; then
  capabilities_rc=$(run_invocation capabilities --capabilities)
else
  echo "capabilities|skipped|--capabilities not present in this build's recorded --help output" >> "$out/exit-codes-summary.txt"
fi

if has_flag -- "--detect-garble"; then
  garble_rc=$(run_invocation garble --detect-garble)
  {
    echo "# Garble verdict for the target binary"
    echo "# Exit-code semantics per the pinned tool's own documentation"
    echo "# (riven-labs/unstrip v1.2.0 README): 0 = garbled, 1 = clean, 2 = indeterminate."
    echo "# Raw exit code from this run: $garble_rc"
    case "$garble_rc" in
      0) echo "VERDICT: GARBLED - the binary is (or appears to be) garble-obfuscated." ;;
      1) echo "VERDICT: CLEAN - not garbled according to the tool's heuristic." ;;
      2) echo "VERDICT: INDETERMINATE - the tool could not decide." ;;
      *) echo "VERDICT: UNEXPECTED EXIT $garble_rc - see unstrip-garble.stderr.txt." ;;
    esac
  } > "$out/unstrip-garble-interpretation.txt"
else
  echo "garble|skipped|--detect-garble not present in this build's recorded --help output" >> "$out/exit-codes-summary.txt"
  echo "# --detect-garble unavailable in this build - no verdict recorded." > "$out/unstrip-garble-interpretation.txt"
fi

# Never fail on analyzer outcomes.
exit 0
