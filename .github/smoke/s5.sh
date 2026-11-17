#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
d=$OUT/s5
mkdir -p "$d"
cd "$GITHUB_WORKSPACE" || exit 1
printf '%s\n' 'List every Java file under src/main/java/org/traccar/api/resource, read each one, and give a one-line summary of each.' >"$d/p-many.txt"
claude_run s5-max-turns "$d/p-many.txt" --max-turns 2
result_of s5-max-turns >"$d/max-turns-result.json"
claude_run s5-max-budget "$d/p-many.txt" --max-turns 30 --max-budget-usd 0.02
result_of s5-max-budget >"$d/max-budget-result.json"

sid=$(uuidgen | tr '[:upper:]' '[:lower:]')
printf '%s\n' 'Remember the code word PELICAN-42. Reply with just OK.' >"$d/p-remember.txt"
printf '%s\n' 'What was the code word? Reply with just the code word.' >"$d/p-recall.txt"
claude_run s5-resume-1 "$d/p-remember.txt" --session-id "$sid" --max-turns 3
claude_run s5-resume-2 "$d/p-recall.txt" --resume "$sid" --max-turns 3
result_of s5-resume-1 >"$d/resume-1-result.json"
result_of s5-resume-2 >"$d/resume-2-result.json"

printf '%s\n' 'Run these three commands one at a time and report the first line of each result, or say which one was denied: (1) sonar context architecture get-constraints --format pretty (2) ./gradlew compileJava (3) curl -sI https://example.com' >"$d/p-tools.txt"
claude_run s5-tools "$d/p-tools.txt" --max-turns 12 --max-budget-usd 1
result_of s5-tools >"$d/tools-result.json"
for n in max-turns max-budget resume-1 resume-2 tools; do
  echo "--- s5-$n rc=$(rc_of s5-$n)"; jq -c '{subtype,is_error,num_turns,total_cost_usd,result:(.result // "" | .[0:200]),denials:(.permission_denials // [] | length)}' "$d/${n}-result.json" 2>/dev/null || cut -c1-300 "$d/"*"$n"*result.json
done
