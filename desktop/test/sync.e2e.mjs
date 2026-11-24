// The PC's real sync code (renderer/sync.js) against a pretend cloud that follows the same rules as the real
// Supabase functions (sync_pull / sync_push2 / sync_time: newest edit wins, a dead heat goes to the higher device id,
// times are clamped to the cloud's clock, a record longer than 1,500,000 characters is refused). The "phone" here
// writes into the pretend cloud directly, encrypted with the same code, as the phone's own sync would.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';

const files = new Map();
const cloud = { rows: new Map(), rev: 0, clockAhead: 0, paused: false, calls: [], hidden: new Set(), maxBody: 0 };
const realNow = Date.now;                 // the cloud keeps real time, whatever the PC's clock says
const now = () => realNow() + cloud.clockAhead;
function row(owner, it, rev) { return { owner, kind: it.kind, id: it.id, updated_ms: it.updated_ms, deleted: !!it.deleted, payload: it.payload || '', device: it.device || '', rev }; }
function push2(owner, items) {
  if (items.length > 500) throw Object.assign(new Error('send at most 500 records at a time'), { status: 400 });
  const n = now(), rejected = [];
  for (const it of items) {
    if ((it.payload || '').length > 1500000) throw Object.assign(new Error('bad record'), { status: 400 });
    const ms = Math.min(Math.max(Number(it.updated_ms) || 0, 0), n);
    const k = owner + '|' + it.kind + '|' + it.id, s = cloud.rows.get(k);
    if (!s || s.updated_ms < ms || (s.updated_ms === ms && s.device <= (it.device || ''))) cloud.rows.set(k, row(owner, { ...it, updated_ms: ms }, ++cloud.rev));
    else rejected.push({ kind: it.kind, id: it.id });
  }
  return { now: n, rejected, rev: cloud.rev };
}
function pull(owner, since, limit) {
  return [...cloud.rows.values()].filter((r) => r.owner === owner && r.rev > since && !cloud.hidden.has(r.rev))
    .sort((a, b) => a.rev - b.rev).slice(0, Math.min(Math.max(limit, 1), 1000))
    .map(({ kind, id, updated_ms, deleted, payload, device, rev }) => ({ kind, id, updated_ms, deleted, payload, device, rev }));
}
globalThis.window = {
  addEventListener() {},
  atlasHost: {
    read: async (n) => files.get(n) ?? null,
    write: async (n, t) => { files.set(n, t); return true; },
    info: async () => ({ version: '9.9.9' }),
    fetch: async (url, opts) => {
      const fn = url.split('/').pop(); const body = opts.body ? JSON.parse(opts.body) : {};
      cloud.calls.push(fn); cloud.maxBody = Math.max(cloud.maxBody, (opts.body || '').length);
      if (cloud.paused) return { ok: false, status: 540, text: 'Project paused' };
      try {
        if (fn === 'sync_time') return { ok: true, status: 200, text: String(now()) };
        if (fn === 'sync_pull') return { ok: true, status: 200, text: JSON.stringify(pull(body.p_owner, body.p_since, body.p_limit)) };
        if (fn === 'sync_push2') return { ok: true, status: 200, text: JSON.stringify(push2(body.p_owner, body.p_items)) };
        return { ok: false, status: 404, text: 'Could not find the function' };
      } catch (e) { return { ok: false, status: e.status || 500, text: e.message }; }
    },
  },
};
const store = await import('../renderer/store.js');
const sync = await import('../renderer/sync.js');
await store.load();
store.db.state.deviceId = 'pc-device';
const CODE = sync.newCode();
await sync.link(CODE);
await new Promise((r) => setTimeout(r, 0));
const OWNER = await sync.owner(CODE);

// the "phone": writes records the way the phone's sync does
let phoneClock = 0;
async function phoneSends(kind, id, json, { deleted = false, at, device = 'phone-device' } = {}) {
  const payload = deleted ? '' : await sync.encrypt(CODE, JSON.stringify(json));
  return push2(OWNER, [{ kind, id, updated_ms: at ?? now() + (phoneClock += 1), deleted, payload, device }]);
}
async function cloudHas(kind, id) {
  const r = cloud.rows.get(OWNER + '|' + kind + '|' + id); if (!r) return null;
  return r.deleted ? { deleted: true } : JSON.parse(await sync.decrypt(CODE, r.payload));
}
const state = async () => JSON.parse(files.get('sync-state.json') || '{}');

