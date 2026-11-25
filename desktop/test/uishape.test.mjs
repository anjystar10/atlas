// Replies laid out as prose plus tidy rows: the same cases as the phone's UiShapeTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { row, split } from '../renderer/uishape.js';

test('labels and times become rows', () => {
  assert.deepEqual(row('Now: Code Atlas'), { key: 'Now', value: 'Code Atlas', tone: 'now' });
  assert.deepEqual(row('23:30 stretch'), { key: '23:30', value: 'stretch', tone: 'time' });
  assert.deepEqual(row('- **Blocked:** Fix bike'), { key: 'Blocked', value: 'Fix bike', tone: 'alert' });
  assert.equal(row('• 8.05 – Gym').key, '8:05');
  assert.equal(row('Done today: 1').tone, 'done');
});

test('sentences and links are not rows', () => {
  for (const l of ["Here's your day:", 'Added stretch for today at 23:30 and an alarm will ring then.', 'https://example.com/page',
    'This sentence has far too many words before its colon: really', '']) assert.equal(row(l), null, l);
});

test('a run of three becomes rows, the lead line kept', () => {
  const segs = split('One thing needs you, the rest is on track.\nNeeds your answer: Read 20 pages\nNow: Code Atlas\n23:23 Gym\n23:30 stretch\nBlocked: Fix bike\nDone today: 1');
  assert.equal(segs.length, 2);
  assert.deepEqual(segs[0], { text: 'One thing needs you, the rest is on track.' });
  assert.deepEqual(segs[1].rows.map((r) => r.key), ['Needs your answer', 'Now', '23:23', '23:30', 'Blocked', 'Done today']);
});

test('short runs and code stay as written', () => {
  const two = 'Sure.\nNow: Code Atlas\nNext: Gym\nThat\'s it.';
  assert.deepEqual(split(two), [{ text: two }]);
  const code = '```\na: 1\nb: 2\nc: 3\n```';
  assert.deepEqual(split(code), [{ text: code }]);
  const tail = split('Lead\nA: 1\nB: 2\nC: 3\n\nWant me to move anything?');
  assert.equal(tail.length, 3); assert.deepEqual(tail[2], { text: 'Want me to move anything?' });
});

test('prices, rates and version numbers are not times (the phone\'s UiShapeTest.pricesRatesAndVersionsAreNotTimes)', () => {
  for (const l of ['- 12.50 for a coffee', '- 1.0845 entry', '- 0.11.4: trading stop on both devices', '12:30:45 server restarted', '3.75 – sandwich', '25:00 nothing', '1.25 million people live there'])
    assert.equal(row(l), null, l);
  const prices = 'Prices today:\n- 2.50 for a coffee\n- 3.10 for tea\n- 1.20 for water';
  assert.deepEqual(split(prices), [{ text: prices }]);
  // real times still are
  assert.equal(row('7:45am Breakfast').key, '7:45am');
  assert.equal(row('3.30pm call').key, '3:30pm');
  assert.deepEqual(row('23:30: Stretch'), { key: '23:30', value: 'Stretch', tone: 'time' });
  assert.deepEqual(row('10:30-11:00 Meeting'), { key: '10:30', value: '11:00 Meeting', tone: 'time' });
});
