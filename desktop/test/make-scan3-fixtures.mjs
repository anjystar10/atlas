// Writes property-style generated cases into the shared fixtures, computed by the PC's own code, so the phone is held to
// the very same answers on inputs nobody wrote by hand: tools/trade-fixtures.json "generated" and "protects" (traderules.js
// → TradeRules.kt, TradeRulesTest.kt) and tools/sync-fixtures.json "generated" (sync.js → SyncPlan.kt, SyncPlanTest.kt).
// Deterministic (a seeded generator): running it again writes the same cases. Run: node desktop/test/make-scan3-fixtures.mjs
import fs from 'node:fs';
import { createRequire } from 'node:module';
globalThis.window = globalThis.window || { atlasHost: { read: async () => null, write: async () => true } };
const R = createRequire(import.meta.url)('../traderules.js');
const sync = await import('../renderer/sync.js');

let seed = 0x5ca43;
const rnd = () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
const pick = (a) => a[Math.floor(rnd() * a.length)];
const chance = (p) => rnd() < p;
const int = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
const dec = (x, d) => Number(x.toFixed(d));

/** The file's own text with these keys (re)written at its end, one case per line: the hand-written part stays exactly as it is. */
function appendKeys(text, keys) {
  const o = JSON.parse(text); for (const k of Object.keys(keys)) delete o[k];
  let base = JSON.stringify(o) === JSON.stringify(JSON.parse(text)) ? text : null;
  if (base == null) {   // drop an earlier run's generated keys (always written last, by this script)
    const at = Math.min(...Object.keys(keys).map((k) => text.indexOf(`,\n  "${k}": `)).filter((i) => i >= 0));
    base = text.slice(0, at) + '\n}\n';
  }
  const body = Object.entries(keys).map(([k, v]) => `  ${JSON.stringify(k)}: ` + (Array.isArray(v) ? '[\n' + v.map((x) => '    ' + JSON.stringify(x)).join(',\n') + '\n  ]'
    : '{\n' + Object.entries(v).map(([k2, v2]) => `    ${JSON.stringify(k2)}: ` + (Array.isArray(v2) ? '[\n' + v2.map((x) => '      ' + JSON.stringify(x)).join(',\n') + '\n    ]' : JSON.stringify(v2))).join(',\n') + '\n  }')).join(',\n');
  const out = base.replace(/\s*}\s*$/, '') + ',\n' + body + '\n}\n';
  JSON.parse(out); return out;
}

