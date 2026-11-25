#!/usr/bin/env python3
"""
A stand-in for the AI services (and anything else) Atlas calls, for the deep test rig (.github/workflows/deep.yml).
It answers in the shape of whichever service the path belongs to, and how it answers is set from outside:

    GET /__mode?m=<mode>     every later AI request gets <mode> (see MODES), until changed
    GET /__mode?m=<mode>&once=1   only the next AI request gets <mode>, then back to "ok"
    GET /__stats              how many requests came in, by path

Modes (the error bodies are the services' documented shapes, so Atlas's real error handling runs):
    ok, md (markdown, unicode, a table, a long line), long (about 12 000 characters), 429, 500, 503, 400key (Gemini's
    "API key not valid"), 403, html502 (a proxy's HTML error page), malformed (cut-off JSON), empty (no candidates:
    blocked), maxtokens (finishReason MAX_TOKENS with no text), slow (answers after 15 s), hang (answers after 100 s),
    tool:<name>:<json args> (asks Atlas to run one tool, then repeats the tool's result back as text)

Usage: stub.py PORT OUTDIR      (every request and answer is written to OUTDIR, API keys masked)
"""
import json, os, sys, threading, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, OUT = int(sys.argv[1]), sys.argv[2]
os.makedirs(OUT, exist_ok=True)
state = {"mode": "ok", "once": False, "n": 0, "paths": {}}
lock = threading.Lock()

MD = ("**Bold**, _italic_, `code` and a [link](https://example.com).\n\n"
      "| Day | Plan |\n|---|---|\n| Mon | Gym 🏋️ |\n| Tue | Read 📚 |\n\n"
      "- Ünïcødé: Ἀθηνᾶ, 漢字, العربية (right to left), עברית, हिन्दी, 👍🏽👨‍👩‍👧‍👦\n"
      "- A long unbroken word: " + "Supercalifragilistic" * 12 + "\n\n"
      "```kotlin\nfun main() = println(\"<script>alert(1)</script>\")\n```\n"
      "<script>window.__xss=(window.__xss||0)+1</script><img src=x onerror=\"window.__xss=(window.__xss||0)+1\">")
LONG = ("This is a very long answer to check scrolling, wrapping and memory. " * 180).strip()

GEMINI_ERR = {
    "429": (429, {"error": {"code": 429, "message": "You exceeded your current quota, please check your plan and billing details.", "status": "RESOURCE_EXHAUSTED",
                            "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37s"}]}}),
    "500": (500, {"error": {"code": 500, "message": "An internal error has occurred. Please retry or report in https://developers.generativeai.google/guide/troubleshooting", "status": "INTERNAL"}}),
    "503": (503, {"error": {"code": 503, "message": "The model is overloaded. Please try again later.", "status": "UNAVAILABLE"}}),
    "400key": (400, {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT",
                               "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID", "domain": "googleapis.com"}]}}),
    "403": (403, {"error": {"code": 403, "message": "Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API consumer identity to call this API.", "status": "PERMISSION_DENIED"}}),
}
OPENAI_ERR = {
    "429": (429, {"error": {"message": "Rate limit reached for requests", "type": "requests", "param": None, "code": "rate_limit_exceeded"}}),
    "500": (500, {"error": {"message": "The server had an error while processing your request. Sorry about that!", "type": "server_error", "param": None, "code": None}}),
    "503": (503, {"error": {"message": "The engine is currently overloaded, please try again later", "type": "server_error", "param": None, "code": None}}),
    "400key": (401, {"error": {"message": "Incorrect API key provided: sk-deep****. You can find your API key at https://platform.openai.com/account/api-keys.", "type": "invalid_request_error", "param": None, "code": "invalid_api_key"}}),
    "403": (403, {"error": {"message": "Country, region, or territory not supported", "type": "request_forbidden", "param": None, "code": "unsupported_country_region_territory"}}),
}
ANTHROPIC_ERR = {
    "429": (429, {"type": "error", "error": {"type": "rate_limit_error", "message": "Number of request tokens has exceeded your per-minute rate limit"}}),
    "500": (500, {"type": "error", "error": {"type": "api_error", "message": "Internal server error"}}),
    "503": (529, {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}),
    "400key": (401, {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}),
    "403": (403, {"type": "error", "error": {"type": "permission_error", "message": "Your API key does not have permission to use the specified resource."}}),
}


