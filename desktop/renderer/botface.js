// Who each bot is, and the Bots page's History. Pure (no DOM, no storage), the same rules as BotFace.kt on the phone,
// checked against the same fixtures (tools/botface-fixtures.json, made by test/make-botface-fixtures.mjs).
//
//  - Names: bots are teammates with human names and a short title ("Ted · Developer"), like Grok Bot's named Bots.
//    The name is the bot's own (the user can rename it); the title says its job. Bots made before this still carry the
//    old "Developer Bot"-style names: migrate() gives them their new name once, keeping the id and everything else.
//  - Faces: like Grok Bot's "pick a shape and a colour", each bot has a shape, a colour pair and a motif for its job,
//    drawn by the screens (an orb with a glyph; Atlas keeps its own liquid orb). Nobody is a letter in a circle.
//  - History: every job any bot ran, on this device or the other one, grouped by day and filterable by bot. The raw
//    job list lives there, not on the Bots page.

export const SHAPES = ['orb', 'hex', 'diamond', 'squircle', 'petal'];
/** Colour pairs [light, deep]. All saturated enough for a white glyph on top, in both the dark and the light look. */
export const COLORS = [
  ['#8FA2FF', '#4B4FD8'], // indigo
  ['#6FE3D2', '#138A8A'], // teal
  ['#FFD27A', '#C9771A'], // amber
  ['#FF9BB5', '#C2386A'], // rose
  ['#C9A2FF', '#7445D6'], // violet
  ['#8EE6A0', '#2E9A55'], // green
  ['#8FD3FF', '#2A7BC9'], // sky
  ['#FFAA8C', '#D2533A'], // coral
];
export const MOTIFS = ['build', 'code', 'chart', 'wave', 'mail', 'play', 'search', 'coins', 'calendar', 'spark'];

/** The old names → who they are now. Only an exact old name is renamed, so a name the user chose is never touched. */
export const RENAMES = {
  'Research Bot': { name: 'Nova', title: 'Researcher', face: { shape: 'orb', color: 6 }, persona: "Curious and exact. I follow the evidence, cross-check what matters and say plainly what I couldn't verify." },
  'Planner Bot': { name: 'Iris', title: 'Planner', face: { shape: 'petal', color: 4 }, persona: 'Calm and organised. I turn messy goals into clear steps with real times, and I protect what matters most to you.' },
  'Developer Bot': { name: 'Ted', title: 'Developer', face: { shape: 'hex', color: 0 }, persona: "Practical and steady. I read before I change anything, and it isn't done until it runs and its tests pass." },
  'Finance Bot': { name: 'Mara', title: 'Finance', face: { shape: 'squircle', color: 5 }, persona: 'Careful with numbers. I show every figure and where it came from, and I never move money.' },
  Trader: { name: 'Rex', title: 'Trader', face: { shape: 'diamond', color: 2 }, persona: 'Disciplined. I trade the plan inside your limits, report every move, and never promise profit.' },
  Inbox: { name: 'Ivy', title: 'Inbox', face: { shape: 'squircle', color: 1 }, persona: 'Brisk and discreet. I keep your email under control and draft replies in your voice for your OK.' },
  'Reel Poster': { name: 'Remi', title: 'Reels', face: { shape: 'petal', color: 3 }, persona: 'Creative but tidy. I write hooks that land, learn what performs, and never post without your OK.' },
  Researcher: { name: 'Sage', title: 'Deep Research', face: { shape: 'orb', color: 5 }, persona: 'Thorough and sceptical. I go deep, separate fact from opinion, and deliver a clean document with sources.' },
  Builder: { name: 'Bo', title: 'Builder', face: { shape: 'hex', color: 7 }, persona: 'Hands-on. I build in small steps until it runs and passes its checks, and I tell you what is not done.' },
  'Chief of Staff': { name: 'Quinn', title: 'Chief of Staff', face: { shape: 'hex', color: 2 }, persona: 'Warm and direct. I plan your day around what matters most and tell you honestly when it is overloaded.' },
  'Market Watcher': { name: 'Otto', title: 'Markets', face: { shape: 'diamond', color: 6 }, persona: "Alert and level-headed. I report what moved and why, and I never dress a guess up as a signal." },
};

