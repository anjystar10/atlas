// Atlas Device Link: the live line between the user's own phone and PC (the same protocol as Link.kt on the phone).
//
// Every message is end-to-end encrypted with the private link code (the same AES-256-GCM key as sync) before it
// leaves the device; the cloud relay (link_* functions in the Atlas Supabase project) only ever sees ciphertext,
// the owner hash and device ids. Messages wait there for ten minutes at most; screen frames keep only the newest.
//
// This file has no app dependencies so it can be tested against a stand-in relay. linkwire.js plugs it into Atlas.
//
// A message (before encryption): { v: 1, id, k: kind, from, to, at, ...fields }.
// Kinds:
//   task {tid, text, fromName}                 run this as a bot job here     → task_ack, task_update, task_approval, task_done
//   task_decide {tid, allow}                    the user's answer to a task_approval, from the device that sent the task
//   screen_start {sid, maxW, q}                 stream your screen to me        → screen_reply {sid, ok, why}
//   screen_keep {sid} / screen_stop {sid}       the viewer is still watching / is done (either side may stop)
//   input {sid, t, x, y, x2, y2, dy, text, key, act}   coordinates are fractions of the frame (0..1)
//   ping {n} → pong {n}                         round-trip time
//   file {fid, name, mime, i, n, data}          a file in pieces (data = base64 of the piece)

export const VERSION = 1;
export const PIECE = 600_000;              // base64 characters of a file per message (the relay takes up to 1.5 MB)
export const MAX_FILE = 10 * 1024 * 1024;  // bytes
const SEEN_KEEP = 500;

export const uid = () => (globalThis.crypto && crypto.randomUUID ? crypto.randomUUID() : 'x' + Math.random().toString(36).slice(2) + Date.now().toString(36));

/** A device counts as online when it checked in within this long. */
export const ONLINE_MS = 75_000;

/** A tap on the frame (fractions 0..1) in the target's real screen pixels. Clamped inside the screen. */
export function toScreen(fx, fy, realW, realH) {
  const cl = (v) => (Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0.5);
  return { x: Math.min(realW - 1, Math.round(cl(fx) * realW)), y: Math.min(realH - 1, Math.round(cl(fy) * realH)) };
}
/** Where a click at (px, py) inside a picture drawn at (drawW × drawH) lands, as fractions; null when outside it. */
export function toFraction(px, py, drawW, drawH) {
  if (!(drawW > 0 && drawH > 0) || px < 0 || py < 0 || px > drawW || py > drawH) return null;
  return { x: px / drawW, y: py / drawH };
}

/** Splits base64 text into pieces for `file` messages. */
export function pieces(b64, size = PIECE) {
  const out = [];
  for (let i = 0; i < b64.length; i += size) out.push(b64.slice(i, i + size));
  return out.length ? out : [''];
}

/** Checks a decrypted message: returns it, or null when it isn't one of ours (wrong version, missing kind, not for us). */
export function accept(msg, me) {
  if (!msg || typeof msg !== 'object' || msg.v !== VERSION || typeof msg.k !== 'string' || !msg.id) return null;
  if (msg.to && msg.to !== '*' && msg.to !== me) return null;
  return msg;
}

/**
 * The live line.
 * deps: rpc(fn, body) → parsed JSON; encrypt(text) / decrypt(text) with the link key; owner (hash); me (device id);
 *       info() → {name, platform, caps}; onMessage(msg); onPeers(list); now(); sleep(ms); log(...)
 */
