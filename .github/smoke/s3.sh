#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
d=$OUT/s3
mkdir -p "$d"
cd "$GITHUB_WORKSPACE" || exit 1
ps aux | grep -i '[s]onar' >"$d/procs-before.txt" || true
rec s3-map-cold sonar context navigation get-map --ecosystem java --depth 0
rec s3-map-warm sonar context navigation get-map --ecosystem java --depth 0
rec s3-map-depth2 sonar context navigation get-map --ecosystem java --depth 2
rec s3-constraints sonar context architecture get-constraints --format pretty
rec s3-guidelines sonar context guidelines get --files src/main/java/org/traccar/api/resource/DeviceResource.java
rec s3-search sonar context navigation search-signatures --pattern 'class \w+Resource' --limit 5
rec s3-hierarchy sonar context navigation get-type-hierarchy --fqn org.traccar.notificators.Notificator
rec s3-callers sonar context navigation trace-callers --fqn org.traccar.api.security.PermissionsService.checkEdit
rec s3-refs sonar context navigation get-references --fqn org.traccar.api.security.PermissionsService --limit 10
rec s3-deps sonar context dependencies check --purl pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.9.0
ps aux | grep -i '[s]onar' >"$d/procs-after.txt" || true
for n in map-cold map-warm constraints guidelines search hierarchy callers refs deps; do
  echo "--- s3-$n rc=$(rc_of s3-$n)"; head -c 600 "$OUT/s3-$n.out"; echo; head -c 300 "$OUT/s3-$n.err"
done
