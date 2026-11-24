// Trading end to end: tools → risk engine → broker, against local copies of the Bybit and ProjectX APIs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const req = createRequire(import.meta.url);
const { createTrading } = req('../tradecore.js');
const brokers = req('../brokers.js');
const mock = req('./fixtures/brokers.js');

function world(srv, accounts, extra = {}) {
  let data = { accounts, ...extra };
  const audit = [];
  let t = Date.parse('2026-11-12T10:00:00Z');
  const tr = createTrading({
    store: { get: () => data, set: (d) => { data = d; } },
    secretFor: (id) => ({ a1: { key: 'k1', secret: 's1' }, a2: { key: 'k1', secret: 's1' }, a3: { userName: 'jane', apiKey: 'pk1' }, a4: { userName: 'jane', apiKey: 'pk1' } })[id],
    makeBroker: (a, s) => brokers.make({ ...a, base: srv.base }, s, { fetch }),
    audit: (e) => audit.push(e),
    now: () => t,
    local: () => ({ day: '2026-11-12', minuteOfDay: 600, isoDay: 4 }),
  });
  return { tr, audit, data: () => data, tick: (ms) => { t += ms; } };
}
const bybitPaper = { id: 'a1', name: 'Bybit demo', broker: 'bybit', mode: 'paper' };
const bybitLive = { id: 'a2', name: 'Bybit', broker: 'bybit', mode: 'live', limits: { maxOrderUsd: 100, maxDailyLossUsd: 40, maxOrdersPerDay: 2, maxOpenPositions: 2 } };
const pxPaper = { id: 'a3', name: 'TopstepX practice', broker: 'projectx', mode: 'paper', remoteId: 465 };
const pxLive = { id: 'a4', name: 'TopstepX combine', broker: 'projectx', mode: 'live', remoteId: 465, limits: { maxContracts: 1, maxDailyLossUsd: 500, allowDerivatives: true } };

test('PAPER: a dollar-sized market buy goes straight through, rounded to the lot step, signed correctly', async () => {
  const srv = await mock.start(); const w = world(srv, [bybitPaper]);
  try {
    const r = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 50 });
    assert.match(r.out, /^Sent\. PAPER · Bybit demo: BUY 0\.000746 BTCUSDT at market/);
    assert.equal(srv.log.length, 1);
    assert.deepEqual({ cat: srv.log[0].category, side: srv.log[0].side, type: srv.log[0].orderType, qty: srv.log[0].qty, unit: srv.log[0].marketUnit, lev: srv.log[0].isLeverage }, { cat: 'spot', side: 'Buy', type: 'Market', qty: '0.000746', unit: 'baseCoin', lev: 0 });
    assert.equal(w.data().days.a1.ordersToday, 1);
    assert.equal(w.audit[0].result, 'sent');
  } finally { srv.close(); }
});

test('LIVE: nothing reaches the broker until the user approves; the approval is single-use and expires', async () => {
  const srv = await mock.start(); const w = world(srv, [bybitLive]);
  try {
    const r = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 60 });
    assert.ok(r.need && r.ticket, 'asks first');
    assert.match(r.need, /^LIVE · Bybit: BUY 0\.000895 BTCUSDT at market \(about \$59\.97\)\. Worst case: the whole \$59\.97/);
    assert.equal(srv.log.length, 0, 'nothing sent before the OK');
    const ok = await w.tr.run('trade_order', {}, { ticket: r.ticket });
    assert.match(ok.out, /^Sent\. LIVE/); assert.equal(srv.log.length, 1);
    const again = await w.tr.run('trade_order', {}, { ticket: r.ticket });
    assert.match(again.out, /expired or was already used/); assert.equal(srv.log.length, 1, 'a ticket works once');
    const r2 = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 20 });
    w.tick(4 * 60e3);
    const late = await w.tr.run('trade_order', {}, { ticket: r2.ticket });
    assert.match(late.out, /expired/); assert.equal(srv.log.length, 1);
  } finally { srv.close(); }
});

test('the stop switch: cancels open orders, refuses new ones even with an approval in hand, and only the user lifts it', async () => {
  const srv = await mock.start(); const w = world(srv, [bybitLive]);
  try {
    const lim = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', qty: 0.001, type: 'limit', limit: 60000 });
    await w.tr.run('trade_order', {}, { ticket: lim.ticket });
    assert.equal(srv.st.bybitOrders.length, 1);
    const pending = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 20 });
    const s = await w.tr.run('trade_stop_all', { reason: 'user said stop' });
    assert.match(s.out, /STOPPED/); assert.equal(srv.st.bybitOrders.length, 0, 'open order cancelled');
    const after = await w.tr.run('trade_order', {}, { ticket: pending.ticket });
    assert.match(after.out, /stop switch is on/); assert.equal(srv.log.filter((x) => x.orderType).length, 1, 'nothing new sent');
    assert.equal(w.data().killed, true);
    w.tr.resume();
    assert.equal(w.data().killed, false);
  } finally { srv.close(); }
});

