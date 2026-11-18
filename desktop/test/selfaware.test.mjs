// Atlas knows itself and what's around it: the shared cases (tools/around-fixtures.json, tools/updates-fixtures.json;
// the phone's SelfAwareTest.kt reads the same files), the brief stays short, the real changelog is sound, and the
// update download refuses anything that isn't Atlas's own release.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const AF = JSON.parse(read('tools/around-fixtures.json'));
const UF = JSON.parse(read('tools/updates-fixtures.json'));
const A = await import('../renderer/around.js');
const U = await import('../renderer/updates.js');
const aware = await import('../renderer/aware.js');
const cl = U.parseChangelog(read('tools/updates-changelog-fixture.json'));

test('around: the now line and alerts match the shared cases', () => {
  for (const c of AF.cases) {
    assert.equal(A.nowLine(c.facts), c.nowLine, c.name);
    assert.deepEqual(A.alerts(c.facts, c.ctx), c.alerts, c.name);
  }
  for (const c of AF.markNew) assert.deepEqual(A.markNew(c.list, c.seen), c.out);
});

test('around: the brief section stays under its limit, says the battery and the trading bot, and is NEW only once', () => {
  const f = { time: 'Wednesday 18 November 2026, 02:30', zone: 'Africa/Lagos', battery: 4, charging: false, network: 'none', dnd: true, audio: 'bluetooth',
    foreground: 'A very long application name that goes on and on and on', notifications: 42, storageFreeGb: 0.2, thermal: 'very hot', cpu: 99, ram: 97, gpu: 95 };
  const c = { botsRunning: 3, tradingRunning: true };
  const s = A.section(f, c, []);
  assert.ok(s.lines.join('\n').length <= A.SECTION_MAX, s.lines.join('\n').length + ' chars');
  assert.ok(s.lines[0].startsWith('Now: '));
  assert.ok(s.lines.some((l) => l.includes('Battery at 4%') && l.includes('trading bot') && l.includes('NEW')));
  assert.deepEqual(s.seen, ['battery-low', 'offline', 'storage', 'thermal', 'load']);
  assert.ok(!A.section(f, c, s.seen).lines.some((l) => l.includes('NEW')), 'told once');
  // the alert clears, then comes back: new again
  const charged = A.section({ ...f, charging: true }, c, s.seen);
  assert.ok(!charged.seen.includes('battery-low'));
  assert.ok(A.section(f, c, charged.seen).lines.some((l) => l.includes('Battery at 4%') && l.includes('NEW')));
});

test('around: detail names what it knows, the same words as the phone', () => {
  assert.equal(A.detail({ battery: 50, charging: true, notifications: 2, notifApps: ['WhatsApp', 'Gmail'], nextAlarm: '07:00' }),
    'Battery: 50%, charging\nNotifications: 2 (WhatsApp, Gmail)\nNext alarm: 07:00');
  assert.equal(A.detail({}), 'Nothing known about the surroundings right now.');
});

test('updates: versions, launch states, messages, latest.json and the brief match the shared cases', () => {
  for (const c of UF.compare) assert.equal(U.compareVersions(c.a, c.b), c.r, JSON.stringify(c));
  for (const c of UF.launch) assert.deepEqual(U.launchState(c.last, c.current, c.hadData), c.out, JSON.stringify(c));
  for (const c of UF.messages) assert.equal(U.updatedMessage(cl, c.from, c.to, c.where), c.out);
  for (const c of UF.newer) assert.equal(U.isNewer(c.latest, c.installed), c.r, JSON.stringify(c));
  for (const c of UF.latest) assert.deepEqual(U.parseLatest(c.in), c.out, c.in);
  for (const c of UF.brief) {
    const got = U.briefLines(cl, c.s);
    assert.deepEqual(got, c.out);
    assert.ok(got.join('\n').length <= U.BRIEF_MAX + 2);
  }
});

