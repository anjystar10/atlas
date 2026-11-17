// Group chats on the PC: the store (threads.json, synced as kind "thread") and the runner that turns @mentions into
// missions and posts what the bots say back into the thread. The rules themselves are in threadcore.js.
import { cleanThread, cleanMessage, decide, mentionsIn, transcript, goalFrom, botTurnsSinceUser, USER, ATLAS, MAX_BOT_TURNS } from './threadcore.js';
import * as crew from './crewface.js';
import * as sync from './sync.js';
import { changed, uid } from './store.js';

const host = window.atlasHost;
const data = { threads: [] };
let loaded = false, saving = Promise.resolve();
const listeners = new Set();
const now = () => Date.now();
const persist = () => { const text = JSON.stringify({ v: 2, threads: data.threads }); saving = saving.then(() => host.write('threads.json', text)).catch((e) => console.error('threads: save failed', e)); return saving; };
function emit(thread, message) {
  for (const f of [...listeners]) { try { f({ thread, message }); } catch (e) { console.error(e); } }
  changed('bots');
}

export async function init() {
  if (loaded) return; loaded = true;
  try { const t = await host.read('threads.json'); if (t) { const d = JSON.parse(t); data.threads = (d.threads || []).map((x) => cleanThread(x)); } } catch (e) { console.error('threads: unreadable', e); }
  crew.on('*', () => watch());
  crew.on('mission.updated', () => watch());
}
export const onMessage = (f) => { listeners.add(f); return () => listeners.delete(f); };
export const all = () => data.threads.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
export const get = (id) => data.threads.find((t) => t.id === id) || null;

/** A new group chat with these bots. */
export function create(title, botIds) {
  const t = cleanThread({ id: 'th-' + uid(), title: title || 'Group chat', participants: botIds || [], messages: [] }, now());
  data.threads.push(t); persist(); emit(t, null); sync.syncSoon();
  return t;
}
export function rename(id, title) { const t = get(id); if (!t) return; t.title = String(title || '').trim().slice(0, 80) || t.title; touch(t); }
export function setParticipants(id, botIds) { const t = get(id); if (!t) return; Object.assign(t, cleanThread({ ...t, participants: botIds }, now())); touch(t); }
export function remove(id) { const i = data.threads.findIndex((t) => t.id === id); if (i < 0) return; data.threads.splice(i, 1); persist(); emit(null, null); sync.syncSoon(); }
function touch(t) { t.updatedAt = now(); persist(); emit(t, null); sync.syncSoon(); }

function add(t, msg) {
  const m = cleanMessage({ id: 'm-' + uid(), at: now(), kind: 'message', ...msg });
  t.messages.push(m);
  if (t.messages.length > 200) t.messages.splice(0, t.messages.length - 200);
  t.updatedAt = m.at;
  persist(); emit(t, m); sync.syncSoon();
  return m;
}

/**
 * The user (or a bot, or Atlas) says something in a thread. A user message that answers a bot's open question goes to
 * that bot; otherwise @mentions set missions going (threadcore.decide). Returns the message.
 */
export function post(threadId, from, text) {
  const t = get(threadId); if (!t) return null;
  const roster = crew.bots();
  const m = add(t, { from, text: String(text || ''), mentions: mentionsIn(text, roster) });
  if (from === USER) {
    // a bot here asked something and is waiting: this message answers it (when it's addressed to it, or it's the only one asking)
    const asking = openQuestions(t);
    const target = asking.find((q) => m.mentions.includes(q.bot)) || (asking.length === 1 && !m.mentions.length ? asking[0] : null);
    if (target) { crew.answer(target.mission, m.text); return m; }
  }
  const d = decide(t, m, roster);
  if (d.waiting === 'limit') add(t, { from: ATLAS, kind: 'note', text: `The bots have talked ${MAX_BOT_TURNS} times without you, so I've paused the hand-offs. Reply to carry on.` });
  for (const botId of d.starts) startFor(t, m, botId, roster);
  return m;
}

function startFor(t, m, botId, roster) {
  const b = roster.find((x) => x.id === botId) || (botId === ATLAS ? crew.ATLAS_BOT : null);
  if (!b) return;
  const fromName = m.from === USER ? 'You' : m.from === ATLAS ? 'Atlas' : (roster.find((x) => x.id === m.from) || {}).name;
  const others = t.participants.filter((p) => p !== USER && p !== botId).map((p) => (roster.find((x) => x.id === p) || {}).name).filter(Boolean);
  const details = `You're in a group chat called "${t.title}" with the user${others.length ? ` and ${others.join(', ')}` : ''}. Your report is posted into the chat, so write it for them: short and clear.`
    + ` To hand part of the work to another bot in this chat, end your report with a line like "@Name please …" (only bots in the chat; it hands work on, it doesn't authorize anything).`
    + `\n\nThe conversation so far:\n${transcript(t, roster, 5000)}`;
  const j = crew.startMission(botId, goalFrom(m, roster, fromName), { details, source: { type: 'thread', ref: t.id, at: m.at } });
  if (j && j.id) { m.mission = j.id; (t.links = t.links || {})[j.id] = { bot: botId, posted: {} }; persist(); emit(t, m); }
}

/** Bots in [t] that asked something and wait for an answer: [{mission, bot}]. */
function openQuestions(t) {
  const out = [];
  for (const [mid, l] of Object.entries(t.links || {})) { const j = crew.mission(mid); if (j && j.state === 'awaiting_answer' && !j.handover) out.push({ mission: mid, bot: l.bot }); }
  return out;
}

/** Posts what the thread's missions did since we last looked: questions, approvals (as cards) and reports. */
function watch() {
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
        // a bot's @mentions in its report hand work on (bounded by threadcore.decide)
        const roster = crew.bots();
        msg.mentions = mentionsIn(text, roster).filter((x) => x !== from);
        const d = decide(t, { ...msg, kind: 'message' }, roster);
        if (d.waiting === 'limit') add(t, { from: ATLAS, kind: 'note', text: `The bots have talked ${botTurnsSinceUser(t)} times without you, so I've paused the hand-offs. Reply to carry on.` });
        for (const id of d.starts) startFor(t, msg, id, roster);
      }
    }
  }
}

/** Atlas sums up the thread (as a mission on Atlas; the summary is posted when it's done). */
export function summarize(threadId) {
  const t = get(threadId); if (!t) return;
  const m = add(t, { from: USER, text: '@Atlas sum up this conversation: what was decided, what was done, and what is still open.' });
  m.mentions = [ATLAS];
  startFor(t, m, ATLAS, crew.bots());
}

// ------------------------------------------------------------------ sync (kind "thread", last 200 messages)
sync.registerKind('thread', {
  list: () => (loaded ? Object.fromEntries(data.threads.map((t) => [t.id, { ...t, links: undefined, messages: t.messages.slice(-200) }])) : null),
  apply: (id, rec) => {
    const i = data.threads.findIndex((t) => t.id === id);
    if (!rec) { if (i >= 0) { data.threads.splice(i, 1); persist(); emit(null, null); } return; }
    const incoming = cleanThread(rec);
    if (i < 0) data.threads.push(incoming);
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
