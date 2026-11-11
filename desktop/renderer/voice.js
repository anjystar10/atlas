// Atlas's ears and voice on the PC.
// Listening: records until you stop talking, then a Gemini (or OpenAI) key turns it into words.
// Speaking: the same natural voices as on the phone (Gemini's or OpenAI's), else the best English voice Windows has.
import { keyFor, readyKey, byId, modelOf } from './brain.js';
import { pref, setPref, db } from './store.js';
import { Endpointer, chunks, fixHeard, names, rate, pace, speed as speedOf } from './speech.js';
import { plainSpoken } from './plain.js';
const host = window.atlasHost;

// An original voice with a butler's manner (not a copy of any actor or character's voice).
const ATLAS_STYLE = 'a composed, impeccably polite British assistant: Received Pronunciation, crisp consonants, low and even pitch, unhurried and precise, quietly warm with understated dry wit; never theatrical, never sing-song';
// With Gemini, how a line is delivered (Google asks for delivery only here: accent and timbre belong to the voice itself)
const ATLAS_DELIVERY = 'composed and unhurried, crisp and precise, quietly warm with understated dry wit; never theatrical, never sing-song';
// The voice itself, designed once from a description (Gemini's voice design): Atlas's own, made up fresh, naming no real person or character.
export const ATLAS_DESIGN = 'A composed, impeccably polite British assistant in his forties with a crisp Received Pronunciation accent and a low, ' +
  'smooth, even baritone; unhurried and precise, quietly warm, with an understated dry wit.';
export const GEMINI_VOICES = [
  { id: 'atlas', label: 'Atlas', about: 'British, calm, refined', voice: 'Charon', style: ATLAS_DELIVERY },
  { id: 'charon', label: 'Charon', about: 'deep, informative', voice: 'Charon', style: 'clear and steady, informative, relaxed' },
  { id: 'algenib', label: 'Algenib', about: 'gravelly, low', voice: 'Algenib', style: 'low and gravelly, unhurried, quietly confident' },
  { id: 'gacrux', label: 'Gacrux', about: 'mature, assured', voice: 'Gacrux', style: 'mature and assured, measured pace' },
  { id: 'puck', label: 'Puck', about: 'upbeat, playful', voice: 'Puck', style: 'upbeat and playful, lively' },
  { id: 'fenrir', label: 'Fenrir', about: 'energetic', voice: 'Fenrir', style: 'energetic and excited, fast-paced' },
  { id: 'sulafat', label: 'Sulafat', about: 'warm (female)', voice: 'Sulafat', style: 'warm and friendly, relaxed' },
  { id: 'kore', label: 'Kore', about: 'firm (female)', voice: 'Kore', style: 'firm and confident, clear' },
  { id: 'zephyr', label: 'Zephyr', about: 'bright (female)', voice: 'Zephyr', style: 'bright and cheerful' },
];
export const OPENAI_VOICES = [
  { id: 'atlas', label: 'Atlas', about: 'British, calm, refined', voice: 'fable', style: 'Accent: British Received Pronunciation throughout. Manner: ' + ATLAS_STYLE + '. Pacing: measured, with brief natural pauses at commas and full stops.' },
  { id: 'onyx', label: 'Onyx', about: 'deep', voice: 'onyx', style: 'Deep, calm and confident.' },
  { id: 'ash', label: 'Ash', about: 'clear, direct', voice: 'ash', style: 'Clear and direct, friendly.' },
  { id: 'echo', label: 'Echo', about: 'smooth', voice: 'echo', style: 'Smooth and relaxed.' },
  { id: 'cedar', label: 'Cedar', about: 'natural', voice: 'cedar', style: 'Natural and warm, like a friend.' },
  { id: 'marin', label: 'Marin', about: 'natural (female)', voice: 'marin', style: 'Natural and warm, like a friend.' },
  { id: 'nova', label: 'Nova', about: 'bright (female)', voice: 'nova', style: 'Bright and upbeat.' },
];
export const engine = () => (!pref('voiceNatural', true) ? null : readyKey('gemini') || keyFor('gemini') ? 'gemini' : keyFor('openai') ? 'openai' : null);
export const voices = (eng = engine()) => (eng === 'openai' ? OPENAI_VOICES : GEMINI_VOICES);
export const current = (eng = engine()) => voices(eng).find((v) => v.id === pref('voice_' + (eng || 'gemini'), 'atlas')) || voices(eng)[0];
// busy: from the moment Atlas is asked to speak until it has finished or been hushed (so also while the voice is fetched)
export const state = { speaking: false, busy: false, lastSpoke: null, lastProblem: null };
export const speed = () => speedOf(pref('voiceSpeed', 'normal'));
export const setSpeed = (v) => { const sp = speedOf(v); setPref('voiceSpeed', sp); return sp; };

