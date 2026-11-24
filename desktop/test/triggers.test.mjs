// Atlas Bot v2 triggers on the PC: the watcher (renderer/trigrun.js) driven with fake email, prices, files and clock.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createTrigRun, lastPrice, configKey } from '../renderer/trigrun.js';
import { cleanTrigger } from '../renderer/trigrules.js';

const MIN = 60000;
function world({ bots, mail = [], price = null, files = [], claim = null } = {}) {
  const w = { now: Date.UTC(2026, 10, 18, 8, 0, 0), fired: [], saved: null, mail, price, files, claimAnswer: claim, claims: [] };
  w.run = createTrigRun({
    device: 'pc',
    bots: () => w.bots,
    fire: (botId, t, ev) => { const m = { id: 'm' + (w.fired.length + 1), botId, trigger: t.id, ev }; w.fired.push(m); return m; },
    now: () => w.now,
    load: async () => w.saved,
    save: async (s) => { w.saved = JSON.parse(JSON.stringify(s)); },
    mailList: async () => w.mail,
    quote: async () => w.price,
    listFiles: async () => w.files,
    claim: async (k) => { w.claims.push(k); return w.claimAnswer; },
  });
  w.bots = bots;
  w.step = async (ms) => { w.now += ms; return w.run.tick(); };
  return w;
}
const bot = (triggers, extra = {}) => ({ id: 'b1', name: 'Inbox', state: 'active', home: 'any', triggers, ...extra });

test('trigger watcher: a schedule fires once per slot, never on the first look, and not twice', async () => {
  const t = cleanTrigger({ id: 't1', type: 'schedule', config: { text: 'every day at 9am', goal: 'Morning digest' } });
  assert.ok(t.config.schedule, 'the schedule text is read');
  const w = world({ bots: [bot([t])] });
  await w.run.tick();
  assert.equal(w.fired.length, 0, 'the first look only finds the next time');
  const next = w.run.nextAt('b1', 't1');
  assert.ok(next > w.now);
  await w.step(next - w.now - 1000);
  assert.equal(w.fired.length, 0, 'not before its time');
  await w.step(2000);
  assert.equal(w.fired.length, 1, 'fires at 9');
  assert.equal(w.fired[0].ev.type, 'schedule');
  await w.step(20000);
  assert.equal(w.fired.length, 1, 'once');
  assert.ok(w.run.nextAt('b1', 't1') > w.now, 'moved on to tomorrow');
});

test('trigger watcher: email — baseline first, then each new matching mail once; claims let only one device start it', async () => {
  const t = cleanTrigger({ id: 'te', type: 'email', config: { from: 'bank', goal: 'Check it' } });
  const w = world({ bots: [bot([t])], mail: [{ uid: 10, from: 'Shop', subject: 'Sale', unread: true }], claim: true });
  await w.run.tick();
  assert.equal(w.fired.length, 0, 'old mail is not news');
  w.mail = [{ uid: 11, from: 'My Bank <alerts@bank.com>', subject: 'Payment received', unread: true }, { uid: 12, from: 'Shop', subject: 'More', unread: true }, ...w.mail];
  await w.step(2 * MIN + 1);
  assert.equal(w.fired.length, 1);
  assert.match(w.fired[0].ev.title, /New email from My Bank/);
  assert.equal(w.claims.length, 1);
  await w.step(2 * MIN + 1);
  assert.equal(w.fired.length, 1, 'the same mail never starts a second mission');
  // the other device got it first
  w.claimAnswer = false;
  w.mail = [{ uid: 13, from: 'bank', subject: 'x', unread: true }, ...w.mail];
  await w.step(2 * MIN + 1);
  assert.equal(w.fired.length, 1, 'the other device runs it');
});

test('trigger watcher: email is looked at every 2 minutes, not every tick', async () => {
  const t = cleanTrigger({ id: 'te', type: 'email', config: {} });
  let looks = 0;
  const w = world({ bots: [bot([t])] });
  const orig = w.run;
  void orig;
  w.mail = new Proxy([], { get: (a, k) => { if (k === 'filter') looks++; return Reflect.get(a, k); } });
  await w.run.tick(); await w.step(15000); await w.step(15000);
  assert.equal(looks, 1);
  await w.step(2 * MIN);
  assert.equal(looks, 2);
});

test('trigger watcher: price crossings fire; paused bots, phone-only bots and phone notifications are left alone here', async () => {
  const t = cleanTrigger({ id: 'tp', type: 'price', config: { symbol: 'btcusdt', above: 100000 } });
  const w = world({ bots: [bot([t]), bot([t], { id: 'b2', state: 'paused' }), bot([t], { id: 'b3', home: 'phone' }), bot([cleanTrigger({ id: 'tn', type: 'notification', config: { app: 'WhatsApp' } })], { id: 'b4' })], price: 99000 });
  await w.run.tick();
  w.price = 100500;
  await w.step(MIN + 1);
  assert.deepEqual(w.fired.map((f) => f.botId), ['b1'], 'only the active bot that runs here');
  assert.match(w.fired[0].ev.title, /BTCUSDT went above 100000/);
  assert.deepEqual(w.run.watched().map((x) => x.bot.id), ['b1']);
});

