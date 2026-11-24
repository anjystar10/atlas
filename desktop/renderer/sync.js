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

export const status = { last: null, at: 0, running: false, pendingOut: false, pending: 0, problem: null, others: [], tooBig: 0, waiting: 0, skew: 0 };

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
  Object.assign(status, { problem: null, others: [], pending: 0, tooBig: 0, waiting: 0 });
  syncSoon(0); return true;
}
export async function unlink() {
  clearTimeout(timer);                       // no retry waiting to run against a link that's gone
  setPref('syncCode', ''); await host.write('sync-state.json', '{}');
  Object.assign(status, { last: null, problem: null, others: [], pending: 0, tooBig: 0, waiting: 0 });
}

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

// ------------------------------------------------------------------ the pull position (as SyncPlan.kt)

/**
 * Two devices sending at the same moment can have their records numbered out of order, so the position kept is the
 * newest one at least this old; each pull looks back over the last few seconds again (records already taken in are skipped).
 */
export const OVERLAP_MS = 30000;
/** Where to pull from next time. recent: [[when, highest number seen], ...]. Returns [cursor, recent]. */
export function advance(cursor, recent, now) {
  let c = cursor; const keep = [];
  for (const [t, rev] of recent) { if (t <= now - OVERLAP_MS || t > now + 60000) c = Math.max(c, rev); else keep.push([t, rev]); }
  return [c, keep.slice(-20)];
}
/** A record already taken in at this number or later (the overlap pulls it again): skip it. */
export const alreadyTaken = (seenRev, rev) => rev > 0 && seenRev >= rev;

// ------------------------------------------------------------------ sending
export const MAX_RECORD = 1400000, MAX_BATCH = 400, MAX_BATCH_CHARS = 1800000;
/** Splits records of these sizes into sends: at most MAX_BATCH records and about MAX_BATCH_CHARS characters each (indices). */
export function batches(sizes) {
  const out = []; let cur = [], chars = 0;
  sizes.forEach((n, i) => {
    if (cur.length && (cur.length >= MAX_BATCH || chars + n > MAX_BATCH_CHARS)) { out.push(cur); cur = []; chars = 0; }
    cur.push(i); chars += n;
  });
  if (cur.length) out.push(cur);
  return out;
}

// ------------------------------------------------------------------ problems, in plain words
/** What went wrong, from the cloud's answer (status 0: no answer at all). */
export function problem(status, body) {
  const b = String(body || '').toLowerCase();
  if (status === 0) return { kind: 'offline', text: "Offline. Changes wait here and go up as soon as you're back online.", fix: null };
  if (status === 540 || b.includes('paused') || (b.includes('restore') && b.includes('project')))
    return { kind: 'paused', text: 'The sync cloud is asleep: Supabase pauses free projects after a week without use.',
      fix: 'Open supabase.com, sign in, open the project “Atlas” and choose Restore project. Then tap Sync Now.' };
  if (status === 401 || status === 403) return { kind: 'refused', text: 'The sync cloud turned Atlas away.', fix: 'Update Atlas on both devices.' };
  if (status === 404 || b.includes('could not find the function'))
    return { kind: 'outdated', text: "The sync cloud doesn't have something this version of Atlas needs.", fix: 'Update Atlas on both devices.' };
  if (status >= 500 && status <= 599) return { kind: 'cloud', text: 'The sync cloud is having trouble right now. Atlas keeps trying by itself.', fix: null };
  return { kind: 'other', text: `Couldn't sync (the cloud answered ${status}).`, fix: null };
}

