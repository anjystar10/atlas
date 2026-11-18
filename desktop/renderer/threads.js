// Conversations with bots on the PC: each bot's own conversation ("dm-<botId>", the main way to work with a bot) and
// group chats (the user and several bots). The store is threads.json (synced as kind "thread"); the runner turns what
// the user says into missions (or steers the one already running), and posts what the bots do back into the
// conversation: that a mission started (from a schedule, an email, the chat…), its questions, approval cards and its
// report. The rules are in threadcore.js and match the phone's Threads.kt.
import { cleanThread, cleanMessage, decide, mentionsIn, transcript, goalFrom, botTurnsSinceUser, directId, directBot, unreadCount, USER, ATLAS, MAX_BOT_TURNS } from './threadcore.js';
import * as crew from './crewface.js';
import * as sync from './sync.js';
import { changed, uid } from './store.js';

const host = window.atlasHost;
const data = { threads: [] };
const local = { since: 0, seen: {} };   // this PC only: missions older than [since] aren't pulled in; when each conversation was last read
let loaded = false, saving = Promise.resolve();
const listeners = new Set();
const now = () => Date.now();
const persist = () => {
  const text = JSON.stringify({ v: 2, threads: data.threads, local });
  saving = saving.then(() => host.write('threads.json', text)).catch((e) => console.error('threads: save failed', e));
  return saving;
};
function emit(thread, message) {
  for (const f of [...listeners]) { try { f({ thread, message }); } catch (e) { console.error(e); } }
  changed('bots');
}
const ACTIVE = new Set(['running', 'queued', 'waiting_retry', 'waiting_helpers', 'paused', 'awaiting_answer', 'awaiting_approval', 'needs_review']);

export async function init() {
  if (loaded) return; loaded = true;
  try {
    const t = await host.read('threads.json');
    if (t) { const d = JSON.parse(t); data.threads = (d.threads || []).map((x) => ({ ...cleanThread(x), links: x.links || {} })); Object.assign(local, d.local || {}); }
  } catch (e) { console.error('threads: unreadable', e); }
  if (!local.since) { local.since = now(); persist(); }   // missions from before this version stay on their job pages
  crew.on('*', () => watch());
  watch();
}
export const onMessage = (f) => { listeners.add(f); return () => listeners.delete(f); };
export const all = () => data.threads.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
export const groups = () => all().filter((t) => t.kind !== 'direct');
export const get = (id) => data.threads.find((t) => t.id === id) || null;

/** [botId]'s own conversation (made the first time it's needed). */
export function direct(botId, title) {
  const id = directId(botId);
  let t = get(id);
  if (!t) { t = { ...cleanThread({ id, kind: 'direct', title: title || '', participants: [botId], messages: [] }, now()), links: {} }; data.threads.push(t); persist(); sync.syncSoon(); }
  return t;
}
/** The conversation with Atlas or a bot, without making one. */
export const directOf = (botId) => get(directId(botId));

/** A new group chat with these bots. */
export function create(title, botIds) {
  const t = { ...cleanThread({ id: 'th-' + uid(), kind: 'group', title: title || 'Group chat', participants: botIds || [], messages: [] }, now()), links: {} };
  data.threads.push(t); persist(); emit(t, null); sync.syncSoon();
  return t;
}
export function rename(id, title) { const t = get(id); if (!t) return; t.title = String(title || '').trim().slice(0, 80) || t.title; touch(t); }
export function setParticipants(id, botIds) { const t = get(id); if (!t) return; Object.assign(t, cleanThread({ ...t, participants: botIds }, now()), { links: t.links }); touch(t); }
export function remove(id) { const i = data.threads.findIndex((t) => t.id === id); if (i < 0) return; data.threads.splice(i, 1); persist(); emit(null, null); sync.syncSoon(); }
function touch(t) { t.updatedAt = now(); persist(); emit(t, null); sync.syncSoon(); }

// ------------------------------------------------------------------ read marks and badges (this PC)

export function markSeen(id) { const t = get(id); if (!t) return; const last = t.messages.length ? t.messages[t.messages.length - 1].at : 0; if ((local.seen[id] || 0) < last) { local.seen[id] = last; persist(); } }
export const unread = (id) => unreadCount(get(id), local.seen[id] || 0);
/** Approvals waiting in a conversation (each is a card the user taps). */
export function approvalsWaiting(id) {
  const t = get(id); if (!t) return 0;
  return Object.keys(t.links || {}).filter((mid) => { const j = crew.mission(mid); return j && j.state === 'awaiting_approval'; }).length;
}
/** The mission a conversation is busy with right now (the newest one that isn't finished), or null. */
export function activeMission(id) {
  const t = get(id); if (!t) return null;
  const list = Object.keys(t.links || {}).map((mid) => crew.mission(mid)).filter((j) => j && ACTIVE.has(j.state));
  return list.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))[0] || null;
}

