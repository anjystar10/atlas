// Bugs found in the bots engine (bots.js), each proven with a scripted model: the real engine, fake AI and tools.
import { test } from 'node:test';
import assert from 'node:assert/strict';
const B = await import('../renderer/bots.js');

/** [model(ctx)] → {text, calls}; [ex]: the extras (botx.js) or null; [countdown]: deps.countdown. */
function world(model, { ex = null, countdown = null, approvalFor = null, runTool = null } = {}) {
  const w = { saved: null, ran: [], posts: [], notes: [], finals: [] };
  let clock = 1000;
  w.bots = B.createBots({
    load: async () => w.saved, save: async (t) => { w.saved = t; },
    provider: () => ({ id: 'gemini', name: 'Gemini' }),
    session: (p, brief, tools) => { let n = 0; return { key: 'k', results: () => {}, call: async () => { await new Promise((r) => setTimeout(r, 2)); return model({ first: brief.split('\n')[0], brief, n: n++, tools: tools.map((t) => t.name), w }); } }; },
    callWithKeys: async (p, sess, c) => c(),
    isSwitch: () => false, isRetryable: () => false,
    tools: () => [{ name: 'web_search' }, { name: 'send_email' }],
    extraApproval: approvalFor || ((name) => (name === 'send_email' ? 'Send an email to Bob' : null)),
    runTool: runTool || (async (c, o) => { w.ran.push([c.name, !!(o && o.approved)]); return { out: c.name + ' result', step: 'Did ' + c.name }; }),
    systemPrompt: () => 'sys',
    notify: (t) => w.notes.push(t), post: (m) => w.posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => (clock += 10), uid: () => 'j' + Math.random().toString(36).slice(2, 8),
    setTimer: (f, ms) => (ms <= 0 ? setTimeout(f, 0) : 0), clearTimer: () => {},
    onFinal: (j) => w.finals.push(j.id),
    extras: ex, countdown,
  });
  return w;
}
const call = (name, args = {}) => ({ name, args, id: name + Math.random() });
const turn = (...calls) => ({ text: '', calls });
const settle = async (w, rounds = 40) => { for (let i = 0; i < rounds; i++) { await new Promise((r) => setTimeout(r, 4)); await w.bots.idle(); } };
const undoExtras = () => ({
  data: { specialists: [], skills: [] }, allows: () => true, categoryOf: () => 'message', label: (x) => x,
  autonomy: { kindFor: () => 'message_known', decide: () => ({ act: 'undo', why: 'you allow this' }), noteDone: () => {} },
});

test('Pause during the 10-second Undo countdown keeps the bot paused and the action not done', async () => {
  let release; const gate = new Promise((r) => { release = r; });
  const w = world(({ n }) => (n === 0 ? turn(call('send_email', { to: 'bob' })) : turn(call('finish', { report: 'Sent.' }))),
    { ex: undoExtras(), countdown: async () => { await gate; return true; } });
  await w.bots.init();
  const j = w.bots.deploy('Email Bob the notes');
  for (let i = 0; i < 50 && !String(j.current || '').startsWith('About to'); i++) await new Promise((r) => setTimeout(r, 4));
  assert.match(String(j.current), /About to/);
  w.bots.pause(j.id);   // the user taps Pause while "About to: … · Undo" is showing
  release();             // the countdown runs out without Undo
  await settle(w);
  assert.equal(j.state, B.S.PAUSED, 'it stays paused');
  assert.deepEqual(w.ran, [], 'and the email was not sent');
});

test('Stop during the 10-second Undo countdown: nothing is done', async () => {
  let release; const gate = new Promise((r) => { release = r; });
  const w = world(({ n }) => (n === 0 ? turn(call('send_email', { to: 'bob' })) : turn(call('finish', { report: 'Sent.' }))),
    { ex: undoExtras(), countdown: async () => { await gate; return true; } });
  await w.bots.init();
  const j = w.bots.deploy('Email Bob the notes');
  for (let i = 0; i < 50 && !String(j.current || '').startsWith('About to'); i++) await new Promise((r) => setTimeout(r, 4));
  w.bots.stop(j.id); release(); await settle(w);
  assert.equal(j.state, B.S.CANCELLED);
  assert.deepEqual(w.ran, []);
});
