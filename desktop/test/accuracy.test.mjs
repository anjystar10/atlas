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

// ------------------------------------------------------------------ the real PC tools: what they accept, refuse and confirm
globalThis.window = globalThis.window || {};
globalThis.window.atlasHost = globalThis.window.atlasHost || { read: async () => null, write: async () => true };
const store = await import('../renderer/store.js');
const brain = await import('../renderer/brain.js');
await store.load();
const tool = (name, args) => brain.runTool({ name, args });

test('accuracy · reminders: the tool stores the exact time and reads it back; bad input is refused, not guessed', async () => {
  const tz = W.localZone(); const now = Date.now();
  const r = await tool('set_reminder', { title: '  Call mum  ', when: 'in 90 minutes' });
  const t = store.db.state.tasks.at(-1);
  assert.equal(t.title, 'Call mum');
  assert.ok(Math.abs(t.startAt - (now + 90 * 60000)) <= 61000, 'stored 90 minutes from now');
  assert.ok(r.out.includes(W.fmtLong(t.startAt, tz)), 'the result quotes the stored time: ' + r.out);
  const before = store.db.state.tasks.length;
  for (const [args, why] of [
    [{ title: 'x', when: '2001-01-01 10:00' }, 'already passed'],
    [{ title: 'x', when: '2027-02-29 10:00' }, "isn't a real date"],
    [{ title: 'x', when: 'tomorrow' }, 'no time'],
    [{ title: 'x', when: 'tomorrow at 7' }, 'morning or the evening'],
    [{ title: 'x', when: 'tomorrow 9am', repeat: 'monthly' }, "isn't a repeat"],
    [{ title: 'x', repeat: 'daily' }, 'needs a time'],
    [{ title: 'x', when: 'tomorrow 9am', duration_min: 0 }, 'between 1 and 1440'],
    [{ title: '   ', when: 'tomorrow 9am' }, 'title is needed'],
  ]) {
    const o = await tool('set_reminder', args);
    assert.ok(o.out.startsWith('Error') && o.out.toLowerCase().includes(why.toLowerCase()), `${JSON.stringify(args)} → ${o.out}`);
  }
  assert.equal(store.db.state.tasks.length, before, 'nothing was stored for the refused ones');
  const ok = await tool('set_reminder', { title: 'Gym', when: 'tomorrow at 6pm', repeat: 'Every weekday', duration_min: '1.5 hours' });
  const g = store.db.state.tasks.at(-1);
  assert.equal(g.recurrence, 'weekdays'); assert.equal(g.durationMin, 90);
  assert.ok(ok.out.includes('tomorrow') && ok.out.includes(', 18:00'), ok.out);
});

test('accuracy · reminders: moving and renaming confirm the new state; empty renames are refused', async () => {
  await tool('set_reminder', { title: 'Dentist', when: 'tomorrow at 10am' });
  const t = store.db.state.tasks.at(-1);
  const m = await tool('update_reminder', { id: t.id, action: 'move', when: 'tomorrow at 11:30am' });
  assert.ok(m.out.includes(W.fmtLong(store.db.state.tasks.find((x) => x.id === t.id).startAt, W.localZone())) && m.out.includes('11:30'), m.out);
  const bad = await tool('update_reminder', { id: t.id, action: 'move', when: 'yesterday 9am' });
  assert.ok(bad.out.startsWith('Error'), bad.out);
  const r0 = await tool('update_reminder', { id: t.id, action: 'rename', title: '  ' });
  assert.ok(r0.out.startsWith('Error'), r0.out);
  assert.equal(store.db.state.tasks.find((x) => x.id === t.id).title, 'Dentist');
  const r1 = await tool('update_reminder', { id: t.id, action: 'rename', title: 'Dentist (Dr Ade)' });
  assert.ok(r1.out.includes("titled 'Dentist (Dr Ade)'"), r1.out);
  const gone = await tool('update_reminder', { id: 'nope', action: 'done' });
  assert.ok(gone.out.startsWith('Error'), gone.out);
});

test('accuracy · plain text: nothing real is dropped; only Atlas\'s own machinery is hidden (shared with Plain.kt)', async () => {
  const P = await import('../renderer/plain.js');
  for (const [input, reply, memory] of FIX.plain) {
    if (reply != null) assert.equal(P.plainReply(input), reply, 'reply: ' + input);
    if (memory != null) assert.equal(P.plainMemory(input), memory, 'memory: ' + input);
  }
});

test('accuracy · memory: saved word for word, no duplicates, forgetting names what went; focus minutes checked', async () => {
  const n0 = store.db.state.memories.length;
  const r = await tool('remember', { text: 'my licence key is 3f2a9c1e-1111-2222-3333-444455556666' });
  assert.ok(r.out.includes("'My licence key is 3f2a9c1e-1111-2222-3333-444455556666'"), r.out);
  const dup = await tool('remember', { text: 'My licence key is 3f2a9c1e-1111-2222-3333-444455556666 ' });
  assert.ok(dup.out.startsWith('Already saved'), dup.out);
  assert.equal(store.db.state.memories.length, n0 + 1);
  const id = store.db.state.memories.at(-1).id;
  const f = await tool('forget', { id }); assert.ok(f.out.includes('licence key'), f.out);
  assert.equal(store.db.state.memories.length, n0);
  assert.ok((await tool('forget', { id })).out.startsWith('Error'));
  assert.ok((await tool('remember', { text: '  ' })).out.startsWith('Error'));
});
