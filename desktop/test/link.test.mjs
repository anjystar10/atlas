// Device Link: the protocol engine against a stand-in for the cloud relay, with the real encryption.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createLink, toScreen, toFraction, pieces, accept, inputPlan, sessionAlive, VERSION } from '../renderer/linkcore.js';
import { createRelay } from './fixtures/relay.js';

const enc = new TextEncoder(), dec = new TextDecoder();
const sha256 = async (s) => new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const hex = (b) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
const b64 = (u8) => Buffer.from(u8).toString('base64');
async function crypt(code) {
  const key = await crypto.subtle.importKey('raw', await sha256('atlas-key:' + code), 'AES-GCM', false, ['encrypt', 'decrypt']);
  return {
    owner: hex(await sha256('atlas-owner:' + code)),
    async encrypt(t) { const iv = crypto.getRandomValues(new Uint8Array(12)); const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(t))); const a = new Uint8Array(12 + ct.length); a.set(iv); a.set(ct, 12); return b64(a); },
    async decrypt(p) { const a = Buffer.from(p, 'base64'); return dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: a.subarray(0, 12) }, key, a.subarray(12))); },
  };
}
const CODE = 'ATLS-ABCD-EFGH-JKMN-PQRS-TVWX';

async function pair(relay, extra = {}) {
  const c = await crypt(CODE);
  const got = { phone: [], pc: [] };
  const mk = (me, name, platform) => createLink({ rpc: relay.rpc, encrypt: c.encrypt, decrypt: c.decrypt, owner: c.owner, me,
    info: () => ({ name, platform, caps: platform === 'android' ? ['tasks', 'screen'] : ['tasks', 'screen', 'files'] }),
    onMessage: (m) => { got[me].push(m); }, idleMs: 50, ...extra });
  return { c, got, phone: mk('phone', "Anjy's phone", 'android'), pc: mk('pc', 'MSI laptop', 'windows') };
}

test('link: a message reaches only the device it is for, decrypted, once', async () => {
  const relay = createRelay();
  const { phone, pc, got } = await pair(relay);
  await pc.pullOnce(0); await phone.pullOnce(0);     // both start from the relay's head
  await phone.send('pc', 'task', { tid: 't1', text: 'open notepad' });
  await pc.pullOnce(0); await pc.pullOnce(0);
  assert.equal(got.pc.length, 1);
  assert.equal(got.pc[0].k, 'task'); assert.equal(got.pc[0].text, 'open notepad'); assert.equal(got.pc[0].from, 'phone');
  await phone.pullOnce(0);
  assert.equal(got.phone.length, 0, 'the sender does not hear its own message');
  // the relay only ever held ciphertext
  assert.ok(!relay.msgs.some((m) => m.payload.includes('notepad')));
});

test('link: a device with a different code hears nothing (and is not listed as a peer)', async () => {
  const relay = createRelay();
  const { phone, pc, got } = await pair(relay);
  const other = await crypt('ATLS-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ');
  const stranger = createLink({ rpc: relay.rpc, encrypt: other.encrypt, decrypt: other.decrypt, owner: phone && (await crypt(CODE)).owner, me: 'x', info: () => ({ name: 'x' }) });
  await pc.pullOnce(0);
  await stranger.send('pc', 'task', { tid: 'evil', text: 'format c:' });   // right owner hash, wrong key
  await pc.pullOnce(0);
  assert.equal(got.pc.length, 0, 'a message that does not decrypt with our key is dropped');
  await stranger.beat(); await phone.beat();
  const peers = await pc.beat();
  assert.deepEqual(peers.map((p) => p.device), ['phone']);
  assert.equal(peers[0].name, "Anjy's phone"); assert.equal(peers[0].online, true);
});

test('link: a long poll hears a message within a few hundred ms', async () => {
  const relay = createRelay({ latencyMs: 30 });
  const { phone, pc, got } = await pair(relay);
  await pc.pullOnce(0);
  const t0 = Date.now();
  const p = pc.pullOnce(2000);
  setTimeout(() => phone.send('pc', 'task', { tid: 't2', text: 'hi' }), 150);
  await p;
  const took = Date.now() - t0;
  assert.equal(got.pc.length, 1);
  assert.ok(took < 700, 'delivered in ' + took + ' ms');
});

test('link: ping measures a round trip through the running loops', async () => {
  const relay = createRelay({ latencyMs: 20 });
  const { phone, pc } = await pair(relay, { alwaysLongPoll: true });
  phone.start(); pc.start();
  await new Promise((r) => setTimeout(r, 120));
  const rtt = await phone.ping('pc', 5000);
  await phone.stop(); await pc.stop();
  assert.ok(rtt !== null && rtt < 1500, 'rtt ' + rtt);
});

