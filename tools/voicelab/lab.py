"""Atlas voice lab: generate candidate voices for Atlas, score them, master them.

Runs on GitHub's CPU runners (see .github/workflows/voice-lab.yml), one step per engine, each in its own Python env
because the engines pin different library versions. Every voice here is ORIGINAL: designed from a written list of traits
(Qwen3-TTS VoiceDesign) or a stock synthetic voice (Kokoro), and the cloning engines only ever clone those designed
clips. No recording of a real person is used, and no real person is named in any prompt.

Licences (checked Nov 2026): Qwen3-TTS Apache-2.0, Chatterbox MIT, Kokoro-82M Apache-2.0. All allow personal and
commercial use.

  python lab.py design   OUT     # Qwen3-TTS VoiceDesign 1.7B: 3 voices x 2 takes, plus a clone check (Base 1.7B)
  python lab.py chatter  OUT     # Chatterbox: re-voice each designed take with its expressive model
  python lab.py kokoro   OUT     # Kokoro-82M British male stock voices, as a baseline
  python lab.py score    OUT     # UTMOS (naturalness), Whisper word error rate, speaker similarity; master; report
"""
import json, os, sys, time, glob, subprocess

MAIN = ("Good evening. I've cleared your inbox and moved the meeting to Thursday. "
        "Shall I start the trading bot, or would you prefer to look at the plan first?")
CLONE = ("Of course. The demo order is placed, and I'll keep an eye on the price. "
         "Do let me know if you'd like anything else tonight.")

VOICES = {
    "A": ("Refined British man around forty with Received Pronunciation and a mid-low, resonant baritone. Calm, precise "
          "and unhurried, with crisp consonants. Warm underneath, with a dry, understated wit, faintly amused. Composed, "
          "quietly confident and impeccably polite, like a trusted butler who runs everything flawlessly. Close, clean "
          "studio recording."),
    "B": ("Polished English gentleman with a deep, velvety voice and an intimate close-microphone delivery. Smooth and "
          "measured, with gentle natural pauses; dry humour, never theatrical. Calm authority and warmth. Clean studio "
          "sound."),
    "C": ("Crisp, articulate British male voice, mid-low pitch, clipped and precise diction, cool and efficient but "
          "never rushed, with a subtle hint of irony, like a highly capable assistant giving a status report. Neutral, "
          "clean, broadcast-quality recording."),
}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def save(path, wav, sr):
    import soundfile as sf
    sf.write(path, wav, sr)
    log("wrote", path, f"{len(wav) / sr:.1f}s")


def design(out):
    import torch
    from qwen_tts import Qwen3TTSModel
    torch.set_num_threads(os.cpu_count() or 4)
    os.makedirs(out, exist_ok=True)
    m = Qwen3TTSModel.from_pretrained("Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign", device_map="cpu", dtype=torch.float32)
    for vid, desc in VOICES.items():
        for take in (1, 2):
            torch.manual_seed(1000 * take + ord(vid))
            t = time.time()
            wavs, sr = m.generate_voice_design(text=MAIN, instruct=desc, language="English",
                                               do_sample=True, temperature=0.8, top_p=0.9)
            save(f"{out}/qwen-design-{vid}{take}.wav", wavs[0], sr)
            log(f"design {vid}{take} took {time.time() - t:.0f}s")
    del m
    import gc; gc.collect()
    # clone check: the Base model speaks a NEW sentence from each designed take 1 (in-context mode, text known)
    m = Qwen3TTSModel.from_pretrained("Qwen/Qwen3-TTS-12Hz-1.7B-Base", device_map="cpu", dtype=torch.float32)
    for vid in VOICES:
        torch.manual_seed(7)
        t = time.time()
        wavs, sr = m.generate_voice_clone(text=CLONE, language="English",
                                          ref_audio=f"{out}/qwen-design-{vid}1.wav", ref_text=MAIN)
        save(f"{out}/qwen-clone-{vid}.wav", wavs[0], sr)
        log(f"clone {vid} took {time.time() - t:.0f}s")


def chatter(out):
    import torch
    from chatterbox.tts import ChatterboxTTS
    torch.set_num_threads(os.cpu_count() or 4)
    m = ChatterboxTTS.from_pretrained(device="cpu")
    for ref in sorted(glob.glob(f"{out}/qwen-design-*1.wav")):
        vid = os.path.basename(ref)[len("qwen-design-")][0]
        for text, tag in ((MAIN, "main"), (CLONE, "clone")):
            torch.manual_seed(11)
            t = time.time()
            # calm and deliberate: a little below neutral expressiveness, lower cfg = slower, more measured pacing
            wav = m.generate(text, audio_prompt_path=ref, exaggeration=0.45, cfg_weight=0.35, temperature=0.7)
            save(f"{out}/chatterbox-{tag}-{vid}.wav", wav.squeeze(0).numpy(), m.sr)
            log(f"chatterbox {tag} {vid} took {time.time() - t:.0f}s")