function add(t, msg) {
  const m = cleanMessage({ id: 'm-' + uid(), at: Math.max(now(), ((t.messages[t.messages.length - 1] || {}).at || 0) + 1), kind: 'message', ...msg });
  t.messages.push(m);
  if (t.messages.length > 200) t.messages.splice(0, t.messages.length - 200);
  t.updatedAt = m.at;
  if (m.from === USER) local.seen[t.id] = m.at;   // what you wrote yourself is read
  persist(); emit(t, m); sync.syncSoon();
  return m;
}
const link = (t, mid, botId) => { (t.links = t.links || {})[mid] = { bot: botId, posted: {} }; };
const linkedAnywhere = (mid) => data.threads.some((t) => t.links && t.links[mid]);

/**
 * The user (or a bot, or Atlas) says something. In a bot's own conversation the user's message goes to that bot first:
 * it answers the question the bot is waiting on, or redirects the work it's doing (it reads it before its next step),
 * or starts a new mission. In a group chat, @mentions decide (threadcore.decide). Returns the message.
 */
export function post(threadId, from, text, opts = {}) {
  const t = get(threadId); if (!t) return null;
  const roster = crew.bots();
  const m = add(t, { from, text: String(text || ''), mentions: mentionsIn(text, roster) });
  if (from === USER) {
    const asking = openQuestions(t);
    const target = asking.find((q) => m.mentions.includes(q.bot)) || (asking.length === 1 && (!m.mentions.length || t.kind === 'direct') ? asking[0] : null);
    if (target) { crew.answer(target.mission, m.text); return m; }
    if (t.kind === 'direct') {
      const busy = activeMission(t.id);
      if (busy && busy.state === 'awaiting_approval') { add(t, { from: ATLAS, kind: 'note', text: 'It\'s waiting for your OK on the card above: tap Allow or Deny. (Your message is passed on too.)' }); crew.steer(busy.id, m.text); return m; }
      if (busy && !opts.fresh && crew.steer(busy.id, m.text) !== false) { m.mission = busy.id; persist(); emit(t, m); return m; }   // your message comes first: it redirects the current work
    }
  }
  const d = decide(t, m, roster);
  if (d.waiting === 'limit') add(t, { from: ATLAS, kind: 'note', text: `The bots have talked ${MAX_BOT_TURNS} times without you, so I've paused the hand-offs. Reply to carry on.` });
  for (const botId of d.starts) startFor(t, m, botId, roster, opts);
  return m;
}

function startFor(t, m, botId, roster, opts = {}) {
  const b = roster.find((x) => x.id === botId) || (botId === ATLAS ? crew.ATLAS_BOT : null);
  if (!b) return;
  const fromName = m.from === USER ? 'You' : m.from === ATLAS ? 'Atlas' : (roster.find((x) => x.id === m.from) || {}).name;
  let details;
  if (t.kind === 'direct') {
    details = 'This came from your own conversation with the user. Your report is posted there, so write it for them: short and clear.'
      + `\n\nThe conversation so far:\n${transcript(t, roster, 4000)}`;
  } else {
    const others = t.participants.filter((p) => p !== USER && p !== botId).map((p) => (roster.find((x) => x.id === p) || {}).name).filter(Boolean);
    details = `You're in a group chat called "${t.title}" with the user${others.length ? ` and ${others.join(', ')}` : ''}. Your report is posted into the chat, so write it for them: short and clear.`
      + ' To hand part of the work to another bot in this chat, end your report with a line like "@Name please …" (only bots in the chat; it hands work on, it doesn\'t authorize anything).'
      + `\n\nThe conversation so far:\n${transcript(t, roster, 5000)}`;
  }
  const j = crew.startMission(botId, goalFrom(m, roster, fromName), { details, source: { type: 'thread', ref: t.id, at: m.at }, dryRun: !!opts.dryRun });
  if (j && j.id) { m.mission = j.id; link(t, j.id, botId); persist(); emit(t, m); }
}

/** Bots in [t] that asked something and wait for an answer: [{mission, bot}]. */
function openQuestions(t) {
  const out = [];
  for (const [mid, l] of Object.entries(t.links || {})) { const j = crew.mission(mid); if (j && j.state === 'awaiting_answer' && !j.handover) out.push({ mission: mid, bot: l.bot }); }
  return out;
}