// ------------------------------------------------------------------ settings that are the same on both devices
/** Your name and how reminders behave are shared; the look, speaking aloud, the orb and Hey Atlas stay each device's own. */
export function profileOf(s) {
  const o = {};
  const name = String(s.name || '').trim(); if (name) o.name = name;
  o.tone = s.tone == null ? 'firm' : String(s.tone); o.quietStart = s.quietStart == null ? '22:30' : String(s.quietStart);
  o.quietEnd = s.quietEnd == null ? '06:30' : String(s.quietEnd);
  o.graceMin = Number.isFinite(+s.graceMin) && s.graceMin !== '' && s.graceMin != null ? Math.trunc(+s.graceMin) : 15;
  o.followUps = Number.isFinite(+s.followUps) && s.followUps !== '' && s.followUps != null ? Math.trunc(+s.followUps) : 1;
  return o;
}
/**
 * A profile nobody has touched here (no name, every setting at its default): a fresh install's. It isn't sent, so
 * linking a new device can never reset the name and reminder settings the other device already has.
 */
export const untouchedProfile = (p) => !p.name && p.tone === 'firm' && p.quietStart === '22:30' && p.quietEnd === '06:30' && p.graceMin === 15 && p.followUps === 1;
/** The fields of a profile from the other device that may be taken in (an older Atlas sends only the name). */
export function profileFields(rec) {
  const out = {}; if (!rec || typeof rec !== 'object') return out;
  const name = typeof rec.name === 'string' ? rec.name.trim() : '';
  if (name && name.length <= 60) out.name = name;
  if (typeof rec.tone === 'string' && rec.tone.trim() && rec.tone.length <= 20) out.tone = rec.tone;
  const hm = /^\d{1,2}:\d{2}$/;
  if (typeof rec.quietStart === 'string' && hm.test(rec.quietStart)) out.quietStart = rec.quietStart;
  if (typeof rec.quietEnd === 'string' && hm.test(rec.quietEnd)) out.quietEnd = rec.quietEnd;
  if (Number.isInteger(rec.graceMin) && rec.graceMin >= 0 && rec.graceMin <= 240) out.graceMin = rec.graceMin;
  if (Number.isInteger(rec.followUps) && rec.followUps >= 0 && rec.followUps <= 10) out.followUps = rec.followUps;
  return out;
}

// ------------------------------------------------------------------ the devices
export const PROTOCOL = 3;
export const PRESENCE_EVERY_MS = 10 * 60000;
export const presenceDue = (lastSaid, now) => lastSaid <= 0 || now - lastSaid >= PRESENCE_EVERY_MS || now < lastSaid;

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
/** Kinds every Atlas of this version knows. "device" is each device saying it's there (and which Atlas it runs). */
const BUILT_IN = ['task', 'memory', 'log', 'profile', 'chat', 'device'];
export function registerKind(kind, { list, apply, absentMeansDeleted = true }) {
  if (BUILT_IN.includes(kind)) throw new Error('reserved kind');
  kinds.set(kind, { list, apply, absentMeansDeleted });
}
const known = (kind) => BUILT_IN.includes(kind) || kinds.has(kind);
function devicesOf(o, me) {
  return Object.entries(o || {}).filter(([id]) => id !== me)
    .map(([id, j]) => ({ id, platform: j.platform || '', name: j.name || '', app: j.app || '', protocol: Number(j.proto) || 2, seenMs: Number(j.said) || 0, outdated: (Number(j.proto) || 2) < PROTOCOL }))
    .sort((a, b) => b.seenMs - a.seenMs);
}

// ------------------------------------------------------------------ the records

