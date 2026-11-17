"""
Hey Atlas: how often does the wake word fire when it should, and how often when it shouldn't?

Runs the real Vosk engine (the same one the phone uses) over synthesized speech with noise mixed in, and replays
the phone's wake logic chunk by chunk (0.1 s, like Hotword.kt) for several variants, so changes are chosen by numbers.

Inputs it builds itself: speech from espeak-ng (several accents) and piper (natural voices, when downloadable),
positive clips ("hey atlas ...") and negative clips (ordinary speech, look-alikes such as "hey Dallas"), each at
several noise levels. Output: a Markdown table on stdout (also written to $GITHUB_STEP_SUMMARY in CI).
"""
import json, os, random, subprocess, sys, wave, glob, time, math
import numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
RATE = 16000
CHUNK = RATE // 10  # 0.1 s
random.seed(7); np.random.seed(7)
WORK = os.environ.get("WORK", "/tmp/hw")
os.makedirs(WORK, exist_ok=True)

POSITIVE = [
    "hey atlas", "hey atlas what's the weather today", "hey atlas, set a reminder for six pm",
    "hey atlas open whatsapp", "hey atlas call my sister", "hey atlas how far is lagos from abuja",
    "hey atlas play some music", "hey atlas what time is it",
]
NEGATIVE = [
    "hey dallas how are you doing", "hey alice can you pass the salt", "at last we finally made it home",
    "the atlas mountains are in morocco", "hey alex, what's up", "hey at least we tried", "hey I'll ask her later",
    "I left my atlas at school", "hey, at lunch let's talk", "hey, add this to the list",
    "can you send me the file tomorrow", "the weather is really nice today", "let's go to the market in the evening",
    "I think the meeting starts at nine", "turn off the lights in the kitchen", "what did you have for breakfast",
    "the match was amazing last night", "please call me when you get home", "I need to buy some bread and milk",
    "he said that the bus was late again", "we should practise chess this weekend", "my phone battery is almost dead",
    "hey there, long time no see", "hey, hold on a second", "hey, at last you're here",
]

def tts_espeak(text, voice, speed, path):
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", path, text], check=True, capture_output=True)

PIPER_VOICES = []
def setup_piper():
    """Natural voices, if piper and its voices could be installed in CI (optional)."""
    try:
        import piper  # noqa
    except Exception:
        return
    for onnx in sorted(glob.glob(os.path.join(WORK, "voices", "*.onnx"))):
        PIPER_VOICES.append(onnx)

def tts_piper(text, onnx, path):
    subprocess.run([sys.executable, "-m", "piper", "-m", onnx, "-f", path], input=text.encode(), check=True, capture_output=True)

def load16k(path):
    out = path + ".16k.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-ar", str(RATE), "-ac", "1", "-sample_fmt", "s16", out], check=True)
    with wave.open(out) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

def noise(kind, n):
    if kind == "white":
        return np.random.randn(n).astype(np.float32)
    if kind == "pink":
        w = np.fft.rfft(np.random.randn(n)); f = np.arange(len(w)); f[0] = 1
        return np.fft.irfft(w / np.sqrt(f), n).astype(np.float32)
    if kind == "babble":
        return BABBLE[np.random.randint(0, len(BABBLE) - n)] if False else babble(n)
    return np.zeros(n, np.float32)

