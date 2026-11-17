#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
d=$OUT/s2
mkdir -p "$d"
cd "$GITHUB_WORKSPACE" || exit 1
git switch -c factory/issue-smoke >/dev/null 2>&1 || git switch factory/issue-smoke
printf '%s\n' 'Add the single comment line "// smoke probe" as the very first line of src/main/java/org/traccar/Main.java using the Edit tool. Then reply with the word done.' >"$d/prompt.txt"
claude_run s2-claude "$d/prompt.txt" --max-turns 8 --max-budget-usd 1
jq -c 'select(.type=="system")' "$OUT/s2-claude.out" >"$d/system-events.jsonl"
jq -c 'select(.type=="system" and ((.subtype // "")|test("hook")))' "$OUT/s2-claude.out" >"$d/hook-events.jsonl"
jq -r 'select(.type=="system" and ((.subtype // "")|test("hook"))) | "== \(.subtype) \(.hook_event // .hook_event_name // "") \(.hook_name // "")\n\(.stdout // .output // "")\n"' "$OUT/s2-claude.out" >"$d/hook-text.txt"
result_of s2-claude >"$d/result.json"
git diff --stat >"$d/edit-diff-stat.txt"
git diff >"$d/edit.diff"
git checkout -- . && git status --porcelain >"$d/status-after-revert.txt"
echo "S2 hook events: $(wc -l <"$d/hook-events.jsonl")"; cut -c1-300 "$d/hook-events.jsonl" | head -20
cut -c1-400 "$d/result.json"
