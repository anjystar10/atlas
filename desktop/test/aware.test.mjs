// The main Atlas: the situation brief, answering approvals from the chat, the bots tool, the chat's browser, and the
// whole chat loop against a scripted model (a fake network), checking it picks the right tools.
import test from 'node:test';
import assert from 'node:assert/strict';

// ---- a fake network: a scripted OpenAI-style model; every request is kept so the test can look at what was sent
const requests = [];
let script = () => ({ text: 'ok' });
globalThis.window = { atlasHost: {
  read: async () => null, write: async () => true,
  fetch: async (url, opts) => {
    const body = JSON.parse(opts.body); requests.push(body);
    const r = script(body);
    const msg = r.call ? { role: 'assistant', content: null, tool_calls: [{ id: 'c' + requests.length, type: 'function', function: { name: r.call, arguments: JSON.stringify(r.args || {}) } }] } : { role: 'assistant', content: r.text };
    return { ok: true, status: 200, text: JSON.stringify({ choices: [{ message: msg }] }) };
  },
} };

const aware = await import('../renderer/aware.js');
const brain = await import('../renderer/brain.js');
const { S, LABELS, FINAL } = await import('../renderer/bots.js');
const store = await import('../renderer/store.js');
const { db } = store;
await store.load();   // a fresh, empty Atlas (the fake host has no saved files)

// ---- a fake set of bots (the real ones are tested in logic.test.mjs)
const calls = [];
const jobs = [
  { id: 'aaaa1111-run', goal: 'Compare three budget laptops for coding', state: S.RUNNING, current: 'Reading reviews', plan: [{ text: 'search', done: true }, { text: 'compare', done: false }], steps: ['Searched'], updatedAt: Date.now() },
  { id: 'bbbb2222-ok', goal: 'Order the blue socks from shop.test', state: S.AWAITING_APPROVAL, approval: { what: 'Click “Place order” on shop.test' }, plan: [], steps: [], updatedAt: Date.now() },
  { id: 'cccc3333-done', goal: 'Find a cheap flight to Abuja', state: S.COMPLETED, report: '# Flights\nCheapest is ₦92,000 on Friday with Air Peace.', plan: [], steps: [], updatedAt: Date.now() - 60000 },
];
const fakeBots = {
  visible: () => jobs, helpers: () => [], job: (id) => jobs.find((j) => j.id === id),
  approve: (id, yes, always) => calls.push(['approve', id, yes, !!always]),
  steer: (id, t) => { calls.push(['steer', id, t]); return true; },
  wrapUp: (id) => calls.push(['wrapUp', id]), pause: (id) => calls.push(['pause', id]), resume: (id) => calls.push(['resume', id]),
  stop: (id) => calls.push(['stop', id]), answer: (id, t) => calls.push(['answer', id, t]),
};
let seen = [];
const pageRuns = [];
aware.use({
  bots: fakeBots, S, labels: LABELS, final: FINAL,
  link: { linked: () => true, phone: () => ({ name: 'Reno', online: true, agoMs: 5000 }), outgoing: () => [], answerTask: async () => true, ago: () => 'just now' },
  conn: () => ({ servers: [{ name: 'notes', status: 'connected', tools: [1, 2, 3] }], apis: [] }),
  library: async () => [{ title: 'Laptop comparison', kind: 'document', at: Date.now() - 3600000 }],
  services: () => ({ ready: ['Gemini'], dontSwitch: false }),
  seen: { get: () => seen, set: (ids) => { seen = ids; } },
  browser: {
    check: async (id, goal, name, args) => (name === 'browser_click' && /order/i.test(args.target) ? 'Click “Place order” on shop.test' : null),
    run: async (id, goal, name, args) => { pageRuns.push([id, name, args]); return { out: `ran ${name}`, step: 'Did it on the page' }; },
  },
  mode: () => 'ask',
});

