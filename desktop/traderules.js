// Atlas trading: the risk engine. Pure (no network, no Electron), so it is tested on its own and the phone's copy
// (app/src/main/java/com/anjy/atlas/TradeRules.kt) is held to the same answers by tools/trade-fixtures.json.
//
// Every order passes through check() in the main process right before it would be sent. An order it refuses never
// reaches the broker. The model can't change the limits, can't lift the stop switch, and can't approve a LIVE order:
// only the user can, on screen.
'use strict';

const DEFAULT_LIMITS = {
  maxOrderUsd: 50,        // spot: the most one order may cost
  maxContracts: 1,        // futures: the most contracts in one order
  maxDailyLossUsd: 25,    // stop opening new trades after losing this much today (from the day's starting equity)
  maxOpenPositions: 2,
  maxOrdersPerDay: 2,     // the user's own rule: two trades a day
  symbols: [],            // allowed symbols; empty = any
  hours: null,            // { start: 'HH:MM', end: 'HH:MM', days: [1..7] } in the user's local time; null = any time
  allowDerivatives: false // LIVE futures only when the user turns this on for the account
};
const PAPER_LIMITS = { ...DEFAULT_LIMITS, maxOrderUsd: 1000, maxContracts: 2, maxDailyLossUsd: 1000, maxOpenPositions: 5, maxOrdersPerDay: 20 };

function limitsFor(account) {
  const base = account && account.mode === 'live' ? DEFAULT_LIMITS : PAPER_LIMITS;
  return { ...base, ...((account && account.limits) || {}) };
}

// ---------------------------------------------------------------- exact decimals (prices and sizes as strings)

function decimalsOf(step) { const s = String(step); const i = s.indexOf('.'); return i < 0 ? 0 : s.length - i - 1; }
/** value rounded DOWN to a multiple of step (sizes: never more than asked), as a plain decimal string. */
function floorTo(value, step) {
  const d = decimalsOf(step), st = Number(step), v = Number(value);
  if (!(st > 0) || !Number.isFinite(v)) return null;
  const n = Math.floor(v / st + 1e-9);
  return (n * st).toFixed(d);
}
/** value rounded to the NEAREST multiple of step (prices), as a plain decimal string. */
function roundTo(value, step) {
  const d = decimalsOf(step), st = Number(step), v = Number(value);
  if (!(st > 0) || !Number.isFinite(v)) return null;
  const n = Math.round(v / st);
  return (n * st).toFixed(d);
}
const money = (x) => '$' + (Math.round(Number(x) * 100) / 100).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

// ---------------------------------------------------------------- time

function minutesOf(hhmm) { const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || '').trim()); return m ? Number(m[1]) * 60 + Number(m[2]) : null; }
/** Is local time (minute of day, ISO weekday 1..7) inside the trading hours? Windows that cross midnight work too. */
function inHours(hours, minuteOfDay, isoDay) {
  if (!hours) return true;
  if (Array.isArray(hours.days) && hours.days.length && !hours.days.includes(isoDay)) return false;
  const s = minutesOf(hours.start), e = minutesOf(hours.end);
  if (s == null || e == null) return true;
  return s <= e ? minuteOfDay >= s && minuteOfDay < e : minuteOfDay >= s || minuteOfDay < e;
}

/** A new day: start equity is now, order count back to 0. [day] is the user's local date 'YYYY-MM-DD'. */
function rollDay(state, day, equity) {
  if (state.day === day) return { ...state };
  return { ...state, day, dayStartEquity: equity, ordersToday: 0 };
}
function lossToday(state, equity) {
  if (state.dayStartEquity == null || equity == null) return 0;
  return Math.max(0, Number(state.dayStartEquity) - Number(equity));
}

// ---------------------------------------------------------------- the check

/**
 * order:  { symbol, side: 'buy'|'sell', qty?, notional?, type: 'market'|'limit'|'stop', limit?, stop?, stopLoss?, takeProfit?, closing? }
 * inst:   { tickSize, qtyStep, minQty, minNotional, pointValue (1 for spot), integerQty, futures }
 * market: { bid, ask, last }
 * account:{ id, name, broker, mode: 'paper'|'live', limits }
 * state:  { killed, day, dayStartEquity, equity, ordersToday, openSymbols: [], minuteOfDay, isoDay }
 * plan:   the user's confirmed plan for this account, or null
 * →  { ok: false, reasons: [{code, text}] }  or  { ok: true, order: {...normalised}, needsApproval, summary, worstCaseUsd, estUsd }
 */
