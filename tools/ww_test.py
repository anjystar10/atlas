"""
Held-out check of Atlas's wake word rule (the exact rule in Hotword.kt):
  1. the listener (grammar: "hey atlas" or unknown) ends an utterance with "hey atlas", both words at confidence >= 0.7;
  2. a second pass over the same audio, with look-alike names as alternatives, still hears "atlas" and no look-alike.
Voices, speeds and phrases here were NOT used to choose the rule.
"""
import json, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model = Model(sys.argv[1])
LISTEN = ["hey atlas", "[unk]"]
CHECK = ["hey atlas", "hey", "atlas", "hey alice", "hey alex", "hey adam", "hey ellis", "hey at last", "at last", "hey there",
         "okay", "hello", "alice", "alex", "ellis", "[unk]"]
NEAR = {"alice", "alex", "adam", "ellis"}
listen = KaldiRecognizer(model, 16000, json.dumps(LISTEN)); listen.SetWords(True)
check = KaldiRecognizer(model, 16000, json.dumps(CHECK)); check.SetWords(True)
free = KaldiRecognizer(model, 16000); free.SetWords(True)   # no grammar: the whole English vocabulary
def free_text(pcm):
    free.Reset(); free.AcceptWaveform(pcm); return json.loads(free.FinalResult()).get("text", "")
def wake_conf(res):
    ws = res.get("result", []); best = 0.0
    for i in range(len(ws) - 1):
        if ws[i]["word"] == "hey" and ws[i + 1]["word"] == "atlas": best = max(best, min(ws[i]["conf"], ws[i + 1]["conf"]))
    return best
def check_ok(pcm):
    check.Reset(); check.AcceptWaveform(pcm); t = json.loads(check.FinalResult()).get("text", "")
    w = t.split()
    return ("at last" not in t) and not any(x in NEAR for x in w) and ("atlas" in w), t
def fires(path):
    wf = wave.open(path, "rb"); listen.Reset(); utt = bytearray(); events = []
    while True:
        d = wf.readframes(1600)
        if not d: break
        utt += d
        if listen.AcceptWaveform(d):
            r = json.loads(listen.Result()); events.append((r, bytes(utt))); utt = bytearray()
    events.append((json.loads(listen.FinalResult()), bytes(utt)))
    out = {"A": False, "F0": False, "F7": False, "freeall": False}
    texts = []
    whole = b"".join(p for _, p in events)
    ft_all = free_text(whole)
    out["freeall"] = "atlas" in ft_all.split()
    for r, pcm in events:
        texts.append(r.get("text", ""))
        if "hey atlas" in r.get("text", ""):
            ft = free_text(pcm); texts.append("free:" + ft)
            fa = "atlas" in ft.split()
            out["F0"] = out["F0"] or fa
            if wake_conf(r) >= 0.7:
                out["F7"] = out["F7"] or fa
                ok, t = check_ok(pcm); out["A"] = out["A"] or ok
    return out, " | ".join(t for t in texts if t), ft_all
pos = ["hey atlas", "hey atlas open youtube", "um hey atlas", "hey atlas set an alarm for seven", "so hey atlas can you help me"]
neg = ["hey alice", "hey ellis", "hey alan", "hey alexa", "atlas", "at last", "the atlas mountains", "hey anna", "hey dallas",
       "hey at lunch", "hey atlantic", "I need an atlas", "hey adam how are you", "can you pass me the salt",
       "we watched a movie last night and it was really good", "turn left at the next street", "hey guys what's up",
       "my brother lives in atlanta", "hey thanks for that", "let me check the weather"]
voices = ["en-us+m3", "en-gb-x-rp", "en-us+f4", "en-gb-scotland", "en-us", "en-gb", "en-us+f3"]
speeds = [140, 185]
from collections import defaultdict
tally = defaultdict(lambda: [0, 0, 0, 0]); fps = defaultdict(set)
for ph in pos + neg:
    want = ph in pos
    for v in voices:
        for s in speeds:
            r = subprocess.run(["espeak-ng", "-v", v, "-s", str(s), "-w", "/tmp/raw.wav", ph], capture_output=True)
            if r.returncode != 0: print("skip voice", v, flush=True); continue
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "16000", "-ac", "1", "/tmp/s.wav"], check=True)
            out, heard, ft_all = fires("/tmp/s.wav")
            for k, hit in out.items():
                t = tally[k]
                if want and hit: t[0] += 1
                elif want: t[1] += 1
                elif hit: t[2] += 1; fps[k].add(ph)
                else: t[3] += 1
            print(f"want={want!s:5} {json.dumps(out)} {v:15} {s} '{ph}' heard='{heard}' free_all='{ft_all}'", flush=True)
names = {"A": "grammar check (current)", "F0": "listener + free decode", "F7": "listener conf>=0.7 + free decode", "freeall": "free decode only"}
for k, (tp, fn, fp, tn) in tally.items():
    print(f"RULE {names[k]:34} caught={tp}/{tp+fn} false_alarms={fp}/{fp+tn} on={sorted(fps[k])}", flush=True)
