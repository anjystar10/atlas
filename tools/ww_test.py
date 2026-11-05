"""Feeds synthetic speech to Vosk and compares ways of deciding that "hey atlas" was said."""
import json, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model = Model(sys.argv[1])
BASIC = ["hey atlas", "[unk]"]
DECOY = ["hey atlas", "hey", "atlas", "hey alice", "hey alex", "hey adam", "hey at last", "at last", "the atlas", "hey there",
         "okay", "hello", "alice", "alex", "[unk]"]
recs = {}
for name, g in (("basic", BASIC), ("decoy", DECOY)):
    r = KaldiRecognizer(model, 16000, json.dumps(g)); r.SetWords(True); recs[name] = r
pos = ["hey atlas", "hey, atlas", "hey atlas, remind me to call mum", "okay so hey atlas", "hey atlas what time is it"]
neg = ["hey alice", "atlas", "the atlas mountains", "hey alex", "hey at last", "open whatsapp", "what is the weather like today",
       "hey", "hello there", "hey ellis", "hey adam", "hey siri", "okay google", "I bought an atlas yesterday", "hey that's lost",
       "at last you are here", "hey everyone"]
voices = ["en-us", "en-gb", "en-us+f3"]
def run(rec, path):
    wf = wave.open(path, "rb"); rec.Reset()
    partial_hit = False; finals = []
    while True:
        d = wf.readframes(1600)
        if not d: break
        if rec.AcceptWaveform(d): finals.append(json.loads(rec.Result()))
        elif "hey atlas" in json.loads(rec.PartialResult()).get("partial", ""): partial_hit = True
    finals.append(json.loads(rec.FinalResult()))
    return partial_hit, [f for f in finals if f.get("text")]
def conf_of(finals):
    best = 0.0
    for f in finals:
        ws = f.get("result", [])
        for i in range(len(ws) - 1):
            if ws[i]["word"] == "hey" and ws[i + 1]["word"] == "atlas":
                best = max(best, min(ws[i]["conf"], ws[i + 1]["conf"]))
    return best
rows = []
for ph in pos + neg:
    want = ph in pos
    for v in voices:
        subprocess.run(["espeak-ng", "-v", v, "-s", "160", "-w", "/tmp/raw.wav", ph], check=True, capture_output=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "16000", "-ac", "1", "/tmp/s.wav"], check=True)
        row = {"said": ph, "voice": v, "want": want}
        for name, rec in recs.items():
            p, finals = run(rec, "/tmp/s.wav")
            row[name] = {"partial": p, "final": any("hey atlas" in f["text"] for f in finals), "conf": round(conf_of(finals), 3),
                         "texts": [f["text"] for f in finals]}
        rows.append(row)
        print(json.dumps(row), flush=True)
def score(label, fires):
    tp = sum(1 for r in rows if r["want"] and fires(r)); fn = sum(1 for r in rows if r["want"] and not fires(r))
    fp = sum(1 for r in rows if not r["want"] and fires(r)); tn = sum(1 for r in rows if not r["want"] and not fires(r))
    bad = sorted({r["said"] for r in rows if not r["want"] and fires(r)})
    print(f"STRATEGY {label:38} caught={tp}/{tp+fn} false_alarms={fp}/{fp+tn} on={bad}", flush=True)
score("basic partial (old)", lambda r: r["basic"]["partial"] or r["basic"]["final"])
score("basic final only", lambda r: r["basic"]["final"])
for t in (0.5, 0.7, 0.8, 0.9, 0.95, 0.99):
    score(f"basic final conf>={t}", lambda r, t=t: r["basic"]["final"] and r["basic"]["conf"] >= t)
score("decoy final only", lambda r: r["decoy"]["final"])
for t in (0.5, 0.7, 0.8, 0.9, 0.95, 0.99):
    score(f"decoy final conf>={t}", lambda r, t=t: r["decoy"]["final"] and r["decoy"]["conf"] >= t)