let audio = null, gen = 0;
export function hush() { gen++; state.speaking = false; state.busy = false; if (audio) { try { audio.pause(); } catch {} audio = null; } try { speechSynthesis.cancel(); } catch {} }

/**
 * Says [text]. A long reply is spoken in pieces (a short first one, so the voice starts quickly; the next is fetched
 * while it plays). If the natural voice fails part-way, Windows' own voice says the rest: a reply is never dropped.
 */
export async function speak(text, onDone) {
  hush(); const mine = gen;
  const say = plainSpoken(text); if (!say) { onDone && onDone(); return; }
  state.busy = true;
  const finish = () => { if (mine !== gen) return; state.busy = false; state.speaking = false; onDone && onDone(); };
  const eng = engine();
  const parts = chunks(say);
  if (!eng) return systemSpeak(parts, finish, mine);
  const v = current(eng);
  const fetchPart = (i) => (eng === 'gemini' ? geminiSpeech(parts[i], v) : openAiSpeech(parts[i], v)).then((clip) => ({ clip }), (e) => ({ error: e }));
  let next = fetchPart(0);
  for (let i = 0; i < parts.length; i++) {
    const got = await next;
    if (mine !== gen) { free(got.clip); return; }
    if (got.error) { state.lastProblem = got.error.message || String(got.error); return systemSpeak(parts.slice(i), finish, mine); }
    if (i === 0) { state.lastProblem = null; state.lastSpoke = `${v.label} (${eng === 'gemini' ? 'Gemini' : 'OpenAI'})`; }
    next = i + 1 < parts.length ? fetchPart(i + 1) : null;
    const ok = await play(got.clip, mine);
    if (mine !== gen) { if (next) next.then((n) => free(n.clip)); return; }
    if (!ok) { if (next) next.then((n) => free(n.clip)); return systemSpeak(parts.slice(i), finish, mine); }
  }
  finish();
}
const free = (clip) => { if (clip && clip.startsWith('blob:')) URL.revokeObjectURL(clip); };
/** Plays one clip to its end. Resolves true when it played, false when it couldn't. */
function play(clip, mine) {
  return new Promise((resolve) => {
    const a = new Audio(clip); audio = a;
    try { a.playbackRate = rate(speed()); a.preservesPitch = true; } catch {}
    const end = (ok) => { free(clip); if (audio === a) audio = null; state.speaking = false; resolve(ok); };
    a.onended = () => end(true); a.onerror = () => end(false);
    a.onpause = () => { if (mine !== gen) end(true); };
    a.play().then(() => { if (mine === gen) state.speaking = true; }, () => end(false));
  });
}

function systemSpeak(parts, finish, mine) {
  const vs = speechSynthesis.getVoices().filter((v) => v.lang.startsWith('en'));
  // a British voice for Atlas, the "natural" ones first (Windows names them that way)
  const score = (v) => (v.lang === 'en-GB' ? 4 : 0) + (/natural|neural|online/i.test(v.name) ? 3 : 0) + (/ryan|george|thomas|male/i.test(v.name) ? 1 : 0);
  const best = vs.sort((a, b) => score(b) - score(a))[0];
  if (best) state.lastSpoke = `Windows voice (${best.name})`;
  // piece by piece: one very long utterance can be cut short by the system voice
  let i = 0;
  const one = () => {
    if (mine !== gen) return;
    if (i >= parts.length) return finish();
    const u = new SpeechSynthesisUtterance(parts[i++]);
    if (best) u.voice = best;
    u.rate = rate(speed()); u.onstart = () => { if (mine === gen) state.speaking = true; };
    u.onend = one; u.onerror = () => { state.speaking = false; if (mine === gen) finish(); };
    try { speechSynthesis.speak(u); } catch { finish(); }
  };
  one();
}

