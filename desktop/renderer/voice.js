// Atlas's ears and voice on the PC.
// Listening: records until you stop talking, then a Gemini (or OpenAI) key turns it into words.
// Speaking: the same natural voices as on the phone (Gemini's or OpenAI's), else the best English voice Windows has.
import { keyFor, readyKey, byId, modelOf } from './brain.js';
import { pref, setPref } from './store.js';
import { plainSpoken } from './plain.js';
const host = window.atlasHost;

const ATLAS_STYLE = 'calm, composed and refined; crisp and articulate with a British accent; quietly warm with a touch of dry wit; unhurried, like a trusted AI butler';
export const GEMINI_VOICES = [
  { id: 'atlas', label: 'Atlas', about: 'British, calm, refined', voice: 'Charon', style: ATLAS_STYLE },
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
  { id: 'atlas', label: 'Atlas', about: 'British, calm, refined', voice: 'fable', style: 'Speak with a refined British accent. ' + ATLAS_STYLE + '.' },
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
export const state = { speaking: false, lastSpoke: null, lastProblem: null };

let audio = null, gen = 0;
export function hush() { gen++; state.speaking = false; if (audio) { try { audio.pause(); } catch {} audio = null; } try { speechSynthesis.cancel(); } catch {} }

export async function speak(text, onDone) {
  hush(); const mine = gen;
  const say = plainSpoken(text); if (!say) { onDone && onDone(); return; }
  const eng = engine();
  try {
    if (!eng) throw new Error('no natural voice');
    const v = current(eng);
    const clip = eng === 'gemini' ? await geminiSpeech(say, v) : await openAiSpeech(say, v);
    if (mine !== gen) return;
    state.lastProblem = null; state.lastSpoke = `${v.label} (${eng === 'gemini' ? 'Gemini' : 'OpenAI'})`;
    audio = new Audio(clip); state.speaking = true;
    audio.onended = audio.onerror = () => { state.speaking = false; if (mine === gen) onDone && onDone(); };
    await audio.play();
  } catch (e) {
    if (mine !== gen) return;
    if (eng) state.lastProblem = e.message || String(e);
    systemSpeak(say, onDone, mine);
  }
}

function systemSpeak(say, onDone, mine) {
  const u = new SpeechSynthesisUtterance(say);
  const vs = speechSynthesis.getVoices().filter((v) => v.lang.startsWith('en'));
  // a British voice for Atlas, the "natural" ones first (Windows names them that way)
  const best = vs.sort((a, b) => score(b) - score(a))[0];
  function score(v) { return (v.lang === 'en-GB' ? 4 : 0) + (/natural|neural|online/i.test(v.name) ? 3 : 0) + (/ryan|george|thomas|male/i.test(v.name) ? 1 : 0); }
  if (best) { u.voice = best; state.lastSpoke = `Windows voice (${best.name})`; }
  u.rate = 1; u.onstart = () => { state.speaking = true; };
  u.onend = u.onerror = () => { state.speaking = false; if (mine === gen) onDone && onDone(); };
  speechSynthesis.speak(u);
}

async function geminiSpeech(text, v) {
  const key = keyFor('gemini');
  const body = { model: 'gemini-3.8-flash-tts', input: [{ type: 'user_input', content: [{ type: 'text', text, annotations: [{ type: 'speech_metadata', style: v.style }] }] }],
    response_format: { type: 'audio' }, generation_config: { speech_config: [{ voice: v.voice }] } };
  const r = await host.fetch('https://generativelanguage.googleapis.com/v1beta/interactions', { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeout: 60000 });
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
    body: JSON.stringify({ model: 'gpt-4o-mini-tts', input: text, voice: v.voice, instructions: v.style + ' Natural, never robotic.', response_format: 'mp3' }), timeout: 60000 });
  if (!r.ok) throw new Error(`OpenAI voice: ${r.status || r.error}`);
  return 'data:audio/mpeg;base64,' + r.base64;
}

// ------------------------------------------------------------------ listening