/** Everything that syncs, as records; and the kinds that couldn't be read just now. */
async function records(presence) {
  const out = [], skip = [];
  for (const t of db.state.tasks) out.push({ kind: 'task', id: t.id, json: t });
  for (const m of db.state.memories) out.push({ kind: 'memory', id: m.id, json: m });
  for (const l of db.state.log.slice(-LOG_KEEP)) out.push({ kind: 'log', id: l.id, json: l });
  // your name and how reminders behave (a name that isn't set here is left out: it must never blank the phone's)
  const prof = profileOf(db.state.settings); if (!untouchedProfile(prof)) out.push({ kind: 'profile', id: 'me', json: prof });
  if (presence) out.push({ kind: 'device', id: db.state.deviceId, json: presence });
  for (const m of db.chat.filter((m) => !m.pending && m.source !== 'error').slice(-CHAT_KEEP))
    out.push({ kind: 'chat', id: m.id, json: { id: m.id, user: !!m.user, text: m.text, source: m.source || '', at: m.at, steps: m.steps || [], files: (m.media || []).length || m.files || 0 } });
  for (const [kind, k] of kinds) {
    let m = null; try { m = await k.list(); } catch {}
    if (!m) skip.push(kind); else for (const [id, json] of Object.entries(m)) out.push({ kind, id, json });
  }
  return { recs: out, skip };
}

/** The cloud's answer when it isn't a success (status 0: no answer at all: offline, timed out). */
class CloudError extends Error { constructor(st, body) { super(st === 0 ? 'offline' : `the cloud answered ${st}`); this.status = st; this.body = body || ''; } }
async function rpc(fn, body) {
  const r = await host.fetch(`${URL_BASE}/${fn}`, { method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 30000 });
  if (!r.ok) throw new CloudError(r.status || 0, r.text || r.error || '');
  return JSON.parse(r.text);
}

