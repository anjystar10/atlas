// Atlas Bot on the PC: an agent Atlas sends off to do a bigger job (research with sources, comparing options, a long
// piece of writing, a chain of PC actions) while the chat stays free. It plans, works step by step with tools, keeps
// notes, checks its work and writes a report that lands in the chat. Jobs are kept in a journal (bots.json) so they
// survive Atlas being closed; anything consequential waits for the user's OK. Same rules as the phone (BotJournal.kt,
// Bots.kt) and docs/AGENT_RUNTIME.md.
//
// This file has no imports from the app: createBots() takes everything it needs (the model, the tools, saving,
// notifications), so the whole loop can be tested in Node with a fake model. botwire.js connects it to the app.

export const S = {
  QUEUED: 'queued', RUNNING: 'running', WAITING_RETRY: 'waiting_retry', AWAITING_APPROVAL: 'awaiting_approval', AWAITING_ANSWER: 'awaiting_answer',
  PAUSED: 'paused', NEEDS_REVIEW: 'needs_review', COMPLETED: 'completed', FAILED: 'failed', CANCELLED: 'cancelled',
  WAITING_HELPERS: 'waiting_helpers',   // handed parts to specialist bots and waits for their reports (holds no working slot)
};
export const ALL = Object.values(S);
export const FINAL = new Set([S.COMPLETED, S.FAILED, S.CANCELLED]);
export const WAITING_ON_USER = new Set([S.AWAITING_APPROVAL, S.AWAITING_ANSWER, S.NEEDS_REVIEW]);
export const MAX_RUNNING = 2, MAX_TOOL_CALLS = 80, MAX_WORK_MS = 30 * 60000, ROUNDS_PER_PART = 10, MAX_RETRIES = 5, KEEP_FINISHED = 30;
/** Helpers (delegation): a bot's helpers may have helpers, no deeper; at most 4 per bot; each gets a share of what's left. */
export const MAX_DEPTH = 2, MAX_CHILDREN = 4, CHILD_MAX_CALLS = 30, CHILD_MAX_MS = 10 * 60000;
export const callsOf = (j) => j.maxCalls || MAX_TOOL_CALLS;
export const msOf = (j) => j.maxMs || MAX_WORK_MS;
/** The budget a new helper of [parent] gets: half of what the parent has left, within the helper limits. */
export function childBudget(parent) {
  return { maxCalls: Math.max(8, Math.min(CHILD_MAX_CALLS, Math.floor((callsOf(parent) - parent.toolCalls) / 2))),
    maxMs: Math.max(3 * 60000, Math.min(CHILD_MAX_MS, Math.floor((msOf(parent) - parent.workMs) / 2))) };
}

const MOVES = {
  [S.QUEUED]: [S.RUNNING, S.PAUSED, S.CANCELLED],
  [S.RUNNING]: [S.QUEUED, S.WAITING_RETRY, S.AWAITING_APPROVAL, S.AWAITING_ANSWER, S.PAUSED, S.NEEDS_REVIEW, S.COMPLETED, S.FAILED, S.CANCELLED, S.WAITING_HELPERS],
  [S.WAITING_HELPERS]: [S.QUEUED, S.PAUSED, S.CANCELLED],
  [S.WAITING_RETRY]: [S.QUEUED, S.RUNNING, S.PAUSED, S.FAILED, S.CANCELLED],
  [S.AWAITING_APPROVAL]: [S.QUEUED, S.PAUSED, S.CANCELLED],
  [S.AWAITING_ANSWER]: [S.QUEUED, S.PAUSED, S.CANCELLED],
  [S.PAUSED]: [S.QUEUED, S.CANCELLED],
  [S.NEEDS_REVIEW]: [S.QUEUED, S.CANCELLED],
};
export const canMove = (from, to) => (MOVES[from] || []).includes(to);
/** After a restart: a running job goes back in the queue, unless an action was mid-way (outcome unknown: the user checks). */
export const recover = (state, actionPending) => (state === S.RUNNING ? (actionPending ? S.NEEDS_REVIEW : S.QUEUED) : state);
export function toStart(jobs, now, max = MAX_RUNNING) {
  const room = Math.max(0, max - jobs.filter((j) => j.state === S.RUNNING).length);
  return jobs.filter((j) => j.state === S.QUEUED || (j.state === S.WAITING_RETRY && j.retryAt <= now)).sort((a, b) => a.createdAt - b.createdAt).slice(0, room);
}
export const retryDelay = (n) => 60000 * 2 ** Math.min(4, Math.max(0, n - 1));
export const LABELS = { queued: 'Waiting to start', running: 'Working', waiting_retry: 'Waiting to try again', awaiting_approval: 'Needs your OK', awaiting_answer: 'Has a question',
  paused: 'Paused', needs_review: 'Needs a check', completed: 'Done', failed: "Couldn't finish", cancelled: 'Stopped', waiting_helpers: 'Waiting for its helpers' };
