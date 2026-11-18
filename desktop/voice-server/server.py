"""Atlas Voice: Atlas's own designed voice, spoken on this laptop's graphics card.

Started by Atlas (desktop/atlasvoice.js) inside its own Python environment. It loads Qwen3-TTS (Apache-2.0) once and
speaks every reply by cloning ONE stored reference clip: the original voice designed for Atlas from a list of traits
(see tools/voicelab). It is never given a recording of a real person.

Listens on 127.0.0.1 only and answers only requests carrying the token Atlas passed at start.

  POST /speak   {"text": "...", "language": "English"}  ->  audio/wav (16-bit mono)
  GET  /health                                          ->  {"ready": true, "model": ..., "device": ..., "rate": ...}
"""
import argparse, io, json, sys, threading, time, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

state = {"ready": False, "error": None, "model": None, "device": None, "rate": 24000}
lock = threading.Lock()   # one generation at a time: the card has one model in memory


def load(args):
    try:
        import torch
        from qwen_tts import Qwen3TTSModel
        cuda = torch.cuda.is_available()
        device = "cuda:0" if cuda else "cpu"
        kw = {"device_map": device, "dtype": torch.bfloat16 if cuda else torch.float32}
        try:
            import flash_attn  # noqa: F401  (optional: less memory, faster)
            kw["attn_implementation"] = "flash_attention_2"
        except Exception:
            pass
        m = Qwen3TTSModel.from_pretrained(args.model, **kw)
        ref_text = open(args.ref_text, encoding="utf-8").read().strip()
        prompt = m.create_voice_clone_prompt(ref_audio=args.ref, ref_text=ref_text)
        # warm up so the first real reply is quick
        m.generate_voice_clone(text="Ready.", language="English", voice_clone_prompt=prompt)
        state.update(ready=True, model=args.model, device=device, _m=m, _prompt=prompt)
        print("ready", args.model, device, flush=True)
    except Exception as e:  # reported through /health; Atlas falls back to its other voices
        state["error"] = f"{type(e).__name__}: {e}"
        print("load failed:", state["error"], flush=True)


def wav_bytes(samples, rate):
    pcm = (np.clip(np.asarray(samples, dtype=np.float32), -1, 1) * 32767).astype("<i2").tobytes()
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm)
    return out.getvalue()


class Handler(BaseHTTPRequestHandler):
    token = ""

    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorised(self):
        if self.headers.get("X-Atlas-Token", "") != self.token:
            self.send(403, b'{"error":"not allowed"}')
            return False
        return True

    def do_GET(self):
        if not self.authorised():
            return
        if self.path == "/health":
            pub = {k: v for k, v in state.items() if not k.startswith("_")}
            return self.send(200, json.dumps(pub).encode())
        self.send(404, b"{}")

    def do_POST(self):
        if not self.authorised():
            return
        if self.path != "/speak":
            return self.send(404, b"{}")
        if not state["ready"]:
            return self.send(503, json.dumps({"error": state["error"] or "still loading"}).encode())
        try:
            n = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(min(n, 100_000)) or b"{}")
            text = str(req.get("text", "")).strip()[:2000]
            if not text:
                return self.send(400, b'{"error":"no text"}')
            t = time.time()
            with lock:
                wavs, sr = state["_m"].generate_voice_clone(text=text, language=req.get("language") or "English",
                                                            voice_clone_prompt=state["_prompt"])
            state["rate"] = sr
            body = wav_bytes(wavs[0], sr)
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Atlas-Ms", str(int((time.time() - t) * 1000)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send(500, json.dumps({"error": f"{type(e).__name__}: {e}"}).encode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--ref-text", required=True)
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--token", required=True)
    args = ap.parse_args()
    Handler.token = args.token
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)   # this laptop only
    threading.Thread(target=load, args=(args,), daemon=True).start()
    print("listening", args.port, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
