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
// The phone and the PC must agree exactly: the same cases run in app/src/test/.../SyncPlanTest.kt (tools/sync-fixtures.json).
test('sync: the same fingerprints, encryption and merge rules as the phone', async () => {
  const { readFileSync } = await import('node:fs');
  const { createHash } = await import('node:crypto');
  const fx = JSON.parse(readFileSync(new URL('../../tools/sync-fixtures.json', import.meta.url), 'utf8'));
  assert.equal(await sync.owner(fx.code), fx.owner);
  assert.equal(await sync.decrypt(fx.code, fx.fromPc), fx.plain);
  assert.equal(await sync.decrypt(fx.code, fx.fromPhoneJava), fx.plain, 'reads what the phone encrypts');
  for (const c of fx.canonical) {
    const canon = sync.canonical(JSON.parse(c.json));
    assert.equal(canon, c.canonical);
    assert.equal(createHash('sha1').update(canon, 'utf8').digest('hex'), c.sha1);
  }
  for (const m of fx.mineWins) assert.equal(sync.mineWins(m.myAt, m.me, m.theirAt, m.theirDevice), m.wins);
  for (const c of fx.stamp) {
    const sent = structuredClone(c.sent), dirty = structuredClone(c.dirty);
    sync.stamp(sent, dirty, c.local, c.gone, c.now, c.first, c.skip, c.extraImplicit);
    assert.deepEqual(sent, c.expectSent, c.name); assert.deepEqual(dirty, c.expectDirty, c.name);
  }
});
test('sync: an older edit from the other device never beats a newer one made here offline', () => {
  // made here at 2000 while offline, the phone's copy says 1000: ours stays and goes up; a later phone edit at 3000 wins
  assert.equal(sync.mineWins(2000, 'pc', 1000, 'phone'), true);
  assert.equal(sync.mineWins(2000, 'pc', 3000, 'phone'), false);
  assert.equal(sync.freshStart('old-id', 'new-id'), true, 'a re-created data file restores from the cloud instead of deleting');
  assert.equal(sync.freshStart('', 'new-id'), false);
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

// ------------------------------------------------------------------ hearing and speaking: the plain rules (same as Speech.kt)
const sp = await import('../renderer/speech.js');
const feedAll = (ep, levels) => { let last = 'going'; for (const [level, ms] of levels) for (let i = 0; i < ms / sp.FRAME_MS; i++) if (last === 'going') last = ep.feed(level); return last; };
test('knows when you have finished talking', () => {
  assert.equal(feedAll(new sp.Endpointer(), [[0.003, 400], [0.08, 1200], [0.003, 1500]]), 'done');
  const ep = new sp.Endpointer();
  assert.equal(feedAll(ep, [[0.003, 300], [0.08, 800], [0.003, 900], [0.08, 600]]), 'going');   // a pause for breath
  assert.equal(feedAll(ep, [[0.003, 1400]]), 'done');
  assert.equal(feedAll(new sp.Endpointer(), [[0.002, 7200]]), 'nothing');
  assert.equal(feedAll(new sp.Endpointer(), [[0.003, 500], [0.2, 60], [0.003, 7000]]), 'nothing');   // a click
  assert.equal(feedAll(new sp.Endpointer(), [[0.02, 600], [0.15, 1000], [0.02, 1600]]), 'done');      // a noisy room
});
test('replies are cut into pieces to speak without losing a word', () => {
  assert.deepEqual(sp.sentences('Dr. Ade is at 3.5 km. Is that fine? Good!'), ['Dr. Ade is at 3.5 km.', 'Is that fine?', 'Good!']);
  const text = Array.from({ length: 30 }, (_, i) => `Sentence number ${i + 1} is here.`).join(' ');
  const c = sp.chunks(text);
  assert.ok(c.length > 2 && c[0].length <= 140 && c.every((x) => x.length <= 320));
  assert.equal(c.join(' '), text);
  const long = 'word '.repeat(200).trim();
  assert.equal(sp.chunks(long).join(' '), long);
});
test('what was heard is tidied; the end of a talk is recognised', () => {
  assert.equal(sp.fixHeard('Thank you.', 300), '');
  assert.equal(sp.fixHeard('Thank you.', 1500), 'Thank you.');
  assert.equal(sp.fixHeard('Atlas, Anjy', 5000, 'Atlas, Anjy'), '');
  assert.equal(sp.fixHeard("hey at last what's the time"), "Hey Atlas, what's the time");
  assert.equal(sp.fixHeard('Ask Atlus to wait'), 'Ask Atlas to wait');
  for (const t of ["That's all", "that's all, thanks", 'Never mind.', "No, that's all for now", 'Goodbye']) assert.ok(sp.endsTalk(t), t);
  for (const t of ["That's all I know about it, what else?", 'Remind me to say goodbye to mum', 'thanks']) assert.ok(!sp.endsTalk(t), t);
  assert.deepEqual(sp.names('Call Tunde about the Lagos trip. Then rest'), ['Tunde', 'Lagos']);
});
test('spoken text reads cleanly: no addresses, table bars or doubled marks', () => {
  const s = plainSpoken("Here's the plan:\n\n| Day | Task |\n|---|---|\n| Mon | Gym |\n\nSee [the guide](https://ex.com/a_b) or https://ex.com/x. Done?");
  assert.ok(!/http|\||\?\.|---/.test(s), s);
  assert.ok(s.includes('the guide') && s.includes('Mon, Gym') && s.endsWith('Done?'), s);
});

// ---- this PC: will a local model fit? (renderer/fit.js) ----
import * as fitR from '../renderer/fit.js';
// the user's laptop: 16 GB, an NVIDIA card (size measured, here an 8 GB card with 7 GB free), ~9 GB of memory free
const msi = { totalRamMb: 16_000, freeRamMb: 9_000, physicalCores: 8, logicalCores: 16, cpu: 'Intel Core i7', gpus: [{ name: 'NVIDIA GeForce RTX 4060 Laptop GPU', vramTotalMb: 8188, vramFreeMb: 7200, source: 'nvidia-smi' }] };

test('fit: a 7B model runs on the laptop card, a 14B splits, a 70B is refused', () => {
  const m7 = fitR.fit(msi, { id: 'qwen2.5:7b', sizeMb: 4_700 });
  assert.equal(m7.place, 'split', 'a 4.7 GB file plus its cache and workspace is over 6.9 GB of card');
  const m3 = fitR.fit(msi, { id: 'llama3.2:3b', sizeMb: 2_000 });
  assert.equal(m3.place, 'gpu');
  const m14 = fitR.fit(msi, { id: 'qwen2.5:14b', sizeMb: 9_000 });
  assert.equal(m14.place, 'split');
  const m70 = fitR.fit(msi, { id: 'llama3.3:70b', sizeMb: 42_000 });
  assert.equal(m70.place, 'no'); assert.equal(m70.ok, false); assert.match(m70.reason, /freeze/);
});

test('fit: a model the server already holds counts its own memory as free', () => {
  const busy = { ...msi, freeRamMb: 3_000, gpus: [{ ...msi.gpus[0], vramFreeMb: 1_000 }] };
  assert.equal(fitR.fit(busy, { id: 'qwen2.5:7b', sizeMb: 4_700 }).ok, false);
  assert.equal(fitR.fit(busy, { id: 'qwen2.5:7b', sizeMb: 4_700, loaded: true, vramMb: 4_700 }).ok, true);
});

test('fit: no card, or a card whose free memory isn\'t reported', () => {
  const cpuOnly = { ...msi, gpus: [] };
  assert.equal(fitR.fit(cpuOnly, { id: 'llama3.2:3b', sizeMb: 2_000 }).place, 'cpu');
  const unknownFree = { ...msi, gpus: [{ name: 'NVIDIA GeForce RTX 4060', vramTotalMb: 8188, vramFreeMb: -1 }] };
  assert.equal(fitR.cardFreeMb(unknownFree), Math.round(8188 * 0.85) - fitR.VRAM_SPARE_MB);
  assert.equal(fitR.fit(msi, { id: 'mystery', sizeMb: -1 }).place, 'unknown');
});

test('fit: size from a model name when the server doesn\'t say', () => {
  assert.ok(Math.abs(fitR.sizeFromName('qwen2.5-7b-instruct-q4_k_m') - 4005) < 50);
  assert.ok(fitR.sizeFromName('llama3.1:8b') > 4000);
  assert.ok(fitR.sizeFromName('gemma-2-9b-it-Q8_0') > 9000);
  assert.equal(fitR.sizeFromName('phi4'), -1);
});

test('fit: ranking puts card-sized models first, refused ones last', () => {
  const r = fitR.rank(msi, [{ id: 'big:70b', sizeMb: 42_000 }, { id: 'small:3b', sizeMb: 2_000 }, { id: 'mid:14b', sizeMb: 9_000 }]);
  assert.deepEqual(r.map((m) => m.id), ['small:3b', 'mid:14b', 'big:70b']);
});

test('fit: bots run one at a time when memory is short', () => {
  assert.equal(fitR.botSlots(msi), 2);
  assert.equal(fitR.botSlots({ ...msi, freeRamMb: 1_800 }), 1);
  assert.equal(fitR.botSlots({ ...msi, physicalCores: 2 }), 1);
  assert.equal(fitR.botSlots(null), 2);
  assert.match(fitR.line(msi), /RTX 4060/);
});

test('fit: the profile cache reads once, then reuses', async () => {
  let reads = 0; const c = fitR.profileCache(async () => { reads++; return { freeRamMb: reads }; }, 60000);
  await Promise.all([c.get(), c.get()]);
  assert.equal(reads, 1); assert.equal(c.now().freeRamMb, 1);
  await c.get(true); assert.equal(reads, 2);
});

// ---- speech on the PC: OpenAI's current speech model form, the designed Atlas voice
const vo = await import('../renderer/voice.js');
test('transcription form: gpt-transcribe gets keywords and languages; older models get language', () => {
  const f = vo.transcribeForm('gpt-transcribe', 'Atlas, Tunde, Lagos', 'en', { base64: 'AA', type: 'audio/wav', name: 's.wav' });
  assert.deepEqual(f['keywords[]'], ['Atlas', 'Tunde', 'Lagos']);
  assert.deepEqual(f['languages[]'], ['en']);
  assert.equal(f.language, undefined); assert.equal(f.temperature, undefined); assert.equal(f.prompt, 'Atlas, Tunde, Lagos');
  const g = vo.transcribeForm('whisper-large-v3-turbo', '', 'fr', { base64: 'AA' });
  assert.equal(g.language, 'fr'); assert.equal(g['keywords[]'], undefined); assert.equal(g.prompt, undefined); assert.equal(g.temperature, '0');
});
test('designed voice id is found in either answer shape, and the description names no one', () => {
  assert.equal(vo.voiceId({ id: 'voice_abc', sample_audio: { data: 'AAAA' } }), 'voice_abc');
  assert.equal(vo.voiceId({ voice: { name: 'voices/voice_x9' } }), 'voice_x9');
  assert.equal(vo.voiceId({ error: { message: 'no' } }), null);
  for (const w of ['jarvis', 'bettany', 'stark', 'iron man', 'marvel']) assert.ok(!vo.ATLAS_DESIGN.toLowerCase().includes(w));
});

test('voice: only Gemini refusing the designed voice forgets it (not the network, a busy server or a limit)', () => {
  assert.equal(vo.refusedVoice(400), true);
  assert.equal(vo.refusedVoice(404), true);
  assert.equal(vo.refusedVoice(0), false);      // no connection
  assert.equal(vo.refusedVoice(429), false);
  assert.equal(vo.refusedVoice(408), false);
  assert.equal(vo.refusedVoice(503), false);
});

test('bot browser (PC): Enter in a message box outside any form needs the OK; a site search does not', async () => {
  const { createRequire } = await import('node:module');
  const req = createRequire(import.meta.url);
  // botbrowser.js needs Electron only for its windows; riskOf is plain logic
  req.cache[req.resolve('electron')] = { id: 'electron', filename: 'electron', loaded: true, exports: { app: { on() {}, getPath: () => '/tmp' }, session: {}, BrowserWindow: class {} } };
  const { riskOf } = req('../botbrowser.js');
  const composer = { found: true, kind: 'text box', label: 'Message', inForm: false, host: 'chat.example.com' };
  assert.match(riskOf('browser_press', { key: 'Enter' }, composer), /Press Enter in “Message”, which may send it on chat\.example\.com/);
  assert.match(riskOf('browser_type', { target: '3', text: 'hi', submit: true }, composer), /may send it/);
  assert.equal(riskOf('browser_type', { target: '3', text: 'hi', submit: false }, composer), null);
  assert.equal(riskOf('browser_press', { key: 'Enter' }, { found: true, kind: 'search field', label: 'Search', inForm: false }), null);
  assert.equal(riskOf('browser_press', { key: 'Enter' }, { found: true, kind: 'field', label: 'Search', inForm: true, formAction: '/search', formSubmit: 'Go' }), null);
  assert.match(riskOf('browser_press', { key: 'Return' }, { found: true, kind: 'field', label: 'Qty', inForm: true, formAction: '/checkout', formSubmit: 'Place order' }), /Submit the form/);
});
