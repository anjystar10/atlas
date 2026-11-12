// Atlas Bot's extras on the PC: skills (reusable ways of doing a job), routines (jobs on a schedule), specialist bots
// (a small team Atlas and bots can hand work to), and the approval rules (ask / always allow / never, per kind of
// action, plus standing rules in the user's own words). Same rules as the phone (BotExtras.kt).
//
// Like bots.js this file imports nothing from the app, so all of it runs in Node tests. botwire.js connects it.

// ------------------------------------------------------------------ approvals: kinds of risky action

export const CATEGORIES = [
  { id: 'message', label: 'Send a message or email' },
  { id: 'publish', label: 'Publish or post something' },
  { id: 'purchase', label: 'Buy or pay' },
  { id: 'delete', label: 'Delete or overwrite' },
  { id: 'account', label: 'Change an account, permission or security setting' },
  { id: 'terms', label: 'Accept terms or sign up' },
  { id: 'command', label: 'Run a command that changes things' },
  { id: 'other', label: 'Anything else that needs an OK' },
];
export const CATEGORY_IDS = CATEGORIES.map((c) => c.id);
export const categoryLabel = (id) => (CATEGORIES.find((c) => c.id === id) || CATEGORIES[CATEGORIES.length - 1]).label;

/** The kind of risky action a described action is, from its wording (for request_approval and tapped buttons). */
export function guessCategory(text) {
  const t = String(text || '').toLowerCase();
  if (/\b(buy|purchase|pay|payment|checkout|check out|order|subscribe|donate|transfer money|book(ing)?|ticket)\b/.test(t)) return 'purchase';
  if (/\b(delete|remove|erase|wipe|overwrite|discard|clear|uninstall|trash|empty)\b/.test(t)) return 'delete';
  if (/\b(password|permission|security|account setting|settings|two[- ]factor|2fa|log ?out|sign ?out|privacy|role|access)\b/.test(t)) return 'account';
  if (/\b(accept|agree|terms|sign ?up|register|create an account|consent)\b/.test(t)) return 'terms';
  if (/\b(post|publish|tweet|share publicly|upload|comment|reply publicly|go live)\b/.test(t)) return 'publish';
  if (/\b(send|email|e-mail|message|text|sms|whatsapp|dm|call|invite|reply|forward)\b/.test(t)) return 'message';
  if (/\b(command|script|install|run)\b/.test(t)) return 'command';
  return 'other';
}
/** The kind of risky action a tool call is (only asked for calls that need an OK). */
/** The kind of an action from all it says (its label, its form, the page around it): the strongest risk in the text wins. */
export function strongestCategory(text, tool, a = {}) { const g = guessCategory(text); return g !== 'other' || !tool ? g : categoryFor(tool, a || {}); }
export function categoryFor(tool, a = {}) {
  switch (tool) {
    case 'run_command': return 'command';
    case 'write_file': case 'forget': return 'delete';
    case 'update_reminder': return 'delete';
    case 'open_link': return 'message';
    case 'press_keys': case 'type_text': case 'tap': case 'click': return guessCategory(a.label || a.text || '') === 'other' ? 'message' : guessCategory(a.label || a.text || '');
    default: return connectionCategory(tool, a) || 'other';
  }
}
/**
 * >>> connections: the kind of an MCP tool (mcp__server__tool) or web API call (call_api), from the tool's own name
 * or the HTTP method and path, so the user's ask / always / never choices cover them too. null when it isn't one.
 */
export function connectionCategory(tool, a = {}) {
  if (tool === 'call_api') {
    const m = String(a.method || 'GET').toUpperCase();
    if (m === 'DELETE') return 'delete';
    const words = String(a.path || '').replace(/[/_.?=&-]+/g, ' ');
    const g = guessCategory(words + ' ' + words.replace(/(\w{3,})s\b/g, '$1'));   // "/orders" counts as "order"
    return g === 'command' ? 'other' : g;
  }
  if (!String(tool).startsWith('mcp__')) return null;
  const g = guessCategory(String(tool).split('__').slice(2).join(' ').replace(/_+/g, ' '));
  return g === 'command' ? 'other' : g;
}
/**
 * >>> projects: the kind of a project action that needs an OK, from the tool and its command (never from names the
 * bot chose, so a project called "Order Tracker" can't make a command count as something the user always allows).
 */
