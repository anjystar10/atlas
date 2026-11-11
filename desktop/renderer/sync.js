// Keeps this PC and the phone the same (the same protocol and rules as Sync.kt / SyncPlan.kt on the phone): records
// are encrypted with the shared private link code (AES-256-GCM) before they leave; the cloud only stores scrambled text.
//
// A sync first notes what changed here and when, then pulls what the phone changed and merges it in unless this PC's
// own unsent edit is newer, then sends what changed here (or a "deleted" mark) with the time of the edit. So the
// newest edit of a record wins whenever it gets sent. Offline, changes wait, and Atlas retries by itself.
import { db, saveState, saveChat, changed, pref, setPref } from './store.js';
const host = window.atlasHost;
const URL_BASE = 'https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc';
const KEY = 'sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj';
const ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
const CHAT_KEEP = 120, LOG_KEEP = 400;

export const status = { last: null, at: 0, running: false, pendingOut: false };

export const code = () => pref('syncCode', '') || null;
export function newCode() {
  const r = crypto.getRandomValues(new Uint32Array(20));
  let s = 'ATLS';
  for (let i = 0; i < 20; i++) { if (i % 4 === 0) s += '-'; s += ALPHABET[r[i] % ALPHABET.length]; }
  return s;
}
export function normalize(c) {
  const body = String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, '').replace(/^ATLS/, '');
  if (body.length !== 20 || [...body].some((ch) => !ALPHABET.includes(ch))) return null;
  return 'ATLS-' + body.match(/.{4}/g).join('-');
}
export async function link(c) {
  const n = normalize(c); if (!n) return false;
  setPref('syncCode', n); await host.write('sync-state.json', '{}'); await host.write('sync-gone.json', '{}');
  syncSoon(0); return true;
}
export async function unlink() { setPref('syncCode', ''); await host.write('sync-state.json', '{}'); status.last = null; }

const enc = new TextEncoder(), dec = new TextDecoder();
async function sha256(s) { return new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s))); }
const hex = (b) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
const b64 = (u8) => { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode(...u8.subarray(i, i + 0x8000)); return btoa(s); };
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export async function owner(c) { return hex(await sha256('atlas-owner:' + c)); }
async function key(c) { return crypto.subtle.importKey('raw', await sha256('atlas-key:' + c), 'AES-GCM', false, ['encrypt', 'decrypt']); }
export async function encrypt(c, plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await key(c), enc.encode(plain)));
  const all = new Uint8Array(12 + ct.length); all.set(iv); all.set(ct, 12); return b64(all);
}
export async function decrypt(c, payload) {
  const all = unb64(payload);
  return dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.subarray(0, 12) }, await key(c), all.subarray(12)));
}

/** The same fingerprint whatever order a record's fields are in. */
export function canonical(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (typeof v === 'object') return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : String(v);
  return JSON.stringify(v);
}
async function fp(o) { return hex(new Uint8Array(await crypto.subtle.digest('SHA-1', enc.encode(canonical(o))))); }

// ------------------------------------------------------------------ the rules (the same as SyncPlan.kt; tested with the same cases)

/** Tasks and memories that are no longer here were deleted. (Chat and Log entries only when Atlas says so: noteDeleted.) */
export const IMPLICIT_DELETES = ['task', 'memory'];

/** Does this device's unsent edit beat the one from the cloud? Newer wins; a dead heat goes to the higher device id (the cloud uses the same rule). */
export const mineWins = (myAt, me, theirAt, theirDevice) => myAt > theirAt || (myAt === theirAt && me > theirDevice);

/** When a record was last edited, by its own account (0 if it doesn't say). */
export const ownTime = (j) => Math.max(Number(j.updatedAt) || 0, Number(j.at) || 0, Number(j.createdAt) || 0, Number(j.updated_ms) || 0);

const kindOf = (k) => k.slice(0, k.indexOf(':'));