export function createLink(deps) {
  const now = deps.now || (() => Date.now());
  const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const log = deps.log || (() => {});
  let running = false, active = 0, cursor = null, loopP = null;
  let peers = [];
  const seen = new Set(), seenOrder = [];
  const waiters = new Map();   // reply kind+key → resolve
  let lastBeat = 0, failures = 0, idleMs = deps.idleMs ?? 15_000;
  const files = new Map();     // fid → {name, mime, n, got: [] }
  const stats = { sent: 0, got: 0, lastRtt: null, lastError: null };

  function remember(id) { seen.add(id); seenOrder.push(id); if (seenOrder.length > SEEN_KEEP) seen.delete(seenOrder.shift()); }

  async function beat() {
    const info = JSON.stringify(Object.assign({ at: now() }, (deps.info && deps.info()) || {}));
    const rows = await deps.rpc('link_beat', { p_owner: deps.owner, p_device: deps.me, p_info: await deps.encrypt(info) });
    const list = [];
    for (const r of rows || []) {
      if (r.device === deps.me) continue;
      let i = {}; try { i = JSON.parse(await deps.decrypt(r.info)); } catch { continue; }   // a device with another code: not ours
      list.push({ device: r.device, name: i.name || 'Device', platform: i.platform || '', caps: i.caps || [], agoMs: Number(r.ago_ms) || 0,
        online: (Number(r.ago_ms) || 0) < ONLINE_MS, reachable: i.reachable !== false, info: i });
    }
    peers = list; lastBeat = now();
    if (deps.onPeers) deps.onPeers(peers);
    return peers;
  }

  async function pullOnce(waitMs) {
    if (cursor === null) cursor = Number(await deps.rpc('link_head', { p_owner: deps.owner })) || 0;
    const rows = await deps.rpc('link_pull', { p_owner: deps.owner, p_device: deps.me, p_since: cursor, p_wait_ms: waitMs });
    for (const r of rows || []) {
      cursor = Math.max(cursor, Number(r.id) || 0);
      let msg; try { msg = accept(JSON.parse(await deps.decrypt(r.payload)), deps.me); } catch { msg = null; }
      if (!msg || seen.has(msg.id)) continue;
      remember(msg.id); stats.got++;
      await handle(msg);
    }
    return (rows || []).length;
  }

  async function handle(msg) {
    if (msg.k === 'ping') { send(msg.from, 'pong', { n: msg.n, t: msg.t }).catch(() => {}); return; }
    if (msg.k === 'file') {
      const f = files.get(msg.fid) || { name: msg.name, mime: msg.mime, n: msg.n, got: [], from: msg.from, meta: msg.meta };
      f.got[msg.i] = msg.data; files.set(msg.fid, f);
      if (f.got.filter((x) => x !== undefined).length === f.n) {
        files.delete(msg.fid);
        const done = { k: 'file_done', id: msg.id, from: msg.from, fid: msg.fid, name: f.name, mime: f.mime, b64: f.got.join(''), meta: f.meta };
        const w = waiters.get('file:' + msg.fid); if (w) { waiters.delete('file:' + msg.fid); w(done); }
        if (deps.onMessage) await deps.onMessage(done);
      }
      return;
    }
    for (const key of [msg.k + ':' + (msg.tid || msg.sid || msg.n || ''), msg.k]) {
      const w = waiters.get(key); if (w) { waiters.delete(key); w(msg); }
    }
    if (deps.onMessage) { try { await deps.onMessage(msg); } catch (e) { log('handler', e); } }
  }

  async function loop() {
    while (running) {
      try {
        if (now() - lastBeat > (active ? 20_000 : 45_000)) await beat();
        if (active || deps.alwaysLongPoll) await pullOnce(2000);
        else { await pullOnce(0); await sleepUnlessActive(idleMs); }
        failures = 0; stats.lastError = null;
      } catch (e) {
        failures++; stats.lastError = String(e && e.message || e);
        await sleep(Math.min(30_000, 1000 * 2 ** Math.min(failures, 5)));
      }
    }
  }
  let wake = null;
  async function sleepUnlessActive(ms) {
    await Promise.race([sleep(ms), new Promise((r) => { wake = r; })]);
    wake = null;
  }

  /** Sends one message to [to] ('*' for every other device). */
  async function send(to, k, fields = {}) {
    const msg = Object.assign({ v: VERSION, id: uid(), k, from: deps.me, to, at: now() }, fields);
    const id = await deps.rpc('link_send', { p_owner: deps.owner, p_from: deps.me, p_to: to, p_payload: await deps.encrypt(JSON.stringify(msg)) });
    stats.sent++;
    return Object.assign(msg, { relayId: id });
  }

  /** Waits for a message of kind [k] (with the given tid/sid/n) for up to [ms]; null when it doesn't come. */
  function waitFor(k, key, ms) {
    return new Promise((resolve) => {
      const name = key === undefined ? k : k + ':' + key;
      const t = setTimeout(() => { waiters.delete(name); resolve(null); }, ms);
      waiters.set(name, (m) => { clearTimeout(t); resolve(m); });
    });
  }

  return {
    stats,
    get peers() { return peers; },
    get running() { return running; },
    start() { if (running) return; running = true; loopP = loop(); },
    async stop() { running = false; if (wake) wake(); await loopP; },
    /** While something live is going on (a remote screen, a task waiting), ask the relay continuously. */
    setActive(on) { active = Math.max(0, active + (on ? 1 : -1)); if (active && wake) wake(); },
    get active() { return active > 0; },
    beat, pullOnce, send, waitFor, handle,
    /** Round trip to [to] in ms, or null if it didn't answer within [ms]. */
    async ping(to, ms = 8000) {
      const n = Math.floor(Math.random() * 1e9), t0 = now();
      this.setActive(true);
      try { const w = waitFor('pong', n, ms); await send(to, 'ping', { n, t: t0 }); const r = await w; stats.lastRtt = r ? now() - t0 : null; return stats.lastRtt; }
      finally { this.setActive(false); }
    },
    /** Sends a file (base64) in pieces; resolves with the file id. */
    async sendFile(to, name, mime, b64, meta) {
      if (b64.length * 0.75 > MAX_FILE) throw new Error('That file is over 10 MB.');
      const fid = uid(), ps = pieces(b64);
      for (let i = 0; i < ps.length; i++) await send(to, 'file', { fid, name, mime, i, n: ps.length, data: ps[i], meta });
      return fid;
    },
    /** Screen frames: the newest one only. */
    async putFrame(seq, payload) { await deps.rpc('link_frame_put', { p_owner: deps.owner, p_device: deps.me, p_seq: seq, p_payload: await deps.encrypt(JSON.stringify(payload)) }); },
    async getFrame(device, after, waitMs = 2000) {
      const rows = await deps.rpc('link_frame_get', { p_owner: deps.owner, p_device: device, p_after: after, p_wait_ms: waitMs });
      const r = rows && rows[0]; if (!r) return null;
      return { seq: Number(r.seq), at: Number(r.at_ms), frame: JSON.parse(await deps.decrypt(r.payload)) };
    },
  };
}

