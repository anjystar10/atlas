// The PC's HUD mode, the parts with no screen: the results-wall panel format (what the model sends with show_panels and
// how it's cleaned), how a running job becomes a HUD card, the terminal's commands, and small number formats.
// No DOM and no imports, so the tests run it in plain Node (test/hud.test.mjs).

export const PANEL_TYPES = ['text', 'image', 'video', 'chart', 'stat', 'link', 'table'];
export const MAX_PANELS = 12, MAX_WALLS = 20;
const clip = (s, n) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, n);
const clipKeep = (s, n) => String(s ?? '').replace(/\r/g, '').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim().slice(0, n);

/** Only plain web addresses: never javascript:, data:, file: or a local address. */
export function safeUrl(u) {
  const s = String(u || '').trim();
  if (!/^https?:\/\/[^\s/?#]+\.[^\s/?#]+/i.test(s) || s.length > 2000) return null;
  try {
    const url = new URL(s);
    if (!/^https?:$/.test(url.protocol)) return null;
    if (/^(localhost|127\.|10\.|192\.168\.|169\.254\.|0\.|\[)/i.test(url.hostname) || /^172\.(1[6-9]|2\d|3[01])\./.test(url.hostname)) return null;
    if (url.username || url.password) return null;
    return url.toString();
  } catch { return null; }
}
/** The video id of a YouTube address (watch, youtu.be, shorts, embed), else null. */
export function youtubeId(u) {
  const s = safeUrl(u); if (!s) return null;
  const m = s.match(/(?:youtube\.com\/(?:watch\?(?:[^#]*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([\w-]{11})/i);
  return m ? m[1] : null;
}
export const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };

/**
 * The show_panels arguments, cleaned: unknown types and fields dropped, text cut to size, addresses checked, numbers
 * made numbers. Returns { title, panels, dropped: [reasons] }; a panel that can't be shown is dropped with its reason.
 */
export function cleanPanels(args) {
  const a = args && typeof args === 'object' ? args : {};
  const title = clip(a.title, 80) || 'Results';
  const raw = Array.isArray(a.panels) ? a.panels : [];
  const panels = [], dropped = [];
  for (const p of raw.slice(0, MAX_PANELS * 2)) {
    if (panels.length >= MAX_PANELS) { dropped.push('too many panels (12 at most)'); break; }
    const r = cleanPanel(p);
    if (r.error) dropped.push(r.error); else panels.push(r.panel);
  }
  return { title, panels, dropped };
}
function cleanPanel(p) {
  if (!p || typeof p !== 'object') return { error: 'a panel was not an object' };
  const type = String(p.type || '').toLowerCase();
  if (!PANEL_TYPES.includes(type)) return { error: `unknown panel type "${clip(p.type, 20)}"` };
  const base = { type, title: clip(p.title, 90), kicker: clip(p.kicker, 40), source: safeUrl(p.source) };
  if (type === 'text') {
    const body = clipKeep(p.body, 900);
    if (!body) return { error: 'a text panel had no body' };
    return { panel: { ...base, body } };
  }
  if (type === 'image') {
    const url = safeUrl(p.url);
    if (!url) return { error: 'an image panel had no usable web address' };
    return { panel: { ...base, url, caption: clip(p.caption, 160) } };
  }
  if (type === 'video') {
    const url = safeUrl(p.url);
    if (!url) return { error: 'a video panel had no usable web address' };
    return { panel: { ...base, url, youtube: youtubeId(url), caption: clip(p.caption, 160) } };
  }
  if (type === 'link') {
    const url = safeUrl(p.url);
    if (!url) return { error: 'a link panel had no usable web address' };
    return { panel: { ...base, url, body: clip(p.body, 240), host: hostOf(url) } };
  }
  if (type === 'stat') {
    const value = clip(p.value, 16);
    if (!value) return { error: 'a stat panel had no value' };
    return { panel: { ...base, value, label: clip(p.label, 120), body: clip(p.body, 220) } };
  }
  if (type === 'chart') {
    const series = (Array.isArray(p.series) ? p.series : []).slice(0, 12)
      .map((x) => ({ label: clip(x && x.label, 24), value: Number(x && x.value) }))
      .filter((x) => x.label && Number.isFinite(x.value));
    if (series.length < 2) return { error: 'a chart panel needs at least two numbers' };
    return { panel: { ...base, series, unit: clip(p.unit, 12), body: clip(p.body, 220) } };
  }
  // table
  const columns = (Array.isArray(p.columns) ? p.columns : []).slice(0, 6).map((c) => clip(c, 28));
  const rows = (Array.isArray(p.rows) ? p.rows : []).slice(0, 10).map((r) => (Array.isArray(r) ? r : []).slice(0, columns.length || 6).map((c) => clip(c, 60)));
  if (!columns.length || !rows.length) return { error: 'a table panel needs columns and rows' };
  return { panel: { ...base, columns, rows } };
}

/**
 * Where each panel goes on the wall: rows of 12 columns that are always filled edge to edge. Panels keep their order;
 * two small ones in a row (stat, link) share one slot, one above the other; a row that would be left short is
 * widened to fill. Returns [{ col, cols, row, rows }] (1-based, CSS grid lines) in panel order.
 */
export function layout(panels, width = 12) {
  const small = (p) => p.type === 'stat' || p.type === 'link';
  // slots: one panel, or two small ones stacked
  const slots = [];
  for (let i = 0; i < panels.length; i++) {
    if (small(panels[i]) && i + 1 < panels.length && small(panels[i + 1])) { slots.push({ items: [i, i + 1], w: 3 }); i++; }
    else slots.push({ items: [i], w: Math.min(width, spanOf(panels[i]).c) });
  }
  const out = new Array(panels.length);
  let row = 1, k = 0;
  while (k < slots.length) {
    const line = []; let used = 0;
    while (k < slots.length && used + slots[k].w <= width) { line.push(slots[k]); used += slots[k].w; k++; }
    if (!line.length) { line.push(slots[k]); used = slots[k].w; k++; }
    // widen to fill: the widest kinds (video, table, text) take the spare columns first, one at a time
    let spare = width - used;
    const grow = [...line].sort((a, b) => b.w - a.w);
    for (let n = 0; spare > 0 && n < 100; n++) { grow[n % grow.length].w++; spare--; }
    // a line of single small panels only is one row high; anything else is two (a pair stacks in them)
    const rows = line.every((s) => s.items.length === 1 && small(panels[s.items[0]])) ? 1 : 2;
    let col = 1;
    for (const s of line) {
      if (s.items.length === 2) { out[s.items[0]] = { col, cols: s.w, row, rows: 1 }; out[s.items[1]] = { col, cols: s.w, row: row + 1, rows: 1 }; }
      else out[s.items[0]] = { col, cols: s.w, row, rows };
      col += s.w;
    }
    row += rows;
  }
  return out;
}

/** How many of the wall's 12 columns and how many rows each panel takes. */
export function spanOf(p) {
  if (p.type === 'video') return { c: 6, r: 2 };
  if (p.type === 'image') return { c: 3, r: 2 };
  if (p.type === 'table') return { c: 6, r: 2 };
  if (p.type === 'chart') return { c: 4, r: 2 };
  if (p.type === 'stat') return { c: 3, r: 1 };
  if (p.type === 'link') return { c: 3, r: 1 };
  return { c: (p.body || '').length > 420 ? 4 : 3, r: 2 };
}

/** The show_panels tool, offered to the main Atlas while the HUD is on. */
export const SHOW_PANELS = {
  name: 'show_panels',
  description: 'Lay your findings out on the PC screen as a wall of panels (the HUD is on). Use it whenever the answer has rich content: research, comparisons, numbers, pictures, videos or links. ' +
    'Panels: text (a heading and a short paragraph), stat (one big number with what it means), chart (a few labelled numbers, drawn as bars), table (columns and rows), link (a page worth opening), ' +
    'image and video (ONLY web addresses that came from your tools in this conversation: search results or pages you read; never make up or guess an address, never use an image you did not find). ' +
    'Put the source page address in "source" wherever you have one. Keep each panel short; up to 12. Still answer in words as usual: the wall is shown next to your reply.',
  parameters: {
    type: 'object',
    properties: {
      title: { type: 'string', description: 'what the wall is about, a few words' },
      panels: {
        type: 'array', description: 'the panels, most important first',
        items: {
          type: 'object',
          properties: {
            type: { type: 'string', enum: PANEL_TYPES },
            title: { type: 'string' }, kicker: { type: 'string', description: 'a tiny label above the title, e.g. FACT or MARKET' },
            body: { type: 'string', description: 'text, link or stat explanation' },
            url: { type: 'string', description: 'image, video or link address (found by your tools)' },
            caption: { type: 'string' }, source: { type: 'string', description: 'the page this came from' },
            value: { type: 'string', description: 'stat: the number, e.g. "≈20m" or "$1,099"' }, label: { type: 'string', description: 'stat: what the number is' },
            unit: { type: 'string', description: 'chart: the unit of the numbers' },
            series: { type: 'array', items: { type: 'object', properties: { label: { type: 'string' }, value: { type: 'number' } } } },
            columns: { type: 'array', items: { type: 'string' } },
            rows: { type: 'array', items: { type: 'array', items: { type: 'string' } } },
          },
          required: ['type'],
        },
      },
    },
    required: ['title', 'panels'],
  },
};

// ------------------------------------------------------------------ HUD cards for running work

export const CARD_KINDS = { research: 'RESEARCH', trade: 'TRADING', mail: 'INBOX', post: 'POSTING', code: 'BUILD', browse: 'BROWSING', task: 'TASK' };
/** What kind of work a goal is, for the card's label. */
export function kindOf(goal, specialist) {
  const t = `${goal || ''} ${specialist || ''}`.toLowerCase();
  if (/research|find out|compare|look into|analy[sz]e|report on|investigate|state of/.test(t)) return 'research';
  if (/trad|buy |sell |order|position|crypto|stock|forex/.test(t)) return 'trade';
  if (/inbox|email|e-mail|mail|reply to/.test(t)) return 'mail';
  if (/post|reel|instagram|tweet|tiktok|upload/.test(t)) return 'post';
  if (/code|build|app|repo|deploy|fix the bug|program/.test(t)) return 'code';
  if (/website|browse|sign in|log in|book|fill in/.test(t)) return 'browse';
  return 'task';
}
const STATUS = {
  queued: 'QUEUED', running: 'ACTIVE', waiting_retry: 'RETRYING', awaiting_approval: 'NEEDS YOU', awaiting_answer: 'QUESTION',
  paused: 'PAUSED', needs_review: 'CHECK', completed: 'DONE', failed: 'FAILED', cancelled: 'STOPPED', waiting_helpers: 'WAITING ON AGENTS',
};
export const SEGMENTS = 16;
export function elapsed(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(r)}` : `${p(m)}:${p(r)}`;
}
/**
 * One bot job (or mission) as a HUD card. [agents]: how many helper agents it has right now.
 * The progress is its plan's ticked steps; with no plan yet, a slow creep from its tool steps (never 100% until done).
 */
export function cardFor(j, now, agents = 0) {
  const top = (j.plan || []).filter((p) => !p.depth);
  const plan = top.length ? top : j.plan || [];
  const done = plan.filter((p) => p.done).length, total = plan.length;
  let frac;
  if (j.state === 'completed') frac = 1;
  else if (total) frac = done / total;
  else frac = Math.min(0.85, (j.toolCalls || 0) / 40);
  const kind = kindOf(j.goal, j.specialist);
  const lastStep = (j.steps || []).length ? j.steps[j.steps.length - 1] : '';
  const nextOpen = plan.find((p) => !p.done);
  const waiting = j.state === 'awaiting_approval' || j.state === 'awaiting_answer' || j.state === 'needs_review';
  const start = (j.history || []).find((h) => h.state === 'running')?.at || j.createdAt || now;
  const end = ['completed', 'failed', 'cancelled'].includes(j.state) ? j.updatedAt || now : now;
  return {
    id: j.id,
    label: `${CARD_KINDS[kind]} // ${STATUS[j.state] || String(j.state || '').toUpperCase()}`,
    kind, state: j.state, waiting,
    title: clip(j.goal, 70).toUpperCase(),
    step: clip(waiting ? (j.state === 'awaiting_approval' && j.approval ? 'Wants to: ' + j.approval.what : j.question || 'Needs a check') : lastStep || (nextOpen && nextOpen.text) || 'Starting', 110),
    lit: Math.round(frac * SEGMENTS), frac,
    counts: [total ? `${done}/${total} STEPS` : `${j.toolCalls || 0} STEPS`, `${(j.sources || []).length} SOURCES`, agents ? `${agents} AGENT${agents === 1 ? '' : 'S'}` : null].filter(Boolean),
    time: elapsed(end - start),
    approval: j.state === 'awaiting_approval' && j.approval ? { what: clip(j.approval.what, 160) } : null,
    question: j.state === 'awaiting_answer' ? clip(j.question, 200) : null,
  };
}
/** Which jobs get a card: running or waiting ones, plus ones that finished in the last two minutes. Waiting first. */
export function cardsFor(jobs, now) {
  const live = jobs.filter((j) => !j.parent && (!['completed', 'failed', 'cancelled'].includes(j.state) || now - (j.updatedAt || 0) < 120000));
  const rank = (j) => (j.state === 'awaiting_approval' || j.state === 'awaiting_answer' ? 0 : j.state === 'running' ? 1 : ['completed', 'failed', 'cancelled'].includes(j.state) ? 3 : 2);
  return live.sort((a, b) => rank(a) - rank(b) || (b.updatedAt || 0) - (a.updatedAt || 0)).slice(0, 6);
}

// ------------------------------------------------------------------ the terminal

export const COMMANDS = {
  help: 'list the commands',
  status: 'brain, voice, bots and sync at a glance',
  bots: 'running and recent bot jobs',
  approve: 'allow what is waiting (approve <id> when more than one)',
  deny: 'refuse what is waiting (deny <id>)',
  stop: 'stop Atlas answering',
  new: 'start a new chat (clears it here and on the phone)',
  clear: 'clear this screen (the chat is kept)',
  history: 'earlier result walls (history <n> to reopen one)',
  wall: 'reopen the last result wall',
  hud: 'close the terminal and go back to the HUD',
  classic: 'switch to the classic window',
  mute: 'silence Atlas (replies still appear); again to unmute',
};
/** "/approve 3f2a" → { cmd: 'approve', arg: '3f2a' }; an unknown command → { cmd: null, word }; plain text → null. */
export function parseCommand(text) {
  const t = String(text || '').trim();
  if (!t.startsWith('/')) return null;
  const [w, ...rest] = t.slice(1).split(/\s+/);
  const word = (w || '').toLowerCase();
  const alias = { '?': 'help', h: 'help', s: 'status', cls: 'clear', a: 'approve', yes: 'approve', y: 'approve', no: 'deny', n: 'deny' }[word] || word;
  return COMMANDS[alias] ? { cmd: alias, arg: rest.join(' ').trim() } : { cmd: null, word };
}
export function helpText() {
  const w = Math.max(...Object.keys(COMMANDS).map((k) => k.length)) + 2;
  return Object.entries(COMMANDS).map(([k, v]) => `/${k.padEnd(w)}${v}`).join('\n');
}

// ------------------------------------------------------------------ numbers

/** A microphone level (0..1, RMS) in decibels for the meter: "-42.1 dB", or "-∞ dB" for silence. */
export function dbOf(level) {
  const l = Number(level) || 0;
  if (l <= 0.00001) return '-∞ dB';
  return `${Math.max(-99.9, 20 * Math.log10(l)).toFixed(1)} dB`;
}
/** How many of the meter's [n] segments light for a level (log scale, -60 dB → 0, 0 dB → n). */
export function meterLit(level, n = 24) {
  const l = Number(level) || 0;
  if (l <= 0.001) return 0;
  return Math.max(0, Math.min(n, Math.round(((20 * Math.log10(l) + 60) / 60) * n)));
}
/** The latest Atlas line worth showing under the core: the first sentence or two of the reply, plain text. */
export function spokenLine(text, max = 160) {
  const plain = String(text || '').replace(/```[\s\S]*?```/g, ' ').replace(/!\[[^\]]*\]\([^)]*\)/g, ' ').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[*_`#>|]/g, '').replace(/\s+/g, ' ').trim();
  if (plain.length <= max) return plain;
  const cut = plain.slice(0, max); const end = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('? '), cut.lastIndexOf('! '));
  return end > 40 ? cut.slice(0, end + 1) : cut.replace(/\s+\S*$/, '') + '…';
}
/** Keep the newest walls, at most [max], dropping empty ones. */
export function pushWall(walls, wall, max = MAX_WALLS) {
  if (!wall || !wall.panels || !wall.panels.length) return walls.slice(0, max);
  return [wall, ...walls.filter((w) => w.id !== wall.id)].slice(0, max);
}
