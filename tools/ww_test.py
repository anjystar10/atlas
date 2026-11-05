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
    for r, pcm in events:
        if "hey atlas" in r.get("text", "") and wake_conf(r) >= 0.7:
            ok, t = check_ok(pcm)
            if ok: return True, r.get("text"), t
            return False, r.get("text"), "rejected: " + t
    return False, " | ".join(r.get("text", "") for r, _ in events if r.get("text")), ""
pos = ["hey atlas", "hey atlas open youtube", "um hey atlas", "hey atlas set an alarm for seven", "so hey atlas can you help me"]
neg = ["hey alice", "hey ellis", "hey alan", "hey alexa", "atlas", "at last", "the atlas mountains", "hey anna", "hey dallas",
       "hey at lunch", "hey atlantic", "I need an atlas", "hey adam how are you", "can you pass me the salt",
       "we watched a movie last night and it was really good", "turn left at the next street", "hey guys what's up",
       "my brother lives in atlanta", "hey thanks for that", "let me check the weather"]
voices = ["en-us+m3", "en-gb-x-rp", "en-us+f4", "en-gb-scotland"]
speeds = [140, 185]
tp = fn = fp = tn = 0
for ph in pos + neg:
    want = ph in pos
    for v in voices:
        for s in speeds:
            r = subprocess.run(["espeak-ng", "-v", v, "-s", str(s), "-w", "/tmp/raw.wav", ph], capture_output=True)
            if r.returncode != 0: print("skip voice", v, flush=True); continue
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "/tmp/raw.wav", "-ar", "16000", "-ac", "1", "/tmp/s.wav"], check=True)
            hit, heard, chk = fires("/tmp/s.wav")
            if want and hit: tp += 1
            elif want: fn += 1
            elif hit: fp += 1
            else: tn += 1
            print(f"{'ok ' if hit == want else 'BAD'} want={want!s:5} fired={hit!s:5} {v:15} {s} '{ph}' heard='{heard}' check='{chk}'", flush=True)
print(f"HELDOUT caught={tp}/{tp+fn} false_alarms={fp}/{fp+tn}", flush=True)