test('updates: the real changelog is newest first, has notes and abilities for every version, and has this app\'s version', () => {
  const real = U.parseChangelog(read('CHANGELOG.json'));
  assert.ok(real.length >= 4);
  for (let i = 1; i < real.length; i++) {
    assert.ok(U.compareVersions(real[i - 1].version, real[i].version) > 0);
    assert.ok(real[i - 1].versionCode > real[i].versionCode);
  }
  for (const e of real) assert.ok(e.notes.length && e.abilities.length, e.version);
  const desk = JSON.parse(read('desktop/package.json')).version;
  assert.ok(U.entryFor(real, desk), `CHANGELOG.json has no entry for the PC's version ${desk}`);
  const gradle = read('app/build.gradle.kts');
  const v = /versionName = "([^"]+)"/.exec(gradle)[1], code = Number(/versionCode = (\d+)/.exec(gradle)[1]);
  assert.equal(U.entryFor(real, v)?.versionCode, code, `CHANGELOG.json entry for ${v}`);
});

test('updates: a check is due every 12 hours, and an update is announced once per version', () => {
  const now = 1_800_000_000_000;
  assert.ok(U.checkDue(0, now));
  assert.ok(!U.checkDue(now - 3600_000, now));
  assert.ok(U.checkDue(now - U.CHECK_EVERY_MS, now));
  assert.ok(U.checkDue(now + 60_000, now), 'a clock that went backwards');
  const l = U.parseLatest('{"version":"0.11.0","url":"https://x.example/a"}');
  assert.ok(U.shouldAnnounce(l, { version: '0.10.2', versionCode: 0 }, ''));
  assert.ok(!U.shouldAnnounce(l, { version: '0.10.2', versionCode: 0 }, '0.11.0'));
  assert.ok(!U.shouldAnnounce(l, { version: '0.11.0', versionCode: 0 }, ''));
  assert.match(U.availableMessage({ ...l, notes: ['a', 'b'] }, { version: '0.10.2' }), /^Atlas 0\.11\.0 is out \(you have 0\.10\.2\)\. New: a; b\. Say "update Atlas"/);
});

test('the brief: "Around you" comes right after what is waiting, "This Atlas" before the rest, and the whole brief stays capped', async () => {
  aware.use({ self: {
    aroundLines: async () => A.section({ time: 'Wed 18 Nov 02:30', zone: 'Africa/Lagos', battery: 9, charging: false, network: 'wifi' }, { botsRunning: 1, tradingRunning: true }, []).lines,
    briefLines: () => U.briefLines(cl, { version: '0.11.0', versionCode: 21, device: 'the PC', from: '0.10.2', updatedAt: 1000, now: 2000, others: [{ platform: 'android', name: 'OPPO', app: '0.10.2' }], latest: null }),
    identity: () => U.identityLine('0.11.0', 'their Windows PC'),
  } });
  const b = await aware.brief({ markSeen: false });
  assert.ok(b.length <= aware.MAX_CHARS);
  assert.match(b, /^Around you:\n- Now: Wed 18 Nov 02:30 \(Africa\/Lagos\) · battery 9% · Wi-Fi\n- Battery at 9% and not charging while the trading bot is running/m);
  assert.match(b, /This Atlas \(about_atlas and self_status have the details\):\n- Updated to 0\.11\.0 from 0\.10\.2 today/);
  assert.match(b, /The phone runs Atlas 0\.10\.2 \(older than this one/);
  assert.ok(b.indexOf('Around you') < b.indexOf('This Atlas'));
  aware.use({ self: null });
});

test('the PC: facts are cheap and plain, and the update download refuses anything but Atlas\'s own release', async () => {
  const require = createRequire(import.meta.url);
  const self = require('../selfhost.js');
  const f = self.facts(fs.mkdtempSync(path.join((await import('node:os')).tmpdir(), 'atlas-self-')));
  assert.ok(typeof f.ram === 'number' && f.ram >= 0 && f.ram <= 100);
  assert.ok(['wifi', 'ethernet', 'none'].includes(f.network));
  assert.ok(!('title' in f));
  assert.ok(self.changelog().includes('"versions"'));
  const d = await self.download('/tmp', 'https://evil.example/Atlas-Setup.exe', '', '0.11.0');
  assert.match(d.error, /not Atlas’s own release/);
  assert.match((await self.download('/tmp', 'https://github.com/anjystar10/atlas/releases/download/desktop/Atlas-Setup.exe', '', '1; rm -rf /')).error, /Bad version/);
});
