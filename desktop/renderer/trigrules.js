// Atlas Bot v2 triggers: what starts a standing bot's mission without the user (docs/BOTS_V2.md §5).
// The rules only, with no app imports, so the phone (TriggerRules.kt) gives the same answers: both are checked against
// tools/trigger-fixtures.json. The runtime (trigrun.js) watches mail, prices, files and the clock and calls these.
//
// Built to grow: each type is one entry in TYPES (defaults, clean, summary). A new type is a new entry plus its watcher;
// configs carry `v` so an older Atlas keeps reading them.
import { parseSchedule, describeSchedule, nextRun } from './botx.js';

export const V = 1;
const clip = (s, n) => String(s == null ? '' : s).trim().slice(0, n);
const num = (x) => (x === '' || x == null || !Number.isFinite(Number(x)) ? null : Number(x));
const lc = (s) => String(s || '').toLowerCase();
/** "a, b ,c" or ["a","b"] → ["a","b","c"] (lower case, no blanks). */
export function words(x) { return (Array.isArray(x) ? x : String(x || '').split(',')).map((w) => lc(w).trim()).filter(Boolean).slice(0, 20); }

/** Every trigger type: what it's called, what it watches, and how to describe it. */
export const TYPES = {
  schedule: { label: 'On a schedule', where: 'any', clean: (c) => {
      // the schedule may come as the parsed object, as words ("every day at 7am", how templates write it) or as text
      const text = clip(c.text || (typeof c.schedule === 'string' ? c.schedule : ''), 120);
      const s = c.schedule && typeof c.schedule === 'object' ? c.schedule : c.parsed && typeof c.parsed === 'object' ? c.parsed : text ? parseSchedule(text) : null;
      return { text, schedule: s && !s.error ? s : null };
    },
    summary: (c) => (c.schedule ? describeSchedule(c.schedule) : 'On a schedule (not set yet)') },
  email: { label: 'When an email arrives', where: 'any', clean: (c) => ({ from: clip(c.from, 200), subject: clip(c.subject, 200), folder: clip(c.folder, 80) || 'INBOX', unreadOnly: c.unreadOnly !== false }),
    summary: (c) => 'When an email ' + [c.from ? `from ${c.from}` : '', c.subject ? `about “${c.subject}”` : ''].filter(Boolean).join(' ') + (c.from || c.subject ? ' arrives' : 'arrives') },
  price: { label: 'When a price moves', where: 'any', clean: (c) => ({ symbol: clip(c.symbol, 30).toUpperCase(), account: clip(c.account, 60), above: num(c.above), below: num(c.below), movePct: num(c.movePct), windowMin: Math.min(1440, Math.max(5, num(c.windowMin) || 60)) }),
    summary: (c) => { const p = []; if (c.above != null) p.push(`goes above ${c.above}`); if (c.below != null) p.push(`goes below ${c.below}`); if (c.movePct != null) p.push(`moves ${c.movePct}% within ${c.windowMin} min`); return `When ${c.symbol || 'a price'} ${p.join(' or ') || 'moves'}`; } },
  notification: { label: 'When a phone notification comes', where: 'phone', clean: (c) => ({ app: clip(c.app, 120), contains: clip(c.contains, 200) }),
    summary: (c) => `When ${c.app || 'an app'} sends a notification` + (c.contains ? ` mentioning “${c.contains}”` : '') },
  app: { label: 'When you open an app', where: 'phone', clean: (c) => ({ app: clip(c.app, 120) }), summary: (c) => `When you open ${c.app || 'an app'}` },
  file: { label: 'When a file appears', where: 'any', clean: (c) => ({ folder: clip(c.folder, 400), pattern: clip(c.pattern, 80) || '*', source: ['folder', 'downloads', 'screenshots'].includes(c.source) ? c.source : 'folder' }),
    summary: (c) => `When a new ${c.pattern && c.pattern !== '*' ? c.pattern + ' ' : ''}file appears in ${c.source === 'downloads' ? 'Downloads' : c.source === 'screenshots' ? 'Screenshots' : c.folder || 'a folder'}` },
  remote: { label: 'When your other device sends it', where: 'any', clean: (c) => ({ name: clip(c.name, 60) }), summary: (c) => `When your other device sends “${c.name || 'an event'}”` },
  thread: { label: 'When @mentioned in a group chat', where: 'any', clean: () => ({}), summary: () => 'When @mentioned in a group chat' },
  link: { label: 'When your other device sends work', where: 'any', clean: () => ({}), summary: () => 'When your other device sends work' },
  manual: { label: 'Only when you start it', where: 'any', clean: () => ({}), summary: () => 'Only when you start it' },
};
export const TYPE_IDS = Object.keys(TYPES);