test('trigger watcher: a new file fires once it stops changing; problems are kept for the bot page', async () => {
  const t = cleanTrigger({ id: 'tf', type: 'file', config: { source: 'downloads', pattern: '*.pdf' } });
  const w = world({ bots: [bot([t])], files: [{ name: 'old.pdf', size: 1, mtime: 1 }] });
  await w.run.tick();
  w.files = [...w.files, { name: 'new.pdf', size: 5, mtime: 9 }];
  await w.step(15001);
  assert.equal(w.fired.length, 0, 'still settling');
  await w.step(15001);
  assert.equal(w.fired.length, 1);
  assert.equal(w.fired[0].ev.data.file, 'new.pdf');
  // a watcher that throws records why, and the others keep going
  w.run = createTrigRun({ device: 'pc', bots: () => w.bots, fire: () => null, now: () => w.now, load: async () => null, save: async () => {}, listFiles: async () => { throw new Error('There\'s no folder X.'); } });
  await w.run.tick();
  assert.equal(w.run.problem('b1', 'tf'), "There's no folder X.");
});

test('trigger watcher: changing a trigger\'s settings starts it from a fresh look; its goal alone does not', () => {
  const a = cleanTrigger({ id: 't', type: 'email', config: { from: 'a', goal: 'x' } });
  const b = cleanTrigger({ id: 't', type: 'email', config: { from: 'a', goal: 'y' } });
  const c = cleanTrigger({ id: 't', type: 'email', config: { from: 'b', goal: 'x' } });
  assert.equal(configKey(a), configKey(b));
  assert.notEqual(configKey(a), configKey(c));
});

test('trigger watcher: reads the last price out of a quote', () => {
  assert.equal(lastPrice('BTCUSDT (Bybit): bid 99990.5, ask 100001, last 100000.25, 24h 1.20%.'), 100000.25);
  assert.equal(lastPrice('NQZ6 (ProjectX): bid 21000, ask 21000.25, last 21000.5.'), 21000.5);
  assert.equal(lastPrice('Error: no account'), null);
  assert.equal(lastPrice('last 0'), null);
});

test('trigger watcher: a price crossing seen by the phone and the laptop at different moments starts one mission, not two', async () => {
  // one cloud: the first device to claim a key gets it for 10 minutes (Sync.claim / sync.claim)
  const cloud = new Map(); let clock = Date.UTC(2026, 10, 18, 8, 0, 0);
  const claim = async (k) => { const c = cloud.get(k); if (c && clock - c < 10 * MIN) return false; cloud.set(k, clock); return true; };
  const bots = [{ id: 'b1', home: 'any', triggers: [{ id: 'tp', type: 'price', enabled: true, config: { symbol: 'BTCUSDT', above: 100000 } }] }];
  const fired = []; const prices = { pc: 99000, phone: 99000 };
  const dev = (name) => createTrigRun({ device: 'pc', bots: () => bots, fire: (b, t, ev) => { fired.push([name, ev.key]); return { id: name }; }, now: () => clock,
    load: async () => null, save: async () => {}, quote: async () => prices[name], claim });
  const pc = dev('pc'), phone = dev('phone');
  await pc.tick(); await phone.tick();                         // first look: the baseline
  clock += MIN; prices.pc = 100500; await pc.tick();           // the laptop sees it go above
  clock += 3 * MIN; prices.phone = 100600; await phone.tick(); // the phone sees the same move a few minutes later
  assert.equal(fired.length, 1, JSON.stringify(fired));
  // a real crossing again later (it fell back and rose again) fires again
  clock += 30 * MIN; prices.pc = 99000; await pc.tick(); clock += MIN; prices.pc = 100400; await pc.tick();
  assert.equal(fired.length, 2);
});

test('trigger rules: the same notification words again in a later hour fire again; a re-post within the hour does not', async () => {
  const { notificationEvent, eventKey, dedupe } = await import('../renderer/trigrules.js');
  const n = { pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Mum', text: 'Call me' };
  const t0 = Date.UTC(2026, 10, 18, 8, 5, 0);
  let s = {}; const seen = (at) => { const r = dedupe(s, eventKey('b', 't', notificationEvent(n, at)), at); s = r.state; return r.fresh; };
  assert.equal(seen(t0), true);
  assert.equal(seen(t0 + 10 * MIN), false, 'the same notification re-posted');
  assert.equal(seen(t0 + 26 * 3600000), true, 'the same words the next day are a new notification');
});