export function projectCategory(tool, a = {}) {
  if (tool === 'project_delete') return 'delete';
  const c = String(a.command || '');
  if (/\b(git\s+push|publish|deploy|vercel|netlify|firebase|wrangler|twine|nuget\s+push|surge|gh-pages)\b/i.test(c)) return 'publish';
  if (/\b(sudo|runas|--global)\b|config\s+--global/i.test(c)) return 'account';
  return 'command';
}
/** 'ask' (the default), 'allow' (always allowed by the user) or 'deny' (never). */
export function modeFor(policy, category) {
  const m = policy && policy.modes ? policy.modes[category] : null;
  return m === 'allow' || m === 'deny' ? m : 'ask';
}
export const emptyPolicy = () => ({ modes: {}, rules: [], updatedAt: 0 });

// ------------------------------------------------------------------ routines: schedules in plain words

export const MIN_INTERVAL_MIN = 5, MAX_ROUTINES = 50;
const DAY_NAMES = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
const DAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const PARTS = { morning: '08:00', noon: '12:00', midday: '12:00', afternoon: '14:00', evening: '18:00', night: '21:00', tonight: '21:00', midnight: '00:00' };
const pad = (n) => String(n).padStart(2, '0');
const isoDow = (d) => ((d.getDay() + 6) % 7) + 1;   // Monday = 1 … Sunday = 7

function timeIn(t) {
  let m = /\b(?:at\s+)?(\d{1,2})[:.](\d{2})\s*(am|pm)?\b/.exec(t);
  if (!m) m = /\b(?:at\s+)?(\d{1,2})()\s*(am|pm)\b/.exec(t);
  if (!m) m = /\bat\s+(\d{1,2})()()\b/.exec(t);
  if (m) {
    let hh = Number(m[1]); const mm = Number(m[2] || 0);
    if (m[3] === 'pm' && hh < 12) hh += 12;
    if (m[3] === 'am' && hh === 12) hh = 0;
    if (hh > 23 || mm > 59) return null;
    return `${pad(hh)}:${pad(mm)}`;
  }
  for (const [w, v] of Object.entries(PARTS)) if (new RegExp(`\\b${w}\\b`).test(t)) return v;
  return null;
}

/**
 * "every weekday at 8am", "every 2 hours", "every Sunday evening", "daily at 7:30", "every month on the 1st at 9am"
 * → {kind:'interval', everyMin} | {kind:'daily', time:'HH:MM', days:[1..7]} | {kind:'monthly', day, time}; null when
 * it isn't a schedule. {error} when it is one but not allowed (too often).
 */
export function parseSchedule(text, now = Date.now()) {
  const t = ' ' + String(text || '').toLowerCase().replace(/[,;]/g, ' ').replace(/\s+/g, ' ').trim() + ' ';
  if (!t.trim()) return null;
  // every N minutes / hours
  let m = /\b(?:every|each)\s+(\d+)\s*(minutes?|mins?|hours?|hrs?)\b/.exec(t);
  if (m || /\bhourly\b|\bevery hour\b|\beach hour\b/.test(t) || /\bevery half[- ]hour\b|\bevery half an hour\b/.test(t)) {
    const every = m ? Number(m[1]) * (/^h/.test(m[2]) ? 60 : 1) : /half/.test(t) ? 30 : 60;
    if (every < MIN_INTERVAL_MIN) return { error: `Routines can run every ${MIN_INTERVAL_MIN} minutes at most.` };
    if (every > 7 * 24 * 60) return { error: 'For something that rare, name the day instead (like "every Monday at 9am").' };
    return { kind: 'interval', everyMin: every };
  }
  // every month on the 3rd
  if (/\b(every|each) month\b|\bmonthly\b/.test(t)) {
    m = /\bon the (\d{1,2})(?:st|nd|rd|th)?\b|\b(\d{1,2})(?:st|nd|rd|th)\b/.exec(t);
    const day = m ? Number(m[1] || m[2]) : 1;
    if (day < 1 || day > 31) return null;
    const rest = m ? t.replace(m[0], ' ') : t;
    return { kind: 'monthly', day, time: timeIn(rest) || '09:00' };
  }
  const days = new Set();
  if (/\bweekdays?\b|\bwork ?days?\b/.test(t)) [1, 2, 3, 4, 5].forEach((d) => days.add(d));
  if (/\bweekends?\b/.test(t)) [6, 7].forEach((d) => days.add(d));
  DAY_NAMES.forEach((n, i) => { if (new RegExp(`\\b(${n}|${n.slice(0, 3)}|${n.slice(0, 4)})s?\\b`).test(t)) days.add(i + 1); });
  const everyDay = /\b(every|each) (day|morning|evening|night|afternoon)\b|\bdaily\b|\bevery ?day\b/.test(t);
  const weekly = /\bweekly\b|\b(every|each) week\b/.test(t);
  if (!days.size && !everyDay && !weekly) return null;
  if (days.size && !/\b(every|each|weekly|on|weekdays?|weekends?)\b/.test(t) && !DAY_NAMES.some((n) => new RegExp(`\\b${n}s\\b`).test(t))) return null;
  if (!days.size && weekly) days.add(isoDow(new Date(now)));
  return { kind: 'daily', time: timeIn(t) || '09:00', days: days.size && days.size < 7 ? [...days].sort((a, b) => a - b) : [] };
}

