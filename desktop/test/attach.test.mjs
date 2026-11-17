// Attachments: the PC's routing agrees with tools/attach-fixtures.json (the phone's AttachRulesTest checks the same file).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import * as R from '../renderer/attachrules.js';

const fx = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'tools/attach-fixtures.json'), 'utf8'));

test('every kind of file is recognised', () => { for (const [name, mime, want] of fx.kinds) assert.equal(R.kind(name, mime), want, `${name} ${mime}`); });
test('each kind and size takes the right route', () => { for (const [k, size, style, chars, want] of fx.routes) assert.equal(R.route(k, size, style, chars), want, `${k} ${size} ${style} ${chars}`); });
test('nothing is turned away: every route has a reading, and the honest note is there when Atlas reads only part', () => {
  for (const [k, size, style, chars] of fx.routes) {
    const r = R.route(k, size, style, chars);
    assert.ok(['native', 'upload', 'frames', 'text', 'chunks', 'listing', 'describe'].includes(r));
    const n = R.note(k, r, style === 'gemini' ? 'Gemini' : 'Claude');
    assert.equal(n === '', r === 'native' || r === 'text', `${k} ${r}`);
  }
});
test('long text is cut into parts at line breaks, and covers all of it', () => {
  const text = Array.from({ length: 500 }, (_, i) => `line ${i} ` + 'x'.repeat(i % 37)).join('\n');
  const starts = R.chunkStarts(text, 1000);
  assert.equal(starts[0], 0);
  for (let i = 1; i < starts.length; i++) { assert.ok(starts[i] > starts[i - 1]); assert.ok(starts[i] - starts[i - 1] <= 1000); assert.equal(text[starts[i] - 1], '\n'); }
  assert.ok(text.length - starts[starts.length - 1] <= 1000);
  assert.deepEqual(R.chunkStarts('short', 1000), [0]);
});
test('strings finds the readable text inside binary bytes', () => {
  const b = Uint8Array.from([0, 1, 2, ...Buffer.from('Hello world'), 0, 255, ...Buffer.from('abc'), 7, ...Buffer.from('Version 2.1')]);
  assert.equal(R.strings(b), 'Hello world Version 2.1');
  assert.equal(R.sizeText(1536), '1 KB'); assert.equal(R.sizeText(3 * R.MB + R.MB / 2), '3.5 MB');
});