BABBLE = None
def babble(n):
    out = np.zeros(n, np.float32)
    for _ in range(4):
        src = BABBLE_SRC[np.random.randint(len(BABBLE_SRC))]
        if len(src) < n: src = np.tile(src, n // len(src) + 1)
        s = np.random.randint(0, len(src) - n + 1)
        out += src[s:s + n]
    return out

def mix(speech, kind, snr_db):
    pad = np.zeros(int(RATE * 0.8), np.float32)
    clip = np.concatenate([pad, speech, np.zeros(int(RATE * 1.5), np.float32)])
    if kind == "clean":
        return clip
    nz = noise(kind, len(clip))
    ps = np.mean(speech ** 2) + 1e-12; pn = np.mean(nz ** 2) + 1e-12
    nz *= math.sqrt(ps / (pn * 10 ** (snr_db / 10)))
    y = clip + nz
    return y / max(1.0, np.max(np.abs(y)) * 1.05)

def pcm(y):
    return (np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes()

# ---------------------------------------------------------------- the phone's logic, variant by variant

def words(t): return t.split()

def check_strict(text): return "atlas" in words(text)
def check_balanced(text):
    w = words(text)
    if any(x in ("atlas", "atlases", "atlas's") for x in w): return True
    j = " ".join(w)
    return any(p in j for p in ("at las", "at less", "at lass", "at lust"))

class Variant:
    def __init__(self, name, grammar, use_partial, check):
        self.name, self.grammar, self.use_partial, self.check = name, grammar, use_partial, check

VARIANTS = [
    Variant("0.10: end of utterance, strict check", ["hey atlas", "[unk]"], False, check_strict),
    Variant("0.10.2: early (partial), strict check", ["hey atlas", "[unk]"], True, check_strict),
    Variant("0.10.2 'Wake More Easily': early, balanced check", ["hey atlas", "[unk]"], True, check_balanced),
    Variant("(reference) early, no second check", ["hey atlas", "[unk]"], True, None),
]

def run_variant(model, v, audio_bytes):
    """Returns (fired, seconds from start of audio to firing) like Hotword.kt would."""
    listen = KaldiRecognizer(model, RATE, json.dumps(v.grammar))
    check = KaldiRecognizer(model, RATE)
    utt = bytearray()
    t = 0.0
    phrases = [p for p in v.grammar if p != "[unk]"]
    for i in range(0, len(audio_bytes), CHUNK * 2):
        buf = audio_bytes[i:i + CHUNK * 2]
        t += len(buf) / 2 / RATE
        utt += buf
        done = listen.AcceptWaveform(bytes(buf))
        heard = None
        if done:
            heard = json.loads(listen.Result()).get("text", "")
        elif v.use_partial:
            heard = json.loads(listen.PartialResult()).get("partial", "")
        if heard and any(p in heard for p in phrases):
            if v.check is None:
                return True, t
            check.Reset(); check.AcceptWaveform(bytes(utt))
            verdict = json.loads(check.FinalResult()).get("text", "")
            if v.check(verdict):
                return True, t
            if v.use_partial and not done:
                # don't re-check the same partial forever: wait for the utterance to end
                continue
        if done:
            utt = bytearray()
    return False, t

def main():
    global BABBLE_SRC
    model_dirs = [d for d in sys.argv[1:]]
    setup_piper()
    voices = [("espeak", v, s) for v, s in (("en-us", 150), ("en-gb", 165), ("en-us+f3", 140), ("en-gb-x-rp", 175))]
    voices += [("piper", p, 0) for p in PIPER_VOICES]
    def synth(text, vv, tag):
        kind, voice, speed = vv
        path = os.path.join(WORK, f"{tag}.wav")
        (tts_espeak(text, voice, speed, path) if kind == "espeak" else tts_piper(text, voice, path))
        return load16k(path)
    print(f"voices: {len(voices)} ({len(PIPER_VOICES)} natural)", flush=True)
    pos = [(p, synth(p, vv, f"p{i}_{j}")) for i, p in enumerate(POSITIVE) for j, vv in enumerate(voices)]
    neg = [(n, synth(n, vv, f"n{i}_{j}")) for i, n in enumerate(NEGATIVE) for j, vv in enumerate(voices) if j % 2 == 0]
    BABBLE_SRC = [a for _, a in neg[: 40]]
    conds = [("clean", None), ("babble", 15), ("pink", 10), ("babble", 5)]
    rows = []
    for md in model_dirs:
        model = Model(md)
        for v in VARIANTS:
            res = {}
            for kind, snr in conds:
                hit = 0; lat = []
                for text, a in pos:
                    y = mix(a, kind, snr or 0)
                    ok, t = run_variant(model, v, pcm(y))
                    if ok:
                        hit += 1
                        # latency from the end of the word "atlas" (approx: end of "hey atlas" ~ 0.8 s pad + phrase share)
                        frac = len("hey atlas") / max(len(text), len("hey atlas"))
                        lat.append(t - (0.8 + frac * len(a) / RATE))
                fa = 0; secs = 0.0
                for text, a in neg:
                    y = mix(a, kind, snr or 0)
                    ok, _ = run_variant(model, v, pcm(y)); fa += ok; secs += len(y) / RATE
                res[(kind, snr)] = (hit / len(pos), fa, secs, (sorted(lat)[len(lat) // 2] if lat else float("nan")))
            rows.append((os.path.basename(md.rstrip("/")), v.name, res))
            print(f"done {md} / {v.name}", flush=True)
    # the words after "Hey Atlas": cut where the check says "atlas" ends, decode the rest
    model = Model(model_dirs[0])
    kept = 0; total = 0; lost_examples = []
    for text, a in pos:
        rest = text.split("atlas", 1)[1].strip(" ,")
        if not rest: continue
        total += 1
        y = pcm(mix(a, "clean", 0))
        chk = KaldiRecognizer(model, RATE); chk.SetWords(True); chk.AcceptWaveform(y)
        r = json.loads(chk.FinalResult()); end = None
        for w in r.get("result", []):
            if w.get("word") == "atlas": end = w["end"]
        if end is None: continue
        tail = y[int(end * RATE) * 2:]
        dec = KaldiRecognizer(model, RATE); dec.AcceptWaveform(tail)
        got = json.loads(dec.FinalResult()).get("text", "")
        first = rest.split()[0].strip(",'").lower().replace("'", "")
        if first and first[:3] in got.replace("'", "")[:12]: kept += 1
        elif len(lost_examples) < 4: lost_examples.append(f"said '{rest}', after the cut heard '{got}'")
    tail_line = f"\nRequest kept after the cut at the end of \"atlas\": first word heard in {kept}/{total} clean clips." + ("" if not lost_examples else " Missed: " + "; ".join(lost_examples))
    out = ["| model | variant | " + " | ".join(f"{k}{'' if s is None else ' '+str(s)+'dB'}: hits / false wakes" for k, s in conds) + " | median wake delay (clean) |",
           "|---|---|" + "---|" * len(conds) + "---|"]
    for m, name, res in rows:
        cells = [f"{res[c][0]*100:.0f}% / {res[c][1]}" for c in conds]
        out.append(f"| {m} | {name} | " + " | ".join(cells) + f" | {res[conds[0]][3]:.2f}s |")
    out.append(f"\n{len(pos)} positive clips and {len(neg)} negative clips per condition ({sum(len(a) for _, a in neg)/RATE/60:.1f} min of non-wake speech).")
    text = "\n".join(out) + tail_line
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write("## Hey Atlas wake-word evaluation\n\n" + text + "\n")

if __name__ == "__main__":
    main()