test('a memory from the phone arrives; one from the PC goes up', async () => {
  await phoneSends('memory', 'm1', { id: 'm1', text: 'My chess goal is 1500', at: now() - 1000 });
  store.db.state.memories.push({ id: 'p1', text: 'Gym at 7', at: now() });
  const r = await sync.runOnce();
  assert.match(r, /^Synced/);
  assert.ok(store.db.state.memories.find((m) => m.id === 'm1'), 'the phone memory is here');
  assert.equal((await cloudHas('memory', 'p1')).text, 'Gym at 7', 'the PC memory is in the cloud');
});

test('two sends numbered out of order: the late one is still taken in (the pull looks back a little)', async () => {
  // the cloud numbers a phone record 1 below another, but makes it visible only after the PC has pulled the other one
  const hiddenRev = cloud.rev + 1;
  await phoneSends('memory', 'late', { id: 'late', text: 'numbered first, shown late', at: now() });
  await phoneSends('memory', 'early', { id: 'early', text: 'numbered second, shown first', at: now() });
  cloud.hidden.add(hiddenRev);
  await sync.runOnce();
  assert.ok(store.db.state.memories.find((m) => m.id === 'early'));
  assert.ok(!store.db.state.memories.find((m) => m.id === 'late'), 'not visible yet');
  cloud.hidden.delete(hiddenRev);          // its sending finished
  await sync.runOnce();
  assert.ok(store.db.state.memories.find((m) => m.id === 'late'), 'the late record was not skipped');
});

test('records already taken in are not applied twice by the look-back', async () => {
  let applied = 0;
  sync.registerKind('counter', { list: () => ({}), apply: () => { applied++; }, absentMeansDeleted: false });
  await phoneSends('counter', 'c1', { n: 1 });
  await sync.runOnce(); await sync.runOnce(); await sync.runOnce();
  assert.equal(applied, 1);
});

test('a kind made by a newer Atlas is kept and taken in once this Atlas knows it', async () => {
  await phoneSends('futurekind', 'f1', { hello: 'from a newer Atlas' });
  await sync.runOnce();
  assert.equal(sync.status.waiting, 1, 'one record waits for a newer Atlas');
  const got = [];
  sync.registerKind('futurekind', { list: () => ({}), apply: (id, rec) => { got.push([id, rec]); }, absentMeansDeleted: false });
  await sync.runOnce();
  assert.deepEqual(got, [['f1', { hello: 'from a newer Atlas' }]]);
  assert.equal(sync.status.waiting, 0);
});

test('a record that fails to merge is tried again, not lost', async () => {
  let fail = true; const got = [];
  sync.registerKind('flaky', { list: () => ({}), apply: (id, rec) => { if (fail) throw new Error('busy'); got.push(rec.v); }, absentMeansDeleted: false });
  await phoneSends('flaky', 'x', { v: 7 });
  await sync.runOnce();
  assert.deepEqual(got, []);
  fail = false;
  await sync.runOnce();
  assert.deepEqual(got, [7]);
});

test('a kind that keeps its own newer copy sends it back, so the devices do not drift apart', async () => {
  // like a bot skill: the copy with the later edit time wins, whichever device's sync saw it last
  const mine = { s1: { id: 's1', v: 'laptop edit', updatedAt: 200 } };
  sync.registerKind('skillish', { list: () => ({ ...mine }), apply: (id, rec) => { if (rec && (mine[id] ? rec.updatedAt >= mine[id].updatedAt : true)) { mine[id] = rec; return undefined; } return sync.KEPT_MINE; }, absentMeansDeleted: false });
  await sync.runOnce();
  assert.equal((await cloudHas('skillish', 's1')).v, 'laptop edit');
  // the phone (its clock behind) sends an older edit after ours: it lands in the cloud last
  await new Promise((r) => setTimeout(r, 5));
  await phoneSends('skillish', 's1', { id: 's1', v: 'older phone edit', updatedAt: 100 }, { at: now() });
  assert.equal((await cloudHas('skillish', 's1')).v, 'older phone edit', 'the cloud took the phone\'s send');
  await new Promise((r) => setTimeout(r, 5));
  await sync.runOnce();
  assert.equal(mine.s1.v, 'laptop edit', 'the newer copy stays here');
  assert.equal((await cloudHas('skillish', 's1')).v, 'laptop edit', 'and goes back up, so the phone gets it too');
});

