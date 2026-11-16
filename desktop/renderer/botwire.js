// Connects Atlas Bot (bots.js) to the app: the key pool and services (brain.js), the chat (store.js), saving and
// Windows notifications (the host).
import { db, saveChat, changed, uid, pref, setPref } from './store.js';
import * as fitRules from './fit.js';
import * as brain from './brain.js';
import { plainReply, plainMemory } from './plain.js';
import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';
import { createExtras, skillsIndex, teamIndex, specialistPrompt, rulesPrompt, describeSchedule } from './botx.js';
import * as sync from './sync.js';
import { BROWSER_TOOLS, BROWSER, VAULT_TOOLS, browserLabel } from './botbrowse.js';
import { FILE_TOOLS, FILES, filesLabel, runFiles, filesFooter } from './botfiles.js';
import * as conn from './connect.js';   // connections: MCP servers and web APIs
import { PROJECTS, projectLabel, runProject, projectApproval, PROJECTS_PROMPT, registerProjects } from './projects.js';   // >>> projects <<<
import * as aware from './aware.js';
import * as trade from './trade.js';   // trading: bots' LIVE orders wait for the user's tap on the job page
import { LABELS as BOT_LABELS, FINAL as BOT_FINAL, S as BOT_S } from './bots.js';
import * as mail from './mail.js';   // >>> email (registered in aware.js; its approvals are here)
const host = window.atlasHost;

