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

// ------------------------------------------------------------------ Atlas Bot

const B = await import('../renderer/bots.js');

test('bot job states follow the rules', () => {
  const j = B.newJob('g', '', 1, 'a');
  assert.ok(B.move(j, B.S.RUNNING, 2)); assert.ok(B.move(j, B.S.AWAITING_APPROVAL, 3));
  assert.ok(!B.move(j, B.S.COMPLETED, 4), 'no jump from waiting for an OK to done');
  assert.ok(B.move(j, B.S.QUEUED, 5)); assert.ok(B.move(j, B.S.RUNNING, 6)); assert.ok(B.move(j, B.S.COMPLETED, 7));
  assert.ok(!B.move(j, B.S.RUNNING, 8), 'finished stays finished');
  assert.equal(B.recover(B.S.RUNNING, true), B.S.NEEDS_REVIEW);
  assert.equal(B.recover(B.S.RUNNING, false), B.S.QUEUED);
  for (const s of B.ALL.filter((x) => x !== B.S.RUNNING)) assert.equal(B.recover(s, true), s);
  const jobs = [{ id: 'c', createdAt: 30, state: 'queued' }, { id: 'a', createdAt: 10, state: 'queued' }, { id: 'b', createdAt: 20, state: 'queued' }, { id: 'r', createdAt: 1, state: 'running' }];
  assert.deepEqual(B.toStart(jobs, 100).map((x) => x.id), ['a']);
  assert.equal(B.retryDelay(1), 60000); assert.equal(B.retryDelay(9), 16 * 60000);
  assert.equal(B.summary([{ state: 'running' }, { state: 'awaiting_approval' }, { state: 'completed', updatedAt: 99 }], 100), '1 working · 1 needs you · 1 done');
});

test('consequential PC actions need an OK, the same rule as the phone', () => {
  assert.equal(B.approvalFor('run_command', { command: 'Get-ChildItem C:\\Users' }), null);
  assert.ok(B.approvalFor('run_command', { command: 'Remove-Item C:\\x' }));
  assert.ok(B.approvalFor('run_command', { command: 'Get-ChildItem | Remove-Item' }));
  assert.ok(B.approvalFor('write_file', { path: 'a.txt' }));
  assert.ok(B.approvalFor('press_keys', { keys: 'enter' }));
  assert.equal(B.approvalFor('press_keys', { keys: 'ctrl+t' }), null);
  assert.ok(B.approvalFor('open_link', { url: 'mailto:a@b.c' }));
  assert.equal(B.approvalFor('open_link', { url: 'https://example.com' }), null);
  assert.equal(B.approvalFor('web_search', { query: 'x' }), null);
  assert.deepEqual(B.parsePlan('1. [x] Search\n[ ] Compare\n\n3) Write'), [{ done: true, text: 'Search' }, { done: false, text: 'Compare' }, { done: false, text: 'Write' }]);
});

class Busy extends Error { constructor() { super('Busy.'); this.code = 503; } }
class Sw extends Error { constructor(to) { super('Keys out.'); this.to = to; } }

/** A bot world with a scripted model: each call takes the next turn from [script] (a function of the brief, or a turn). */
function world(script, { journal = null, providers = [{ id: 'gemini', name: 'Gemini' }], switchAt = -1, failAt = -1 } = {}) {
  const w = { saved: journal, notes: [], posts: [], ran: [], briefs: [], tools: [], timers: [], calls: 0, prov: providers };
  let n = 0, clock = 1000;
  w.bots = B.createBots({
    load: async () => w.saved, save: async (t) => { w.saved = t; },
    provider: () => w.prov[0],
    session: (p, brief, tools) => { w.briefs.push(brief); w.tools.push(tools.map((t) => t.name)); return { key: 'k', call: async () => {
      const i = n++; const s = script[i]; if (!s) throw new Error('script ran out at ' + i); return typeof s === 'function' ? s(brief) : s; }, results: (list) => w.notes.push(list.map((x) => x.out)) }; },
    callWithKeys: async (p, sess, call) => { const k = w.calls++; if (k === switchAt) throw new Sw(w.prov[1]); if (k === failAt) throw new Busy(); return call(); },
    isSwitch: (e) => e instanceof Sw, isRetryable: (e) => e instanceof Busy,
    tools: [{ name: 'web_search' }, { name: 'read_page' }, { name: 'run_command' }, { name: 'change_setting' }, { name: 'deploy_bot' }],
    runTool: async (c, o) => { w.ran.push([c.name, !!(o && o.approved)]); return { out: `${c.name} result`, step: `Did ${c.name}` }; },
    systemPrompt: () => 'sys', notify: (t, b, tag) => w.notes.push(['notify', t, tag]), post: (m) => w.posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => (clock += 10), uid: () => 'job1', deviceId: () => 'pc-1',
    setTimer: (f, ms) => { if (ms <= 0) { setTimeout(f, 0); return 0; } w.timers.push(ms); return w.timers.length; }, clearTimer: () => {},
  });
  return w;
}
const call = (name, args = {}) => ({ name, args, id: name });
const turn = (...calls) => ({ text: '', calls });
const settle = async (w) => { for (let i = 0; i < 20; i++) { await new Promise((r) => setTimeout(r, 5)); await w.bots.idle(); } };