export function summary(jobs, now) {
  const working = jobs.filter((j) => !j.parent && [S.RUNNING, S.QUEUED, S.WAITING_RETRY, S.WAITING_HELPERS].includes(j.state)).length;
  const waiting = jobs.filter((j) => WAITING_ON_USER.has(j.state)).length;
  const paused = jobs.filter((j) => j.state === S.PAUSED).length;
  const done = jobs.filter((j) => j.state === S.COMPLETED && now - j.updatedAt < 86400000).length;
  return [working && `${working} working`, waiting && `${waiting} need${waiting === 1 ? 's' : ''} you`, paused && `${paused} paused`, done && `${done} done`].filter(Boolean).join(' · ');
}

// ---- what needs the user's OK first (the PC's tools, and the Log's)

const READ_ONLY = /^\s*(get-[a-z]+|test-path|test-connection|select-string|measure-object|resolve-path|where\.exe|where|dir|ls|cat|type|echo|hostname|whoami|ipconfig|systeminfo|tasklist|ping)\b[^;|&>]*$/i;
/** Anything that could chain or hide a second command (new lines, $( ), backticks, script blocks, pipes, redirects) is never "read-only". */
const CHAINS = /[\r\n`$({;|&<>]/;
export const readOnlyCommand = (c) => { const t = String(c || ''); return !CHAINS.test(t) && READ_ONLY.test(t); };
const RISKY_LINKS = ['tel:', 'sms:', 'smsto:', 'mms:', 'mailto:'];
/** A sentence saying what would happen, when a bot needs the user's OK before running [tool]; null when it can go ahead. */
export function approvalFor(tool, a = {}) {
  switch (tool) {
    case 'run_command': return readOnlyCommand(a.command) ? null : `Run this command on your PC: ${String(a.command || '').slice(0, 160)}`;
    // a new line in typed text is pressed as Enter (pc.js), which may send or submit something, like press_keys Enter
    case 'type_text': return /[\r\n]/.test(String(a.text || '')) ? `Type “${String(a.text || '').replace(/\s+/g, ' ').slice(0, 60)}” with Enter (this may send or submit something)` : null;
    case 'write_file': return `Write the file ${String(a.path || '').slice(0, 120)}`;
    case 'forget': return 'Delete a saved memory';
    case 'update_reminder': return a.action === 'delete' ? 'Delete a reminder from your Log' : a.action === 'cancel' ? 'Cancel a reminder' : null;
    case 'open_link': { const u = String(a.url || '').trim().toLowerCase(); return RISKY_LINKS.some((s) => u.startsWith(s)) ? `Open ${String(a.url).slice(0, 80)} (starts a call, message or email)` : null; }
    case 'press_keys': return /\benter\b|\breturn\b/i.test(String(a.keys || '')) ? `Press ${a.keys} (this may send or submit something)` : null;
    default: return null;
  }
}

// ---- the tools only bots have

const str = (description) => ({ type: 'string', description });
const fn = (name, description, props, required = []) => ({ name, description, parameters: { type: 'object', properties: props, required } });
export const FINISH = fn('finish', 'Finish the job and hand over the report. The report is shown to the user and posted in their chat.',
  { report: str('The full report in neat Markdown, ending with a Sources section when you used the web.') }, ['report']);
export const BOT_TOOLS = [
  fn('update_plan', 'Write or update your plan for this job: 3 to 8 short steps, one per line, in order. Start a line with [x] when that step is done.',
    { plan: str('The steps, one per line, e.g. "[x] Search for reviews\\n[ ] Compare prices"') }, ['plan']),
  fn('note', "Save something you'll need later: a fact, figure, name or link you found, with where it came from. Older tool results are dropped as you go, but notes are kept for the whole job.",
    { text: str('The note, short and specific') }, ['text']),
  fn('ask_user', "Ask the user one clear question when you truly can't go on without their answer (a choice only they can make, a missing detail). The job waits until they answer.",
    { question: str('The question') }, ['question']),
  fn('request_approval', "Ask the user's OK before anything that sends a message, posts, buys or pays, deletes something, or changes an account or security setting. Say exactly what you'll do. The job waits for their answer.",
    { action: str("Exactly what you're about to do") }, ['action']),
  FINISH,
];
/** With skills, routines and specialist bots (botx.js) connected: */
export const EXTRA_TOOLS = {
  use_skill: fn('use_skill', 'Load one of the skills the user taught you (listed in your instructions) and follow it for this kind of job.', { name: str("The skill's name") }, ['name']),
  delegate: fn('delegate', "Hand part of this job to one of the user's specialist bots (listed in your instructions). It works on its own and reports back; call wait_for_helpers when you need the reports. Up to 4 helpers; give each a clear, self-contained task.",
    { specialist: str("The specialist bot's name"), task: str('Exactly what it should do and report back, with everything it needs to know') }, ['specialist', 'task']),
  wait_for_helpers: fn('wait_for_helpers', 'Wait until your helpers have reported. Their reports are added to your notes, then you carry on.', {}),
  bot_remember: fn('bot_remember', 'Save a note for yourself (this specialist) for future jobs: a lasting fact about how the user likes this kind of work done.', { text: str('The note') }, ['text']),
  bot_forget: fn('bot_forget', 'Remove one of your own saved notes that is wrong or out of date.', { text: str('The note, or words from it') }, ['text']),
};
/** Atlas's own tools that bots don't get. */
export const NOT_FOR_BOTS = new Set(['change_setting', 'start_focus', 'deploy_bot', 'save_skill', 'set_routine']);

const LABEL = { web_search: (a) => `Searching for “${String(a.query || '').slice(0, 60)}”`, read_page: (a) => 'Reading ' + String(a.url || '').replace(/^https?:\/\//, '').slice(0, 50),
  open_app: (a) => 'Opening ' + (a.name || ''), list_apps: () => 'Looking at your apps', open_link: () => 'Opening a link', open_path: () => 'Opening a file', list_files: () => 'Looking in a folder',
  read_file: () => 'Reading a file', write_file: () => 'Writing a file', run_command: () => 'Running a command', look_at_screen: () => 'Looking at the screen', click: () => 'Clicking',
  type_text: () => 'Typing', press_keys: (a) => 'Pressing ' + (a.keys || ''), scroll: () => 'Scrolling', set_reminder: () => 'Logging a reminder', update_reminder: () => 'Changing a reminder',
  remember: () => 'Saving to memory', forget: () => 'Deleting a memory', read_log: () => 'Checking your Log' };
const label = (c, extra) => (LABEL[c.name] ? LABEL[c.name](c.args || {}) : (extra && extra(c)) || 'Working');

/** One job: plain JSON with no secrets, an id, updated_ms and the owning device, so it can later sync like the Log. */
export function newJob(goal, details, now, id, device = '') {
  return { id, goal, details: details || '', createdAt: now, device, state: S.QUEUED, updatedAt: now, updated_ms: now, history: [{ at: now, state: S.QUEUED }], plan: [], notes: [], steps: [], sources: [], recent: [],
    toolCalls: 0, workMs: 0, provider: null, pending: null, approval: null, question: null, answer: null, handover: false, browser: false, report: null, error: null, retryAt: 0, retries: 0, current: null, posted: false,
    // extras: messages from the user while it works, "wrap up now", the specialist it works as, its place in a team, the routine that sent it
    inbox: [], wrapUp: false, specialist: null, parent: null, depth: 0, children: [], routine: null, maxCalls: 0, maxMs: 0 };
}
export function move(j, to, now) {
  if (j.state === to) return true;
  if (!canMove(j.state, to)) return false;
  j.state = to; j.updatedAt = now; j.history.push({ at: now, state: to }); if (j.history.length > 60) j.history.shift();
  return true;
}
const step = (j, s) => { j.steps.push(s); if (j.steps.length > 200) j.steps.shift(); };
const addRecent = (j, s) => { j.recent.push(s); while (j.recent.length > 3) j.recent.shift(); };

/** Where the job stands, as the opening message of each fresh round with the model. */
export function brief(j) {
  const out = [`Your job: ${j.goal}`];
  if (j.details) out.push(`Details: ${j.details}`);
  out.push(`(Tool steps used: ${j.toolCalls} of ${callsOf(j)}. Time left: about ${Math.max(0, Math.floor((msOf(j) - j.workMs) / 60000))} min.)`);
  if (j.parent) out.push('(You are a helper: another bot gave you this task. Your report goes back to it, not to the user.)');
  out.push(j.plan.length ? '\nYour plan:\n' + j.plan.map((p, i) => `${i + 1}. ${p.done ? '[x]' : '[ ]'} ${p.text}`).join('\n') : '\nNo plan yet: start by making one with update_plan.');
  if (j.notes.length) out.push('\nYour notes so far:\n' + j.notes.map((n) => '- ' + n).join('\n'));
  if (j.sources.length) out.push("\nPages you've read:\n" + j.sources.map((n) => '- ' + n).join('\n'));
  const done = j.steps.slice(-20); if (done.length) out.push("\nWhat you've done so far (latest last):\n" + done.map((n) => '- ' + n).join('\n'));
  if (j.recent.length) out.push('\nLatest results:\n' + j.recent.map((r) => '---\n' + r).join('\n') + '\n---');
  out.push(j.toolCalls === 0 && !j.plan.length ? '\nBegin.' : '\nCarry on from here.');
  return out.join('\n');
}

export function compiled(j) {
  let s = `## ${j.goal}\n\nI ran out of time before writing a full report. Here is what I found:\n\n`;
  s += j.notes.length ? j.notes.map((n) => '- ' + n.replace(/\n/g, ' ')).join('\n') + '\n' : "- I didn't get far enough to find anything useful.\n";
  if (j.sources.length) s += '\n## Sources\n' + j.sources.map((u) => '- ' + u).join('\n') + '\n';
  return s;
}

