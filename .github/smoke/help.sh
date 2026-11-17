#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
mkdir -p "$OUT/help"
H() { local n=$1; shift; "$@" >"$OUT/help/$n.txt" 2>&1; }
H root sonar --help
H context sonar context --help
H navigation sonar context navigation --help
H architecture sonar context architecture --help
H guidelines sonar context guidelines --help
H dependencies sonar context dependencies --help
for c in get-map search-signatures trace-callers get-type-hierarchy get-references; do H "nav-$c" sonar context navigation "$c" --help; done
H arch-get-constraints sonar context architecture get-constraints --help
H guidelines-get sonar context guidelines get --help
H analyze sonar analyze --help
H analyze-agentic sonar analyze agentic --help
H integrate sonar integrate --help
H integrate-claude sonar integrate claude --help
H auth sonar auth --help
echo "help dumped: $(ls "$OUT/help" | wc -l) files"