/** The `voice_…` id in a voice-design answer (it may come as "id" or as a resource name ending in it). */
export function voiceId(o) {
  let found = null;
  (function walk(x) {
    if (found || !x || typeof x !== 'object') return;
    if (Array.isArray(x)) { x.forEach(walk); return; }
    for (const k of ['id', 'name', 'voice']) { const v = x[k]; if (typeof v === 'string' && v.split('/').pop().startsWith('voice_')) { found = v.split('/').pop(); return; } }
    for (const [k, v] of Object.entries(x)) if (k !== 'sample_audio') walk(v);
  })(o);
  return found;
}
/**
 * Atlas's own voice, designed once on this Gemini key and remembered (Google keeps it a year after last use).
 * A refusal is remembered so it isn't asked again; a dropped connection or a busy server tries again next time.
 */
async function designedVoice(key) {
  const tag = key.slice(-6); const saved = pref('atlasVoiceDesigned', '');
  if (saved === '-') return null;
  if (saved) { const [t, id] = saved.split('|'); if (t === tag && id) return id; }
  const body = { store: true, voice: { model: 'gemini-3.8-flash-tts', type: 'prompted', display_name: 'Atlas', gender: 'male', language_code: 'en-GB', prompted: { input: ATLAS_DESIGN } } };
  const r = await host.fetch('https://generativelanguage.googleapis.com/v1beta/voices', { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 60000 });
  if (!r.ok) { if (r.status >= 400 && r.status < 500 && r.status !== 429 && r.status !== 408) setPref('atlasVoiceDesigned', '-'); return null; }
  let id = null; try { id = voiceId(JSON.parse(r.text)); } catch {}
  setPref('atlasVoiceDesigned', id ? `${tag}|${id}` : '-');
  return id;
}
async function geminiCall(key, text, voice, style) {
  const body = { model: 'gemini-3.8-flash-tts', input: [{ type: 'user_input', content: [{ type: 'text', text, annotations: [{ type: 'speech_metadata', style }] }] }],
    response_format: { type: 'audio' }, generation_config: { speech_config: [{ voice }] } };
  return host.fetch('https://generativelanguage.googleapis.com/v1beta/interactions', { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 60000 });
}
async function geminiSpeech(text, v) {
  const key = keyFor('gemini');
  const style = v.style + (pace(speed()) ? '; ' + pace(speed()) : '');
  // "Atlas": its own designed voice when it can be made, else Charon
  const designed = v.id === 'atlas' ? await designedVoice(key).catch(() => null) : null;
  let r = await geminiCall(key, text, designed || v.voice, style);
  if (!r.ok && designed) { setPref('atlasVoiceDesigned', '-'); r = await geminiCall(key, text, v.voice, style); }
  if (!r.ok) throw new Error(`Gemini voice: ${r.status || r.error}`);
  let best = null;
  (function walk(x) { if (Array.isArray(x)) x.forEach(walk); else if (x && typeof x === 'object') for (const [k, val] of Object.entries(x)) { if (k === 'data' && typeof val === 'string' && val.length > (best ? best.length : 200)) best = val; else walk(val); } })(JSON.parse(r.text));
  if (!best) throw new Error('Gemini sent no audio');
  const bytes = Uint8Array.from(atob(best), (c) => c.charCodeAt(0));
  const isWav = bytes[0] === 0x52 && bytes[1] === 0x49;
  return URL.createObjectURL(new Blob([isWav ? bytes : wav(bytes, 24000)], { type: 'audio/wav' }));
}
function wav(pcm, rate) {
  const h = new DataView(new ArrayBuffer(44)); const w = (o, s) => [...s].forEach((c, i) => h.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF'); h.setUint32(4, 36 + pcm.length, true); w(8, 'WAVE'); w(12, 'fmt '); h.setUint32(16, 16, true); h.setUint16(20, 1, true); h.setUint16(22, 1, true);
  h.setUint32(24, rate, true); h.setUint32(28, rate * 2, true); h.setUint16(32, 2, true); h.setUint16(34, 16, true); w(36, 'data'); h.setUint32(40, pcm.length, true);
  const out = new Uint8Array(44 + pcm.length); out.set(new Uint8Array(h.buffer)); out.set(pcm, 44); return out;
}
async function openAiSpeech(text, v) {
  const r = await host.fetch('https://api.openai.com/v1/audio/speech', { method: 'POST', binary: true, headers: { Authorization: 'Bearer ' + keyFor('openai'), 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'gpt-4o-mini-tts', input: text, voice: v.voice, instructions: v.style + ' Natural, never robotic.' + (pace(speed()) ? ' Pace: ' + pace(speed()) + '.' : ''), response_format: 'mp3' }), timeout: 60000 });
  if (!r.ok) throw new Error(`OpenAI voice: ${r.status || r.error}`);
  return 'data:audio/mpeg;base64,' + r.base64;
}