/**
 * Notes what changed here since the last sync, before talking to the cloud.
 * sent: "kind:id" → fingerprint the cloud holds of ours. dirty: "kind:id" → {at, fp, del}: changed here, not sent yet.
 * local: "kind:id" → {fp, ownTime} for every record in the synced window. gone: deletions Atlas recorded itself.
 * first: nothing synced yet (a new link): records keep their own times, so an old copy here can't beat a newer one there.
 * skip: kinds that couldn't be read just now (left exactly as they are).
 */
export function stamp(sent, dirty, local, gone, now, first, skip = [], extraImplicit = []) {
  for (const [k, l] of Object.entries(local)) {
    if (sent[k] === l.fp) { delete dirty[k]; continue; }
    const d = dirty[k];
    if (!d || d.del || d.fp !== l.fp) dirty[k] = { at: first ? Math.max(1, l.ownTime) : now, fp: l.fp };
  }
  for (const [k, at] of Object.entries(gone)) {
    if (k in local || skip.includes(kindOf(k))) continue;
    if (k in sent || (dirty[k] && !dirty[k].del)) dirty[k] = { at, del: true };
  }
  const implicit = [...IMPLICIT_DELETES, ...extraImplicit];
  for (const k of Object.keys(sent)) {
    if (k in local || skip.includes(kindOf(k))) continue;
    if (dirty[k] && dirty[k].del) continue;
    if (implicit.includes(kindOf(k))) dirty[k] = { at: now, del: true };
    else delete sent[k];                       // scrolled out of the synced window: forgotten here, not deleted there
  }
  for (const k of Object.keys(dirty)) {
    if (k in local || skip.includes(kindOf(k))) continue;
    if (!dirty[k].del) delete dirty[k];
  }
}

/** This PC's data has a different device id from the one the last sync saw: it was made anew since (the file was lost). */
export const freshStart = (lastDevice, me) => !!lastDevice && lastDevice !== me;

// ------------------------------------------------------------------ deletions Atlas records itself, more kinds

let goneQueue = Promise.resolve();
async function readGone() { try { return JSON.parse((await host.read('sync-gone.json')) || '{}'); } catch { return {}; } }
/**
 * Chat messages (New Chat) and Log entries are only deleted on the other device when Atlas says they were deleted on
 * purpose, here. (Their absence alone proves nothing: old ones simply fall out of the synced part.)
 */
export function noteDeleted(kind, ids) {
  if (!ids.length || !code()) return goneQueue;
  goneQueue = goneQueue.then(async () => { const o = await readGone(); const now = Date.now(); for (const id of ids) o[kind + ':' + id] = now; await host.write('sync-gone.json', JSON.stringify(o)); }).catch(() => {});
  syncSoon();
  return goneQueue;
}
function dropGone(keys) {
  if (!keys.length) return goneQueue;
  goneQueue = goneQueue.then(async () => { const o = await readGone(); for (const k of keys) delete o[k]; await host.write('sync-gone.json', JSON.stringify(o)); }).catch(() => {});
  return goneQueue;
}

/**
 * Another kind of record that syncs (say "job" for Atlas Bot's jobs, later). list() gives every record as {id: json}
 * (null: can't be read just now, leave it be); apply(id, json|null) takes one in from the other device (null: deleted).
 * Records must be plain JSON with no secrets; "updatedAt", "updated_ms" or "at" is read as the edit time.
 */
const kinds = new Map();
export function registerKind(kind, { list, apply, absentMeansDeleted = true }) {
  if (['task', 'memory', 'log', 'profile', 'chat'].includes(kind)) throw new Error('reserved kind');
  kinds.set(kind, { list, apply, absentMeansDeleted });
}

// ------------------------------------------------------------------ the records

