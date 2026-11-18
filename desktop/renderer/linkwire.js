// Device Link in the Windows app: the live line to the phone (linkcore.js) plugged into Atlas.
//
// - Tasks both ways: "on my phone, …" goes to the phone as a bot job there; a task from the phone runs here as an
//   Atlas Bot job with this PC's tools. Progress, approvals and the report travel back; the report lands in the chat
//   of the device that asked (and syncs to the other as usual).
// - Remote screen both ways: the phone can watch and drive this PC (after you allow it here, with a banner and Stop
//   while it happens); this PC can watch and drive the phone (the phone asks its owner the same way).
import { createLink, inputPlan, sessionAlive, toFraction, uid, ONLINE_MS } from './linkcore.js';
import * as sync from './sync.js';
import { db, pref, setPref, saveChat, changed } from './store.js';
import { bots } from './botwire.js';
import { S as BOT, FINAL as BOT_FINAL } from './bots.js';
import { plainReply } from './plain.js';
import * as aware from './aware.js';
import { local as voiceLocal } from './voice.js';   // >>> atlas voice
const host = window.atlasHost;
const URL_BASE = 'https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc';
const KEY = 'sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj';

let link = null, linkCode = null, ui = null;
const listeners = new Set();
export const onChange = (f) => { listeners.add(f); return () => listeners.delete(f); };
const fire = () => { for (const f of [...listeners]) { try { f(); } catch {} } };

async function rpc(fn, body) {
  const r = await host.fetch(`${URL_BASE}/${fn}`, { method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 9000 });
  if (!r.ok) throw new Error(r.status === 0 ? 'offline' : `the cloud answered ${r.status}`);
  return r.text ? JSON.parse(r.text) : null;
}

// ------------------------------------------------------------------ the line

/** Starts the link (and restarts it if the link code changes). [kit]: the app's sheet/toast helpers for prompts. */
export function start(kit) {
  ui = kit;
  host.on('remote-stop', () => stopControlled('Stopped here.'));
  const check = async () => {
    const c = sync.code();
    if (c === linkCode) return;
    if (link) { await link.stop(); link = null; }
    linkCode = c;
    if (!c) { fire(); return; }
    link = createLink({ rpc, owner: await sync.owner(c), me: db.state.deviceId,
      encrypt: (t) => sync.encrypt(c, t), decrypt: (t) => sync.decrypt(c, t),
      info: () => ({ name: pref('deviceName', '') || 'Laptop', platform: 'windows', caps: ['tasks', 'screen', 'control', ...(voiceLocal.ready ? ['voice'] : [])], reachable: true }),
      onMessage, onPeers: () => { sendQueued(); fire(); },
      alwaysLongPoll: true,   // a PC is plugged in: it hears the phone within a moment
      log: (...a) => console.warn('[link]', ...a) });
    link.start(); fire();
  };
  check(); setInterval(check, 5000);
  // >>> sync: after this PC sends changes, tell the phone to fetch them now (at most every 3 s)
  let lastPing = 0;
  sync.onPushed(() => {
    if (!link || Date.now() - lastPing < 3000) return; lastPing = Date.now();
    for (const p of peers()) link.send(p.device, 'sync', {}).catch(() => {});
  });
  // <<< sync
}

export const linked = () => !!link;
export const peers = () => (link ? link.peers : []);
export const phone = () => peers().find((p) => p.platform === 'android') || null;
export const stats = () => (link ? link.stats : null);

async function onMessage(m) {
  switch (m.k) {
    case 'task': return taskIn(m);
    case 'task_decide': return decideIn(m);
    case 'task_ack': case 'task_update': case 'task_approval': case 'task_done': return taskOutUpdate(m);
    case 'screen_start': return screenStart(m);
    case 'screen_keep': { const s = controlled.get(m.sid); if (s) s.lastKeep = Date.now(); return; }
    case 'screen_stop': { if (controlled.has(m.sid)) endControlled(m.sid, 'Your phone stopped watching.'); if (viewer && viewer.sid === m.sid) viewer.end('The phone stopped sharing.'); return; }
    case 'screen_reply': return;   // picked up by waitFor
    case 'input': return inputIn(m);
    case 'voice_req': return voiceReq(m);   // >>> atlas voice: the phone wants a reply spoken in Atlas Voice
    // >>> sync: the phone just sent changes to the cloud: fetch them now instead of at the next look
    case 'sync': return sync.syncSoon(300);
    // <<< sync
  }
}

