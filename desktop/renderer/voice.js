// Atlas's ears and voice on the PC.
// Listening: records until you stop talking, then a Gemini (or OpenAI) key turns it into words.
// Speaking: the same natural voices as on the phone (Atlas's designed ElevenLabs voice, Gemini's, OpenAI's), with the
// same "AI system" finish (finish.js), else the best English voice Windows has (which plays as Windows makes it).
import { keyFor, readyKey, byId, modelOf, keys as poolKeys } from './brain.js';
import { applyPcm } from './finish.js';
import { pref, setPref, db } from './store.js';
import { Endpointer, Pace, minVoiceFor, heardFrom, chunks, fixHeard, names, rate, pace, speed as speedOf, voiceRev, voiceEntry, voiceRead, ttsKeyProblem, ttsRestMs, NATURAL_FIRST, NATURAL_REST, fallbackNote } from './speech.js';
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
// ElevenLabs: one voice, Atlas's own, designed there from a description of traits (no one's voice is copied).
// Checked against ElevenLabs' API reference (November 2026); the same calls, seed and description as the phone's ElevenLabs.kt.
export const ELEVEN_DESIGN = 'A refined British man in his mid-forties with a crisp, educated Received Pronunciation accent. ' +
  'A calm, low, smooth baritone with precise diction; unhurried, composed and quietly confident, with an understated dry wit. ' +
  'Courteous and warm but reserved, like a polished butler who is also a highly capable AI assistant. ' +
  'Clean studio recording, close microphone, no background noise.';
const ELEVEN_SEED = 1096043603, ELEVEN = 'https://api.elevenlabs.io/v1';
export const ELEVEN_VOICES = [{ id: 'atlas', label: 'Atlas', about: 'designed for Atlas: British, calm, refined', voice: '', style: '' }];
/** The natural voices, best first. "atlas" is Atlas Voice: Atlas's own designed voice on this laptop's graphics card. */
export const ENGINES = ['atlas', 'elevenlabs', 'gemini', 'openai'];
export const engineName = (e) => ({ atlas: 'Atlas Voice', elevenlabs: 'ElevenLabs', gemini: 'Gemini', openai: 'OpenAI' }[e] || 'Windows');
export const LOCAL_VOICES = [{ id: 'atlas', label: 'Atlas', about: "Atlas's own designed voice, on this laptop", voice: '', style: '' }];
// Atlas Voice's state, from the main process (atlasvoice.js): kept fresh here so choosing a voice never waits
export const local = { ready: false, status: null };
export async function refreshLocal() {
  try { const st = host.atlasVoice ? await host.atlasVoice.status() : null; local.status = st; local.ready = !!(st && st.ready); } catch { local.ready = false; }
  return local;
}
if (host && host.atlasVoice) {
  refreshLocal(); setInterval(refreshLocal, 15_000);
  try { host.on('voice-local', (st) => { local.status = st; local.ready = !!(st && st.ready); }); } catch {}
}
/** The natural voices that can speak right now, best first (none when Natural Voice is off). */
export const engines = () => (!pref('voiceNatural', true) ? [] : ENGINES.filter((e) => (e === 'atlas' ? local.ready : keyFor(e))));
/**
 * Which voice speaks: Atlas Voice whenever it's running (unless the user picked another voice while it was there),
 * else the one chosen in Me → Voice if it can speak, else the best that can; null: Windows' voice.
 */