export function parsePlan(text) {
  return String(text || '').split('\n').map((l) => l.trim()).filter(Boolean).slice(0, 12).map((l) => ({
    done: /^(\d+[.)]\s*)?\[(x|X|✓)]/.test(l),
    text: l.replace(/^(\d+[.)]\s*)?(\[[ xX✓]?]\s*)?(\d+[.)]\s*)?/, '').slice(0, 160),
  }));
}

/**
 * deps:
 *  load(): Promise<string|null>, save(text): Promise          the journal (bots.json)
 *  provider(): {id, name} | null                               the service to think with (from the key pool)
 *  session(p, brief, tools): {call(system), results(list), key} one fresh conversation with that service
 *  callWithKeys(p, sess, () => sess.call(system), {onStatus, cancelled})  with the key pool behind it
 *  isSwitch(e) → e.to is the next service;  isRetryable(e) → busy / offline / limit
 *  tools: Atlas's tool declarations (bots get these minus NOT_FOR_BOTS, plus BOT_TOOLS)
 *  runTool(call, {approved, job}) → {out, step, image, url?, handover?}   handover: a sentence; the job waits for the user to tap Hand Back
 *  extraTools?: more tool declarations only bots get (e.g. the PC's browser);  labelFor?(call) → a short label for them
 *  checkApproval?(call, job) → Promise<sentence|null>: a look at the real thing (e.g. the button on the page) before it runs
 *  onFinal?(job): a job finished, was stopped or removed (e.g. close its browser window)
 *  reportFooter?(job) → text added to the end of its report (e.g. the files it made and where they are)
 *  systemPrompt(job): string;  notify(title, body, tag);  post(markdown): the report into the chat
 *  extras?: skills, specialists and approval rules (botx.js createExtras): enables use_skill, delegate, bot_remember and
 *           the user's always-allow / never rules
 *  plainReply(s), plainMemory(s);  now(): ms;  uid(): string;  deviceId(): this device's id;  setTimer(fn, ms), clearTimer(t)
 */
