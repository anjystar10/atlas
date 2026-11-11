// The parts of hearing and speaking that are plain logic (no microphone, no network), so they can be tested.
// The phone has the same rules in Speech.kt; keep the two in step.

export const FRAME_MS = 20;

/**
 * Follows the loudness of the microphone, frame by frame, and says when the speaker has finished: 'going', 'done'
 * or 'nothing'. It learns how loud the room is, needs a moment of steady sound before it counts as speech, and
 * waits for a real pause before finishing.
 */
export class Endpointer {
  constructor({ endSilenceMs = 1300, noSpeechMs = 7000, maxMs = 60000 } = {}) {
    Object.assign(this, { endSilenceMs, noSpeechMs, maxMs, frames: 0, floor: -1, recent: 0, speaking: false, speechMs: 0, startFrame: -1, quietMs: 0 });
  }
  get threshold() { return Math.max((this.floor < 0 ? 0.004 : this.floor) * 3, 0.012); }
  feed(rms) {
    this.frames++;
    if (this.floor < 0) this.floor = Math.min(rms, 0.02);
    const loud = rms > this.threshold;
    if (!loud) this.floor += (rms - this.floor) * (rms < this.floor ? 0.2 : 0.02);
    else if (!this.speaking && this.frames > 150 && this.speechMs === 0) this.floor += (Math.min(rms, 0.05) - this.floor) * 0.01;
    this.recent = ((this.recent << 1) | (loud ? 1 : 0)) & 0xff;
    let bits = 0; for (let b = this.recent; b; b >>= 1) bits += b & 1;
    if (loud) this.quietMs = 0; else this.quietMs += FRAME_MS;
    if (!this.speaking && bits >= 5) { this.speaking = true; if (this.startFrame < 0) this.startFrame = Math.max(0, this.frames - 8); }
    if (this.speaking && loud) this.speechMs += FRAME_MS;
    const ms = this.frames * FRAME_MS;
    if (ms >= this.maxMs) return this.speechMs > 0 ? 'done' : 'nothing';
    if (this.speaking && this.speechMs >= 200 && this.quietMs >= this.endSilenceMs) return 'done';
    if (this.speaking && this.speechMs < 200 && this.quietMs >= this.endSilenceMs) { this.speaking = false; this.speechMs = 0; this.startFrame = -1; return 'going'; }
    if (!this.speaking && this.speechMs === 0 && ms >= this.noSpeechMs) return 'nothing';
    return 'going';
  }
}

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