export function chooseEngine(have, pick, localOff) {
  if (have.includes('atlas') && (pick === 'atlas' || !localOff)) return 'atlas';
  return have.includes(pick) ? pick : have.find((e) => e !== 'atlas') || have[0] || null;
}
export const engine = () => chooseEngine(engines(), pref('voiceEngine', ''), pref('voiceLocalOff', false));
export const setEngine = (e) => { setPref('voiceEngine', e); if (local.ready) setPref('voiceLocalOff', e !== 'atlas'); };
/** The "AI system" finish on every spoken reply (on unless switched off). */
export const finishOn = () => pref('voiceFinish', true);
export const setFinish = (on) => setPref('voiceFinish', !!on);
/** Spoken replies may address the user as "sir" now and then. */
export const sir = () => pref('voiceSir', false);
export const setSir = (on) => setPref('voiceSir', !!on);
export const voices = (eng = engine()) => (eng === 'atlas' ? LOCAL_VOICES : eng === 'openai' ? OPENAI_VOICES : eng === 'elevenlabs' ? ELEVEN_VOICES : GEMINI_VOICES);
export const current = (eng = engine()) => voices(eng).find((v) => v.id === pref('voice_' + (eng || 'gemini'), 'atlas')) || voices(eng)[0];
// busy: from the moment Atlas is asked to speak until it has finished or been hushed (so also while the voice is fetched)
export const state = { speaking: false, busy: false, lastSpoke: null, lastProblem: null, engines: {} };
const stamp = () => new Date().toTimeString().slice(0, 5);
/** Called with a line to show the user (app.js sets it to its toast). */
export const notices = { show: null };
let notedAt = 0;
/** Once every few hours: the system voice spoke instead of a natural one, and why (before, this happened silently). */
function noteFallback() {
  if (!pref('voiceNatural', true) || Date.now() - notedAt < 3 * 60 * 60_000) return;
  notedAt = Date.now();
  const msg = fallbackNote(state.lastProblem);
  console.warn('[AtlasVoice] fallback to Windows voice:', state.lastProblem || 'no natural voice');
  try { notices.show && notices.show(msg); } catch {}
}
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
  if (!eng) { noteFallback(); return systemSpeak(chunks(say), finish, mine); }
  const parts = chunks(say, NATURAL_FIRST, NATURAL_REST);
  // each piece from the chosen voice; if it fails, the next natural voice there's a key for says that piece
  const fetchPart = async (i) => {
    let error = null;
    for (const e of [eng, ...engines().filter((x) => x !== eng)]) {
      if (mine !== gen) break;
      try { const clip = await speechClip(e, parts[i], parts[i - 1], parts[i + 1]); state.engines[e] = `worked ${stamp()}`; return { clip, used: e }; }
      catch (err) { error = err; state.lastProblem = `${engineName(e)}: ${err.message || String(err)}`; state.engines[e] = `failed ${stamp()}: ${err.message || String(err)}`; }
    }
    return { error: error || new Error('stopped') };
  };
  let next = fetchPart(0);
  for (let i = 0; i < parts.length; i++) {
    const got = await next;
    if (mine !== gen) { free(got.clip); return; }
    if (got.error) { noteFallback(); return systemSpeak(parts.slice(i), finish, mine); }
    if (i === 0) { if (got.used === eng) state.lastProblem = null; state.lastSpoke = `${current(got.used).label} (${engineName(got.used)})`; }
    next = i + 1 < parts.length ? fetchPart(i + 1) : null;
    const ok = await play(got.clip, mine, got.used);
    if (mine !== gen) { if (next) next.then((n) => free(n.clip)); return; }
    if (!ok) { if (next) next.then((n) => free(n.clip)); state.lastProblem = `${engineName(got.used)}: the audio couldn't be played`; noteFallback(); return systemSpeak(parts.slice(i), finish, mine); }
  }
  finish();
}
const free = (clip) => { if (clip && clip.startsWith('blob:')) URL.revokeObjectURL(clip); };
/** Plays one clip to its end. Resolves true when it played, false when it couldn't. */
/** Atlas Voice replies come out about 20% brisker than the designed voice (voice lab), so they play a touch slower. */
export const enginePace = (eng) => (eng === 'atlas' ? 0.86 : 1);
function play(clip, mine, eng) {
  return new Promise((resolve) => {
    const a = new Audio(clip); audio = a;
    try { a.playbackRate = rate(speed()) * enginePace(eng); a.preservesPitch = true; } catch {}
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

/** One piece of speech from [eng], finished, as a playable WAV clip. */
async function speechClip(eng, text, before, after) {
  const v = current(eng);
  const { pcm, rate: hz } = eng === 'atlas' ? await localSpeech(text) : eng === 'elevenlabs' ? await elevenSpeech(text, before, after) : eng === 'openai' ? await openAiSpeech(text, v) : await geminiSpeech(text, v);
  if (!pcm || pcm.length < 200) throw new Error(`${engineName(eng)} sent no audio`);
  return URL.createObjectURL(new Blob([wav(finishOn() ? applyPcm(pcm, hz) : pcm, hz)], { type: 'audio/wav' }));
}
/** Atlas Voice on this laptop: 16-bit PCM straight from the local voice server (main process). */
export async function localSpeech(text) {
  const r = await host.atlasVoice.speak(text, lastHeard && lastHeard.language ? languageName(lastHeard.language) : 'English');
  return { pcm: r.pcm instanceof Uint8Array ? r.pcm : new Uint8Array(r.pcm), rate: r.rate };
}
/** Qwen3-TTS takes a language name; the ears give a code ("en", "fr" …). Unknown ones fall back to English. */
export function languageName(code) {
  const c = String(code || '').toLowerCase().trim().split(/[-_]/)[0];
  const names = { en: 'English', zh: 'Chinese', ja: 'Japanese', ko: 'Korean', de: 'German', fr: 'French', ru: 'Russian', pt: 'Portuguese', es: 'Spanish', it: 'Italian' };
  return names[c] || Object.values(names).find((n) => n.toLowerCase() === c) || 'English';
}
const bytesOf = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
/** ElevenLabs' errors come as {"detail": {"message": …}}. */
function elevenError(r) {
  let msg = ''; try { const o = JSON.parse(r.text || '{}'); msg = (o.detail && (o.detail.message || (Array.isArray(o.detail) && o.detail[0] && o.detail[0].msg))) || (typeof o.detail === 'string' ? o.detail : ''); } catch {}
  const e = new Error(`ElevenLabs ${r.status || r.error}${msg ? ': ' + String(msg).slice(0, 160) : ''}`); e.code = r.status || 0; return e;
}
async function elevenPost(key, url, body, binary) {
  const r = await host.fetch(url, { method: 'POST', binary, headers: { 'xi-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 90000 });
  if (!r.ok) { if (binary && !r.text && r.base64) try { r.text = atob(r.base64); } catch {} throw elevenError(r); }
  return r;
}
/** Atlas's designed voice on this key: made the first time (design → create), then remembered with the key it belongs to. */
async function elevenVoice(key) {
  const tag = key.slice(-6); const saved = String(pref('elevenVoice', '') || '').split('|');
  if (saved.length === 2 && saved[0] === tag) { if (saved[1] === '-') throw Object.assign(new Error("ElevenLabs couldn't design Atlas's voice on this key."), { code: 403 }); if (saved[1]) return saved[1]; }
  try {
    const d = await elevenPost(key, `${ELEVEN}/text-to-voice/design?output_format=mp3_22050_32`, { voice_description: ELEVEN_DESIGN, model_id: 'eleven_ttv_v3', auto_generate_text: true, seed: ELEVEN_SEED });
    const gen = ((JSON.parse(d.text).previews || [])[0] || {}).generated_voice_id; if (!gen) throw new Error('ElevenLabs designed no voice.');
    const m = await elevenPost(key, `${ELEVEN}/text-to-voice`, { voice_name: 'Atlas', voice_description: ELEVEN_DESIGN, generated_voice_id: gen, labels: { made_by: 'Atlas' } });
    const id = JSON.parse(m.text).voice_id; if (!id) throw new Error('ElevenLabs saved no voice.');
    setPref('elevenVoice', `${tag}|${id}`); return id;
  } catch (e) {
    // turned down (not the key itself, not a limit, not the network): don't ask again on this key
    if (e.code >= 400 && e.code < 500 && ![401, 408, 409, 429].includes(e.code)) setPref('elevenVoice', `${tag}|-`);
    throw e;
  }
}
async function elevenSpeech(text, before, after) {
  let key = keyFor('elevenlabs');
  for (let attempt = 0; ; attempt++) {
    try {
      const id = await elevenVoice(key);
      const body = { text, model_id: 'eleven_multilingual_v2', voice_settings: { stability: 0.55, similarity_boost: 0.8, style: 0, use_speaker_boost: true } };
      if (before) body.previous_text = before.slice(-600); if (after) body.next_text = after.slice(0, 600);
      let r;
      try { r = await elevenPost(key, `${ELEVEN}/text-to-speech/${id}/stream?output_format=pcm_24000`, body, true); }
      catch (e) { if (e.code === 404) setPref('elevenVoice', ''); throw e; }   // the voice was removed: designed again next time
      return { pcm: bytesOf(r.base64), rate: 24000 };
    } catch (e) {
      // a key that's turned down or out of credit: the next key in the pool tries once
      const other = [401, 402, 403, 429].includes(e.code) && attempt === 0 ? poolKeys('elevenlabs').find((k) => !k.bad && k.key !== key)?.key : null;
      if (!other) throw e;
      key = other;
    }
  }
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
const TTS_MODEL = 'gemini-3.8-flash-tts';
const rev = () => voiceRev(ATLAS_DESIGN, TTS_MODEL);
const designKey = (key) => 'atlasVoiceDesigned@' + key.slice(-6);
async function designedVoice(key) {
  const tag = key.slice(-6), now = Date.now();
  const hit = voiceRead(pref(designKey(key), ''), rev(), tag, now);
  if (hit.use) return hit.use;
  if (hit.refused) return null;
  const body = { store: true, voice: { model: TTS_MODEL, type: 'prompted', display_name: 'Atlas', gender: 'male', language_code: 'en-GB', prompted: { input: ATLAS_DESIGN } } };
  const r = await host.fetch('https://generativelanguage.googleapis.com/v1beta/voices', { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 60000 });
  if (!r.ok) {
    state.engines['gemini-design'] = `Gemini voice design: ${r.status || r.error}`;
    // turned down: not again for a few hours (it used to be never again, so the voice could never change)
    if (r.status >= 400 && r.status < 500 && r.status !== 429 && r.status !== 408) setPref(designKey(key), voiceEntry(rev(), tag, null, now));
    return null;
  }
  let id = null; try { id = voiceId(JSON.parse(r.text)); } catch {}
  setPref(designKey(key), voiceEntry(rev(), tag, id, now));
  return id;
}
/** Gemini refused the voice itself (not the network, a busy server or a limit). */
export const refusedVoice = (status) => status >= 400 && status < 500 && status !== 429 && status !== 408;
async function geminiCall(key, text, voice, style) {
  const body = { model: TTS_MODEL, input: [{ type: 'user_input', content: [{ type: 'text', text, annotations: [{ type: 'speech_metadata', style }] }] }],
    response_format: { type: 'audio' }, generation_config: { speech_config: [{ voice }] } };
  return host.fetch('https://generativelanguage.googleapis.com/v1beta/interactions', { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 60000 });
}
/** Keys resting from speech only (the speech model's limit is far smaller than chat's): key tail → until. */
const ttsRest = new Map();
/** Every Gemini key in the pool that isn't turned down, those not resting from speech first. */
export function ttsKeyOrder(all, rest, now) {
  const list = [...new Set(all.filter((e) => e && !e.bad).map((e) => e.key))];
  const resting = (k) => (rest.get(k.slice(-6)) || 0) > now;
  return [...list.filter((k) => !resting(k)), ...list.filter(resting)];
}
async function geminiSpeech(text, v) {
  const order = ttsKeyOrder(poolKeys('gemini'), ttsRest, Date.now());
  if (!order.length) { const k = keyFor('gemini'); if (k) order.push(k); else throw new Error('no Gemini key'); }
  let last = null;
  for (const key of order) {
    try { return await geminiSpeechWith(key, text, v); }
    catch (e) {
      last = e;
      if (!ttsKeyProblem(e.code)) throw e;
      // a used-up limit or a turned-down key: this key rests from speech and the next key in the pool tries
      ttsRest.set(key.slice(-6), Date.now() + ttsRestMs(e.code, e.message, 0));
    }
  }
  throw last || new Error('Gemini voice');
}
async function geminiSpeechWith(key, text, v) {
  const style = v.style + (pace(speed()) ? '; ' + pace(speed()) : '');
  // "Atlas": its own designed voice when it can be made, else Charon
  const designed = v.id === 'atlas' ? await designedVoice(key).catch(() => null) : null;
  let r = await geminiCall(key, text, designed || v.voice, style);
  if (!r.ok && designed && !ttsKeyProblem(r.status)) {
    // only Gemini turning the voice down (a 4xx other than 429/408) means it's gone; a dropped connection (status 0),
    // a busy server or a used-up limit says nothing about the voice, so it's kept for next time
    if (refusedVoice(r.status)) setPref(designKey(key), voiceEntry(rev(), key.slice(-6), null, Date.now()));
    r = await geminiCall(key, text, v.voice, style);
  }
  if (!r.ok) {
    let msg = ''; try { msg = (JSON.parse(r.text || '{}').error || {}).message || ''; } catch {}
    throw Object.assign(new Error(`Gemini voice: ${r.status || r.error}${msg ? ' ' + String(msg).slice(0, 160) : ''}`), { code: r.status || 0 });
  }
  let best = null;
  (function walk(x) { if (Array.isArray(x)) x.forEach(walk); else if (x && typeof x === 'object') for (const [k, val] of Object.entries(x)) { if (k === 'data' && typeof val === 'string' && val.length > (best ? best.length : 200)) best = val; else walk(val); } })(JSON.parse(r.text));
  if (!best) throw new Error('Gemini sent no audio');
  return wavData(bytesOf(best));
}
/** (rate, sound) of a WAV file's "data" chunk; anything that isn't one is raw 16-bit sound at 24 kHz (what Gemini sends). */
export function wavData(b) {
  const tag = (at, t) => at + 4 <= b.length && String.fromCharCode(b[at], b[at + 1], b[at + 2], b[at + 3]) === t;
  const le32 = (at) => (b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (b[at + 3] << 24)) >>> 0;
  if (!(tag(0, 'RIFF') && tag(8, 'WAVE'))) return { pcm: b, rate: 24000 };
  let rate = 24000, at = 12;
  while (at + 8 <= b.length) {
    const len = le32(at + 4);
    if (tag(at, 'fmt ') && at + 16 <= b.length) rate = le32(at + 12);
    if (tag(at, 'data')) return { pcm: b.subarray(at + 8, Math.min(b.length, at + 8 + len)), rate };
    at += 8 + len + (len & 1);
  }
  return { pcm: new Uint8Array(0), rate };
}
export function wav(pcm, rate) {
  const h = new DataView(new ArrayBuffer(44)); const w = (o, s) => [...s].forEach((c, i) => h.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF'); h.setUint32(4, 36 + pcm.length, true); w(8, 'WAVE'); w(12, 'fmt '); h.setUint32(16, 16, true); h.setUint16(20, 1, true); h.setUint16(22, 1, true);
  h.setUint32(24, rate, true); h.setUint32(28, rate * 2, true); h.setUint16(32, 2, true); h.setUint16(34, 16, true); w(36, 'data'); h.setUint32(40, pcm.length, true);
  const out = new Uint8Array(44 + pcm.length); out.set(new Uint8Array(h.buffer)); out.set(pcm, 44); return out;
}
async function openAiSpeech(text, v) {
  const r = await host.fetch('https://api.openai.com/v1/audio/speech', { method: 'POST', binary: true, headers: { Authorization: 'Bearer ' + keyFor('openai'), 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'gpt-4o-mini-tts', input: text, voice: v.voice, instructions: v.style + ' Natural, never robotic.' + (pace(speed()) ? ' Pace: ' + pace(speed()) + '.' : ''), response_format: 'pcm' }), timeout: 60000 });
  if (!r.ok) throw new Error(`OpenAI voice: ${r.status || r.error}`);
  return { pcm: bytesOf(r.base64), rate: 24000 };   // raw 24 kHz 16-bit mono
}

// ------------------------------------------------------------------ listening

const RATE = 16000, FRAME = RATE * 20 / 1000;
/** The speech models that can write down what's said, fastest first: [id, key]. */
const ears = () => ['groq', 'openai', 'gemini'].map((id) => [id, readyKey(id)]).filter(([, k]) => k && k !== 'local');
// how this person talks, learned as they do (the same rules as the phone): their pace, how loud, words to expect
export const talkPace = () => Pace.parse(pref('hearPace', ''));
export const patient = () => pref('hearPatient', false);
export const setPatient = (on) => setPref('hearPatient', !!on);
export const endSilenceMs = () => talkPace().endSilenceMs(patient());
export const taughtWords = () => (Array.isArray(pref('hearWords', [])) ? pref('hearWords', []) : []);
export const setTaughtWords = (ws) => setPref('hearWords', [...new Map(ws.map((w) => String(w).trim()).filter(Boolean).map((w) => [w.toLowerCase(), w])).values()].slice(0, 60));
const voiceLevel = () => { const [l, n] = String(pref('hearLevel', '') || '').split(',').map(Number); return n >= 3 ? l : null; };
function learn(ep) {
  if (ep.pauses.length) setPref('hearPace', talkPace().learn(ep.pauses).save());
  if (ep.voiceLevel > 0) { const [l = 0, n = 0] = String(pref('hearLevel', '') || '').split(',').map(Number); setPref('hearLevel', `${l + (ep.voiceLevel - l) / (Math.min(n || 0, 20) + 1)},${(n || 0) + 1}`); }
}
/** What the last thing heard was like: its language and whether the speech model was unsure (Atlas's instructions take these in). */
export const lastHeard = { language: null, unsure: false, at: 0 };

/** Names and words Atlas should expect to hear, so they're spelled right. */
export function hint() {
  const words = new Set(['Atlas']);
  try {
    const name = (db.state.settings.name || '').trim(); if (name) words.add(name);
    taughtWords().forEach((w) => words.add(w));
    [...db.state.memories.slice(-40).map((m) => m.text), ...db.state.tasks.slice(-40).map((t) => t.title)].forEach((t) => names(t).forEach((w) => words.add(w)));
  } catch {}
  let out = ''; for (const w of words) { if (out.length + w.length + 2 > 500) break; out += (out ? ', ' : '') + w; } return out;
}

/**
 * Listens until you stop talking (or [stop]() is called). Calls onLevel(0..1) as you speak and onState('writing')
 * while a speech model writes it down, then resolves to the words heard ('' for silence). Throws with a sentence
 * for the user when it can't.
 */
export function listen({ onLevel = () => {}, onState = () => {}, onPause = () => {}, maxMs = 60000 } = {}) {
  // stopped before the microphone was even ready (Cancel straight away): don't start recording at all
  let stopped = false;
  let stopFn = () => { stopped = true; };
  const done = (async () => {
    if (!ears().length) throw new Error('To hear you, Atlas needs a Groq, Gemini or OpenAI key (Me → Brain).');
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } }); }
    catch (e) {
      // the truth for each: none plugged in, another app holding it, or Windows (or Atlas's setting) blocking it
      const n = e && e.name;
      if (n === 'NotFoundError' || n === 'OverconstrainedError') throw new Error('Atlas found no microphone on this PC. Plug one in (or turn it on), then try again.');
      if (n === 'NotReadableError' || n === 'AbortError') throw new Error("The microphone is busy: another app may be using it. Close that app's call or recording, then try again.");
      throw new Error("Atlas can't use the microphone. Check Windows Settings → Privacy → Microphone.");
    }
    if (stopped) { stream.getTracks().forEach((t) => t.stop()); return ''; }
    let ctx = null; const ep = new Endpointer({ maxMs, endSilenceMs: endSilenceMs(), minVoice: minVoiceFor(voiceLevel()) }); const pcm = []; let verdict = 'going'; let cued = false;
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
            if (ep.pausing !== cued) { cued = ep.pausing; onPause(cued); }   // a long pause: show "still listening"
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
    // the first speech model that manages it; when it's unsure, the next one listens too and the sure one is kept
    const h = hint(); let last = null, unsure = null, got = null;
    for (const [id, key] of ears()) {
      try {
        const t = id === 'gemini' ? { text: await transcribeGemini(key, b64, h), language: null, unsure: false } : heardFrom(await transcribeWhisper(id, key, b64, h));
        if (!t.unsure || !t.text) { got = !t.text && unsure ? unsure : t; break; }
        if (!unsure) { unsure = t; continue; }
        got = unsure; break;
      } catch (e) { last = e instanceof SyntaxError ? new Error("Couldn't make out the words (the speech service sent back something unreadable).") : e; }   // an HTML error page, not a parser's message
    }
    got = got || unsure;
    if (!got) throw last || new Error("Couldn't make out the words.");
    const text = fixHeard(got.text, ep.speechMs, h);
    if (text) { learn(ep); Object.assign(lastHeard, { language: got.language || (language().slice(0, 2).toLowerCase() || null), unsure: got.unsure, at: Date.now() }); }
    return text;
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
  const f = { model, response_format: model.startsWith('whisper-large') ? 'verbose_json' : 'json', ...(newer ? {} : { temperature: '0' }), ...(h ? { prompt: h } : {}) };
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
  if (groq) r = await call('whisper-large-v3');   // the full model: 10.3% word error rate against turbo's 12% (Groq's figures)
  else {
    for (let i = Math.min(openAiSkip, OPENAI_EARS.length - 1); ; i++) {
      r = await call(OPENAI_EARS[i]);
      if (r.ok || !(r.status === 404 || r.status === 400) || i >= OPENAI_EARS.length - 1) break;
      openAiSkip = i + 1;
    }
  }
  if (!r.ok) throw new Error(`Couldn't make out the words (${groq ? 'Groq' : 'OpenAI'} ${r.status || r.error}).`);
  return r.text;   // the whole answer: heardFrom reads the words, the language and how sure it was
}

/** Plays a voice right away (Me → Voice → Hear It). */
/**
 * Me → Voice → Test Each Voice: each natural voice says a line in turn (or says exactly why it can't), then Windows'
 * voice. Resolves to one line per voice: the way to see, in seconds, which voice really speaks.
 */
export async function testAll() {
  hush(); const mine = gen; state.busy = true;
  const lines = [];
  for (const e of ENGINES) {
    if (mine !== gen) break;
    const missing = e === 'atlas' ? (local.ready ? null : 'not set up or not running (Set Up below)') : (poolKeys(e).some((k) => !k.bad) ? null : 'no key added');
    if (missing) { lines.push(`${engineName(e)}: ${missing}`); continue; }
    try {
      const clip = await speechClip(e, `This is ${engineName(e)}, speaking for Atlas.`);
      state.engines[e] = `worked ${stamp()}`;
      const ok = await play(clip, mine, e);
      lines.push(`${engineName(e)}: ${ok ? 'works ✓ (' + current(e).label + ')' : "made the audio but it couldn't be played"}`);
    } catch (err) {
      const m = err.message || String(err); state.engines[e] = `failed ${stamp()}: ${m}`; state.lastProblem = `${engineName(e)}: ${m}`;
      lines.push(`${engineName(e)}: failed — ${m}`);
    }
  }
  lines.push("Windows' voice: always there (the fallback when the others fail)" + (pref('voiceNatural', true) ? '' : '. Natural Voice is off, so it speaks every reply.'));
  if (mine === gen) { state.busy = false; state.speaking = false; }
  return lines.join('\n');
}
export function preview(eng, id) { setPref('voice_' + eng, id); speak("Good day. I'm Atlas. This is how I sound. What can I do for you?"); }
