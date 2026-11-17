#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
d=$OUT/s1
mkdir -p "$d"
base="https://binaries.sonarsource.com/Distribution/sonarqube-cli/${SONAR_CLI_VERSION}/linux"
f="sonarqube-cli-${SONAR_CLI_VERSION}-linux-x86-64.bin"
bin="$RUNNER_TEMP/$f"

curl -sS -L -o "$bin" -w '%{http_code} %{size_download}\n' "$base/$f" >"$d/download-status.txt"
cat "$d/download-status.txt"
sha256sum "$bin" | sed "s#$RUNNER_TEMP/##" | tee "$d/sha256.txt"

# What is published next to the binary?
: >"$d/probes.txt"
for ext in .asc .sig .sha256 .sha256sum .sha512 .sha1 .md5 .minisig; do
  code=$(curl -sS -L -o "$d/probe$ext" -w '%{http_code}' "$base/$f$ext" || echo curl-fail)
  echo "$f$ext $code" | tee -a "$d/probes.txt"
  [ "$code" = 200 ] || rm -f "$d/probe$ext"
done
curl -sS -L -o "$d/stable.version" -w 'stable.version %{http_code}\n' "https://binaries.sonarsource.com/Distribution/sonarqube-cli/stable.version" | tee -a "$d/probes.txt"
curl -sS -L "https://binaries.sonarsource.com/?prefix=Distribution/sonarqube-cli/${SONAR_CLI_VERSION}/&delimiter=/" -o "$d/listing-version.xml" -w 'listing-version %{http_code}\n' | tee -a "$d/probes.txt"
curl -sS -L "https://binaries.sonarsource.com/?prefix=Distribution/sonarqube-cli/${SONAR_CLI_VERSION}/linux/" -o "$d/listing-linux.xml" -w 'listing-linux %{http_code}\n' | tee -a "$d/probes.txt"

# Sonar's public signing key: the CLI source says "Found at binaries.sonarsource.com/".
export GNUPGHOME
GNUPGHOME=$(mktemp -d)
chmod 700 "$GNUPGHOME"
: >"$d/key-sources.txt"
for u in https://binaries.sonarsource.com/sonarsource-public.key https://binaries.sonarsource.com/SonarSource-public.key; do
  code=$(curl -sS -L -o "$d/key-candidate.asc" -w '%{http_code}' "$u" || echo curl-fail)
  echo "$u $code" | tee -a "$d/key-sources.txt"
  if [ "$code" = 200 ] && grep -q 'BEGIN PGP PUBLIC KEY BLOCK' "$d/key-candidate.asc"; then mv "$d/key-candidate.asc" "$d/sonarsource-public.key"; break; fi
done
src_url="https://raw.githubusercontent.com/SonarSource/sonarqube-cli/${SONAR_CLI_VERSION}/src/core/host/install/signatures.ts"
code=$(curl -sS -L -o "$d/signatures.ts" -w '%{http_code}' "$src_url" || echo curl-fail)
echo "$src_url $code" | tee -a "$d/key-sources.txt"
if [ "$code" = 200 ]; then
  sed -n '/-----BEGIN PGP PUBLIC KEY BLOCK-----/,/-----END PGP PUBLIC KEY BLOCK-----/p' "$d/signatures.ts" | sed 's/^export const SONARSOURCE_PUBLIC_KEY = `//; s/`;$//' >"$d/key-from-cli-source.asc"
fi
for k in sonarsource-public.key key-from-cli-source.asc; do
  [ -s "$d/$k" ] && { echo "== fingerprints in $k"; gpg --batch --show-keys --with-colons --fingerprint "$d/$k" | awk -F: '$1=="pub"||$1=="sub"||$1=="fpr"||$1=="uid"{print $1":"$2":"$5":"$10}' | tee "$d/fingerprints-$k.txt"; }
done
if [ -s "$d/probe.asc" ]; then
  gpg --batch --list-packets "$d/probe.asc" >"$d/asc-packets.txt" 2>&1 || true
  : >"$d/gpg-verify.txt"
  for k in sonarsource-public.key key-from-cli-source.asc; do
    [ -s "$d/$k" ] || continue
    export GNUPGHOME
    GNUPGHOME=$(mktemp -d)
    chmod 700 "$GNUPGHOME"
    gpg --batch --import "$d/$k" >>"$d/gpg-import.txt" 2>&1
    echo "=== verify with $k" >>"$d/gpg-verify.txt"
    gpg --batch --status-fd 1 --verify "$d/probe.asc" "$bin" >>"$d/gpg-verify.txt" 2>&1
    echo "gpg --verify rc=$? ($k)" | tee -a "$d/gpg-verify.txt"
  done
else
  echo "no .asc published next to the binary; signature not verified" | tee "$d/gpg-verify.txt"
fi

install -D -m 0755 "$bin" "$HOME/.local/bin/sonar"
rec version sonar --version
cat "$OUT/version.out"
(cd "$GITHUB_WORKSPACE" && rec auth-status sonar auth status)
cat "$OUT/auth-status.out" "$OUT/auth-status.err" | tail -n 8

before=$(cd "$GITHUB_WORKSPACE" && git status --porcelain --untracked-files=all | wc -l)
(cd "$GITHUB_WORKSPACE" && rec integrate sonar integrate claude --non-interactive)
cat "$OUT/integrate.out" "$OUT/integrate.err"
after=$(cd "$GITHUB_WORKSPACE" && git status --porcelain --untracked-files=all)
printf 'worktree changes before=%s\nafter:\n%s\n' "$before" "$after" >"$d/worktree-after-integrate.txt"
mkdir -p "$d/claude-home"
ls -la "$HOME/.claude" >"$d/claude-home/ls.txt" 2>&1
[ -f "$HOME/.claude/settings.json" ] && cp "$HOME/.claude/settings.json" "$d/claude-home/settings.json" && jq '.hooks' "$HOME/.claude/settings.json" >"$d/claude-home/hooks.json"
[ -f "$HOME/.claude/CLAUDE.md" ] && cp "$HOME/.claude/CLAUDE.md" "$d/claude-home/CLAUDE.md"
{
  echo "SessionStart entries:"; jq -c '.hooks.SessionStart // empty' "$HOME/.claude/settings.json"
  echo "PostToolUse entries:"; jq -c '.hooks.PostToolUse // empty' "$HOME/.claude/settings.json"
  echo "CLAUDE.md marker 'Vortex analysis protocol':"; grep -c 'Vortex analysis protocol' "$HOME/.claude/CLAUDE.md"
  echo "'skipping' in integrate output:"; cat "$OUT/integrate.out" "$OUT/integrate.err" | grep -ci skipping
} >"$d/postconditions.txt" 2>&1 || true
cat "$d/postconditions.txt"
