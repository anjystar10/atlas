// Atlas: turning "when" into an exact time, the same way on the PC and the phone (When.kt).
// The model is good at language and bad at calendar arithmetic, so the tools take what the user said
// ("tomorrow at 6pm", "in 90 minutes", "monday 9am", "2026-11-14 18:00") and this file works out the
// exact moment from the device clock and its time zone. Anything unreadable, impossible (31 April, 25:00)
// or already past is refused with a precise message instead of being guessed.
// Shared test cases: tools/accuracy-fixtures.json (the phone runs the same file).
'use strict';

const DAY = 86400000, MIN = 60000;
const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
const SHORT_DAYS = { sun: 0, mon: 1, tue: 2, tues: 2, wed: 3, thu: 4, thur: 4, thurs: 4, fri: 5, sat: 6 };
const pad = (n) => String(n).padStart(2, '0');

/** The wall-clock parts of [ms] in time zone [tz]. */
export function partsIn(ms, tz) {
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'long' });
  const p = {}; for (const x of f.formatToParts(new Date(ms))) p[x.type] = x.value;
  return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour % 24, mi: +p.minute, s: +p.second, wd: WEEKDAYS.indexOf(p.weekday.toLowerCase()) };
}

const offsetAt = (ms, tz) => { const p = partsIn(ms, tz); return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(ms / 1000) * 1000; };

/**
 * The moment a wall-clock time happens in [tz], with java.time's rules so both devices agree:
 * a time skipped by a clock change moves forward by the gap; a time that happens twice takes the earlier one.
 */
export function zonedToMs(y, mo, d, h, mi, tz) {
  const wall = Date.UTC(y, mo - 1, d, h, mi);
  const offs = [...new Set([offsetAt(wall - DAY, tz), offsetAt(wall, tz), offsetAt(wall + DAY, tz)])];
  const hits = offs.map((o) => wall - o).filter((ms) => { const p = partsIn(ms, tz); return p.y === y && p.mo === mo && p.d === d && p.h === h && p.mi === mi; });
  if (hits.length) return Math.min(...hits);   // overlap: the earlier moment
  // gap: shift forward by the gap's length, using the offset from before the change
  const before = offsetAt(wall - DAY, tz);
  return wall - before;
}

export const fmtLocal = (ms, tz) => { const p = partsIn(ms, tz); return `${p.y}-${pad(p.mo)}-${pad(p.d)} ${pad(p.h)}:${pad(p.mi)}`; };
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** "Fri 13 Nov 2026, 18:00" — the form tools report back, so the model quotes the stored time, not its own guess. */
export const fmtLong = (ms, tz) => { const p = partsIn(ms, tz); return `${WEEKDAYS[p.wd].slice(0, 1).toUpperCase()}${WEEKDAYS[p.wd].slice(1, 3)} ${p.d} ${MONTHS[p.mo - 1]} ${p.y}, ${pad(p.h)}:${pad(p.mi)}`; };

const daysInMonth = (y, mo) => new Date(Date.UTC(y, mo, 0)).getUTCDate();
function addDays(y, mo, d, n) { const t = new Date(Date.UTC(y, mo - 1, d + n)); return [t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()]; }

/** "6pm", "6:30 pm", "18:00", "18.30", "noon", "midnight" → [h, m]; null when it isn't a time of day. */
export function parseClock(s) {
  const t = String(s || '').trim().toLowerCase().replace(/\s+/g, ' ');
  if (t === 'noon' || t === 'midday') return [12, 0];
  if (t === 'midnight') return [0, 0];
  let m = t.match(/^(\d{1,2})(?:[:.](\d{2}))? ?(am|pm|a\.m\.|p\.m\.)$/);
  if (m) {
    let h = +m[1]; const mi = m[2] ? +m[2] : 0;
    if (h < 1 || h > 12 || mi > 59) return { bad: true };
    const pm = m[3].startsWith('p');
    if (h === 12) h = pm ? 12 : 0; else if (pm) h += 12;
    return [h, mi];
  }
  m = t.match(/^(\d{1,2})[:.](\d{2})$/) || t.match(/^(\d{2})(\d{2})$/);
  if (m) { const h = +m[1], mi = +m[2]; if (h > 23 || mi > 59) return { bad: true }; return [h, mi]; }
  return null;
}