// ------------------------------------------------------------------ listening

const RATE = 16000, FRAME = RATE * 20 / 1000;
/** The speech models that can write down what's said, fastest first: [id, key]. */
const ears = () => ['groq', 'openai', 'gemini'].map((id) => [id, readyKey(id)]).filter(([, k]) => k && k !== 'local');
/** Names and words Atlas should expect to hear, so they're spelled right. */
export function hint() {
  const words = new Set(['Atlas']);
  try {
    const name = (db.state.settings.name || '').trim(); if (name) words.add(name);
    [...db.state.memories.slice(-40).map((m) => m.text), ...db.state.tasks.slice(-40).map((t) => t.title)].forEach((t) => names(t).forEach((w) => words.add(w)));
  } catch {}
  return [...words].slice(0, 40).join(', ');
}

/**
 * Listens until you stop talking (or [stop]() is called). Calls onLevel(0..1) as you speak and onState('writing')
 * while a speech model writes it down, then resolves to the words heard ('' for silence). Throws with a sentence
 * for the user when it can't.
 */
export function listen({ onLevel = () => {}, onState = () => {}, maxMs = 60000 } = {}) {
  // stopped before the microphone was even ready (Cancel straight away): don't start recording at all
  let stopped = false;
  let stopFn = () => { stopped = true; };
  const done = (async () => {
    if (!ears().length) throw new Error('To hear you, Atlas needs a Groq, Gemini or OpenAI key (Me → Brain).');
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }); }
    catch { throw new Error("Atlas can't use the microphone. Check Windows Settings → Privacy → Microphone."); }
    if (stopped) { stream.getTracks().forEach((t) => t.stop()); return ''; }
    let ctx = null; const ep = new Endpointer({ maxMs }); const pcm = []; let verdict = 'going';
    try {
      ctx = new AudioContext({ sampleRate: RATE });
      const src = ctx.createMediaStreamSource(stream);
      const tap = ctx.createScriptProcessor(1024, 1, 1); const mute = ctx.createGain(); mute.gain.value = 0;
      src.connect(tap); tap.connect(mute); mute.connect(ctx.destination);
      let carry = new Float32Array(0), shown = 0;
      await new Promise((resolve) => {
        stopFn = () => { stopped = true; resolve(); };
        tap.onaudioprocess = (e) => {
          if (stopped || verdict !== 'going') return;
          const inp = e.inputBuffer.getChannelData(0);
          const all = new Float32Array(carry.length + inp.length); all.set(carry); all.set(inp, carry.length);
          let o = 0;
          for (; o + FRAME <= all.length && verdict === 'going'; o += FRAME) {
            const f = all.subarray(o, o + FRAME); let sum = 0; for (const v of f) sum += v * v; const rms = Math.sqrt(sum / FRAME);
            pcm.push(f.slice()); verdict = ep.feed(rms);
            shown += (Math.min(1, rms * 9) - shown) * 0.5;
          }
          carry = all.slice(o); onLevel(shown);
          if (verdict !== 'going') resolve();
        };
      });
    } finally { stream.getTracks().forEach((t) => t.stop()); try { ctx && ctx.close(); } catch {} onLevel(0); }
    if (verdict === 'nothing' || ep.speechMs < 150) return '';
    // only the part with the voice in it (and a little either side) is sent
    const from = Math.max(0, ep.startFrame - 15);
    const b64 = wavBase64(pcm.slice(from));
    onState('writing');
    const h = hint(); let last = null;
    for (const [id, key] of ears()) {
      try {
        const t = id === 'gemini' ? await transcribeGemini(key, b64, h) : await transcribeWhisper(id, key, b64, h);
        return fixHeard(t, ep.speechMs, h);
      } catch (e) { last = e; }
    }
    throw last || new Error("Couldn't make out the words.");
  })();
  return { done, stop: () => stopFn() };
}

