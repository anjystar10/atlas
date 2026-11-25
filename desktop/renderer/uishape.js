// How a reply is laid out (the same rules as the phone's UiShape.kt): prose stays prose, and a run of 3+ lines that
// are times ("23:30 Stretch") or short labels ("Now: Code Atlas") becomes a tidy card of rows. Code is never touched.

const BULLET = /^\s*(?:[-•*]|\d{1,2}[.)])\s+/;
// a clock time (0–23 h, 00–59 min) at the start of the line; "8.05" counts only with am/pm or a dash after it, so a
// price ("12.50 for a coffee"), a rate ("1.0845 entry"), a version ("0.11.4: …") or "12:30:45" isn't taken for one
const TIME = /^((?:[01]?\d|2[0-3])(?::[0-5]\d|\.[0-5]\d(?=\s?[aApP][mM]|\s*[-–—·:]))(?:\s?[aApP][mM])?)(?!\d|[.,:]\d)\s*(?:[-–—·:]\s*)?(.+)$/;
const LABEL = /^\**([A-Za-z][A-Za-z0-9 '’/&()-]{0,26}?)\**\s*:\**\s+(.+)$/;

export function toneOf(key) {
  const k = key.toLowerCase();
  if (/\b(needs?|waiting|blocked|overdue|missed|late|urgent|action|warning|failed)\b/.test(k)) return 'alert';
  if (/\b(done|finished|completed|sent|saved|booked)\b/.test(k)) return 'done';
  if (/\b(now|current|currently|doing|working|in progress|today)\b/.test(k)) return 'now';
  return 'plain';
}

const stars = (s) => s.trim().replace(/^\*+|\*+$/g, '').trim();

/** A line as a row ({ key, value, tone }), or null. */
export function row(line) {
  const l = line.replace(BULLET, '').trim();
  if (!l || l.length > 140) return null;
  const t = l.match(TIME);
  if (t) { const v = stars(t[2]); if (v) return { key: t[1].replace('.', ':').trim(), value: v, tone: 'time' }; }
  const m = l.match(LABEL); if (!m) return null;
  const key = m[1].trim(); const value = stars(m[2]);
  if (!value || key.split(' ').length > 4 || /http/i.test(key) || value.startsWith('//')) return null;
  return { key, value, tone: toneOf(key) };
}

/** [{ text }] and [{ rows }] segments, in order. */
export function split(text, minRun = 3) {
  const out = []; let prose = []; let run = []; let fence = false;
  const flushProse = () => { const s = prose.join('\n').replace(/^\n+|\n+$/g, ''); if (s.trim()) out.push({ text: s }); prose = []; };
  const endRun = () => {
    if (run.length >= minRun) { flushProse(); out.push({ rows: run.map((r) => r.row) }); }
    else run.forEach((r) => prose.push(r.line));
    run = [];
  };
  for (const line of String(text).split('\n')) {
    if (line.trimStart().startsWith('```')) { endRun(); fence = !fence; prose.push(line); continue; }
    const r = fence ? null : row(line);
    if (r) { run.push({ line, row: r }); continue; }
    endRun(); prose.push(line);
  }
  endRun(); flushProse();
  return out;
}