// >>> atlas voice: speak a piece of a reply for the phone in Atlas's designed voice (this laptop's graphics card does
// the work); the phone plays it, with its own finish. Raw 16-bit PCM, base64, well under the relay's 1.5 MB.
export function pcmBase64(bytes) {
  let s = ''; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
async function voiceReq(m) {
  if (!m.tid) return;
  try {
    if (!voiceLocal.ready || !window.atlasHost.atlasVoice) throw new Error('Atlas Voice is not running on the laptop');
    const r = await window.atlasHost.atlasVoice.speak(String(m.text || '').slice(0, 1200), String(m.language || 'English'));
    const pcm = pcmBase64(r.pcm instanceof Uint8Array ? r.pcm : new Uint8Array(r.pcm));
    if (pcm.length > 1_300_000) throw new Error('that piece is too long to send');
    await link.send(m.from, 'voice_reply', { tid: m.tid, rate: r.rate, pcm });
  } catch (e) { await link.send(m.from, 'voice_reply', { tid: m.tid, error: e.message || String(e) }).catch(() => {}); }
}
// <<< atlas voice

// ------------------------------------------------------------------ tasks from the phone (run here)

const doneTids = () => pref('linkTidsDone', []);
function rememberTid(tid) { const a = doneTids().filter((x) => x !== tid); a.push(tid); setPref('linkTidsDone', a.slice(-200)); }
const remoteJobs = () => pref('linkRemoteJobs', {});   // jobId → { tid, from, last }

async function taskIn(m) {
  if (!m.tid || doneTids().includes(m.tid)) return;
  rememberTid(m.tid);
  // one device runs it, even if the message reached two PCs
  const c = await sync.claim('linktask:' + m.tid, 3600_000);
  if (c && !c.mine) return;
  const text = String(m.text || '').slice(0, 4000);
  const j = bots.deploy(text.split('\n')[0].slice(0, 300), `Sent from ${m.fromName || 'the phone'} through the Device Link: the user is on their phone, not at this PC. Do the whole job here with this PC's tools (apps, files, commands, the screen, your browser). Then report exactly what you did, with evidence (file paths, what the screen showed, confirmation text).\n\nThe request: ${text}`);
  if (!j) { await link.send(m.from, 'task_done', { tid: m.tid, ok: false, report: "Atlas Bot isn't available on the laptop right now." }); return; }
  j.posted = true;   // the phone posts the report in its chat (it syncs back here), so it isn't posted twice
  const rj = remoteJobs(); rj[j.id] = { tid: m.tid, from: m.from, last: '' }; setPref('linkRemoteJobs', rj);
  await link.send(m.from, 'task_ack', { tid: m.tid, jobId: j.id, title: j.goal });
  watchJobs();
}
function decideIn(m) {
  const rj = remoteJobs();
  const id = Object.keys(rj).find((k) => rj[k].tid === m.tid); if (!id) return;
  const j = bots.job(id);
  if (j && j.state === BOT.AWAITING_APPROVAL) bots.approve(id, !!m.allow);   // first answer wins (here or there)
}
let watching = false;
function watchJobs() {
  if (watching) return; watching = true;
  bots.onChange(() => { reportJobs().catch(() => {}); });
  reportJobs().catch(() => {});
}
async function reportJobs() {
  if (!link) return;
  const rj = remoteJobs(); let dirty = false;
  for (const [id, r] of Object.entries(rj)) {
    const j = bots.job(id);
    if (!j) { delete rj[id]; dirty = true; continue; }
    const line = j.state === BOT.AWAITING_APPROVAL && j.approval ? 'approval:' + (j.approval.what || '') : j.state + ':' + (j.current || '');
    if (line === r.last) continue;
    r.last = line; dirty = true;
    if (j.state === BOT.AWAITING_APPROVAL && j.approval && !j.approval.decision) {
      await link.send(r.from, 'task_approval', { tid: r.tid, what: j.approval.what || 'An action that needs your OK', category: j.approval.category || '' });
    } else if (BOT_FINAL.has(j.state)) {
      const ok = j.state === BOT.COMPLETED;
      await link.send(r.from, 'task_done', { tid: r.tid, ok, state: j.state, report: ok ? (j.report || 'Done.') : (j.error || (j.state === BOT.CANCELLED ? 'It was stopped.' : "It couldn't finish.")) });
      delete rj[id];
    } else {
      await link.send(r.from, 'task_update', { tid: r.tid, state: j.state, line: j.current || '' });
    }
  }
  if (dirty) setPref('linkRemoteJobs', rj);
}

// ------------------------------------------------------------------ tasks for the phone (sent from here)

const outbox = () => pref('linkOut', {});   // tid → { text, at, state, line, sent, approval }
function saveOut(o) { setPref('linkOut', o); fire(); }

/** "On my phone, …": sends [text] to the phone as a bot job there. Returns a sentence for Atlas to tell the user. */
export async function sendTask(text) {
  if (!link) return "Error: this PC isn't linked to a phone yet (Me → Your Devices).";
  const p = phone();
  const tid = uid(), o = outbox();
  o[tid] = { text: String(text).slice(0, 4000), at: Date.now(), state: 'queued', line: '', sent: false };
  saveOut(o);
  if (p && p.online) { await sendOne(tid); return 'Sent to your phone. It will do it there and its report will come into this chat.'; }
  return `Your phone isn't online right now${p ? ` (last seen ${ago(p.agoMs)})` : ''}. I'll send it as soon as it is (open Atlas on the phone).`;
}
async function sendOne(tid) {
  const o = outbox(), t = o[tid]; if (!t || t.sent || !link) return;
  const p = phone(); if (!p) return;
  await link.send(p.device, 'task', { tid, text: t.text, fromName: pref('deviceName', '') || 'your laptop' });
  t.sent = true; t.state = 'sent'; t.to = p.device; saveOut(o);
  link.setActive(true); setTimeout(() => link && link.setActive(false), 120_000);
}
let sending = false;
async function sendQueued() {
  if (sending) return; sending = true;
  try {
    const p = phone(); if (!p || !p.online) return;
    const o = outbox();
    for (const [tid, t] of Object.entries(o)) {
      if (!t.sent && Date.now() - t.at < 24 * 3600_000) await sendOne(tid);
      else if (!t.sent) { delete o[tid]; saveOut(o); }
    }
  } catch {} finally { sending = false; }
}
function taskOutUpdate(m) {
  const o = outbox(), t = o[m.tid]; if (!t) return;
  if (m.k === 'task_ack') { t.state = 'running'; t.title = m.title || t.text; }
  if (m.k === 'task_update') { t.state = m.state; t.line = m.line || ''; }
  if (m.k === 'task_approval') { t.state = 'awaiting_approval'; t.approval = m.what; t.from = m.from; askApproval(m.tid, m.what, m.from); }
  if (m.k === 'task_done') {
    delete o[m.tid];
    post(`**From your phone:** ${t.title || t.text}\n\n${m.report || (m.ok ? 'Done.' : "It couldn't finish.")}`);
    host.notify(m.ok ? 'Your phone finished' : "Your phone couldn't finish", (t.title || t.text).slice(0, 120), 'link');
  } else o[m.tid] = t;
  saveOut(o);
}
async function askApproval(tid, what, from) {
  host.notify('Your phone needs your OK', what, 'link');
  if (!ui) return;
  const yes = await ui.sheet({ title: 'Allow This on Your Phone?', body: what, primary: 'Allow', secondary: 'Deny' });
  const o = outbox(); if (!o[tid] || o[tid].state !== 'awaiting_approval') return;   // answered on the phone already
  await link.send(from, 'task_decide', { tid, allow: !!yes });
  o[tid].state = yes ? 'running' : 'denied'; o[tid].approval = null; saveOut(o);
}
/** The user's answer to a phone job's approval, from the chat ("yes, go ahead"). First answer wins. */
export async function answerTask(tid, yes) {
  const o = outbox(), t = o[tid]; if (!t || t.state !== 'awaiting_approval' || !link) return false;
  await link.send(t.from || t.to, 'task_decide', { tid, allow: !!yes });
  t.state = yes ? 'running' : 'denied'; t.approval = null; saveOut(o);
  return true;
}
function post(text) {
  const m = { id: uid(), user: false, text: plainReply(text), source: 'Device Link', at: Date.now(), steps: [] };
  db.chat.push(m); saveChat(); changed('chat');
}
// ------------------------------------------------------------------ this PC being controlled by the phone

const controlled = new Map();   // sid → { from, lastKeep, seq, stop, maxW, q }
const allowedDevices = () => pref('linkAllowControl', []);
export function allowControl(device, on) { const a = allowedDevices().filter((d) => d !== device); if (on) a.push(device); setPref('linkAllowControl', a); fire(); }
export const controlAllowed = (device) => allowedDevices().includes(device);

async function screenStart(m) {
  if (!m.sid || controlled.has(m.sid)) return;
  const who = (peers().find((p) => p.device === m.from) || {}).name || 'your phone';
  let ok = controlAllowed(m.from);
  if (!ok) {
    await link.send(m.from, 'screen_reply', { sid: m.sid, ok: false, why: 'asking' });
    host.show(); host.notify(`${who} wants to control this PC`, 'Open Atlas to allow it.', 'link');
    if (!ui) return;
    const o = await ui.sheet({ title: `Let ${who} Control This PC?`, body: `${who} will see this screen and can click and type here. A bar at the top shows while it does, with Stop. You can turn this off in Me → Your Devices.`, primary: 'Allow', secondary: "Don't Allow" });
    if (o) allowControl(m.from, true);
    ok = !!o;
  }
  if (!ok) { await link.send(m.from, 'screen_reply', { sid: m.sid, ok: false, why: 'denied' }); return; }
  const s = { from: m.from, who, lastKeep: Date.now(), seq: 0, maxW: Math.min(1600, Math.max(480, Number(m.maxW) || 1280)), q: Math.min(80, Math.max(30, Number(m.q) || 55)), stopped: false };
  controlled.set(m.sid, s);
  await link.send(m.from, 'screen_reply', { sid: m.sid, ok: true });
  host.remote.banner(`${who} is controlling this PC`);
  link.setActive(true);
  stream(m.sid, s).catch(() => {});
  fire();
}
async function stream(sid, s) {
  let lastJpg = '', lastSent = 0;
  while (!s.stopped && link) {
    if (!sessionAlive(s, Date.now())) { endControlled(sid, 'Your phone went quiet, so control ended.'); break; }
    const t0 = Date.now();
    const f = await host.remote.frame(s.maxW, s.q);
    if (f && !f.error && (f.jpg !== lastJpg || Date.now() - lastSent > 3000)) {
      lastJpg = f.jpg; lastSent = Date.now();
      try { await link.putFrame(++s.seq, { sid, w: f.w, h: f.h, realW: f.realW, realH: f.realH, jpg: f.jpg }); } catch { await new Promise((r) => setTimeout(r, 800)); }
      s.realW = f.realW; s.realH = f.realH;
    }
    await new Promise((r) => setTimeout(r, Math.max(120, 350 - (Date.now() - t0))));   // at most about 3 frames a second
  }
}
async function inputIn(m) {
  const s = controlled.get(m.sid); if (!s || s.from !== m.from || !s.realW) return;
  s.lastKeep = Date.now();
  const plan = inputPlan(m.input, s.realW, s.realH); if (!plan) return;
  const err = await host.remote.input(plan);
  if (err) link.send(m.from, 'input_error', { sid: m.sid, error: String(err).slice(0, 200) }).catch(() => {});
}
function endControlled(sid, why) {
  const s = controlled.get(sid); if (!s) return;
  s.stopped = true; controlled.delete(sid);
  if (link) { link.send(s.from, 'screen_stop', { sid, why }).catch(() => {}); link.setActive(false); }
  if (!controlled.size) { host.remote.banner(''); host.remote.done(); }
  fire();
}
function stopControlled(why) { for (const sid of [...controlled.keys()]) endControlled(sid, why); }
export const beingControlled = () => [...controlled.values()].map((s) => s.who);

// ------------------------------------------------------------------ this PC controlling the phone

let viewer = null;
/**
 * Opens the phone's screen in [el] (an element to draw into). Clicks tap, the wheel and drags swipe, keys type.
 * Returns a controller with end(); calls onEnd(why) when the session ends.
 */
export async function viewPhone(el, { onStatus = () => {}, onEnd = () => {} } = {}) {
  if (!link) throw new Error('This PC isn\'t linked to a phone yet.');
  const p = phone();
  if (!p) throw new Error('No phone is linked yet.');
  if (!p.online) throw new Error(`Your phone isn't online (last seen ${ago(p.agoMs)}). Open Atlas on it, or turn on "Reachable from Your Laptop" there.`);
  if (viewer) viewer.end();
  const sid = uid(), dev = p.device;
  link.setActive(true);
  onStatus('Asking your phone…');
  const replyWait = (ms) => link.waitFor('screen_reply', sid, ms);
  let r = replyWait(15_000);
  await link.send(dev, 'screen_start', { sid, maxW: 720, q: 50 });
  r = await r;
  if (r && !r.ok && r.why === 'asking') { onStatus('Waiting for you to allow it on the phone…'); r = await replyWait(90_000); }
  if (!r || !r.ok) { link.setActive(false); throw new Error(!r ? "Your phone didn't answer." : r.why === 'denied' ? 'Not allowed on the phone.' : r.why === 'no_capture' ? 'The phone needs screen control on: Atlas → Me → Phone Control.' : "Your phone couldn't share its screen."); }
  onStatus('');
  const img = document.createElement('img'); img.className = 'remote-screen'; img.draggable = false; img.tabIndex = 0;
  el.replaceChildren(img);
  let seq = -1, ended = false, frame = null;
  const v = viewer = { sid, end: (why) => {
    if (ended) return; ended = true; viewer = null; clearInterval(keep);
    link && link.send(dev, 'screen_stop', { sid }).catch(() => {}); link && link.setActive(false);
    onEnd(why || '');
  } };
  const keep = setInterval(() => link && link.send(dev, 'screen_keep', { sid }).catch(() => {}), 5000);
  (async () => {
    let misses = 0;
    while (!ended && link) {
      try {
        const f = await link.getFrame(dev, seq, 2000);
        if (f && f.frame && f.frame.sid === sid) { seq = f.seq; frame = f.frame; img.src = 'data:image/jpeg;base64,' + f.frame.jpg; misses = 0; }
        else if (++misses > 12) { v.end('The phone stopped sending its screen.'); }
      } catch { await new Promise((res) => setTimeout(res, 700)); }
    }
  })();
  // input: click → tap, long press → long tap, drag → swipe, wheel → swipe, keys → text / keys
  const send = (input) => { if (!ended && link) link.send(dev, 'input', { sid, input }).catch(() => {}); };
  const frac = (e) => { const b = img.getBoundingClientRect(); return toFraction(e.clientX - b.left, e.clientY - b.top, b.width, b.height); };
  let down = null;
  img.addEventListener('pointerdown', (e) => { img.focus(); down = { f: frac(e), t: Date.now() }; img.setPointerCapture(e.pointerId); });
  img.addEventListener('pointerup', (e) => {
    if (!down || !down.f) return; const f = frac(e) || down.f; const d = Math.hypot(f.x - down.f.x, f.y - down.f.y);
    if (d > 0.02) send({ t: 'drag', x: down.f.x, y: down.f.y, x2: f.x, y2: f.y });
    else send({ t: Date.now() - down.t > 550 ? 'long' : 'tap', x: f.x, y: f.y });
    down = null;
  });
  img.addEventListener('wheel', (e) => { e.preventDefault(); const f = frac(e) || { x: 0.5, y: 0.5 }; send({ t: 'scroll', x: f.x, y: f.y, dy: e.deltaY > 0 ? 3 : -3 }); }, { passive: false });
  img.addEventListener('keydown', (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key.length === 1) { send({ t: 'text', text: e.key }); e.preventDefault(); }
    else if (['Enter', 'Backspace', 'Tab', 'Escape'].includes(e.key)) { send({ t: 'key', key: e.key.toLowerCase() === 'escape' ? 'esc' : e.key.toLowerCase() }); e.preventDefault(); }
  });
  img.addEventListener('paste', (e) => { const t = e.clipboardData.getData('text'); if (t) send({ t: 'text', text: t }); });
  v.act = (act) => send({ t: 'act', act });
  v.type = (text) => send({ t: 'text', text });
  v.key = (key) => send({ t: 'key', key });
  return v;
}

export const ago = (ms) => { const s = Math.round((ms || 0) / 1000); return s < 90 ? 'just now' : s < 5400 ? Math.round(s / 60) + ' min ago' : s < 129600 ? Math.round(s / 3600) + ' h ago' : Math.round(s / 86400) + ' days ago'; };
export { ONLINE_MS };
export const outgoing = () => Object.entries(outbox()).map(([tid, t]) => ({ tid, ...t }));
export const ping = async () => { const p = phone(); return p && link ? link.ping(p.device) : null; };

// >>> awareness: the main Atlas sees the phone and can answer its approvals from the chat
aware.use({ link: { linked, phone, outgoing, answerTask, ago } });
// <<< awareness
