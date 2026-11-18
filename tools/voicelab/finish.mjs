// Applies Atlas's "AI system" finish (desktop/renderer/finish.js) to a 16-bit mono WAV at a SUBTLE level (half the
// app's default), for the voice lab:  node finish.mjs in.wav out.wav
import { readFileSync, writeFileSync } from 'node:fs';
import { process as finish, DEFAULT } from '../../desktop/renderer/finish.js';

const SUBTLE = { ...DEFAULT, exciter: DEFAULT.exciter / 2, chorus: DEFAULT.chorus / 2, room: DEFAULT.room / 2, presenceDb: 1.5, airDb: 1 };

const [, , src, dst] = process.argv;
const buf = readFileSync(src);
let off = 12, rate = 0, data = null, bits = 16, ch = 1;
while (off + 8 <= buf.length) {
  const id = buf.toString('ascii', off, off + 4), size = buf.readUInt32LE(off + 4);
  if (id === 'fmt ') { ch = buf.readUInt16LE(off + 10); rate = buf.readUInt32LE(off + 12); bits = buf.readUInt16LE(off + 22); }
  if (id === 'data') { data = buf.subarray(off + 8, off + 8 + size); break; }
  off += 8 + size + (size & 1);
}
if (!data || bits !== 16 || ch !== 1) throw new Error(`need 16-bit mono WAV, got ${bits}-bit ${ch}ch`);
const n = data.length >> 1, x = new Float64Array(n);
for (let i = 0; i < n; i++) x[i] = data.readInt16LE(2 * i) / 32768;
const y = finish(x, rate, SUBTLE);
const out = Buffer.alloc(44 + y.length * 2);
out.write('RIFF', 0); out.writeUInt32LE(36 + y.length * 2, 4); out.write('WAVE', 8); out.write('fmt ', 12);
out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(1, 22); out.writeUInt32LE(rate, 24);
out.writeUInt32LE(rate * 2, 28); out.writeUInt16LE(2, 32); out.writeUInt16LE(16, 34); out.write('data', 36); out.writeUInt32LE(y.length * 2, 40);
for (let i = 0; i < y.length; i++) out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(y[i] * 32767))), 44 + 2 * i);
writeFileSync(dst, out);
