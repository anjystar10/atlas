#!/usr/bin/env python3
"""
Atlas deep crawl on an Android emulator, through adb and uiautomator (part of the deep test rig, deep.yml).

It drives the real app the way a person would and harder: the stand-in AI answering with every error shape, airplane
mode, rotation, going to the background and coming back, the process being killed while in the background (and with
"don't keep activities"), light/dark and system night mode, big fonts and a bigger display size, permissions taken
away, Reduce Motion off, then a crawl that taps every button on every tab, sheet and screen it can reach (depth 3),
types odd text into every field, and finally the destructive buttons (delete, remove, clear…) to check each one asks.

Usage: crawl.py OUTDIR [--minutes N (the crawl's budget, after the scenarios)] [--stub-port PORT] [--only scenarios|crawl]
Expects: adb root, Atlas installed, OUTDIR/logcat.txt being written by `adb logcat -v threadtime` (deep.sh does this),
the stand-in (stub.py) on this machine at PORT. Writes:
  OUTDIR/shots/NNNN-<what>.png     every new screen and every scenario step
  OUTDIR/ui/NNNN.xml               the screen's layout, for every new screen
  OUTDIR/actions.jsonl             every step: what was tapped where, and what came of it
  OUTDIR/events.jsonl              crashes, ANRs, the app dying, leaving the app, stuck screens: with the step before
  OUTDIR/coverage.json             screens reached, targets tapped, must-reach labels found or not
  OUTDIR/scenarios.json            what each scenario saw (reply texts for each stand-in mode, state after process death…)
"""
import json, os, re, subprocess, sys, time, urllib.request, xml.etree.ElementTree as ET

PKG = "com.anjy.atlas"
ADB = os.environ.get("ADB", "adb")
args = sys.argv[1:]
OUT = args[0] if args and not args[0].startswith("--") else "deep-out"
def opt(name, d):
    return args[args.index(name) + 1] if name in args and args.index(name) + 1 < len(args) else d
MINUTES = float(opt("--minutes", "45"))
STUB = int(opt("--stub-port", "8765"))
ONLY = opt("--only", "")
T0 = time.time()
DEADLINE = T0 + MINUTES * 60
for d in ("shots", "ui"):
    os.makedirs(os.path.join(OUT, d), exist_ok=True)
SHOT_N = [0]
ACT = open(os.path.join(OUT, "actions.jsonl"), "a", encoding="utf-8")
EVT = open(os.path.join(OUT, "events.jsonl"), "a", encoding="utf-8")
SCEN = {}


def log(*a):
    print(f"[{time.time() - T0:7.1f}s]", *a, flush=True)


