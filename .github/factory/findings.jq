# sonar analyze agentic --format json -> sorted "path:line [rule] message" lines
[ .files[]? | .path as $p | .issues[]?
  | "\(.filePath // $p):\(.textRange.startLine // 0) [\(.rule)] \(.message | gsub("\\s+"; " "))" ]
| sort | .[]