export function describeSchedule(s) {
  if (!s) return '';
  if (s.kind === 'interval') return s.everyMin % 60 === 0 ? (s.everyMin === 60 ? 'Every hour' : `Every ${s.everyMin / 60} hours`) : `Every ${s.everyMin} minutes`;
  if (s.kind === 'monthly') { const d = s.day; const suf = d % 10 === 1 && d !== 11 ? 'st' : d % 10 === 2 && d !== 12 ? 'nd' : d % 10 === 3 && d !== 13 ? 'rd' : 'th'; return `Every month on the ${d}${suf} at ${s.time}`; }
  const d = s.days || [];
  const which = !d.length || d.length === 7 ? 'Every day' : d.join() === '1,2,3,4,5' ? 'Every weekday' : d.join() === '6,7' ? 'Every weekend' : 'Every ' + d.map((x) => DAY_SHORT[x - 1]).join(', ');
  return `${which} at ${s.time}`;
}

/** The next time [s] is due strictly after [after] (ms), in this device's own time zone. */
export function nextRun(s, after) {
  if (!s) return 0;
  if (s.kind === 'interval') return after + Math.max(MIN_INTERVAL_MIN, s.everyMin) * 60000;
  const [hh, mm] = String(s.time || '09:00').split(':').map(Number);
  const a = new Date(after);
  if (s.kind === 'monthly') {
    for (let i = 0; i < 14; i++) {
      const first = new Date(a.getFullYear(), a.getMonth() + i, 1);
      const last = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
      const d = new Date(first.getFullYear(), first.getMonth(), Math.min(s.day, last), hh, mm, 0, 0);
      if (d.getTime() > after) return d.getTime();
    }
    return 0;
  }
  for (let i = 0; i < 9; i++) {
    const d = new Date(a.getFullYear(), a.getMonth(), a.getDate() + i, hh, mm, 0, 0);
    if (d.getTime() > after && (!s.days || !s.days.length || s.days.includes(isoDow(d)))) return d.getTime();
  }
  return 0;
}

// ------------------------------------------------------------------ skills

