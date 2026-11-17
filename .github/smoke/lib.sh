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