test('the brief says what is waiting, what is working and what just finished, and stays short', async () => {
  const b = await aware.brief();
  assert.match(b, /\[bot:bbbb2222-ok\] needs OK from the bot “Order the blue socks/);
  assert.match(b, /\[aaaa1111\] “Compare three budget laptops for coding” — Working: Reading reviews · plan 1\/2/);
  assert.match(b, /\[cccc3333\] “Find a cheap flight to Abuja” — .*Cheapest is ₦92,000.*\(NEW/);
  assert.match(b, /Reno: online/);
  assert.match(b, /notes: connected, 3 tools/);
  assert.match(b, /Laptop comparison \(document/);
  assert.match(b, /ready: Gemini/);
  assert.ok(b.length <= aware.MAX_CHARS + 2);
  // the finished one is only NEW once
  assert.doesNotMatch(await aware.brief(), /NEW/);
  assert.ok(aware.cap('x\n'.repeat(5000)).length <= aware.MAX_CHARS + 2);
});

test('a plain yes answers the only thing waiting; with several it asks which', async () => {
  assert.equal(aware.pick([{ ref: 'bot:x1234' }], '').ref, 'bot:x1234');
  assert.equal(aware.pick([{ ref: 'bot:x1234' }, { ref: 'chat1' }], ''), null);
  calls.length = 0;
  const r = await aware.run({ name: 'answer_approval', args: { yes: true } });
  assert.match(r.out, /Allowed/); assert.deepEqual(calls, [['approve', 'bbbb2222-ok', true, false]]);
  aware.addAsk('Submit the form', async () => ({ out: 'submitted' }));
  const two = await aware.run({ name: 'answer_approval', args: { yes: true } });
  assert.match(two.out, /several things are waiting/);
  assert.equal(aware.pick(aware.pending(), 'bot:bbbb2222').ref, 'bot:bbbb2222-ok');
  assert.equal(aware.pick(aware.pending(), 'bot:bb'), null);   // too short to guess
});

test('the bots tool lists, steers and stops the right bot', async () => {
  calls.length = 0;
  assert.match((await aware.run({ name: 'bots', args: { action: 'list' } })).out, /Compare three budget laptops/);
  await aware.run({ name: 'bots', args: { action: 'steer', id: 'aaaa', text: 'also check prices' } });
  await aware.run({ name: 'bots', args: { action: 'stop', id: 'socks' } });
  assert.deepEqual(calls, [['steer', 'aaaa1111-run', 'also check prices'], ['stop', 'bbbb2222-ok']]);
  assert.match((await aware.run({ name: 'bots', args: { action: 'report', id: 'cccc' } })).out, /Air Peace/);
});

test("the chat's browser: ordinary steps just happen, a risky one waits for the user's yes", async () => {
  pageRuns.length = 0;
  await aware.run({ name: 'web', args: { action: 'open', url: 'https://shop.test' } });
  assert.deepEqual(pageRuns.map((x) => x[1]), ['browser_open']);
  const r = await aware.run({ name: 'web', args: { action: 'click', target: 'Place order' } });
  const ref = /\[(chat\d+)\]/.exec(r.out)[1];
  assert.equal(pageRuns.length, 1, 'nothing was clicked before the OK');
  const done = await aware.run({ name: 'answer_approval', args: { ref, yes: true } });
  assert.equal(done.out, 'ran browser_click'); assert.equal(pageRuns.length, 2);
});

// ---- the whole chat loop, with a scripted model
test('the chat loop: the model sees the brief and the right tool runs for each request', async () => {
  db.prefs.keys = { openai: [{ key: 'sk-test', restUntil: 0, bad: false, fails: 0 }] };
  db.prefs.provider = 'openai';
  let phoneTask = null; brain.ui.onPhone = async (t) => { phoneTask = t; return 'Sent to your phone.'; };
  const lastUser = (b) => [...b.messages].reverse().find((m) => m.role === 'user').content;
  script = (b) => {
    const last = b.messages[b.messages.length - 1];
    if (last.role === 'tool') return { text: 'Done: ' + last.content.slice(0, 40) };
    const u = String(lastUser(b));
    if (/bots doing/.test(u)) return { call: 'bots', args: { action: 'list' } };
    if (/approve it/.test(u)) return { call: 'answer_approval', args: { yes: true } };
    if (/on my phone/.test(u)) return { call: 'on_phone', args: { task: 'open the camera' } };
    return { text: 'hm' };
  };
  // only one thing waiting again
  for (const a of aware.listAsks()) await aware.run({ name: 'answer_approval', args: { ref: a.ref, yes: false } });
  calls.length = 0; requests.length = 0;

  const r1 = await brain.send('what are my bots doing?');
  assert.match(r1.text, /Done:/);
  const sys = requests[0].messages[0].content;
  assert.match(sys, /What's going on right now/); assert.match(sys, /needs OK from the bot “Order the blue socks/);
  assert.ok(requests[0].tools.some((t) => t.function.name === 'bots') && requests[0].tools.some((t) => t.function.name === 'web'));
  assert.match(requests[1].messages.at(-1).content, /Compare three budget laptops/);

  await brain.send('approve it');
  assert.deepEqual(calls, [['approve', 'bbbb2222-ok', true, false]]);

  await brain.send('on my phone open the camera');
  assert.equal(phoneTask, 'open the camera');
});
