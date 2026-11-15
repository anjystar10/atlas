// The one place the Bots screens and group chats talk to the Atlas Bot v2 engine (docs/BOTS_V2.md §10).
// When the engine exports `crew` from botwire.js, everything here goes straight to it. Until then (and for any piece it
// doesn't have yet) a STUB built on today's engine stands in: bots = the specialists, missions = the jobs. Every stub is
// marked "STUB" so it's easy to find and drop.
import * as BW from './botwire.js';
import { normalizeBot, missionDefaults, usageToday, dayOf, budgetCheck, DEFAULT_BUDGET, defaultAccess, planProgress } from './crew.js';
import { FINAL } from './bots.js';

const engine = () => BW.crew || null;
const ext = () => BW.extras;
const jobs = () => BW.bots;

/** The built-in bot for missions started without a specific bot (the spec's "atlas"). */
export const ATLAS_BOT = Object.freeze({ id: 'atlas', v: 2, name: 'Atlas', role: 'Your chief of staff: takes on anything, hands parts to the right bot', icon: '✦', home: 'any',
  access: null, groups: [], triggers: [], budget: DEFAULT_BUDGET, notes: [], log: [], state: 'active', builtIn: true });

// ------------------------------------------------------------------ registries (other parts add to these)

const templates = [];
/** A bot template the Create screen offers: {id, name, icon, role, instructions, access, triggers, budget, home, about}. */
export function registerTemplate(t) { if (t && t.id && !templates.some((x) => x.id === t.id)) templates.push(t); }
export const listTemplates = () => (engine() && engine().templates ? engine().templates() : templates.slice());

const triggerSections = [];
/** A section on a bot's page for its triggers (the Triggers work draws here): fn(col, bot, k) → void. */
export function registerTriggerSection(fn) { if (typeof fn === 'function') triggerSections.push(fn); }
export const triggerSectionsFor = () => triggerSections.slice();

// ------------------------------------------------------------------ bots

export function bots() {
  const e = engine(); if (e) return e.bots();
  /* STUB */ return [ATLAS_BOT, ...ext().data.specialists.map((s) => normalizeBot(s, s.updatedAt || 0))];
}
export function bot(id) {
  const e = engine(); if (e) return e.bot(id);
  return id === 'atlas' ? ATLAS_BOT : bots().find((b) => b.id === id || b.name.toLowerCase() === String(id || '').toLowerCase()) || null;
}
export function createBot(def) {
  const e = engine(); if (e) return e.createBot(def);
  /* STUB: v1 store keeps name/role/instructions/notes only */
  return normalizeBot(ext().saveSpecialist({ ...def, access: def.access || defaultAccess(), v: 2 }), Date.now());
}
export function updateBot(id, patch) {
  const e = engine(); if (e) return e.updateBot(id, patch);
  const cur = ext().data.specialists.find((s) => s.id === id); if (!cur) return null;
  /* STUB */ return normalizeBot(ext().saveSpecialist({ ...cur, ...patch, id }), Date.now());
}
export function removeBot(id) { const e = engine(); if (e) return e.removeBot(id); /* STUB */ ext().removeSpecialist(id); }
export function setNotes(id, notes) { return updateBot(id, { notes }); }

// ------------------------------------------------------------------ missions

const topLevel = (j) => !j.parent;
export function missions(botId) {
  const e = engine(); const list = e ? e.missions(botId) : jobs().jobs.map(missionDefaults).filter((j) => !botId || j.bot === botId);
  return list.filter(topLevel).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}
export const mission = (id) => (engine() && engine().mission ? engine().mission(id) : jobs().job(id)) || null;
/** The mission and its agents as a tree: {mission, kids: [tree…]}. */
export function tree(id) {
  const e = engine(); if (e && e.tree) return e.tree(id);
  const seen = new Set();
  const walk = (mid) => { const m = jobs().job(mid); if (!m || seen.has(mid)) return null; seen.add(mid); missionDefaults(m); return { mission: m, kids: (m.children || []).map(walk).filter(Boolean) }; };
  return walk(id);
}
export function startMission(botId, goal, opts = {}) {
  const e = engine(); if (e) return e.startMission(botId, goal, opts);
  /* STUB: today's deploy (no dry run, no source) */
  return jobs().deploy(goal, opts.details || '', { specialist: botId && botId !== 'atlas' ? botId : null });
}
export const approve = (id, yes, always) => jobs().approve(id, yes, always);
export const answer = (id, text) => jobs().answer(id, text);
export const steer = (id, text) => jobs().steer(id, text);
export const stop = (id) => jobs().stop(id);
export const pause = (id) => jobs().pause(id);
export const resume = (id) => jobs().resume(id);
export const isFinal = (state) => FINAL.has(state);

// ------------------------------------------------------------------ budgets and the action log

/** Today's use against the bot's budget: {calls, ms, callsPerDay, minutesPerDay, ok, reason, pct}. */
export function usage(botId) {
  const e = engine(); const b = bot(botId) || ATLAS_BOT;
  let u;
  if (e && e.usage) u = e.usage(botId);
  else {
    /* STUB: counts today's jobs on this device */
    const day = dayOf(Date.now()); const since = new Date(); since.setHours(0, 0, 0, 0);
    const today = jobs().jobs.filter((j) => (j.bot || j.specialist || 'atlas') === botId && (j.updatedAt || 0) >= since.getTime());
    u = usageToday({ day, calls: today.reduce((s, j) => s + (j.toolCalls || 0), 0), ms: today.reduce((s, j) => s + (j.workMs || 0), 0) }, day);
  }
  const c = budgetCheck(b.budget, u);
  const pc = Math.max(u.calls / Math.max(1, b.budget.callsPerDay + (u.extraCalls || 0)), u.ms / Math.max(1, b.budget.minutesPerDay * 60000 + (u.extraMs || 0)));
  return { ...u, callsPerDay: b.budget.callsPerDay, minutesPerDay: b.budget.minutesPerDay, ok: c.ok, reason: c.reason, pct: Math.min(1, pc) };
}
export function giveMore(botId, more) { const e = engine(); if (e && e.giveMore) return e.giveMore(botId, more); /* STUB */ return null; }
/** The latest actions (audit trail), newest first: [{at, bot, mission, tool, what, dryRun, outcome}]. */
export function actions(botId, max = 60) {
  const e = engine(); if (e && e.actions) return e.actions(botId).slice(0, max);
  /* STUB: the steps of its missions */
  const out = [];
  for (const j of jobs().jobs) if (!botId || (j.bot || j.specialist || 'atlas') === botId) for (const s of (j.steps || []).slice(-20)) out.push({ at: j.updatedAt, bot: j.bot || j.specialist || 'atlas', mission: j.id, what: s });
  return out.sort((a, b) => (b.at || 0) - (a.at || 0)).slice(0, max);
}
export { planProgress };

// ------------------------------------------------------------------ events

/** Subscribe to the spec's events (or '*'). Falls back to the old change listeners. */
export function on(event, fn) {
  const e = engine(); if (e && e.on) return e.on(event, fn);
  /* STUB */
  if (event === 'bot.updated') return ext().onChange(() => fn({}));
  return jobs().onChange(() => fn({}));
}