/** Takes one record in. Throws when a registered kind can't take it in just now (it's kept and tried again, not lost). */
async function applyRecord(kind, id, rec) {
  if (kinds.has(kind)) { await kinds.get(kind).apply(id, rec); return kind; }
  const arr = kind === 'task' ? db.state.tasks : kind === 'memory' ? db.state.memories : kind === 'log' ? db.state.log : null;
  // your name and how reminders behave (an older Atlas sends only the name; a blank name never wipes this one)
  if (kind === 'profile') { if (rec) Object.assign(db.state.settings, profileFields(rec)); return 'state'; }
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

/**
 * One device at a time: asks the cloud to hold [id] (say an Atlas Bot job) for this PC for [leaseMs] (at most an hour;
 * 0 lets it go). Atomic in the cloud, so the phone and this PC asking at once can't both get it.
 * Returns { holder, mine, untilMs } (holder null: nobody), or null on failure (offline, not linked).
 */
export async function claim(id, leaseMs) {
  const c = code(); if (!c) return null;
  try {
    const r = await rpc('sync_claim', { p_owner: await owner(c), p_id: id, p_device: db.state.deviceId, p_lease_ms: leaseMs });
    return { holder: r.holder || null, mine: !!r.mine, untilMs: Number(r.until_ms) || 0 };
  } catch { return null; }
}

let timer = 0, failures = 0, lastTry = 0;
const pushedHooks = new Set();
/** Called after a sync that sent changes (the device link uses it to tell the other device to fetch them now). */
export function onPushed(fn) { pushedHooks.add(fn); return () => pushedHooks.delete(fn); }
/** Asks for a sync shortly (changes made close together go in one). Also what the device link calls when the phone says "something changed". */
export function syncSoon(ms = 1500) { if (!code()) return; clearTimeout(timer); timer = setTimeout(() => runOnce(), ms); }
/**
 * While Atlas is running: look for the phone's changes every 20 seconds (a tiny request), at once when the internet
 * returns or the window comes to the front, and after the PC wakes from sleep (the clock jumps past the timer).
 */
export function keepFresh() {
  let tick = Date.now();
  setInterval(() => {
    const now = Date.now(), slept = now - tick > 60000; tick = now;
    if (!code()) return;
    if (slept) syncSoon(2000);
    else if (now - lastTry > 15000) runOnce();
  }, 20000);
  window.addEventListener('focus', () => syncSoon(500));
  window.addEventListener('online', () => syncSoon(1500));
}

let inflight = null;
/**
 * Syncs now. If a sync is already running, this one starts right after it (so a change made meanwhile goes up at
 * once, and "Sync Now" gets the answer of a sync that includes everything up to the tap).
 */
export async function runOnce() {
  if (!code()) return status.last;
  while (inflight) { try { await inflight; } catch {} }
  inflight = runInner();
  try { return await inflight; } finally { inflight = null; }
}

async function runInner() {
  const c = code(); if (!c) return status.last;
  status.running = true; lastTry = Date.now();
  try {
    let st = {}; try { st = JSON.parse((await host.read('sync-state.json')) || '{}'); } catch {}
    let first = st.cursor === undefined;
    let cursor = st.cursor || 0, sent = st.sent || {}, dirty = st.dirty || {};
    let recent = Array.isArray(st.recent) ? st.recent : [], seen = st.seen || {}, parked = st.parked || {};
    const devices = st.devices || {}, tooBig = st.tooBig || {};
    const me = db.state.deviceId, own = await owner(c);
    // this PC's data was started afresh since the last sync: what's missing wasn't deleted, it was lost. Take it back from the cloud.
    if (freshStart(st.device, me)) { cursor = 0; sent = {}; dirty = {}; first = true; recent = []; seen = {}; parked = {}; }
    // how far this PC's clock is from the cloud's: edit times are kept in cloud time, so a clock that runs fast can't
    // win every disagreement. Learnt from each send, and asked afresh when the clock was changed (or the PC slept).
    let skew = Number(st.skew) || 0;
    // (wall clock minus time since Atlas started: it only moves when the clock is changed)
    const base = Date.now() - performance.now();
    if (st.skew === undefined || st.base === undefined || Math.abs(base - Number(st.base)) > 5000) {
      skew = Number(await rpc('sync_time', {})) - Date.now();
    }
    status.skew = skew;
    let said = Number(st.said) || 0;
    const save = () => host.write('sync-state.json', JSON.stringify({ v: 3, cursor, recent, seen, parked, sent, dirty, device: me, skew, base, said, devices, tooBig, lastAt: status.at }));

    // 1. note what changed here, with the time it changed, before anything from the cloud is taken in
    const now = Date.now() + skew;
    if (presenceDue(said, now)) said = now;
    let version = ''; try { version = (await host.info()).version || ''; } catch {}
    const presence = { platform: 'windows', name: 'PC', app: version, proto: PROTOCOL, said };
    const { recs, skip } = await records(presence);
    const byKey = {}, local = {};
    for (const r of recs) { const k = r.kind + ':' + r.id; byKey[k] = r; local[k] = { fp: await fp(r.json), ownTime: ownTime(r.json) }; }
    await goneQueue; const gone = await readGone();
    // deletions were noted by this PC's clock: in cloud time, like every other edit (a slow clock's New Chat must not lose to the message)
    const goneAt = Object.fromEntries(Object.entries(gone).map(([k, at]) => [k, Math.min(now, (Number(at) || 0) + skew)]));
    stamp(sent, dirty, local, goneAt, now, first, skip, [...kinds].filter(([, k]) => k.absentMeansDeleted).map(([n]) => n));
    devices[me] = presence;
    if (!first) await save();        // an edit made offline keeps the time it was noticed, however long sending takes
    status.pendingOut = Object.keys(dirty).length > 0;

    // 2. take in what the phone changed, unless this PC's own unsent edit is newer
    let got = 0, blocked = false; const touched = new Set();
    const takeIn = async (rows, replay) => {
      const applied = [], recOf = {};
      for (const r of rows) {
        const k = r.kind + ':' + r.id, rev = Number(r.rev) || 0;
        if (!replay && alreadyTaken(seen[k] === undefined ? -1 : seen[k], rev)) continue;
        if (skip.includes(r.kind)) { blocked = true; break; }       // can't merge this kind right now: stop here, take it next time
        if (!known(r.kind)) {
          // a kind this version doesn't know (made by a newer Atlas): kept, and taken in once Atlas knows it
          if (!parked[k] || (Number(parked[k].rev) || 0) <= rev) parked[k] = r;
          seen[k] = rev; continue;
        }
        if (!replay) delete parked[k];                               // a newer copy came: the parked one is old news
        let rec = null; if (!r.deleted) { try { rec = JSON.parse(await decrypt(c, r.payload)); } catch { seen[k] = rev; continue; } }
        seen[k] = rev;
        if (r.kind === 'device') { if (r.id !== me) { if (rec) devices[r.id] = rec; else delete devices[r.id]; } continue; }
        const d = dirty[k];
        // the cloud already holds exactly what's here (a re-link, or our own record coming back): nothing to send or take
        if (rec && d && !d.del && d.fp === await fp(rec)) { sent[k] = d.fp; delete dirty[k]; continue; }
        if (r.device === me) { if (!d) { if (r.deleted) delete sent[k]; else sent[k] = await fp(rec); } continue; }   // our own, coming back
        if (d && mineWins(d.at, me, r.updated_ms, r.device)) continue;        // ours is newer: it goes up below
        delete dirty[k];
        try {
          const w = await applyRecord(r.kind, r.id, rec); if (w) touched.add(w); got++;
          if (r.deleted) delete sent[k]; else sent[k] = await fp(rec);
          if (rec) { applied.push(k); recOf[k] = rec; }
        } catch (e) {
          // couldn't be taken in just now: kept and tried again next time, not lost
          console.warn('sync: a record could not be taken in', r.kind, e && e.message);
          parked[k] = { ...r, retry: true }; delete sent[k];
        }
      }
      if (applied.length) {
        // this PC may keep a record its own way (field order, defaults): that is not an edit to send back
        const mine = {}; for (const r of (await records(presence)).recs) mine[r.kind + ':' + r.id] = r.json;
        for (const k of applied) {
          // the other device has no name set and this one has: that's news for it, not a way of writing the record (it goes up)
          if (kindOf(k) === 'profile' && mine[k] && mine[k].name && !recOf[k].name) continue;
          if (mine[k]) sent[k] = await fp(mine[k]);
        }
      }
    };
    // parked records this Atlas can take in now (it learnt the kind, or a merge that failed may work now)
    const ready = Object.keys(parked).filter((k) => known(kindOf(k)) && !skip.includes(kindOf(k)));
    if (ready.length) {
      const rows = ready.map((k) => parked[k]).sort((a, b) => (Number(a.rev) || 0) - (Number(b.rev) || 0));
      for (const k of ready) delete parked[k];
      await takeIn(rows, true);
    }
    let pos = cursor, top = cursor;
    while (!blocked) {
      const page = await rpc('sync_pull', { p_owner: own, p_since: pos, p_limit: 500 });
      if (!page.length) break;
      await takeIn(page, false);
      if (touched.has('state')) saveState(true);
      if (touched.has('chat')) saveChat(true);
      if (blocked) break;
      pos = Math.max(...page.map((r) => Number(r.rev) || 0)); top = Math.max(top, pos);
      await save();
      if (page.length < 500) break;
    }
    // where to pull from next time: a little behind the newest position, in case two sends were numbered out of order
    if (!blocked) {
      [cursor, recent] = advance(cursor, [...recent, [Date.now(), top]], Date.now());
      for (const k of Object.keys(seen)) if (seen[k] <= cursor) delete seen[k];
    }
    const pk = Object.keys(parked);
    if (pk.length > 3000) for (const k of pk.sort((a, b) => (Number(parked[a].rev) || 0) - (Number(parked[b].rev) || 0)).slice(0, pk.length - 3000)) delete parked[k];

    // 3. send what changed here, each with the time of its edit, in sends the cloud accepts
    const items = [];
    for (const k of Object.keys(dirty)) {
      const d = dirty[k], kind = kindOf(k), id = k.slice(kind.length + 1);
      if (d.del) { items.push([k, { kind, id, updated_ms: d.at, deleted: true, payload: '', device: me }]); continue; }
      const r = byKey[k]; if (!r) continue;
      let payload = await encrypt(c, JSON.stringify(r.json));
      if (payload.length > MAX_RECORD && kind === 'chat') {
        // a huge message: the phone gets it cut short (the whole of it stays here)
        payload = await encrypt(c, JSON.stringify({ ...r.json, text: String(r.json.text || '').slice(0, 600000) + '\n\n… (cut short: too long to sync)' }));
      }
      if (payload.length > MAX_RECORD) {
        // too big for the cloud: not sent (and not tried again until it changes); Me → Your Devices says so
        console.warn('sync: a record is too big to sync', kind); tooBig[k] = d.fp; sent[k] = d.fp; delete dirty[k]; continue;
      }
      delete tooBig[k];
      items.push([k, { kind, id, updated_ms: d.at, payload, device: me }]);
    }
    let put = 0, again = false;
    for (const batch of batches(items.map(([, x]) => x.payload.length + 200))) {
      const res = await rpc('sync_push2', { p_owner: own, p_items: batch.map((i) => items[i][1]) });
      if (res && res.now > 0) { skew = res.now - Date.now(); status.skew = skew; }
      const rejected = new Set(((res && res.rejected) || []).map((x) => x.kind + ':' + x.id));
      for (const i of batch) {
        const k = items[i][0], d = dirty[k]; if (!d) continue;
        // the cloud already had a newer edit (made on the phone a moment ago): ours is dropped, the phone's comes in next
        if (rejected.has(k)) { delete dirty[k]; again = true; continue; }
        if (d.del) delete sent[k]; else sent[k] = d.fp; delete dirty[k];
      }
      put += batch.length;
      await save();
    }
    for (const k of Object.keys(tooBig)) if (!byKey[k]) delete tooBig[k];
    status.at = Date.now();
    await save();
    await dropGone(Object.keys(gone).filter((k) => !dirty[k] && !skip.includes(kindOf(k))));
    status.pendingOut = Object.keys(dirty).length > 0; status.pending = Object.keys(dirty).length;
    status.others = devicesOf(devices, me); status.tooBig = Object.keys(tooBig).length; status.waiting = Object.keys(parked).length;
    failures = 0; status.problem = null;
    if (again) syncSoon(1500);
    if (put) for (const fn of pushedHooks) { try { fn(); } catch {} }   // the device link tells the phone to fetch them now
    status.last = 'Synced' + (got + put ? ` (${got} in, ${put} out)` : '');
    for (const w of touched) changed(w);
  } catch (e) {
    const p = e instanceof CloudError ? problem(e.status, e.body) : { kind: 'other', text: "Couldn't sync: " + (e.message || e), fix: null };
    status.problem = p;
    status.last = p.kind === 'offline' ? "Couldn't sync: offline" : p.text;
    // try again by itself: after 30 s, then 2, 5 and 15 minutes (and at once when the internet comes back); a sleeping
    // cloud isn't asked again so often (it won't wake by itself)
    failures++;
    const waits = p.kind === 'paused' ? [900000, 1800000, 3600000, 3600000] : [30000, 120000, 300000, 900000];
    clearTimeout(timer); timer = setTimeout(() => runOnce(), waits[Math.min(failures, 4) - 1]);
  } finally { status.running = false; changed('sync'); }
  return status.last;
}

/** After a restart: what the last sync knew (for Me → Your Devices before the first sync finishes). */
export async function loadStatus() {
  try {
    const st = JSON.parse((await host.read('sync-state.json')) || '{}');
    status.others = devicesOf(st.devices, db.state.deviceId); status.pending = Object.keys(st.dirty || {}).length;
    status.tooBig = Object.keys(st.tooBig || {}).length; status.waiting = Object.keys(st.parked || {}).length;
    if (!status.at) status.at = Number(st.lastAt) || 0; status.skew = Number(st.skew) || 0;
  } catch {}
}