def style_of(path):
    if ":generateContent" in path or ":streamGenerateContent" in path or "/v1beta/" in path: return "gemini"
    if path.rstrip("/").endswith("/chat/completions"): return "openai"
    if path.rstrip("/").endswith("/messages"): return "anthropic"
    return None


def text_answer(style, t, finish="STOP"):
    if style == "gemini":
        return {"candidates": [{"content": {"role": "model", "parts": [{"text": t}]}, "finishReason": finish, "index": 0}],
                "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 10, "totalTokenCount": 20}, "modelVersion": "stub"}
    if style == "openai":
        return {"id": "chatcmpl-deep", "object": "chat.completion", "created": int(time.time()), "model": "stub",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": t}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
    return {"id": "msg_deep", "type": "message", "role": "assistant", "model": "stub", "content": [{"type": "text", "text": t}],
            "stop_reason": "end_turn", "stop_sequence": None, "usage": {"input_tokens": 10, "output_tokens": 10}}


def tool_answer(style, name, args):
    if style == "gemini":
        return {"candidates": [{"content": {"role": "model", "parts": [{"functionCall": {"name": name, "args": args}, "thoughtSignature": "deep-sig"}]}, "finishReason": "STOP", "index": 0}]}
    if style == "openai":
        return {"id": "chatcmpl-deep", "object": "chat.completion", "created": int(time.time()), "model": "stub",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": None, "tool_calls": [{"id": "call_deep", "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}, "finish_reason": "tool_calls"}]}
    return {"id": "msg_deep", "type": "message", "role": "assistant", "model": "stub", "content": [{"type": "tool_use", "id": "toolu_deep", "name": name, "input": args}],
            "stop_reason": "tool_use", "stop_sequence": None, "usage": {"input_tokens": 10, "output_tokens": 10}}


def tool_result_in(style, req):
    """The tool result Atlas sent back, if this request carries one (as text), else None."""
    try:
        if style == "gemini":
            last = (req.get("contents") or [{}])[-1]
            for p in last.get("parts", []):
                if "functionResponse" in p: return json.dumps(p["functionResponse"].get("response"), ensure_ascii=False)
        elif style == "openai":
            last = (req.get("messages") or [{}])[-1]
            if last.get("role") == "tool": return str(last.get("content"))
        else:
            last = (req.get("messages") or [{}])[-1]
            for c in last.get("content", []) if isinstance(last.get("content"), list) else []:
                if c.get("type") == "tool_result": return json.dumps(c.get("content"), ensure_ascii=False)
    except Exception:
        return None
    return None


