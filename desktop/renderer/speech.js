// The parts of hearing and speaking that are plain logic (no microphone, no network), so they can be tested.
// The phone has the same rules in Speech.kt; keep the two in step.

export const FRAME_MS = 20;

/**
 * Follows the loudness of the microphone, frame by frame, and says when the speaker has finished: 'going', 'done'
 * or 'nothing'. It learns how loud the room is, needs a moment of steady sound before it counts as speech, and
 * waits for a real pause before finishing.
 */
export class Endpointer {
  constructor({ endSilenceMs = 1300, noSpeechMs = 7000, maxMs = 60000, minVoice = 0.012 } = {}) {
    Object.assign(this, { endSilenceMs, noSpeechMs, maxMs, minVoice, frames: 0, floor: -1, recent: 0, speaking: false, speechMs: 0, startFrame: -1, quietMs: 0,
      runMs: 0, lastRunMs: 0, pauses: [], voiceLevel: 0, voicedFrames: 0, pausing: false });
  }
  get threshold() { return Math.max((this.floor < 0 ? 0.004 : this.floor) * 3, this.minVoice); }
  /** Longer after a short false start or a stammered fragment, and after very little speech ("um"). */
  get needMs() { return this.speechMs < 700 || (this.lastRunMs > 0 && this.lastRunMs < 350 && this.speechMs < 2500) ? Math.floor(this.endSilenceMs * 3 / 2) : this.endSilenceMs; }
  feed(rms) {
    this.frames++;
    if (this.floor < 0) this.floor = Math.min(rms, 0.02);
    const loud = rms > this.threshold;
    if (!loud) this.floor += (rms - this.floor) * (rms < this.floor ? 0.2 : 0.02);
    else if (!this.speaking && this.frames > 150 && this.speechMs === 0) this.floor += (Math.min(rms, 0.05) - this.floor) * 0.01;
    this.recent = ((this.recent << 1) | (loud ? 1 : 0)) & 0xff;
    let bits = 0; for (let b = this.recent; b; b >>= 1) bits += b & 1;
    if (loud) {
      if (this.speaking && this.quietMs >= 200) this.pauses.push(this.quietMs);
      if (this.quietMs >= 200) this.runMs = 0;
      this.quietMs = 0; this.runMs += FRAME_MS;
    } else { if (this.quietMs === 0 && this.runMs > 0) this.lastRunMs = this.runMs; this.quietMs += FRAME_MS; }
    if (!this.speaking && bits >= 5) { this.speaking = true; if (this.startFrame < 0) this.startFrame = Math.max(0, this.frames - 8); }
    if (this.speaking && loud) { this.speechMs += FRAME_MS; this.voicedFrames++; this.voiceLevel += (rms - this.voiceLevel) / this.voicedFrames; }
    this.pausing = this.speaking && this.speechMs >= 200 && this.quietMs >= 700 && this.quietMs < this.needMs;
    const ms = this.frames * FRAME_MS;
    if (ms >= this.maxMs) return this.speechMs > 0 ? 'done' : 'nothing';
    if (this.speaking && this.speechMs >= 200 && this.quietMs >= this.needMs) return 'done';
    if (this.speaking && this.speechMs < 200 && this.quietMs >= this.endSilenceMs) { this.speaking = false; this.speechMs = 0; this.startFrame = -1; this.pauses = []; return 'going'; }
    if (!this.speaking && this.speechMs === 0 && ms >= this.noSpeechMs) return 'nothing';
    return 'going';
  }
}

