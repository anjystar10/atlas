// Why the voice never changed (see Speech.VoiceCache on the phone): a voice remembered forever, a small speech limit on one
// key, and failures nobody saw. The same rules as the phone's VoiceFixTest.
import test from 'node:test';
import assert from 'node:assert/strict';
import { voiceRev, voiceEntry, voiceRead, VOICE_RETRY_MS, ttsKeyProblem, ttsRestMs, chunks, NATURAL_FIRST, NATURAL_REST, fallbackNote } from '../renderer/speech.js';

globalThis.window = globalThis.window || { atlasHost: { read: async () => null, write: async () => true } };
const voice = await import('../renderer/voice.js');
const rev = voiceRev('design one', 'gemini-3.8-flash-tts');

test('a remembered voice is used for the same revision and key', () => {
  assert.deepEqual(voiceRead(voiceEntry(rev, 'abc123', 'voice_x', 1000), rev, 'abc123', 5000), { use: 'voice_x' });
});
test('a new design (or revision) makes the voice afresh', () => {
  assert.deepEqual(voiceRead(voiceEntry(rev, 'abc123', 'voice_x', 1000), voiceRev('design two', 'gemini-3.8-flash-tts'), 'abc123', 5000), { miss: true });
});
test('the old forever format is never trusted again', () => {
  assert.deepEqual(voiceRead('abc123|voice_old', rev, 'abc123', 5000), { miss: true });
  assert.deepEqual(voiceRead('-', rev, 'abc123', 5000), { miss: true });
  assert.deepEqual(voiceRead(undefined, rev, 'abc123', 5000), { miss: true });
});
test('a refusal is only remembered for a few hours', () => {
  const e = voiceEntry(rev, 'abc123', null, 1000);
  assert.deepEqual(voiceRead(e, rev, 'abc123', 61_000), { refused: true });
  assert.deepEqual(voiceRead(e, rev, 'abc123', 1000 + VOICE_RETRY_MS + 1), { miss: true });
});
test('used-up limits and turned-down keys try the next key; a daily limit rests longer', () => {
  assert.ok(ttsKeyProblem(429) && ttsKeyProblem(403) && ttsKeyProblem(401));
  assert.ok(!ttsKeyProblem(400) && !ttsKeyProblem(500) && !ttsKeyProblem(0));
  assert.ok(ttsRestMs(429, 'GenerateRequestsPerDayPerProjectPerModel', 0) >= 3600_000);
  const m = ttsRestMs(429, 'per minute', 5000); assert.ok(m >= 30_000 && m <= 600_000);
  assert.equal(ttsRestMs(400, 'bad', 0), 0);
});
test('every Gemini key in the pool gets a turn, those resting from speech last, turned-down ones never', () => {
  const pool = [{ key: 'AIza-one-111111' }, { key: 'AIza-two-222222' }, { key: 'AIza-bad-333333', bad: true }, { key: 'AIza-one-111111' }];
  const rest = new Map([['111111', 10_000]]);
  assert.deepEqual(voice.ttsKeyOrder(pool, rest, 5000), ['AIza-two-222222', 'AIza-one-111111']);
  assert.deepEqual(voice.ttsKeyOrder(pool, rest, 20_000), ['AIza-one-111111', 'AIza-two-222222']);
});
test('natural voices use fewer requests per reply, and lose nothing', () => {
  const reply = Array.from({ length: 12 }, (_, i) => `This is sentence number ${i + 1} of a fairly ordinary spoken reply from Atlas.`).join(' ');
  const before = chunks(reply).length, now = chunks(reply, NATURAL_FIRST, NATURAL_REST);
  assert.ok(now.length < before, `was ${before}, now ${now.length}`);
  assert.ok(now[0].length <= NATURAL_FIRST);
  assert.equal(now.join('').replace(/ /g, ''), reply.replace(/ /g, ''));
});
test('the fallback note says why', () => {
  assert.match(fallbackNote('Gemini: 429'), /Gemini: 429/);
  assert.match(fallbackNote(null), /key/);
});
