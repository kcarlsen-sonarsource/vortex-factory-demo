#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
d=$OUT/s4
mkdir -p "$d"
cd "$GITHUB_WORKSPACE" || exit 1
git switch -c factory/issue-smoke-s4 >/dev/null 2>&1 || git switch factory/issue-smoke-s4
echo "branch: $(git branch --show-current) base: $BASE_SHA" >"$d/context.txt"
f=src/main/java/org/traccar/api/resource/DeviceResource.java
new=src/main/java/org/traccar/api/resource/SmokeFileResource.java
deep() { rec "$1" sonar analyze agentic --base "${2:-$BASE_SHA}" --depth DEEP --format json --force; echo "  files: $(jq -c '[.files[]? | (.path // .file // .filePath // .name)]' "$OUT/$1.out" 2>/dev/null | head -c 300)"; }

# Case A: unchanged tree
deep s4-a-clean-tree

# Case B: seeded defect in a touched file (path from request input, stream never closed)
python3 - "$f" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().rstrip()
assert s.endswith("}")
s = s[:-1] + '''
    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("smoke")
    public String smokeRead(@jakarta.ws.rs.QueryParam("name") String name) throws java.io.IOException {
        java.io.FileInputStream stream = new java.io.FileInputStream("/var/lib/traccar/media/" + name);
        return new String(stream.readAllBytes());
    }

}
'''
open(p, "w").write(s)
PY
git diff >"$d/b-seed.diff"
deep s4-b-seeded-nocompile
t0=$SECONDS; ./gradlew compileJava -q >"$d/b-compile.log" 2>&1; echo "compileJava rc=$? $((SECONDS - t0))s" | tee "$d/b-compile.txt"
deep s4-b-seeded-compiled

# Case C: fix it
git checkout -- "$f"
python3 - "$f" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().rstrip()
s = s[:-1] + '''
    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("smoke")
    public String smokeRead(@jakarta.ws.rs.QueryParam("name") String name) throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("/var/lib/traccar/media").toAbsolutePath().normalize();
        java.nio.file.Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid name");
        }
        try (java.io.InputStream stream = java.nio.file.Files.newInputStream(file)) {
            return new String(stream.readAllBytes());
        }
    }

}
'''
open(p, "w").write(s)
PY
git diff >"$d/c-fixed.diff"
./gradlew compileJava -q >"$d/c-compile.log" 2>&1
deep s4-c-fixed-compiled
git checkout -- "$f"

# Case D: brand-new class file, same defect
cat >"$new" <<'JAVA'
package org.traccar.api.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

@Path("smoke-files")
public class SmokeFileResource {

    @GET
    public String read(@QueryParam("name") String name) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("/var/lib/traccar/media/" + name));
        return reader.readLine();
    }

}
JAVA
git add -N "$new"
deep s4-d-newclass-nocompile
./gradlew compileJava -q >"$d/d-compile.log" 2>&1
deep s4-d-newclass-compiled
git rm -q --cached "$new"; rm -f "$new"; git status --porcelain >"$d/status-final.txt"

# Case E: base is the CI-analyzed commit
deep s4-e-clean-tree-base-7c94c932b 7c94c932b9a0f9a7d94ea0142d97b887a1942fd7
for n in a-clean-tree b-seeded-nocompile b-seeded-compiled c-fixed-compiled d-newclass-nocompile d-newclass-compiled e-clean-tree-base-7c94c932b; do
  echo "--- s4-$n rc=$(rc_of s4-$n)"; head -c 500 "$OUT/s4-$n.out"; echo; head -c 300 "$OUT/s4-$n.err"
done
