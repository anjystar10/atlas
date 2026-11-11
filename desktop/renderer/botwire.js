// Connects Atlas Bot (bots.js) to the app: the key pool and services (brain.js), the chat (store.js), saving and
// Windows notifications (the host).
import { db, saveChat, changed, uid } from './store.js';
import * as fitRules from './fit.js';
import * as brain from './brain.js';
import { plainReply, plainMemory } from './plain.js';
import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS } from './bots.js';
const host = window.atlasHost;

function systemPrompt() {
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

Budget: up to ${MAX_TOOL_CALLS} tool steps and ${MAX_WORK_MS / 60000} minutes for the job. When it's nearly used up, finish with what you have.

Now: ${new Date().toLocaleString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' })} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
${brain.logContext()}`;
}

/** The report joins the chat as an Atlas message (and so reaches the phone too). While Atlas is answering, it goes in before that exchange. */
function post(text) {
  const m = { id: uid(), user: false, text: plainReply(text), source: 'Atlas Bot', at: Date.now(), steps: [] };
  const busy = db.chat.findIndex((x) => x.pending);
  if (busy > 0) db.chat.splice(busy - 1, 0, m); else db.chat.push(m);
  saveChat(); changed('chat');
}

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
});
bots.onChange(() => changed('bots'));
brain.ui.deployBot = (goal, details) => bots.deploy(goal, details);