function wavBase64(frames) {
  const n = frames.length * FRAME; const bytes = new Uint8Array(n * 2); const dv = new DataView(bytes.buffer); let o = 0;
  for (const f of frames) for (const v of f) { dv.setInt16(o, Math.max(-1, Math.min(1, v)) * 32767, true); o += 2; }
  const w = wav(bytes, RATE); let bin = '';
  for (let i = 0; i < w.length; i += 0x8000) bin += String.fromCharCode.apply(null, w.subarray(i, i + 0x8000));
  return btoa(bin);
}

async function transcribeGemini(key, b64, h) {
  const model = modelOf(byId('gemini'));
  const ask = 'Write down exactly what is said in this recording, in the language spoken. Output only the words: no quotes, no notes, no timestamps. If nobody speaks, output nothing at all.' + (h ? ` Names that may come up: ${h}.` : '');
  const r = await host.fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, timeout: 45000,
    body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: ask }, { inline_data: { mime_type: 'audio/wav', data: b64 } }] }],
      generationConfig: { temperature: 0, ...(model.startsWith('gemini-3') ? { thinkingConfig: { thinkingLevel: 'low' } } : {}) } }) });
  if (!r.ok) throw new Error(`Couldn't make out the words (Gemini ${r.status || r.error}).`);
  const c = (JSON.parse(r.text).candidates || [])[0];
  return ((c && c.content && c.content.parts) || []).map((p) => (p.thought ? '' : p.text || '')).join('').trim();
}
/** The OpenAI-style transcription endpoint (Groq's Whisper, OpenAI). */
/** The language you speak ("en", "en-GB", "fr"…), or '' to let the speech model work it out. */
export const language = () => { const l = String(pref('hearLang', '') || '').trim(); return /^auto$/i.test(l) ? '' : l; };
export const setLanguage = (v) => setPref('hearLang', String(v || '').trim());
export const LANGUAGES = [['auto', 'Any'], ['en', 'English'], ['en-GB', 'English (UK)'], ['en-NG', 'English (Nigeria)'], ['fr', 'French'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['ar', 'Arabic'], ['ha', 'Hausa'], ['yo', 'Yoruba'], ['ig', 'Igbo']];
/** Speech models take the two-letter code only. */
const whisperLang = () => { const l = language().slice(0, 2).toLowerCase(); return /^[a-z]{2}$/.test(l) ? l : ''; };

// OpenAI's newest speech model takes the expected names as keywords and the language as a list; older ones follow.
// A model OpenAI turns down is skipped from then on (until Atlas restarts).
const OPENAI_EARS = ['gpt-transcribe', 'gpt-4o-mini-transcribe', 'whisper-1'];
let openAiSkip = 0;
/** The form for one transcription request (exported for the tests). */
export function transcribeForm(model, h, lang, file) {
  const newer = model === 'gpt-transcribe';
  const f = { model, response_format: 'json', ...(newer ? {} : { temperature: '0' }), ...(h ? { prompt: h } : {}) };
  if (newer) {
    const kw = h ? h.split(', ').map((w) => w.trim()).filter(Boolean).slice(0, 30) : [];
    if (kw.length) f['keywords[]'] = kw;
    if (lang) f['languages[]'] = [lang];
  } else if (lang) f.language = lang;
  f.file = file;
  return f;
}
async function transcribeWhisper(id, key, b64, h) {
  const groq = id === 'groq';
  const url = groq ? 'https://api.groq.com/openai/v1/audio/transcriptions' : 'https://api.openai.com/v1/audio/transcriptions';
  const call = (model) => host.fetch(url, { method: 'POST', headers: { Authorization: 'Bearer ' + key }, timeout: 30000,
    form: transcribeForm(model, h, whisperLang(), { base64: b64, type: 'audio/wav', name: 'speech.wav' }) });
  let r;
  if (groq) r = await call('whisper-large-v3-turbo');
  else {
    for (let i = Math.min(openAiSkip, OPENAI_EARS.length - 1); ; i++) {
      r = await call(OPENAI_EARS[i]);
      if (r.ok || !(r.status === 404 || r.status === 400) || i >= OPENAI_EARS.length - 1) break;
      openAiSkip = i + 1;
    }
  }
  if (!r.ok) throw new Error(`Couldn't make out the words (${groq ? 'Groq' : 'OpenAI'} ${r.status || r.error}).`);
  return (JSON.parse(r.text).text || '').trim();
}

/** Plays a voice right away (Me → Voice → Hear It). */
export function preview(eng, id) { setPref('voice_' + eng, id); speak("Good day. I'm Atlas. This is how I sound. What can I do for you?"); }
