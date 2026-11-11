// Connects Atlas Bot (bots.js) to the app: the key pool and services (brain.js), the chat (store.js), saving and
// Windows notifications (the host).
import { db, saveChat, changed, uid } from './store.js';
import * as fitRules from './fit.js';
import * as brain from './brain.js';
import { plainReply, plainMemory } from './plain.js';
import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';
import { createExtras, skillsIndex, teamIndex, specialistPrompt, rulesPrompt, describeSchedule } from './botx.js';
import * as sync from './sync.js';
const host = window.atlasHost;

function systemPrompt(j = {}) {
  const name = (db.state.settings.name || '').trim() || 'the user';
  return `You are Atlas Bot, an agent that Atlas (${name}'s personal AI assistant) sends to do a job in the background on their Windows PC. You're not chatting: nobody reads what you write until you finish. Work through the job with your tools, then call finish with the report.

How you work:
1. Plan first: call update_plan with a short plan (3 to 8 steps). Keep it current: tick steps off with [x] as you finish them, and change the plan when you learn something.
2. Work step by step with tools. For research, run several focused web searches and read the best pages instead of guessing; cross-check important facts in two sources when you can. Prefer recent, primary sources.
3. Older tool results are dropped as you go to keep things small, so save what you'll need later (facts, figures, names, links, with the source) with note as soon as you find it. Your plan and notes are kept for the whole job.
4. Before you finish, check your work: does the report do the whole job? Is every number and claim backed by something you read or did?
5. If you truly can't go on without the user (a choice only they can make, a missing detail), call ask_user with one clear question. Decide sensible things yourself.
6. Call finish with the report.

The report:
- Neat Markdown: a title line (## heading), a short summary first, then sections with headings, bullet lists, **bold** key words, and a table when comparing options. No filler.
- Honest: say what you couldn't find, couldn't verify or didn't do. Never say you did something a tool result didn't show.
- Never show code, ids, tool or function names, or JSON, unless the job asked for code.
- If you used the web, end with a "Sources" section: each page you relied on as [Title or site](link).

Safety:
- Anything that sends a message, posts, buys or pays, deletes something, or changes an account or security setting needs the user's OK first: call request_approval saying exactly what you'll do, and wait. Commands that change things, writing files and pressing Enter are also checked automatically and will wait for the OK.
- Never type passwords, payment details or verification codes. Never delete files or change system settings unless the job clearly asks for it.

Using the PC (only when the job needs it): open_app, open_path, list_files and read_file to look around; for anything on screen, look_at_screen first, then click using its coordinates, then look again to check.

Budget: up to ${j.id ? callsOf(j) : MAX_TOOL_CALLS} tool steps and ${Math.round((j.id ? msOf(j) : MAX_WORK_MS) / 60000)} minutes for the job. When it's nearly used up, finish with what you have.
${teamPart(j)}

Now: ${new Date().toLocaleString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' })} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
${brain.logContext()}`;
}

/** Skills, the team, the user's standing rules and (for a specialist) who it is: from botx.js. */
function teamPart(j) {
  const out = [];
  const sk = skillsIndex(extras.data.skills); if (sk) out.push(sk);
  if ((j.depth || 0) < MAX_DEPTH) { const t = teamIndex(extras.data.specialists.filter((s) => s.id !== j.specialist)); if (t) out.push(t + '\nHand a part to one with delegate when it fits their speciality and the job is big enough to split; small jobs you do yourself.'); }
  const r = rulesPrompt(extras.data.policy); if (r) out.push(r);
  if (j.routine) { const rt = extras.data.routines.find((x) => x.id === j.routine); if (rt) out.push(`This job is the routine "${rt.name}" (${describeSchedule(rt.schedule)}). Keep the report short and focused on what's new since last time.`); }
  const sp = j.specialist ? extras.data.specialists.find((s) => s.id === j.specialist) : null;
  return (out.length ? '\n' + out.join('\n\n') : '') + specialistPrompt(sp);
}

/** The report joins the chat as an Atlas message (and so reaches the phone too). While Atlas is answering, it goes in before that exchange. */
function post(text) {
  const m = { id: uid(), user: false, text: plainReply(text), source: 'Atlas Bot', at: Date.now(), steps: [] };
  const busy = db.chat.findIndex((x) => x.pending);
  if (busy > 0) db.chat.splice(busy - 1, 0, m); else db.chat.push(m);
  saveChat(); changed('chat');
}

// skills, routines, specialist bots and the approval rules (botx.json), shared with the phone through sync
export const extras = createExtras({
  load: () => host.read('botx.json'),
  save: (text) => host.write('botx.json', text),
  now: () => Date.now(), uid, deviceId: () => db.state.deviceId || '',
  setTimer: (f, ms) => setTimeout(f, ms), clearTimer: (t) => clearTimeout(t),
  // a routine is due: send its bot (as its specialist, when it has one)
  fire: (r) => bots.deploy(r.goal, `This is the routine "${r.name}", which runs ${describeSchedule(r.schedule).toLowerCase()}.`, { specialist: r.specialist || null, routine: r.id }),
  // only one device runs each firing: the cloud decides (offline or not linked: the device that made the routine)
  claim: async (r, due) => {
    const c = await sync.claim(`routine:${r.id}:${due}`, 15 * 60000);
    return c ? c.mine : extras.mine(r);
  },
  changed: () => sync.syncSoon(),
});
for (const kind of ['skill', 'routine', 'specialist', 'botpolicy']) {
  sync.registerKind(kind, {
    list: () => Object.fromEntries(extras.synced().filter((x) => x.kind === kind).map((x) => [x.id, x.json])),
    apply: (id, rec) => extras.applySynced(kind, id, rec),
  });
}
extras.onChange(() => changed('bots'));

export const bots = createBots({
  load: () => host.read('bots.json'),
  save: (text) => host.write('bots.json', text),
  provider: () => brain.active(),
  session: (p, brief, tools) => brain.session(p, brain.keyFor(p.id), [], brief, [], tools),
  callWithKeys: (p, sess, call, opts) => brain.callWithKeys(p, sess, call, null, opts),
  isSwitch: (e) => e instanceof brain.Switch,
  isRetryable: (e) => e instanceof brain.Failure && (e.offline || [429, 500, 502, 503, 504].includes(e.code)),
  tools: brain.TOOLS,
  runTool: (call, opts) => brain.runTool(call, opts),
  systemPrompt,
  notify: (title, body, tag) => host.notify(title, String(body || '').slice(0, 240), tag),
  post,
  plainReply, plainMemory,
  now: () => Date.now(), uid, deviceId: () => db.state.deviceId || '',
  setTimer: (f, ms) => setTimeout(f, ms), clearTimer: (t) => clearTimeout(t),
  // one bot at a time when this PC is short of memory (fit.js); the profile is refreshed in the background
  slots: () => { brain.device.get().catch(() => {}); return fitRules.botSlots(brain.device.now()); },
  extras,
});
bots.onChange(() => changed('bots'));
brain.ui.deployBot = (goal, details, specialist) => {
  const sp = specialist ? extras.specialist(specialist) : null;
  return bots.deploy(goal, details, { specialist: sp ? sp.id : null });
};
brain.ui.saveSkill = (x) => extras.saveSkill(x);
brain.ui.saveRoutine = (x) => {
  const sp = x.specialist ? extras.specialist(x.specialist) : null;
  return extras.saveRoutine({ name: x.name, goal: x.goal, scheduleText: x.schedule, specialist: sp ? sp.id : null });
};
brain.ui.botTeam = () => [skillsIndex(extras.data.skills), teamIndex(extras.data.specialists)].filter(Boolean).join('\n');
