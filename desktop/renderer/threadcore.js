// Group chats (threads): the pure rules, shared in spirit with the phone's Threads.kt (cases in tools/threads-fixtures.json).
// A thread is the user plus several standing bots in one conversation (docs/BOTS_V2.md §2.4). The user @mentions a bot to
// give it the job; bots may @mention each other to hand work on, but only within limits, so two bots can never keep each
// other going forever: after MAX_BOT_TURNS bot messages without the user, or MAX_PAIR_HOPS hand-offs between the same two
// bots, the thread waits on the user. Only messages from the user can authorize anything (§8).

export const V = 2;
export const MAX_MESSAGES = 200;          // kept per thread (and synced)
export const MAX_TEXT = 8000;             // one message
export const MAX_BOT_TURNS = 8;           // bot messages in a row without the user before the thread waits on them
export const MAX_PAIR_HOPS = 3;           // hand-offs between the same two bots since the user last spoke
export const MAX_PARTICIPANTS = 7;        // the user + up to six bots
export const USER = 'user', ATLAS = 'atlas';
export const DIRECT = 'dm-';              // a bot's own conversation has the id "dm-<botId>" (the same on both devices)

/** The id of [botId]'s own conversation. */
export const directId = (botId) => DIRECT + clip(botId, 56);
/** The bot a conversation belongs to (its own conversation), or null for a group chat. */
export const directBot = (t) => (t && t.kind === 'direct' ? t.participants[1] || null : null);
/** Messages since [seenAt] that the user hasn't read (anything not from the user). */
export function unreadCount(thread, seenAt) {
  let n = 0;
  for (const m of (thread && thread.messages) || []) if (m.from !== USER && (m.at || 0) > (seenAt || 0)) n++;
  return n;
}
/** The newest message worth showing in a list row (notes from Atlas count; empty ones don't). */
export function preview(thread) {
  const ms = (thread && thread.messages) || [];
  for (let i = ms.length - 1; i >= 0; i--) if (String(ms[i].text || '').trim()) return ms[i];
  return null;
}

const clip = (s, n) => String(s == null ? '' : s).trim().slice(0, n);

/** A thread in the v2 shape (unknown fields kept, messages kept to the last MAX_MESSAGES). */
export function cleanThread(x, now = 0, id = '') {
  const src = x || {};
  const out = { ...src };
  out.id = clip(src.id, 60) || id;
  out.v = V;
  out.kind = src.kind === 'direct' || String(out.id).startsWith(DIRECT) ? 'direct' : 'group';
  const ps = (Array.isArray(src.participants) ? src.participants : []).map((p) => clip(p, 60)).filter(Boolean);
  if (out.kind === 'direct') {
    // a bot's own conversation: the user and that one bot (Atlas included: its conversation is "dm-atlas")
    const b = String(out.id).startsWith(DIRECT) ? String(out.id).slice(DIRECT.length) : ps.find((p) => p !== USER) || ATLAS;
    out.participants = [USER, b];
    out.title = clip(src.title, 80) || 'Conversation';
  } else {
    out.title = clip(src.title, 80) || 'Group chat';
    out.participants = [USER, ...ps.filter((p) => p !== USER && p !== ATLAS).filter((p, i, a) => a.indexOf(p) === i)].slice(0, MAX_PARTICIPANTS);
  }
  out.messages = (Array.isArray(src.messages) ? src.messages : []).filter((m) => m && m.id && (m.text || m.kind))
    .map((m) => cleanMessage(m)).slice(-MAX_MESSAGES);
  out.updatedAt = Number(src.updatedAt) || now || 0;
  return out;
}
export function cleanMessage(m) {
  const out = { ...m };
  out.id = clip(m.id, 60);
  out.at = Number(m.at) || 0;
  out.from = clip(m.from, 60) || USER;
  out.text = String(m.text == null ? '' : m.text).slice(0, MAX_TEXT);
  out.mentions = (Array.isArray(m.mentions) ? m.mentions : []).map((x) => clip(x, 60)).filter(Boolean);
  out.mission = m.mission ? clip(m.mission, 60) : null;
  if (m.kind) out.kind = clip(m.kind, 20);   // "message" (default) | "approval" | "question" | "report" | "note"
  return out;
}

/**
 * The bots @mentioned in [text], in the order they appear. [bots] is [{id, name}]. A mention is "@" followed by a bot's
 * name (any case; spaces in names allowed; the longest name that fits wins, so "@Reel Poster" isn't "@Reel") or its id.
 * "@Atlas" is the chief of staff ("atlas"). Duplicates are dropped.
 */
export function mentionsIn(text, bots) {
  const t = String(text || '');
  const names = [];
  for (const b of bots || []) {
    if (!b || !b.id) continue;
    names.push({ key: String(b.name || '').toLowerCase(), id: b.id });
    names.push({ key: String(b.id).toLowerCase(), id: b.id });
  }
  names.push({ key: 'atlas', id: ATLAS });
  const list = names.filter((n) => n.key).sort((a, b) => b.key.length - a.key.length);
  const out = [];
  const lower = t.toLowerCase();
  let i = 0;
  while ((i = lower.indexOf('@', i)) >= 0) {
    if (i > 0 && /[\w.]/.test(lower[i - 1])) { i++; continue; }   // an email address, not a mention
    const rest = lower.slice(i + 1);
    const hit = list.find((n) => rest.startsWith(n.key) && !/[a-z0-9_]/.test(rest[n.key.length] || ''));
    if (hit) { if (!out.includes(hit.id)) out.push(hit.id); i += 1 + hit.key.length; } else i++;
  }
  return out;
}

