// The "AI system" finish on Atlas's spoken replies, the same arithmetic as the phone's AudioFinish.kt: a little
// presence and air, a light digital sheen (a touch of harmonic exciter and a faint short chorus), a small, short room
// and gentle compression. Subtle on purpose: words first. Pure logic on 16-bit mono PCM, so it can be tested.

export const DEFAULT = { presenceDb: 2.5, airDb: 1.5, boxDb: -1.5, exciter: 0.07, chorus: 0.10, room: 0.085, ratio: 2.5, thresholdDb: -20 };
export const TAIL_S = 0.22;

class Biquad {
  constructor(b0, b1, b2, a0, a1, a2) { Object.assign(this, { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 }); }
  run(x) {
    const y = new Float64Array(x.length); let x1 = 0, x2 = 0, y1 = 0, y2 = 0; const { b0, b1, b2, a1, a2 } = this;
    for (let i = 0; i < x.length; i++) { const v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v; }
    return y;
  }
  static highPass(fs, f, q) { const w = 2 * Math.PI * f / fs, al = Math.sin(w) / (2 * q), c = Math.cos(w); return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); }
  static peak(fs, f, q, db) { const A = 10 ** (db / 40), w = 2 * Math.PI * f / fs, al = Math.sin(w) / (2 * q), c = Math.cos(w); return new Biquad(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A); }
  static highShelf(fs, f, db) {
    const A = 10 ** (db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / 2 * Math.SQRT2, sa = 2 * Math.sqrt(A) * al;
    return new Biquad(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa), (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
  }
}

const rms = (v, len = v.length) => { let s = 0; for (let i = 0; i < len; i++) s += v[i] * v[i]; return Math.sqrt(s / Math.max(len, 1)); };

function compress(x, fs, thresholdDb, ratio) {
  const att = Math.exp(-1 / (0.004 * fs)), rel = Math.exp(-1 / (0.09 * fs)), knee = 6; let env = 0; const out = new Float64Array(x.length);
  for (let i = 0; i < x.length; i++) {
    const lvl = Math.abs(x[i]); env = lvl > env ? att * env + (1 - att) * lvl : rel * env + (1 - rel) * lvl;
    const over = 20 * Math.log10(Math.max(env, 1e-9)) - thresholdDb;
    const cut = over <= -knee / 2 ? 0 : over >= knee / 2 ? over * (1 - 1 / ratio) : (1 - 1 / ratio) * (over + knee / 2) ** 2 / (2 * knee);
    out[i] = x[i] * 10 ** (-cut / 20);
  }
  return out;
}

function room(x, fs) {
  const pre = Math.floor(0.008 * fs); const sum = new Float64Array(x.length);
  for (const d of [0.0231, 0.0267, 0.0299, 0.0323].map((s) => Math.floor(s * fs))) {
    const buf = new Float64Array(d); let idx = 0, lp = 0;
    for (let i = 0; i < x.length; i++) {
      const inp = i >= pre ? x[i - pre] : 0, o = buf[idx];
      lp = o * (1 - 0.35) + lp * 0.35; buf[idx] = inp + lp * 0.62; idx = (idx + 1) % d; sum[i] += o * 0.25;
    }
  }
  let y = sum;
  for (const [s, g] of [[0.005, 0.5], [0.0017, 0.5]]) {
    const d = Math.max(1, Math.floor(s * fs)), buf = new Float64Array(d), o = new Float64Array(y.length); let idx = 0;
    for (let i = 0; i < y.length; i++) { const b = buf[idx], v = y[i] + b * g; o[i] = b - v * g; buf[idx] = v; idx = (idx + 1) % d; }
    y = o;
  }
  return y;
}

function limit(y, fs, ceiling) {
  const rel = Math.exp(-1 / (0.06 * fs)), ahead = Math.max(1, Math.floor(0.002 * fs));
  const need = new Float64Array(y.length); for (let i = 0; i < y.length; i++) { const p = Math.abs(y[i]); need[i] = p > ceiling ? ceiling / p : 1; }
  const minAhead = new Float64Array(y.length); const dq = [];
  for (let i = y.length - 1; i >= 0; i--) {
    while (dq.length && dq[0] > i + ahead) dq.shift();
    while (dq.length && need[dq[dq.length - 1]] >= need[i]) dq.pop();
    dq.push(i); minAhead[i] = need[dq[0]];
  }
  let gain = 1;
  for (let i = 0; i < y.length; i++) {
    gain = minAhead[i] < gain ? minAhead[i] : rel * gain + (1 - rel) * Math.min(1, minAhead[i]);
    y[i] *= gain; if (y[i] > ceiling) y[i] = ceiling; else if (y[i] < -ceiling) y[i] = -ceiling;
  }
}

/** The finish on samples in -1..1 at [rate]; returns a slightly longer array (the room's tail). */
export function process(input, rate, a = DEFAULT) {
  const fs = rate, n = input.length, tail = Math.floor(TAIL_S * fs);
  const x = new Float64Array(n + tail); x.set(input);
  const inRms = rms(input); if (inRms < 1e-6) return x;
  let y = Biquad.highPass(fs, 80, 0.707).run(x);
  y = Biquad.peak(fs, 300, 1.0, a.boxDb).run(y);
  y = Biquad.peak(fs, 3200, 0.9, a.presenceDb).run(y);
  if (8000 < 0.45 * fs) y = Biquad.highShelf(fs, 8000, a.airDb).run(y);
  if (a.exciter > 0) {
    const band = Biquad.highPass(fs, 2500, 0.707).run(y); for (let i = 0; i < band.length; i++) band[i] = Math.tanh(4 * band[i]) / 4;
    const harm = Biquad.highPass(fs, 4000, 0.707).run(band); for (let i = 0; i < y.length; i++) y[i] += a.exciter * harm[i];
  }
  if (a.chorus > 0) {
    const src = y.slice(), base = 0.007 * fs, depth = 0.00035 * fs;
    for (let i = 0; i < y.length; i++) {
      const p = i - (base + depth * Math.sin(2 * Math.PI * 0.4 * i / fs)); if (p < 0) continue;
      const k = Math.floor(p), f = p - k; y[i] += a.chorus * (src[k] * (1 - f) + src[Math.min(k + 1, src.length - 1)] * f);
    }
  }
  y = compress(y, fs, a.thresholdDb, a.ratio);
  if (a.room > 0) { const wet = room(y, fs); for (let i = 0; i < y.length; i++) y[i] += a.room * wet[i]; }
  const g = inRms / Math.max(rms(y, n), 1e-9); for (let i = 0; i < y.length; i++) y[i] *= g;
  limit(y, fs, 0.89);
  return y;
}

/** The finish on 16-bit little-endian mono PCM bytes; returns new PCM bytes. */
export function applyPcm(bytes, rate, a = DEFAULT) {
  const n = bytes.length >> 1, dv = new DataView(bytes.buffer, bytes.byteOffset, n * 2), x = new Float64Array(n);
  for (let i = 0; i < n; i++) x[i] = dv.getInt16(2 * i, true) / 32768;
  const y = process(x, rate, a); const out = new Uint8Array(y.length * 2); const ov = new DataView(out.buffer);
  for (let i = 0; i < y.length; i++) ov.setInt16(2 * i, Math.max(-32768, Math.min(32767, Math.round(y[i] * 32767))), true);
  return out;
}
