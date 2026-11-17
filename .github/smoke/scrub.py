"""Redact secret-like values from every file under argv[1], then fail if any needle survives."""
import os, re, sys

root = sys.argv[1]
needles = set()
for var in ("SONARQUBE_CLI_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "ANTHROPIC_CUSTOM_HEADERS"):
    v = os.environ.get(var, "")
    if not v:
        continue
    needles.add(v)
    for line in v.splitlines():
        line = line.strip()
        needles.add(line)
        if ":" in line:
            needles.add(line.split(":", 1)[1].strip())
    if var == "ANTHROPIC_BASE_URL":
        needles.add(re.sub(r"^https?://", "", v).rstrip("/"))
needles = sorted({n for n in needles if len(n) >= 6}, key=len, reverse=True)
patterns = [re.compile(p) for p in (
    rb"sk-[A-Za-z0-9_\-]{20,}", rb"sq[puabt]_[A-Za-z0-9]{20,}", rb"gh[pousr]_[A-Za-z0-9]{20,}",
    rb"(?i)bearer\s+[A-Za-z0-9._\-]{20,}", rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")]
hits = 0
for dp, _, fs in os.walk(root):
    for f in fs:
        p = os.path.join(dp, f)
        data = open(p, "rb").read()
        orig = data
        for n in needles:
            data = data.replace(n.encode(), b"[REDACTED]")
        for pat in patterns:
            data = pat.sub(b"[REDACTED]", data)
        if data != orig:
            hits += 1
            open(p, "wb").write(data)
left = 0
for dp, _, fs in os.walk(root):
    for f in fs:
        data = open(os.path.join(dp, f), "rb").read()
        if any(n.encode() in data for n in needles):
            left += 1
print(f"scrub: {len(needles)} needles, {hits} files redacted, {left} files still containing a needle")
sys.exit(1 if left else 0)