test('a huge chat message is cut short for the phone; an oversized record is reported, and syncing carries on', async () => {
  const big = 'x'.repeat(1200000);
  store.db.chat.push({ id: 'huge', user: true, text: big, at: now() });
  sync.registerKind('blob', { list: () => ({ b1: { data: 'y'.repeat(1200000) } }), apply: () => {}, absentMeansDeleted: false });
  store.db.state.memories.push({ id: 'after', text: 'still syncs', at: now() });
  const r = await sync.runOnce();
  assert.match(r, /^Synced/, r);
  const c = await cloudHas('chat', 'huge');
  assert.ok(c && c.text.length < big.length && c.text.endsWith('(cut short: too long to sync)'));
  assert.equal(await cloudHas('blob', 'b1'), null, 'too big: not sent');
  assert.equal(sync.status.tooBig, 1);
  assert.equal((await cloudHas('memory', 'after')).text, 'still syncs');
  assert.ok(cloud.maxBody < 2600000, 'each send stays a sensible size: ' + cloud.maxBody);
});

test('many changes go up in several sends', async () => {
  for (let i = 0; i < 950; i++) store.db.state.memories.push({ id: 'bulk' + i, text: 'note ' + i, at: now() });
  cloud.calls.length = 0;
  await sync.runOnce();
  assert.ok(cloud.calls.filter((c) => c === 'sync_push2').length >= 3);
  assert.equal((await cloudHas('memory', 'bulk949')).text, 'note 949');
});

test('more than 500 changes from the phone are all taken in (pages)', async () => {
  const items = [];
  for (let i = 0; i < 1200; i++) items.push({ kind: 'log', id: 'pl' + i, updated_ms: now(), payload: await sync.encrypt(CODE, JSON.stringify({ id: 'pl' + i, at: i, type: 'note', title: 'n' + i })), device: 'phone-device' });
  for (let i = 0; i < items.length; i += 500) push2(OWNER, items.slice(i, i + 500));
  await sync.runOnce();
  for (const id of ['pl0', 'pl599', 'pl1199']) assert.ok(store.db.state.log.find((l) => l.id === id), id);
});

test('your name and reminder behaviour come from the phone; the look stays this PC’s own', async () => {
  store.db.state.settings.theme = 'dark';
  await phoneSends('profile', 'me', { name: 'King Anjy', tone: 'strict', quietStart: '23:00', quietEnd: '07:00', graceMin: 20, followUps: 2 }, { at: now() + 5000 });
  await sync.runOnce();
  const s = store.db.state.settings;
  assert.deepEqual([s.name, s.tone, s.quietStart, s.quietEnd, s.graceMin, s.followUps, s.theme], ['King Anjy', 'strict', '23:00', '07:00', 20, 2, 'dark']);
  // an older Atlas sends only the name: the rest stays
  await phoneSends('profile', 'me', { name: 'Anjy' }, { at: now() + 9000 });
  await sync.runOnce();
  assert.deepEqual([s.name, s.tone], ['Anjy', 'strict']);
});

test('the phone shows up in Your Devices; an older phone is asked to update', async () => {
  await phoneSends('device', 'phone-device', { platform: 'android', name: 'OPPO Reno', app: '0.9.3', proto: 2, said: now() });
  await sync.runOnce();
  const o = sync.status.others.find((d) => d.id === 'phone-device');
  assert.ok(o && o.platform === 'android' && o.app === '0.9.3' && o.outdated);
  const me = await cloudHas('device', 'pc-device');
  assert.equal(me.platform, 'windows'); assert.equal(me.app, '9.9.9'); assert.equal(me.proto, sync.PROTOCOL);
});