export function createBots(deps) {
  const now = deps.now || Date.now;
  const jobs = [];
  const workers = new Map();   // job id → token of the loop running it
  const listeners = new Set();
  let loaded = false, timer = null, saving = Promise.resolve();
  const extra = deps.extraTools || [];
  const myTools = [...deps.tools.filter((t) => !NOT_FOR_BOTS.has(t.name)), ...extra, ...BOT_TOOLS];
  const toolNames = new Set([...deps.tools.map((t) => t.name).filter((n) => !NOT_FOR_BOTS.has(n)), ...extra.map((t) => t.name)]);
  const finalHook = (j) => { if (deps.onFinal) { try { deps.onFinal(j); } catch (e) { console.error(e); } } };
  const ex = deps.extras || null;
  const specialistOf = (j) => (ex && j.specialist ? ex.data.specialists.find((s) => s.id === j.specialist) || null : null);
  /** The tools this job's bot gets: a specialist only its own kinds; helpers only at the allowed depth. */
  function toolsFor(j) {
    const sp = specialistOf(j);
    const list = myTools.filter((t) => !sp || ex.allows(sp, t.name));
    if (!ex) return list;
    const more = [EXTRA_TOOLS.use_skill];
    if (j.depth < MAX_DEPTH && ex.data.specialists.length) more.push(EXTRA_TOOLS.delegate, EXTRA_TOOLS.wait_for_helpers);
    if (sp) more.push(EXTRA_TOOLS.bot_remember, EXTRA_TOOLS.bot_forget);
    return [...list.filter((t) => t.name !== 'finish'), ...more, FINISH];
  }

  const changed = () => { for (const f of [...listeners]) { try { f(); } catch (e) { console.error(e); } } };
  const job = (id) => jobs.find((j) => j.id === id);
  const active = () => jobs.some((j) => [S.RUNNING, S.QUEUED, S.WAITING_RETRY].includes(j.state));

  function snapshot() {
    // a finished helper stays while its bot still needs its report
    const old = jobs.filter((j) => FINAL.has(j.state) && !(j.parent && job(j.parent) && !FINAL.has(job(j.parent).state))).sort((a, b) => b.updatedAt - a.updatedAt).slice(KEEP_FINISHED);
    for (const o of old) jobs.splice(jobs.indexOf(o), 1);
    for (const j of jobs) j.updated_ms = j.updatedAt;
    return JSON.stringify(jobs);
  }
  /** Saves; the promise resolves once it's on disk (writes go one after another, newest last). */
  function persist() { const text = snapshot(); saving = saving.then(() => deps.save(text)).catch((e) => console.error('bots: save failed', e)); return saving; }

  async function init() {
    if (loaded) return;
    loaded = true;
    try { const t = await deps.load(); if (t) for (const j of JSON.parse(t)) if (j && j.id && ALL.includes(j.state)) jobs.push(j); } catch (e) { console.error('bots: journal unreadable', e); }
    // jobs saved by an older Atlas don't have the newer fields yet
    for (const j of jobs) { j.inbox = j.inbox || []; j.children = j.children || []; j.depth = j.depth || 0; j.wrapUp = !!j.wrapUp; }
    const t = now();
    for (const j of jobs) {
      const to = recover(j.state, !!j.pending);
      if (to !== j.state) {
        j.state = to; j.updatedAt = t; j.history.push({ at: t, state: to }); j.current = null;
        if (to === S.NEEDS_REVIEW) deps.notify('Atlas Bot needs a check', `Atlas was closed while the bot was doing: ${j.pending}. It may or may not have happened.`, 'bot:' + j.id);
      }
    }
    await persist(); pump();
  }

  /** opts: {specialist: id, routine: id, parent: id (a helper), maxCalls, maxMs} */
  function deploy(goal, details = '', opts = {}) {
    const g = (deps.plainMemory(goal) || String(goal).trim()).slice(0, 300);
    const j = newJob(g, String(details || '').trim().slice(0, 4000), now(), deps.uid(), deps.deviceId ? deps.deviceId() : '');
    if (opts.specialist) j.specialist = opts.specialist;
    if (opts.routine) j.routine = opts.routine;
    if (opts.parent) { const p = job(opts.parent); j.parent = opts.parent; j.depth = p ? (p.depth || 0) + 1 : 1; }
    if (opts.maxCalls) j.maxCalls = opts.maxCalls;
    if (opts.maxMs) j.maxMs = opts.maxMs;
    jobs.push(j); persist(); changed(); pump();
    return j;
  }
  /** A message from the user to a bot while it works (or waits): it reads it at its next step. */
  function steer(id, text) {
    const j = job(id); const t = String(text || '').trim().slice(0, 1000);
    if (!j || !t || FINAL.has(j.state)) return false;
    j.inbox.push({ at: now(), text: t }); step(j, 'You said: ' + t.slice(0, 120)); j.updatedAt = now();
    persist(); changed(); return true;
  }
  /** "Wrap up now": the bot stops after its current step and writes the report with what it has. */
  function wrapUp(id) {
    const j = job(id); if (!j || FINAL.has(j.state)) return;
    j.wrapUp = true; step(j, 'You asked it to wrap up'); j.updatedAt = now();
    if (j.state === S.PAUSED || j.state === S.WAITING_HELPERS) move(j, S.QUEUED, now());
    persist(); changed(); pump();
  }
  function pause(id) { const j = job(id); if (j && move(j, S.PAUSED, now())) { j.current = null; persist(); changed(); } }
  function resume(id) { const j = job(id); if (j && j.state === S.PAUSED && move(j, S.QUEUED, now())) { persist(); changed(); pump(); } }
  function stop(id) {
    const j = job(id); if (!j || FINAL.has(j.state)) return;
    workers.delete(id); move(j, S.CANCELLED, now()); j.current = null; j.handover = false;
    for (const c of j.children || []) stop(c);   // its helpers stop with it
    persist(); changed(); finalHook(j); pump();
  }
  /** [always]: also allow this kind of action from now on (the user's standing choice, in Me). */
  function approve(id, yes, always = false) {
    const j = job(id); if (!j || !j.approval || j.state !== S.AWAITING_APPROVAL) return;
    j.approval.decision = yes ? 'approved' : 'denied';
    if (yes && always && ex && j.approval.category) ex.setMode(j.approval.category, 'allow');
    move(j, S.QUEUED, now()); persist(); changed(); pump();
  }
  function answer(id, text) { const j = job(id); if (!j || j.state !== S.AWAITING_ANSWER || !String(text || '').trim()) return; j.answer = String(text).trim().slice(0, 2000); j.handover = false; move(j, S.QUEUED, now()); persist(); changed(); pump(); }
  /** The user took the browser (Take Over, or the bot handed it over) and gives it back: the bot looks again before going on. */
  function handBack(id) {
    const j = job(id); if (!j) return;
    if (j.state === S.AWAITING_ANSWER && j.handover) return answer(id, 'Done: I finished it in the browser window and handed it back to you. Read the page again before going on.');
    if (j.state === S.PAUSED) { j.notes.push('The user took over the browser and handed it back. Read the page again (browser_read) before doing anything else: it may have changed.'); step(j, 'You handed the browser back'); resume(id); }
  }
  function carryOn(id) {
    const j = job(id); if (!j || j.state !== S.NEEDS_REVIEW) return;
    if (j.pending) j.notes.push(`Atlas was interrupted while you were doing: ${j.pending}. It may or may not have happened: check before doing it again.`);
    j.pending = null; move(j, S.QUEUED, now()); persist(); changed(); pump();
  }
  function remove(id) { const j = job(id); if (!j || !FINAL.has(j.state)) return; jobs.splice(jobs.indexOf(j), 1); persist(); changed(); finalHook(j); }

  /** A bot waiting on helpers carries on once they've all finished (their reports go into its notes). */
  function wakeParents() {
    for (const p of jobs.filter((x) => x.state === S.WAITING_HELPERS)) {
      const kids = (p.children || []).map(job).filter(Boolean);
      if (kids.every((k) => FINAL.has(k.state))) { takeHelperReports(p); move(p, S.QUEUED, now()); persist(); }
    }
  }
  function takeHelperReports(p) {
    for (const k of (p.children || []).map(job).filter(Boolean)) {
      if (!FINAL.has(k.state) || k.reported) continue;
      k.reported = true;
      const who = (specialistOf(k) || {}).name || 'A helper';
      const body = k.state === S.COMPLETED ? String(k.report || '').slice(0, 4000) : `It couldn't finish (${k.error || LABELS[k.state]}).`;
      p.notes.push(`${who} reported on "${k.goal}":\n${body}`);
      while (p.notes.length > 60) p.notes.shift();
      p.toolCalls += k.toolCalls;   // the helpers' work counts against the whole job's budget
      step(p, `${who} reported back`);
    }
  }

  function pump() {
    if (!loaded) return;
    wakeParents();
    const t = now();
    // how many may work at once depends on the device right now (deps.slots), never more than MAX_RUNNING
    const cap = Math.max(1, Math.min(MAX_RUNNING, (deps.slots && deps.slots()) || MAX_RUNNING));
    for (const j of toStart(jobs.filter((x) => !workers.has(x.id) && x.state !== S.RUNNING), t, cap - workers.size)) {
      if (workers.size >= cap) break;
      if (j.state === S.WAITING_RETRY) move(j, S.QUEUED, t);
      if (!move(j, S.RUNNING, t)) continue;
      const token = {};
      workers.set(j.id, token);
      (async () => {
        try { await work(j, token); }
        catch (e) { console.error('bot failed', e); fail(j, 'Something went wrong: ' + (e.message || e)); }
        finally {
          if (workers.get(j.id) === token) workers.delete(j.id);
          if (j.state === S.RUNNING) move(j, S.QUEUED, now());
          j.current = null; persist(); changed(); deps.setTimer(pump, 0);
        }
      })();
    }
    if (timer) deps.clearTimer(timer);
    timer = null;
    const next = jobs.filter((x) => x.state === S.WAITING_RETRY).map((x) => x.retryAt).sort((a, b) => a - b)[0];
    if (next) timer = deps.setTimer(pump, Math.min(3600000, Math.max(1000, next - t)));
    changed();
  }

  function fail(j, why) { j.error = why; j.current = null; if (move(j, S.FAILED, now())) { deps.notify("Atlas Bot couldn't finish", why, 'bot:' + j.id); finalHook(j); } persist(); }

  function waitAndRetry(j, why) {
    j.retries++;
    if (j.retries > MAX_RETRIES) return fail(j, `${why} I tried ${MAX_RETRIES} times; it still didn't work.`);
    const wait = retryDelay(j.retries);
    j.retryAt = now() + wait; j.error = why; j.current = null;
    move(j, S.WAITING_RETRY, now()); step(j, `Waiting ${Math.round(wait / 60000)} min to try again: ${String(why).slice(0, 100)}`);
    persist(); changed();
  }

  function complete(j, report) {
    j.report = (deps.plainReply(report || '') || compiled(j)) + (deps.reportFooter ? deps.reportFooter(j) : '');   // e.g. the files it made
    for (const p of j.plan) p.done = true;
    j.current = null; j.error = null;
    move(j, S.COMPLETED, now());
    // a helper's report goes back to the bot that sent it, not into the chat
    if (j.parent) { persist(); changed(); finalHook(j); deps.setTimer(pump, 0); return; }
    if (!j.posted) { j.posted = true; deps.post(`**Atlas Bot finished:** ${j.goal}\n\n${j.report}`); }
    deps.notify('Atlas Bot is done', j.goal, 'bot:' + j.id);
    persist(); changed(); finalHook(j);
  }

  async function askApproval(j, a) {
    // the user's standing choice for this kind of action: always allowed, never allowed, or ask (botx.js)
    if (ex) {
      a.category = a.category || ex.categoryOf(a.tool, a.args || {}, a.what);
      const mode = ex.mode(a.category);
      if (mode !== 'ask') {
        j.approval = { ...a, decision: mode === 'allow' ? 'approved' : 'denied', auto: true };
        step(j, (mode === 'allow' ? 'Allowed by your rule (you always allow: ' : 'Not done: you never allow ') + ex.label(a.category).toLowerCase() + (mode === 'allow' ? ')' : '') + ': ' + a.what);
        // straight back in the queue: the decision is acted on at the start of its next turn, like a tapped one
        move(j, S.AWAITING_APPROVAL, now()); move(j, S.QUEUED, now()); j.current = null;
        await persist(); changed(); deps.setTimer(pump, 0);
        return;
      }
    }
    j.approval = a; j.current = null;
    move(j, S.AWAITING_APPROVAL, now()); step(j, 'Waiting for your OK: ' + a.what);
    await persist(); deps.notify('Atlas Bot needs your OK', a.what, 'bot:' + j.id); changed();
  }

  async function handOver(j, why) {
    j.question = (deps.plainReply(String(why)) || String(why)).slice(0, 600); j.handover = true; j.current = null;
    move(j, S.AWAITING_ANSWER, now());
    await persist(); deps.notify('Atlas Bot needs you in the browser', j.question, 'bot:' + j.id); changed();
  }

  async function runTool(j, c, approved = false, approvedWhat = null) {
    const what = label(c, deps.labelFor);
    j.current = what; j.pending = what; await persist(); changed();
    const r = (await deps.runTool(c, { approved, job: j, approvedWhat })) || { out: '' };
    j.pending = null; j.toolCalls++;
    if (String(c.name).startsWith('browser_')) j.browser = true;
    if (r.step !== null) step(j, r.step || what);
    const url = String((c.name === 'read_page' ? (c.args || {}).url : r.url) || '').trim();
    if (/^https?:/.test(url) && !/^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(url) && !j.sources.includes(url)) { j.sources.push(url); if (j.sources.length > 40) j.sources.shift(); }
    const long = ['read_page', 'web_search', 'browser_open', 'browser_read', 'browser_click', 'browser_type', 'browser_select', 'browser_press', 'browser_scroll', 'browser_back', 'browser_wait'].includes(c.name);
    addRecent(j, `${what}:\n${String(r.out || '').slice(0, long ? 5000 : 1200)}`);
    persist();
    return r;
  }

  async function takeDecisions(j) {
    if (j.question && j.answer) { j.notes.push(`You asked the user: ${j.question}. They answered: ${j.answer}`); step(j, 'You answered: ' + j.answer.slice(0, 120)); }
    if (j.answer) { j.question = null; j.answer = null; }
    const a = j.approval; if (!a || !a.decision) return;
    j.approval = null;
    if (a.decision === 'denied') {
      j.notes.push(a.auto ? `The user never allows this kind of action (${a.what}). Don't do it; find another way or finish without it.` : `The user said NO to: ${a.what}. Don't do it; find another way or finish without it.`);
      if (!a.auto) step(j, 'You said no to: ' + a.what);
      persist(); return;
    }
    if (!a.auto) step(j, 'You approved: ' + a.what);
    if (!a.tool) { j.notes.push(`The user approved: ${a.what}. Go ahead with exactly that.`); persist(); return; }
    const r = await runTool(j, { name: a.tool, args: a.args || {}, id: null }, true, a.what);
    j.notes.push(`Approved and done: ${a.what}. Result: ${String(r.out || '').split('\n')[0].slice(0, 300)}`);
    persist();
    if (r.handover) await handOver(j, r.handover);
  }

  async function work(j, token) {
    const mine = () => j.state === S.RUNNING && workers.get(j.id) === token;
    let p = deps.provider();
    if (!p) return fail(j, 'Atlas Bot needs an AI service: add a key in Me → Brain, or start a local model.');
    j.provider = p.name;
    let last = now();
    const tick = () => { const n = now(); j.workMs += n - last; last = n; };
    await takeDecisions(j);
    if (!mine()) return;
    const tried = new Set([p.id]);
    let nudges = 0, extra = '';
    const inbox = () => {
      // the user's messages since the last turn become standing instructions (kept in the notes for the whole job)
      if (!j.inbox.length) return false;
      for (const m of j.inbox.splice(0)) j.notes.push(`NEW INSTRUCTION FROM THE USER (follow it; it overrides earlier plans where they differ): ${m.text}`);
      while (j.notes.length > 60) j.notes.shift();
      persist(); return true;
    };
    while (mine()) {
      tick();
      inbox();
      const wrapping = j.wrapUp || j.toolCalls >= callsOf(j) || j.workMs >= msOf(j);
      const text = brief(j) + extra + (j.wrapUp ? '\n\nThe user asked you to wrap up now. Call finish with the best report you can from what you have, and say plainly what\'s left undone.'
        : wrapping ? "\n\nYour budget for this job is used up. Call finish now with the best report you can from your plan and notes, and say plainly what's left undone." : '');
      extra = '';
      let sess;
      try { sess = deps.session(p, text, wrapping ? [FINISH] : toolsFor(j)); } catch (e) { return fail(j, e.message || String(e)); }
      let rounds = 0, fresh = false;
      while (mine() && rounds < ROUNDS_PER_PART && !fresh) {
        rounds++;
        j.current = 'Thinking'; changed();
        let turn;
        try {
          turn = await deps.callWithKeys(p, sess, () => sess.call(deps.systemPrompt(j)), { onStatus: (st) => { if (mine()) { j.current = st || 'Thinking'; changed(); } }, cancelled: () => !mine() });
        } catch (e) {
          if (!mine()) return;
          if (deps.isSwitch(e)) {
            if (tried.has(e.to.id)) return waitAndRetry(j, e.message || 'All your keys are used up for now.');
            tried.add(e.to.id); p = e.to; j.provider = p.name; step(j, 'Switched to ' + p.name); persist();
            fresh = true; continue;
          }
          if (deps.isRetryable(e)) return waitAndRetry(j, e.message || 'The AI service is busy.');
          return fail(j, e.message || String(e));
        }
        j.retries = 0;
        if (!mine()) return;
        tick();
        if (!turn.calls.length) {
          if (turn.text && (j.toolCalls > 0 || nudges >= 2 || wrapping)) return complete(j, turn.text);
          nudges++;
          extra = '\n\n(You replied without using a tool. Keep working with your tools, and call finish with the report when the job is done.)';
          fresh = true; continue;
        }
        if (wrapping && !turn.calls.some((c) => c.name === 'finish')) return complete(j, compiled(j));
        const results = [];
        for (const c of turn.calls) {
          if (!mine()) break;
          const a = c.args || {};
          if (c.name === 'finish') {
            const busy = (j.children || []).map(job).filter((k) => k && !FINAL.has(k.state));
            if (busy.length && !j.finishNudged && !wrapping) {
              j.finishNudged = true;
              results.push({ call: c, out: `${busy.length} of your helpers are still working. Call wait_for_helpers to get their reports first (or call finish again to finish without them).` }); continue;
            }
            for (const k of busy) stop(k.id);
            return complete(j, a.report || turn.text || compiled(j));
          }
          if (ex && c.name === 'use_skill') {
            const sk = ex.skill(a.name);
            results.push({ call: c, out: sk ? ex.skillText(sk) : `There's no skill called "${String(a.name || '')}". Skills: ${ex.data.skills.map((x) => x.name).join(', ') || 'none yet'}.` });
            if (sk) step(j, 'Using the skill: ' + sk.name);
            continue;
          }
          if (ex && c.name === 'delegate' && j.depth < MAX_DEPTH) {
            const sp = ex.specialist(a.specialist);
            const task = String(a.task || '').trim();
            if (!sp) { results.push({ call: c, out: `There's no specialist bot called "${String(a.specialist || '')}". The team: ${ex.data.specialists.map((x) => x.name).join(', ')}.` }); continue; }
            if (!task) { results.push({ call: c, out: 'Error: say what the helper should do.' }); continue; }
            if ((j.children || []).length >= MAX_CHILDREN) { results.push({ call: c, out: `You already have ${MAX_CHILDREN} helpers: that's the limit. Do the rest yourself.` }); continue; }
            const k = deploy(task.split('\n')[0].slice(0, 300), task, { specialist: sp.id, parent: j.id, ...childBudget(j) });
            j.children.push(k.id); step(j, `Handed to ${sp.name}: ${k.goal.slice(0, 100)}`); persist();
            results.push({ call: c, out: `${sp.name} is on it. Keep working on other parts; call wait_for_helpers when you need its report.` }); continue;
          }
          if (ex && c.name === 'wait_for_helpers') {
            const busy = (j.children || []).map(job).filter((k) => k && !FINAL.has(k.state));
            if (!busy.length) { takeHelperReports(j); results.push({ call: c, out: (j.children || []).length ? 'All your helpers have reported: their reports are in your notes now.' : "You haven't handed anything to a helper." }); fresh = true; continue; }
            sess.results([...results, { call: c, out: 'Waiting for your helpers.' }]);
            j.current = null; move(j, S.WAITING_HELPERS, now()); step(j, `Waiting for ${busy.length} helper${busy.length === 1 ? '' : 's'}`);
            await persist(); changed(); return;
          }
          if (ex && (c.name === 'bot_remember' || c.name === 'bot_forget') && j.specialist) {
            results.push({ call: c, out: ex.specialistNote(j.specialist, a.text, c.name === 'bot_forget') }); continue;
          }
          if (c.name === 'update_plan') {
            const plan = parsePlan(a.plan).map((x) => ({ ...x, text: deps.plainMemory(x.text) || x.text }));
            if (plan.length) { step(j, j.plan.length ? 'Updated the plan' : 'Made a plan'); j.plan = plan; }
            results.push({ call: c, out: 'Plan saved.' }); continue;
          }
          if (c.name === 'note') { const n = String(a.text || '').trim(); if (n) { j.notes.push(n.slice(0, 1500)); while (j.notes.length > 60) j.notes.shift(); } results.push({ call: c, out: 'Noted.' }); continue; }
          if (c.name === 'ask_user') {
            const q = (deps.plainReply(String(a.question || '')) || 'Could you tell me more about what you want?').slice(0, 600);
            j.question = q; j.current = null; move(j, S.AWAITING_ANSWER, now()); step(j, 'Asked you: ' + q.slice(0, 120));
            await persist(); deps.notify('Atlas Bot has a question', q, 'bot:' + j.id); changed();
            return;
          }
          if (c.name === 'request_approval') { await askApproval(j, { what: (deps.plainReply(String(a.action || '')) || 'Go ahead with the next step').slice(0, 300), tool: null, args: null, decision: null }); return; }
          if (!toolNames.has(c.name)) { results.push({ call: c, out: `Error: there's no tool called ${c.name}.` }); continue; }
          const need = approvalFor(c.name, a) || (deps.checkApproval ? await deps.checkApproval(c, j) : null);
          if (!mine()) return;
          if (need) { await askApproval(j, { what: need, tool: c.name, args: JSON.parse(JSON.stringify(a)), decision: null }); return; }
          const r = await runTool(j, c);
          if (r.handover) { await handOver(j, r.handover); return; }
          results.push({ call: c, out: r.out, image: r.image || null });
        }
        if (!mine()) return;
        sess.results(results);
        persist(); changed();
        if (j.toolCalls >= callsOf(j) || j.workMs >= msOf(j) || j.wrapUp) break;
        if (j.inbox.length) break;   // the user said something: start a fresh turn that includes it
      }
    }
  }

  return {
    jobs, init, deploy, pause, resume, stop, approve, answer, handBack, carryOn, remove, pump, job, active, steer, wrapUp,
    // helpers show inside their bot's page, not in the list
    visible: () => jobs.filter((j) => !j.parent && (!FINAL.has(j.state) || now() - j.updatedAt < 7 * 86400000)).sort((a, b) => b.createdAt - a.createdAt),
    helpers: (id) => ((job(id) || {}).children || []).map(job).filter(Boolean),
    onChange: (f) => { listeners.add(f); return () => listeners.delete(f); },
    idle: async () => { while (workers.size) await new Promise((r) => setTimeout(r, 5)); await saving; },
  };
}