function check(order, inst, market, account, state, plan) {
  const L = limitsFor(account), live = account.mode === 'live';
  const reasons = [], no = (code, text) => reasons.push({ code, text });
  const side = String(order.side || '').toLowerCase();
  const type = String(order.type || 'market').toLowerCase();
  const sym = String(order.symbol || '').toUpperCase();
  const closing = !!order.closing;
  const futures = !!inst.futures;
  const pv = Number(inst.pointValue || 1);

  if (side !== 'buy' && side !== 'sell') no('side', 'Say buy or sell.');
  if (!['market', 'limit', 'stop'].includes(type)) no('type', 'The order type must be market, limit or stop.');
  if (state.killed && !closing) no('killed', 'Trading is stopped (the stop switch is on). Only the user can turn it back on, in Me → Trading.');
  if (live && futures && !L.allowDerivatives && !closing) no('derivatives_off', 'This is a LIVE futures account and futures trading is off for it. The user can turn it on in Me → Trading.');
  if (!closing && L.symbols && L.symbols.length && !L.symbols.map((s) => String(s).toUpperCase()).some((s) => sym === s || sym.startsWith(s))) no('symbol', `${sym} isn't on this account's allowed list (${L.symbols.join(', ')}).`);
  if (!closing && !inHours(L.hours, state.minuteOfDay, state.isoDay)) no('hours', `It's outside the trading hours set for this account (${L.hours.start}–${L.hours.end}).`);

  // the price this order will (roughly) fill at
  const ref = type === 'limit' ? Number(order.limit) : type === 'stop' ? Number(order.stop) : Number(side === 'buy' ? (market.ask || market.last) : (market.bid || market.last));
  if (type === 'limit' && !(Number(order.limit) > 0)) no('price_needed', 'A limit order needs a limit price.');
  if (type === 'stop' && !(Number(order.stop) > 0)) no('price_needed', 'A stop order needs a stop price.');
  if (!reasons.some((r) => r.code === 'price_needed') && !(ref > 0)) no('no_price', "There's no current price for this symbol, so the order can't be sized or checked.");
  if (reasons.length) return { ok: false, reasons };

  // size
  let qty;
  if (order.qty != null && order.qty !== '') qty = Number(order.qty);
  else if (order.notional != null && order.notional !== '') qty = futures ? NaN : Number(order.notional) / ref;
  if (!(qty > 0)) { no('size', futures ? 'Say how many contracts.' : 'Say how much: a quantity, or an amount in dollars.'); return { ok: false, reasons }; }
  const step = futures || inst.integerQty ? 1 : inst.qtyStep;
  const q = floorTo(qty, step);
  const qn = Number(q);
  const estUsd = qn * ref * pv;
  if (!(qn > 0) || (inst.minQty && qn < Number(inst.minQty) - 1e-12)) no('too_small', `That's below the smallest order for ${sym} (${inst.minQty || step}).`);
  else if (!futures && inst.minNotional && estUsd < Number(inst.minNotional) - 1e-9) no('too_small', `That's below the smallest order value for ${sym} (${money(inst.minNotional)}).`);
  const price = type === 'limit' ? roundTo(order.limit, inst.tickSize) : null;
  const stopPx = type === 'stop' ? roundTo(order.stop, inst.tickSize) : null;
  const sl = order.stopLoss != null && order.stopLoss !== '' ? roundTo(order.stopLoss, inst.tickSize) : null;
  const tp = order.takeProfit != null && order.takeProfit !== '' ? roundTo(order.takeProfit, inst.tickSize) : null;
  if (sl != null && ((side === 'buy' && Number(sl) >= ref) || (side === 'sell' && Number(sl) <= ref))) no('stop_side', `The stop loss (${sl}) is on the wrong side of the price (${ref}).`);
  if (tp != null && ((side === 'buy' && Number(tp) <= ref) || (side === 'sell' && Number(tp) >= ref))) no('tp_side', `The take profit (${tp}) is on the wrong side of the price (${ref}).`);

  // worst case: what this order can lose if it goes wrong. With a stop: the distance to it. Spot bought with no stop:
  // at worst it goes to zero (shown to the user; the per-order size limit is what bounds it). Futures with no stop: no cap.
  let worst = null;
  if (sl != null) worst = Math.abs(ref - Number(sl)) * qn * pv;
  else if (!futures && side === 'buy') worst = estUsd;
  const stopRisk = sl != null ? worst : null;   // the risk counted against today's loss limit
  if (!closing) {
    if (futures && qn > L.maxContracts) no('contracts', `That's ${qn} contracts; this account allows at most ${L.maxContracts} per order.`);
    if (!futures && estUsd > L.maxOrderUsd + 1e-9) no('order_size', `That's about ${money(estUsd)}; this account allows at most ${money(L.maxOrderUsd)} per order.`);
    if (live && futures && sl == null) no('needs_stop', 'A LIVE futures order needs a stop loss, so the most it can lose is known.');
    const open = (state.openSymbols || []).map((s) => String(s).toUpperCase());
    if (!open.includes(sym) && open.length >= L.maxOpenPositions) no('positions', `There are already ${open.length} open positions; this account allows ${L.maxOpenPositions}.`);
    if ((state.ordersToday || 0) >= L.maxOrdersPerDay) no('orders_today', `That would be more than ${L.maxOrdersPerDay} trades today, the daily limit for this account.`);
    const lost = lossToday(state, state.equity);
    if (lost >= L.maxDailyLossUsd - 1e-9) no('daily_loss', `Today's loss is ${money(lost)}, at the daily limit of ${money(L.maxDailyLossUsd)}. No new trades until tomorrow.`);
    else if (stopRisk != null && lost + stopRisk > L.maxDailyLossUsd + 1e-9) no('risk_exceeds', `If this goes wrong it could lose ${money(worst)}; with ${money(lost)} lost today that passes the daily limit of ${money(L.maxDailyLossUsd)}. Use a smaller size or a tighter stop.`);
  }
  if (reasons.length) return { ok: false, reasons };

  const norm = { symbol: sym, side, type, qty: q, price, stop: stopPx, stopLoss: sl, takeProfit: tp, closing };
  const byPlan = live && planCovers(plan, account, norm, estUsd, futures, state);
  const byLimits = live && !byPlan && standingCovers(state.standing, account, norm, estUsd, worst, futures, state);
  const needsApproval = live && !byPlan && !byLimits;
  return { ok: true, order: norm, needsApproval, coveredByPlan: byPlan, coveredByLimits: byLimits, estUsd, worstCaseUsd: worst, summary: summary(account, norm, ref, estUsd, worst, futures) };
}