test('limits are enforced in code: size, trades per day, daily loss', async () => {
  const srv = await mock.start(); const w = world(srv, [bybitLive]);
  try {
    const big = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 150 });
    assert.match(big.out, /at most \$100\.00 per order/); assert.equal(srv.log.length, 0);
    for (let i = 0; i < 2; i++) { const r = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 10 }); await w.tr.run('trade_order', {}, { ticket: r.ticket }); }
    const third = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 10 });
    assert.match(third.out, /more than 2 trades today/);
    // a new day: count resets; the price drops so today's equity falls below the day's start
    const w2 = world(srv, [bybitLive], { days: { a2: { day: '2026-11-12', dayStartEquity: 700, ordersToday: 0 } } });
    srv.st.price = 50000;
    const loss = await w2.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 10 });
    assert.match(loss.out, /Today's loss is .* at the daily limit/);
    assert.equal(w2.audit.at(-1).result, 'refused');
  } finally { srv.close(); }
});

test('a confirmed plan: LIVE orders inside it go ahead without asking; outside it they still ask', async () => {
  const srv = await mock.start(); const w = world(srv, [bybitLive]);
  try {
    const p = await w.tr.run('trade_plan', { summary: 'Buy BTC on dips, small size, stop under the day low.', symbols: ['BTC'], sides: ['buy'], max_orders: 2, max_order_usd: 30, hours: 4 });
    assert.ok(p.need && /Losses are possible/.test(p.need));
    await w.tr.run('trade_plan', {}, { ticket: p.ticket });
    const inside = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 25 });
    assert.match(inside.out, /^Sent\./); assert.equal(srv.log.length, 1);
    const outside = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 50 });
    assert.ok(outside.need, 'bigger than the plan allows: asks');
    const prep = await w.tr.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 25 }, { prepare: true });
    assert.deepEqual(prep, { ok: true }, 'prepare sends nothing'); assert.equal(srv.log.length, 1);
  } finally { srv.close(); }
});

test('ProjectX: sign-in, contract lookup, brackets in ticks, and a stop-guarded fallback when brackets are off', async () => {
  const srv = await mock.start(); const w = world(srv, [pxPaper, pxLive]);
  try {
    const q = await w.tr.run('trade_quote', { symbol: 'NQ', account: 'practice' });
    assert.match(q.out, /NQZ6 .*last 21000.*Tick 0\.25 = \$5/);
    const p = await w.tr.run('trade_order', { account: 'practice', symbol: 'NQ', side: 'buy', qty: 1, stop_loss: 20990, take_profit: 21020 });
    assert.match(p.out, /^Sent\. PAPER · TopstepX practice: BUY 1 contract NQZ6/);
    assert.deepEqual({ t: srv.log[0].type, s: srv.log[0].side, size: srv.log[0].size, sl: srv.log[0].stopLossBracket, tp: srv.log[0].takeProfitBracket }, { t: 2, s: 0, size: 1, sl: { ticks: 40, type: 4 }, tp: { ticks: 80, type: 1 } });
    const noStop = await w.tr.run('trade_order', { account: 'combine', symbol: 'NQ', side: 'sell', qty: 1 });
    assert.match(noStop.out, /needs a stop loss/);
    srv.st.px.bracketsMode = false; srv.st.px.positions = [];
    const live = await w.tr.run('trade_order', { account: 'combine', symbol: 'NQ', side: 'sell', qty: 1, stop_loss: 21010 });
    assert.ok(live.need); const sent = await w.tr.run('trade_order', {}, { ticket: live.ticket });
    assert.match(sent.out, /stop placed as its own order/);
    const guard = srv.log.at(-1); assert.deepEqual({ t: guard.type, side: guard.side, stop: guard.stopPrice }, { t: 4, side: 0, stop: 21010 });
    const c = await w.tr.run('trade_close', { account: 'combine', symbol: 'NQ' });
    assert.ok(c.need && /closes or reduces/.test(c.need));
    await w.tr.run('trade_close', {}, { ticket: c.ticket });
    assert.equal(srv.log.at(-1).close, 'CON.F.US.ENQ.Z26');
    const acc = await w.tr.run('trade_account', { account: 'combine' });
    assert.match(acc.out, /Equity: \$50,000\.00/);
  } finally { srv.close(); }
});

test('wrong keys and unknown symbols give plain answers, never a crash', async () => {
  const srv = await mock.start({ bybitSecret: 'other' }); const w = world(srv, [bybitPaper]);
  try {
    const r = await w.tr.run('trade_account', {});
    assert.match(r.out, /^Error: Bybit said: error sign!/);
    const q = await w.tr.run('trade_quote', { symbol: 'NOPEUSDT' });
    assert.match(q.out, /^Error: Bybit has no spot market called NOPEUSDT/);
    const none = createTrading({ store: { get: () => ({}), set() {} }, secretFor: () => null, makeBroker: () => null, audit() {}, local: () => ({}) });
    assert.match((await none.run('trade_order', { symbol: 'BTCUSDT', side: 'buy', notional: 5 })).out, /No trading account is connected yet/);
  } finally { srv.close(); }
});

test('trading: a day that began without an equity reading still counts its loss once one comes in', async () => {
  const { createRequire } = await import('node:module');
  const R = createRequire(import.meta.url)('../traderules.js');
  let s = R.rollDay({}, '2026-11-24', Number(undefined));   // the first look of the day couldn't read the equity
  assert.equal(s.dayStartEquity, null);
  s = R.rollDay(s, '2026-11-24', 1000);                      // the next reading starts the day
  assert.equal(s.dayStartEquity, 1000);
  s = R.rollDay(s, '2026-11-24', 960);                       // and later readings don't move it
  assert.equal(s.dayStartEquity, 1000);
  assert.equal(R.lossToday(s, 960), 40);
});