/**
 * Listens until you stop talking (or [stop]() is called). Calls onLevel(0..1) as you speak, then resolves to the
 * words heard ('' for silence). Throws with a sentence for the user when it can't.
 */
export function listen({ onLevel = () => {}, maxMs = 30000 } = {}) {
  let stopFn = () => {};
  const done = (async () => {
    const gk = keyFor('gemini'), ok = keyFor('openai');
    if (!gk && !ok) throw new Error('To hear you, Atlas needs a Gemini or OpenAI key (Me → Brain).');
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }); }
    catch { throw new Error("Atlas can't use the microphone. Check Windows Settings → Privacy → Microphone."); }
    const ctx = new AudioContext(); const src = ctx.createMediaStreamSource(stream); const an = ctx.createAnalyser(); an.fftSize = 1024; src.connect(an);
    const rec = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' }); const chunks = [];
    rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    const buf = new Float32Array(an.fftSize); let heard = false, quietSince = 0; const t0 = performance.now();
    await new Promise((resolve) => {
      let raf = 0;
      const finish = () => { cancelAnimationFrame(raf); if (rec.state !== 'inactive') rec.stop(); };
      stopFn = finish; rec.onstop = resolve; rec.start(200);
      const tick = () => {
        an.getFloatTimeDomainData(buf); let sum = 0; for (const v of buf) sum += v * v; const rms = Math.sqrt(sum / buf.length);
        const level = Math.min(1, rms * 9); onLevel(level);
        const now = performance.now();
        if (level > 0.12) { heard = true; quietSince = now; }
        // stop after 1.4 s of quiet once you've spoken, 6 s if you never did, or at the limit
        if ((heard && now - quietSince > 1400) || (!heard && now - t0 > 6000) || now - t0 > maxMs) return finish();
        raf = requestAnimationFrame(tick);
      };
      tick();
    });
    stream.getTracks().forEach((t) => t.stop()); ctx.close(); onLevel(0);
    if (!heard) return '';
    const blob = new Blob(chunks, { type: 'audio/webm' });
    const b64 = await new Promise((r) => { const fr = new FileReader(); fr.onload = () => r(String(fr.result).split(',')[1]); fr.readAsDataURL(blob); });
    return gk ? transcribeGemini(gk, b64) : transcribeOpenAi(ok, b64);
  })();
  return { done, stop: () => stopFn() };
}

async function transcribeGemini(key, b64) {
  const model = modelOf(byId('gemini'));
  const r = await host.fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, { method: 'POST', headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' }, timeout: 60000,
    body: JSON.stringify({ contents: [{ role: 'user', parts: [{ inline_data: { mime_type: 'audio/webm', data: b64 } }, { text: 'Write down exactly what is said in this recording, in the language spoken. Output only the words, nothing else. If nothing is said, output nothing.' }] }],
      generationConfig: model.startsWith('gemini-3') ? { thinkingConfig: { thinkingLevel: 'low' } } : undefined }) });
  if (!r.ok) throw new Error(`Couldn't make out the words (Gemini ${r.status || r.error}).`);
  const c = (JSON.parse(r.text).candidates || [])[0];
  return ((c && c.content && c.content.parts) || []).map((p) => (p.thought ? '' : p.text || '')).join('').trim();
}
async function transcribeOpenAi(key, b64) {
  const r = await host.fetch('https://api.openai.com/v1/audio/transcriptions', { method: 'POST', headers: { Authorization: 'Bearer ' + key }, timeout: 60000,
    form: { model: 'gpt-4o-mini-transcribe', file: { base64: b64, type: 'audio/webm', name: 'speech.webm' } } });
  if (!r.ok) throw new Error(`Couldn't make out the words (OpenAI ${r.status || r.error}).`);
  return (JSON.parse(r.text).text || '').trim();
}

/** Plays a voice right away (Me → Voice → Hear It). */
export function preview(eng, id) { setPref('voice_' + eng, id); speak("Hello, I'm Atlas. Here's how I sound. What can I help you with today?"); }