def kokoro(out):
    import numpy as np
    from kokoro import KPipeline
    p = KPipeline(lang_code="b")
    for voice in ("bm_george", "bm_fable", "bm_lewis"):
        audio = [a for _, _, a in p(MAIN, voice=voice, speed=0.95)]
        save(f"{out}/kokoro-{voice}.wav", np.concatenate([np.asarray(a) for a in audio]), 24000)


def master(src, dst):
    """Gentle broadcast chain: rumble cut, a little mud out, a little presence in, de-ess, soft compression, -16 LUFS."""
    chain = ("highpass=f=70,equalizer=f=250:t=q:w=1:g=-1.5,equalizer=f=3500:t=q:w=1.2:g=1.5,deesser=i=0.3,"
             "acompressor=threshold=-20dB:ratio=2.5:attack=8:release=120:makeup=1.5,loudnorm=I=-16:TP=-1.5:LRA=7")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-af", chain, "-ar", "24000", "-ac", "1", dst],
                   check=True)


def score(out):
    import numpy as np, soundfile as sf, librosa, torch, jiwer
    from faster_whisper import WhisperModel
    from resemblyzer import VoiceEncoder, preprocess_wav
    utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True)
    asr = WhisperModel("small.en", device="cpu", compute_type="int8")
    enc = VoiceEncoder("cpu")
    norm = jiwer.Compose([jiwer.ToLowerCase(), jiwer.RemovePunctuation(), jiwer.RemoveMultipleSpaces(), jiwer.Strip()])
    fin = f"{out}/finished"; os.makedirs(fin, exist_ok=True)

    def mos(path):
        y, _ = librosa.load(path, sr=16000)
        with torch.no_grad():
            return float(utmos(torch.from_numpy(y).unsqueeze(0), 16000).item())

    def wer(path, ref):
        segs, _ = asr.transcribe(path, beam_size=5, language="en")
        hyp = " ".join(s.text for s in segs)
        return float(jiwer.wer(norm(ref), norm(hyp))), hyp.strip()

    rows = []
    for path in sorted(glob.glob(f"{out}/*.wav")):
        name = os.path.basename(path)[:-4]
        ref = CLONE if "clone" in name else MAIN
        mastered = f"{fin}/{name}-mastered.wav"
        master(path, mastered)
        finished = f"{fin}/{name}-finished.wav"
        subprocess.run(["node", "tools/voicelab/finish.mjs", mastered, finished], check=True)
        w, hyp = wer(path, ref)
        y, sr = sf.read(path)
        rows.append({"name": name, "seconds": round(len(y) / sr, 2), "mos_raw": round(mos(path), 3),
                     "mos_mastered": round(mos(mastered), 3), "mos_finished": round(mos(finished), 3),
                     "wer": round(w, 3), "heard": hyp,
                     "words_per_min": round(len(norm(ref).split()) / (len(y) / sr) * 60, 1)})
        log(rows[-1])

    # speaker similarity: does each clone still sound like the designed voice it came from?
    emb = lambda p: enc.embed_utterance(preprocess_wav(p))
    for r in rows:
        n = r["name"]
        for prefix in ("qwen-clone-", "chatterbox-main-", "chatterbox-clone-"):
            if n.startswith(prefix):
                vid = n[len(prefix):]
                base = f"{out}/qwen-design-{vid}1.wav"
                if os.path.exists(base):
                    r["similarity_to_design"] = round(float(np.dot(emb(f"{out}/{n}.wav"), emb(base))), 3)

    rows.sort(key=lambda r: (-r["mos_finished"], r["wer"]))
    json.dump(rows, open(f"{out}/results.json", "w"), indent=1)
    with open(f"{out}/RESULTS.md", "w") as f:
        f.write("| sample | UTMOS raw | mastered | finished | WER | words/min | similarity | sec |\n|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            f.write(f"| {r['name']} | {r['mos_raw']} | {r['mos_mastered']} | {r['mos_finished']} | {r['wer']} | "
                    f"{r['words_per_min']} | {r.get('similarity_to_design', '')} | {r['seconds']} |\n")
    log(open(f"{out}/RESULTS.md").read())


if __name__ == "__main__":
    {"design": design, "chatter": chatter, "kokoro": kokoro, "score": score}[sys.argv[1]](sys.argv[2])
