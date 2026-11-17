// "Share My AI Keys" (the same rules as KeyShare.kt on the phone): keys go to the other device inside the encrypted
// sync, scrambled with the private link code before they leave. Off unless turned on, on each device.
//
// One record per service: {added: {key: when}, removed: {fingerprint of key: when}}. The devices merge rather than
// overwrite each other, so neither can lose a key: a key stays while its newest "added" is later than its newest
// "removed". Adds and removes are noticed by comparing the pool with what was seen last time. Keys are never logged.
import { pref, setPref } from './store.js';
import * as brain from './brain.js';
import * as sync from './sync.js';

export const KIND = 'keys';
export const on = () => pref('shareKeys', false) === true;

const enc = new TextEncoder();
/** A key's fingerprint, for the "removed" list (the key itself isn't kept once it's removed). */
export async function fingerprint(key) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode('atlas-keyshare:' + key)));
  return [...d].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 32);
}
/** Both sides' adds and removes together: the newest time of each. */
export function merge(a, b) {
  const newest = (x, y) => { const o = {}; for (const m of [x || {}, y || {}]) for (const [k, t] of Object.entries(m)) if (!(k in o) || t > o[k]) o[k] = t; return o; };
  return { added: newest(a.added, b.added), removed: newest(a.removed, b.removed) };
}
/** The keys that are in: added later than they were (last) removed. */
export async function present(rec) {
  const out = [];
  for (const [k, t] of Object.entries(rec.added || {})) { const r = (rec.removed || {})[await fingerprint(k)]; if (r === undefined || t > r) out.push(k); }
  return out.sort();
}
/** Notes adds and removes since last time: the pool now ([now]) against what was seen last ([seen]). */
export async function observe(rec, seen, now, at) {
  const added = { ...(rec.added || {}) }, removed = { ...(rec.removed || {}) }, before = new Set(seen);
  // new here (added, or added again after a removal): stamped now
  for (const k of now) if (!before.has(k) || !(k in added)) added[k] = at;
  // gone from here since last time: removed now (only its fingerprint is kept, not the key)
  for (const k of seen) if (!now.includes(k)) { removed[await fingerprint(k)] = at; delete added[k]; }
  return { added, removed };
}

// the AI services, and the voice-only ones whose keys live in the same pool (ElevenLabs)
const services = () => [...brain.PROVIDERS.filter((p) => !p.local).map((p) => p.id), 'elevenlabs'];
const pool = (id) => brain.keys(id).map((e) => e.key);
let queue = Promise.resolve();
const serial = (fn) => (queue = queue.then(fn, fn));

async function list() {
  if (!on()) return {};
  return serial(async () => {
    const meta = { ...pref('keyshareMeta', {}) }, at = Date.now() + (sync.status.skew || 0), out = {};
    for (const id of services()) {
      const m = meta[id] || {}, seen = Array.isArray(m.seen) ? m.seen : [], now = pool(id);
      if (!now.length && !seen.length && !m.added) continue;
      const rec = await observe(m, seen, now, at);
      meta[id] = { ...rec, seen: now }; out[id] = rec;
    }
    setPref('keyshareMeta', meta);
    return out;
  });
}

/** Takes in the other device's record for a service: merged with ours, and the pool made to match. */
async function take(id, rec) {
  if (id !== 'elevenlabs' && !brain.PROVIDERS.find((p) => p.id === id && !p.local)) return;
  const meta = { ...pref('keyshareMeta', {}) }, m = meta[id] || {}, seen = Array.isArray(m.seen) ? m.seen : [];
  const mine = await observe(m, seen, pool(id), Date.now() + (sync.status.skew || 0));
  const merged = merge(mine, rec), want = new Set(await present(merged)), have = pool(id);
  for (const k of have) if (!want.has(k)) brain.removeKey(id, k);
  for (const k of want) if (!have.includes(k)) brain.addKey(id, k);
  meta[id] = { ...merged, seen: pool(id) }; setPref('keyshareMeta', meta);
}

/** Sharing is off here: the other device's keys wait, unused, until it's turned on. */
function stash(id, rec) { const inbox = { ...pref('keyshareInbox', {}) }; inbox[id] = inbox[id] ? merge(inbox[id], rec) : rec; setPref('keyshareInbox', inbox); }

/** Turns sharing on or off here. On: keys that came from the other device meanwhile are taken in, and ours go up. */
export async function set(enabled) {
  setPref('shareKeys', !!enabled);
  if (enabled) {
    const inbox = pref('keyshareInbox', {});
    for (const [id, rec] of Object.entries(inbox)) await serial(() => take(id, rec));
    setPref('keyshareInbox', {});
  }
  sync.syncSoon(500);
}

export function register() {
  sync.registerKind(KIND, {
    list,
    apply: (id, rec) => { if (!rec) return; return on() ? serial(() => take(id, rec)) : stash(id, rec); },
    absentMeansDeleted: false,
  });
}