const clip = (s, n) => String(s || '').trim().slice(0, n);
export function cleanSkill(x, now, id) {
  return { id: x.id || id, name: clip(x.name, 60) || 'Untitled skill', when: clip(x.when, 300), inputs: clip(x.inputs, 400), steps: clip(x.steps, 3000), checks: clip(x.checks, 600),
    output: clip(x.output, 600), permissions: clip(x.permissions, 400), updatedAt: now };
}
/** One line per skill, for a bot's (or Atlas's) instructions: enough to know when to load one. */
export function skillsIndex(skills) {
  if (!skills.length) return '';
  return 'Skills the user has taught you (load one with use_skill before doing that kind of job, and follow it):\n' + skills.slice(0, 40).map((s) => `- ${s.name}: ${s.when || 'no description'}`).join('\n');
}
export function skillText(s) {
  const part = (title, v) => (v ? `\n${title}:\n${v}\n` : '');
  return `Skill: ${s.name}\n` + part('When to use it', s.when) + part('What you need first', s.inputs) + part('Steps', s.steps) + part('Checks before finishing', s.checks) + part('What the result should look like', s.output) + part('Needs the user\'s OK', s.permissions);
}
export const findByName = (list, name) => {
  const n = String(name || '').trim().toLowerCase(); if (!n) return null;
  return list.find((s) => s.name.toLowerCase() === n) || list.find((s) => s.id === name) || list.find((s) => s.name.toLowerCase().includes(n) || n.includes(s.name.toLowerCase())) || null;
};
/** A first draft of a skill from a finished job's journal, for the user to review and edit. */
export function draftSkill(job) {
  const plan = (job.plan || []).map((p) => p.text).filter(Boolean);
  const did = (job.steps || []).filter((s) => !/^(Made a plan|Updated the plan|Waiting|You |Asked you|Switched to)/.test(s));
  const steps = (plan.length ? plan : did.slice(0, 12)).map((s, i) => `${i + 1}. ${s}`).join('\n');
  const asked = (job.steps || []).filter((s) => /^(You approved|Waiting for your OK): /.test(s)).map((s) => s.replace(/^[^:]+: /, ''));
  const heads = String(job.report || '').split('\n').filter((l) => /^#{1,3}\s/.test(l)).map((l) => l.replace(/^#+\s*/, '')).slice(0, 8);
  return { name: clip(job.goal, 60), when: clip('When asked to: ' + job.goal, 300), inputs: clip(job.details, 400), steps,
    checks: 'Check the result does the whole job, and that every number and claim comes from something read or done.',
    output: heads.length ? 'A report in neat Markdown with these sections: ' + heads.join('; ') : 'A short report in neat Markdown.',
    permissions: [...new Set(asked)].slice(0, 6).join('\n') };
}

// ------------------------------------------------------------------ specialist bots

export const TOOL_GROUPS = [
  { id: 'web', label: 'Search and read the web', tools: ['web_search', 'read_page'] },
  { id: 'device', label: 'Use this device (apps, files, screen)', tools: ['open_app', 'list_apps', 'open_link', 'open_path', 'list_files', 'read_file', 'write_file', 'run_command', 'look_at_screen', 'click', 'type_text', 'press_keys', 'scroll',
    'read_screen', 'tap', 'swipe', 'go_back', 'go_home', 'wait'] },
  { id: 'log', label: 'Your Log, reminders and memory', tools: ['set_reminder', 'update_reminder', 'remember', 'forget', 'read_log'] },
];
const GROUPED = new Set(TOOL_GROUPS.flatMap((g) => g.tools));
/** Whether a specialist may use [tool]: no groups chosen = everything; tools outside the groups are always allowed. */
export function toolAllowed(sp, tool) {
  if (!sp || !sp.groups || !sp.groups.length || !GROUPED.has(tool)) return true;
  return TOOL_GROUPS.some((g) => sp.groups.includes(g.id) && g.tools.includes(tool));
}
export function cleanSpecialist(x, now, id) {
  return { id: x.id || id, name: clip(x.name, 40) || 'Helper Bot', role: clip(x.role, 200), instructions: clip(x.instructions, 2000),
    groups: (x.groups || []).filter((g) => TOOL_GROUPS.some((t) => t.id === g)), notes: (x.notes || []).map((n) => clip(n, 400)).filter(Boolean).slice(-40), updatedAt: now };
}
export const DEFAULT_SPECIALISTS = [
  { id: 'sp-research', name: 'Research Bot', role: 'Finds things out and backs them with sources', instructions: 'Search widely, read the best primary sources, cross-check key facts in two places, and report with links. Say plainly what you could not verify.', groups: ['web'] },
  { id: 'sp-planner', name: 'Planner Bot', role: 'Turns goals into schedules and step-by-step plans', instructions: 'Break the goal into clear steps with realistic timings. Check the Log for what is already planned and avoid clashes. Offer to log reminders rather than logging many without being asked.', groups: ['web', 'log'] },
  { id: 'sp-developer', name: 'Developer Bot', role: 'Programming, debugging and technical work', instructions: 'Read before you change. Prefer small, safe steps and check each one worked: code is not done until it has run and its tests pass. Building something new happens on the laptop, in a project. Explain technical findings in plain words.', groups: [] },
  { id: 'sp-finance', name: 'Finance Bot', role: 'Numbers, budgets, prices and comparisons', instructions: 'Show the figures and where each came from. Recheck every sum. Never move money or buy anything; lay out the options and let the user decide.', groups: ['web', 'log'] },
];
export function specialistPrompt(sp) {
  if (!sp) return '';
  let s = `\n\nYou are working as ${sp.name}${sp.role ? ` (${sp.role})` : ''}, one of the user's specialist bots.`;
  if (sp.instructions) s += `\nYour standing instructions: ${sp.instructions}`;
  if (sp.notes && sp.notes.length) s += `\nYour own notes from earlier jobs (keep what's still true in mind; fix them with bot_remember / bot_forget):\n` + sp.notes.map((n) => '- ' + n).join('\n');
  return s;
}
export function teamIndex(list) {
  if (!list.length) return '';
  return 'Specialist bots you can hand part of the work to (delegate):\n' + list.map((s) => `- ${s.name}: ${s.role || 'general helper'}`).join('\n');
}
export function rulesPrompt(policy) {
  const out = [];
  const rules = (policy && policy.rules) || [];
  if (rules.length) out.push("The user's standing rules (always follow them):\n" + rules.map((r) => '- ' + r).join('\n'));
  const never = CATEGORY_IDS.filter((c) => modeFor(policy, c) === 'deny');
  if (never.length) out.push('Never do these, the user has ruled them out: ' + never.map((c) => categoryLabel(c).toLowerCase()).join('; ') + '.');
  return out.join('\n');
}

// ------------------------------------------------------------------ the store (botx.json) and the routine clock

/**
 * deps: load(): Promise<string|null>, save(text), now(), uid(), deviceId(), setTimer(fn, ms), clearTimer(t),
 *       fire(routine) → job | null   (sends the bot; called when a routine is due),
 *       claim?(routine, due): Promise<boolean>  (true = this device runs it; without it the owning device does),
 *       changed?(kind)   ('skill' | 'routine' | 'specialist' | 'policy': after a local edit, so it can sync)
 */
export function createExtras(deps) {
  const now = deps.now || Date.now;
  const data = { skills: [], routines: [], specialists: [], policy: emptyPolicy(), seeded: false };
  const listeners = new Set();
  let loaded = false, timer = null, saving = Promise.resolve();
  const changed = (kind) => { if (kind && deps.changed) { try { deps.changed(kind); } catch (e) { console.error(e); } } for (const f of [...listeners]) { try { f(); } catch (e) { console.error(e); } } };
  const persist = () => { const text = JSON.stringify(data); saving = saving.then(() => deps.save(text)).catch((e) => console.error('botx: save failed', e)); return saving; };

  async function init() {
    if (loaded) return;
    loaded = true;
    try { const t = await deps.load(); if (t) { const d = JSON.parse(t); for (const k of ['skills', 'routines', 'specialists']) if (Array.isArray(d[k])) data[k] = d[k]; if (d.policy) data.policy = { ...emptyPolicy(), ...d.policy }; data.seeded = !!d.seeded; } } catch (e) { console.error('botx: unreadable', e); }
    if (!data.seeded) { data.seeded = true; for (const s of DEFAULT_SPECIALISTS) if (!data.specialists.some((x) => x.id === s.id)) data.specialists.push(cleanSpecialist(s, 0, s.id)); }
    await persist(); tick();
  }

  // skills
  function saveSkill(x) {
    const old = x.id ? data.skills.find((s) => s.id === x.id) : data.skills.find((s) => s.name.toLowerCase() === clip(x.name, 60).toLowerCase());
    const s = cleanSkill({ ...(old || {}), ...x, id: old ? old.id : x.id }, now(), deps.uid());
    if (old) Object.assign(old, s); else data.skills.push(s);
    persist(); changed('skill'); return old || s;
  }
  function removeSkill(id) { const i = data.skills.findIndex((s) => s.id === id); if (i >= 0) { data.skills.splice(i, 1); persist(); changed('skill'); } }

  // specialists
  function saveSpecialist(x) {
    const old = x.id ? data.specialists.find((s) => s.id === x.id) : null;
    const s = cleanSpecialist({ ...(old || {}), ...x }, now(), deps.uid());
    if (old) Object.assign(old, s); else data.specialists.push(s);
    persist(); changed('specialist'); return old || s;
  }
  function removeSpecialist(id) { const i = data.specialists.findIndex((s) => s.id === id); if (i >= 0) { data.specialists.splice(i, 1); persist(); changed('specialist'); } }
  function specialistNote(id, text, forget = false) {
    const sp = data.specialists.find((s) => s.id === id); const t = clip(text, 400); if (!sp || !t) return 'Error: nothing to save.';
    if (forget) {
      const i = sp.notes.findIndex((n) => n.toLowerCase().includes(t.toLowerCase()) || t.toLowerCase().includes(n.toLowerCase()));
      if (i < 0) return 'No note like that.';
      sp.notes.splice(i, 1);
    } else { if (!sp.notes.includes(t)) sp.notes.push(t); while (sp.notes.length > 40) sp.notes.shift(); }
    sp.updatedAt = now(); persist(); changed('specialist'); return forget ? 'Forgotten.' : 'Noted for next time.';
  }

  // policy
  function setMode(category, mode) {
    if (!CATEGORY_IDS.includes(category)) return;
    if (mode === 'allow' || mode === 'deny') data.policy.modes[category] = mode; else delete data.policy.modes[category];
    data.policy.updatedAt = now(); persist(); changed('policy');
  }
  function addRule(text) { const t = clip(text, 200); if (!t || data.policy.rules.includes(t)) return; data.policy.rules.push(t); while (data.policy.rules.length > 20) data.policy.rules.shift(); data.policy.updatedAt = now(); persist(); changed('policy'); }
  function removeRule(text) { const i = data.policy.rules.indexOf(text); if (i >= 0) { data.policy.rules.splice(i, 1); data.policy.updatedAt = now(); persist(); changed('policy'); } }

  // routines
  /** {routine} or {error}. [x]: {id?, name, goal, schedule (object) | scheduleText, specialist?} */
  function saveRoutine(x) {
    const old = x.id ? data.routines.find((r) => r.id === x.id) : null;
    if (!old && data.routines.length >= MAX_ROUTINES) return { error: `You can have up to ${MAX_ROUTINES} routines. Delete one first.` };
    let sched = x.schedule || (x.scheduleText != null ? parseSchedule(x.scheduleText, now()) : old && old.schedule);
    if (!sched) return { error: 'I couldn\'t read that schedule. Try something like "every weekday at 8am", "every 2 hours" or "every Sunday evening".' };
    if (sched.error) return { error: sched.error };
    const goal = clip(x.goal != null ? x.goal : old && old.goal, 1000);
    if (!goal) return { error: 'Say what the routine should do.' };
    const r = old || { id: deps.uid(), device: deps.deviceId ? deps.deviceId() : '', enabled: true, createdAt: now(), lastAt: 0, history: [] };
    r.name = clip(x.name != null ? x.name : r.name, 60) || clip(goal, 60);
    r.goal = goal; r.schedule = sched; r.specialist = x.specialist !== undefined ? x.specialist || null : r.specialist || null;
    r.nextAt = nextRun(sched, now()); r.updatedAt = now();
    if (!old) data.routines.push(r);
    persist(); changed('routine'); tick(); return { routine: r };
  }
  function removeRoutine(id) { const i = data.routines.findIndex((r) => r.id === id); if (i >= 0) { data.routines.splice(i, 1); persist(); changed('routine'); tick(); } }
  function enableRoutine(id, on) { const r = data.routines.find((x) => x.id === id); if (!r) return; r.enabled = !!on; if (on) r.nextAt = nextRun(r.schedule, now()); r.updatedAt = now(); persist(); changed('routine'); tick(); }
  /** Runs it now (a test run, or because it's due). */
  function runRoutine(id, test = false) {
    const r = data.routines.find((x) => x.id === id); if (!r) return null;
    const j = deps.fire(r);
    r.lastAt = now(); r.history.push({ at: now(), job: j ? j.id : null, test }); while (r.history.length > 20) r.history.shift();
    if (!test) { r.nextAt = nextRun(r.schedule, now()); r.updatedAt = now(); }
    persist(); changed(test ? null : 'routine'); return j;
  }
  const mine = (r) => !r.device || !deps.deviceId || r.device === deps.deviceId();
  /** Fires what's due (once each, however long Atlas was closed) and sets the clock for the next one. */
  async function tick() {
    if (!loaded) return;
    const t = now();
    for (const r of data.routines.filter((x) => x.enabled && x.nextAt && x.nextAt <= t)) {
      const due = r.nextAt;
      r.nextAt = nextRun(r.schedule, t);   // moved on first, so a crash can't fire it twice
      let run = mine(r);
      if (deps.claim) { try { run = await deps.claim(r, due); } catch { run = mine(r); } }
      if (run) runRoutine(r.id); else { persist(); changed(null); }
    }
    if (timer) deps.clearTimer(timer);
    const next = data.routines.filter((x) => x.enabled && x.nextAt).map((x) => x.nextAt).sort((a, b) => a - b)[0];
    timer = next ? deps.setTimer(tick, Math.min(60000, Math.max(1000, next - now()))) : null;
  }

  /** From sync: another device's copy of one item ([rec] null = deleted there). */
  function applySynced(kind, id, rec) {
    if (kind === 'botpolicy') { if (rec && (rec.updatedAt || 0) >= (data.policy.updatedAt || 0)) data.policy = { ...emptyPolicy(), ...rec }; persist(); changed(null); return; }
    const arr = kind === 'skill' ? data.skills : kind === 'routine' ? data.routines : kind === 'specialist' ? data.specialists : null; if (!arr) return;
    const i = arr.findIndex((x) => x.id === id);
    if (!rec) { if (i >= 0) arr.splice(i, 1); }
    else if (i < 0) arr.push(rec);
    else if ((rec.updatedAt || 0) >= (arr[i].updatedAt || 0)) {
      // a routine's run times belong to the device that runs it
      if (kind === 'routine' && mine(arr[i])) { const keep = { nextAt: arr[i].nextAt, lastAt: arr[i].lastAt, history: arr[i].history }; arr[i] = { ...rec, ...keep }; if (rec.enabled) arr[i].nextAt = nextRun(rec.schedule, now()); }
      else arr[i] = rec;
    }
    persist(); changed(null); if (kind === 'routine') tick();
  }
  /** For sync: everything to share, as {kind, id, json}. */
  function synced() {
    const out = [];
    for (const s of data.skills) out.push({ kind: 'skill', id: s.id, json: s });
    for (const s of data.specialists) out.push({ kind: 'specialist', id: s.id, json: s });
    for (const r of data.routines) out.push({ kind: 'routine', id: r.id, json: { ...r, history: (r.history || []).slice(-5) } });
    if (data.policy.updatedAt) out.push({ kind: 'botpolicy', id: 'me', json: data.policy });
    return out;
  }

  return {
    data, init, tick, saveSkill, removeSkill, saveSpecialist, removeSpecialist, specialistNote, setMode, addRule, removeRule,
    saveRoutine, removeRoutine, enableRoutine, runRoutine, applySynced, synced, mine,
    skill: (name) => findByName(data.skills, name), specialist: (name) => data.specialists.find((s) => s.id === name) || findByName(data.specialists, name),
    mode: (category) => modeFor(data.policy, category),
    allows: (sp, tool) => toolAllowed(sp, tool),
    /** The kind of a risky action: from its wording first (what it would do), else from the tool. */
    categoryOf: (tool, args, what) => { if (tool === 'browser_fill_login') return 'account'; if (/^project_/.test(String(tool || ''))) return projectCategory(tool, args || {}); const cc = connectionCategory(tool, args || {}); if (cc) return cc; const g = guessCategory(what); return g !== 'other' || !tool ? g : categoryFor(tool, args || {}); },
    /** The kind from everything an action says (label, form, page): the strongest risk wins. */
    guess: (text, tool, args) => { if (tool === 'browser_fill_login' && !/payment/i.test(text)) return 'account'; return strongestCategory(text, tool, args); },
    label: categoryLabel, skillText, draftSkill,
    onChange: (f) => { listeners.add(f); return () => listeners.delete(f); },
    idle: () => saving,
  };
}