function systemPrompt(j = {}) {
  const name = (db.state.settings.name || '').trim() || 'the user';
  return `You are Atlas Bot, an agent that Atlas (${name}'s personal AI assistant) sends to do a job in the background on their Windows PC. You're not chatting: nobody reads what you write until you finish. Work through the job with your tools, then call finish with the report.

How you work:
1. Plan first: call update_plan with a plan of as many steps as the job really needs (a small job may need 3, a big one 30 or more; there's no fixed number). The plan gives you structure, not a limit: add, split or reorder steps whenever you learn something, and tick steps off with [x] as you finish them.
   Your job is to get the thing DONE, not just to find out about it: research only as far as doing it needs, unless the user asked for information. Keep going until every step is done or truly can't be.
2. Work step by step with tools. For research, run several focused web searches and read the best pages instead of guessing; cross-check important facts in two sources when you can. Prefer recent, primary sources.
   You can also DO things on websites, not just read about them: you have your own browser (browser_* tools), a real window with its own sign-ins. Use it to fill in forms, search inside sites, compare live prices, add to a cart, book, register, download files and upload them.
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

Using your browser:
- browser_open a page, then work from what browser_read shows: the page text and a numbered list of links, buttons and fields. Act with browser_click / browser_type / browser_select using those numbers, then check the result it returns (did the page change? is the value there? did a confirmation appear?) before the next step. The numbers change when the page changes: use the latest list.
- Prefer the site's own search box and menus over guessing addresses. If the page has more below, browser_scroll down. If the text misses something (images, charts, a layout), browser_screenshot.
- Signing in, passwords, verification codes, CAPTCHAs or "are you human" checks, passkeys and payment details are the user's to do: call browser_handover saying what they need to do, and wait. Never try to get around a check.
- Clicks or keys that send, post, buy or pay, book, sign up, subscribe, accept terms or delete are checked on the page and wait for the user's OK automatically; say clearly in your plan what you're about to do.
- Downloads land in this job's folder on the PC (Documents/Atlas Bot); browser_upload takes files from there.
- In the report, say exactly what you did on which site and how you know it worked (the confirmation text, an order or reference number, the final address). Never claim a step happened unless a browser result showed it.

Files you hand over (your job folder, Documents/Atlas Bot):
- When the job calls for something the user will keep, make the real file: make_document for a report, brief, letter or plan (a typeset PDF); make_spreadsheet for data, comparisons, budgets or trackers (Excel, with formulas where they help); make_page for an interactive view with tables to sort and filter and charts.
- workspace_write / workspace_read / workspace_list keep drafts, notes and data in the job folder; downloads land there too.
- The report still gives the answer itself (the key findings), then names the files you made. They show in Atlas Bot › Library.

${host.projects ? PROJECTS_PROMPT + '\n\n' : ''}Using the PC (only when the job needs it): open_app, open_path, list_files and read_file to look around; for anything on screen, look_at_screen first, then click using its coordinates, then look again to check.

Budget: up to ${j.id ? callsOf(j) : MAX_TOOL_CALLS} tool steps and ${Math.round((j.id ? msOf(j) : MAX_WORK_MS) / 60000)} minutes for the job. When it's nearly used up, finish with what you have.
${teamPart(j)}

Now: ${new Date().toLocaleString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' })} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
${conn.promptLine()}
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

/** A browser tool, in the job's own window. An approved click is made only if the page still shows what the user approved. */
async function runBrowser(call, { approved, job, approvedWhat } = {}) {
  if (!host.botBrowser || !job) return { out: "Error: the browser isn't available here." };
  if (approved && approvedWhat && ['browser_click', 'browser_type', 'browser_press'].includes(call.name)) {
    const got = await host.botBrowser.check(job.id, job.goal, call.name, call.args || {}); const now = got && typeof got === 'object' ? got.what : got;   // check() may say more than a sentence
    if (now !== approvedWhat) return { out: "The page changed since the user said yes, so nothing was clicked. Read the page again (browser_read); if it's still needed, the OK will be asked again." };
  }
  return host.botBrowser.run(job.id, job.goal, call.name, call.args || {});
}

export const bots = createBots({
  load: () => host.read('bots.json'),
  save: (text) => host.write('bots.json', text),
  provider: () => brain.active(),
  session: (p, brief, tools) => brain.session(p, brain.keyFor(p.id), [], brief, [], tools),
  callWithKeys: (p, sess, call, opts) => brain.callWithKeys(p, sess, call, null, opts),
  isSwitch: (e) => e instanceof brain.Switch,
  isRetryable: (e) => e instanceof brain.Failure && (e.offline || [429, 500, 502, 503, 504].includes(e.code)),
  tools: () => [...brain.TOOLS, ...conn.declarations('bots'), ...aware.botTools(), ...(host.trading ? trade.TOOLS : [])],   // connections come and go while Atlas runs; capabilities marked for bots
  extraApproval: (name, args) => conn.approvalFor(name, args) || mail.approvalFor(name, args),   // >>> email: sending and deleting ask
  extraTools: [...(host.botBrowser ? BROWSER_TOOLS : []), ...(host.vault ? VAULT_TOOLS : []), ...(host.botFiles ? FILE_TOOLS : [])],   // project tools come through aware.botTools()
  labelFor: (c) => browserLabel(c) || filesLabel(c) || projectLabel(c) || trade.label(c) || conn.label(c.name, c.args || {}) || mail.label(c.name, c.args || {}),
  runTool: (call, opts) => (call.name === 'vault_list' ? (host.vault ? host.vault.model().then((out) => ({ out, step: 'Checked your saved sign-ins' })) : Promise.resolve({ out: "Error: the vault isn't available here." }))
    : BROWSER.has(call.name) ? runBrowser(call, opts) : FILES.has(call.name) ? runFiles(call, opts && opts.job) : PROJECTS.has(call.name) ? runProject(call, { approved: !!(opts && opts.approved) }) : trade.NAMES.has(call.name) ? trade.runForBot(call, opts) : brain.runTool(call, opts)),
  reportFooter: filesFooter,
  checkApproval: (call, job) => (BROWSER.has(call.name) && host.botBrowser ? host.botBrowser.check(job.id, job.goal, call.name, call.args || {}) : PROJECTS.has(call.name) ? projectApproval(call) : trade.NAMES.has(call.name) ? trade.checkForBot(call, job) : Promise.resolve(null)),
  onFinal: (job) => { if (job.browser && host.botBrowser) host.botBrowser.close(job.id); },
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

// >>> awareness (aware.js): the main Atlas sees the bots, the Library, the AI services and its own browser
registerProjects(aware);   // >>> projects: their tools in the chat and for bots, and a line in the brief <<<
let chatBrowserTimer = null;
aware.use({
  bots, S: BOT_S, labels: BOT_LABELS, final: BOT_FINAL,
  conn: () => conn.state.snapshot,
  library: async () => { if (!host.botFiles) return []; const l = await host.botFiles.library(); return (Array.isArray(l) ? l : []).map((f) => ({ title: f.title || f.name, kind: f.kind, at: f.at || f.mtime })); },
  services: () => ({ ready: brain.PROVIDERS.filter((p) => brain.readyKey(p.id)).map((p) => p.name), dontSwitch: brain.dontSwitch() }),
  browser: host.botBrowser ? {
    check: (id, goal, name, args) => host.botBrowser.check(id, goal, name, args),
    run: async (id, goal, name, args) => {
      // the chat's window closes itself after ten quiet minutes
      clearTimeout(chatBrowserTimer); chatBrowserTimer = setTimeout(() => host.botBrowser.close(id).catch(() => {}), 10 * 60000);
      return host.botBrowser.run(id, goal, name, args);
    },
  } : null,
  mode: (what, tool, args) => extras.mode(extras.categoryOf(tool, args || {}, what)),
  seen: { get: () => pref('awareSeen', []), set: (ids) => setPref('awareSeen', ids) },
});
// <<< awareness
