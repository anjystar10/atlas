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
};
export const ALL = Object.values(S);
export const FINAL = new Set([S.COMPLETED, S.FAILED, S.CANCELLED]);
export const WAITING_ON_USER = new Set([S.AWAITING_APPROVAL, S.AWAITING_ANSWER, S.NEEDS_REVIEW]);
export const MAX_RUNNING = 2, MAX_TOOL_CALLS = 80, MAX_WORK_MS = 30 * 60000, ROUNDS_PER_PART = 10, MAX_RETRIES = 5, KEEP_FINISHED = 30;

const MOVES = {
  [S.QUEUED]: [S.RUNNING, S.PAUSED, S.CANCELLED],
  [S.RUNNING]: [S.QUEUED, S.WAITING_RETRY, S.AWAITING_APPROVAL, S.AWAITING_ANSWER, S.PAUSED, S.NEEDS_REVIEW, S.COMPLETED, S.FAILED, S.CANCELLED],
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
  paused: 'Paused', needs_review: 'Needs a check', completed: 'Done', failed: "Couldn't finish", cancelled: 'Stopped' };
export function summary(jobs, now) {
  const working = jobs.filter((j) => [S.RUNNING, S.QUEUED, S.WAITING_RETRY].includes(j.state)).length;
  const waiting = jobs.filter((j) => WAITING_ON_USER.has(j.state)).length;
  const paused = jobs.filter((j) => j.state === S.PAUSED).length;
  const done = jobs.filter((j) => j.state === S.COMPLETED && now - j.updatedAt < 86400000).length;
  return [working && `${working} working`, waiting && `${waiting} need${waiting === 1 ? 's' : ''} you`, paused && `${paused} paused`, done && `${done} done`].filter(Boolean).join(' · ');
}

// ---- what needs the user's OK first (the PC's tools, and the Log's)