test('a bot plans, researches, notes, finishes, and posts its report', async () => {
  const w = world([
    turn(call('update_plan', { plan: '[ ] Search\n[ ] Read\n[ ] Report' }), call('web_search', { query: 'best phone' })),
    turn(call('note', { text: 'Phone X is 299 (example.com)' }), call('read_page', { url: 'https://example.com/x' })),
    turn(call('finish', { report: '## Best phone\n\nPhone X.\n\n## Sources\n- [Example](https://example.com/x)' })),
  ]);
  await w.bots.init();
  const j = w.bots.deploy('Find the best phone', 'under 300');
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
  assert.ok(j.report.includes('Phone X'));
  assert.deepEqual(j.plan.map((p) => p.done), [true, true, true]);
  assert.deepEqual(j.sources, ['https://example.com/x']);
  assert.deepEqual(w.ran, [['web_search', false], ['read_page', false]]);
  assert.equal(w.posts.length, 1); assert.ok(w.posts[0].startsWith('**Atlas Bot finished:** Find the best phone'));
  assert.ok(!w.tools[0].includes('change_setting') && !w.tools[0].includes('deploy_bot') && w.tools[0].includes('finish'), 'bots get their own tool set');
  assert.equal(j.pending, null);
  const back = JSON.parse(w.saved); assert.equal(back[0].state, 'completed', 'the journal is saved');
  assert.equal(back[0].updated_ms, back[0].updatedAt); assert.equal(back[0].device, 'pc-1', 'ready to sync later: updated_ms and the owning device');
  assert.ok(!/key/i.test(Object.keys(back[0]).join(',')), 'no secrets in the record');
});

test('a risky command waits for the OK, then runs once approved', async () => {
  const w = world([
    turn(call('run_command', { command: 'Remove-Item C:\\temp\\old.txt' })),
    (brief) => { assert.ok(brief.includes('Approved and done')); return turn(call('finish', { report: 'Removed it.' })); },
  ]);
  await w.bots.init();
  const j = w.bots.deploy('Clean up', '');
  await settle(w);
  assert.equal(j.state, B.S.AWAITING_APPROVAL);
  assert.deepEqual(w.ran, [], 'nothing ran before the OK');
  assert.ok(w.notes.some((x) => Array.isArray(x) && x[1] === 'Atlas Bot needs your OK'));
  w.bots.approve(j.id, true);
  await settle(w);
  assert.deepEqual(w.ran, [['run_command', true]]);
  assert.equal(j.state, B.S.COMPLETED);
});

test('a denied action is not run and the bot is told', async () => {
  const w = world([
    turn(call('run_command', { command: 'Remove-Item x' })),
    (brief) => { assert.ok(brief.includes('said NO')); return turn(call('finish', { report: 'Left it.' })); },
  ]);
  await w.bots.init(); const j = w.bots.deploy('Clean up', ''); await settle(w);
  w.bots.approve(j.id, false); await settle(w);
  assert.deepEqual(w.ran, []); assert.equal(j.state, B.S.COMPLETED);
});

test('a question waits for the answer, and the bot gets it', async () => {
  const w = world([
    turn(call('ask_user', { question: 'Which colour?' })),
    (brief) => { assert.ok(brief.includes('They answered: Blue')); return turn(call('finish', { report: 'Blue it is.' })); },
  ]);
  await w.bots.init(); const j = w.bots.deploy('Pick a case', ''); await settle(w);
  assert.equal(j.state, B.S.AWAITING_ANSWER); assert.equal(j.question, 'Which colour?');
  w.bots.answer(j.id, 'Blue'); await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
});