// ------------------------------------------------------------------ trading: check() on generated orders
const INSTS = [
  { symbol: 'BTCUSDT', tickSize: '0.01', qtyStep: '0.000001', minQty: '0.000048', minNotional: '1', pointValue: 1, futures: false, tpslLimitOnly: true, px: 67000 },
  { symbol: 'BTCUSDT', tickSize: '0.01', qtyStep: '0.000001', minQty: '0.000048', minNotional: '1', pointValue: 1, futures: false, px: 67000 },
  { symbol: 'DOGEUSDT', tickSize: '0.00001', qtyStep: '0.1', minQty: '1', minNotional: '5', pointValue: 1, futures: false, tpslLimitOnly: true, px: 0.16 },
  { symbol: 'ETHBTC', tickSize: '0.000001', qtyStep: '0.0001', minQty: '0.0001', minNotional: '0.0001', pointValue: 1, futures: false, px: 0.037 },
  { symbol: 'NQZ6', tickSize: '0.25', qtyStep: '1', minQty: '1', pointValue: 20, futures: true, px: 21000 },
  { symbol: 'MNQZ6', tickSize: '0.25', qtyStep: '1', minQty: '1', pointValue: 2, futures: true, px: 21000 },
  { symbol: 'GCG7', tickSize: '0.1', qtyStep: '1', minQty: '1', pointValue: 100, futures: true, px: 2650.3 },
];
function genCase(i) {
  const ins = pick(INSTS); const { px, ...inst } = ins;
  const spread = inst.futures ? Number(inst.tickSize) : px * 0.0001;
  const market = chance(0.06) ? { bid: 0, ask: 0, last: chance(0.5) ? 0 : px } : { bid: dec(px - spread, 8), ask: dec(px + spread, 8), last: px };
  const mode = chance(0.55) ? 'live' : 'paper';
  const limits = chance(0.3) ? undefined : {
    ...(chance(0.7) ? { maxOrderUsd: pick([20, 50, 100, 1000]) } : {}), ...(chance(0.7) ? { maxContracts: pick([1, 2, 3]) } : {}),
    ...(chance(0.7) ? { maxDailyLossUsd: pick([25, 50, 300, 1000]) } : {}), ...(chance(0.5) ? { maxOpenPositions: pick([1, 2, 5]) } : {}),
    ...(chance(0.5) ? { maxOrdersPerDay: pick([1, 2, 20]) } : {}), ...(chance(0.2) ? { symbols: pick([['BTC'], ['ETH', 'NQ'], ['DOGE', 'GC'], ['MNQ'], ['BTCUSDT', 'NQZ6']]) } : {}),
    ...(chance(0.12) ? { hours: pick([{ start: '09:00', end: '17:00', days: [1, 2, 3, 4, 5] }, { start: '22:00', end: '02:00', days: [] }, { start: '00:00', end: '23:59', days: [6, 7] }]) } : {}),
    ...(inst.futures ? { allowDerivatives: chance(0.85) } : chance(0.3) ? { allowDerivatives: chance(0.5) } : {}),
  };
  const account = { id: 'g' + (i % 3), name: pick(['Bybit', 'Topstep 50K', 'Practice', 'Prop · café']), broker: inst.futures ? 'projectx' : 'bybit', mode, ...(limits ? { limits } : {}) };
  const side = pick(['buy', 'buy', 'buy', 'sell', 'sell', 'sell', 'BUY', chance(0.3) ? 'hold' : 'sell']);
  const type = pick(['market', 'market', 'market', 'limit', 'limit', 'stop', undefined, chance(0.3) ? 'trailing' : 'market']);
  const order = { symbol: chance(0.9) ? ins.symbol.toLowerCase() : ins.symbol, side };
  if (type !== undefined) order.type = type;
  const near = (k) => dec(px * (1 + (rnd() - 0.5) * k), inst.futures ? 2 : 8);
  if (inst.futures) { if (chance(0.92)) order.qty = pick([1, 1, 2, 3, 0, 1.7, -1]); else order.notional = 1000; }
  else if (chance(0.5)) order.qty = dec(pick([5, 20, 60, 150, 0.4]) / px * (0.5 + rnd()), 9); else order.notional = pick([0.5, 5, 20, 49.99, 50, 80, 150, 1000]);
  if (chance(0.04)) order.qty = pick(['abc', '', null]);
  if (type === 'limit' && chance(0.92)) order.limit = near(0.02);
  if (type === 'stop' && chance(0.92)) order.stop = near(0.02);
  const buyish = String(side).toLowerCase() === 'buy';
  if (chance(0.55)) order.stopLoss = chance(0.85) ? dec(px * (buyish ? 1 - rnd() * 0.03 : 1 + rnd() * 0.03), inst.futures ? 2 : 8) : near(0.01);
  if (chance(0.35)) order.takeProfit = dec(px * (buyish ? 1 + rnd() * 0.03 : 1 - rnd() * 0.03), inst.futures ? 2 : 8);
  if (chance(0.12)) order.closing = true;
  const start = pick([1000, 50000, 634.5]);
  const state = { killed: chance(0.05), day: '2026-11-25', dayStartEquity: chance(0.08) ? null : start, equity: chance(0.06) ? null : dec(start - pick([0, 0, 5, 24.99, 25, 40, 400, -30]), 2),
    ordersToday: pick([0, 0, 0, 1, 2, 3]), openSymbols: pick([[], [], [], ['BTCUSDT'], ['NQZ6', 'GCG7'], ['SOLUSDT', 'ETHUSDT', 'XRPUSDT']]), minuteOfDay: int(0, 1439), isoDay: int(1, 7), now: 1764000000000 };
  if (mode === 'live' && chance(0.45)) state.standing = { accountId: chance(0.9) ? account.id : 'other', enabled: chance(0.9), symbols: pick([['BTC'], ['NQ', 'MNQ'], [], ['DOGE', 'GC']]),
    expiresAt: chance(0.85) ? state.now + 86400e3 : state.now - 1, maxOrderUsd: pick([0, 25, 60, 100]), maxContracts: pick([0, 1, 2]), maxDailyLossUsd: pick([0, 20, 300, 600]) };
  const plan = mode === 'live' && chance(0.45) ? { accountId: chance(0.9) ? account.id : 'other', confirmed: chance(0.9), expiresAt: chance(0.85) ? state.now + 3600e3 : state.now - 1,
    maxOrders: int(1, 3), used: int(0, 3), symbols: pick([[], ['BTC'], ['NQ'], ['GC', 'DOGE']]), sides: pick([[], ['buy'], ['sell']]), maxOrderUsd: pick([25, 60, 200]), maxContracts: pick([1, 2]) } : null;
  const v = R.check(order, inst, market, account, state, plan);
  const expect = v.ok ? { ok: true, order: v.order, needsApproval: v.needsApproval, coveredByPlan: v.coveredByPlan, coveredByLimits: v.coveredByLimits, estUsd: v.estUsd, worstCaseUsd: v.worstCaseUsd, summary: v.summary }
    : { ok: false, codes: v.reasons.map((r) => r.code) };
  return { name: `generated ${i}`, order, inst, market, account, state, plan, expect };
}

function genProtects(i) {
  const sym = pick(['NQZ6', 'nqz6', 'GCG7', 'BTCUSDT', '']);
  const order = { id: String(9000 + i), symbol: sym, side: pick(['buy', 'sell', 'SELL', '']), type: pick(['stop', 'limit', 'market']) };
  const positions = [];
  for (let k = int(0, 2); k > 0; k--) positions.push(pick([
    { symbol: 'NQZ6', contractId: 'CON.F.US.ENQ.Z26', size: pick([1, 2, 0, -1]), type: pick([1, 2, 3]) },
    { symbol: 'GCG7', size: 1, type: pick([1, 2]) },
    { symbol: 'BTCUSDT', size: '0.002', free: 0.002, usd: 134 },
    { symbol: ' nqz6 ', size: '1', type: '1' },
  ]));
  return { order, positions, want: R.protectsPosition(order, positions) };
}