test('a paused cloud is explained with the fix; syncing resumes when it wakes', async () => {
  store.db.state.memories.push({ id: 'while-paused', text: 'made while the cloud slept', at: now() });
  cloud.paused = true;
  await sync.runOnce();
  assert.equal(sync.status.problem.kind, 'paused');
  assert.match(sync.status.problem.fix, /Restore project/);
  cloud.paused = false;
  await sync.runOnce();
  assert.equal(sync.status.problem, null);
  assert.equal((await cloudHas('memory', 'while-paused')).text, 'made while the cloud slept');
});

test('a PC clock running an hour fast does not make its edits beat newer ones from the phone', async () => {
  cloud.clockAhead = 0;
  const real = Date.now;
  Date.now = () => real() + 3600000;            // this PC's clock is an hour ahead of the cloud
  try {
    store.db.state.memories.find((m) => m.id === 'p1').text = 'edited on the PC';
    await sync.runOnce();                         // asks the cloud's time afresh (the clock moved)
    await phoneSends('memory', 'p1', { id: 'p1', text: 'edited on the phone, later', at: real() + 10 }, { at: real() + 10 });
    await sync.runOnce();
    assert.equal(store.db.state.memories.find((m) => m.id === 'p1').text, 'edited on the phone, later');
  } finally { Date.now = real; }
});

test('the kept pull position moves on once the look-back time has passed', async () => {
  const before = (await state()).cursor;
  const real = Date.now;
  Date.now = () => real() + 120000;
  try { await sync.runOnce(); await sync.runOnce(); } finally { Date.now = real; }
  const st = await state();
  assert.ok(st.cursor > before, `${st.cursor} > ${before}`);
  assert.ok(Object.keys(st.seen).length < 50, 'the list of records taken in is pruned');
});

test('shared AI keys: off by default (nothing sent); on, the phone’s key arrives and this PC’s goes up, encrypted', async () => {
  const ks = await import('../renderer/keyshare.js');
  const brain = await import('../renderer/brain.js');
  ks.register();
  brain.addKey('groq', 'gsk_pc_key');
  await sync.runOnce();
  assert.equal(await cloudHas('keys', 'groq'), null, 'off: no keys leave this PC');
  const phoneRec = await ks.observe({}, [], ['gsk_phone_key'], now());
  await phoneSends('keys', 'groq', phoneRec);
  await sync.runOnce();
  assert.deepEqual(brain.keys('groq').map((e) => e.key), ['gsk_pc_key'], 'off: the phone’s key waits unused');
  await ks.set(true);
  assert.deepEqual(brain.keys('groq').map((e) => e.key).sort(), ['gsk_pc_key', 'gsk_phone_key'], 'on: the waiting key is taken in');
  await sync.runOnce(); await sync.runOnce();
  assert.deepEqual(await ks.present(await cloudHas('keys', 'groq')), ['gsk_pc_key', 'gsk_phone_key'], 'both keys are in the cloud record');
  const raw = cloud.rows.get(OWNER + '|keys|groq').payload;
  assert.ok(!raw.includes('gsk_'), 'the cloud holds only scrambled text');
  // removed on this PC: removed on the phone too (the record keeps only its fingerprint)
  brain.removeKey('groq', 'gsk_pc_key');
  await sync.runOnce();
  const rec = await cloudHas('keys', 'groq');
  assert.deepEqual(await ks.present(rec), ['gsk_phone_key']);
  assert.ok(!JSON.stringify(rec).includes('gsk_pc_key'));
  await ks.set(false);
});

