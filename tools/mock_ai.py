#!/usr/bin/env python3
"""
A stand-in AI service (OpenAI-style chat completions, Atlas's "Other (OpenAI-compatible)" provider) plus a small test
shop website, for the emulator test. Both run on this machine; the phone reaches them at http://127.0.0.1:PORT
through `adb reverse`.

The "model" is scripted: it reads Atlas's request and answers the way a real model would for these scenarios:
  chat  "hello there"                    -> a plain Markdown reply
  chat  "remind me to water the plants"  -> set_reminder, then a reply
  chat  "busy test"                      -> two 429s ("try again in 2s"), then a reply (Atlas waits and retries)
  chat  "send a bot to order ..."        -> deploy_bot, then a reply
  bot   "Order a blue widget ..."        -> plan, open the shop, type a search, click Search, open the product,
                                            choose size Large, click "Place order" (Atlas must ask the user first),
                                            then finish with the confirmation it sees
  bot   "Sign in to the test shop"       -> plan, open the sign-in page, try to type the password (Atlas must refuse
                                            and wait for the user), then finish once the user hands back
Keys: "bad-key-1" is turned down (401) so Atlas has to move to the next key; any other key works.

Every request is saved to OUT (req-NN.json / resp-NN.json); everything the shop receives goes to OUT/site-log.jsonl.

Usage: mock_ai.py PORT OUT
"""
import json, os, re, sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, OUT = int(sys.argv[1]), sys.argv[2]
os.makedirs(OUT, exist_ok=True)
BASE = f"http://127.0.0.1:{PORT}"
count = 0
busy_left = {"n": 2}


def site_log(entry):
    entry["t"] = time.time()
    with open(f"{OUT}/site-log.jsonl", "a") as f:
        f.write(json.dumps(entry) + "\n")


# ------------------------------------------------------------------ the model

def reply(text):
    return {"id": "x", "object": "chat.completion", "model": "mock",
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": text}}]}


def calls(*cs):
    tcs = [{"id": f"call_{int(time.time()*1000)}_{i}", "type": "function",
            "function": {"name": n, "arguments": json.dumps(a)}} for i, (n, a) in enumerate(cs)]
    return {"id": "x", "object": "chat.completion", "model": "mock",
            "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": None, "tool_calls": tcs}}]}


def call(name, args):
    return calls((name, args))


def last_tool(msgs):
    """The name and result of the latest tool answer in this session, or (None, None)."""
    if not msgs or msgs[-1].get("role") != "tool":
        return None, None
    tid = msgs[-1].get("tool_call_id")
    for m in reversed(msgs):
        for tc in m.get("tool_calls") or []:
            if tc.get("id") == tid:
                return tc["function"]["name"], msgs[-1].get("content") or ""
    return "?", msgs[-1].get("content") or ""


def first_user(msgs):
    for m in msgs:
        if m.get("role") == "user":
            c = m.get("content")
            return c if isinstance(c, str) else " ".join(p.get("text", "") for p in c if isinstance(p, dict))
    return ""


def last_user(msgs):
    for m in reversed(msgs):
        if m.get("role") == "user":
            c = m.get("content")
            return c if isinstance(c, str) else " ".join(p.get("text", "") for p in c if isinstance(p, dict))
    return ""


def bot(msgs):
    brief = first_user(msgs)
    name, result = last_tool(msgs)
    order = "blue widget" in brief.lower()
    signin = "sign in to the test shop" in brief.lower()
    if name is None:
        # a fresh start (first part, or after the user approved / answered)
        if "Approved and done" in brief:
            conf = re.search(r"Order confirmed[^\n.]*", brief)
            return call("finish", {"report": "## Ordered\n\nI ordered **one blue widget, size Large** from the test shop. "
                                             f"The shop showed: *{conf.group(0) if conf else 'a confirmation'}*.\n\nSources:\n- {BASE}/shop"})
        if "said NO" in brief:
            return call("finish", {"report": "You said no to placing the order, so nothing was ordered."})
        if "They answered" in brief or "You answered" in brief:
            return call("finish", {"report": "You signed in yourself in my browser, so the sign-in step is done. I never typed your password."})
        if order:
            return call("update_plan", {"plan": "1. Open the test shop\n2. Search for the blue widget\n3. Open it and pick size Large\n4. Place the order (with the user's OK)\n5. Report the confirmation"})
        if signin:
            return call("update_plan", {"plan": "1. Open the sign-in page\n2. Sign in\n3. Report"})
        return call("finish", {"report": "Nothing scripted for this job."})
    if name == "update_plan":
        return call("browser_open", {"url": f"{BASE}/shop" if order else f"{BASE}/login"})
    if order:
        if name == "browser_open":
            return call("browser_type", {"target": "Search the shop", "text": "blue widget"})
        if name == "browser_type":
            return call("browser_click", {"target": "Search"})
        if name == "browser_click" and "Blue Widget" in result and "Place order" not in result:
            return call("browser_click", {"target": "Blue Widget"})
        if name == "browser_click" and "Place order" in result:
            return call("browser_select", {"target": "Size", "option": "Large"})
        if name == "browser_select":
            return call("browser_click", {"target": "Place order"})
        return call("finish", {"report": f"Stopped: unexpected page after {name}:\n\n{result[:800]}"})
    if signin:
        if name == "browser_open":
            return call("browser_type", {"target": "Password", "text": "hunter2-should-never-be-typed"})
        return call("finish", {"report": f"Stopped after {name}: {result[:300]}"})
    return call("finish", {"report": "done"})


