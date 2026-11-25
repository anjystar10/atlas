#!/usr/bin/env python3
"""ESLint's JSON report → findings grouped by rule, errors first, each with file:line, the message and the line itself.
Usage: eslint_summary.py eslint.json DESKTOP_DIR > eslint-findings.md"""
import collections, json, os, sys

try:
    data = json.load(open(sys.argv[1]))
except Exception as e:
    print(f"# ESLint\n\nNo report could be read ({e}); see eslint.log."); sys.exit(0)
base = sys.argv[2] if len(sys.argv) > 2 else ""
by = collections.defaultdict(list)
for f in data:
    src = (f.get("source") or open(f["filePath"], encoding="utf-8", errors="replace").read()).splitlines()
    for m in f.get("messages", []):
        rel = os.path.relpath(f["filePath"], base) if base else f["filePath"]
        line = src[m.get("line", 1) - 1].strip()[:160] if m.get("line") and m["line"] <= len(src) else ""
        by[(m.get("severity", 1), m.get("ruleId") or "parse")].append((f"desktop/{rel}:{m.get('line')}", m.get("message", ""), line))
print("# ESLint (eslint:recommended + bug checks) over desktop/\n")
print("Totals: " + ", ".join(f"{r} {'error' if s == 2 else 'warning'} ×{len(v)}" for (s, r), v in sorted(by.items(), key=lambda kv: (-kv[0][0], -len(kv[1])))) + "\n")
for (sev, rule), items in sorted(by.items(), key=lambda kv: (-kv[0][0], kv[0][1])):
    print(f"## {'error' if sev == 2 else 'warning'} · {rule} ({len(items)})")
    for loc, msg, line in items[:80]:
        print(f"- {loc} — {msg}" + (f"\n    `{line}`" if line else ""))
    print()
