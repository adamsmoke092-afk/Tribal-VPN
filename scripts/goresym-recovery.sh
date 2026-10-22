#!/usr/bin/env bash
# GoReSym metadata recovery for the UDP Custom analysis workflow.
#
# Usage: goresym-recovery.sh <analyzer_exe> <target_binary> <output_dir>
#
# GitHub Actions runs workflow steps with an implicit "bash -e", which
# kills a step on the first nonzero command. This script is therefore a
# SEPARATE bash process: expected analyzer failures (stripped or
# unsupported binary, JSON error object) NEVER fail it. Every diagnostic
# is preserved and the caller continues to findings generation. Only
# infrastructure problems exit nonzero:
#   exit 2  usage error
#   exit 3  analyzer not executable / target not readable
#
# Output files in <output_dir>:
#   analysis-status.txt        RECOVERED | NO-METADATA
#   goresym-exit-codes.txt      orig_rc / retry_rc / human_rc / carved / status
#   goresym-full.json          analyzer stdout of the FINAL attempt
#   goresym-attempt1-stdout.json  analyzer stdout of the FIRST attempt
#   goresym-stderr.txt         analyzer stderr (both attempts, appended)
#   goresym-version-carve.txt  go1.x string carved from raw bytes (if any)
#   json-parse-status.txt      every jq validation/extraction outcome
#   goresym-summary.txt        Version/BuildId/Arch, or a no-metadata note
#   goresym-keys.txt           top-level JSON keys (schema adaptation aid)
#   goresym-files.txt          embedded source paths
#   user-functions.txt         recovered user functions with addresses
#   std-functions.txt          recovered std functions with addresses
#   goresym-human.txt          -human flat listing (when recovery succeeded)

set -uo pipefail

if [ $# -ne 3 ]; then
  echo "usage: goresym-recovery.sh <analyzer_exe> <target_binary> <output_dir>" >&2
  exit 2
fi

analyzer="$1"
target="$2"
out="$3"

if [ ! -x "$analyzer" ]; then
  echo "INFRA-FAILURE: analyzer not executable: $analyzer" >&2
  exit 3
fi
if [ ! -r "$target" ]; then
  echo "INFRA-FAILURE: target not readable: $target" >&2
  exit 3
fi
mkdir -p "$out" || exit 3

: > "$out/goresym-full.json"
: > "$out/goresym-attempt1-stdout.json"
: > "$out/goresym-human.txt"
: > "$out/goresym-keys.txt"
: > "$out/goresym-summary.txt"
: > "$out/goresym-stderr.txt"
: > "$out/goresym-files.txt"
: > "$out/user-functions.txt"
: > "$out/std-functions.txt"
: > "$out/json-parse-status.txt"
: > "$out/goresym-version-carve.txt"

orig_rc=0
retry_rc=-1
human_rc=-1

# Carve a Go version string from the raw bytes. This is EVIDENCE for the
# -v retry (README: version override is needed for some stripped
# binaries) - the retry only runs when a version was actually recovered.
carved=$(grep -aoE 'go1\.[0-9]+(\.[0-9]+)?' "$target" 2>/dev/null \
  | sort | uniq -c | sort -rn | head -n 1 | awk '{print $2}')
if [ -n "$carved" ]; then
  echo "$carved" > "$out/goresym-version-carve.txt"
fi

# True only when stdout is a nonempty object carrying at least one of the
# metadata fields GoReSym documents - an error object does NOT count as
# recovered metadata.
json_has_metadata() {
  jq -e 'type == "object" and (has("Version") or has("UserFunctions") or has("StdFunctions") or has("TabMeta"))' \
    "$1" >/dev/null 2>&1
}

validate_output() { # $1 = json file, $2 = label
  if [ ! -s "$1" ]; then
    echo "$2 stdout empty" >> "$out/json-parse-status.txt"
    return 1
  fi
  if ! json_has_metadata "$1"; then
    echo "$2 stdout has no metadata fields (error object or other shape)" >> "$out/json-parse-status.txt"
    return 1
  fi
  return 0
}

if "$analyzer" -t -d -p -strings "$target" \
    > "$out/goresym-full.json" 2> "$out/goresym-stderr.txt"; then
  orig_rc=0
else
  orig_rc=$?
fi
cp "$out/goresym-full.json" "$out/goresym-attempt1-stdout.json"

recovered=0
validate_output "$out/goresym-full.json" "attempt1" || recovered=1

if [ "$recovered" -ne 0 ] && [ -n "$carved" ]; then
  if "$analyzer" -v "${carved#go}" -t -d -p -strings "$target" \
      > "$out/goresym-full.json" 2>> "$out/goresym-stderr.txt"; then
    retry_rc=0
  else
    retry_rc=$?
  fi
  recovered=0
  validate_output "$out/goresym-full.json" "retry" || recovered=1
fi

status="NO-METADATA"
if [ "$recovered" -eq 0 ]; then
  status="RECOVERED"
  if "$analyzer" -human -t -d -p "$target" \
      > "$out/goresym-human.txt" 2>> "$out/goresym-stderr.txt"; then
    human_rc=0
  else
    human_rc=$?
    echo "human listing failed rc=$human_rc" >> "$out/json-parse-status.txt"
  fi
  # Extractions against the ACTUAL recovered JSON - every jq outcome is
  # recorded, never silently discarded.
  if ! jq -r 'keys' "$out/goresym-full.json" \
      > "$out/goresym-keys.txt" 2>> "$out/json-parse-status.txt"; then
    echo "jq keys extraction failed" >> "$out/json-parse-status.txt"
    : > "$out/goresym-keys.txt"
  fi
  if ! jq -r '[.Version? // "unknown", .BuildId? // "none", .Arch? // "unknown"] | @tsv' \
      "$out/goresym-full.json" > "$out/goresym-summary.txt" 2>> "$out/json-parse-status.txt"; then
    echo "jq summary extraction failed" >> "$out/json-parse-status.txt"
    : > "$out/goresym-summary.txt"
  fi
  if ! jq -r '.UserFunctions[]? | [(.StartAddress // 0 | tostring), (.FullFuncName // .FuncName // .Name // "?")] | @tsv' \
      "$out/goresym-full.json" > "$out/user-functions.txt" 2>> "$out/json-parse-status.txt"; then
    echo "jq UserFunctions extraction failed" >> "$out/json-parse-status.txt"
    : > "$out/user-functions.txt"
  fi
  if ! jq -r '.StdFunctions[]? | [(.StartAddress // 0 | tostring), (.FullFuncName // .FuncName // .Name // "?")] | @tsv' \
      "$out/goresym-full.json" > "$out/std-functions.txt" 2>> "$out/json-parse-status.txt"; then
    echo "jq StdFunctions extraction failed" >> "$out/json-parse-status.txt"
    : > "$out/std-functions.txt"
  fi
  if ! jq -r '.Files[]? | tostring' "$out/goresym-full.json" \
      > "$out/goresym-files.txt" 2>> "$out/json-parse-status.txt"; then
    echo "jq Files extraction failed" >> "$out/json-parse-status.txt"
    : > "$out/goresym-files.txt"
  fi
else
  echo "no metadata recovered - see exit codes, stderr and parse status" > "$out/goresym-summary.txt"
fi

echo "$status" > "$out/analysis-status.txt"
{
  echo "orig_rc=$orig_rc"
  echo "retry_rc=$retry_rc"
  echo "human_rc=$human_rc"
  echo "carved=$carved"
  echo "status=$status"
} > "$out/goresym-exit-codes.txt"

exit 0
