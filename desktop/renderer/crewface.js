// The one place the Bots screens and group chats talk to the Atlas Bot v2 engine (docs/BOTS_V2.md §10): botwire.js's
// `crew` API, the job engine (bots.js) and the store (botx.js), with the built-in "Atlas" bot added for missions that
// were started without a specific bot. Templates register themselves here (bottemplates.js).
import * as BW from './botwire.js';
import { normalizeBot, missionDefaults, usageToday, dayOf, budgetCheck, DEFAULT_BUDGET, planProgress } from './crew.js';
import { FINAL } from './bots.js';
import * as TR from './trigrules.js';

const engine = () => BW.crew;
const jobs = () => BW.bots;

/** The built-in bot for missions started without a specific bot (the spec's "atlas"). */
export const ATLAS_BOT = Object.freeze({ id: 'atlas', v: 2, name: 'Atlas', role: 'Your chief of staff: takes on anything, hands parts to the right bot', icon: '✦', home: 'any',
  access: null, groups: [], triggers: [], budget: DEFAULT_BUDGET, notes: [], log: [], state: 'active', builtIn: true });

// ------------------------------------------------------------------ templates (bottemplates.js registers them)

const templates = [];
/** A bot template the New Bot screen offers: {id, name, icon, role, instructions, access, triggers, budget, home, about}. */
export function registerTemplate(t) { if (t && t.id && !templates.some((x) => x.id === t.id)) templates.push(t); }
export const listTemplates = () => templates.slice();

// ------------------------------------------------------------------ bots

/** Atlas first, then the standing bots. */
export function bots() { return [ATLAS_BOT, ...engine().bots().map((s) => normalizeBot(s, s.updatedAt || 0, s.id))]; }
export function bot(id) {
  if (!id || id === 'atlas') return id ? ATLAS_BOT : null;
  const b = engine().bot(id);
  return b ? normalizeBot(b, b.updatedAt || 0, b.id) : null;
}
export const createBot = (def) => engine().createBot(def, def.createdBy === 'atlas' ? 'atlas' : 'user');
export const updateBot = (id, patch) => engine().updateBot(id, patch);
export const removeBot = (id) => engine().removeBot(id);
export const setNotes = (id, notes) => updateBot(id, { notes });

// ------------------------------------------------------------------ triggers

/** A bot's triggers, each cleaned and with what the page shows: summary, next run, the latest problem. */
export function triggers(botId) {
  const b = bot(botId); if (!b) return [];
  return (b.triggers || []).map((raw, i) => {
    const t = TR.cleanTrigger(raw, i);
    return { ...t, summary: TR.summary(t), nextAt: engine().nextTriggerAt(b.id, t.id), problem: engine().triggerProblem(b.id, t.id), watchable: { pc: TR.watchable(t.type, 'pc'), phone: TR.watchable(t.type, 'phone') } };
  });
}
export function saveTriggers(botId, list) { return updateBot(botId, { triggers: list.map((t, i) => TR.cleanTrigger(t, i)) }); }

// ------------------------------------------------------------------ missions

const topLevel = (j) => !j.parent;
export function missions(botId) {
  return engine().missions(botId).map((j) => missionDefaults(j)).filter(topLevel).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}
export const mission = (id) => engine().mission(id);
/** The mission and its agents as a tree: {mission, kids: [tree…]}. */
export function tree(id) {
  const t = engine().tree(id); if (!t) return null;
  const conv = (n) => ({ mission: n.job, kids: (n.agents || []).map(conv) });
  return conv(t);
}
export const startMission = (botId, goal, opts = {}) => engine().startMission(botId, goal, opts);
export const approve = (id, yes, always, scope = null) => jobs().approve(id, yes, always, scope);   // scope (autonomy): 'today' | 'bot' | 'plan'
export const answer = (id, text) => jobs().answer(id, text);
export const steer = (id, text) => jobs().steer(id, text);
export const stop = (id) => jobs().stop(id);
export const pause = (id) => jobs().pause(id);
export const resume = (id) => jobs().resume(id);
export const isFinal = (state) => FINAL.has(state);

// ------------------------------------------------------------------ budgets and the action log

/** Today's use against the bot's budget: {calls, ms, callsPerDay, minutesPerDay, ok, reason, pct}. */
export function usage(botId) {
  const b = bot(botId) || ATLAS_BOT;
  const u = { ...usageToday(null, dayOf(Date.now())), ...(engine().usage(botId) || {}) };
  const c = budgetCheck(b.budget, u);
  const pc = Math.max((u.calls || 0) / Math.max(1, b.budget.callsPerDay + (u.extraCalls || 0)), (u.ms || 0) / Math.max(1, b.budget.minutesPerDay * 60000 + (u.extraMs || 0)));
  return { ...u, calls: u.calls || 0, ms: u.ms || 0, callsPerDay: b.budget.callsPerDay, minutesPerDay: b.budget.minutesPerDay, ok: c.ok, reason: c.reason, pct: Math.min(1, pc) };
}
export const giveMore = (botId, more) => engine().giveMore(botId, more);
/** The latest actions (audit trail), newest first: [{at, bot, mission, tool, what, dryRun, outcome}]. */
export const actions = (botId, max = 60) => engine().actions(botId).slice(0, max);
export { planProgress };

// ------------------------------------------------------------------ events

/** Subscribe to the spec's events (or '*'); the job list's own changes count as mission updates. */
export function on(event, fn) {
  const offs = [engine().on(event, fn)];
  if (event === '*' || event === 'mission.updated') offs.push(jobs().onChange(() => fn(event === '*' ? 'mission.updated' : {}, {})));
  if (event === '*' || event === 'bot.updated') offs.push(BW.extras.onChange ? BW.extras.onChange(() => fn(event === '*' ? 'bot.updated' : {}, {})) : () => {});
  return () => offs.forEach((f) => { try { f && f(); } catch {} });
}