test('bot jobs show on the other device, read-only: the phone’s arrive, this PC’s go up, and none run twice', async () => {
  const bm = await import('../renderer/botmirror.js');
  const pcJobs = [{ id: 'job-pc', goal: 'Compare three laptops', state: 'running', current: 'Reading a review', plan: [{ done: true }, { done: false }], createdAt: now(), updatedAt: now(), device: 'pc-device' }];
  bm.register({ visible: () => pcJobs, labels: { running: 'Working', completed: 'Done' } });
  await phoneSends('botview', 'job-phone', { id: 'job-phone', device: 'phone-device', platform: 'android', goal: 'Find a gym near me', state: 'completed', label: 'Done', current: '', done: 3, total: 3, report: '**Gym A** is best.', createdAt: now(), updatedAt: now() });
  await sync.runOnce();
  assert.deepEqual(bm.others().map((v) => [v.id, v.label]), [['job-phone', 'Done']]);
  const up = await cloudHas('botview', 'job-pc');
  assert.deepEqual([up.goal, up.label, up.done, up.total, up.platform], ['Compare three laptops', 'Working', 1, 2, 'windows']);
  await sync.runOnce();
  assert.equal(await cloudHas('botview', 'job-phone').then((r) => r.device), 'phone-device', 'the phone’s job is not sent back as this PC’s');
  // the phone removes its job: it goes from here too
  await phoneSends('botview', 'job-phone', null, { deleted: true });
  await sync.runOnce();
  assert.deepEqual(bm.others(), []);
});

test('New Chat on a PC whose clock runs slow still clears the phone’s latest messages', async () => {
  cloud.clockAhead = 0;
  const real = Date.now;
  Date.now = () => real() - 300000;             // this PC's clock is five minutes behind the cloud
  try {
    await sync.runOnce();                         // learns the clock is off
    await phoneSends('chat', 'slow-q', { id: 'slow-q', user: true, text: 'what time is it', source: '', at: real(), steps: [], files: 0 }, { at: real() });
    await sync.runOnce();
    assert.ok(store.db.chat.find((m) => m.id === 'slow-q'), 'the phone’s message arrived');
    await sync.noteDeleted('chat', store.db.chat.map((m) => m.id)); store.db.chat = [];
    await sync.runOnce();
    assert.deepEqual(await cloudHas('chat', 'slow-q'), { deleted: true }, 'the deletion was not refused as older than the message');
  } finally { Date.now = real; }
});

test('linking a fresh PC (nothing set) never resets the name and reminder settings the phone already has', async () => {
  const code2 = sync.newCode(); await sync.link(code2); const owner2 = await sync.owner(code2);
  Object.assign(store.db.state.settings, { name: '', tone: 'firm', quietStart: '22:30', quietEnd: '06:30', graceMin: 15, followUps: 1 });
  // the phone made the link: its first sync sent its settings with no edit time of their own (1), from a device id that loses a dead heat to this PC's
  const mine = { name: 'King Anjy', tone: 'gentle', quietStart: '23:15', quietEnd: '07:00', graceMin: 20, followUps: 2 };
  push2(owner2, [{ kind: 'profile', id: 'me', updated_ms: 1, payload: await sync.encrypt(code2, JSON.stringify(mine)), device: 'a-phone' }]);
  await sync.runOnce(); await sync.runOnce();
  const s = store.db.state.settings;
  assert.deepEqual([s.name, s.tone, s.quietStart, s.quietEnd, s.graceMin, s.followUps], ['King Anjy', 'gentle', '23:15', '07:00', 20, 2]);
  const r = cloud.rows.get(owner2 + '|profile|me');
  assert.deepEqual(JSON.parse(await sync.decrypt(code2, r.payload)), mine, 'the cloud still holds the phone’s settings');

  // a device with no name set changes its quiet hours: they come here, and this PC's name goes there
  const nameless = { tone: 'gentle', quietStart: '21:00', quietEnd: '07:00', graceMin: 20, followUps: 2 };
  push2(owner2, [{ kind: 'profile', id: 'me', updated_ms: now(), payload: await sync.encrypt(code2, JSON.stringify(nameless)), device: 'b-tablet' }]);
  await sync.runOnce(); await sync.runOnce();
  assert.deepEqual([s.name, s.quietStart], ['King Anjy', '21:00']);
  const back = JSON.parse(await sync.decrypt(code2, cloud.rows.get(owner2 + '|profile|me').payload));
  assert.deepEqual([back.name, back.quietStart], ['King Anjy', '21:00'], 'the name went up to the device that has none');
});

after(async () => { await sync.unlink(); });