/** 32-bit FNV-1a over UTF-16 code units: the same number in Kotlin and JS. */
export function hash(s) {
  let h = 0x811c9dc5 | 0;
  const t = String(s || '');
  for (let i = 0; i < t.length; i++) { h ^= t.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return h >>> 0;
}

const has = (re, s) => re.test(s);
/** The glyph for a bot's job, from its title, role and name (first match wins). */
export function motifOf(b) {
  const s = `${(b && b.title) || ''} ${(b && b.role) || ''} ${(b && b.name) || ''}`.toLowerCase();
  if (has(/\bbuild/, s)) return 'build';
  if (has(/develop|\bcode|coding|program|software|debug/, s)) return 'code';
  if (has(/\btrad(e|er|es|ing)\b/, s)) return 'chart';
  if (has(/market|news|watch/, s)) return 'wave';
  if (has(/inbox|e-?mail|\bmail/, s)) return 'mail';
  if (has(/reel|video|instagram|tiktok|youtube|\bpost/, s)) return 'play';
  if (has(/research|find|search|learn/, s)) return 'search';
  if (has(/financ|budget|money|price|cost/, s)) return 'coins';
  if (has(/plan|schedul|calendar|chief|\bday\b/, s)) return 'calendar';
  return 'spark';
}

/** A bot's face: {shape, color (index into COLORS), motif}. Its own choice if it made one, else steady from its id. */
export function faceOf(b) {
  const f = (b && b.face) || {};
  const h = hash((b && b.id) || (b && b.name) || '');
  const shape = SHAPES.includes(f.shape) ? f.shape : SHAPES[h % SHAPES.length];
  const color = Number.isInteger(f.color) && f.color >= 0 && f.color < COLORS.length ? f.color : (h >>> 3) % COLORS.length;
  return { shape, color, motif: motifOf(b) };
}

/** "Ted · Developer" (or just the name when it has no title). */
export function label(b) {
  const n = String((b && b.name) || '').trim() || 'A bot';
  const t = String((b && b.title) || '').trim();
  return t ? `${n} · ${t}` : n;
}
/** The short line under a bot's name: its title, else the start of its role. */
export function subtitle(b) {
  const t = String((b && b.title) || '').trim();
  if (t) return t;
  const r = String((b && b.role) || '').trim();
  return r || 'A bot of yours';
}

/**
 * Gives a bot from before names its new name, title, face and personality, once. Returns a NEW object when it changed
 * something, else the same one (so callers can tell). Never renames a name the user chose.
 */
export function migrate(b) {
  if (!b || typeof b !== 'object') return b;
  const r = RENAMES[String(b.name || '').trim()];
  const out = { ...b };
  let changed = false;
  if (r) {
    out.name = r.name; changed = true;
    if (!String(out.title || '').trim()) out.title = r.title;
    if (!out.face || !SHAPES.includes(out.face.shape)) out.face = { ...r.face };
    if (!String(out.persona || '').trim()) out.persona = r.persona;
  }
  return changed ? out : b;
}

/** One line for the bot's prompt: who it is and how it sounds. */
export function personaPrompt(b) {
  if (!b) return '';
  const n = String(b.name || '').trim();
  if (!n) return '';
  let s = `Your name is ${n}${b.title ? `, the user's ${String(b.title).toLowerCase()}` : ''}. Speak in the first person as ${n}, briefly and warmly.`;
  if (b.persona) s += ` Your manner: ${b.persona}`;
  return s;
}

// =================================================================== History

/** The day a time falls on, as YYYY-MM-DD, in a time zone [tzMin] minutes east of UTC. */
export function dayKey(ms, tzMin = 0) {
  const d = new Date(ms + tzMin * 60000);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

export const WAITING_STATES = ['awaiting_approval', 'awaiting_answer', 'needs_review'];
export const RUNNING_STATES = ['running', 'queued', 'waiting_retry', 'waiting_helpers'];

/**
 * Every job, grouped by day, newest first. [items]: {id, goal, state, bot, at, where ('here' | 'other'), …}. A job with
 * no bot belongs to Atlas. [opts]: {bot: only this bot's, query: words that must all appear in the goal, tzMin, now}.
 * Returns {groups: [{day, label: 'Today' | 'Yesterday' | YYYY-MM-DD, items}], waiting: how many need the user, total}.
 */
export function historyGroups(items, opts = {}) {
  const now = opts.now || Date.now();
  const tz = opts.tzMin || 0;
  const today = dayKey(now, tz), yesterday = dayKey(now - 86400000, tz);
  const words = String(opts.query || '').toLowerCase().split(/\s+/).filter(Boolean);
  const seen = new Set();
  const list = [];
  for (const raw of items || []) {
    if (!raw || !raw.id || seen.has(raw.id)) continue;
    seen.add(raw.id);
    const it = { ...raw, bot: raw.bot || 'atlas', at: raw.at || 0 };
    if (opts.bot && it.bot !== opts.bot) continue;
    const hay = `${it.goal || ''}`.toLowerCase();
    if (words.some((w) => !hay.includes(w))) continue;
    list.push(it);
  }
  list.sort((a, b) => b.at - a.at || (String(a.id) < String(b.id) ? -1 : String(a.id) > String(b.id) ? 1 : 0));
  const groups = [];
  for (const it of list) {
    const day = dayKey(it.at, tz);
    let g = groups[groups.length - 1];
    if (!g || g.day !== day) { g = { day, label: day === today ? 'Today' : day === yesterday ? 'Yesterday' : day, items: [] }; groups.push(g); }
    g.items.push(it);
  }
  return { groups, waiting: list.filter((i) => WAITING_STATES.includes(i.state)).length, total: list.length };
}
