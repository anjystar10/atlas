#!/usr/bin/env python3
"""
Packs one deep-test run for the deep-results branch: every screenshot shrunk to a JPEG (phone 540 px wide, PC 960),
big logs gzipped, everything else copied, and README.md: the run's findings from each part, in one place.
Usage: publish.py DOWNLOADS OUT BRANCH SHA RUN_ID
"""
import gzip, os, shutil, sys
from PIL import Image

src, out, branch, sha, run = sys.argv[1:6]
os.makedirs(out, exist_ok=True)
parts = []
for art in sorted(os.listdir(src)):
    base = os.path.join(src, art)
    if not os.path.isdir(base):
        continue
    name = art.replace("deep-", "", 1)
    for root, _, files in os.walk(base):
        rel = os.path.relpath(root, base)
        # the artifact holds deep-out/<part>/…; keep <part>/…
        dest_dir = os.path.join(out, name, *[p for p in rel.split(os.sep) if p not in (".",)][1:]) if rel != "." else os.path.join(out, name)
        os.makedirs(dest_dir, exist_ok=True)
        for f in files:
            p = os.path.join(root, f)
            if f.endswith(".png"):
                try:
                    im = Image.open(p).convert("RGB")
                    w = 960 if name == "desktop" else 540
                    if im.width > w:
                        im = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
                    im.save(os.path.join(dest_dir, f[:-4] + ".jpg"), quality=72, optimize=True)
                except Exception as e:
                    print("picture skipped:", p, e)
                continue
            if f.endswith((".apk", ".jks")):
                continue
            if os.path.getsize(p) > 1_500_000 and not f.endswith(".gz"):
                with open(p, "rb") as a, gzip.open(os.path.join(dest_dir, f + ".gz"), "wb") as b:
                    shutil.copyfileobj(a, b)
                continue
            shutil.copy(p, os.path.join(dest_dir, f))
    parts.append(name)

lines = [f"# Deep test — {branch} @ {sha[:7]}", "", f"Run: https://github.com/anjystar10/atlas/actions/runs/{run}", "",
         "Parts: " + ", ".join(parts), ""]
for root, _, files in sorted(os.walk(out)):
    for f in sorted(files):
        if f in ("findings.md", "lint-findings.md", "desktop-findings.md", "eslint-findings.md"):
            p = os.path.join(root, f)
            body = open(p, encoding="utf-8", errors="replace").read().splitlines()
            lines += [f"\n---\n\n<!-- {os.path.relpath(p, out)} -->"] + body[:220] + (["", f"… ({len(body) - 220} more lines in {os.path.relpath(p, out)})"] if len(body) > 220 else [])
open(os.path.join(out, "README.md"), "w").write("\n".join(lines) + "\n")
open(os.path.join(out, "built-from.txt"), "w").write(f"{branch} {sha} run {run}\n")