def chat(msgs):
    q = last_user(msgs).lower()
    name, result = last_tool(msgs)
    if name == "set_reminder":
        return reply("**Logged it.** I'll remind you to water the plants.")
    if name == "deploy_bot":
        return reply("On it: **a bot is ordering the blue widget**. Its report will land here.")
    if name:
        return reply("Done.")
    if "busy test" in q:
        return "BUSY"
    if "remind me to water the plants" in q:
        return call("set_reminder", {"title": "Water the plants", "when": "2031-01-16 18:00", "duration_min": 10})
    if "send a bot" in q and "blue widget" in q:
        return call("deploy_bot", {"goal": "Order a blue widget (size Large) from the test shop",
                                   "details": f"The shop is at {BASE}/shop. One widget, size Large."})
    if "busy" in q:
        return reply("Through after the wait.")
    return reply("**Hi!** I'm here.\n\n- one\n- two")


def answer(req):
    msgs = req.get("messages", [])
    system = msgs[0].get("content", "") if msgs and msgs[0].get("role") == "system" else ""
    if system.startswith("You are Atlas Bot"):
        return bot(msgs)
    r = chat(msgs)
    if r == "BUSY":
        if busy_left["n"] > 0:
            busy_left["n"] -= 1
            return "BUSY"
        return reply("**Through after the wait.** The service was busy, so I tried again.")
    return r


# ------------------------------------------------------------------ the test shop

PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title>
<style>body{{font-family:sans-serif;margin:16px}} input,select,button{{font-size:18px;margin:6px 0;padding:6px}}</style></head>
<body><h1>{title}</h1>{body}</body></html>"""


def page(title, body):
    return PAGE.format(title=title, body=body).encode()


def shop_pages(path, q):
    if path in ("/shop", "/shop/"):
        return page("Test Shop", '<form action="/shop/search" method="get"><input name="q" aria-label="Search the shop" placeholder="Search the shop">'
                                 '<button type="submit">Search</button></form><p><a href="/files/catalogue.pdf">Catalogue (PDF)</a></p>')
    if path == "/shop/search":
        term = (q.get("q") or [""])[0]
        hit = '<p><a href="/shop/item/blue-widget">Blue Widget</a> &mdash; $12</p>' if "blue" in term.lower() else "<p>No results.</p>"
        return page("Results", f"<p>Results for &ldquo;{term}&rdquo;</p>{hit}<p><a href='/shop'>Back to the shop</a></p>")
    if path == "/shop/item/blue-widget":
        return page("Blue Widget", '<form action="/shop/order" method="post"><input type="hidden" name="item" value="blue-widget">'
                                   '<label>Size <select name="size" aria-label="Size"><option>Small</option><option>Medium</option><option>Large</option></select></label><br>'
                                   '<button type="submit">Place order</button></form>')
    if path == "/login":
        return page("Sign in", '<form action="/login" method="post"><input name="user" aria-label="Email" placeholder="Email"><br>'
                               '<input name="password" type="password" aria-label="Password" placeholder="Password"><br><button type="submit">Sign in</button></form>')
    return None


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send(self, code, data, ctype="text/html; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        site_log({"method": "GET", "path": u.path, "query": q, "ua": self.headers.get("User-Agent", "")[:120]})
        if u.path == "/files/catalogue.pdf":
            return self.send(200, b"%PDF-1.4\n% Atlas test file\n" + b"0" * 2048 + b"\n%%EOF\n", "application/pdf",
                             {"Content-Disposition": 'attachment; filename="catalogue.pdf"'})
        body = shop_pages(u.path, q)
        if body is None:
            return self.send(404, page("Not found", "<p>Nothing here.</p>"))
        return self.send(200, body)

    def do_POST(self):
        global count
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        u = urllib.parse.urlparse(self.path)
        if u.path.endswith("/chat/completions"):
            count += 1
            key = (self.headers.get("Authorization") or "").replace("Bearer ", "")
            try:
                req = json.loads(raw)
            except Exception:
                req = {"unparsed": raw.decode("utf-8", "replace")}
            meta = {"path": self.path, "key": key, "t": time.time()}
            if key == "bad-key-1":
                resp, code = {"error": {"message": "Incorrect API key provided.", "type": "invalid_request_error"}}, 401
            else:
                try:
                    resp, code = answer(req), 200
                except Exception as e:
                    resp, code = reply(f"mock error: {e}"), 200
                if resp == "BUSY":
                    resp, code = {"error": {"message": "Rate limit reached. Please try again in 2s.", "type": "rate_limit"}}, 429
            meta["status"] = code
            json.dump({"meta": meta, "request": req}, open(f"{OUT}/req-{count:02d}.json", "w"), indent=1)
            json.dump(resp, open(f"{OUT}/resp-{count:02d}.json", "w"), indent=1)
            return self.send(code, json.dumps(resp).encode(), "application/json")
        form = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
        site_log({"method": "POST", "path": u.path, "form": form})
        if u.path == "/shop/order":
            size = (form.get("size") or ["?"])[0]
            return self.send(200, page("Order confirmed", f"<p>Order confirmed: #A1234, one blue widget, size {size}.</p>"))
        if u.path == "/login":
            return self.send(200, page("Signed in", "<p>Welcome back.</p>"))
        return self.send(404, page("Not found", ""))

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
