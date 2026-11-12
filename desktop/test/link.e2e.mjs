// Device Link end to end: the real Windows app (Electron, under xvfb) and a simulated phone talking through a local
// stand-in for the cloud relay. Run:  xvfb-run -a node test/link.e2e.mjs
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { createRelay } from './fixtures/relay.js';
import { createLink } from '../renderer/linkcore.js';
const require = createRequire(import.meta.url);

const results = [];
const ok = (name, cond, more = '') => { results.push(!!cond); console.log((cond ? 'ok   ' : 'FAIL ') + name + (more ? '  (' + more + ')' : '')); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// the relay, over HTTP like Supabase's rpc endpoint
const relay = createRelay();
const srv = http.createServer((req, res) => {
  let body = ''; req.on('data', (d) => (body += d)); req.on('end', async () => {
    const fn = req.url.replace(/^\/rpc\//, '');
    try {
      const b = JSON.parse(body || '{}');
      const out = fn === 'sync_claim' ? { holder: b.p_device, mine: true, until_ms: Date.now() + b.p_lease_ms } : await relay.rpc(fn, b);
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(out));
    } catch (e) { res.writeHead(400); res.end(String(e.message)); }
  });
});
await new Promise((r) => srv.listen(0, '127.0.0.1', r));
const RELAY = 'http://127.0.0.1:' + srv.address().port;

// the crypto, as on both devices
const CODE = 'ATLS-ABCD-EFGH-JKMN-PQRS-TVWX';
const enc = new TextEncoder(), dec = new TextDecoder();
const sha = async (s) => new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const hex = (b) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
const key = await crypto.subtle.importKey('raw', await sha('atlas-key:' + CODE), 'AES-GCM', false, ['encrypt', 'decrypt']);
const owner = hex(await sha('atlas-owner:' + CODE));
const encrypt = async (t) => { const iv = crypto.getRandomValues(new Uint8Array(12)); const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(t))); const a = new Uint8Array(12 + ct.length); a.set(iv); a.set(ct, 12); return Buffer.from(a).toString('base64'); };
const decrypt = async (p) => { const a = Buffer.from(p, 'base64'); return dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: a.subarray(0, 12) }, key, a.subarray(12))); };

// the PC: the real app, linked with the code, the phone already allowed to control it
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlaslink-'));
const data = path.join(tmp, 'data'); fs.mkdirSync(data, { recursive: true });
const PHONE = 'phone-sim-1';
fs.writeFileSync(path.join(data, 'prefs.json'), JSON.stringify({ syncCode: CODE, linkAllowControl: [PHONE], deviceName: 'MSI test PC' }));
const shot = path.join(tmp, 'shot.png');
const electron = require('electron');
const toCard = "document.querySelector('.nav[data-tab=me]').click(); setTimeout(() => { const c = [...document.querySelectorAll('.card')].find((x) => x.textContent.includes('Control Across Devices')); if (c) c.scrollIntoView(); }, 300)";
const steps = [toCard, toCard, toCard, toCard, toCard, toCard,
  "[...document.querySelectorAll('button')].find((b) => b.textContent === 'Control Phone').click()",
  "(() => { const i = document.querySelector('.remote-screen'); const b = i.getBoundingClientRect(); const o = { clientX: b.left + b.width * 0.25, clientY: b.top + b.height * 0.5, pointerId: 1, bubbles: true }; i.dispatchEvent(new PointerEvent('pointerdown', o)); i.dispatchEvent(new PointerEvent('pointerup', o)); document.querySelector('[data-a=home]').click(); })()"];
const app = spawn(electron, ['--no-sandbox', path.join(import.meta.dirname, '..')], { env: { ...process.env, ATLAS_DATA: data, ATLAS_LINK_RELAY: RELAY, ATLAS_SHOT: shot, ATLAS_WAIT: '7000', ATLAS_STEPS: JSON.stringify(steps) }, stdio: ['ignore', 'pipe', 'pipe'] });
let appOut = ''; app.stdout.on('data', (d) => (appOut += d)); app.stderr.on('data', (d) => (appOut += d));

// the phone
const got = [];
const phoneJpg = fs.readFileSync(new URL('./fixtures/phone-screen.jpg', import.meta.url)).toString('base64');
let streaming = null; const phoneInputs = [];
const phone = createLink({ rpc: relay.rpc, encrypt, decrypt, owner, me: PHONE, info: () => ({ name: "Test phone", platform: 'android', caps: ['tasks', 'screen'] }), idleMs: 200,
  onMessage: async (m) => {
    got.push(m);
    // the phone side of "Control Phone" on the PC: say yes, then send its (fixed) screen until told to stop
    if (m.k === 'screen_start') {
      await phone.send(m.from, 'screen_reply', { sid: m.sid, ok: true });
      streaming = m.sid; let seq = 0;
      (async () => { while (streaming === m.sid) { await phone.putFrame(++seq, { sid: m.sid, w: 360, h: 780, realW: 1080, realH: 2340, jpg: phoneJpg }); await sleep(700); } })();
    }
    if (m.k === 'screen_stop' && m.sid === streaming) streaming = null;
    if (m.k === 'input') phoneInputs.push(m.input);
  } });
phone.setActive(true); phone.start();
const waitMsg = async (pred, ms) => { const t = Date.now(); while (Date.now() - t < ms) { const m = got.find(pred); if (m) return m; await sleep(100); } return null; };