const tf = new URL('../../tools/trade-fixtures.json', import.meta.url);
const T = JSON.parse(fs.readFileSync(tf, 'utf8'));
T.generated = Array.from({ length: 400 }, (_, i) => genCase(i));
T.protects = [{ order: null, positions: [], want: false }, { order: { symbol: 'NQZ6', side: 'sell' }, positions: null, want: false }, ...Array.from({ length: 120 }, (_, i) => genProtects(i))];
fs.writeFileSync(tf, appendKeys(fs.readFileSync(tf, 'utf8'), { generated: T.generated, protects: T.protects }));

// ------------------------------------------------------------------ sync: stamp, advance, batches, alreadyTaken, presence
const KINDS = ['task', 'memory', 'chat', 'log', 'profile', 'job', 'keys', 'tradestop'];
const fps = ['f1', 'f2', 'f3'];
function genStamp(i) {
  const keys = Array.from({ length: int(1, 6) }, () => pick(KINDS) + ':' + pick(['a', 'b', 'c', 'ü', 'x:y']));
  const sent = {}, dirty = {}, local = {}, gone = {};
  for (const k of keys) {
    if (chance(0.5)) sent[k] = pick(fps);
    if (chance(0.35)) dirty[k] = chance(0.3) ? { at: int(1, 90), del: true } : { at: int(1, 90), fp: pick(fps) };
    if (chance(0.55)) local[k] = { fp: pick(fps), ownTime: pick([0, 0, 40, 120]) };
    if (chance(0.2)) gone[k] = int(50, 99);
  }
  const skip = chance(0.2) ? [pick(KINDS)] : [], extraImplicit = chance(0.3) ? [pick(['job', 'keys'])] : [], first = chance(0.15);
  const s2 = structuredClone(sent), d2 = structuredClone(dirty);
  sync.stamp(s2, d2, local, gone, 100, first, skip, extraImplicit);
  return { name: `generated stamp ${i}`, sent, dirty, local, gone, now: 100, first, skip, extraImplicit, expectSent: s2, expectDirty: d2 };
}
function genAdvance(i) {
  const now = 1800000000000, cursor = int(0, 50);
  const recent = Array.from({ length: int(0, 25) }, () => [now + pick([-90000, -30001, -30000, -29999, -1000, 0, 59999, 60001, 3600000]) + int(0, 5), int(0, 120)]);
  const [c, r] = sync.advance(cursor, recent, now);
  return { name: `generated advance ${i}`, cursor, recent, now, expectCursor: c, expectRecent: r };
}
function genBatches(i) {
  const sizes = Array.from({ length: int(0, 30) }, () => pick([10, 200, 90000, 600000, 899999, 900000, 1400200, 1800000, 1800001]));
  if (chance(0.15)) for (let k = 0; k < 420; k++) sizes.push(pick([1, 300]));
  return { name: `generated batches ${i}`, sizes, expect: sync.batches(sizes) };
}
const sf = new URL('../../tools/sync-fixtures.json', import.meta.url);
const S = JSON.parse(fs.readFileSync(sf, 'utf8'));
S.generated = {
  about: 'Made by desktop/test/make-scan3-fixtures.mjs from sync.js (seeded, so the same every run); SyncPlanTest.kt must agree.',
  stamp: Array.from({ length: 300 }, (_, i) => genStamp(i)),
  advance: Array.from({ length: 80 }, (_, i) => genAdvance(i)),
  batches: Array.from({ length: 40 }, (_, i) => genBatches(i)),
  alreadyTaken: Array.from({ length: 40 }, () => { const seen = pick([-1, 0, 3, 7]), rev = pick([0, 1, 3, 7, 8]); return { seen, rev, want: sync.alreadyTaken(seen, rev) }; }),
  presenceDue: Array.from({ length: 40 }, () => { const now = 1800000000000, last = pick([0, -5, now - 600000, now - 599999, now - 600001, now, now + 1, now - 1]); return { last, now, want: sync.presenceDue(last, now) }; }),
  mineWins: Array.from({ length: 40 }, () => { const myAt = int(1, 3), theirAt = int(1, 3), me = pick(['a', 'b', 'B', 'é']), them = pick(['a', 'b', 'B', 'é']); return { myAt, me, theirAt, theirDevice: them, wins: sync.mineWins(myAt, me, theirAt, them) }; }),
};
fs.writeFileSync(sf, appendKeys(fs.readFileSync(sf, 'utf8'), { generated: S.generated }));
console.log('trade generated', T.generated.length, 'ok', T.generated.filter((c) => c.expect.ok).length, 'protects', T.protects.length, 'true', T.protects.filter((c) => c.want).length, '| sync stamp', S.generated.stamp.length);