def masked(headers):
    out = {}
    for k, v in headers.items():
        lk = k.lower()
        out[k] = (v[:6] + "…") if lk in ("x-goog-api-key", "authorization", "x-api-key", "apikey") else v
    return out


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send(self, code, body, ctype="application/json", raw=None):
        data = raw if raw is not None else (json.dumps(body, ensure_ascii=False).encode() if ctype == "application/json" else str(body).encode())
        try:
            self.send_response(code)
            self.send_header("Content-Type", ctype + ("; charset=UTF-8" if "json" in ctype or "html" in ctype else ""))
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/__mode":
            q = urllib.parse.parse_qs(u.query)
            with lock:
                state["mode"] = q.get("m", ["ok"])[0]
                state["once"] = q.get("once", ["0"])[0] == "1"
            return self.send(200, {"mode": state["mode"], "once": state["once"]})
        if u.path == "/__stats":
            return self.send(200, {"n": state["n"], "paths": state["paths"], "mode": state["mode"]})
        self.handle_any(b"")

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.handle_any(self.rfile.read(n) if n else b"")

    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_GET

    def handle_any(self, body):
        u = urllib.parse.urlparse(self.path)
        host = self.headers.get("X-Deep-Host") or self.headers.get("Host") or ""
        style = style_of(u.path)
        with lock:
            state["n"] += 1; k = state["n"]
            key = (host + " " if host and not host.startswith("127.0.0.1") else "") + u.path
            state["paths"][key] = state["paths"].get(key, 0) + 1
            mode = state["mode"] if style else "ok"
            if style and state["once"]:
                state["mode"], state["once"] = "ok", False
        try:
            req = json.loads(body) if body else {}
        except Exception:
            req = {"unparsed": body[:4000].decode("utf-8", "replace")}
        rec = {"n": k, "method": self.command, "host": host, "path": u.path, "style": style, "mode": mode, "headers": masked(self.headers)}
        code, ctype, resp, raw = 200, "application/json", None, None
        if style is None:
            # anything that isn't an AI service (sync, updates, web pages, other APIs): not found, as a real server says it
            code, resp = 404, {"error": "not found (deep test stand-in)"}
        else:
            back = tool_result_in(style, req)
            if back is not None:
                resp = text_answer(style, "TOOL RESULT: " + back[:600])
            elif mode == "ok": resp = text_answer(style, "Stub reply: all good. Привет 👋")
            elif mode == "md": resp = text_answer(style, MD)
            elif mode == "long": resp = text_answer(style, LONG)
            elif mode in ("429", "500", "503", "400key", "403"):
                code, resp = {"gemini": GEMINI_ERR, "openai": OPENAI_ERR, "anthropic": ANTHROPIC_ERR}[style][mode]
            elif mode == "html502":
                code, ctype, raw = 502, "text/html", b"<html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center><hr><center>nginx</center></body></html>"
            elif mode == "malformed":
                raw = b'{"candidates": [{"content": {"role": "model", "parts": [{"text": "cut of'
            elif mode == "empty":
                resp = {"promptFeedback": {"blockReason": "SAFETY", "safetyRatings": []}} if style == "gemini" else (
                    {"id": "x", "object": "chat.completion", "choices": []} if style == "openai" else {"type": "message", "role": "assistant", "content": [], "stop_reason": "refusal"})
            elif mode == "maxtokens":
                resp = ({"candidates": [{"content": {"role": "model"}, "finishReason": "MAX_TOKENS", "index": 0}]} if style == "gemini" else
                        {"id": "x", "object": "chat.completion", "choices": [{"index": 0, "message": {"role": "assistant", "content": ""}, "finish_reason": "length"}]} if style == "openai" else
                        {"type": "message", "role": "assistant", "content": [], "stop_reason": "max_tokens"})
            elif mode == "slow":
                time.sleep(15); resp = text_answer(style, "Stub reply after 15 seconds.")
            elif mode == "hang":
                time.sleep(100); resp = text_answer(style, "Stub reply after 100 seconds.")
            elif mode.startswith("tool:"):
                _, name, args = (mode.split(":", 2) + ["{}"])[:3]
                try: a = json.loads(args)
                except Exception: a = {}
                resp = tool_answer(style, name, a)
            else:
                resp = text_answer(style, "Stub: unknown mode " + mode)
        rec["status"] = code
        rec["request"] = req if len(body) < 200_000 else {"big": len(body)}
        rec["response"] = resp if resp is not None else (raw or b"").decode("utf-8", "replace")
        try:
            with open(os.path.join(OUT, f"req-{k:04d}.json"), "w") as f: json.dump(rec, f, ensure_ascii=False, indent=1)
        except Exception:
            pass
        self.send(code, resp, ctype, raw)

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