/**
 * The user's pre-approved LIVE limits (set by their own tap in Me → Trading, never by the model): orders inside them go
 * without a tap. Off unless set; they need an expiry date and a list of instruments, and the worst case must be known.
 * standing: { accountId, enabled, maxOrderUsd, maxContracts, maxDailyLossUsd, symbols: [...], expiresAt }
 */
function standingCovers(standing, account, o, estUsd, worst, futures, state) {
  const s = standing;
  if (!s || !s.enabled || s.accountId !== account.id) return false;
  if (!(Number(s.expiresAt) > 0) || state.now == null || state.now > Number(s.expiresAt)) return false;
  const syms = Array.isArray(s.symbols) ? s.symbols.map((x) => String(x).toUpperCase()).filter(Boolean) : [];
  if (!syms.length || !syms.some((x) => o.symbol === x || o.symbol.startsWith(x))) return false;
  if (o.closing) return true;   // closing or reducing a position on an allowed instrument only lowers the risk
  if (futures ? !(Number(o.qty) <= Number(s.maxContracts || 0)) : !(estUsd <= Number(s.maxOrderUsd || 0) + 1e-9)) return false;
  if (worst == null) return false;   // a loss with no cap is never pre-approved
  const lost = lossToday(state, state.equity);
  return lost + worst <= Number(s.maxDailyLossUsd || 0) + 1e-9;
}

/** The user confirmed a plan: LIVE orders inside it don't ask again (still inside every account limit). */
function planCovers(plan, account, o, estUsd, futures, state) {
  if (!plan || plan.accountId !== account.id || !plan.confirmed) return false;
  if (state.now != null && plan.expiresAt && state.now > plan.expiresAt) return false;
  if ((plan.used || 0) >= (plan.maxOrders || 0)) return false;
  if (Array.isArray(plan.symbols) && plan.symbols.length && !plan.symbols.map((s) => String(s).toUpperCase()).some((s) => o.symbol === s || o.symbol.startsWith(s))) return false;
  if (Array.isArray(plan.sides) && plan.sides.length && !plan.sides.includes(o.side)) return false;
  if (futures ? Number(o.qty) > Number(plan.maxContracts || 0) : estUsd > Number(plan.maxOrderUsd || 0) + 1e-9) return false;
  return true;
}

function summary(account, o, ref, estUsd, worst, futures) {
  const mode = account.mode === 'live' ? 'LIVE' : 'PAPER';
  const what = `${o.side.toUpperCase()} ${o.qty}${futures ? (Number(o.qty) === 1 ? ' contract' : ' contracts') : ''} ${o.symbol}`;
  const at = o.type === 'limit' ? `limit ${o.price}` : o.type === 'stop' ? `stop ${o.stop}` : 'at market';
  const parts = [`${mode} · ${account.name}: ${what} ${at} (about ${futures ? money(estUsd) + ' of contract value' : money(estUsd)}).`];
  if (o.stopLoss) parts.push(`Stop loss ${o.stopLoss}.`);
  if (o.takeProfit) parts.push(`Take profit ${o.takeProfit}.`);
  parts.push(o.closing ? 'This closes or reduces a position.' : worst == null ? 'Worst case: no stop, so the loss isn\'t capped.' : o.stopLoss ? `Worst case: about ${money(worst)} lost if the stop is hit (more if the price jumps past it).` : `Worst case: the whole ${money(worst)} if the price went to zero; there's no stop.`);
  return parts.join(' ');
}

// ---------------------------------------------------------------- Bybit request signing (bybit-exchange.github.io/docs/v5/guide)

/** The string Bybit signs: timestamp + api key + recv window + (query string for GET | JSON body for POST). */
function bybitPayload(ts, apiKey, recvWindow, queryOrBody) { return String(ts) + apiKey + String(recvWindow) + (queryOrBody || ''); }

module.exports = { DEFAULT_LIMITS, PAPER_LIMITS, limitsFor, floorTo, roundTo, decimalsOf, inHours, rollDay, lossToday, check, planCovers, standingCovers, summary, bybitPayload, money };
