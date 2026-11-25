#!/usr/bin/env python3
"""
Turns one deep-test run's raw output into findings (markdown on stdout, findings.json beside it):
crashes (Java and native) with their stacks, ANRs with the main thread's stack, Atlas's own error lines grouped,
caught exceptions printed to the log, StrictMode, leaked windows/receivers, dropped frames, monkey CRASH / NOT
RESPONDING reports, and what the crawler flagged (events.jsonl). The wake word engine's SIGILL on x86 emulators is
listed as a known emulator limit, not a finding.
Usage: triage.py OUTDIR PART
"""
import collections, glob, json, os, re, sys

OUT, PART = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "")
PKG = "com.anjy.atlas"
LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*: (.*)$")


def read(p):
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except Exception:
        return ""


log = read(os.path.join(OUT, "logcat.txt")).splitlines()
pids = {}   # pid -> process name
for l in log:
    m = re.search(r"Start proc (\d+):(" + re.escape(PKG) + r"[^/ ]*)/", l)
    if m:
        pids[m.group(1)] = m.group(2)
    m = re.search(r"pid (\d+) \((" + re.escape(PKG) + r"[^)]*)\)", l)
    if m:
        pids.setdefault(m.group(1), m.group(2))
    m = re.search(r"^Process: (" + re.escape(PKG) + r"[^,]*), PID: (\d+)", l.split(": ", 1)[-1] if ": " in l else "")
    if m:
        pids.setdefault(m.group(2), m.group(1))

norm = lambda s: re.sub(r"0x[0-9a-f]+|\b\d+\b|[0-9a-f]{8}-[0-9a-f-]{27,}", "#", s)[:220]
crashes, anrs, natives, known, errors, caught, strict, leaks, frames = [], [], [], [], collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter(), []
samples = {}
i = 0
while i < len(log):
    l = log[i]
    m = LINE.match(l)
    if not m:
        i += 1; continue
    ts, pid, tid, lvl, tag, msg = m.groups()
    mine = pid in pids
    if tag == "AndroidRuntime" and msg.startswith("FATAL EXCEPTION"):
        block = [l]
        j = i + 1
        while j < len(log) and j < i + 80:
            mm = LINE.match(log[j])
            if not mm or mm.group(5) != "AndroidRuntime":
                break
            block.append(log[j]); j += 1
        text = "\n".join(b.split(": ", 1)[-1] if "AndroidRuntime" in b else b for b in block)
        if PKG in text:
            crashes.append({"at": ts, "stack": [LINE.match(b).group(6) if LINE.match(b) else b for b in block][:40]})
        i = j; continue
    if "ANR in " + PKG in msg:
        block = [msg] + [LINE.match(x).group(6) for x in log[i + 1:i + 12] if LINE.match(x) and LINE.match(x).group(2) == pid]
        anrs.append({"at": ts, "report": block})
    if tag == "libc" and "Fatal signal" in msg and ("atlas" in msg):
        (known if ("SIGILL" in msg and "atlas-hotword" in msg) else natives).append({"at": ts, "line": msg})
    if mine and lvl in "EF" and tag not in ("AndroidRuntime", "libc", "DEBUG"):
        k = f"{tag}: {norm(msg)}"
        errors[k] += 1; samples.setdefault(k, f"{ts} [{pids.get(pid)}] {tag}: {msg[:400]}")
    if mine and lvl in "WE" and tag == "System.err":
        if re.search(r"Exception|Error", msg) and not msg.lstrip().startswith("at "):
            block = [msg] + [LINE.match(x).group(6).strip() for x in log[i + 1:i + 10] if LINE.match(x) and LINE.match(x).group(5) == "System.err" and LINE.match(x).group(6).lstrip().startswith("at ")]
            k = norm(msg)
            caught[k] += 1; samples.setdefault("caught:" + k, f"{ts} [{pids.get(pid)}]\n  " + "\n  ".join(block[:8]))
    if mine and "StrictMode" in tag + msg:
        k = norm(msg); strict[k] += 1; samples.setdefault("strict:" + k, f"{ts} {tag}: {msg[:300]}")
    if re.search(r"WindowLeaked|has leaked|leaked IntentReceiver|leaked ServiceConnection|A resource failed to call close", msg) and (mine or PKG in msg):
        k = norm(msg); leaks[k] += 1; samples.setdefault("leak:" + k, f"{ts} {tag}: {msg[:300]}")
    if mine and tag == "Choreographer" and "Skipped" in msg:
        n = re.search(r"Skipped (\d+) frames", msg)
        if n: frames.append(int(n.group(1)))
    i += 1

# ANR traces: the main thread of Atlas's process in each trace file
anr_traces = []
for f in sorted(glob.glob(os.path.join(OUT, "anr", "**", "*"), recursive=True)):
    if os.path.isdir(f):
        continue
    t = read(f)
    if PKG not in t:
        continue
    m = re.search(r'"main" prio=.*?\n(.*?)\n\n', t, re.S)
    anr_traces.append({"file": os.path.relpath(f, OUT), "main": (m.group(0)[:3000] if m else t[:1500])})

