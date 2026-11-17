#!/usr/bin/env bash
# Vortex DEEP check of the working tree against BASE_SHA.
# usage: verify.sh OUT.json   (run from the workspace)
# stdout: one "path:line [rule] message" per finding
# exit:   0 clean | 51 findings | 52 no CI baseline yet | 1 error (never counts as clean)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=factory.env
source "$here/factory.env"
: "${BASE_SHA:?}"
out=${1:?usage: verify.sh OUT.json}

rc=0
sonar analyze agentic --base "$BASE_SHA" --depth DEEP --format json --force >"$out" 2>"$out.err" || rc=$?

if grep -qi 'INVALID_CONTEXT' "$out" "$out.err"; then
  echo "verify: no CI baseline for this project and branch yet" >&2
  exit 52
fi

if ! jq -e 'type == "object"' "$out" >/dev/null 2>&1; then
  echo "verify: sonar exited $rc without a JSON result" >&2
  exit 1
fi

jq -r -f "$here/findings.jq" "$out"

if [ "$rc" -eq 51 ]; then
  exit 51
fi

if [ "$rc" -ne 0 ]; then
  echo "verify: sonar exited $rc" >&2
  exit 1
fi

changed=$( { git diff --name-only --diff-filter=ACMR "$BASE_SHA"; git ls-files --others --exclude-standard; } | grep -E "$SOURCE_REGEX" || true)

problems=$(jq -r --arg changed "$changed" '
  [ (if (.globalError // null) != null then "globalError: \(.globalError.message // .globalError.kind)" else empty end),
    (if ((.failures // []) | length) > 0 then "\(.failures | length) file failure(s)" else empty end),
    (if ((.skipped // []) | length) > 0 then "\(.skipped | length) skipped file(s)" else empty end),
    (if (.summary.totalIssues // 0) > 0 then "issues reported with exit 0" else empty end),
    ( [ .files[]?.path ] as $seen
      | $changed | split("\n") | map(select(. != "")) | .[]
      | . as $f | select(any($seen[]; . == $f or endswith("/" + $f)) | not)
      | "changed file not analyzed: \($f)" ) ] | .[]' "$out")

if [ -n "$problems" ]; then
  echo "verify: not clean" >&2
  echo "$problems" >&2
  exit 1
fi