let pc = null;
for (let i = 0; i < 60 && !pc; i++) { await sleep(500); try { pc = (await phone.beat()).find((p) => p.platform === 'windows'); } catch {} }
ok('the PC app joins the link and reports itself online', pc && pc.online && pc.name === 'MSI test PC', pc ? pc.name : 'not seen');
if (pc) {
  const rtts = [];
  for (let i = 0; i < 5; i++) { const r = await phone.ping(pc.device, 6000); if (r != null) rtts.push(r); }
  ok('ping through the running PC app answers', rtts.length >= 4, 'round trips ms: ' + rtts.join(', '));

  // remote screen: start → reply ok → frames → input → stop
  const sid = 'sess-' + Date.now();
  await phone.send(pc.device, 'screen_start', { sid, maxW: 640, q: 50 });
  const reply = await waitMsg((m) => m.k === 'screen_reply' && m.sid === sid, 15000);
  ok('the PC accepts the phone it was told to allow', reply && reply.ok === true, reply ? JSON.stringify(reply.why || '') : 'no reply');
  let frame = null; const t0 = Date.now();
  for (let i = 0; i < 10 && !frame; i++) { try { frame = await phone.getFrame(pc.device, -1, 2000); } catch {} }
  ok('a frame of the PC screen arrives (encrypted JPEG)', frame && frame.frame.sid === sid && frame.frame.jpg.startsWith('/9j/') && frame.frame.w <= 640, frame ? `${frame.frame.w}x${frame.frame.h} of ${frame.frame.realW}x${frame.frame.realH}, ${Math.round(frame.frame.jpg.length / 1024)} KB, after ${Date.now() - t0} ms` : 'none');
  let frames = 0; const tf = Date.now(); let seq = frame ? frame.seq : -1;
  while (Date.now() - tf < 4000) { await phone.send(pc.device, 'screen_keep', { sid }); const f = await phone.getFrame(pc.device, seq, 1500).catch(() => null); if (f) { seq = f.seq; frames++; } }
  ok('frames keep coming while the screen is shared', frames >= 1, frames + ' new frames in 4 s (a still screen sends one every 3 s)');
  await phone.send(pc.device, 'input', { sid, input: { t: 'tap', x: 0.5, y: 0.5 } });
  const ie = await waitMsg((m) => m.k === 'input_error' && m.sid === sid, 6000);
  ok('a tap reaches the PC input (on Linux it answers that input only works on Windows)', ie && /Windows/.test(ie.error), ie ? ie.error : 'no answer');
  await phone.send(pc.device, 'screen_stop', { sid });
  await sleep(1500);

  // a task: the PC runs it as an Atlas Bot job and reports back (no AI service here, so it reports it can't)
  const tid = 'task-' + Date.now();
  await phone.send(pc.device, 'task', { tid, text: 'Open Notepad and type hello', fromName: 'Test phone' });
  const ack = await waitMsg((m) => m.k === 'task_ack' && m.tid === tid, 10000);
  ok('the PC takes the task as an Atlas Bot job', ack && ack.jobId, ack ? ack.title : 'no ack');
  const done = await waitMsg((m) => m.k === 'task_done' && m.tid === tid, 25000);
  ok('the PC reports back on the task', !!done, done ? `${done.state}: ${String(done.report).slice(0, 90)}` : 'nothing within 25 s');
  // sending it twice doesn't run it twice
  await phone.send(pc.device, 'task', { tid, text: 'Open Notepad and type hello', fromName: 'Test phone' });
  await sleep(2500);
  ok('the same task sent again is not run again', got.filter((m) => m.k === 'task_ack' && m.tid === tid).length === 1);
  // a stranger with another code can't reach it
  const k2 = await crypto.subtle.importKey('raw', await sha('atlas-key:ATLS-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ'), 'AES-GCM', false, ['encrypt']);
  const strangerEnc = async (t) => { const iv = crypto.getRandomValues(new Uint8Array(12)); const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k2, enc.encode(t))); const a = new Uint8Array(12 + ct.length); a.set(iv); a.set(ct, 12); return Buffer.from(a).toString('base64'); };
  const stranger = createLink({ rpc: relay.rpc, encrypt: strangerEnc, decrypt, owner, me: 'stranger', info: () => ({}) });
  const sid2 = 'evil';
  await stranger.send(pc.device, 'screen_start', { sid: sid2 });
  const r2 = await waitMsg((m) => m.k === 'screen_reply' && m.sid === sid2, 4000);
  ok('a device without the code gets nothing back', !r2);
}
await new Promise((r) => app.on('exit', r));
ok('Control Phone on the PC: the phone was asked, its screen shown, and a click there arrived as a tap', phoneInputs.some((i) => i.t === 'tap' && Math.abs(i.x - 0.25) < 0.02 && Math.abs(i.y - 0.5) < 0.02) && phoneInputs.some((i) => i.t === 'act' && i.act === 'home'), JSON.stringify(phoneInputs));
await phone.stop();
const shots = fs.readdirSync(tmp).filter((f) => f.startsWith('shot-')).sort();
const keep = '/tmp/claude-0/-home-claude/2c7c13ae-4c7b-5ab4-8a4d-a0af179f1494/scratchpad/link';
fs.mkdirSync(keep, { recursive: true });
for (const f of shots) fs.copyFileSync(path.join(tmp, f), path.join(keep, f));
const errs = (fs.existsSync(path.join(tmp, 'page.txt')) ? fs.readFileSync(path.join(tmp, 'page.txt'), 'utf8') : '').split('\n').filter((l) => l.trim() && !/WebGL|GroupMarker|swiftshader/i.test(l)).join('\n');
ok('no page errors in the app', !errs, errs.slice(0, 400));
srv.close();
console.log(results.filter(Boolean).length + '/' + results.length + ' passed');
process.exit(results.every(Boolean) ? 0 : 1);
