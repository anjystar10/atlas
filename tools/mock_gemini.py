#!/usr/bin/env python3
"""
A stand-in for Gemini's generateContent, for the emulator test. It plays a scripted assistant so the whole loop
runs on a real Android: Atlas sends its request, the "model" asks for tools, Atlas runs them on the phone and
sends the results back. Every request is saved so the format can be checked afterwards.

Usage: mock_gemini.py PORT OUTDIR
"""
import json, re, sys, os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, OUT = int(sys.argv[1]), sys.argv[2]
os.makedirs(OUT, exist_ok=True)
count = 0
checks = []

def call(name, args, sig=None):
    part = {"functionCall": {"name": name, "args": args}}
    if sig: part["thoughtSignature"] = sig
    return {"candidates": [{"content": {"role": "model", "parts": [part]}, "finishReason": "STOP"}]}

def text(t):
    return {"candidates": [{"content": {"role": "model", "parts": [{"text": t}]}, "finishReason": "STOP"}]}

def last_user_text(contents):
    for c in reversed(contents):
        if c.get("role") == "user":
            for p in c.get("parts", []):
                if "text" in p: return p["text"]
    return ""

def answer(req):
    contents = req.get("contents", [])
    last = contents[-1]
    fr = [p["functionResponse"] for p in last.get("parts", []) if "functionResponse" in p]
    q = last_user_text(contents).lower()
    if not fr:
        if "settings" in q:
            return call("open_app", {"name": "Settings"}, sig="SIG-open")
        if "remind" in q:
            return call("set_reminder", {"title": "Pick up my sister", "when": "2031-01-16 16:00", "duration_min": 30}, sig="SIG-rem")
        return text("Hey! I'm here.")
    name, result = fr[-1]["name"], fr[-1]["response"]["result"]
    # the model's turn before the results must come back with its signature
    prev = contents[-2]
    sigs = [p.get("thoughtSignature") for p in prev.get("parts", []) if "functionCall" in p]
    checks.append({"after": name, "signature_returned": sigs})
    if name == "open_app":
        m = re.search(r'^\[(\d+)\][^\n]*"(Network[^"]*|Connected devices[^"]*|Apps[^"]*)"', result, re.M)
        if not m:
            return text("I opened Settings but couldn't find Network & internet. Screen was:\n" + result[:600])
        return call("tap", {"element": int(m.group(1))}, sig="SIG-tap")
    if name == "tap":
        return call("press_key", {"key": "back"})
    if name == "press_key":
        return text("Done. I opened Settings, tapped into it and came back.")
    if name == "set_reminder":
        return text("Logged it: " + result)
    return text("Finished.")

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        global count
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        count += 1
        try:
            req = json.loads(body)
        except Exception:
            req = {"unparsed": body.decode("utf-8", "replace")}
        meta = {"path": self.path, "key_header": self.headers.get("x-goog-api-key"), "content_type": self.headers.get("Content-Type")}
        json.dump({"meta": meta, "request": req}, open(f"{OUT}/req-{count:02d}.json", "w"), indent=1)
        try:
            resp = answer(req)
        except Exception as e:
            resp = text(f"mock error: {e}")
        json.dump(resp, open(f"{OUT}/resp-{count:02d}.json", "w"), indent=1)
        json.dump(checks, open(f"{OUT}/checks.json", "w"), indent=1)
        data = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *a): pass

HTTPServer(("127.0.0.1", PORT), H).serve_forever()