/** The message with the @mentions taken out (what the bot is asked to do). */
export function stripMentions(text, bots) {
  let t = String(text || '');
  const keys = [];
  for (const b of bots || []) { if (b && b.name) keys.push(b.name); if (b && b.id) keys.push(b.id); }
  keys.push('Atlas');
  keys.sort((a, b) => b.length - a.length);
  for (const k of keys) {
    const esc = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    t = t.replace(new RegExp(`(^|[^\\w.])@${esc}(?![\\w])[,:]?`, 'gi'), '$1');
  }
  return t.replace(/\s{2,}/g, ' ').trim();
}

/** Bot (and Atlas) messages since the user last spoke. */
export function botTurnsSinceUser(thread) {
  let n = 0;
  const ms = (thread && thread.messages) || [];
  for (let i = ms.length - 1; i >= 0; i--) { if (ms[i].from === USER) break; if (!ms[i].kind || ms[i].kind === 'message' || ms[i].kind === 'report') n++; }
  return n;
}
/** Hand-offs from [a] to [b] or [b] to [a] since the user last spoke. */
export function pairHops(thread, a, b) {
  let n = 0;
  const ms = (thread && thread.messages) || [];
  for (let i = ms.length - 1; i >= 0; i--) {
    const m = ms[i];
    if (m.from === USER) break;
    if ((m.from === a && m.mentions.includes(b)) || (m.from === b && m.mentions.includes(a))) n++;
  }
  return n;
}

/**
 * What a new message should set going. Returns {starts: [botId…], waiting: null | "limit", hint: null | "mention"}.
 * - From the user: every @mentioned bot gets the job (Atlas included). With no mention: the only bot in the thread
 *   gets it; with several bots, Atlas takes it (and may hand parts on).
 * - From a bot: each OTHER bot it @mentions gets a hand-off, unless the thread has run out of turns without the user
 *   (then nothing starts and the thread waits on the user). A bot mentioning itself, or a bot not in the thread, is ignored.
 * - From Atlas: Atlas's own @mentions hand work on the same way as a bot's.
 * [thread] must already include [msg] as its last message.
 */
export function decide(thread, msg, bots) {
  const m = msg || {};
  const known = new Set((bots || []).map((b) => b.id));
  const inThread = new Set(((thread && thread.participants) || []).filter((p) => p !== USER));
  const mentions = (m.mentions && m.mentions.length ? m.mentions : mentionsIn(m.text, bots));
  if (m.kind && m.kind !== 'message') return { starts: [], waiting: null, hint: null };
  if (m.from === USER) {
    if (thread && thread.kind === 'direct') return { starts: [thread.participants[1]], waiting: null, hint: null };   // a bot's own conversation: it's the one asked
    const picked = mentions.filter((id) => id === ATLAS || known.has(id));
    if (picked.length) return { starts: picked, waiting: null, hint: null };
    const bs = [...inThread].filter((id) => known.has(id));
    if (bs.length === 1) return { starts: bs, waiting: null, hint: null };
    return { starts: [ATLAS], waiting: null, hint: bs.length ? 'mention' : null };
  }
  const targets = mentions.filter((id) => id !== m.from && id !== USER && (id === ATLAS || (known.has(id) && inThread.has(id))));
  if (!targets.length) return { starts: [], waiting: null, hint: null };
  if (botTurnsSinceUser(thread) >= MAX_BOT_TURNS) return { starts: [], waiting: 'limit', hint: null };
  const ok = targets.filter((id) => pairHops(thread, m.from, id) <= MAX_PAIR_HOPS);
  return { starts: ok, waiting: ok.length < targets.length ? 'limit' : null, hint: null };
}

/** The conversation as text for a bot (or for Atlas's summary): newest last, kept under [max] characters. */
export function transcript(thread, bots, max = 6000) {
  const nameOf = (id) => (id === USER ? 'You (the user)' : id === ATLAS ? 'Atlas' : ((bots || []).find((b) => b.id === id) || {}).name || id);
  const lines = [];
  for (const m of (thread && thread.messages) || []) {
    if (m.kind === 'approval') continue;
    lines.push(`${nameOf(m.from)}: ${String(m.text || '').replace(/\s+\n/g, '\n').trim()}`);
  }
  let out = '';
  for (let i = lines.length - 1; i >= 0; i--) {
    const next = lines[i] + (out ? '\n\n' + out : '');
    if (next.length > max) { out = '(earlier messages left out)\n\n' + out; break; }
    out = next;
  }
  return out;
}

/** The goal a bot gets from a message in a thread (the words without the @mentions). */
export function goalFrom(msg, bots, fromName) {
  const g = stripMentions(msg.text, bots) || 'Pick up from the conversation.';
  return msg.from === USER ? g : `${fromName || 'Another bot'} handed this to you: ${g}`;
}
