// Accuracy checks: every feature area, with the exact answer each case must give.
// Shared cases live in tools/accuracy-fixtures.json (the phone's AccuracyTest.kt reads the same file);
// regenerate them with `python3 tools/make-accuracy-fixtures.py tools/accuracy-fixtures.json`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const FIX = JSON.parse(fs.readFileSync(path.join(here, '..', '..', 'tools', 'accuracy-fixtures.json'), 'utf8'));
const W = await import('../renderer/when.js');

test('accuracy · time: every shared "when" case resolves to the exact instant or the right refusal', () => {
  const failed = [];
  for (const c of FIX.when) {
    const r = W.resolveWhen(c.input, c.now, c.tz);
    if (c.error) {
      if (r.ok || !r.error.toLowerCase().includes(c.error.toLowerCase())) failed.push(`${c.input} @ ${c.nowLocal} ${c.tz}: expected refusal “${c.error}”, got ${JSON.stringify(r)}`);
    } else if (!r.ok || r.local !== c.local || r.ms !== c.ms) failed.push(`${c.input} @ ${c.nowLocal} ${c.tz}: expected ${c.local} (${c.ms}), got ${JSON.stringify(r)}`);
  }
  assert.deepEqual(failed, [], `${failed.length} of ${FIX.when.length} cases wrong:\n` + failed.join('\n'));
});

test('accuracy · time: clock words, repeats and durations', () => {
  for (const [input, want] of FIX.clock) {
    const got = W.parseClock(input);
    if (want === 'bad') assert.equal(got && got.bad, true, input); else assert.deepEqual(got, want, input);
  }
  for (const [input, want] of FIX.repeat) assert.equal(W.normRepeat(input), want, String(input));
  for (const [input, min, max, want] of FIX.minutes) {
    const got = W.normMinutes(input, min, max);
    if (want === 'NaN') assert.ok(Number.isNaN(got), String(input)); else assert.equal(got, want, String(input));
  }
});

test('accuracy · time: the read-back names the weekday, date and time that were stored', () => {
  const c = FIX.when.find((x) => x.input === 'tomorrow at 6pm');
  const r = W.resolveWhen(c.input, c.now, c.tz);
  assert.equal(r.long, 'Fri 13 Nov 2026, 18:00'); assert.equal(r.relative, 'tomorrow');
});