/**
 * [text] → { ok: true, ms, local, long, relative } or { ok: false, error }.
 * Understands: "YYYY-MM-DD HH:MM" (also T and seconds), "in 90 minutes" / "in 2 hours" / "in 1h30m",
 * "today 6pm", "tonight at 9", "tomorrow at 6:30pm", "monday 9am", "next friday at noon", "6pm" (the next 6pm).
 * [allowPast]: for reading or moving into the past on purpose (not used by reminders).
 */
export function resolveWhen(text, now, tz, { allowPast = false } = {}) {
  const raw = String(text ?? '').trim();
  if (!raw) return { ok: false, error: 'No time was given.' };
  const s = raw.toLowerCase().replace(/\s+/g, ' ').replace(/,/g, '').replace(/\bat\b /g, '').replace(/^on /, '').trim();
  const n = partsIn(now, tz);
  let ms = null;

  // 1. exact local date and time
  let m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ t](\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
  if (m) {
    const y = +m[1], mo = +m[2], d = +m[3];
    if (m[4] == null) return { ok: false, error: `“${raw}” has a date but no time. Ask the user what time.` };
    const h = +m[4], mi = +m[5];
    if (mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return { ok: false, error: `“${raw}” isn't a real date.` };
    if (h > 23 || mi > 59) return { ok: false, error: `“${raw}” isn't a real time.` };
    ms = zonedToMs(y, mo, d, h, mi, tz);
  }

  // 2. "in 90 minutes", "in 2 hours", "in 1 hour 30 minutes", "in 1h30m", "in half an hour", "in an hour"
  if (ms == null && /^in /.test(s)) {
    let rest = s.slice(3).replace(/\bhalf an hour\b/, '30 minutes').replace(/\ban hour\b/, '1 hour').replace(/\ba minute\b/, '1 minute').replace(/\band\b/g, ' ').replace(/\s+/g, ' ').trim();
    const re = /(\d+(?:\.\d+)?) ?(hours?|hrs?|h|minutes?|mins?|m|days?|d)(?=\d|\s|$)/g; let total = 0, used = '', x;
    while ((x = re.exec(rest))) { const v = +x[1], u = x[2][0]; total += u === 'h' ? v * 60 : u === 'd' ? v * 1440 : v; used += x[0]; }
    if (!total || rest.replace(re, '').replace(/\s/g, '')) return { ok: false, error: `Couldn't read “${raw}”. Say it like “in 90 minutes” or “in 2 hours”.` };
    if (total > 60 * 24 * 366) return { ok: false, error: `“${raw}” is more than a year away. Check it.` };
    ms = now + Math.round(total * MIN);
    ms -= ms % MIN; // whole minutes, like every other reminder
    if (ms <= now) ms += MIN;
  }

  // 3. day word + clock: "today 6pm", "tonight 9", "tomorrow 6:30pm", "monday 9am", "next friday noon"
  if (ms == null) {
    m = s.match(/^(today|tonight|this evening|this afternoon|this morning|tomorrow(?: morning| afternoon| evening| night)?|(?:next |this )?(sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat)[a-z]*)?(?: )?(.*)$/);
    const dayWord = m && m[1] ? m[1] : null; const clockText = m ? m[3] : s;
    let clock = clockText ? parseClock(clockText) : null;
    if (clock && clock.bad) return { ok: false, error: `“${raw}” isn't a real time.` };
    // "tonight 9" / "this evening 7": a bare small hour means pm
    if (!clock && dayWord && /^(tonight|this evening|this afternoon|tomorrow (evening|night|afternoon))$/.test(dayWord) && /^\d{1,2}$/.test(clockText) && +clockText >= 1 && +clockText <= 11) clock = [+clockText + 12, 0];
    // "this morning 7" / "tomorrow morning 9": a bare hour means am
    if (!clock && dayWord && /morning$/.test(dayWord) && /^\d{1,2}$/.test(clockText) && +clockText >= 1 && +clockText <= 11) clock = [+clockText, 0];
    if (!clock && dayWord && /^\d{1,2}$/.test(clockText)) return { ok: false, error: `“${raw}”: is that ${clockText} in the morning or the evening? Ask the user.` };
    if (Array.isArray(clock)) {
      let [y, mo, d] = [n.y, n.mo, n.d];
      if (!dayWord) {
        // a bare time means the next time the clock shows it
        let cand = zonedToMs(y, mo, d, clock[0], clock[1], tz);
        if (cand <= now) { [y, mo, d] = addDays(y, mo, d, 1); cand = zonedToMs(y, mo, d, clock[0], clock[1], tz); }
        ms = cand;
      } else if (/^tomorrow/.test(dayWord)) { [y, mo, d] = addDays(y, mo, d, 1); ms = zonedToMs(y, mo, d, clock[0], clock[1], tz); }
      else if (/^(today|tonight|this )/.test(dayWord)) ms = zonedToMs(y, mo, d, clock[0], clock[1], tz);
      else {
        const key = m[2]; const target = SHORT_DAYS[key] ?? SHORT_DAYS[key.slice(0, 3)];
        if (target == null) return { ok: false, error: `Couldn't read the day in “${raw}”.` };
        let add = (target - n.wd + 7) % 7;
        const cand = zonedToMs(...addDays(y, mo, d, add), clock[0], clock[1], tz);
        if (add === 0 && cand <= now) add = 7;
        if (/^next /.test(dayWord) && add === 0) add = 7;
        ms = zonedToMs(...addDays(y, mo, d, add), clock[0], clock[1], tz);
      }
    } else if (dayWord && !clockText) {
      return { ok: false, error: `“${raw}” has a day but no time. Ask the user what time.` };
    }
  }

  if (ms == null) return { ok: false, error: `Couldn't read the time “${raw}”. Use 'YYYY-MM-DD HH:MM', or words like “tomorrow at 6pm” or “in 90 minutes”.` };
  if (!allowPast && ms < now - MIN) return { ok: false, error: `${fmtLong(ms, tz)} has already passed (it's ${fmtLong(now, tz)} now). Use the next time it comes round, or ask the user.` };
  if (ms - now > 5 * 366 * DAY) return { ok: false, error: `${fmtLong(ms, tz)} is more than five years away. Check the year.` };
  return { ok: true, ms, local: fmtLocal(ms, tz), long: fmtLong(ms, tz), relative: relativeDay(ms, now, tz) };
}

/** "today", "tomorrow", "in 3 days" — how far off the day is, for read-backs. */
export function relativeDay(ms, now, tz) {
  const a = partsIn(ms, tz), b = partsIn(now, tz);
  const diff = Math.round((Date.UTC(a.y, a.mo - 1, a.d) - Date.UTC(b.y, b.mo - 1, b.d)) / DAY);
  return diff === 0 ? 'today' : diff === 1 ? 'tomorrow' : diff === -1 ? 'yesterday' : diff > 1 ? `in ${diff} days` : `${-diff} days ago`;
}

export const REPEATS = ['none', 'daily', 'weekdays', 'weekly'];
/** "every day" / "Daily" / "" → "daily"; null when it isn't one Atlas can do. */
export function normRepeat(v) {
  const t = String(v ?? '').trim().toLowerCase().replace(/[^a-z ]/g, '');
  if (!t || t === 'none' || t === 'no' || t === 'once' || t === 'never') return 'none';
  if (['daily', 'every day', 'everyday', 'each day'].includes(t)) return 'daily';
  if (['weekdays', 'every weekday', 'weekday', 'mondays to fridays', 'monday to friday'].includes(t)) return 'weekdays';
  if (['weekly', 'every week', 'each week'].includes(t)) return 'weekly';
  return null;
}

/** A whole number in [min, max], or null. Accepts "30", 30, "30 minutes", "1.5 hours" (for duration fields). */
export function normMinutes(v, min, max) {
  if (v == null || v === '') return null;
  const t = String(v).trim().toLowerCase(); const m = t.match(/^(\d+(?:\.\d+)?) ?(h|hours?|hrs?|m|mins?|minutes?)?$/);
  if (!m) return NaN;
  const n = Math.round(+m[1] * (m[2] && m[2][0] === 'h' ? 60 : 1));
  return n >= min && n <= max ? n : NaN;
}

/** This device's time zone (the user's), e.g. "Africa/Lagos". */
export const localZone = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch { return 'UTC'; } };
