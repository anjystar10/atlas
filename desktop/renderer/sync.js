// Keeps this PC and the phone the same (the same protocol as Sync.kt on the phone): records are encrypted with
// the shared private link code (AES-256-GCM) before they leave; the cloud only stores scrambled text.
import { db, saveState, saveChat, changed, pref, setPref } from './store.js';
const host = window.atlasHost;
const URL_BASE = 'https://ggekbmptlgpjxaaejkns.supabase.co/rest/v1/rpc';
const KEY = 'sb_publishable_C03KposFeeF3oZ3z6c554g_91mwmbTj';
const ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
const CHAT_KEEP = 120, LOG_KEEP = 400;

export const status = { last: null, at: 0, running: false };

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
  setPref('syncCode', n); await host.write('sync-state.json', '{}');
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

function records() {
  const out = [];
  for (const t of db.state.tasks) out.push({ kind: 'task', id: t.id, json: t });
  for (const m of db.state.memories) out.push({ kind: 'memory', id: m.id, json: m });
  for (const l of db.state.log.slice(-LOG_KEEP)) out.push({ kind: 'log', id: l.id, json: l });
  out.push({ kind: 'profile', id: 'me', json: { name: db.state.settings.name || '' } });
  for (const m of db.chat.filter((m) => !m.pending && m.source !== 'error').slice(-CHAT_KEEP))
    out.push({ kind: 'chat', id: m.id, json: { id: m.id, user: !!m.user, text: m.text, source: m.source || '', at: m.at, steps: m.steps || [], files: (m.media || []).length || m.files || 0 } });
  return out;
}

async function rpc(fn, body) {
  const r = await host.fetch(`${URL_BASE}/${fn}`, { method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 30000 });
  if (!r.ok) throw new Error(r.status === 0 ? 'offline' : `the cloud answered ${r.status}`);
  return JSON.parse(r.text);
}

function applyRecord(kind, id, rec) {
  const arr = kind === 'task' ? db.state.tasks : kind === 'memory' ? db.state.memories : kind === 'log' ? db.state.log : null;
  if (kind === 'profile') { if (rec && typeof rec.name === 'string') db.state.settings.name = rec.name; return 'state'; }
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

let timer = 0;
export function syncSoon(ms = 4000) { if (!code()) return; clearTimeout(timer); timer = setTimeout(() => runOnce(), ms); }

export async function runOnce() {
  const c = code(); if (!c || status.running) return status.last;
  status.running = true;
  try {
    let st = {}; try { st = JSON.parse((await host.read('sync-state.json')) || '{}'); } catch {}
    let cursor = st.cursor || 0; const sent = st.sent || {};
    const me = db.state.deviceId, own = await owner(c);
    let got = 0; const touched = new Set();
    for (;;) {
      const page = await rpc('sync_pull', { p_owner: own, p_since: cursor, p_limit: 500 });
      if (!page.length) break;
      for (const r of page) {
        cursor = Math.max(cursor, r.rev);
        const k = r.kind + ':' + r.id;
        if (r.deleted) { if (r.device !== me) { const w = applyRecord(r.kind, r.id, null); if (w) touched.add(w); got++; } delete sent[k]; continue; }
        let rec; try { rec = JSON.parse(await decrypt(c, r.payload)); } catch { continue; }
        sent[k] = await fp(rec);
        if (r.device !== me) { const w = applyRecord(r.kind, r.id, rec); if (w) touched.add(w); got++; }
      }
      if (page.length < 500) break;
    }
    if (touched.has('state')) saveState(true);
    if (touched.has('chat')) saveChat(true);
    const now = Date.now(), items = [], present = new Set();
    for (const r of records()) {
      const k = r.kind + ':' + r.id; present.add(k);
      const f = await fp(r.json);
      if (sent[k] !== f) { items.push({ kind: r.kind, id: r.id, updated_ms: now, payload: await encrypt(c, JSON.stringify(r.json)), device: me }); sent[k] = f; }
    }
    for (const k of Object.keys(sent)) {
      if (present.has(k) || k.startsWith('chat:') || k.startsWith('log:')) continue;
      const [kind, ...rest] = k.split(':');
      items.push({ kind, id: rest.join(':'), updated_ms: now, deleted: true, payload: '', device: me }); delete sent[k];
    }
    for (let i = 0; i < items.length; i += 400) await rpc('sync_push', { p_owner: own, p_items: items.slice(i, i + 400) });
    await host.write('sync-state.json', JSON.stringify({ cursor, sent }));
    status.at = Date.now();
    status.last = 'Synced' + (got + items.length ? ` (${got} in, ${items.length} out)` : '');
    for (const w of touched) changed(w);
  } catch (e) {
    status.last = "Couldn't sync: " + (e.message || e);
  } finally { status.running = false; changed('sync'); }
  return status.last;
}