/**
 * The remote-control rules on the device being controlled (pure: the caller does the screen and the input).
 * Keeps sessions, drops ones whose viewer went quiet, and says what an input means for this screen.
 */
export const SESSION_IDLE_MS = 20_000;
export function sessionAlive(s, t) { return !!s && t - s.lastKeep < SESSION_IDLE_MS; }

/** What a remote input turns into here (screen pixels); null when it's malformed. */
export function inputPlan(inp, realW, realH) {
  if (!inp || typeof inp.t !== 'string') return null;
  const p = (x, y) => toScreen(Number(x), Number(y), realW, realH);
  switch (inp.t) {
    case 'tap': case 'long': case 'double': case 'right': return { t: inp.t, ...p(inp.x, inp.y) };
    case 'drag': { const a = p(inp.x, inp.y), b = p(inp.x2, inp.y2); return { t: 'drag', x: a.x, y: a.y, x2: b.x, y2: b.y }; }
    case 'scroll': { const a = p(inp.x ?? 0.5, inp.y ?? 0.5); const dy = Math.max(-20, Math.min(20, Math.round(Number(inp.dy) || 0))); return dy ? { t: 'scroll', x: a.x, y: a.y, dy } : null; }
    case 'text': { const s = String(inp.text ?? '').slice(0, 2000); return s ? { t: 'text', text: s } : null; }
    case 'key': { const k = String(inp.key || '').toLowerCase().slice(0, 40); return /^[a-z0-9+]+$/.test(k) ? { t: 'key', key: k } : null; }
    case 'act': { const a = String(inp.act || ''); return ['back', 'home', 'recents', 'notifications', 'lock', 'volume_up', 'volume_down', 'mute', 'show_desktop', 'task_manager'].includes(a) ? { t: 'act', act: a } : null; }
    default: return null;
  }
}
