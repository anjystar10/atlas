// Regenerates tools/around-fixtures.json and tools/updates-fixtures.json from the PC code (run: node tools/make-selfaware-fixtures.mjs).
// Only when a change in wording or rules is meant: the phone (SelfAwareTest.kt) must then give the same results.
import * as A from '../desktop/renderer/around.js';
import * as U from '../desktop/renderer/updates.js';
import fs from 'fs';
const aroundCases = [
  { name: 'phone, ordinary', facts: { time: 'Wed 18 Nov 02:30', zone: 'Africa/Lagos', battery: 64, charging: false, network: 'wifi', ringer: 'normal', dnd: false, audio: 'speaker', foreground: 'WhatsApp', notifications: 3 }, ctx: { botsRunning: 0 } },
  { name: 'low battery while the trading bot runs', facts: { time: 'Wed 18 Nov 02:30', zone: 'Africa/Lagos', battery: 9, charging: false, network: 'mobile', dnd: true, audio: 'bluetooth' }, ctx: { botsRunning: 1, tradingRunning: true } },
  { name: 'low battery, nothing running', facts: { battery: 8, charging: false }, ctx: { botsRunning: 0 } },
  { name: 'low-ish battery, nothing running: no alert', facts: { battery: 14, charging: false }, ctx: { botsRunning: 0 } },
  { name: 'charging: no alert', facts: { battery: 5, charging: true, network: 'wifi' }, ctx: { botsRunning: 2 } },
  { name: 'offline with two bots', facts: { network: 'none', ringer: 'vibrate', storageFreeGb: 0.6, thermal: 'hot' }, ctx: { botsRunning: 2 } },
  { name: 'offline, no bots', facts: { network: 'none' }, ctx: {} },
  { name: 'PC busy', facts: { time: 'Wed 18 Nov 02:31', zone: 'Africa/Lagos', network: 'ethernet', foreground: 'Visual Studio Code', cpu: 95.4, ram: 72, gpu: 30 }, ctx: { botsRunning: 0 } },
  { name: 'nothing known', facts: {}, ctx: {} },
];
for (const c of aroundCases) {
  c.nowLine = A.nowLine(c.facts);
  c.alerts = A.alerts(c.facts, c.ctx);
}
const seenCases = [
  { list: [{ key: 'battery-low', text: 'B' }, { key: 'offline', text: 'O' }], seen: ['offline', 'storage'] },
];
for (const c of seenCases) c.out = A.markNew(c.list, c.seen);
const cl = U.parseChangelog(fs.readFileSync(new URL('../', import.meta.url).pathname + 'tools/updates-changelog-fixture.json', 'utf8'));
const updates = {
  compare: [['0.10.2', '0.10.10'], ['0.11.0', '0.10.2'], ['1.0', '1.0.0'], ['v0.9.3', '0.9.3'], ['0.10.2-beta', '0.10.2'], ['x', '0.1'], ['', '']].map(([a, b]) => ({ a, b, r: U.compareVersions(a, b) })),
  launch: [[null, '0.11.0', false], [null, '0.11.0', true], ['0.10.2', '0.10.2', true], ['0.10.1', '0.11.0', true], ['0.11.0', '0.10.2', true]].map(([last, cur, had]) => ({ last, current: cur, hadData: had, out: U.launchState(last, cur, had) })),
  messages: [['0.10.1', '0.11.0', 'on your phone'], [null, '0.11.0', 'on your PC'], ['0.11.0', '0.11.0', ''], ['0.9.0', '0.11.0', '']].map(([from, to, where]) => ({ from, to, where, out: U.updatedMessage(cl, from, to, where) })),
  newer: [
    [{ version: '0.11.0', versionCode: 21 }, { version: '0.10.2', versionCode: 20 }],
    [{ version: '0.10.2', versionCode: 21 }, { version: '0.10.2', versionCode: 20 }],
    [{ version: '0.11.0', versionCode: 0 }, { version: '0.10.2', versionCode: 20 }],
    [{ version: '0.10.2', versionCode: 20 }, { version: '0.10.2', versionCode: 20 }],
    [{ version: '0.12.0', versionCode: 19 }, { version: '0.10.2', versionCode: 20 }],
  ].map(([l, i]) => ({ latest: l, installed: i, r: U.isNewer(l, i) })),
  latest: [
    { in: '{"version":"0.11.0","versionCode":21,"url":"https://raw.githubusercontent.com/anjystar10/atlas/releases/Atlas.apk","sha256":"' + 'AB'.repeat(32) + '","size":74180544,"notes":["one","two"]}' },
    { in: '{"version":"0.11.0","url":"http://insecure.example/a.apk"}' },
    { in: 'not json' },
    { in: '{"version":"0.11.0","url":"https://x.example/a.exe","sha256":"short"}' },
  ].map((c) => ({ ...c, out: U.parseLatest(c.in) })),
  brief: [
    { s: { version: '0.11.0', versionCode: 21, device: 'the phone', from: '0.10.2', updatedAt: 1000, now: 1000 + 86400000 + 5, others: [{ platform: 'windows', name: 'PC', app: '0.10.1' }], latest: null } },
    { s: { version: '0.10.2', versionCode: 20, device: 'the PC', from: null, updatedAt: 0, now: 5, others: [{ platform: 'android', name: 'OPPO', app: '0.10.2' }], latest: { version: '0.11.0', versionCode: 21, url: 'https://x', sha256: '', size: 0, notes: [], builtFrom: '' } } },
    { s: { version: '0.11.0', versionCode: 21, device: 'the phone', from: '0.10.1', updatedAt: 1000, now: 1000 + 6 * 86400000, others: [], latest: null } },
  ].map((c) => ({ ...c, out: U.briefLines(cl, c.s) })),
};
fs.writeFileSync(new URL('../', import.meta.url).pathname + 'tools/around-fixtures.json', JSON.stringify({ _doc: 'Shared cases for desktop/renderer/around.js and core/Around.kt: both must give exactly these results. Regenerate the expected values only when a change is meant.', cases: aroundCases, markNew: seenCases }, null, 1) + '\n');
fs.writeFileSync(new URL('../', import.meta.url).pathname + 'tools/updates-fixtures.json', JSON.stringify({ _doc: 'Shared cases for desktop/renderer/updates.js and core/Updates.kt (with tools/updates-changelog-fixture.json): both must give exactly these results.', ...updates }, null, 1) + '\n');
