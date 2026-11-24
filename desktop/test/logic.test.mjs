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
function world(script, { journal = null, providers = [{ id: 'gemini', name: 'Gemini' }], switchAt = -1, failAt = -1, extras = null, ids = false, tools = null, extraApproval = null, checkApproval = null, countdown = null } = {}) {
  const w = { saved: journal, notes: [], posts: [], ran: [], briefs: [], tools: [], timers: [], calls: 0, prov: providers, made: 0 };
  let n = 0, clock = 1000;
  w.bots = B.createBots({
    load: async () => w.saved, save: async (t) => { w.saved = t; },
    provider: () => w.prov[0],
    session: (p, brief, tools) => { w.briefs.push(brief); w.tools.push(tools.map((t) => t.name)); return { key: 'k', call: async () => {
      const i = n++; const s = script[i]; if (!s) throw new Error('script ran out at ' + i); return typeof s === 'function' ? s(brief) : s; }, results: (list) => w.notes.push(list.map((x) => x.out)) }; },
    callWithKeys: async (p, sess, call) => { const k = w.calls++; if (k === switchAt) throw new Sw(w.prov[1]); if (k === failAt) throw new Busy(); return call(); },
    isSwitch: (e) => e instanceof Sw, isRetryable: (e) => e instanceof Busy,
    tools: tools || [{ name: 'web_search' }, { name: 'read_page' }, { name: 'run_command' }, { name: 'change_setting' }, { name: 'deploy_bot' }], extraApproval, ...(checkApproval ? { checkApproval } : {}),
    runTool: async (c, o) => { w.ran.push([c.name, !!(o && o.approved)]); return { out: `${c.name} result`, step: `Did ${c.name}` }; },
    systemPrompt: (j) => { w.sysFor = (w.sysFor || []).concat([j && j.id]); return 'sys'; }, notify: (t, b, tag) => w.notes.push(['notify', t, tag]), post: (m) => w.posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => (clock += 10), uid: ids ? (() => 'job' + (++w.made)) : () => 'job1', deviceId: () => 'pc-1', extras, ...(countdown ? { countdown } : {}),
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
    turn(call('finish', { report: 'too early' })),
    (brief, ctx) => turn(call('finish', { report: '## Best phone\n\nPhone X.\n\n## Sources\n- [Example](https://example.com/x)' })),
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
test("a 400 that only names a tool with \"key\" in it is not the key's fault: the key stays and nothing switches", async () => {
  pool(['k1', 'k2'], { other: true });
  const f = new brain.Failure("Groq didn't accept the request: tool call validation failed: parameters for tool press_keys did not match schema", 400);
  await assert.rejects(run([f]), (e) => e === f, 'the refusal is reported as it is');
  assert.ok(brain.keys('gemini').every((e) => !e.bad && !e.restUntil), 'no key was set aside or rested');
  assert.equal(brain.cause(new brain.Failure("That Gemini key isn't valid.", 400)), 'key');
  assert.equal(brain.cause(new brain.Failure("Gemini didn't accept the request: API key expired. Please renew the API key.", 400)), 'key');
  assert.equal(brain.cause(new brain.Failure("OpenAI didn't accept the request: 'keywords' is not valid", 400)), 'other');
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
test('sync, round two: pull position, batches, problems, shared settings and presence agree with the phone', async () => {
  const { readFileSync } = await import('node:fs');
  const fx = JSON.parse(readFileSync(new URL('../../tools/sync-fixtures.json', import.meta.url), 'utf8'));
  for (const c of fx.advance) assert.deepEqual(sync.advance(c.cursor, c.recent, c.now), [c.expectCursor, c.expectRecent], c.name);
  for (const c of fx.alreadyTaken) assert.equal(sync.alreadyTaken(c.seen, c.rev), c.want);
  for (const c of fx.batches) assert.deepEqual(sync.batches(c.sizes), c.expect, c.name);
  for (const c of fx.problem) assert.deepEqual(sync.problem(c.status, c.body), c.expect);
  for (const c of fx.profileOf) assert.equal(sync.canonical(sync.profileOf(c.settings)), sync.canonical(c.expect));
  for (const c of fx.profileFields) assert.deepEqual(sync.profileFields(c.rec), c.expect);
  for (const c of fx.untouchedProfile) assert.equal(sync.untouchedProfile(c.profile), c.want, c.name);
  for (const c of fx.presenceDue) assert.equal(sync.presenceDue(c.last, c.now), c.want);
});
test('sharing AI keys: the same merge rules as the phone, and a key is never lost to the other device', async () => {
  const { readFileSync } = await import('node:fs');
  const ks = await import('../renderer/keyshare.js');
  const fx = JSON.parse(readFileSync(new URL('../../tools/sync-fixtures.json', import.meta.url), 'utf8')).keyshare;
  for (const [k, f] of fx.fingerprints) assert.equal(await ks.fingerprint(k), f);
  for (const c of fx.merge) assert.deepEqual(ks.merge(c.a, c.b), c.expect, c.name);
  for (const c of fx.present) assert.deepEqual(await ks.present(c.rec), c.expect, c.name);
  for (const c of fx.observe) assert.deepEqual(await ks.observe(c.rec, c.seen, c.now, c.at), c.expect, c.name);
  // both devices add a different key at once: after merging both ways, both have both keys
  const phone = await ks.observe({}, [], ['AIza-phone'], 100), pc = await ks.observe({}, [], ['AIza-pc'], 101);
  assert.deepEqual(await ks.present(ks.merge(phone, pc)), ['AIza-pc', 'AIza-phone']);
  assert.deepEqual(await ks.present(ks.merge(pc, phone)), ['AIza-pc', 'AIza-phone']);
  // the record holds no removed key itself, only its fingerprint
  const gone = await ks.observe(pc, ['AIza-pc'], [], 200);
  assert.ok(!JSON.stringify(gone).includes('AIza-pc'));
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
  // Windows runs "setup.exe." and "setup.exe " as setup.exe; installers and disk images count too
  for (const p of ['C:\\x\\setup.exe.', 'C:\\x\\setup.exe .. ', 'C:\\x\\a.exe::$DATA', 'app.msix', 'disk.iso', 'x.msc', '~/Downloads/fix.py', 'tool.pyw', 'go.sh']) assert.equal(pc.runsSomething(p), true, p);
  for (const p of ['~/Documents/notes.txt', 'C:\\Pictures', 'photo.jpg']) assert.equal(pc.runsSomething(p), false, p);
  assert.equal(pc.sendKeysCombo('ctrl+shift+t'), '^+t');
});
test("PC paths start at the user's home and real folders, not the app's working folder", async () => {
  const { createRequire } = await import('node:module');
  const pc = createRequire(import.meta.url)('../pc.js');
  const os = await import('node:os'), path = await import('node:path');
  const home = os.homedir(), folder = (k) => path.join('/od', k);   // e.g. OneDrive moved Desktop and Documents
  const cwd = process.cwd(); process.chdir(os.tmpdir());
  try {
    assert.equal(pc.expand('Desktop', folder), path.join('/od', 'Desktop'));
    assert.equal(pc.expand('Desktop/todo.txt', folder), path.join('/od', 'Desktop', 'todo.txt'));
    assert.equal(pc.expand('documents\\report.txt', folder), path.join('/od', 'Documents', 'report.txt'));
    assert.equal(pc.expand('~/Downloads/a.pdf', folder), path.join('/od', 'Downloads', 'a.pdf'));
    assert.equal(pc.expand('~', folder), home);
    assert.equal(pc.expand('', folder), home);
    assert.equal(pc.expand('notes.txt', folder), path.join(home, 'notes.txt'));
    assert.equal(pc.expand('~/code/x.js', folder), path.join(home, 'code', 'x.js'));
    assert.equal(pc.expand('/etc/hosts', folder), path.resolve('/etc/hosts'));   // C:\etc\hosts on Windows
    assert.equal(pc.expand('"DesktopStuff/a.txt"', folder), path.join(home, 'DesktopStuff', 'a.txt'));
  } finally { process.chdir(cwd); }
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

// ---- the vault's rules (desktop/vault.js): which site a sign-in belongs to, and what the model may see
import { createRequire as vaultRequire } from 'node:module';
const vaultRules = vaultRequire(import.meta.url)('../vault.js');
test('vault: a sign-in only works on its own site, never a look-alike', () => {
  assert.equal(vaultRules.siteOf('https://www.Accounts.Google.com/signin?x=1'), 'accounts.google.com');
  assert.ok(vaultRules.siteMatches('google.com', 'accounts.google.com'));
  assert.ok(!vaultRules.siteMatches('google.com', 'google.com.evil.example'));
  assert.ok(!vaultRules.siteMatches('google.com', 'notgoogle.com'));
  assert.ok(!vaultRules.siteMatches('', 'google.com'));
});
test('vault: the model sees names and sites only; values are quoted safely for the page', () => {
  const entries = [{ id: '1', name: 'My Gmail', site: 'accounts.google.com', username: 'king@example.com', password: 'p"w\\</script>', trusted: false }];
  const text = vaultRules.forModel(entries);
  assert.ok(text.includes('My Gmail') && text.includes('accounts.google.com'));
  assert.ok(!text.includes('king@example.com') && !text.includes('p"w'));
  assert.equal(vaultRules.find(entries, 'my gmail').id, '1');
  assert.equal(vaultRules.find(entries, 'accounts.google.com').id, '1');
  assert.ok(vaultRules.fillScript('u', entries[0].password).includes(JSON.stringify(entries[0].password)));
});

// ---------------------------------------------------------------- Atlas Bot extras: approvals, steering, routines, skills, specialists
import * as X from '../renderer/botx.js';
function extrasFor(opts = {}) {
  const st = { saved: null, fired: [], timers: [] };
  let clock = opts.clock || Date.UTC(2026, 10, 9, 7, 0);   // Monday
  st.ex = X.createExtras({
    load: async () => st.saved, save: async (t) => { st.saved = t; }, now: () => clock, uid: (() => { let n = 0; return () => 'x' + (++n); })(), deviceId: () => opts.device || 'pc-1',
    setTimer: (f, ms) => { st.timers.push(ms); return st.timers.length; }, clearTimer: () => {},
    fire: (r) => { st.fired.push(r.id); return { id: 'job-' + r.id }; }, claim: opts.claim, changed: (k) => (st.lastChanged = k),
  });
  st.tick = (ms) => { clock += ms; return st.ex.tick(); };
  st.at = (ms) => { clock = ms; };
  return st;
}

test('approvals: "always allow" runs that kind without asking; "never" refuses it; others still ask', async () => {
  const e = extrasFor(); await e.ex.init();
  e.ex.setMode('command', 'allow'); e.ex.setMode('purchase', 'deny');
  const w = world([
    turn(call('run_command', { command: 'Stop-Process -Name notepad' })),
    (brief) => { assert.ok(brief.includes('Approved and done'), 'allowed by the rule, then done'); return turn(call('request_approval', { action: 'Buy the blue case for $20' })); },
    (brief) => { assert.ok(brief.includes('never allows'), 'the bot is told the user never allows it'); return turn(call('request_approval', { action: 'Send the summary to Sam by email' })); },
    turn(call('finish', { report: 'Done what I could.' })),
  ], { extras: e.ex, countdown: async () => true });   // (autonomy: an allowed kind runs after the 10-second Undo, which nobody clicks here)
  await w.bots.init(); const j = w.bots.deploy('Tidy and shop', ''); await settle(w);
  assert.deepEqual(w.ran, [['run_command', true]], 'the allowed command ran without a tap, as approved');
  assert.equal(j.state, B.S.AWAITING_APPROVAL, 'an email still asks');
  assert.equal(j.approval.category, 'message');
  assert.ok(!w.notes.some((x) => Array.isArray(x) && x[1] === 'Atlas Bot needs your OK' && /Stop-Process|Buy/.test(x[2] || '')), 'no OK asked for the allowed or never kinds');
  w.bots.approve(j.id, true, true);   // Always allow this kind
  // autonomy: "always" is a grant for this exact kind (a message to someone known), not the whole category: a new person still asks
  assert.deepEqual(e.ex.autonomy.grants().map((g) => [g.kind, g.scope]), [['message_send', 'always']], 'Always allow saves a standing grant');
  assert.equal(e.ex.autonomy.decide('message_new').act, 'ask', 'messaging someone new still asks');
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
});

test('autonomy (Balanced): a small action waits 10 seconds for Undo; Undo stops it; with no way to show Undo it asks', async () => {
  // screen: the "low" kind (a heads-up before using the screen) — request_approval wording picks the kind
  const e = extrasFor(); await e.ex.init();
  let w = world([turn(call('run_command', { command: 'Stop-Process -Name notepad' })), turn(call('finish', { report: 'ok' }))], { extras: e.ex, countdown: async () => false });
  e.ex.autonomy.grant('command', 'always');
  await w.bots.init(); let j = w.bots.deploy('Tidy', ''); await settle(w);
  assert.deepEqual(w.ran, [], 'Undo was clicked: nothing ran');
  assert.ok(j.notes.some((n) => /tapped Undo/.test(n)), 'the bot is told the user clicked Undo');
  assert.ok(j.steps.some((t) => /You tapped Undo/.test(t)));
  const e2 = extrasFor(); await e2.ex.init(); e2.ex.autonomy.grant('command', 'always');
  w = world([turn(call('run_command', { command: 'Stop-Process -Name notepad' }))], { extras: e2.ex });   // no countdown: Atlas isn't on screen
  await w.bots.init(); j = w.bots.deploy('Tidy', ''); await settle(w);
  assert.equal(j.state, B.S.AWAITING_APPROVAL, 'no Undo possible, so it asks');
  assert.ok(j.approval.askedAt > 0 && j.approval.kind === 'command');
});

test('autonomy: approving a plan covers the kinds its steps name for that mission; Approve All skips money', async () => {
  const e = extrasFor(); await e.ex.init();
  const w = world([
    turn(call('update_plan', { plan: '1. Run the cleanup command\n2. Post the summary\n3. Pay the invoice' })),
    turn(call('request_approval', { action: 'Run the cleanup command' })),
    turn(call('request_approval', { action: 'Post the summary on the team page' })),
    turn(call('request_approval', { action: 'Pay the invoice of $40' })),
  ], { extras: e.ex, countdown: async () => true });
  await w.bots.init(); const j = w.bots.deploy('Wrap up the month', ''); await settle(w);
  assert.equal(j.state, B.S.AWAITING_APPROVAL); assert.equal(j.approval.kind, 'command');
  w.bots.approve(j.id, true, false, 'plan'); await settle(w);
  assert.ok(j.steps.some((t) => /Did it on its own .*Post the summary/.test(t)), 'the plan approval covered posting');
  assert.equal(j.state, B.S.AWAITING_APPROVAL, 'paying still asks'); assert.equal(j.approval.kind, 'purchase');
  const b = w.bots.approveAll();
  assert.equal(b.bulk.length, 0, 'Approve All never covers money'); assert.equal(j.state, B.S.AWAITING_APPROVAL);
  // (expiry itself: autonomy.test.mjs, 'approvals raised while the user is away expire'; the test clock here never reaches 12 hours)
});

test('approval kinds come from what the action does, including browser clicks', () => {
  const e = extrasFor();
  assert.equal(e.ex.categoryOf('browser_click', { target: '12' }, 'Click “Place order”'), 'purchase');
  assert.equal(e.ex.categoryOf('browser_click', {}, 'Click “Delete account”'), 'delete');
  assert.equal(e.ex.categoryOf('browser_click', {}, 'Click “Post”'), 'publish');
  assert.equal(e.ex.categoryOf('browser_type', {}, 'Type “hi” into Message and send it'), 'message');
  assert.equal(e.ex.categoryOf('run_command', {}, 'Run this command on your PC: Stop-Process x'), 'command');
  assert.equal(e.ex.categoryOf('write_file', {}, 'Write the file C:\\a.txt'), 'delete');
  assert.equal(e.ex.categoryOf(null, null, 'Accept the terms and sign up'), 'terms');
});

test('steering: a message reaches the working bot at its next step; wrap up finishes with what it has', async () => {
  let w;
  w = world([
    (brief) => { w.bots.steer(w.bots.jobs[0].id, 'Only look at phones under $250'); return turn(call('web_search', { query: 'phones' })); },
    (brief) => { assert.ok(brief.includes('NEW INSTRUCTION FROM THE USER') && brief.includes('under $250')); w.bots.wrapUp(w.bots.jobs[0].id); return turn(call('web_search', { query: 'cheap phones' })); },
    (brief) => { assert.ok(brief.includes('wrap up now')); return turn(call('finish', { report: 'Short report.' })); },
  ]);
  await w.bots.init(); const j = w.bots.deploy('Phones', ''); await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
  assert.ok(j.steps.includes('You said: Only look at phones under $250') && j.steps.includes('You asked it to wrap up'));
  assert.deepEqual(w.tools[w.tools.length - 1], ['finish'], 'wrapping up offers only finish');
});

test('routines: schedules in plain words', () => {
  const now = new Date(2026, 10, 9, 7, 0).getTime();   // Monday 07:00 local
  assert.deepEqual(X.parseSchedule('every weekday at 8am', now), { kind: 'daily', time: '08:00', days: [1, 2, 3, 4, 5] });
  assert.deepEqual(X.parseSchedule('every 2 hours', now), { kind: 'interval', everyMin: 120 });
  assert.deepEqual(X.parseSchedule('every Sunday evening', now), { kind: 'daily', time: '18:00', days: [7] });
  assert.deepEqual(X.parseSchedule('every month on the 1st at 9:30', now), { kind: 'monthly', day: 1, time: '09:30' });
  assert.ok(X.parseSchedule('every 2 minutes', now).error, 'too often is refused');
  assert.equal(X.parseSchedule('buy milk', now), null);
  const s = X.parseSchedule('every weekday at 8am', now);
  assert.equal(new Date(X.nextRun(s, now)).getHours(), 8);
  const fri = new Date(2026, 10, 13, 9, 0).getTime();   // Friday after 8: next is Monday
  assert.equal(new Date(X.nextRun(s, fri)).getDay(), 1);
  assert.equal(X.describeSchedule(s), 'Every weekday at 08:00');
  const m = X.parseSchedule('every month on the 31st at 9am', now);
  assert.equal(new Date(X.nextRun(m, new Date(2027, 1, 1).getTime())).getDate(), 28, 'a short month uses its last day');
  // "every 30 minutes" lands on the same slots whichever device works it out, and whenever
  const every = { kind: 'interval', everyMin: 30 }, t = Date.UTC(2026, 10, 18, 8, 7, 13);
  assert.equal(X.nextRun(every, t), Date.UTC(2026, 10, 18, 8, 30));
  assert.equal(X.nextRun(every, t + 11 * 60000), Date.UTC(2026, 10, 18, 8, 30), 'the other device, 11 minutes later, agrees');
  assert.equal(X.nextRun(every, Date.UTC(2026, 10, 18, 8, 30)), Date.UTC(2026, 10, 18, 9, 0), 'right on a slot: the next one');
});

test('routines: fire once when due, even after a long sleep; only the device the cloud picks runs it', async () => {
  const e = extrasFor({ claim: async (r, due) => due % 2 === 0 }); await e.ex.init();
  const base = new Date(2026, 10, 9, 7, 0).getTime(); e.at(base);
  const { routine } = e.ex.saveRoutine({ goal: 'News summary', scheduleText: 'every day at 8am' });
  assert.ok(routine && routine.nextAt > base);
  e.at(routine.nextAt + 3 * 86400000);   // Atlas was closed for three days
  await e.ex.tick();
  assert.equal(e.fired.length, 1, 'missed runs fire once, not three times');
  assert.ok(routine.nextAt > routine.nextAt - 1 && routine.nextAt > Date.now() - 1e12);
  const e2 = extrasFor({ claim: async () => false }); await e2.ex.init(); e2.at(base);
  const r2 = e2.ex.saveRoutine({ goal: 'x', scheduleText: 'every hour' }).routine;
  e2.at(r2.nextAt + 1); await e2.ex.tick();
  assert.equal(e2.fired.length, 0, 'the other device holds this firing');
  assert.ok(r2.nextAt > base + 3600000, 'and this device moves on to the next one');
  const e3 = extrasFor({ device: 'phone-1', claim: async () => { throw new Error('offline'); } }); await e3.ex.init(); e3.at(base);
  const r3 = e3.ex.saveRoutine({ goal: 'x', scheduleText: 'every hour' }).routine;
  e3.at(r3.nextAt + 1); await e3.ex.tick();
  assert.equal(e3.fired.length, 1, 'offline: the device that made it runs it');
});

test('skills: saved, found by name, drafted from a finished job, offered to bots', async () => {
  const e = extrasFor(); await e.ex.init();
  const sk = e.ex.saveSkill({ name: 'Weekly money check', when: 'When asked about spending', steps: '1. Read the Log\n2. Sum it' });
  assert.equal(e.ex.skill('weekly money check').id, sk.id);
  assert.equal(e.ex.saveSkill({ name: 'Weekly money check', when: 'x', steps: 'y' }).id, sk.id, 'same name updates');
  assert.ok(X.skillsIndex(e.ex.data.skills).includes('Weekly money check'));
  const d = X.draftSkill({ goal: 'Find a gift', details: 'for mum', plan: [{ text: 'Search shops' }, { text: 'Compare' }], steps: ['You approved: Buy it'], report: '## Gift\n## Sources' });
  assert.ok(d.steps.startsWith('1. Search shops') && d.permissions.includes('Buy it') && d.output.includes('Gift'));
  const w = world([
    turn(call('use_skill', { name: 'weekly money' })),
    (brief) => turn(call('finish', { report: 'ok' })),
  ], { extras: e.ex });
  await w.bots.init(); w.bots.deploy('Money', ''); await settle(w);
  assert.ok(w.notes.some((x) => Array.isArray(x) && String(x[0]).startsWith('Skill: Weekly money check')), 'the skill text reaches the bot');
  assert.ok(w.tools[0].includes('use_skill'));
});

test('specialists: a bot hands parts to helpers, waits without holding a slot, and gets their reports', async () => {
  const e = extrasFor(); await e.ex.init();
  const w = world([
    turn(call('delegate', { specialist: 'Research Bot', task: 'Find three laptops' }), call('delegate', { specialist: 'Finance Bot', task: 'Check prices' })),
    turn(call('wait_for_helpers')),
    // the two helpers run (each its own fresh session)
    turn(call('finish', { report: 'Laptops: A, B, C' })),
    turn(call('finish', { report: 'Prices: 500, 600' })),
    (brief) => { assert.ok(brief.includes('Laptops: A, B, C') && brief.includes('Prices: 500, 600'), 'helper reports are in the notes'); return turn(call('finish', { report: 'Final.' })); },
  ], { extras: e.ex, ids: true });
  await w.bots.init(); const j = w.bots.deploy('Laptop plan', ''); await settle(w);
  assert.equal(j.state, B.S.COMPLETED, j.state);
  assert.equal(j.children.length, 2);
  const kids = j.children.map((id) => w.bots.job(id));
  assert.ok(kids.every((k) => k.state === B.S.COMPLETED && k.parent === j.id && k.depth === 1 && k.maxCalls > 0));
  assert.equal(w.posts.length, 1, 'only the main bot posts in the chat');
  assert.deepEqual(w.bots.visible().map((x) => x.id), [j.id], 'helpers show inside their bot, not in the list');
  assert.ok(w.tools[0].includes('delegate') && !w.tools[0].includes('bot_remember'), 'the main bot can delegate; only specialists keep notes');
  assert.ok(!w.tools[1].includes('run_command') && w.tools[1].includes('web_search') && w.tools[1].includes('bot_remember'), 'Research Bot only gets its own kinds of tools');
});

test('helpers: bounded only against runaway loops (12 per bot, 4 levels), and stopping a bot stops its helpers', async () => {
  const e = extrasFor(); await e.ex.init();
  const p = { toolCalls: B.MAX_TOOL_CALLS - 10, workMs: 0 };
  assert.equal(B.childBudget(p).maxCalls, 8, 'a helper gets a share of what is left (never less than 8)');
  const w = world([
    turn(...Array.from({ length: 13 }, (_, i) => call('delegate', { specialist: 'Research Bot', task: 'Part ' + (i + 1) }))),
    (brief) => { return turn(call('ask_user', { question: 'hold on' })); },
  ], { extras: e.ex, ids: true });
  await w.bots.init(); const j = w.bots.deploy('Big', '');
  await settle(w);
  assert.equal(j.children.length, 12, 'the thirteenth is refused (a ceiling against runaway loops, not a working limit)');
  assert.ok(w.notes.some((x) => Array.isArray(x) && x.some((o) => String(o).includes("that's the limit"))));
  w.bots.stop(j.id); await settle(w);
  assert.ok(j.children.map((id) => w.bots.job(id)).every((k) => B.FINAL.has(k.state)), 'helpers stop with it');
  assert.equal(B.MAX_DEPTH, 4);
});

test('specialist notes and the synced copies of skills, routines, team and rules', async () => {
  const e = extrasFor(); await e.ex.init();
  const sp = e.ex.specialist('Research Bot');
  assert.equal(e.ex.specialistNote(sp.id, 'User prefers UK sources'), 'Noted for next time.');
  assert.ok(X.specialistPrompt(sp).includes('UK sources'));
  e.ex.specialistNote(sp.id, 'UK sources', true); assert.equal(sp.notes.length, 0);
  e.ex.addRule('Never book anything on a Sunday');
  assert.ok(X.rulesPrompt(e.ex.data.policy).includes('Sunday'));
  const kinds = new Set(e.ex.synced().map((x) => x.kind));
  assert.ok(['specialist', 'botpolicy'].every((k) => kinds.has(k)));
  // another device's newer copy wins; an older one doesn't
  const other = extrasFor({ device: 'phone-1' }); await other.ex.init();
  const sk = e.ex.saveSkill({ name: 'A', when: 'w', steps: 's' });
  other.ex.applySynced('skill', sk.id, { ...sk });
  assert.equal(other.ex.skill('A').id, sk.id);
  other.ex.applySynced('skill', sk.id, { ...sk, name: 'Old', updatedAt: sk.updatedAt - 1 });
  assert.equal(other.ex.skill('A').name, 'A', 'an older copy does not win');
  other.ex.applySynced('skill', sk.id, null);
  assert.equal(other.ex.data.skills.length, 0, 'deleted there, deleted here');
  // a routine made on the PC keeps the phone's own run times when the phone runs it
  for (const x of e.ex.synced()) assert.ok(!/api.?key|secret|password|"code"/i.test(JSON.stringify(x.json)), 'nothing secret syncs');
});

test('a command is only read-only when nothing can hide a second command in it', () => {
  for (const c of ['Get-Date\nRemove-Item -Recurse X', 'Get-Date\r\nRemove-Item X', 'echo $(Remove-Item X)', 'echo `Remove-Item X`', 'Get-ChildItem; Remove-Item X',
    'dir | Remove-Item', 'echo hi > C:\\x.txt', 'type x < y', 'Get-Date & del x', 'Get-Item {Remove-Item X}', 'echo (Remove-Item X)'])
    assert.ok(B.approvalFor('run_command', { command: c }), 'needs an OK: ' + JSON.stringify(c));
  for (const c of ['Get-Date', 'dir C:\\Users', 'Get-ChildItem C:\\temp', 'whoami', 'ipconfig'])
    assert.equal(B.approvalFor('run_command', { command: c }), null, 'read-only: ' + c);
  assert.ok(B.approvalFor('type_text', { text: 'hello\n' }), 'typing a new line presses Enter, so it needs an OK');
  assert.equal(B.approvalFor('type_text', { text: 'hello' }), null);
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

test('this PC: a mixture-of-experts name (8x7b) counts all its experts, so it is not judged a small model', () => {
  const moe = fitR.sizeFromName('mixtral-8x7b-instruct-v0.1-q4_k_m');
  assert.ok(moe > 30000 && moe < 34000, String(moe));   // ≈ 56 billion × 4.8 bits
  assert.ok(Math.abs(fitR.sizeFromName('qwen2.5-7b-instruct-q4_k_m') - 4005) < 50);
});

// ---- Atlas Bot's files: the page template is one file on both apps
test('the phone and the PC draw pages from the same template', async () => {
  const fs = await import('node:fs');
  const pc = fs.readFileSync(new URL('../renderer/templates/page.html', import.meta.url), 'utf8');
  const phone = fs.readFileSync(new URL('../../app/src/main/assets/page.html', import.meta.url), 'utf8');
  assert.equal(phone, pc);
  assert.ok(pc.includes('/*ATLAS_PAGE_DATA*/null') && pc.includes("default-src 'none'"));
});
test("a bot's files: the report footer lists what it made", async () => {
  globalThis.window = globalThis.window || {};
  const { filesFooter, FILE_TOOLS, FILES } = await import('../renderer/botfiles.js');
  assert.equal(filesFooter({ made: [] }), '');
  assert.match(filesFooter({ made: ['a.pdf', 'b.xlsx'] }), /^\n\n\*\*Files:\*\* a\.pdf, b\.xlsx \(open them in Atlas Bot › Library\)\.$/);
  assert.deepEqual([...FILES], ['workspace_list', 'workspace_read', 'workspace_write', 'make_document', 'make_spreadsheet', 'make_page']);
  for (const t of FILE_TOOLS) assert.ok(t.description.length > 20 && /^[a-z_]+$/.test(t.name));
});

// ------------------------------------------------------------------ the AI services: same catalogue as the phone, keys land right
test('the PC and the phone list the same services, addresses and default models', async () => {
  const fs = await import('node:fs');
  const kt = fs.readFileSync(new URL('../../app/src/main/java/com/anjy/atlas/Providers.kt', import.meta.url), 'utf8');
  const block = kt.slice(kt.indexOf('val builtIn = listOf('), kt.indexOf('/** Services the user added'));
  const phone = [...block.matchAll(/Provider\("([^"]+)", "[^"]*", "([^"]+)", ("[^"]*"|Gemini\.ENDPOINT), ("[^"]*"|Gemini\.DEFAULT_MODEL)/g)]
    .map((m) => ({ id: m[1], style: m[2], base: m[3].replace(/"/g, ''), model: m[4].replace(/"/g, '') }));
  const pc = brain.BUILT_IN.filter((p) => !p.local);
  assert.deepEqual(phone.map((p) => p.id), pc.map((p) => p.id), 'same services in the same order (the fallback order)');
  for (const p of phone) {
    const q = brain.byId(p.id);
    assert.equal(q.style, p.style, p.id);
    if (p.base !== 'Gemini.ENDPOINT') assert.equal(q.base, p.base, p.id);
    if (p.model !== 'Gemini.DEFAULT_MODEL') assert.equal(q.model, p.model, p.id);
  }
  for (const p of pc) if (!p.custom) { if (p.style !== 'gemini') assert.match(p.base, /^https:\/\/[^ ]+[^/]$/, p.id); assert.ok(p.model, p.id); assert.match(p.keyUrl, /^https:\/\//, p.id); }
});

test('a pasted key lands on the right service', () => {
  assert.equal(brain.keyTarget('gsk_abcdefghijklmnop', 'mistral'), 'groq');
  assert.equal(brain.keyTarget('sk-ant-api03-xyz', 'openai'), 'anthropic');
  assert.equal(brain.keyTarget('sk-1234567890abcdef', 'deepseek'), 'deepseek');
  assert.equal(brain.keyTarget('sk-1234567890abcdef', 'custom'), 'custom');
  assert.equal(brain.keyTarget('sk-proj-1234567890', 'anthropic'), 'openai');
  assert.equal(brain.keyTarget('abcdefghijklmnopqrstuvwxyz', 'cohere'), 'cohere');
});

test('services you add work like the built-in ones and go cleanly', () => {
  db.prefs = {};
  const p = brain.addCustom('My Server', 'https://llm.example.com/v1/', 'my-model');
  assert.ok(p.id.startsWith('c_')); assert.equal(brain.baseOf(p), 'https://llm.example.com/v1'); assert.equal(brain.modelOf(p), 'my-model');
  const q = brain.addCustom('Second', 'https://two.example.com/v1', 'm2');
  assert.notEqual(p.id, q.id);
  brain.addKey(p.id, 'key-for-my-server-123456');
  db.prefs.provider = p.id;
  assert.equal(brain.active().id, p.id, 'a service you added can be the one Atlas thinks with');
  brain.updateCustom(p.id, 'Renamed', 'https://llm.example.com/v1', 'other');
  assert.equal(brain.byId(p.id).name, 'Renamed'); assert.equal(brain.modelOf(brain.byId(p.id)), 'other');
  brain.removeCustom(p.id);
  assert.equal(brain.byId(p.id), undefined); assert.deepEqual(brain.keys(p.id), [], 'its keys go too');
  assert.ok(brain.byId(q.id)); brain.removeCustom('gemini'); assert.ok(brain.byId('gemini'), 'built-in services cannot be removed');
  db.prefs = {};
  brain.loadCustoms();
});

// ------------------------------------------------------------------ connections (MCP servers, web APIs)
import * as CN from '../renderer/connect.js';

test('connections: tool names fit every AI service (≤64, letters, numbers, _) and stay unique when cut', () => {
  assert.equal(CN.toolName('notes', 'add'), 'mcp__notes__add');
  assert.equal(CN.toolName('my-files', 'read.file'), 'mcp__my_files__read_file');
  const long1 = CN.toolName('server', 'a'.repeat(80) + '_one'), long2 = CN.toolName('server', 'a'.repeat(80) + '_two');
  assert.ok(long1.length <= 64 && long2.length <= 64 && long1 !== long2);
  assert.match(long1, /^[A-Za-z0-9_]+$/);
});

test('connections: schemas lose what Gemini refuses ($ref, allOf, anyOf, defaults, extra keys) but keep their meaning', () => {
  const s = CN.simpleSchema({ type: 'object', $defs: { Pt: { type: 'object', properties: { x: { type: 'number' } }, required: ['x'] } }, additionalProperties: false,
    properties: { at: { $ref: '#/$defs/Pt', description: 'where' }, kind: { anyOf: [{ type: 'string', enum: ['a', 'b'] }, { type: 'null' }] }, n: { type: ['integer', 'null'], default: 3 },
      both: { allOf: [{ type: 'object', properties: { p: { type: 'string' } } }, { properties: { q: { type: 'boolean' } }, required: ['q'] }] } }, required: ['at', 'ghost'] });
  assert.deepEqual(s.properties.at, { type: 'object', description: 'where', properties: { x: { type: 'number' } }, required: ['x'] });
  assert.deepEqual(s.properties.kind, { type: 'string', enum: ['a', 'b'], nullable: true });
  assert.deepEqual(s.properties.n, { type: 'integer', description: 'Default: 3', nullable: true });
  assert.deepEqual(Object.keys(s.properties.both.properties), ['p', 'q']);
  assert.deepEqual(s.required, ['at'], 'a required field that does not exist is dropped');
  assert.ok(!JSON.stringify(s).includes('additionalProperties') && !JSON.stringify(s).includes('$'));
});

test('connections: approvals follow each kind of action (W2 kinds), and the trust setting', () => {
  assert.equal(X.connectionCategory('call_api', { method: 'DELETE', path: '/items/1' }), 'delete');
  assert.equal(X.connectionCategory('call_api', { method: 'POST', path: '/v1/orders' }), 'purchase');
  assert.equal(X.connectionCategory('call_api', { method: 'POST', path: '/messages/send' }), 'message');
  assert.equal(X.connectionCategory('mcp__slack__post_message', {}), 'publish');
  assert.equal(X.connectionCategory('mcp__gmail__send_email', {}), 'message');
  assert.equal(X.connectionCategory('mcp__notes__save_note', {}), 'other');
  assert.equal(X.connectionCategory('run_command', {}), null);
  const idx = new Map([['mcp__n__get', { server: 'n', tool: 'get', readOnly: true, destructive: false, trust: 'reads' }], ['mcp__n__wipe', { server: 'n', tool: 'wipe', readOnly: true, destructive: true, trust: 'reads' }]]);
  assert.equal(CN.approvalFrom(idx, 'mcp__n__get', {}), null);
  assert.ok(CN.approvalFrom(idx, 'mcp__n__wipe', {}), 'marked read-only AND destructive: ask');
});

test('connections in a bot: tools appear while the bot runs; read-only ones go ahead, changes wait for the OK', async () => {
  const snap = { servers: [{ name: 'notes', status: 'off', trust: 'reads', chat: true, bots: true, tools: [] }], apis: [] };
  const toolsSeen = () => [{ name: 'web_search' }, ...CN.declarationsFrom(snap, 'bots')];
  const idx = () => { const m = new Map(); CN.declarationsFrom(snap, 'bots', m); return m; };
  const w = world([
    turn(call('mcp__notes__add', { a: 1, b: 2 })),
    turn(call('mcp__notes__save_note', { text: 'hello' })),
    (brief) => { assert.ok(brief.includes('Approved and done')); return turn(call('finish', { report: 'Saved.' })); },
  ], { tools: toolsSeen, extraApproval: (name, a) => CN.approvalFrom(idx(), name, a) });
  // the server connects after the app started: its tools must still reach the bot
  snap.servers[0].status = 'connected';
  snap.servers[0].tools = [{ name: 'add', inputSchema: { type: 'object', properties: { a: { type: 'number' }, b: { type: 'number' } } }, readOnly: true },
    { name: 'save_note', inputSchema: { type: 'object', properties: { text: { type: 'string' } } }, readOnly: false }];
  await w.bots.init();
  const j = w.bots.deploy('Note it', '');
  await settle(w);
  assert.ok(w.tools[0].includes('mcp__notes__add') && w.tools[0].includes('mcp__notes__save_note'), 'the bot was offered the connection tools');
  assert.deepEqual(w.ran, [['mcp__notes__add', false]], 'the read-only tool ran; the change did not');
  assert.equal(j.state, B.S.AWAITING_APPROVAL);
  w.bots.approve(j.id, true); await settle(w);
  assert.deepEqual(w.ran, [['mcp__notes__add', false], ['mcp__notes__save_note', true]]);
  assert.equal(j.state, B.S.COMPLETED);
});

// ------------------------------------------------------------------ email (mailtext.js; the phone's MailText.kt answers the same cases)
test('email: the shared cases in tools/mail-fixtures.json still hold (the phone checks the same file)', async () => {
  const { createRequire } = await import('node:module'); const req = createRequire(import.meta.url);
  const T = req('../mailtext.js'); const F = req('../../tools/mail-fixtures.json');
  for (const c of F.htmlToText) assert.equal(T.htmlToText(c.in), c.out);
  for (const c of F.stripQuoted) assert.deepEqual(T.stripQuoted(c.in), c.out);
  for (const c of F.quoteForReply) assert.equal(T.quoteForReply(c.body, c.original), c.out);
  for (const c of F.replySubject) assert.equal(T.replySubject(c.in), c.out);
  for (const c of F.parseQuery) assert.deepEqual(T.parseQuery(c.in), c.out);
  for (const c of F.presetFor) assert.deepEqual(T.presetFor(c.in), c.out);
  for (const c of F.parseId) assert.deepEqual(T.parseId(c.in), c.out);
  for (const c of F.splitAddresses) assert.deepEqual(T.splitAddresses(c.in), c.out);
  for (const c of F.addr) assert.equal(T.addr(c.in), c.out);
});
test('email: what the cases mean', async () => {
  const { createRequire } = await import('node:module'); const T = createRequire(import.meta.url)('../mailtext.js');
  assert.equal(T.stripQuoted('Yes!\n\nOn Mon, Anna <a@x> wrote:\n> lunch?').text, 'Yes!');
  assert.equal(T.stripQuoted('> a forward that is only quotes').hidden, false);
  assert.equal(T.htmlToText('<p>Caf&eacute;</p><script>x()</script>'), 'Café');
  assert.ok(T.presetFor('me@outlook.com').webmail && !T.presetFor('me@outlook.com').imap, 'Outlook.com goes to the bot browser');
  assert.deepEqual(T.presetFor('a@gmail.com').imap, { host: 'imap.gmail.com', port: 993, secure: true });
  assert.match(T.friendlyError({ authenticationFailed: true }, T.presetFor('a@gmail.com')), /Gmail turned down the password.*apppasswords/);
  assert.match(T.friendlyError({ code: 'ENOTFOUND' }), /Couldn't find the mail server/);
  assert.match(T.friendlyError({ code: 'EAUTH', response: '535 5.7.8 Username and Password not accepted' }, T.presetFor('x@yahoo.com')), /Yahoo Mail turned down/);
  assert.equal(T.splitAddresses('Anna'), null, 'a bare name is not an address');
});
test('email: sending and deleting need an OK, as a message and a delete; reading and tidying do not', async () => {
  const M = await import('../renderer/mail.js');
  assert.match(M.approvalFor('email_send', { draft_id: 'd9' }), /^Send the email draft d9$/);
  M.state.drafts.set('d1', { to: 'Anna <a@x.org>', subject: 'Re: Lunch' });
  assert.equal(M.approvalFor('email_send', { draft_id: 'd1' }), 'Send the email “Re: Lunch” to Anna <a@x.org>');
  assert.equal(M.approvalFor('email_delete', { ids: 'INBOX#1, INBOX#2' }), 'Delete 2 emails (to the trash)');
  for (const t of ['email_list', 'email_read', 'email_search', 'email_draft', 'email_mark', 'email_archive', 'email_move', 'email_save_attachment']) assert.equal(M.approvalFor(t, {}), null, t);
  assert.equal(M.category('email_send'), 'message'); assert.equal(M.category('email_delete'), 'delete');
  const BX = await import('../renderer/botx.js');
  assert.equal(BX.categoryFor('email_send', {}), 'message'); assert.equal(BX.categoryFor('email_delete', {}), 'delete');
  assert.deepEqual(M.TOOLS.map((t) => t.name), ['email_list', 'email_read', 'email_search', 'email_save_attachment', 'email_draft', 'email_send', 'email_mark', 'email_archive', 'email_move', 'email_delete']);
  for (const t of M.TOOLS) assert.ok(t.description.length > 20 && t.parameters.type === 'object', t.name);
});

// ------------------------------------------------------------------ trading: the risk engine (desktop/traderules.js)
test('trading: every fixture case gives the answer the phone must also give', async () => {
  const { createRequire } = await import('node:module');
  const req = createRequire(import.meta.url);
  const TR = req('../traderules.js');
  const fs = await import('node:fs');
  const fx = JSON.parse(fs.readFileSync(new URL('../../tools/trade-fixtures.json', import.meta.url), 'utf8'));
  assert.ok(fx.cases.length >= 25);
  for (const c of fx.cases) {
    const r = TR.check(c.order, c.inst, c.market, c.account, c.state, c.plan);
    assert.equal(r.ok, c.expect.ok, c.name);
    if (!r.ok) { assert.deepEqual(r.reasons.map((x) => x.code), c.expect.codes, c.name); continue; }
    assert.deepEqual(r.order, c.expect.order, c.name);
    assert.equal(r.needsApproval, c.expect.needsApproval, c.name);
    assert.equal(r.summary, c.expect.summary, c.name);
  }
  for (const r of fx.rounding) { assert.equal(TR.floorTo(r.value, r.step), r.floor); assert.equal(TR.roundTo(r.value, r.step), r.round); }
  for (const [h, m, d, want] of fx.hours) assert.equal(TR.inHours(h, m, d), want);
  for (const [sym, entry, want] of fx.symbols) assert.equal(TR.symbolAllowed(sym, entry), want, `${sym} vs ${entry}`);   // "ETH" must not allow ETHFIUSDT
  const crypto = await import('node:crypto');
  assert.equal(crypto.createHmac('sha256', fx.hmac.key).update(fx.hmac.data).digest('hex'), fx.hmac.hex);
  const b = fx.bybitPayload; assert.equal(TR.bybitPayload(b.ts, b.key, b.recv, b.query), b.expect);
});
test('trading: the stop switch crosses devices the same way on the phone (tools/trade-fixtures.json stopSync)', async () => {
  const { createRequire } = await import('node:module');
  const TR = createRequire(import.meta.url)('../traderules.js');
  const fs = await import('node:fs');
  const fx = JSON.parse(fs.readFileSync(new URL('../../tools/trade-fixtures.json', import.meta.url), 'utf8')).stopSync;
  for (const c of fx.stopIdOf) assert.equal(TR.stopIdOf(c.state), c.out, c.name);
  for (const c of fx.record) assert.deepEqual(TR.stopRecord(c.state), c.out, c.name);
  for (const c of fx.fromOther) assert.equal(TR.stopFromOther(c.state, c.rec), c.out, c.name);
  for (const c of fx.addStop) assert.deepEqual(TR.addStop(c.stops, c.id), c.out);
  // a record never lifts a stop, and a stop this device already had (and lifted here) doesn't come back
  assert.ok(fx.fromOther.length >= 100);
  assert.ok(fx.fromOther.filter((c) => c.rec && c.rec.killed === false).every((c) => c.out === null));
  assert.equal(TR.stopFromOther({ killed: false, stopId: 'stop-a', stops: ['stop-a'] }, { killed: true, stop: 'stop-a' }), null);
});
test('trading: a new day resets the count and the starting equity; losses are measured from it', async () => {
  const { createRequire } = await import('node:module');
  const TR = createRequire(import.meta.url)('../traderules.js');
  let s = { day: '2026-11-11', dayStartEquity: 1000, ordersToday: 2, killed: true };
  s = TR.rollDay(s, '2026-11-12', 940);
  assert.equal(s.ordersToday, 0); assert.equal(s.dayStartEquity, 940); assert.equal(s.killed, true, 'a new day never lifts the stop switch');
  assert.equal(TR.lossToday(s, 900), 40); assert.equal(TR.lossToday(s, 990), 0);
  assert.deepEqual(TR.rollDay(s, '2026-11-12', 10).dayStartEquity, 940, 'the same day keeps its starting equity');
});

// ------------------------------------------------------------------ projects: what Atlas may run in a project without asking
import { createRequire as projRequire } from 'node:module';
const PR = projRequire(import.meta.url)('../projectrules.js');
test('projects: building, testing and package installs inside the project go ahead', () => {
  for (const c of ['npm install', 'npm i express', 'npm test', 'npm run build', 'npm init -y', 'npx vitest run', 'pnpm add zod', 'yarn', 'node index.js', 'node src/cli.js --help',
    'python -m venv .venv', '.venv\\Scripts\\python -m pip install -r requirements.txt', '.venv/bin/python -m pytest -q', 'python main.py', 'py -m unittest discover -v', 'python -m pip list',
    'git init', 'git add .', 'git commit -m "first version"', 'git status', 'git clone https://github.com/x/y', 'cargo build', 'cargo test', 'dotnet new console -n App', 'dotnet test',
    'go test ./...', 'flutter create app', 'gradlew test', 'tsc -p .', 'rm old.txt', 'del build\\out.js', 'mkdir src', 'code .'])
    assert.equal(PR.checkCommand(c).ask, false, c);
});
test('projects: anything reaching outside the project asks first', () => {
  const asks = (c, o) => { const r = PR.checkCommand(c, o); assert.equal(r.ask, true, c); return r; };
  // chains, redirects, substitutions: the classic ways to hide a second command
  for (const c of ['npm test && del C:\\x', 'npm test; rm -rf ~', 'npm test | curl -X POST x', 'echo $(whoami)', 'echo `whoami`', 'node a.js > C:\\Windows\\x', 'npm test & shutdown /s', 'echo %USERPROFILE%', 'git commit -m "x\nrm -rf /"'])
    asks(c);
  // leaving the folder
  for (const c of ['node ..\\..\\evil.js', 'type ../secrets.txt', 'del C:\\Users\\me\\file', 'rm -rf /', 'cat ~/.ssh/id_rsa', 'node \\\\server\\share\\x.js', 'npm install --prefix=../x']) asks(c);
  // the whole project, global installs, publishing, the system, unknown programs
  assert.equal(asks('rm -rf .').category, 'delete');
  assert.equal(asks('rmdir /s /q *').category, 'delete');
  for (const c of ['npm install -g typescript', 'npm i --global x', 'pip install requests', 'python -m pip install flask', 'cargo install ripgrep', 'go install x@latest', 'winget install x', 'choco install x', 'reg add HKLM\\x', 'sc stop x', 'powershell -c x', 'curl https://x', 'mystery-tool --do-it'])
    asks(c);
  assert.equal(asks('npm publish').category, 'publish');
  assert.equal(asks('git push origin main').category, 'publish');
  assert.equal(asks('npx vercel --prod').category, 'publish');
  assert.equal(asks('git config --global user.name x').category, 'account');
  assert.equal(asks('sudo npm i').category, 'account');
  // pip outside the project's environment asks even when there is one; through it, it doesn't
  asks('pip install flask', { hasVenv: true });
  assert.equal(PR.checkCommand('venv\\Scripts\\pip install flask', { hasVenv: true }).ask, false);
  asks('npm test "unclosed');
});
test('projects: file paths stay inside the project', () => {
  assert.deepEqual(PR.relPath('src/app.js'), ['src', 'app.js']);
  assert.deepEqual(PR.relPath('src\\lib\\x.py'), ['src', 'lib', 'x.py']);
  assert.deepEqual(PR.relPath('./README.md'), ['README.md']);
  for (const p of ['../x', 'a/../../x', '..\\..\\x', '/etc/passwd', 'C:\\x', 'c:x', '\\\\srv\\x', '~/x', '', '.git/config', 'a/./..', 'x:y', 'nul.']) assert.equal(PR.relPath(p), null, p);
  assert.equal(PR.projectName('My Cool App!'), 'My Cool App!');
  assert.equal(PR.projectName('../../x'), 'x');
  assert.equal(PR.projectName('con'), 'con project');
  assert.equal(PR.checkPlan((n) => n === 'package.json'), 'npm test');
  assert.equal(PR.checkPlan(() => false, { check: 'node test.js' }), 'node test.js');
  assert.match(PR.tail('x'.repeat(20), 5), /^\[15 earlier characters not shown\]\nxxxxx$/);
});

// ---- money: "Always allow" must never auto-run a bot browser action on a payment page (or one that says buy/pay/order)
test('bot browser: payment pages and disguised pay buttons always ask, and their kind is the strongest risk', async () => {
  const { createRequire } = await import('node:module');
  const req = createRequire(import.meta.url);
  req.cache[req.resolve('electron')] = { id: 'electron', filename: 'electron', loaded: true, exports: { app: { on() {}, getPath: () => '/tmp' }, session: {}, BrowserWindow: class {} } };
  const B = req('../botbrowser.js');
  const X = await import('../renderer/botx.js');
  // the address
  assert.ok(B.paymentPage({ url: 'https://shop.example/checkout/step-2' }));
  assert.ok(B.paymentPage({ url: 'https://shop.example/payment?id=4' }));
  assert.ok(B.paymentPage({ url: 'https://shop.example/cart/pay' }));
  assert.ok(!B.paymentPage({ url: 'https://news.example/paying-for-college' }));
  assert.ok(!B.paymentPage({ url: 'https://payments.example/' }));          // the host alone doesn't count
  assert.ok(B.paymentPage({ url: 'https://news.example/a', pay: true }));   // card fields on the page
  // a pay button dressed up as "Reply": its form submits to /orders/pay
  const disguised = { url: 'https://mail.example/inbox', pay: false, el: 'reply-btn', form: '/orders/pay Place order' };
  assert.ok(B.moneyRisk('browser_click', disguised));
  assert.equal(X.strongestCategory(B.riskText('Reply', disguised) + ' ' + B.moneyAsk('browser_click', { target: '12' }, disguised, 'mail.example'), 'browser_click'), 'purchase');
  assert.ok(B.moneyRisk('browser_click', { url: 'https://x.example/', el: 'Continue checkout-submit', form: '' }));
  // every listed action on a payment page asks; reading and scrolling never count
  const pay = { url: 'https://shop.example/checkout', pay: false, el: '', form: '' };
  for (const t of ['browser_click', 'browser_type', 'browser_press', 'browser_select', 'browser_fill_login']) assert.ok(B.moneyRisk(t, pay), t);
  assert.ok(!B.moneyRisk('browser_read', pay)); assert.ok(!B.moneyRisk('browser_scroll', pay));
  assert.ok(!B.moneyRisk('browser_click', { url: 'https://news.example/a', el: 'next', form: 'Search' }));
  // the strongest risk wins: the label says message, the form says delete
  assert.equal(X.strongestCategory(B.riskText('Send', null, { formSubmit: 'Delete account', formAction: '/x' }), 'browser_click'), 'delete');
});

test('Always Allow never auto-runs a money action: it waits for a tap', async () => {
  const e = extrasFor(); await e.ex.init();
  e.ex.setMode('purchase', 'allow'); e.ex.setMode('message', 'allow');   // the user always allows purchases and messages
  // the page check (botbrowser.check) says: this "Reply" button is on a payment page
  const checkApproval = async (c) => (c.name === 'browser_click' ? { what: 'Click “Reply” on a payment page on shop.example', mustAsk: true, riskText: 'Reply /orders/pay payment' } : null);
  const w = world([
    turn(call('browser_click', { target: '7' })),
    turn(call('finish', { report: 'Paid.' })),
  ], { extras: e.ex, tools: [{ name: 'browser_click' }], checkApproval });
  await w.bots.init(); const j = w.bots.deploy('Reply to the seller', ''); await settle(w);
  assert.equal(j.state, B.S.AWAITING_APPROVAL, 'it waits for a tap despite Always Allow');
  assert.equal(j.approval.category, 'purchase', 'its kind is the strongest risk: money, not "message"');
  assert.deepEqual(w.ran, [], 'nothing clicked yet');
  w.bots.approve(j.id, true); await settle(w);
  assert.deepEqual(w.ran, [['browser_click', true]], 'clicked once, after the tap');
  assert.equal(j.state, B.S.COMPLETED);
});
test('projects in the chat: through the capability registry, a risky step waits for the yes; a safe one just runs', async () => {
  const calls = [];
  globalThis.window.atlasHost.projects = {
    run: async (name, args, approved) => { calls.push([name, args.command, approved]); return /push/.test(args.command || '') && !approved ? { needs: 'Run this in the project "X" (it sends your code out (push)): git push', category: 'publish', out: '' } : { out: 'ran ' + args.command }; },
    check: async () => null, list: async () => [{ name: 'X', status: 'checks pass' }],
  };
  const aware = await import('../renderer/aware.js');
  const P = await import('../renderer/projects.js');
  P.registerProjects(aware);
  assert.ok(aware.chatTools().some((t) => t.name === 'project_run') && aware.botTools().some((t) => t.name === 'project_check'));
  assert.equal((await aware.run({ name: 'project_run', args: { project: 'X', command: 'npm test' } })).out, 'ran npm test');
  const r = await aware.run({ name: 'project_run', args: { project: 'X', command: 'git push' } });
  const ref = (r.out.match(/\[(chat\d+)\]/) || [])[1];
  assert.ok(ref, r.out); assert.deepEqual(calls.at(-1), ['project_run', 'git push', false]);
  aware.heard('yes, push it');   // the user's own words (aware.consent)
  const yes = await aware.run({ name: 'answer_approval', args: { ref, yes: true } });
  assert.equal(yes.out, 'ran git push'); assert.deepEqual(calls.at(-1), ['project_run', 'git push', true]);
  // the user's standing choice "always allow" runs it straight away
  aware.use({ mode: () => 'allow' });
  assert.equal((await aware.run({ name: 'project_run', args: { project: 'X', command: 'git push' } })).out, 'ran git push');
  aware.use({ mode: () => 'ask' });
  delete globalThis.window.atlasHost.projects;
});

// ------------------------------------------------------------------ Atlas Bot v2: the model and rules (crew.js) against the shared fixtures
test('bots v2: crew.js gives every answer in tools/bots2-fixtures.json (the phone checks the same file)', async () => {
  const C = await import('../renderer/crew.js');
  const fs = await import('node:fs');
  const F = JSON.parse(fs.readFileSync(new URL('../../tools/bots2-fixtures.json', import.meta.url), 'utf8'));
  for (const c of F.capabilityOf) assert.deepEqual(C.capabilityOf(c.tool), c.out, c.tool);
  assert.deepEqual(C.defaultAccess(), F.defaultAccess);
  for (const c of F.normalizeBot) assert.deepEqual(JSON.parse(JSON.stringify(C.normalizeBot(c.in, c.now, c.id))), c.out, c.id);
  const bots = F.normalizeBot.map((c) => c.out);
  for (const c of F.allows) assert.equal(C.allows(bots[c.botIndex], c.tool, () => true, c.access), c.out, `${c.botIndex} ${c.tool}`);
  for (const c of F.narrowAccess) assert.deepEqual(C.narrowAccess(c.parent, c.child), c.out);
  for (const c of F.budgetCheck) assert.deepEqual(C.budgetCheck(c.budget, c.u), c.out);
  for (const c of F.moneyAllowed) assert.equal(C.moneyAllowed(c.budget, c.u, c.amount), c.out);
  for (const c of F.agentSlice) assert.deepEqual(C.agentSlice(c.callsLeft, c.msLeft), c.out);
  for (const c of F.usageToday) assert.deepEqual(C.usageToday(c.u, c.day), c.out);
  for (const c of F.dayOf) assert.equal(C.dayOf(c.ms, c.offsetMin), c.out);
  for (const c of F.parsePlanTree) assert.deepEqual(C.parsePlanTree(c.text), c.out, c.text.slice(0, 40));
  for (const c of F.userSaid) assert.equal(C.userSaid(c.text), c.out, c.text);
  for (const c of F.consent) assert.deepEqual(C.consent(c.text), c.out, c.text);
  for (const c of F.dryRunSkips) assert.equal(C.dryRunSkips(c.tool, c.needsApproval), c.out, c.tool);
  for (const c of F.missionDefaults) assert.deepEqual(C.missionDefaults(JSON.parse(JSON.stringify(c.in))), c.out, c.in.id);
  // no plan cap: a 120-step nested plan keeps every step
  const long = Array.from({ length: 120 }, (_, i) => (i % 3 ? '  ' : '') + `[ ] step ${i}`).join('\n');
  assert.equal(C.parsePlanTree(long).length, 120);
});

// ------------------------------------------------------------------ 0.10.1: bots keep going until the job is done
test('bots: no fixed plan size, words are not a finished job, and unticked steps get one reminder', async () => {
  assert.ok(B.MAX_PLAN >= 50, 'plans can be long');
  assert.equal(B.textEndsJob(0, false), false, 'a bot that did some work and then talks has not finished');
  assert.equal(B.textEndsJob(1, false), false);
  assert.equal(B.textEndsJob(2, false), true, 'after two reminders its words are the report');
  assert.equal(B.textEndsJob(0, true), true, 'when the budget is spent, words are the report');
  assert.equal(B.remindOpenSteps(2, false, false), true);
  assert.equal(B.remindOpenSteps(2, true, false), false, 'only once');
  assert.equal(B.remindOpenSteps(0, false, false), false);
  assert.equal(B.remindOpenSteps(3, false, true), false, 'not when the budget is spent');
  assert.match(B.openStepsReminder(1), /1 step of your plan isn't ticked off/);
  assert.ok(B.MAX_TOOL_CALLS >= 300 && B.MAX_WORK_MS >= 120 * 60000, 'the default budget matches the user\'s "moderate" choice');
  // a 20-step plan is kept whole
  const w = world([
    turn(call('update_plan', { plan: Array.from({ length: 20 }, (_, i) => `[ ] Step ${i + 1}`).join('\n') })),
    turn(call('update_plan', { plan: Array.from({ length: 20 }, (_, i) => `[x] Step ${i + 1}`).join('\n') }), call('web_search', { query: 'x' })),
    turn(call('finish', { report: 'Done.' })),
  ]);
  await w.bots.init();
  const j = w.bots.deploy('A long job', '');
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
  assert.equal(j.plan.length, 20);
});

test('bots: talking without a tool after some work is nudged, not taken as the report', async () => {
  const w = world([
    turn(call('web_search', { query: 'flights' })),
    { calls: [], text: 'I found some flights. Next I will compare prices.' },
    (brief) => { assert.ok(brief.includes("the job isn't finished"), 'the nudge reaches the bot'); return turn(call('web_search', { query: 'prices' })); },
    turn(call('finish', { report: 'Cheapest: X.' })),
  ]);
  await w.bots.init();
  const j = w.bots.deploy('Find the cheapest flight', '');
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED);
  assert.equal(j.report, 'Cheapest: X.');
  assert.deepEqual(w.ran.map((r) => r[0]), ['web_search', 'web_search']);
});

// ------------------------------------------------------------------ Atlas Bot v2 triggers: the rules (desktop/renderer/trigrules.js)
test('triggers: every fixture case gives the answer the phone must also give', async () => {
  const R = await import('../renderer/trigrules.js');
  const F = JSON.parse((await import('node:fs')).readFileSync(new URL('../../tools/trigger-fixtures.json', import.meta.url), 'utf8'));
  for (const c of F.clean) { const o = R.cleanTrigger(c.input, c.i); assert.deepEqual(o, c.out); assert.equal(R.summary(o), c.summary); }
  for (const c of F.goal) { assert.equal(R.goalFor(c.bot, c.trigger, c.event), c.out); assert.equal(R.eventKey(c.bot.id, c.trigger.id, c.event), c.key); assert.equal(R.claimKey(c.bot.id, c.trigger.id, c.event), c.claim); }
  for (const c of F.where) assert.equal(R.where({ home: c.home || undefined }, c.device), c.out);
  for (const c of F.awake) assert.equal(R.awakeLabel(c.bot), c.out);
  for (const c of F.watchable) assert.equal(R.watchable(c.type, c.device), c.out);
  let s = {}; for (const c of F.dedupe) { const r = R.dedupe(s, c.key, c.at); s = r.state; assert.equal(r.fresh, c.fresh, c.key); }
  s = {}; for (const c of F.rate) { const r = R.rateAllow(s, 'b|t', c.perHour, c.at); s = r.state; assert.equal(r.ok, c.ok, String(c.at)); }
  s = {}; for (const c of F.schedule) { const r = R.scheduleDue(s, F.scheduleSpec, c.at); s = r.state; assert.equal(r.fire, c.fire); assert.equal(s.nextAt, c.nextAt); }
  s = {}; for (const c of F.email.steps) { const r = R.emailEvents(s, c.messages, F.email.cfg); s = r.state; assert.deepEqual(r.events, c.events); assert.deepEqual(r.state, c.state); }
  { const r = R.emailEvents(F.emailFolderChange.before, F.emailFolderChange.messages, F.emailFolderChange.cfg); assert.deepEqual(r.events, []); assert.deepEqual(r.state, F.emailFolderChange.state); }
  for (const P of [F.price, F.priceStartAbove]) { s = {}; for (const c of P.steps) { const r = R.priceEvents(s, c.price, c.at, P.cfg); s = r.state; assert.deepEqual(r.events, c.events); assert.deepEqual(r.state, c.state); } }
  for (const c of F.notification) { assert.equal(R.notificationMatches(c.cfg, c.n), c.out); assert.deepEqual(R.notificationEvent(c.n, c.at), c.event); }
  for (const c of F.glob) assert.equal(R.globMatch(c.pattern, c.name), c.out, c.pattern + ' ' + c.name);
  s = null; for (const c of F.file.steps) { const r = R.fileEvents(s, c.listing, c.at, F.file.cfg); s = r.state; assert.deepEqual(r.events, c.events); assert.deepEqual(r.state, c.state); }
  // the cases say what they should, not just what the code did
  assert.equal(F.email.steps[0].events.length, 0, 'the first look only sets the baseline');
  assert.ok(F.price.steps.some((x) => x.events.some((e) => /above 100000 \(now 100100\)/.test(e.title))), 'a level re-arms after the price falls back');
  assert.equal(F.priceStartAbove.steps[1].events.length, 0, 'already above at the first look: no event');
  assert.equal(F.file.steps.flatMap((x) => x.events).length, 1, 'one new pdf, once, after it settled; edits to an old file are not new');
});

test("a local model that can't use tools still answers, in words", async () => {
  const host = globalThis.window.atlasHost; const sent = [];
  db.prefs = { ...db.prefs, model_local: 'gemma3:4b' };
  host.fetch = async (url, o) => {
    const b = JSON.parse(o.body); sent.push(b);
    if (b.tools) return { status: 400, ok: false, text: '{"error":{"message":"registry.ollama.ai/library/gemma3:4b does not support tools","type":"api_error"}}' };
    return { status: 200, ok: true, text: '{"choices":[{"message":{"role":"assistant","content":"Hello there"}}]}' };
  };
  try {
    const t = await brain.session(brain.byId('local'), 'local', [], 'hi', []).call('sys');
    assert.equal(t.text, 'Hello there'); assert.equal(sent.length, 2); assert.ok(!sent[1].tools);
    // a cloud service's refusal is not retried this way
    host.fetch = async () => ({ status: 400, ok: false, text: '{"error":{"message":"model does not support tools"}}' });
    db.prefs.keys = { groq: [{ key: 'gsk_x', restUntil: 0, bad: false, fails: 0, problem: '' }] };
    await assert.rejects(brain.session(brain.byId('groq'), 'gsk_x', [], 'hi', []).call('sys'), /support tools/);
  } finally { delete host.fetch; }
});

test('a local model that timed out is not asked three more times', async () => {
  db.prefs = { model_local: 'qwen3:30b', keys: {} };
  const sess = { key: 'local' }; let n = 0;
  const slow = new brain.Failure('Local model took too long to answer.', 0, true);
  await assert.rejects(brain.callWithKeys(brain.byId('local'), sess, async () => { n++; throw slow; }, 0, { onStatus: () => {}, cancelled: () => false, wait: async () => {} }), /took too long/);
  assert.equal(n, 1);
});