const READ_ONLY = /^\s*(get-[a-z]+|test-path|test-connection|select-string|measure-object|resolve-path|where\.exe|where|dir|ls|cat|type|echo|hostname|whoami|ipconfig|systeminfo|tasklist|ping)\b[^;|&>]*$/i;
const RISKY_LINKS = ['tel:', 'sms:', 'smsto:', 'mms:', 'mailto:'];
/** A sentence saying what would happen, when a bot needs the user's OK before running [tool]; null when it can go ahead. */
export function approvalFor(tool, a = {}) {
  switch (tool) {
    case 'run_command': return READ_ONLY.test(String(a.command || '')) ? null : `Run this command on your PC: ${String(a.command || '').slice(0, 160)}`;
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
/** Atlas's own tools that bots don't get. */
export const NOT_FOR_BOTS = new Set(['change_setting', 'start_focus', 'deploy_bot']);

const LABEL = { web_search: (a) => `Searching for “${String(a.query || '').slice(0, 60)}”`, read_page: (a) => 'Reading ' + String(a.url || '').replace(/^https?:\/\//, '').slice(0, 50),
  open_app: (a) => 'Opening ' + (a.name || ''), list_apps: () => 'Looking at your apps', open_link: () => 'Opening a link', open_path: () => 'Opening a file', list_files: () => 'Looking in a folder',
  read_file: () => 'Reading a file', write_file: () => 'Writing a file', run_command: () => 'Running a command', look_at_screen: () => 'Looking at the screen', click: () => 'Clicking',
  type_text: () => 'Typing', press_keys: (a) => 'Pressing ' + (a.keys || ''), scroll: () => 'Scrolling', set_reminder: () => 'Logging a reminder', update_reminder: () => 'Changing a reminder',
  remember: () => 'Saving to memory', forget: () => 'Deleting a memory', read_log: () => 'Checking your Log' };
const label = (c, extra) => (LABEL[c.name] ? LABEL[c.name](c.args || {}) : (extra && extra(c)) || 'Working');

/** One job: plain JSON with no secrets, an id, updated_ms and the owning device, so it can later sync like the Log. */
export function newJob(goal, details, now, id, device = '') {
  return { id, goal, details: details || '', createdAt: now, device, state: S.QUEUED, updatedAt: now, updated_ms: now, history: [{ at: now, state: S.QUEUED }], plan: [], notes: [], steps: [], sources: [], recent: [],
    toolCalls: 0, workMs: 0, provider: null, pending: null, approval: null, question: null, answer: null, handover: false, browser: false, report: null, error: null, retryAt: 0, retries: 0, current: null, posted: false };
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
  out.push(`(Tool steps used: ${j.toolCalls} of ${MAX_TOOL_CALLS}. Time left: about ${Math.max(0, Math.floor((MAX_WORK_MS - j.workMs) / 60000))} min.)`);
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
 *  systemPrompt(): string;  notify(title, body, tag);  post(markdown): the report into the chat
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

  const changed = () => { for (const f of [...listeners]) { try { f(); } catch (e) { console.error(e); } } };
  const job = (id) => jobs.find((j) => j.id === id);
  const active = () => jobs.some((j) => [S.RUNNING, S.QUEUED, S.WAITING_RETRY].includes(j.state));

  function snapshot() {
    const old = jobs.filter((j) => FINAL.has(j.state)).sort((a, b) => b.updatedAt - a.updatedAt).slice(KEEP_FINISHED);
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

  function deploy(goal, details = '') {
    const g = (deps.plainMemory(goal) || String(goal).trim()).slice(0, 300);
    const j = newJob(g, String(details || '').trim().slice(0, 4000), now(), deps.uid(), deps.deviceId ? deps.deviceId() : '');
    jobs.push(j); persist(); changed(); pump();
    return j;
  }
  function pause(id) { const j = job(id); if (j && move(j, S.PAUSED, now())) { j.current = null; persist(); changed(); } }
  function resume(id) { const j = job(id); if (j && j.state === S.PAUSED && move(j, S.QUEUED, now())) { persist(); changed(); pump(); } }
  function stop(id) { const j = job(id); if (!j || FINAL.has(j.state)) return; workers.delete(id); move(j, S.CANCELLED, now()); j.current = null; j.handover = false; persist(); changed(); finalHook(j); pump(); }
  function approve(id, yes) { const j = job(id); if (!j || !j.approval || j.state !== S.AWAITING_APPROVAL) return; j.approval.decision = yes ? 'approved' : 'denied'; move(j, S.QUEUED, now()); persist(); changed(); pump(); }
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

  function pump() {
    if (!loaded) return;
    const t = now();
    for (const j of toStart(jobs.filter((x) => !workers.has(x.id) && x.state !== S.RUNNING), t, MAX_RUNNING - workers.size)) {
      if (workers.size >= MAX_RUNNING) break;
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
    j.report = deps.plainReply(report || '') || compiled(j);
    for (const p of j.plan) p.done = true;
    j.current = null; j.error = null;
    move(j, S.COMPLETED, now());
    if (!j.posted) { j.posted = true; deps.post(`**Atlas Bot finished:** ${j.goal}\n\n${j.report}`); }
    deps.notify('Atlas Bot is done', j.goal, 'bot:' + j.id);
    persist(); changed(); finalHook(j);
  }

  async function askApproval(j, a) {
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
    addRecent(j, `${what}:\n${String(r.out || '').slice(0, long ? 3500 : 1200)}`);
    persist();
    return r;
  }

  async function takeDecisions(j) {
    if (j.question && j.answer) { j.notes.push(`You asked the user: ${j.question}. They answered: ${j.answer}`); step(j, 'You answered: ' + j.answer.slice(0, 120)); }
    if (j.answer) { j.question = null; j.answer = null; }
    const a = j.approval; if (!a || !a.decision) return;
    j.approval = null;
    if (a.decision === 'denied') { j.notes.push(`The user said NO to: ${a.what}. Don't do it; find another way or finish without it.`); step(j, 'You said no to: ' + a.what); persist(); return; }
    step(j, 'You approved: ' + a.what);
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
    while (mine()) {
      tick();
      const wrapping = j.toolCalls >= MAX_TOOL_CALLS || j.workMs >= MAX_WORK_MS;
      const text = brief(j) + extra + (wrapping ? "\n\nYour budget for this job is used up. Call finish now with the best report you can from your plan and notes, and say plainly what's left undone." : '');
      extra = '';
      let sess;
      try { sess = deps.session(p, text, wrapping ? [FINISH] : myTools); } catch (e) { return fail(j, e.message || String(e)); }
      let rounds = 0, fresh = false;
      while (mine() && rounds < ROUNDS_PER_PART && !fresh) {
        rounds++;
        j.current = 'Thinking'; changed();
        let turn;
        try {
          turn = await deps.callWithKeys(p, sess, () => sess.call(deps.systemPrompt()), { onStatus: (st) => { if (mine()) { j.current = st || 'Thinking'; changed(); } }, cancelled: () => !mine() });
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
          if (c.name === 'finish') return complete(j, a.report || turn.text || compiled(j));
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
        if (j.toolCalls >= MAX_TOOL_CALLS || j.workMs >= MAX_WORK_MS) break;
      }
    }
  }

  return {
    jobs, init, deploy, pause, resume, stop, approve, answer, handBack, carryOn, remove, pump, job, active,
    visible: () => jobs.filter((j) => !FINAL.has(j.state) || now() - j.updatedAt < 7 * 86400000).sort((a, b) => b.createdAt - a.createdAt),
    onChange: (f) => { listeners.add(f); return () => listeners.delete(f); },
    idle: async () => { while (workers.size) await new Promise((r) => setTimeout(r, 5)); await saving; },
  };
}