/** How this person pauses mid-sentence (the same rules as the phone's Speech.Pace): [p90] ms over [n] pauses. */
export class Pace {
  constructor(p90 = 0, n = 0) { this.p90 = p90; this.n = n; }
  endSilenceMs(patient = false) {
    const base = this.n < 8 ? 1300 : Math.min(2800, Math.max(1100, Math.trunc(this.p90 * 3 / 2) + 300));
    return patient ? Math.min(4500, Math.max(2200, Math.trunc(base * 8 / 5))) : base;
  }
  learn(turn) {
    const ps = turn.filter((p) => p >= 200 && p <= 4000).sort((a, b) => a - b);
    if (!ps.length) return this;
    const q = ps[Math.trunc((ps.length * 9 + 9) / 10) - 1];
    const w = Math.min(ps.length, 6) / (Math.min(this.n, 60) + Math.min(ps.length, 6));
    return new Pace(this.n === 0 ? q : Math.trunc(this.p90 + (q - this.p90) * w), Math.min(this.n + ps.length, 1000));
  }
  save() { return `${this.p90},${this.n}`; }
  static parse(s) { const v = String(s || '').split(',').map((x) => parseInt(x.trim(), 10)); return v.length === 2 && v.every((x) => Number.isFinite(x)) ? new Pace(v[0], v[1]) : new Pace(); }
}
export const minVoiceFor = (level) => (!level || level <= 0 ? 0.012 : Math.min(0.012, Math.max(0.006, level / 4)));

const LANG_NAMES = { english: 'en', french: 'fr', spanish: 'es', portuguese: 'pt', arabic: 'ar', hausa: 'ha', yoruba: 'yo', igbo: 'ig', german: 'de', italian: 'it', dutch: 'nl', chinese: 'zh', japanese: 'ja', korean: 'ko',
  hindi: 'hi', russian: 'ru', turkish: 'tr', swahili: 'sw', polish: 'pl', ukrainian: 'uk', urdu: 'ur', bengali: 'bn', indonesian: 'id', vietnamese: 'vi', thai: 'th', persian: 'fa', hebrew: 'he', greek: 'el', swedish: 'sv', tagalog: 'tl' };
export function isoName(lang) {
  const l = String(lang || '').trim().toLowerCase(); if (!l) return null;
  if (/^[a-z]{2}([-_][a-z]{2,4})?$/.test(l)) return l.slice(0, 2);
  return LANG_NAMES[l] || null;
}
/** What a speech model wrote down, its language and whether it was unsure (the same rules as the phone's Speech.heardFrom). */
export function heardFrom(json) {
  const o = typeof json === 'string' ? JSON.parse(json) : json;
  const text = String(o.text || '').trim();
  let lang = String(o.language || '').trim() || ((o.languages || [])[0] || {}).code || null;
  let unsure = false;
  const segs = Array.isArray(o.segments) ? o.segments : [];
  if (segs.length) {
    let weight = 0, lp = 0, rep = false, silent = true;
    for (const g of segs) {
      const d = Math.max(0.1, (g.end || 0) - (g.start || 0)); weight += d; lp += d * (g.avg_logprob || 0);
      if ((g.compression_ratio ?? 1) > 2.4) rep = true;
      if ((g.no_speech_prob ?? 0) < 0.6) silent = false;
    }
    unsure = (weight > 0 && lp / weight < -0.7) || rep || silent;
  }
  return { text, language: isoName(lang), unsure };
}
/** What the speech model noticed, for Atlas's instructions (the same words as the phone's Speech.heardNote). */
export const heardNote = (language, unsure) =>
  (language && language !== 'en' ? ` The speech model heard this in the language with code "${language}"; reply in it.` : '') +
  (unsure ? ' The speech model was unsure of these words: if the request doesn\'t make clear sense, or a name or number in it could be misheard and matters, ask in one short question before acting.' : '');

const ABBREV = new Set(['mr', 'mrs', 'ms', 'dr', 'prof', 'sr', 'jr', 'st', 'vs', 'etc', 'e.g', 'i.e', 'no', 'approx', 'a.m', 'p.m']);
const CLOSERS = '.!?…"\')”’';

