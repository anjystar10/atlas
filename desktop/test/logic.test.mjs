// The desktop app's logic, tested in Node: cleaning replies, Markdown, the sync code and encryption.
import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window = { atlasHost: { read: async () => null, write: async () => true } };
const { plainReply, plainMemory, plainSpoken } = await import('../renderer/plain.js');
const { renderMarkdown } = await import('../renderer/md.js');
const sync = await import('../renderer/sync.js');

test('ids, notes and printed tool calls disappear', () => {
  assert.equal(plainReply('Saved memory [3f2a9c1e-1b2c-4d5e-8f90-1234567890ab].'), 'Saved memory.');
  assert.equal(plainReply('Done.\n(What I did: Logged “Gym”)'), 'Done.');
  assert.equal(plainReply('Sure!\n```tool_code\nprint(default_api.remember(text="x"))\n```\nSaved.'), 'Sure!\n\nSaved.');
  assert.equal(plainReply('Ok\nremember(text="x")'), 'Ok');
  const code = 'Here:\n```python\nprint(1)\n```';
  assert.equal(plainReply(code), code);
});
test('memories are plain sentences', () => {
  assert.equal(plainMemory('`my chess goal is 1500`'), 'My chess goal is 1500');
  assert.equal(plainMemory('{"a": "Chess goal", "b": "1500"}'), 'Chess goal. 1500');
});
test('spoken text has no symbols', () => {
  const s = plainSpoken('## Plan\n- **Gym** at 10\n- Read');
  assert.ok(!/[*#]/.test(s)); assert.ok(s.includes('Gym at 10'));
});
test('markdown is escaped and structured', () => {
  const h = renderMarkdown('# Hi\n- **a** <b>x</b>\n1. one\n```js\nlet a = "<x>"\n```');
  assert.ok(h.includes('<h2>Hi</h2>')); assert.ok(h.includes('<strong>a</strong>')); assert.ok(h.includes('&lt;b&gt;'));
  assert.ok(h.includes('<ol start="1">')); assert.ok(h.includes('class="codebox"')); assert.ok(!h.includes('<b>x</b>'));
});
test('link codes', () => {
  const c = sync.newCode(); assert.match(c, /^ATLS(-[A-HJ-NP-TV-Z2-9]{4}){5}$/);
  assert.equal(sync.normalize(c.toLowerCase().replace(/-/g, ' ')), c);
  assert.equal(sync.normalize('ATLS-ABCD'), null);
  assert.equal(sync.normalize('ATLS-0000-0000-0000-0000-0000'), null);
});
test('encryption round trip, and the same owner as the phone', async () => {
  const c = 'ATLS-ABCD-EFGH-JKMN-PQRS-TV23';
  assert.equal(await sync.owner(c), 'cb1625882351349ba10af21b26b051c4af29c9e205a34e77ef37eaf3327cf965');
  const msg = JSON.stringify({ title: 'Pick up my sister ✓ é' });
  assert.equal(await sync.decrypt(c, await sync.encrypt(c, msg)), msg);
  await assert.rejects(sync.decrypt('ATLS-ABCD-EFGH-JKMN-PQRS-TV24', await sync.encrypt(c, msg)));
});
test('canonical form ignores field order', () => {
  assert.equal(sync.canonical({ b: 1, a: [1, { d: 2, c: null }] }), sync.canonical({ a: [1, { c: null, d: 2 }], b: 1 }));
});

// ------------------------------------------------------------------ the key pool: retries before switching, Don't Switch
const brain = await import('../renderer/brain.js');
const { db } = await import('../renderer/store.js');
const gem = brain.byId('gemini');
function pool(keys, { other = false, stay = false } = {}) {
  db.prefs = { keys: { gemini: keys.map((k) => ({ key: k, restUntil: 0, bad: false, fails: 0, problem: '' })), ...(other ? { groq: [{ key: 'gsk_x', restUntil: 0, bad: false, fails: 0, problem: '' }] } : {}) }, dont_switch: stay };
}
/** Calls through the router with a call that fails with [fails] in order, then answers 'ok'. */
async function run(fails) {
  const sess = { key: brain.keys('gemini')[0].key }; const used = []; const waits = []; let n = 0;
  const out = await brain.callWithKeys(gem, sess, async () => { used.push(sess.key); if (n < fails.length) throw fails[n++]; return 'ok'; }, 0,
    { onStatus: () => {}, cancelled: () => false, wait: async (ms) => { waits.push(ms); } });
  return { out, used, waits };
}
const busy = () => new brain.Failure('Gemini is having trouble right now (503).', 503);
const limit = () => new brain.Failure('The Gemini limit is used up for now.', 429);

test('a busy service is retried 3 times on the same key', async () => {
  pool(['k1', 'k2'], { other: true });
  const r = await run([busy(), busy(), busy()]);
  assert.equal(r.out, 'ok'); assert.deepEqual(r.used, ['k1', 'k1', 'k1', 'k1']); assert.deepEqual(r.waits, [2000, 5000, 10000]);
});
test('a fourth limit rests the key and the next key takes over', async () => {
  pool(['k1', 'k2'], { other: true });
  const r = await run([limit(), limit(), limit(), limit()]);
  assert.deepEqual(r.used, ['k1', 'k1', 'k1', 'k1', 'k2']);
  assert.ok(brain.keys('gemini')[0].restUntil > Date.now());
});
test('a turned-down key switches at once', async () => {
  pool(['k1', 'k2'], { other: true });
  const r = await run([new brain.Failure('Gemini turned the key down: no', 401)]);
  assert.deepEqual(r.used, ['k1', 'k2']); assert.ok(brain.keys('gemini')[0].bad);
});
test('with no keys left it moves to another service, after the retries', async () => {
  pool(['k1'], { other: true });
  await assert.rejects(run(Array(10).fill(0).map(busy)), (e) => e instanceof brain.Switch && e.to.id === 'groq');
  pool(['k1'], { other: true });
  await assert.rejects(run(Array(10).fill(0).map(limit)), (e) => e instanceof brain.Switch && e.to.id === 'groq');
});
test("Don't Switch stays on the service and says so", async () => {
  pool(['k1'], { other: true, stay: true });
  await assert.rejects(run(Array(20).fill(0).map(limit)), (e) => e instanceof brain.Failure && !(e instanceof brain.Switch) && /Don't Switch Services is on/.test(e.message));
  // other keys of the same service still take over
  pool(['k1', 'k2'], { other: true, stay: true });
  const r = await run([limit(), limit(), limit(), limit()]); assert.equal(r.used.at(-1), 'k2');
  // the chosen service stays chosen even while its keys rest; without Don't Switch another service takes over
  pool(['k1'], { other: true, stay: true }); db.prefs.provider = 'gemini'; db.prefs.keys.gemini[0].restUntil = Date.now() + 60000;
  assert.equal(brain.active().id, 'gemini');
  db.prefs.dont_switch = false; assert.equal(brain.active().id, 'groq');
});
test('other errors are not retried', async () => {
  pool(['k1'], { other: true });
  await assert.rejects(run([new brain.Failure("Gemini doesn't know that model", 404)]), (e) => e.code === 404);
test('links keep underscores and stay links; trailing punctuation stays outside', () => {
  const h = renderMarkdown('See https://ex.com/_a_/b_c. and [**x**](https://a.com/x_y_z)');
  assert.ok(h.includes('href="https://ex.com/_a_/b_c"')); assert.ok(h.includes('</a>.'));
  assert.ok(h.includes('href="https://a.com/x_y_z"')); assert.ok(h.includes('<strong>x</strong></a>'));
  assert.ok(!h.includes('<em>'));
  assert.ok(!renderMarkdown('[x](javascript:alert(1))').includes('href'));
});
test('a reminder edited here while offline is not overwritten by an older copy', async () => {
  const { db } = await import('../renderer/store.js');
  db.state = { tasks: [{ id: 't1', title: 'Gym at 7', updatedAt: 2000 }], memories: [], log: [], settings: {} };
  const older = { id: 't1', title: 'Gym', updatedAt: 1000 }, newer = { id: 't1', title: 'Gym at 8', updatedAt: 3000 };
  // 'stale-fingerprint' stands for what the cloud had before the edit here
  assert.equal(await sync.keepMine('task', 't1', older, 'stale-fingerprint'), true);
  assert.equal(await sync.keepMine('task', 't1', newer, 'stale-fingerprint'), false);
  assert.equal(await sync.keepMine('memory', 't1', older, 'x'), false);
  assert.equal(await sync.keepMine('task', 'nope', older, 'x'), false);
});
test('PowerShell quoting and programs that need a yes', async () => {
  const { createRequire } = await import('node:module');
  const pc = createRequire(import.meta.url)('../pc.js');
  assert.equal(pc.q("it's"), "'it''s'");
  assert.equal(pc.q('a\u2019b'), "'a''b'");
  assert.equal(pc.q('$(Remove-Item x)'), "'$(Remove-Item x)'");
  for (const p of ['C:\\x\\setup.exe', '~/Downloads/run.BAT', '"C:\\a b\\s.ps1"', 'thing.lnk']) assert.equal(pc.runsSomething(p), true, p);
  for (const p of ['~/Documents/notes.txt', 'C:\\Pictures', 'photo.jpg']) assert.equal(pc.runsSomething(p), false, p);
  assert.equal(pc.sendKeysCombo('ctrl+shift+t'), '^+t');
});
