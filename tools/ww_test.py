"""Feeds synthetic speech to Vosk in the same grammar mode Atlas uses and reports what fires."""
import json, subprocess, sys, wave, os
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(0)
model = Model(sys.argv[1])
GRAMMAR = json.dumps(["hey atlas", "[unk]"])
phrases = {
    "hey atlas": True, "hey, atlas": True, "hey atlas, remind me to call mum": True, "okay so hey atlas": True,
    "hey alice": False, "atlas": False, "the atlas mountains": False, "hey alex": False, "hey at last": False,
    "open whatsapp": False, "what is the weather like today": False, "hey": False, "hello there": False,
}
voices = ["en-us", "en-gb", "en-us+m3", "en-us+f3", "en-gb-x-rp"]
speeds = [140, 175]
def fires(path):
    wf = wave.open(path, "rb")
    rec = KaldiRecognizer(model, wf.getframerate(), GRAMMAR)
    hit = False; heard = []
    while True:
        d = wf.readframes(1600)
        if not d: break
        if rec.AcceptWaveform(d):
            t = json.loads(rec.Result()).get("text", ""); heard.append(t)
            if "hey atlas" in t: hit = True
        else:
            p = json.loads(rec.PartialResult()).get("partial", "")
            if "hey atlas" in p: hit = True
    t = json.loads(rec.FinalResult()).get("text", ""); heard.append(t)
    if "hey atlas" in t: hit = True
    return hit, " | ".join(h for h in heard if h)
tp = fn = fp = tn = 0
for ph, want in phrases.items():
    for v in voices:
        for s in speeds:
            raw = "/tmp/raw.wav"; out = "/tmp/s.wav"
            subprocess.run(["espeak-ng", "-v", v, "-s", str(s), "-w", raw, ph], check=True, capture_output=True)
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-ar", "16000", "-ac", "1", out], check=True)
            hit, heard = fires(out)
            mark = "ok " if hit == want else "BAD"
            if want and hit: tp += 1
            elif want: fn += 1
            elif hit: fp += 1
            else: tn += 1
            print(f"{mark} want={want!s:5} fired={hit!s:5} voice={v:12} speed={s} said='{ph}' heard='{heard}'")
print(f"\nSUMMARY true-pos={tp} missed={fn} false-alarms={fp} true-neg={tn}")
