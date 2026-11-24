// Writes the "stopSync" part of tools/trade-fixtures.json from desktop/traderules.js: how the trading stop switch crosses
// devices (sync kind "tradestop"). The phone's TradeRules.kt must give the same answers (TradeRulesTest.kt).
// Run: node desktop/test/make-trade-stop-fixtures.mjs   (then both test suites check them)
import fs from 'node:fs';
import { createRequire } from 'node:module';
const R = createRequire(import.meta.url)('../traderules.js');

const K = 1762900000000;
const states = {
  never: {},
  neverResumedOnly: { killed: false, resumedAt: K },
  legacyOn: { killed: true, killedAt: K },
  legacyLifted: { killed: false, killedAt: K, resumedAt: K + 60000 },
  on: { killed: true, killedAt: K, stopId: 'stop-a', stops: ['stop-a'] },
  lifted: { killed: false, killedAt: K, resumedAt: K + 60000, stopId: 'stop-a', stops: ['stop-a'] },
  liftedTwo: { killed: false, killedAt: K + 5000, resumedAt: K + 90000, stopId: 'stop-b', stops: ['stop-a', 'stop-b'] },
  onAfterOld: { killed: true, killedAt: K + 5000, stopId: 'stop-b', stops: ['stop-a', 'stop-b'] },
};
const recs = {
  none: null,
  notObject: 'stop-x',
  newOn: { killed: true, stop: 'stop-x', updatedAt: K + 1000 },
  newOff: { killed: false, stop: 'stop-x', updatedAt: K + 1000 },
  killedNotTrue: { killed: 'true', stop: 'stop-x', updatedAt: K },
  noStop: { killed: true, updatedAt: K },
  blankStop: { killed: true, stop: '   ', updatedAt: K },
  numberStop: { killed: true, stop: 5, updatedAt: K },
  sameAsMine: { killed: true, stop: 'stop-a', updatedAt: K },
  oldOne: { killed: true, stop: 'stop-a', updatedAt: K },
  legacyOther: { killed: true, stop: `legacy-${K}`, updatedAt: K },
  padded: { killed: true, stop: '  stop-y  ', updatedAt: K },
  long: { killed: true, stop: 'stop-' + 'z'.repeat(150), updatedAt: K },
};
const fromOther = [];
for (const [sn, s] of Object.entries(states)) for (const [rn, rec] of Object.entries(recs)) fromOther.push({ name: `${sn} + ${rn}`, state: s, rec, out: R.stopFromOther(s, rec) });
const stopSync = {
  note: 'A stop on one device stops the other; a record never lifts a stop (that is the user\'s own tap on each device).',
  stopIdOf: Object.entries(states).map(([name, state]) => ({ name, state, out: R.stopIdOf(state) })),
  record: Object.entries(states).map(([name, state]) => ({ name, state, out: R.stopRecord(state) })),
  fromOther,
  addStop: [
    { stops: null, id: 'a', out: R.addStop(null, 'a') },
    { stops: ['a', 'b'], id: 'a', out: R.addStop(['a', 'b'], 'a') },
    { stops: Array.from({ length: 20 }, (_, i) => 's' + i), id: 'new', out: R.addStop(Array.from({ length: 20 }, (_, i) => 's' + i), 'new') },
  ],
};
const file = new URL('../../tools/trade-fixtures.json', import.meta.url);
const fx = JSON.parse(fs.readFileSync(file, 'utf8'));
fx.stopSync = stopSync;
fs.writeFileSync(file, JSON.stringify(fx, null, 1) + '\n');
console.log('wrote stopSync:', fromOther.length, 'cases;', fromOther.filter((c) => c.out).length, 'take a stop in');
