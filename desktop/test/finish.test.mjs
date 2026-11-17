// The "AI system" finish (renderer/finish.js): the same checks as the phone's AudioFinishTest.
import test from 'node:test';
import assert from 'node:assert/strict';
import { process as finish, applyPcm, TAIL_S } from '../renderer/finish.js';

const fs = 24000;
const tone = (hz, amp = 0.2, secs = 1, dc = 0) => Float64Array.from({ length: secs * fs }, (_, i) => dc + hz.reduce((s, f) => s + amp * Math.sin(2 * Math.PI * f * i / fs), 0));
const speechLike = (secs = 2) => Float64Array.from({ length: secs * fs }, (_, i) => {
  const t = i / fs; if ((t % 0.25) >= 0.18) return 0;
  let s = 0; for (let h = 1; h <= 30; h++) s += 0.25 / h * Math.sin(2 * Math.PI * 120 * h * t); return s * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t));
});
function level(x, hz) {
  const from = x.length >> 2, to = (x.length * 3) >> 2, c = 2 * Math.cos(2 * Math.PI * hz / fs); let s1 = 0, s2 = 0;
  for (let i = from; i < to; i++) { const s = x[i] + c * s1 - s2; s2 = s1; s1 = s; }
  return Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (to - from);
}
const rms = (x, len = x.length) => { let s = 0; for (let i = 0; i < len; i++) s += x[i] * x[i]; return Math.sqrt(s / len); };
const db = (r) => 20 * Math.log10(r);

test('silence stays silent, with the room tail added', () => {
  const y = finish(new Float64Array(fs), fs);
  assert.ok(y.every((v) => v === 0)); assert.equal(y.length, fs + Math.floor(TAIL_S * fs));
});
test('presence is lifted against the mids', () => {
  const x = tone([1000, 3200], 0.05), y = finish(x, fs);
  const lift = db(level(y, 3200) / level(y, 1000)) - db(level(x, 3200) / level(x, 1000));
  assert.ok(lift >= 1.5 && lift <= 4, `lift ${lift}`);
});
test('rumble and offset go, the voice stays', () => {
  const x = tone([40, 1000], 0.1, 1, 0.1), y = finish(x, fs);
  assert.ok(db(level(y, 40) / level(y, 1000)) - db(level(x, 40) / level(x, 1000)) < -6);
  let m = 0; for (let i = fs / 2; i < fs; i++) m += y[i]; assert.ok(Math.abs(m / (fs / 2)) < 0.005);
});
test('same loudness, never clips', () => {
  for (const g of [0.2, 1, 3]) {
    const x = speechLike().map((v) => Math.max(-1, Math.min(1, v * g))), y = finish(x, fs);
    assert.ok(y.every((v) => Math.abs(v) <= 0.89 + 1e-9));
    const ch = db(rms(y, x.length) / rms(x));
    if (g <= 1) assert.ok(Math.abs(ch) < 1.5, `changed ${ch} dB`); else assert.ok(ch < 0.5);
  }
});
test('the words stay on top: a quiet tail, clear gaps, the plain voice dominant', () => {
  const x = speechLike(1), y = finish(x, fs);
  const tail = y.subarray(x.length + Math.floor(0.05 * fs));
  assert.ok(db(rms(tail) / rms(y, x.length)) < -25);
  const plain = finish(x, fs, { presenceDb: 2.5, airDb: 1.5, boxDb: -1.5, exciter: 0, chorus: 0, room: 0, ratio: 2.5, thresholdDb: -20 });
  let xy = 0, xx = 0, yy = 0; for (let i = 0; i < x.length; i++) { xy += plain[i] * y[i]; xx += plain[i] ** 2; yy += y[i] ** 2; }
  assert.ok(xy / Math.sqrt(xx * yy) > 0.95);
});
test('PCM in, PCM out', () => {
  const x = speechLike(0.5); const b = new Uint8Array(x.length * 2); const dv = new DataView(b.buffer);
  x.forEach((v, i) => dv.setInt16(2 * i, Math.round(v * 32767), true));
  const out = applyPcm(b, fs); assert.equal(out.length, b.length + 2 * Math.floor(TAIL_S * fs)); assert.ok(out.some((v) => v !== 0));
});

// hearing everyone well: the same rules as the phone's Speech.kt (SpeechTest)
import { Endpointer, Pace, heardFrom, heardNote, minVoiceFor, manner } from '../renderer/speech.js';
const feed = (ep, steps) => { let v = 'going'; for (const [lvl, ms] of steps) for (let i = 0; i < ms / 20; i++) if (v === 'going') v = ep.feed(lvl); return v; };
test('a stammer or false start gets more time, and pauses are kept', () => {
  const ep = new Endpointer();
  assert.equal(feed(ep, [[0.003, 300], [0.08, 500], [0.003, 1500]]), 'going'); assert.ok(ep.pausing);
  assert.equal(feed(ep, [[0.08, 1500]]), 'going'); assert.equal(feed(ep, [[0.003, 1400]]), 'done');
  assert.deepEqual(ep.pauses, [1500]);
});
test('pace is learned like on the phone', () => {
  let p = new Pace(); for (let i = 0; i < 5; i++) p = p.learn([300, 400, 500, 1200]);
  assert.equal(p.p90, 1200); assert.equal(p.endSilenceMs(), 2100); assert.equal(p.endSilenceMs(true), 3360);
  assert.equal(new Pace().endSilenceMs(), 1300); assert.equal(Pace.parse(p.save()).save(), p.save());
});
test('a soft voice is heard once learned', () => {
  assert.equal(feed(new Endpointer(), [[0.002, 300], [0.01, 1500], [0.002, 6000]]), 'nothing');
  assert.equal(feed(new Endpointer({ minVoice: minVoiceFor(0.03) }), [[0.002, 300], [0.01, 1500], [0.002, 1500]]), 'done');
});
test('transcript language and confidence; the note and manner', () => {
  const m = heardFrom({ text: 'call tundy', language: 'english', segments: [{ start: 0, end: 1, avg_logprob: -1.1, compression_ratio: 1, no_speech_prob: 0.1 }] });
  assert.equal(m.language, 'en'); assert.ok(m.unsure);
  assert.equal(heardFrom({ text: 'Bonjour', languages: [{ code: 'fr' }] }).language, 'fr');
  assert.equal(heardNote('en', false), ''); assert.ok(heardNote('yo', true).includes('"yo"'));
  assert.ok(manner(true).includes('"sir" now and then') && !manner(false).includes('"sir"'));
});