test('link: a file goes over in pieces and arrives whole', async () => {
  const relay = createRelay();
  const { phone, pc, got } = await pair(relay);
  await pc.pullOnce(0);
  const data = Buffer.alloc(1_500_000, 7).toString('base64');   // 2 MB of base64: 4 pieces
  await phone.sendFile('pc', 'shot.png', 'image/png', data, { tid: 't9' });
  for (let i = 0; i < 3; i++) await pc.pullOnce(0);
  const f = got.pc.find((m) => m.k === 'file_done');
  assert.ok(f, 'file arrived'); assert.equal(f.name, 'shot.png'); assert.equal(f.b64, data); assert.equal(f.meta.tid, 't9');
  await assert.rejects(phone.sendFile('pc', 'big', 'x', 'A'.repeat(14_500_000)), /10 MB/);
});

test('link: the screen stream keeps only the newest frame and a viewer gets the latest', async () => {
  const relay = createRelay();
  const { phone, pc } = await pair(relay);
  await pc.putFrame(1, { sid: 's', w: 10, h: 5, jpg: 'AAA' });
  await pc.putFrame(2, { sid: 's', w: 10, h: 5, jpg: 'BBB' });
  const f = await phone.getFrame('pc', 0, 0);
  assert.equal(f.seq, 2); assert.equal(f.frame.jpg, 'BBB');
  assert.equal(await phone.getFrame('pc', 2, 50), null, 'nothing newer yet');
  assert.ok(![...relay.frames.values()][0].payload.includes('BBB'), 'frames are encrypted too');
});

test('link: coordinates map from the picture to the real screen, clamped', () => {
  assert.deepEqual(toScreen(0.5, 0.5, 1920, 1080), { x: 960, y: 540 });
  assert.deepEqual(toScreen(1, 1, 1920, 1080), { x: 1919, y: 1079 });
  assert.deepEqual(toScreen(-3, NaN, 1080, 2400), { x: 0, y: 1200 });
  assert.deepEqual(toFraction(50, 25, 100, 50), { x: 0.5, y: 0.5 });
  assert.equal(toFraction(120, 25, 100, 50), null);
  assert.equal(pieces('abcdef', 4).length, 2);
});

test('link: remote inputs are checked before they touch anything', () => {
  assert.deepEqual(inputPlan({ t: 'tap', x: 0.25, y: 0.5 }, 1080, 2400), { t: 'tap', x: 270, y: 1200 });
  assert.deepEqual(inputPlan({ t: 'drag', x: 0.5, y: 0.8, x2: 0.5, y2: 0.2 }, 1000, 1000), { t: 'drag', x: 500, y: 800, x2: 500, y2: 200 });
  assert.deepEqual(inputPlan({ t: 'scroll', dy: -99 }, 100, 100), { t: 'scroll', x: 50, y: 50, dy: -20 });
  assert.equal(inputPlan({ t: 'scroll', dy: 0 }, 100, 100), null);
  assert.equal(inputPlan({ t: 'key', key: 'ctrl+alt+del; rm -rf' }, 1, 1), null);
  assert.deepEqual(inputPlan({ t: 'key', key: 'Ctrl+C' }, 1, 1), { t: 'key', key: 'ctrl+c' });
  assert.equal(inputPlan({ t: 'act', act: 'format_disk' }, 1, 1), null);
  assert.deepEqual(inputPlan({ t: 'act', act: 'home' }, 1, 1), { t: 'act', act: 'home' });
  assert.equal(inputPlan({ t: 'text', text: '' }, 1, 1), null);
  assert.equal(inputPlan({ t: 'text', text: 'x'.repeat(5000) }, 1, 1).text.length, 2000);
  assert.equal(inputPlan({ t: 'eval', code: '1' }, 1, 1), null);
  assert.equal(sessionAlive({ lastKeep: 1000 }, 10_000), true);
  assert.equal(sessionAlive({ lastKeep: 1000 }, 30_000), false);
});

test('link: messages from another version or for another device are ignored', () => {
  assert.equal(accept({ v: VERSION + 1, id: 'a', k: 'task', to: 'pc' }, 'pc'), null);
  assert.equal(accept({ v: VERSION, id: 'a', k: 'task', to: 'phone' }, 'pc'), null);
  assert.ok(accept({ v: VERSION, id: 'a', k: 'task', to: '*' }, 'pc'));
});

test('link: the phone (Link.kt) and this PC agree — fixture decrypts and matches', async () => {
  const fx = JSON.parse(fs.readFileSync(new URL('../../tools/link-fixtures.json', import.meta.url), 'utf8'));
  const c = await crypt(fx.code);
  assert.equal(c.owner, fx.owner);
  const m = JSON.parse(await c.decrypt(fx.ciphertext));
  assert.deepEqual(m, fx.message);
  assert.ok(accept(m, fx.message.to));
  for (const k of fx.inputs) assert.deepEqual(inputPlan(k.input, k.w, k.h), k.plan);
});