/** Everything that syncs, as records; and the kinds that couldn't be read just now. */
async function records() {
  const out = [], skip = [];
  for (const t of db.state.tasks) out.push({ kind: 'task', id: t.id, json: t });
  for (const m of db.state.memories) out.push({ kind: 'memory', id: m.id, json: m });
  for (const l of db.state.log.slice(-LOG_KEEP)) out.push({ kind: 'log', id: l.id, json: l });
  // a name that isn't set here is not sent: it must never blank the name on the other device
  if ((db.state.settings.name || '').trim()) out.push({ kind: 'profile', id: 'me', json: { name: db.state.settings.name } });
  for (const m of db.chat.filter((m) => !m.pending && m.source !== 'error').slice(-CHAT_KEEP))
    out.push({ kind: 'chat', id: m.id, json: { id: m.id, user: !!m.user, text: m.text, source: m.source || '', at: m.at, steps: m.steps || [], files: (m.media || []).length || m.files || 0 } });
  for (const [kind, k] of kinds) {
    let m = null; try { m = await k.list(); } catch {}
    if (!m) skip.push(kind); else for (const [id, json] of Object.entries(m)) out.push({ kind, id, json });
  }
  return { recs: out, skip };
}

async function rpc(fn, body) {
  const r = await host.fetch(`${URL_BASE}/${fn}`, { method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 30000 });
  if (!r.ok) throw new Error(r.status === 0 ? 'offline' : `the cloud answered ${r.status}`);
  return JSON.parse(r.text);
}

async function applyRecord(kind, id, rec) {
  if (kinds.has(kind)) { try { await kinds.get(kind).apply(id, rec); } catch {} return kind; }
  const arr = kind === 'task' ? db.state.tasks : kind === 'memory' ? db.state.memories : kind === 'log' ? db.state.log : null;
  // a device that has no name set doesn't wipe the one this device has
  if (kind === 'profile') { if (rec && typeof rec.name === 'string' && rec.name.trim()) db.state.settings.name = rec.name; return 'state'; }
  if (kind === 'chat') {
    const i = db.chat.findIndex((m) => m.id === id);
    if (!rec) { if (i >= 0) db.chat.splice(i, 1); return 'chat'; }
    const m = { id, user: !!rec.user, text: rec.text || '', source: rec.source || '', at: rec.at || 0, steps: rec.steps || [], files: rec.files || 0 };
    if (i >= 0) Object.assign(db.chat[i], m); else { const at = db.chat.findIndex((x) => x.at > m.at); if (at < 0) db.chat.push(m); else db.chat.splice(at, 0, m); }
    return 'chat';
  }
  if (!arr) return null;
  const i = arr.findIndex((x) => x.id === id);
  if (!rec) { if (i >= 0) arr.splice(i, 1); }
  else if (i >= 0) arr[i] = rec; else arr.push(rec);
  if (kind === 'log') arr.sort((a, b) => (a.at || 0) - (b.at || 0));
  return 'state';
}

let timer = 0, failures = 0, lastTry = 0;
export function syncSoon(ms = 4000) { if (!code()) return; clearTimeout(timer); timer = setTimeout(() => runOnce(), ms); }
/** While Atlas is running: look for the phone's changes every minute (a tiny request), and at once when the internet returns. */
export function keepFresh() {
  setInterval(() => { if (code() && Date.now() - lastTry > 45000) runOnce(); }, 60000);
  window.addEventListener('focus', () => syncSoon(500));
  window.addEventListener('online', () => { if (failures > 0 || status.pendingOut) syncSoon(1500); });
}