/** A trigger in the v2 shape, its config cleaned for its type. Unknown types are kept (a newer Atlas made them) but never fire here. */
export function cleanTrigger(t, i = 0) {
  const type = clip(t && t.type, 30);
  const raw = t && t.config && typeof t.config === 'object' ? t.config : {};
  const def = TYPES[type];
  const config = def ? { v: V, ...def.clean(raw), goal: clip(raw.goal, 1000), perHour: Math.min(120, Math.max(1, num(raw.perHour) || 12)) } : raw;
  return { id: clip(t && t.id, 60) || `tr-${i}`, type, config, enabled: !(t && t.enabled === false) };
}

/** The trigger in plain words for the bot's settings ("When an email from my bank arrives"). */
export function summary(t) { const d = TYPES[t && t.type]; return d ? d.summary(t.config || {}) : `“${(t && t.type) || '?'}” (needs a newer Atlas)`; }

/** The mission goal: the trigger's own goal, then what happened (spec §5). */
export function goalFor(bot, t, ev) {
  const base = (t.config && t.config.goal) || (bot && bot.instructions ? 'Do your job for this:' : 'Handle this:');
  const data = ev.data && typeof ev.data === 'object' ? Object.entries(ev.data).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${k}: ${clip(typeof v === 'object' ? JSON.stringify(v) : v, 300)}`).join('\n') : '';
  return `${base}\n\nWhat happened: ${clip(ev.title, 300)}${data ? '\n' + data : ''}`;
}

/** Same event, same key, so one event never starts two missions (both devices may see it). */
export function eventKey(botId, trigId, ev) { return `${botId}|${trigId}|${clip(ev.key || ev.title, 200)}`; }
/**
 * The key both devices claim in the cloud before starting the mission. A price crossing is seen at a different moment on
 * each device, so its key has no time in it ([ev.claim]); the claim lasting 10 minutes keeps a later real crossing free.
 */
export function claimKey(botId, trigId, ev) { return eventKey(botId, trigId, ev.claim ? { key: ev.claim } : ev); }

// ------------------------------------------------------------------ de-duplication and rate limits (state is plain JSON)

export const DEDUPE_MS = 3 * 24 * 3600000;
/** {fresh, state}: whether [key] hasn't been seen in the last 3 days; remembers it. Keeps at most 2000 keys. */
export function dedupe(state, key, now) {
  const seen = { ...((state && state.seen) || {}) };
  for (const [k, at] of Object.entries(seen)) if (now - at > DEDUPE_MS) delete seen[k];
  const fresh = !(key in seen);
  seen[key] = now;
  const keys = Object.keys(seen);
  if (keys.length > 2000) keys.sort((a, b) => seen[a] - seen[b]).slice(0, keys.length - 2000).forEach((k) => delete seen[k]);
  return { fresh, state: { ...(state || {}), seen } };
}

/** {ok, state}: at most perHour missions an hour from one trigger, and at least a minute apart. */
export function rateAllow(state, trigKey, perHour, now) {
  const hits = ((state && state.hits && state.hits[trigKey]) || []).filter((at) => now - at < 3600000);
  const ok = hits.length < perHour && (!hits.length || now - hits[hits.length - 1] >= 60000);
  if (ok) hits.push(now);
  return { ok, state: { ...(state || {}), hits: { ...((state && state.hits) || {}), [trigKey]: hits } } };
}

// ------------------------------------------------------------------ where it runs

/** 'here' when this device ([device]: 'phone'|'pc') should run the bot's mission; 'queue' to leave it for its home device. */
export function where(bot, device) { const home = (bot && bot.home) || 'any'; return home === 'any' || home === device ? 'here' : 'queue'; }
/** Honest label for a bot whose triggers can only run on one device (shown in its settings). */
export function awakeLabel(bot) {
  const h = bot && bot.home;
  const ts = ((bot && bot.triggers) || []).filter((t) => t.enabled && t.type !== 'manual');
  if (!ts.length) return '';
  if (h === 'phone') return 'Runs when your phone is awake';
  if (h === 'pc') return 'Runs when your laptop is on and Atlas is open';
  return 'Runs when your phone or laptop is awake';
}
/** Whether a trigger of this type can be watched on [device] (phone notifications only exist on the phone). */
export function watchable(type, device) { const w = TYPES[type] && TYPES[type].where; return !!w && (w === 'any' || w === device); }

// ------------------------------------------------------------------ schedule

/** {fire: slot ms | null, state}: one firing per due slot, even after a long sleep (a missed run fires once, late). */
export function scheduleDue(state, schedule, now) {
  if (!schedule) return { fire: null, state: state || {} };
  const s = { ...(state || {}) };
  if (!s.nextAt) { s.nextAt = nextRun(schedule, now); return { fire: null, state: s }; }
  if (now < s.nextAt) return { fire: null, state: s };
  const slot = s.nextAt;
  s.nextAt = nextRun(schedule, now);
  s.lastAt = now;
  return { fire: slot, state: s };
}

// ------------------------------------------------------------------ email

/** messages: [{uid, from, subject, unread}] newest first or any order. The first look only sets the baseline (no flood of old mail). */
export function emailEvents(state, messages, cfg) {
  const s = { ...(state || {}) };
  const list = (messages || []).filter((m) => Number.isFinite(Number(m.uid)));
  const top = list.reduce((a, m) => Math.max(a, Number(m.uid)), 0);
  if (s.lastUid == null || s.folder !== cfg.folder) return { events: [], state: { lastUid: top, folder: cfg.folder } };
  const froms = words(cfg.from), subs = words(cfg.subject);
  const events = list.filter((m) => Number(m.uid) > s.lastUid)
    .filter((m) => !cfg.unreadOnly || m.unread !== false)
    .filter((m) => !froms.length || froms.some((f) => lc(m.from).includes(f)))
    .filter((m) => !subs.length || subs.some((w) => lc(m.subject).includes(w)))
    .sort((a, b) => Number(a.uid) - Number(b.uid))
    .map((m) => ({ type: 'email', key: `${cfg.folder}:${m.uid}`, title: `New email from ${clip(m.from, 120) || 'someone'}: ${clip(m.subject, 160) || '(no subject)'}`, data: { email_id: m.id || null, from: m.from || '', subject: m.subject || '' } }));
  return { events, state: { lastUid: Math.max(s.lastUid, top), folder: cfg.folder } };
}

// ------------------------------------------------------------------ price

const HYSTERESIS = 0.002;   // a level re-arms once the price is 0.2% back on the other side (no flapping)
/** {events, state} for one new price. Crossings only (the first look never fires); a % move against a reference that resets each window. */
export function priceEvents(state, price, now, cfg) {
  const p = Number(price);
  if (!Number.isFinite(p) || p <= 0) return { events: [], state: state || {} };
  const s = { armedAbove: true, armedBelow: true, ...(state || {}) };
  const events = [];
  const sym = cfg.symbol || 'the price';
  if (s.last == null) return { events, state: { ...s, last: p, ref: p, refAt: now, armedAbove: !(cfg.above != null && p >= cfg.above), armedBelow: !(cfg.below != null && p <= cfg.below) } };
  if (cfg.above != null) {
    if (s.armedAbove && s.last < cfg.above && p >= cfg.above) { events.push({ type: 'price', key: `above:${cfg.above}:${now}`, claim: `above:${cfg.above}`, title: `${sym} went above ${cfg.above} (now ${p})`, data: { symbol: cfg.symbol, price: p, level: cfg.above } }); s.armedAbove = false; }
    else if (!s.armedAbove && p < cfg.above * (1 - HYSTERESIS)) s.armedAbove = true;
  }
  if (cfg.below != null) {
    if (s.armedBelow && s.last > cfg.below && p <= cfg.below) { events.push({ type: 'price', key: `below:${cfg.below}:${now}`, claim: `below:${cfg.below}`, title: `${sym} went below ${cfg.below} (now ${p})`, data: { symbol: cfg.symbol, price: p, level: cfg.below } }); s.armedBelow = false; }
    else if (!s.armedBelow && p > cfg.below * (1 + HYSTERESIS)) s.armedBelow = true;
  }
  if (cfg.movePct != null && cfg.movePct > 0) {
    if (now - s.refAt > cfg.windowMin * 60000) { s.ref = s.last; s.refAt = now; }
    const pct = ((p - s.ref) / s.ref) * 100;
    if (Math.abs(pct) >= cfg.movePct) {
      events.push({ type: 'price', key: `move:${now}`, claim: 'move', title: `${sym} moved ${pct > 0 ? '+' : ''}${pct.toFixed(2)}% (from ${s.ref} to ${p})`, data: { symbol: cfg.symbol, price: p, from: s.ref, pct: Number(pct.toFixed(2)) } });
      s.ref = p; s.refAt = now;
    }
  }
  s.last = p;
  return { events, state: s };
}

// ------------------------------------------------------------------ notifications (phone)

/** n: {pkg, app, title, text}. App matches the package exactly or the app's name in part; "contains" is any of a comma list. */
export function notificationMatches(cfg, n) {
  if (!n) return false;
  const app = lc(cfg.app).trim();
  if (app && lc(n.pkg) !== app && !lc(n.app).includes(app)) return false;
  const ws = words(cfg.contains);
  const body = lc(`${n.title || ''} ${n.text || ''}`);
  return !ws.length || ws.some((w) => body.includes(w));
}
/** [at]: when it came. The same words again in a later hour are a new notification ("Mum: Call me" tomorrow); a re-post within the hour isn't. */
export function notificationEvent(n, at = 0) {
  return { type: 'notification', key: `${n.pkg}:${clip(n.title, 80)}:${clip(n.text, 120)}:${Math.floor((Number(at) || 0) / 3600000)}`, title: `${n.app || n.pkg}: ${clip(n.title, 120)}${n.text ? ' — ' + clip(n.text, 200) : ''}`, data: { app: n.app || n.pkg, title: n.title || '', text: n.text || '' } };
}

// ------------------------------------------------------------------ an app opening (phone)

/** o: {pkg, app}. Matches the package exactly or the app's name in part; an empty app never fires (it would fire on everything). */
export function appMatches(cfg, o) {
  const app = lc(cfg && cfg.app).trim();
  if (!o || !app) return false;
  return lc(o.pkg) === app || lc(o.app).includes(app);
}
/** One app opening, as an event. [minute] keeps the same opening from firing twice (de-duplication is per minute). */
export function appEvent(o, at) {
  const minute = Math.floor((Number(at) || 0) / 60000);
  return { type: 'app', key: `${o.pkg}:${minute}`, title: `You opened ${o.app || o.pkg}`, data: { app: o.app || o.pkg, pkg: o.pkg } };
}

// ------------------------------------------------------------------ files

/** "*.pdf", "report*", "*" → whether [name] matches (case-insensitive). */
export function globMatch(pattern, name) {
  const p = lc(pattern || '*').trim() || '*';
  const re = new RegExp('^' + p.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$');
  return re.test(lc(name));
}
export const SETTLE_MS = 3000;
/**
 * listing: [{name, size, mtime}]. A file fires once it's new (not there at the first look) and its size and time have
 * stopped changing for 3 s, so a download in progress isn't picked up half-written.
 */
export function fileEvents(state, listing, now, cfg) {
  const items = (listing || []).filter((f) => f && f.name && globMatch(cfg.pattern, f.name));
  if (!state || !state.base) return { events: [], state: { base: true, known: Object.fromEntries(items.map((f) => [f.name, { size: f.size, mtime: f.mtime, done: true, since: now }])) } };
  const known = { ...state.known };
  const events = [];
  for (const f of items) {
    const k = known[f.name];
    if (!k || k.size !== f.size || k.mtime !== f.mtime) { known[f.name] = { size: f.size, mtime: f.mtime, done: !!(k && k.done), since: now }; continue; }   // an old file being edited isn't new
    if (!k.done && now - k.since >= SETTLE_MS) {
      known[f.name] = { ...k, done: true };
      events.push({ type: 'file', key: `${f.name}:${f.size}:${f.mtime}`, title: `New file: ${f.name}`, data: { file: f.name, path: f.path || null, size: f.size } });
    }
  }
  for (const name of Object.keys(known)) if (!items.some((f) => f.name === name)) delete known[name];
  return { events, state: { base: true, known } };
}