def adb(*a, timeout=60, binary=False):
    try:
        r = subprocess.run([ADB, *a], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log("adb timed out:", " ".join(a)[:160])
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def slug(s, n=48):
    return re.sub(r"[^A-Za-z0-9]+", "-", s or "").strip("-")[:n] or "x"


def shot(what):
    SHOT_N[0] += 1
    name = f"{SHOT_N[0]:04d}-{slug(what)}.png"
    data = adb("exec-out", "screencap -p", timeout=40, binary=True)
    if data:
        with open(os.path.join(OUT, "shots", name), "wb") as f:
            f.write(data)
    return name


def record(kind, **kw):
    kw.update(t=round(time.time() - T0, 1), kind=kind)
    ACT.write(json.dumps(kw, ensure_ascii=False) + "\n"); ACT.flush()


def event(kind, **kw):
    kw.update(t=round(time.time() - T0, 1), kind=kind)
    log("EVENT", kind, json.dumps(kw, ensure_ascii=False)[:300])
    EVT.write(json.dumps(kw, ensure_ascii=False) + "\n"); EVT.flush()


def stub(mode, once=False):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{STUB}/__mode?m={urllib.parse.quote(mode)}{'&once=1' if once else ''}", timeout=5).read()
    except Exception as e:
        log("stub not reachable:", e)


import urllib.parse  # noqa: E402  (used by stub())

# ------------------------------------------------------------------ the screen, read through uiautomator

class Node:
    __slots__ = ("text", "desc", "cls", "pkg", "rid", "click", "long", "check", "checked", "enabled", "scroll", "password", "b", "label", "edit")

    def __init__(self, e):
        g = e.get
        self.text = g("text") or ""; self.desc = g("content-desc") or ""; self.cls = g("class") or ""; self.pkg = g("package") or ""
        self.rid = g("resource-id") or ""; self.click = g("clickable") == "true"; self.long = g("long-clickable") == "true"
        self.check = g("checkable") == "true"; self.checked = g("checked") == "true"; self.enabled = g("enabled") != "false"
        self.scroll = g("scrollable") == "true"; self.password = g("password") == "true"
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", g("bounds") or "")
        self.b = tuple(map(int, m.groups())) if m else (0, 0, 0, 0)
        self.edit = "EditText" in self.cls
        self.label = ""

    @property
    def cx(self): return (self.b[0] + self.b[2]) // 2
    @property
    def cy(self): return (self.b[1] + self.b[3]) // 2
    @property
    def area(self): return max(0, self.b[2] - self.b[0]) * max(0, self.b[3] - self.b[1])


class Screen:
    def __init__(self, xml):
        self.xml = xml; self.nodes = []; self.pkg = ""
        try:
            root = ET.fromstring(xml)
        except Exception:
            root = None
        if root is not None:
            def walk(e, inherited):
                for c in e:
                    if c.tag != "node":
                        continue
                    n = Node(c); self.nodes.append(n)
                    if not self.pkg: self.pkg = n.pkg
                    kids_before = len(self.nodes)
                    walk(c, inherited)
                    if not (n.desc or n.text):
                        # a clickable row without its own words: name it by the first words inside it
                        words = [k.text or k.desc for k in self.nodes[kids_before:] if (k.text or k.desc)]
                        n.label = " / ".join(words[:2])[:80]
                    else:
                        n.label = (n.desc or n.text)[:80]
            walk(root, None)
        self.ok = root is not None and bool(self.nodes)

    def words(self):
        return [n.label for n in self.nodes if n.label]

    def sig(self):
        # what can be tapped, without numbers (clocks, counts and "5m ago" change by themselves)
        ks = sorted({re.sub(r"\d+", "#", n.label) for n in self.nodes if (n.click or n.edit) and n.label})
        return self.pkg + "|" + "|".join(ks)[:4000]

    def labels(self):
        return {re.sub(r"\d+", "#", n.label) for n in self.nodes if (n.click or n.edit) and n.label}

    def content(self):
        return "|".join(re.sub(r"\d+", "#", w) for w in self.words())[:6000]

    def find(self, label, exact=False):
        for n in self.nodes:
            for s in (n.desc, n.text, n.label):
                if s and ((s == label) if exact else (s == label or s.startswith(label))):
                    return n
        return None


W, H = 1080, 2400


def dump():
    for i in range(3):
        out = sh("uiautomator dump /sdcard/deep-ui.xml >/dev/null 2>&1; cat /sdcard/deep-ui.xml 2>/dev/null; rm -f /sdcard/deep-ui.xml", timeout=45)
        k = out.find("<?xml")
        if k >= 0 and "</hierarchy>" in out:
            s = Screen(out[k:out.rfind("</hierarchy>") + len("</hierarchy>")])
            if s.ok:
                return s
        time.sleep(1.5)
    event("dump-failed", note="uiautomator could not read the screen 3 times (an animation that never settles, or a frozen app)")
    return Screen("")


def save_ui(s, name):
    try:
        with open(os.path.join(OUT, "ui", name.replace(".png", ".xml")), "w", encoding="utf-8") as f:
            f.write(s.xml)
    except Exception:
        pass


def tap(n_or_xy, long=False):
    x, y = (n_or_xy.cx, n_or_xy.cy) if isinstance(n_or_xy, Node) else n_or_xy
    if long:
        sh(f"input swipe {x} {y} {x} {y} 900")
    else:
        sh(f"input tap {x} {y}")


def key(code):
    sh(f"input keyevent {code}")


def back():
    key(4); time.sleep(1.0)


def home():
    key(3); time.sleep(1.5)


def swipe_up():
    sh(f"input swipe {W // 2} {int(H * 0.68)} {W // 2} {int(H * 0.30)} 350"); time.sleep(1.0)


def swipe_down_to_top():
    for _ in range(6):
        sh(f"input swipe {W // 2} {int(H * 0.30)} {W // 2} {int(H * 0.80)} 120")
    time.sleep(0.6)


def type_text(s):
    """adb's `input text` takes ASCII only; spaces are %s, and the shell's own characters are escaped."""
    s = s.encode("ascii", "ignore").decode()
    if s.startswith("-"):
        key(69); s = s[1:]   # a leading "-" would be read as an option of `input`
    for i in range(0, len(s), 80):
        part = s[i:i + 80].replace(" ", "%s")
        part = re.sub(r"([\\'\"`$&|;<>()\[\]{}*?!#~])", r"\\\1", part)
        sh(f"input text {part}", timeout=30)


def clear_field():
    sh("input keyevent 123")  # end of the field
    sh("input keyevent " + " ".join(["67"] * 60))


def pid():
    return sh(f"pidof {PKG}").strip()


def foreground():
    out = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
    m = re.search(r"\{[^}]* ([\w.]+)/([\w.$]+)", out)
    return (m.group(1), m.group(2)) if m else ("", "")


def launch(tab=None, fresh=True):
    if fresh:
        sh(f"am force-stop {PKG}")
    extra = f" --es tab {tab}" if tab else ""
    sh(f"am start -W -n {PKG}/.MainActivity{extra}", timeout=60)
    time.sleep(3.5)


def relaunch_task():
    """Back to Atlas as a person would from the launcher: the task comes forward (or is rebuilt from what it saved)."""
    sh(f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {PKG}/.MainActivity", timeout=60)
    time.sleep(3.5)


def put_file(local, rel):
    """A file into Atlas's private folder (adb root), owned by Atlas."""
    adb("push", local, "/data/local/tmp/deep-put.tmp")
    owner = sh(f"stat -c %u /data/data/{PKG}").strip()
    d = os.path.dirname(rel)
    sh(f"mkdir -p /data/data/{PKG}/{d} && cp /data/local/tmp/deep-put.tmp /data/data/{PKG}/{rel} && chown -R {owner}:{owner} /data/data/{PKG}/{d} && chmod 600 /data/data/{PKG}/{rel} && restorecon -R /data/data/{PKG}/{d}")


def prefs(extra_xml):
    """Atlas's small settings file. Reduce Motion is on so uiautomator can read screens that would otherwise never settle."""
    p = os.path.join(OUT, "atlas_ui.xml")
    with open(p, "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <boolean name="reduceMotion" value="true" />\n' + extra_xml + "\n</map>\n")
    sh(f"am force-stop {PKG}")
    put_file(p, "shared_prefs/atlas_ui.xml")


def stub_key_prefs(endpoint=True):
    return ('    <string name="gemini_key">AIzaDeepTestKey-not-real-0000000000000</string>\n' +
            (f'    <string name="gemini_endpoint">http://127.0.0.1:{STUB}/v1beta/models/</string>\n' if endpoint else ""))


# ------------------------------------------------------------------ crashes and ANRs, read from the logcat deep.sh keeps

LOGCAT = os.path.join(OUT, "logcat.txt")
_log_off = [0]
MARKERS = [("crash", re.compile(r"FATAL EXCEPTION|AndroidRuntime: Process: " + re.escape(PKG))),
           ("anr", re.compile(r"ANR in " + re.escape(PKG))),
           ("native-crash", re.compile(r"Fatal signal \d+ .*\((?:[^)]*)\), pid \d+ \(.*atlas")),
           ("strictmode", re.compile(r"StrictMode policy violation|D StrictMode")),
           ("leak", re.compile(r"WindowLeaked|has leaked (?:window|IntentReceiver|ServiceConnection)")),
           ("died", re.compile(r"Process " + re.escape(PKG) + r"(?::\w+)? \(pid \d+\) has died"))]


def new_log_events(ctx):
    try:
        with open(LOGCAT, "rb") as f:
            f.seek(_log_off[0]); data = f.read(); _log_off[0] += len(data)
    except Exception:
        return []
    found = []
    lines = data.decode("utf-8", "replace").splitlines()
    for i, l in enumerate(lines):
        for kind, rx in MARKERS:
            if rx.search(l):
                known = kind == "native-crash" and "SIGILL" in l and "atlas-hotword" in l
                found.append(kind)
                event(("known-emulator-" if known else "") + kind, line=l[:400], context=lines[i + 1:i + 18] if kind in ("crash", "anr") else [], after=ctx)
    return found


def health(ctx):
    """After every step: did Atlas crash, stop answering, or vanish?"""
    time.sleep(0.3)
    found = new_log_events(ctx)
    bad = [k for k in found if k in ("crash", "anr")]
    if bad:
        shot("after-" + bad[0] + "-" + slug(str(ctx.get("label", "")), 24))
    return bad


# ------------------------------------------------------------------ the scenarios

def last_reply_text(s):
    """The bottom-most words on the Atlas page above the ask bar (the newest reply)."""
    edit = next((n for n in s.nodes if n.edit), None)
    limit = edit.b[1] if edit else H * 0.8
    texts = [n for n in s.nodes if n.text and n.b[3] <= limit + 5 and not n.edit and len(n.text) > 1]
    texts.sort(key=lambda n: n.b[3])
    return [n.text[:400] for n in texts[-4:]]


def ask(text, wait=8.0, tag=""):
    s = dump()
    f = next((n for n in s.nodes if n.edit), None)
    if not f:
        event("no-ask-field", where=tag); shot("no-ask-field-" + tag); return None
    tap(f); time.sleep(0.6)
    type_text(text); time.sleep(0.5)
    s = dump()
    send = s.find("Send", exact=True) or s.find("Send")
    if send:
        tap(send)
    else:
        key(66)
    time.sleep(wait)
    health({"scenario": "ask", "label": text})
    s = dump()
    name = shot("reply-" + (tag or text))
    return {"asked": text, "shot": name, "bottom": last_reply_text(s)}


def scenario_tabs(note):
    seen = {}
    for tab in ("atlas", "log", "bots", "me"):
        launch(tab); s = dump(); name = shot(f"{note}-{tab}")
        save_ui(s, name)
        seen[tab] = {"shot": name, "words": s.words()[:60]}
        health({"scenario": note, "label": tab})
    return seen


def scenario_stub_modes():
    """The stand-in AI answers with each shape in turn; what does Atlas show?"""
    prefs(stub_key_prefs())
    launch("atlas"); time.sleep(4)
    res = {}
    for mode, wait in [("ok", 8), ("md", 9), ("long", 10), ("429", 14), ("500", 14), ("503", 14), ("400key", 10), ("403", 10),
                       ("html502", 12), ("malformed", 10), ("empty", 10), ("maxtokens", 10), ("slow", 26)]:
        stub(mode)
        res[mode] = ask(f"deep test {mode} reply please", wait=wait, tag="stub-" + mode)
        log("stub", mode, "→", (res[mode] or {}).get("bottom"))
    # a reply still coming while the user leaves and returns, and while a second message is sent
    stub("slow")
    res["slow-leave"] = ask("deep test slow then leave", wait=2, tag="stub-slow-leave-start")
    home(); time.sleep(16); relaunch_task(); time.sleep(2)
    res["slow-leave"]["after_return"] = last_reply_text(dump()); res["slow-leave"]["after_shot"] = shot("stub-slow-after-return")
    stub("ok")
    # double send: two messages before the first answer arrives
    stub("slow")
    ask("deep test first of two", wait=1, tag="stub-double-1"); ask("deep test second of two", wait=24, tag="stub-double-2")
    res["double"] = {"bottom": last_reply_text(dump()), "shot": shot("stub-double-after")}
    stub("ok")
    # tools under the stand-in: what each phone tool tells the model (permissions are granted here)
    for t, a in [("calendar_events", {"days": 2}), ("find_contact", {"name": "mum"}), ("get_location", {}), ("read_log", {}),
                 ("set_reminder", {"title": "Deep ✓ test 測試", "when": "2031-01-16 16:00", "duration_min": 30}),
                 ("web_search", {"query": "atlas"}), ("open_link", {"url": "javascript:alert(1)"}), ("send_sms", {"to": "+15555550123", "text": "deep test"})]:
        stub(f"tool:{t}:{json.dumps(a)}", once=True)
        res["tool-" + t] = ask(f"deep test tool {t}", wait=10, tag="tool-" + t)
        s = dump()
        if s.pkg != PKG:
            event("left-app", after={"scenario": "tool", "label": t}, pkg=s.pkg, shot=shot("tool-left-" + t)); back(); relaunch_task()
        elif s.find("Cancel") or s.find("Not Now") or s.find("No"):
            res["tool-" + t]["asked_user"] = shot("tool-asks-" + t)
            n = s.find("Cancel") or s.find("Not Now") or s.find("No"); tap(n); time.sleep(1.5)
    SCEN["stub_modes"] = res


def airplane(on):
    v = "enable" if on else "disable"
    sh(f"cmd connectivity airplane-mode {v}")
    sh(f"svc wifi {'disable' if on else 'enable'}; svc data {'disable' if on else 'enable'}")
    time.sleep(4)


def scenario_offline():
    """No network (airplane mode, Wi-Fi and data off) with a key for the real service; then back online."""
    prefs(stub_key_prefs(endpoint=False))
    launch("atlas")
    airplane(True)
    r = {"offline": ask("deep test while offline", wait=20, tag="offline")}
    r["offline_me"] = (launch("me", fresh=False), shot("offline-me"))[1]
    r["offline_bots"] = (launch("bots", fresh=False), shot("offline-bots"))[1]
    launch("atlas", fresh=False)
    airplane(False); time.sleep(8)
    # the real Gemini, with a key that isn't real: its own "API key not valid" answer
    r["online_bad_key"] = ask("deep test back online with a key that is not real", wait=20, tag="online-bad-key")
    SCEN["offline"] = r


def rotate(to):
    sh("settings put system accelerometer_rotation 0"); sh(f"settings put system user_rotation {to}"); time.sleep(2.5)


def screen_size():
    global W, H
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    if m:
        W, H = map(int, m[-1])
    # landscape swaps them
    o = sh("dumpsys input | grep -m1 SurfaceOrientation")
    if re.search(r"SurfaceOrientation: [13]", o):
        W, H = max(W, H), min(W, H)
    else:
        W, H = min(W, H), max(W, H)


def atlas_row(s):
    """Atlas's own row on the Bots tab (its conversation)."""
    return next((n for n in s.nodes if n.click and re.match(r"Atlas\b.*chief of staff", n.desc or "")), None)


def scenario_lifecycle():
    r = {}
    prefs(stub_key_prefs())
    # rotation on every tab, and with the Chats sheet open
    for tab in ("atlas", "log", "bots", "me"):
        launch(tab, fresh=False); rotate(1); screen_size()
        s = dump(); r[f"rotate-{tab}"] = {"shot": shot(f"landscape-{tab}"), "words": len(s.words())}
        save_ui(s, f"landscape-{tab}.png")
        health({"scenario": "rotate", "label": tab})
        rotate(0); screen_size()
    launch("atlas", fresh=False); s = dump(); c = s.find("Chats")
    if c:
        tap(c); time.sleep(1.5); shot("chats-sheet"); rotate(1); screen_size(); r["chats-rotated"] = shot("chats-sheet-landscape")
        rotate(0); screen_size(); r["chats-after-rotate-back"] = shot("chats-sheet-portrait-again"); back()
    # background and back, with a draft in the ask bar
    launch("atlas", fresh=False); s = dump(); f = next((n for n in s.nodes if n.edit), None)
    if f:
        tap(f); type_text("draft that should survive"); key(4)
    home(); time.sleep(5); relaunch_task(); s = dump()
    r["background-return"] = {"shot": shot("background-return"), "draft_kept": any("draft that should survive" in n.text for n in s.nodes)}
    # process death in the background: Me scrolled down, then a bot's conversation with a draft
    for where in ("me", "bots"):
        launch(where, fresh=False)
        if where == "me":
            swipe_up(); swipe_up()
        else:
            s = dump(); row = atlas_row(s)
            if row:
                tap(row); time.sleep(3)
                s = dump(); f = next((n for n in s.nodes if n.edit), None)
                if f:
                    tap(f); type_text("bot draft that should survive"); key(4)
        before = dump(); bshot = shot(f"before-death-{where}")
        fg_before = foreground()
        home(); time.sleep(2)
        p0 = pid(); sh(f"am kill {PKG}"); time.sleep(2); p1 = pid(); how = "am kill"
        if p1 and p1 == p0:
            sh(f"kill -9 {p0.split()[0]}"); time.sleep(2); how = "kill -9 (am kill left it: a foreground service keeps Atlas alive)"
        relaunch_task(); time.sleep(2); after = dump()
        r[f"death-{where}"] = {"how": how, "pid_before": p0, "pid_after_kill": p1, "fg_before": fg_before, "fg_after": foreground(),
                               "before": bshot, "after": shot(f"after-death-{where}"),
                               "same_screen": before.sig() == after.sig(),
                               "draft_kept": any("draft that should survive" in n.text for n in after.nodes) if where == "bots" else None}
        health({"scenario": "process-death", "label": where})
    # don't keep activities: every screen is destroyed when left
    sh("settings put global always_finish_activities 1")
    for label, open_it in [("bot-chat", atlas_row),
                           ("library", lambda s: s.find("Library"))]:
        launch("bots", fresh=False); s = dump(); n = open_it(s)
        if not n:
            swipe_up(); s = dump(); n = open_it(s)
        if n:
            tap(n); time.sleep(3); b = shot(f"dka-{label}-open")
            home(); time.sleep(3); relaunch_task(); time.sleep(2)
            r[f"dka-{label}"] = {"open": b, "after": shot(f"dka-{label}-after-return"), "fg": foreground()}
            rotate(1); time.sleep(1); r[f"dka-{label}"]["rotated"] = shot(f"dka-{label}-rotated"); rotate(0)
            back(); health({"scenario": "dont-keep-activities", "label": label})
    sh("settings put global always_finish_activities 0")
    SCEN["lifecycle"] = r


def scenario_looks():
    r = {}
    # the orb's long press switches light and dark
    launch("atlas", fresh=False); s = dump()
    orb = s.find("Talk to Atlas. Hold")
    if orb:
        tap(orb, long=True); time.sleep(3)
        r["held-orb"] = {t: (launch(t, fresh=False), shot(f"held-orb-{t}"))[1] for t in ("atlas", "log", "bots", "me")}
        launch("atlas", fresh=False); s = dump(); orb = s.find("Talk to Atlas. Hold")
        if orb:
            tap(orb, long=True); time.sleep(3); r["held-orb-again"] = shot("held-orb-back")
    else:
        event("not-found", label="Talk to Atlas. Hold to switch between light and dark.")
    # the phone's own night mode flipping while Atlas is open
    for m in ("yes", "no"):
        sh(f"cmd uimode night {m}"); time.sleep(3); r[f"night-{m}"] = shot(f"system-night-{m}"); health({"scenario": "night", "label": m})
    sh("cmd uimode night auto")
    # big text and a bigger display size (people do this; OPPO ships 1.1 by default on some models)
    sh("settings put system font_scale 1.3"); time.sleep(2)
    r["font-1.3"] = scenario_tabs("font130")
    sh("settings put system font_scale 1.0")
    dens = re.findall(r"(\d+)", sh("wm density"))
    if dens:
        sh(f"wm density {int(int(dens[0]) * 1.25)}"); time.sleep(2)
        r["density-125"] = scenario_tabs("density125")
        sh("wm density reset"); time.sleep(2)
    # a narrow, short screen
    sh("wm size 720x1280"); time.sleep(2); screen_size()
    r["small-screen"] = scenario_tabs("small720x1280")
    sh("wm size reset"); time.sleep(2); screen_size()
    # Reduce Motion off: the moving look, for the record (uiautomator often can't read it, so pictures only)
    p = os.path.join(OUT, "atlas_ui_motion.xml")
    with open(p, "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <boolean name="reduceMotion" value="false" />\n' + stub_key_prefs() + "</map>\n")
    sh(f"am force-stop {PKG}"); put_file(p, "shared_prefs/atlas_ui.xml")
    motion = {}
    for tab in ("atlas", "log", "bots", "me"):
        launch(tab); motion[tab] = shot(f"motion-{tab}")
        sh(f"input swipe {W // 2} {int(H * 0.6)} {W // 2} {int(H * 0.3)} 300"); time.sleep(1)
        health({"scenario": "motion", "label": tab})
    adb("shell", f"dumpsys gfxinfo {PKG} > /data/local/tmp/gfx.txt")
    motion["gfxinfo"] = sh("cat /data/local/tmp/gfx.txt | head -40")
    r["motion"] = motion
    prefs(stub_key_prefs())
    SCEN["looks"] = r


def scenario_denied():
    """Every runtime permission taken away (Android stops Atlas when one is revoked), then features that need them."""
    r = {}
    perms = ["android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "android.permission.READ_CALENDAR",
             "android.permission.WRITE_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.ACCESS_FINE_LOCATION",
             "android.permission.ACCESS_COARSE_LOCATION", "android.permission.SEND_SMS"]
    launch("me", fresh=False)
    for p in perms:
        sh(f"pm revoke {PKG} {p}")
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW deny")
    time.sleep(2)
    r["after-revoke"] = {"pid": pid(), "fg": foreground()}
    relaunch_task(); r["relaunched"] = shot("denied-relaunched")
    health({"scenario": "denied", "label": "relaunch"})
    # the mic: Android's own question appears; the answer is no
    s = dump(); m = s.find("Talk to Atlas")
    if m:
        tap(m); time.sleep(2.5); s = dump(); r["mic-ask"] = {"pkg": s.pkg, "shot": shot("denied-mic-ask")}
        no = s.find("Don’t allow") or s.find("Don't allow") or s.find("Deny")
        if no:
            tap(no); time.sleep(2)
        r["mic-after-no"] = {"shot": shot("denied-mic-after-no"), "bottom": last_reply_text(dump())}
        if dump().pkg != PKG:
            back()
    # tools that need the permissions, asked through the stand-in
    launch("atlas", fresh=False)
    for t, a in [("calendar_events", {"days": 2}), ("find_contact", {"name": "mum"}), ("get_location", {}), ("calendar_add", {"title": "Deep", "start": "2031-01-16 16:00"})]:
        stub(f"tool:{t}:{json.dumps(a)}", once=True)
        r["tool-" + t] = ask(f"deep test denied {t}", wait=10, tag="denied-" + t)
        s = dump()
        if s.pkg != PKG:
            r["tool-" + t]["system_dialog"] = {"pkg": s.pkg, "shot": shot("denied-dialog-" + t)}
            no = s.find("Don’t allow") or s.find("Don't allow") or s.find("Deny")
            tap(no) if no else back(); time.sleep(2)
    # the permissions page in Me, as it reads now
    launch("me", fresh=False)
    for i in range(10):
        s = dump()
        if s.find("Permissions") or s.find("What Atlas Can Use"):
            break
        swipe_up()
    r["me-permissions"] = shot("denied-me")
    # put them back for the crawl
    for p in perms:
        sh(f"pm grant {PKG} {p}")
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    SCEN["denied"] = r


# ------------------------------------------------------------------ the crawl: every tap target on every screen

DANGER = re.compile(r"\b(delete|remove|erase|forget|wipe|reset|clear|unlink|disconnect|sign out|log ?out|uninstall|revoke|"
                    r"stop all|cancel all|end the session|discard|leave (the )?group|archive all)\b", re.I)
# not tapped by the crawl (they'd stop the crawl from reading screens, or are tried in scenarios)
SKIP = re.compile(r"^(Reduce Motion|Atlas, current page|Log, current page|Bots, current page|Me, current page|Atlas|Log|Bots|Me)$|"
                  r"Reduce Motion", re.I)
NAV = re.compile(r"^(Atlas|Log|Bots|Me)(, current page)?$")
INPUTS = ["", "x" * 600, "<script>alert(1)</script><img src=x onerror=alert(2)>", "-1", "0", "  ", "Robert'); DROP TABLE tasks;--",
          "a%s%sb", "https://example.com/" + "a" * 300, "99999999999999999999", "../../etc/passwd", "@everyone /approve"]
MUST = ["Chats", "New chat", "Add: take a photo", "New Bot", "New Group", "Library", "Routines", "Skills", "Details",
        "New Reminder", "Save to memory", "Talk to Atlas"]
seen_labels = set()
coverage = {"screens": {}, "tapped": 0, "danger": [], "inputs": 0, "left_app": [], "replay_failed": 0}
tapped_global = {}
explored = set()
done = set()


def overlay_top(s):
    """Where the floating tab bar (and the ask bar above it) begin: targets under them are reached by scrolling."""
    tops = [n.b[1] for n in s.nodes if NAV.match(n.desc or "")]
    edits = [n.b[1] for n in s.nodes if n.edit and n.b[1] > H * 0.6]
    t = min(tops + edits) if (tops or edits) else H
    return t - 24


def targets(s, root):
    top = overlay_top(s)
    out = []
    for n in s.nodes:
        if not (n.click or n.edit or n.long) or not n.enabled or n.area < 40:
            continue
        if n.pkg != PKG:
            continue
        lab = n.label or f"{n.cls.split('.')[-1]}@{n.cx // 60},{n.cy // 60}"
        if NAV.match(n.desc or ""):
            continue
        if n.cy > top and not root:
            continue
        if n.cy > top and root and not (n.edit or n.desc):
            continue
        if n.b[3] <= 0 or n.b[1] >= H or n.cy < 40:
            continue
        out.append((lab, n))
    return out


def replay(path):
    """Back to a screen the hard way: open its tab again and redo the taps that led there."""
    tab, steps = path[0], path[1:]
    launch(tab)
    for rounds, lab in steps:
        for _ in range(rounds):
            swipe_up()
        s = dump(); n = s.find(lab, exact=True) or s.find(lab)
        if not n:
            coverage["replay_failed"] += 1
            event("replay-failed", path=[tab] + [l for _, l in steps], missing=lab)
            return False
        tap(n); time.sleep(1.8)
    return True


def settle():
    time.sleep(1.3)


def sim(a, b):
    return len(a & b) / max(1, len(a | b))


def opened_something(before, after, act_before, act_after):
    """Did the tap open a new screen or sheet (go in, then Back), or only change this one (a toggle, a chip)?"""
    if act_before != act_after:
        return True
    a, b = before.labels(), after.labels()
    added, removed = b - a, a - b
    if len(added) >= 2 and len(removed) <= len(added):
        return True    # a sheet over the page (the page underneath is still read) or a section that opened
    return len(removed) > 3 and sim(a, b) < 0.5


def handle_foreign(s, ctx):
    """Atlas isn't in front: a permission question, a crash/ANR dialog, or another app. Note it and come back."""
    txt = " ".join(s.words())[:300]
    name = shot("left-" + slug(s.pkg, 30) + "-" + slug(ctx.get("label", ""), 20))
    if re.search(r"isn.t responding|keeps stopping|has stopped", txt):
        event("system-dialog", pkg=s.pkg, words=txt, shot=name, after=ctx)
        w = s.find("Wait") or s.find("Close app") or s.find("OK")
        if w:
            tap(w); time.sleep(2)
    else:
        coverage["left_app"].append({"pkg": s.pkg, "after": ctx, "shot": name})
        record("left-app", pkg=s.pkg, words=txt, shot=name, **ctx)
        ok = s.find("Allow") or s.find("While using the app") or s.find("Only this time")
        if ok and "permissioncontroller" in s.pkg:
            tap(ok); time.sleep(1.5)
    for _ in range(3):
        if dump().pkg == PKG:
            return True
        back()
    relaunch_task()
    return dump().pkg == PKG


def fill_inputs(s, path):
    for lab, n in targets(s, False):
        if not n.edit:
            continue
        k = ("input", s.sig(), lab)
        if k in done:
            continue
        done.add(k)
        val = INPUTS[coverage["inputs"] % len(INPUTS)]
        coverage["inputs"] += 1
        tap(n); time.sleep(0.6)
        clear_field()
        type_text(val); time.sleep(0.4)
        key(66); time.sleep(1.2)   # Enter: sends, saves or adds, wherever the field does
        ctx = {"path": [p if isinstance(p, str) else p[1] for p in path], "label": lab, "typed": val[:60]}
        bad = health(ctx)
        s2 = dump()
        record("type", shot=shot("typed-" + slug(lab, 24)) if bad or s2.sig() != s.sig() else None, result="crash" if bad else "ok", **ctx)
        if s2.pkg != PKG:
            handle_foreign(s2, ctx)
        return True   # one field per pass: the screen may have changed; the caller reads it again
    return False


def explore(path, depth):
    """Taps everything on this screen (scrolling down it), going into what opens, up to depth 3."""
    s = dump()
    if not s.ok or s.pkg != PKG:
        return
    sig0 = s.sig()
    labels0 = s.labels()
    here = foreground()[1]
    explored.add(sig0)
    root = depth == 0
    pname = "/".join([path[0]] + [l for _, l in path[1:]])
    coverage["screens"].setdefault(pname, {"sig": sig0[:200], "taps": 0})
    if len(coverage["screens"]) > 1:
        save_ui(s, f"screen-{len(coverage['screens']):03d}-{slug(pname, 40)}.png")
    rounds = 0
    while rounds < 14 and time.time() < DEADLINE:
        # odd text into the fields first, so the buttons then act on it
        for _ in range(4):
            if not fill_inputs(s, path):
                break
            s = dump()
            if s.pkg != PKG:
                break
        progressed = False
        for lab, n in targets(s, root):
            if time.time() > DEADLINE:
                return
            k = (sig0, rounds, lab)
            if k in done or n.edit:
                continue
            done.add(k)
            if SKIP.search(lab):
                continue
            if tapped_global.get(lab, 0) >= 3:
                continue
            if DANGER.search(lab):
                coverage["danger"].append({"path": path + [(rounds, lab)], "label": lab})
                continue
            tapped_global[lab] = tapped_global.get(lab, 0) + 1
            seen_labels.add(lab)
            ctx = {"path": [path[0]] + [l for _, l in path[1:]], "label": lab, "round": rounds}
            pre = s
            tap(n); settle()
            coverage["tapped"] += 1; coverage["screens"][pname]["taps"] += 1
            bad = health(ctx)
            s2 = dump()
            if not s2.ok:
                record("tap", result="unreadable", shot=shot("unreadable-" + slug(lab, 24)), **ctx)
                back(); s2 = dump()
            if s2.pkg != PKG:
                if not pid():
                    event("app-gone", after=ctx, shot=shot("app-gone-" + slug(lab, 24)))
                if not handle_foreign(s2, ctx):
                    replay(path) or launch(path[0])
                s = dump(); progressed = True
                if sim(s.labels(), pre.labels()) < 0.8:
                    if not replay(path):
                        return
                    for _ in range(rounds):
                        swipe_up()
                    s = dump()
                break
            if s2.sig() != pre.sig() and opened_something(pre, s2, here, foreground()[1]):
                name = shot(slug(pname, 30) + "--" + slug(lab, 30))
                record("tap", result="new-screen", shot=name, words=s2.words()[:40], crashed=bool(bad), **ctx)
                if depth < 3 and s2.sig() not in explored:
                    explore(path + [(rounds, lab)], depth + 1)
                back(); settle()
                s = dump()
                home_labels = pre.labels()
                if s.pkg != PKG or sim(s.labels(), home_labels) < 0.8:
                    # Back didn't come back here: one more, then the long way
                    if s.pkg == PKG:
                        back(); s = dump()
                    if s.pkg != PKG or sim(s.labels(), home_labels) < 0.8:
                        if not replay(path):
                            return
                        for _ in range(rounds):
                            swipe_up()
                        s = dump()
                progressed = True
                break   # the screen was rebuilt: read the targets again
            else:
                record("tap", result="same-screen" if s2.content() == s.content() else "changed", crashed=bool(bad), **ctx)
                if s2.content() != s.content():
                    s = s2; progressed = True
                    break   # positions may have moved: read the targets again
        if progressed:
            continue
        before = s.content()
        swipe_up(); rounds += 1
        s = dump()
        if s.pkg != PKG or s.content() == before:
            break


def danger_pass():
    """Each destructive button once: does Atlas ask first? (A hard rule: deleting always asks.)"""
    res = []
    tried = set()
    for d in coverage["danger"]:
        if time.time() > DEADLINE + 10 * 60:
            break
        lab = d["label"]
        if lab in tried:
            continue
        tried.add(lab)
        if not replay(d["path"][:-1]):
            continue
        rounds, _ = d["path"][-1]
        for _ in range(rounds):
            swipe_up()
        s = dump(); n = s.find(lab, exact=True) or s.find(lab)
        if not n:
            continue
        before = s
        tap(n); time.sleep(1.8)
        bad = health({"label": lab, "danger": True})
        s2 = dump()
        asked = s2.pkg != PKG or bool(s2.find("Cancel") or s2.find("Keep") or s2.find("Not Now") or s2.find("No") or
                                       any(re.search(r"\?$", w) for w in s2.words()))
        name = shot("danger-" + slug(lab, 30))
        gone = [w for w in before.words() if w not in s2.words()][:10]
        res.append({"label": lab, "path": [d["path"][0]] + [l for _, l in d["path"][1:]], "asked": asked, "shot": name, "vanished": gone, "crashed": bool(bad)})
        if not asked:
            event("danger-without-asking", label=lab, path=res[-1]["path"], vanished=gone, shot=name)
        c = s2.find("Cancel") or s2.find("Keep") or s2.find("Not Now") or s2.find("No")
        tap(c) if c else back(); time.sleep(1)
    SCEN["danger"] = res


def meminfo(tag):
    out = sh(f"dumpsys meminfo {PKG}", timeout=60)
    with open(os.path.join(OUT, f"meminfo-{tag}.txt"), "w") as f:
        f.write(out)
    m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"TOTAL\s+(\d+)", out)
    return int(m.group(1)) if m else None


def main():
    screen_size()
    log(f"screen {W}x{H}, budget {MINUTES} min")
    new_log_events({"scenario": "start"})
    mem = {"start": None}
    launch("atlas"); time.sleep(3)
    mem["start"] = meminfo("start")
    steps = [("tabs", lambda: SCEN.__setitem__("tabs", scenario_tabs("tab"))), ("stub_modes", scenario_stub_modes), ("offline", scenario_offline),
             ("lifecycle", scenario_lifecycle), ("looks", scenario_looks), ("denied", scenario_denied)]
    if ONLY != "crawl":
        for name, fn in steps:
            log("scenario", name)
            try:
                fn()
            except Exception as e:
                event("rig-error", scenario=name, error=repr(e))
                sh("settings put global always_finish_activities 0"); rotate(0); sh("wm size reset"); sh("wm density reset"); sh("settings put system font_scale 1.0")
            with open(os.path.join(OUT, "scenarios.json"), "w") as f:
                json.dump(SCEN, f, ensure_ascii=False, indent=1)
    if ONLY != "scenarios":
        global DEADLINE
        DEADLINE = time.time() + MINUTES * 60   # the crawl's own budget, after the scenarios
        prefs(stub_key_prefs()); stub("ok")
        mem["before_crawl"] = (launch("atlas"), meminfo("before-crawl"))[1]
        for tab in ("atlas", "log", "bots", "me"):
            if time.time() > DEADLINE:
                break
            log("crawl", tab)
            try:
                launch(tab)
                explore([tab], 0)
            except Exception as e:
                event("rig-error", crawl=tab, error=repr(e))
        log("danger pass", len(coverage["danger"]))
        try:
            danger_pass()
        except Exception as e:
            event("rig-error", scenario="danger", error=repr(e))
    launch("atlas", fresh=False); time.sleep(3)
    mem["end"] = meminfo("end")
    coverage["must_reach"] = {m: any(l.lower().startswith(m.lower()) for l in seen_labels) for m in MUST}
    coverage["memory_pss_kb"] = mem
    coverage["minutes"] = round((time.time() - T0) / 60, 1)
    with open(os.path.join(OUT, "coverage.json"), "w") as f:
        json.dump(coverage, f, ensure_ascii=False, indent=1, default=str)
    with open(os.path.join(OUT, "scenarios.json"), "w") as f:
        json.dump(SCEN, f, ensure_ascii=False, indent=1)
    log("done:", coverage["tapped"], "taps,", len(coverage["screens"]), "screens")


if __name__ == "__main__":
    main()