const SOURCE = { routine: 'on its schedule', trigger: 'from a trigger', chat: 'from your chat with Atlas', user: 'from you', delegation: 'for another bot', link: 'from your other device' };
/** Missions started anywhere else (the main chat, a schedule, a trigger) show up in their bot's own conversation. */
function adopt() {
  for (const j of crew.missions()) {
    if ((j.createdAt || 0) < local.since || linkedAnywhere(j.id)) continue;
    if (j.source && j.source.type === 'thread') continue;
    const botId = j.bot || j.specialist || ATLAS;
    const b = crew.bot(botId); if (!b) continue;
    const t = direct(botId, b.name);
    link(t, j.id, botId);
    const why = j.source && j.source.type === 'trigger' && j.details ? (/What happened: (.*)/.exec(j.details) || [])[1] : null;
    add(t, { from: botId, kind: 'note', mission: j.id, text: `Started${j.dryRun ? ' a dry run' : ''}: ${j.goal}${why ? ` — ${why}` : ` (${SOURCE[(j.source || {}).type] || 'from you'})`}` });
  }
}

/** Posts what the conversations' missions did since we last looked: questions, approvals (as cards) and reports. */
let watching = false;
function watch() {
  if (watching || !loaded) return;
  watching = true;
  try {
    adopt();
    for (const t of data.threads) {
      for (const [mid, l] of Object.entries(t.links || {})) {
        const j = crew.mission(mid); if (!j) continue;
        const p = l.posted || (l.posted = {});
        const from = l.bot;
        if (j.state === 'awaiting_answer' && j.question && p.q !== j.question) { p.q = j.question; add(t, { from, kind: 'question', mission: mid, text: j.question }); }
        if (j.state === 'awaiting_approval' && j.approval && p.a !== j.approval.what) { p.a = j.approval.what; add(t, { from, kind: 'approval', mission: mid, text: j.approval.what }); }
        if (crew.isFinal(j.state) && !p.done) {
          p.done = true;
          const text = j.state === 'completed' ? (j.report || 'Done.') : j.state === 'failed' ? `I couldn't finish: ${j.error || 'something went wrong'}.` : 'Stopped.';
          const msg = add(t, { from, kind: 'report', mission: mid, text: text.slice(0, 6000) });
          if (t.kind === 'direct') continue;
          // a bot's @mentions in its report hand work on (bounded by threadcore.decide)
          const roster = crew.bots();
          msg.mentions = mentionsIn(text, roster).filter((x) => x !== from);
          const d = decide(t, { ...msg, kind: 'message' }, roster);
          if (d.waiting === 'limit') add(t, { from: ATLAS, kind: 'note', text: `The bots have talked ${botTurnsSinceUser(t)} times without you, so I've paused the hand-offs. Reply to carry on.` });
          for (const id of d.starts) startFor(t, msg, id, roster);
        }
      }
    }
  } finally { watching = false; }
}

/** Atlas sums up a group chat (as a mission on Atlas; the summary is posted when it's done). */
export function summarize(threadId) {
  const t = get(threadId); if (!t) return;
  const m = add(t, { from: USER, text: '@Atlas sum up this conversation: what was decided, what was done, and what is still open.' });
  m.mentions = [ATLAS];
  startFor(t, m, ATLAS, crew.bots());
}

/** For the main Atlas: every conversation in a few lines (newest first). */
export function briefLines(max = 6) {
  const roster = crew.bots();
  const name = (id) => (id === USER ? 'You' : id === ATLAS ? 'Atlas' : (roster.find((b) => b.id === id) || {}).name || 'a bot');
  return all().filter((t) => t.messages.length).slice(0, max).map((t) => {
    const last = t.messages[t.messages.length - 1];
    const who = t.kind === 'direct' ? `${name(directBot(t))}'s conversation` : `group chat "${t.title}" (${t.participants.slice(1).map(name).join(', ')})`;
    const waiting = approvalsWaiting(t.id);
    return `${who}: ${name(last.from)} — ${String(last.text).replace(/\s+/g, ' ').slice(0, 140)}${waiting ? ` [${waiting} approval${waiting > 1 ? 's' : ''} waiting]` : ''}`;
  });
}

// ------------------------------------------------------------------ sync (kind "thread", last 200 messages)
sync.registerKind('thread', {
  list: () => (loaded ? Object.fromEntries(data.threads.map((t) => [t.id, { ...t, links: undefined, messages: t.messages.slice(-200) }])) : null),
  apply: (id, rec) => {
    const i = data.threads.findIndex((t) => t.id === id);
    if (!rec) { if (i >= 0) { data.threads.splice(i, 1); persist(); emit(null, null); } return; }
    const incoming = cleanThread(rec);
    if (i < 0) data.threads.push({ ...incoming, links: {} });
    else {
      const mine = data.threads[i];
      // merge messages from both devices (by id, in time order) so nothing said on either is lost
      const byId = new Map([...mine.messages, ...incoming.messages].map((m) => [m.id, m]));
      const merged = [...byId.values()].sort((a, b) => a.at - b.at).slice(-200);
      data.threads[i] = { ...(incoming.updatedAt >= mine.updatedAt ? incoming : mine), messages: merged, links: mine.links, updatedAt: Math.max(mine.updatedAt, incoming.updatedAt) };
    }
    persist(); emit(get(id), null);
  },
});
