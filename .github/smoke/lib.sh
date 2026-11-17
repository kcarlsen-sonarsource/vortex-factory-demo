#!/usr/bin/env bash
# Temporary smoke-test helpers. Removed after the smoke run.
set -u
: "${OUT:?}"
mkdir -p "$OUT"
export PATH="$HOME/.local/bin:$PATH"

# rec NAME CMD...: run CMD, keep stdout/stderr/rc/timing under $OUT, never abort.
rec() {
  local name=$1
  shift
  local s e rc su
  su=$(date -u +%FT%TZ)
  s=$(date +%s.%N)
  "$@" >"$OUT/$name.out" 2>"$OUT/$name.err"
  rc=$?
  e=$(date +%s.%N)
  printf '{"name":"%s","start":"%s","rc":%d,"seconds":%s}\n' "$name" "$su" "$rc" "$(awk -v s="$s" -v e="$e" 'BEGIN{printf "%.1f", e-s}')" >>"$OUT/records.jsonl"
  echo "[$name] rc=$rc $(awk -v s="$s" -v e="$e" 'BEGIN{printf "%.1fs", e-s}')"
  return 0
}
rc_of() { jq -r --arg n "$1" 'select(.name==$n)|.rc' "$OUT/records.jsonl" | tail -n1; }

# claude_run NAME PROMPT_FILE [extra claude flags...]: production flags, prompt on stdin, JSONL in $OUT/NAME.out
claude_run() {
  local name=$1 pf=$2
  shift 2
  # shellcheck source=/dev/null
  source "$GITHUB_WORKSPACE/.github/factory/factory.env"
  local tools
  tools="$(tr '\n' ' ' <"$GITHUB_WORKSPACE/.github/factory/allowed-tools.txt") $AGENT_BUILD_TOOLS"
  rec "$name" bash -c 'pf=$1; tools=$2; mcp=$3; shift 3; exec claude -p "$@" --permission-mode dontAsk --permission-prompts none --allowedTools "$tools" --strict-mcp-config --mcp-config "$mcp" --output-format stream-json --verbose --include-hook-events <"$pf"' _ "$pf" "$tools" "$GITHUB_WORKSPACE/.github/factory/mcp-empty.json" "$@"
}
result_of() { jq -c 'select(.type=="result")' "$OUT/$1.out" | tail -n1; }
