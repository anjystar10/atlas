// Atlas Bot v2 triggers on the PC: the watcher. Every few seconds it looks at each standing bot's switched-on triggers
// (schedule, new email, price levels, new files) and, when one fires, starts that bot's mission through crew.fireTrigger.
// The rules (what counts as an event, de-duplication, rate limits, where a bot runs) are in trigrules.js and match the
// phone's TriggerRules.kt; this file only gathers the inputs and remembers state. Phone notifications are watched on
// the phone. No app imports: everything it touches comes in [deps], so the tests drive it with fakes.
import * as R from './trigrules.js';

/** How often each kind of trigger looks (ms). The clock is checked every tick. */
export const POLL = { schedule: 0, email: 120000, price: 60000, file: 15000 };
export const TICK_MS = 15000;

/** The last traded price in a trade_quote answer ("… bid 1, ask 2, last 3 …"), or null. */
export function lastPrice(out) {
  const m = /\blast\s+(-?\d+(?:\.\d+)?(?:e[-+]?\d+)?)/i.exec(String(out || ''));
  const n = m ? Number(m[1]) : NaN;
  return Number.isFinite(n) && n > 0 ? n : null;
}

/** A short fingerprint of a trigger's config: when the user changes it, its watcher starts again from a fresh look. */
export function configKey(t) {
  const c = { ...((t && t.config) || {}) };
  delete c.goal; delete c.perHour;   // changing the goal or the rate doesn't reset what was already seen
  return JSON.stringify([t && t.type, c]);
}

/**
 * deps: {
 *   bots() → standing bots, fire(botId, trigger, event) → mission | null, now(),
 *   load() → saved state | null, save(state),
 *   mailList(folder) → [{uid, id, from, subject, unread}] (throws when email isn't connected),
 *   quote(symbol, account) → number | null, listFiles(source, folder) → [{name, size, mtime, path}],
 *   claim(key) → true | false | null (null: no cloud, run it here), device: 'pc' | 'phone', log(msg)
 * }
 */
export function createTrigRun(deps) {
  const device = deps.device || 'pc';
  let state = { v: 1, trig: {}, dedupe: {}, rate: {} };
  let loaded = false, busy = false, dirty = false;
  const lastPoll = {};
  const errors = {};   // the latest problem per trigger, shown on the bot's page ("Couldn't read your email: …")

  async function init() {
    if (loaded) return;
    try { const s = await deps.load(); if (s && typeof s === 'object') state = { v: 1, trig: s.trig || {}, dedupe: s.dedupe || {}, rate: s.rate || {} }; } catch {}
    loaded = true;
  }
  const keyOf = (b, t) => `${b.id}|${t.id}`;

  /** The triggers this device watches now: bot active, trigger on, the type watchable here, the bot's home here. */
  function watched() {
    const out = [];
    for (const b of deps.bots() || []) {
      if (!b || b.state === 'paused') continue;
      if (R.where(b, device) !== 'here') continue;
      for (const raw of b.triggers || []) {
        if (!raw || raw.enabled === false || !R.TYPES[raw.type] || !R.watchable(raw.type, device)) continue;
        if (!(raw.type in POLL)) continue;   // thread, link, remote and manual are started by other parts
        out.push({ bot: b, t: R.cleanTrigger(raw) });
      }
    }
    return out;
  }

  /** Runs one event through de-duplication, the rate limit and the cloud claim, then starts the mission. */
  async function deliver(b, t, ev, now) {
    const key = R.eventKey(b.id, t.id, ev);
    const d = R.dedupe(state.dedupe, key, now); state.dedupe = d.state; dirty = true;
    if (!d.fresh) return null;
    const r = R.rateAllow(state.rate, keyOf(b, t), t.config.perHour || 12, now); state.rate = r.state; dirty = true;
    if (!r.ok) { errors[keyOf(b, t)] = 'Skipped an event: it fired too often (its hourly limit).'; return null; }
    // both devices may see the same email or price move: only one starts the mission
    let mine = null;
    try { mine = deps.claim ? await deps.claim(`trig:${R.claimKey(b.id, t.id, ev)}`) : null; } catch { mine = null; }
    if (mine === false) return null;
    try { return deps.fire(b.id, t, ev) || null; } catch (e) { errors[keyOf(b, t)] = String(e && e.message || e); return null; }
  }

  async function check(b, t, now) {
    const k = keyOf(b, t);
    let s = state.trig[k];
    const ck = configKey(t);
    if (!s || s.ck !== ck) s = state.trig[k] = { ck, st: null };
    const c = t.config;
    let events = [];
    switch (t.type) {
      case 'schedule': {
        if (!c.schedule) return [];
        const r = R.scheduleDue(s.st, c.schedule, now); s.st = r.state;
        if (r.fire != null) events = [{ type: 'schedule', key: `slot:${r.fire}`, title: `Scheduled run (${R.summary(t)})`, data: { due: new Date(r.fire).toISOString() } }];
        break;
      }
      case 'email': {
        const list = await deps.mailList(c.folder || 'INBOX');
        const r = R.emailEvents(s.st, list, c); s.st = r.state; events = r.events;
        break;
      }
      case 'price': {
        if (!c.symbol) return [];
        const p = await deps.quote(c.symbol, c.account);
        if (p == null) return [];
        const r = R.priceEvents(s.st, p, now, c); s.st = r.state; events = r.events;
        break;
      }
      case 'file': {
        const listing = await deps.listFiles(c.source, c.folder);
        const r = R.fileEvents(s.st, listing, now, c); s.st = r.state; events = r.events;
        break;
      }
      default: return [];
    }
    dirty = true;
    delete errors[k];
    return events;
  }

  /** One pass over every watched trigger. Returns the missions it started. */
  async function tick() {
    if (busy) return [];
    busy = true;
    const started = [];
    try {
      await init();
      const now = deps.now();
      const live = new Set();
      for (const { bot: b, t } of watched()) {
        const k = keyOf(b, t); live.add(k);
        const every = POLL[t.type];
        if (every && lastPoll[k] && now - lastPoll[k] < every) continue;
        lastPoll[k] = now;
        let events = [];
        try { events = await check(b, t, now); } catch (e) { errors[k] = String((e && e.message) || e).slice(0, 200); continue; }
        for (const ev of events) { const m = await deliver(b, t, ev, now); if (m) started.push(m); }
      }
      // forget watchers whose trigger is gone or off (switching it back on starts from a fresh look)
      for (const k of Object.keys(state.trig)) if (!live.has(k)) { delete state.trig[k]; dirty = true; }
      if (dirty) { dirty = false; try { await deps.save(state); } catch {} }
    } finally { busy = false; }
    return started;
  }

  /** The next time a schedule trigger fires (for the bot's page), or null. */
  function nextAt(botId, trigId) { const s = state.trig[`${botId}|${trigId}`]; return (s && s.st && s.st.nextAt) || null; }
  const problem = (botId, trigId) => errors[`${botId}|${trigId}`] || null;

  /** An event that came from elsewhere (the other device, a group chat): same de-duplication and limits. */
  async function external(botId, type, ev) {
    await init();
    const b = (deps.bots() || []).find((x) => x.id === botId);
    if (!b || b.state === 'paused') return null;
    const raw = (b.triggers || []).find((x) => x.enabled !== false && x.type === type);
    if (!raw) return null;
    const m = await deliver(b, R.cleanTrigger(raw), ev, deps.now());
    if (dirty) { dirty = false; try { await deps.save(state); } catch {} }
    return m;
  }

  return { init, tick, nextAt, problem, external, watched, _state: () => state };
}