/** Sentences of [text], without splitting after "Dr." or inside "3.5". */
export function sentences(text) {
  const out = []; let sb = ''; const t = String(text || '').trim();
  for (let i = 0; i < t.length; i++) {
    const c = t[i]; sb += c;
    if ('.!?…'.includes(c)) {
      while (i + 1 < t.length && CLOSERS.includes(t[i + 1])) { i++; sb += t[i]; }
      const atEnd = i + 1 >= t.length, spaceNext = !atEnd && /\s/.test(t[i + 1]);
      const lastWord = sb.replace(/[.!?…"')”’]+$/, '').split(' ').pop().toLowerCase();
      const abbrev = c === '.' && (ABBREV.has(lastWord) || (lastWord.length === 1 && /\p{L}/u.test(lastWord) && lastWord !== 'i' && lastWord !== 'a'));
      if (atEnd || (spaceNext && !abbrev)) { if (sb.trim()) out.push(sb.trim()); sb = ''; }
    }
  }
  if (sb.trim()) out.push(sb.trim());
  return out;
}

function splitLong(s, limit) {
  const out = []; let left = s;
  while (left.length > limit) {
    const w = left.slice(0, limit);
    let cut = Math.max(w.lastIndexOf(', '), w.lastIndexOf('; '), w.lastIndexOf(': '));
    if (cut < limit / 3) cut = w.lastIndexOf(' ');
    if (cut < limit / 3) cut = limit - 1;
    out.push(left.slice(0, cut + 1).trim()); left = left.slice(cut + 1).trim();
  }
  if (left) out.push(left);
  return out;
}

/** [text] cut into pieces to speak one after another: a short first piece so the voice starts quickly. */
export function chunks(text, first = 140, rest = 320) {
  const parts = sentences(text).flatMap((s) => (s.length <= rest ? [s] : splitLong(s, rest)));
  const out = []; let sb = '';
  for (const p of parts) {
    const limit = out.length ? rest : first;
    if (sb && sb.length + 1 + p.length > limit) { out.push(sb); sb = ''; }
    sb = sb ? sb + ' ' + p : p;
  }
  if (sb) out.push(sb);
  return out;
}

const GHOSTS = new Set(['thank you', 'thanks for watching', 'thank you for watching', 'you', 'bye', 'okay', 'thanks', 'so', 'the', 'uh', 'um', 'hmm', 'subtitles by the amara.org community', 'please subscribe']);
const HEY = /^(hey|hi|ok|okay|yo)[ ,]+(at last|atlus|atlass|alas|atlis|atless|at las|outlast|atlas's)\b[,.!]?/i;
const NAME = /\b(atlus|atlass|atlis)\b/gi;
const bare = (s) => s.toLowerCase().replace(/^[.!?, …]+|[.!?, …]+$/g, '');

/** What was heard, tidied: nothing when it's only what a speech model invents for silence, or the hint echoed back. */
export function fixHeard(text, speechMs = 10000, hint = '') {
  let t = String(text || '').trim().replace(/^"+|"+$/g, '').trim();
  if (!t) return '';
  const b = bare(t);
  if (!b) return '';
  if (speechMs < 700 && GHOSTS.has(b)) return '';
  if (hint.trim() && b === bare(hint)) return '';
  t = t.replace(HEY, (_m, hey) => hey[0].toUpperCase() + hey.slice(1) + ' Atlas,').replace(NAME, 'Atlas');
  return t.replace(/\s+/g, ' ').replace(',,', ',').trim().replace(/,+$/, '');
}

const END = /^(no[, ]+)?(thanks?( you)?[, ]+)?(that'?s all|that is all|that will be all|that'll be all|never ?mind|stop listening|good ?bye|bye( bye)?|nothing else|that'?s it|i'?m done|we'?re done)( for now)?([, ]+(thanks?( you)?|atlas))*$/i;
/** "That's all", "never mind", "goodbye": the conversation is over. */
export const endsTalk = (text) => END.test(String(text || '').trim().replace(/^[.! ]+|[.! ]+$/g, ''));

/** How Atlas answers a spoken request (the same words as the phone's Speech.manner). */
export const manner = (sir) => '- This message came by voice and your answer will be read aloud. ' +
  'Reply in the language the user spoke (if they mixed languages, the main one). ' +
  'Keep it short: usually one or two sentences, three at most, the key fact first. No lists, no formatting, no links, no emoji; ' +
  'say numbers, times and units the way a person says them. ' +
  'Your manner: composed, precise and unflappable, like a superbly capable butler; courteous and quietly warm, ' +
  'with a dry, understated wit when it fits (never forced, and never about bad news or anything serious); never gushing. ' +
  'Confirm what you did in a few words ("Done." "Reminder set for six.") rather than describing it. ' +
  'The words were written down from speech, so a word may be misheard (accents, names, a stammer): read it sensibly in context, ' +
  'and if it still could mean two different things that matter, ask one short question ("Tunde or Tunji?") instead of guessing.' +
  (sir ? ' Address the user as "sir" now and then: at most once in a reply, and not in every reply.' : '');
export const speed = (s) => (!s ? 'normal' : /slow/i.test(s) ? 'slow' : /fast|quick/i.test(s) ? 'fast' : 'normal');
export const rate = (sp) => (sp === 'slow' ? 0.88 : sp === 'fast' ? 1.15 : 1);
export const pace = (sp) => (sp === 'slow' ? 'speaking slowly and clearly' : sp === 'fast' ? 'speaking briskly' : '');

/** Capitalised words that aren't just the start of a sentence: likely names (for the hint). */
export function names(text) {
  const out = []; const words = String(text || '').split(/\s+/);
  words.forEach((raw, i) => {
    const w = raw.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');
    if (w.length < 3 || !/^\p{Lu}/u.test(w) || !/\p{Ll}/u.test(w.slice(1))) return;
    const prev = i ? words[i - 1].slice(-1) : '.';
    if (!'.!?:'.includes(prev)) out.push(w);
  });
  return out;
}

// ---- why the voice never changed: a voice remembered forever, and failures nobody saw (the same rules as Speech.kt) ----
/** Bump to make every device make Atlas's voice afresh. Saved form: "rev|keyTag|id|madeAtMs" (id "-" = turned down). */
export const VOICE_REV = 'v3';
export const VOICE_RETRY_MS = 6 * 60 * 60_000;
function javaHash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; return h; }
export const voiceRev = (design, model) => VOICE_REV + '-' + (javaHash(design + '|' + model) >>> 0).toString(16);
export const voiceEntry = (rev, keyTag, id, now) => `${rev}|${keyTag}|${id || '-'}|${now}`;
/** { use: id } | { refused: true } | { miss: true } */
export function voiceRead(saved, rev, keyTag, now) {
  const p = typeof saved === 'string' ? saved.split('|') : null;
  if (!p || p.length !== 4 || p[0] !== rev || p[1] !== keyTag) return { miss: true };
  if (p[2] === '-') return now - (Number(p[3]) || 0) < VOICE_RETRY_MS ? { refused: true } : { miss: true };
  return p[2] ? { use: p[2] } : { miss: true };
}
/** A used-up limit or a turned-down key: the next key in the pool tries (Gemini's speech model: ~10 a minute, 100 a day). */
export const ttsKeyProblem = (code) => code === 429 || code === 401 || code === 403;
export function ttsRestMs(code, message, retryAfterMs) {
  if (code === 429 && /per ?day|PerDay|daily/i.test(message || '')) return 6 * 60 * 60_000;
  if (code === 429) return Math.min(10 * 60_000, Math.max(30_000, retryAfterMs || 0));
  if (code === 401 || code === 403) return 60 * 60_000;
  return 0;
}
/** Natural voices: fewer, longer pieces (each is one request against a small limit), a short first one so it starts fast. */
export const NATURAL_FIRST = 220, NATURAL_REST = 900;
export const fallbackNote = (problem, sys = "Windows' voice") => (problem
  ? `Atlas's natural voice didn't work (${problem}), so ${sys} spoke. Me → Voice → Test Each Voice shows why.`
  : `Atlas's natural voice isn't available, so ${sys} spoke. Add a Gemini, ElevenLabs or OpenAI key (Me → Voice).`);