# the monkey's own reports
monkey = []
for f in sorted(glob.glob(os.path.join(OUT, "monkey-*.txt"))):
    t = read(f)
    for m in re.finditer(r"// (CRASH|NOT RESPONDING): (\S+).*?\n((?://.*\n){0,40})", t):
        body = m.group(3)
        monkey.append({"file": os.path.basename(f), "kind": m.group(1), "proc": m.group(2), "head": body.splitlines()[:24]})
    done = re.search(r"Events injected: (\d+)", t)
    monkey.append({"file": os.path.basename(f), "kind": "summary", "events": done.group(1) if done else "?", "aborted": "** Monkey aborted" in t,
                   "tail": t.strip().splitlines()[-3:]})

events = [json.loads(l) for l in read(os.path.join(OUT, "events.jsonl")).splitlines() if l.strip()]
cov = {}
try:
    cov = json.load(open(os.path.join(OUT, "coverage.json")))
except Exception:
    pass
dropbox = read(os.path.join(OUT, "dropbox.txt"))
db_counts = collections.Counter(re.findall(r"^\S+ \S+ (data_app_\w+)", dropbox, re.M))

res = {"part": PART, "pids": pids, "crashes": crashes, "anrs": anrs, "anr_traces": anr_traces, "native": natives, "known_emulator": len(known),
       "errors": errors.most_common(), "caught": caught.most_common(), "strictmode": strict.most_common(), "leaks": leaks.most_common(),
       "skipped_frames": {"count": len(frames), "max": max(frames) if frames else 0, "over_30": sum(1 for f in frames if f >= 30)},
       "monkey": monkey, "crawler_events": events, "dropbox": dict(db_counts), "coverage": {k: cov.get(k) for k in ("tapped", "inputs", "replay_failed", "must_reach", "memory_pss_kb", "minutes")}}
json.dump(res, open(os.path.join(OUT, "findings.json"), "w"), ensure_ascii=False, indent=1, default=str)

p = print
p(f"# Deep test — {PART}\n")
p(open(os.path.join(OUT, "device.txt")).read() if os.path.exists(os.path.join(OUT, "device.txt")) else "")
p(f"Atlas processes seen: {sorted(set(pids.values()))}\n")
p(f"## Crashes ({len(crashes)})")
seen = set()
for c in crashes:
    k = "\n".join(c["stack"][1:4])
    if k in seen:
        continue
    seen.add(k)
    p(f"\n{c['at']}  (×{sum(1 for x in crashes if chr(10).join(x['stack'][1:4]) == k)})\n```\n" + "\n".join(c["stack"][:30]) + "\n```")
p(f"\n## ANRs ({len(anrs)}, traces {len(anr_traces)})")
for a in anrs:
    p(f"\n{a['at']}\n```\n" + "\n".join(a["report"]) + "\n```")
for a in anr_traces:
    p(f"\n{a['file']}\n```\n{a['main']}\n```")
p(f"\n## Native crashes ({len(natives)}; known emulator SIGILL in the wake word engine: {len(known)})")
for n in natives[:20]:
    p(f"- {n['at']} {n['line']}")
p("\n## Monkey")
for m in monkey:
    if m["kind"] == "summary":
        p(f"- {m['file']}: {m['events']} events injected{' — ABORTED' if m['aborted'] else ''}; ends: {' | '.join(m['tail'])}")
    else:
        p(f"\n{m['file']} {m['kind']} {m['proc']}\n```\n" + "\n".join(m["head"]) + "\n```")
p(f"\n## Crawler events ({len(events)})")
for e in events:
    if e["kind"].startswith("known-emulator"):
        continue
    p(f"- {e['kind']}: " + json.dumps({k: v for k, v in e.items() if k not in ('kind', 'context')}, ensure_ascii=False)[:600])
    for c in e.get("context", [])[:14]:
        p(f"    {c[:220]}")
p(f"\n## Atlas's error lines ({sum(errors.values())}, {len(errors)} kinds)")
for k, n in errors.most_common(60):
    p(f"- ×{n} {samples[k][:500]}")
p(f"\n## Exceptions printed to the log ({sum(caught.values())}, {len(caught)} kinds)")
for k, n in caught.most_common(40):
    p(f"- ×{n} {samples['caught:' + k][:900]}")
p(f"\n## StrictMode ({sum(strict.values())})")
for k, n in strict.most_common(30):
    p(f"- ×{n} {samples['strict:' + k]}")
p(f"\n## Leaks ({sum(leaks.values())})")
for k, n in leaks.most_common(20):
    p(f"- ×{n} {samples['leak:' + k]}")
p(f"\n## Frames: {res['skipped_frames']}")
p(f"\n## Dropbox: {dict(db_counts)}")
if cov:
    p(f"\n## Crawl coverage\n- taps {cov.get('tapped')}, odd inputs {cov.get('inputs')}, screens {len(cov.get('screens', {}))}, replays failed {cov.get('replay_failed')}, minutes {cov.get('minutes')}")
    p(f"- must reach: {cov.get('must_reach')}")
    p(f"- memory (PSS kB): {cov.get('memory_pss_kb')}")
    p(f"- left the app {len(cov.get('left_app', []))} times; destructive buttons found {len(cov.get('danger', []))}")
