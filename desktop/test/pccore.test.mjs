// Scan 3 (pc-core): the chat loop's Stop, the key pool's passing failures, attachments kept through a long task, and
// the reminder engine's missed reminders. A scripted OpenAI-style model behind a fake network, like aware.test.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';

const requests = [];
let script = () => ({ text: 'ok' });
const pcCalls = [];
globalThis.window = { atlasHost: {
  read: async () => null, write: async () => true,
  pc: async (name, a) => { pcCalls.push([name, a]); return name === 'look_at_screen' ? { out: 'The screen.', image: { mime: 'image/jpeg', base64: 'U0NSRUVO' } } : { out: 'Done.', step: name }; },
  fetch: async (url, opts) => {
    const body = JSON.parse(opts.body); requests.push(body);
    const r = script(body);
    const calls = r.calls || (r.call ? [{ name: r.call, args: r.args }] : null);
    const msg = calls ? { role: 'assistant', content: null, tool_calls: calls.map((c, i) => ({ id: `c${requests.length}_${i}`, type: 'function', function: { name: c.name, arguments: JSON.stringify(c.args || {}) } })) } : { role: 'assistant', content: r.text };
    return { ok: true, status: 200, text: JSON.stringify({ choices: [{ message: msg }] }) };
  },
} };

const brain = await import('../renderer/brain.js');
const store = await import('../renderer/store.js');
const { db } = store;
await store.load();
db.prefs.keys = { openai: [{ key: 'sk-test-0123456789', restUntil: 0, bad: false, fails: 0 }] };
db.prefs.provider = 'openai';

test('Stop: the tool calls still waiting in the same turn do not run', async () => {
  requests.length = 0; pcCalls.length = 0;
  const before = db.state.memories.length;
  // the model asks for two things at once; the user presses Stop while the first one runs
  brain.ui.onPhone = async () => { brain.stop(); return 'Sent to your phone.'; };
  script = (b) => (b.messages.at(-1).role === 'tool' ? { text: 'All done.' } : { calls: [{ name: 'on_phone', args: { task: 'open the camera' } }, { name: 'remember', args: { text: 'My locker code is 4321' } }, { name: 'type_text', args: { text: 'hello' } }] });
  const r = await brain.send('open the camera on my phone and remember my locker code');
  assert.equal(r.source, 'Stopped');
  assert.equal(db.state.memories.length, before, 'remember ran after Stop');
  assert.deepEqual(pcCalls, [], 'a PC action ran after Stop');
  assert.equal(brain.convo.busy, false);
});

test('Claude overloaded (HTTP 529) is a passing failure: retried, then another key or service', () => {
  assert.equal(brain.cause({ code: 529 }), 'passing');
  assert.equal(brain.cause({ code: 503 }), 'passing');
  assert.equal(brain.cause({ code: 404 }), 'other');
});

test('a picture the user attached stays in the request through a long task with several screenshots', async () => {
  requests.length = 0; pcCalls.length = 0;
  // an earlier exchange, so this message isn't the first in the request
  db.chat.push({ id: 'h1', user: true, text: 'hi', source: 'You', at: 1, steps: [] }, { id: 'h2', user: false, text: 'Hello!', source: 'OpenAI', at: 2, steps: [] });
  let looks = 0;
  script = (b) => (looks++ < 4 ? { call: 'look_at_screen' } : { text: 'Filled in.' });
  const r = await brain.send('fill in the form on screen with the details in this picture', { attachments: [{ kind: 'image', mime: 'image/jpeg', name: 'card.jpg', base64: 'UElDVFVSRQ==', size: 8 }] });
  assert.equal(r.text, 'Filled in.');
  const last = requests.at(-1).messages;
  const mine = last.find((m) => m.role === 'user' && Array.isArray(m.content) && m.content.some((p) => p.type === 'text' && /details in this picture/.test(p.text)));
  assert.ok(mine, 'the user message is in the request');
  assert.ok(mine.content.some((p) => p.type === 'image_url' && p.image_url.url.includes('UElDVFVSRQ==')), 'the attached picture was dropped from the request');
  // old screenshots still go (only the latest two stay)
  const shots = last.filter((m) => m.role === 'user' && Array.isArray(m.content) && m.content.some((p) => p.type === 'image_url' && p.image_url.url.includes('U0NSRUVO')));
  assert.equal(shots.length, 2);
});

test('a reminder missed while the PC was off (over 6 h) is saved as rung, and the Log hears of it', async () => {
  const now = Date.now();
  const t = store.createTask('Missed one', { startAt: now - 8 * 3600000 }, now - 9 * 3600000);
  let heard = 0; const off = store.onChange((w) => { if (w === 'state') heard++; });
  const due = store.takeDue(now);
  off();
  assert.equal(due.length, 0, 'a reminder 8 h late does not ring');
  assert.ok(store.findTask(t.id).remindedAt != null);
  assert.ok(heard > 0, 'the state was not saved after marking it missed');
  // and it isn't logged as missed a second time
  const notes = () => db.state.log.filter((l) => l.taskId === t.id && l.type === 'note').length;
  store.takeDue(now + 60000);
  assert.equal(notes(), 1);
});