export async function runOnce() {
  const c = code(); if (!c || status.running) return status.last;
  status.running = true; lastTry = Date.now();
  try {
    let st = {}; try { st = JSON.parse((await host.read('sync-state.json')) || '{}'); } catch {}
    let first = st.cursor === undefined;
    let cursor = st.cursor || 0, sent = st.sent || {}, dirty = st.dirty || {};
    const me = db.state.deviceId, own = await owner(c);
    // this PC's data was started afresh since the last sync: what's missing wasn't deleted, it was lost. Take it back from the cloud.
    if (freshStart(st.device, me)) { cursor = 0; sent = {}; dirty = {}; first = true; }
    const save = () => host.write('sync-state.json', JSON.stringify({ v: 2, cursor, sent, dirty, device: me }));

    // 1. note what changed here, with the time it changed, before anything from the cloud is taken in
    const now = Date.now();
    const { recs, skip } = await records();
    const byKey = {}, local = {};
    for (const r of recs) { const k = r.kind + ':' + r.id; byKey[k] = r; local[k] = { fp: await fp(r.json), ownTime: ownTime(r.json) }; }
    await goneQueue; const gone = await readGone();
    stamp(sent, dirty, local, gone, now, first, skip, [...kinds].filter(([, k]) => k.absentMeansDeleted).map(([n]) => n));
    if (!first) await save();        // an edit made offline keeps the time it was noticed, however long sending takes
    status.pendingOut = Object.keys(dirty).length > 0;

    // 2. take in what the phone changed, unless this PC's own unsent edit is newer
    let got = 0; const touched = new Set();
    for (;;) {
      const page = await rpc('sync_pull', { p_owner: own, p_since: cursor, p_limit: 500 });
      if (!page.length) break;
      const applied = [];
      for (const r of page) {
        cursor = Math.max(cursor, r.rev);
        const k = r.kind + ':' + r.id;
        let rec = null; if (!r.deleted) { try { rec = JSON.parse(await decrypt(c, r.payload)); } catch { continue; } }
        const d = dirty[k];
        // the cloud already holds exactly what's here (a re-link, or our own record coming back): nothing to send or take
        if (rec && d && !d.del && d.fp === await fp(rec)) { sent[k] = d.fp; delete dirty[k]; continue; }
        if (r.device === me) { if (!d) { if (r.deleted) delete sent[k]; else sent[k] = await fp(rec); } continue; }   // our own, coming back
        if (d && mineWins(d.at, me, r.updated_ms, r.device)) continue;        // ours is newer: it goes up below
        delete dirty[k];
        if (r.deleted) delete sent[k]; else sent[k] = await fp(rec);
        const w = await applyRecord(r.kind, r.id, rec); if (w) touched.add(w); got++;
        if (rec) applied.push(k);
      }
      if (applied.length) {
        // this PC may keep a record its own way (field order, defaults): that is not an edit to send back
        const mine = {}; for (const r of (await records()).recs) mine[r.kind + ':' + r.id] = r.json;
        for (const k of applied) if (mine[k]) sent[k] = await fp(mine[k]);
      }
      if (touched.has('state')) saveState(true);
      if (touched.has('chat')) saveChat(true);
      await save();
      if (page.length < 500) break;
    }

    // 3. send what changed here, each with the time of its edit
    const keys = Object.keys(dirty); let put = 0;
    for (let i = 0; i < keys.length; i += 400) {
      const items = [], done = [];
      for (const k of keys.slice(i, i + 400)) {
        const d = dirty[k], kind = kindOf(k), id = k.slice(kind.length + 1);
        if (d.del) items.push({ kind, id, updated_ms: d.at, deleted: true, payload: '', device: me });
        else { const r = byKey[k]; if (!r) continue; items.push({ kind, id, updated_ms: d.at, payload: await encrypt(c, JSON.stringify(r.json)), device: me }); }
        done.push(k);
      }
      if (items.length) await rpc('sync_push', { p_owner: own, p_items: items });
      for (const k of done) { if (dirty[k].del) delete sent[k]; else sent[k] = dirty[k].fp; delete dirty[k]; }
      put += items.length;
      await save();
    }
    await save();
    await dropGone(Object.keys(gone).filter((k) => !dirty[k] && !skip.includes(kindOf(k))));
    status.pendingOut = Object.keys(dirty).length > 0;
    status.at = Date.now(); failures = 0;
    status.last = 'Synced' + (got + put ? ` (${got} in, ${put} out)` : '');
    for (const w of touched) changed(w);
  } catch (e) {
    status.last = "Couldn't sync: " + (e.message || e);
    // try again by itself: after 30 s, then 2, 5 and 15 minutes (and at once when the internet comes back)
    failures++; clearTimeout(timer); timer = setTimeout(() => runOnce(), [30000, 120000, 300000, 900000][Math.min(failures, 4) - 1]);
  } finally { status.running = false; changed('sync'); }
  return status.last;
}