test('when a service runs out the bot carries on with the next, and busy services are retried later', async () => {
  const w = world([
    turn(call('web_search', { query: 'a' })),
    (brief) => { assert.ok(brief.includes('Did web_search'), 'the new service hears what was done'); return turn(call('finish', { report: 'Done.' })); },
  ], { providers: [{ id: 'gemini', name: 'Gemini' }, { id: 'groq', name: 'Groq' }], switchAt: 1 });
  await w.bots.init(); const j = w.bots.deploy('Look', ''); await settle(w);
  assert.equal(j.state, B.S.COMPLETED); assert.equal(j.provider, 'Groq');

  const w2 = world([turn(call('finish', { report: 'ok' }))], { failAt: 0 });
  await w2.bots.init(); const k = w2.bots.deploy('Look', ''); await settle(w2);
  assert.equal(k.state, B.S.WAITING_RETRY); assert.equal(k.retries, 1); assert.equal(w2.timers.length, 1); assert.ok(w2.timers[0] > 59000 && w2.timers[0] <= 60000, 'wakes up in about a minute');
});

test('after a crash mid-action the job waits for a check and is never replayed', async () => {
  const j = B.newJob('g', '', 1, 'job1'); j.state = 'running'; j.pending = 'Running a command';
  const k = B.newJob('h', '', 2, 'job2'); k.state = 'running';
  const w = world([turn(call('finish', { report: 'resumed' }))], { journal: JSON.stringify([j, k]) });
  await w.bots.init(); await settle(w);
  const a = w.bots.job('job1'), b = w.bots.job('job2');
  assert.equal(a.state, B.S.NEEDS_REVIEW); assert.deepEqual(w.ran, []);
  assert.equal(b.state, B.S.COMPLETED, 'a job that was between steps just carries on');
  w.bots.stop('job1'); assert.equal(a.state, B.S.CANCELLED);
});

test('pause and stop', async () => {
  let release; const gate = new Promise((r) => (release = r));
  const w = world([async () => { await gate; return turn(call('web_search', { query: 'a' })); }, turn(call('finish', { report: 'ok' }))]);
  await w.bots.init(); const j = w.bots.deploy('Look', '');
  await new Promise((r) => setTimeout(r, 10));
  w.bots.pause(j.id); release(); await settle(w);
  assert.equal(j.state, B.S.PAUSED); assert.deepEqual(w.ran, [], 'nothing runs after a pause');
  w.bots.resume(j.id); await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
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

test("the service's own wait is read from its answer", () => {
  const gem = '{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}';
  assert.equal(brain.retryHint(gem, null), 37000);
  assert.equal(brain.retryHint('Rate limit reached. Please try again in 20s.', null), 20000);
  assert.equal(brain.retryHint('Please retry in 1.5 seconds', null), 1500);
  assert.equal(brain.retryHint('Please retry after 20 seconds.', null), 20000);
  assert.equal(brain.retryHint('try again in 1m30s', null), 90000);
  assert.equal(brain.retryHint('Please try again in 500ms.', null), 500);
  assert.equal(brain.retryHint('Try again in 2 minutes.', null), 120000);
  assert.equal(brain.retryHint('Please retry later. Or try again in 12s', null), 12000);
  assert.equal(brain.retryHint('{}', '8'), 8000);
  assert.equal(brain.retryHint('Please retry later.', null), 0);
  assert.equal(brain.retryHint('retry after 20', 'Wed, 21 Oct 2026 07:28:00 GMT'), 0);
  assert.equal(brain.retryHint(null, null), 0);
});
test("a limit waits as long as the service asked, unless that's over a minute", async () => {
  pool(['k1', 'k2'], { other: true });
  const asked = () => Object.assign(limit(), { retryAfterMs: 37000 });
  let r = await run([asked(), asked(), asked()]);
  assert.deepEqual(r.waits, [37000, 37000, 37000]); assert.deepEqual(r.used, ['k1', 'k1', 'k1', 'k1']);
  pool(['k1', 'k2'], { other: true });
  r = await run([Object.assign(limit(), { retryAfterMs: 3600000 })]);
  assert.deepEqual(r.used, ['k1', 'k2']);
  pool(['k1', 'k2'], { other: true });
  r = await run([limit(), limit(), limit()]); assert.deepEqual(r.waits, [5000, 15000, 40000]);
});
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
});

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
