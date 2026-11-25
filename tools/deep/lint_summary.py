#!/usr/bin/env python3
"""
Android lint's XML report → the findings that matter: every error and warning in Correctness, Security and
Performance (and anything lint calls fatal), grouped by check, with file:line and lint's own message.
Usage: lint_summary.py lint-results-debug.xml > lint-findings.md
"""
import collections, re, sys, xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception as e:
    print(f"# Android lint\n\nNo report could be read ({e}). See lint.log."); sys.exit(0)
KEEP = ("Correctness", "Security", "Performance")
groups = collections.defaultdict(list)
cats = collections.Counter()
for i in root.iter("issue"):
    cat = (i.get("category") or "").split(":")[0]
    sev = i.get("severity") or ""
    cats[(cat, sev)] += 1
    if cat not in KEEP and sev not in ("Fatal", "Error"):
        continue
    if sev not in ("Fatal", "Error", "Warning"):
        continue
    locs = [f"{re.sub(r'^.*?/app/src/main/', 'app/src/main/', l.get('file') or '')}:{l.get('line') or '?'}" for l in i.iter("location")]
    groups[(cat, i.get("id"), sev, i.get("summary") or "")].append((locs[0] if locs else "?", (i.get("message") or "").strip(), (i.get("errorLine1") or "").strip()))
print("# Android lint (Correctness, Security, Performance: errors and warnings)\n")
print("All categories: " + ", ".join(f"{c}/{s} {n}" for (c, s), n in sorted(cats.items())) + "\n")
order = {"Fatal": 0, "Error": 1, "Warning": 2}
for (cat, iid, sev, summ), items in sorted(groups.items(), key=lambda kv: (order.get(kv[0][2], 9), kv[0][0], -len(kv[1]))):
    print(f"## {sev} · {cat} · {iid} ({len(items)}): {summ}")
    for loc, msg, line in items[:40]:
        print(f"- {loc} — {msg[:300]}" + (f"\n    `{line[:160]}`" if line else ""))
    if len(items) > 40:
        print(f"- … and {len(items) - 40} more")
    print()
