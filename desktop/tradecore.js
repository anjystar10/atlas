// Atlas trading: what each trading tool does, start to finish. Storage, secrets, the clock and the network are passed
// in (trading.js gives the real ones; the tests give fakes), so the whole path is tested without Electron.
//
// The rule that matters: an order is only ever sent from send(), and send() is only reached after check() passed with
// fresh numbers from the broker. LIVE orders also need the user's OK (a ticket the user approved on screen), unless a
// plan the user confirmed covers them. The model never approves anything and can't lift the stop switch.
'use strict';
const R = require('./traderules');
const { BrokerError } = require('./brokers');

const TICKET_MS = 3 * 60e3;   // an approval request is good for 3 minutes

function createTrading(deps) {
  const { store, secretFor, makeBroker, audit, now = () => Date.now(), local, notify = () => {} } = deps;
  const tickets = new Map();
  const brokers = new Map();

  function accounts() { return store.get().accounts || []; }
  function pick(ref) {
    const list = accounts();
    if (!list.length) throw new BrokerError('No trading account is connected yet. The user can connect one in Me → Trading (Bybit, or a prop firm on ProjectX such as TopstepX).', 'setup');
    if (ref == null || ref === '') { if (list.length === 1) return list[0]; const d = list.find((a) => a.default); if (d) return d; throw new BrokerError(`Say which account: ${list.map((a) => a.name).join(', ')}.`, 'which'); }
    const r = String(ref).toLowerCase();
    const a = list.find((x) => x.id === ref) || list.find((x) => x.name.toLowerCase() === r) || list.filter((x) => x.name.toLowerCase().includes(r)).reduce((m, x, i, all) => (all.length === 1 ? x : m), null);
    if (!a) throw new BrokerError(`There's no trading account called "${ref}". Connected: ${list.map((x) => x.name).join(', ')}.`, 'which');
    return a;
  }
  function broker(a) {
    const key = a.id + ':' + (a.updatedAt || 0);
    if (!brokers.has(key)) {
      const s = secretFor(a.id);
      if (!s) throw new BrokerError(`The keys for ${a.name} are missing; connect it again in Me → Trading.`, 'setup');
      brokers.set(key, makeBroker(a, s));
    }
    return brokers.get(key);
  }
  const label = (a) => `${a.mode === 'live' ? 'LIVE' : 'PAPER'} · ${a.name}`;

  /** Today's state for an account, rolled over to a new day (local time) with the equity just read. */
  function dayState(a, equity) {
    const s = store.get(); const day = local().day;
    const per = { ...(s.days || {}) };
    per[a.id] = R.rollDay(per[a.id] || {}, day, equity);
    store.set({ ...s, days: per });
    return per[a.id];
  }
  function bumpOrders(a) {
    const s = store.get(); const per = { ...(s.days || {}) };
    per[a.id] = { ...(per[a.id] || {}), ordersToday: ((per[a.id] || {}).ordersToday || 0) + 1 };
    store.set({ ...s, days: per });
  }
  function planFor(a) { const p = (store.get().plans || {})[a.id]; return p && (!p.expiresAt || p.expiresAt > now()) ? p : null; }
  function usePlan(a) { const s = store.get(); const plans = { ...(s.plans || {}) }; if (plans[a.id]) plans[a.id] = { ...plans[a.id], used: (plans[a.id].used || 0) + 1 }; store.set({ ...s, plans }); }

  // ---------------------------------------------------------------- the check, with fresh numbers

  async function assess(a, order) {
    const b = broker(a);
    const inst = await b.inst(order.symbol);
    const market = await b.quote(inst.symbol);
    const acct = await b.account();
    const d = dayState(a, acct.equity);
    const t = local();
    const state = { killed: !!store.get().killed, day: d.day, dayStartEquity: d.dayStartEquity, equity: acct.equity, ordersToday: d.ordersToday || 0,
      openSymbols: (acct.positions || []).map((p) => p.symbol), minuteOfDay: t.minuteOfDay, isoDay: t.isoDay, now: now() };
    const v = R.check({ ...order, symbol: inst.symbol }, inst, market, a, state, planFor(a));
    return { v, inst, market, acct, b };
  }

  async function send(a, verdict, assessed, args) {
    // the one place an order leaves Atlas
    const o = { ...verdict.order, refPrice: verdict.order.type === 'limit' ? verdict.order.price : assessed.market[verdict.order.side === 'buy' ? 'ask' : 'bid'] || assessed.market.last };
    const tag = 'atlas-' + now().toString(36) + '-' + Math.random().toString(36).slice(2, 6);
    let res;
    if (o.closing && o.closeContract && assessed.b.closeContract) { await assessed.b.closeContract(o.closeContract); res = { id: 'close', tag }; }
    else res = await assessed.b.place(o, tag);
    if (!o.closing) bumpOrders(a);
    if (verdict.coveredByPlan) usePlan(a);
    audit({ at: now(), account: a.name, mode: a.mode, action: o.closing ? 'close' : 'order', symbol: o.symbol, side: o.side, qty: o.qty, type: o.type, price: o.price || '', stopLoss: o.stopLoss || '', takeProfit: o.takeProfit || '', result: 'sent', orderId: res.id, note: (res.notes || []).join('; ') || (verdict.coveredByPlan ? 'inside the confirmed plan' : '') });
    if (a.mode === 'live') notify(`${label(a)}: order sent`, verdict.summary);
    return `Sent. ${verdict.summary}\nOrder id: ${res.id}${res.notes && res.notes.length ? '\n' + res.notes.join('\n') : ''}\n${a.mode === 'live' ? 'This was a LIVE order with real money.' : 'PAPER: no real money.'} Check fills with trade_account.`;
  }

  function ticketFor(kind, a, payload, summary) {
    const id = 't' + now().toString(36) + Math.random().toString(36).slice(2, 8);
    tickets.set(id, { kind, accountId: a.id, payload, summary, expires: now() + TICKET_MS });
    return id;
  }
  function takeTicket(id, kind) {
    const t = tickets.get(id); tickets.delete(id);
    if (!t || t.kind !== kind) throw new BrokerError('That approval has expired or was already used. Ask again.', 'ticket');
    if (t.expires < now()) throw new BrokerError('That approval has expired (3 minutes). Check the price again and ask again.', 'ticket');
    return t;
  }

  const refused = (a, order, v) => {
    audit({ at: now(), account: a.name, mode: a.mode, action: 'refused', symbol: String(order.symbol || '').toUpperCase(), side: order.side || '', qty: order.qty || order.notional || '', type: order.type || 'market', result: 'refused', note: v.reasons.map((r) => r.code).join(' ') });
    return { out: `Not done (${label(a)}): ${v.reasons.map((r) => r.text).join(' ')}` };
  };

  // ---------------------------------------------------------------- the tools

  /** The account an approval was made for (never whatever the model names now). */
  function ticketAccount(t) { const a = accounts().find((x) => x.id === t.accountId); if (!a) throw new BrokerError('That account was removed.', 'which'); return a; }

  async function order(args, opts) {
    if (opts.ticket) {
      const t = takeTicket(opts.ticket, 'order'); const a = ticketAccount(t);
      const fresh = await assess(a, t.payload);   // fresh numbers: the stop switch, today's loss and limits still decide
      if (!fresh.v.ok) return refused(a, t.payload, fresh.v);
      return { out: await send(a, fresh.v, fresh, args) };
    }
    const a = pick(args.account);
    const order = { symbol: args.symbol, side: args.side, qty: args.qty, notional: args.notional, type: args.type || (args.limit ? 'limit' : 'market'), limit: args.limit, stop: args.stop, stopLoss: args.stop_loss, takeProfit: args.take_profit };
    const as = await assess(a, order);
    if (opts.prepare && !(as.v.ok && as.v.needsApproval)) return { ok: true };   // nothing to ask; the real run checks again
    if (!as.v.ok) return refused(a, order, as.v);
    if (as.v.needsApproval) {
      const frozen = { ...as.v.order, qty: as.v.order.qty, notional: undefined, limit: as.v.order.price, stop: as.v.order.stop };
      const ticket = ticketFor('order', a, frozen, as.v.summary);
      return { need: as.v.summary, ticket, title: 'Place This LIVE Order?', button: 'Place Order' };
    }
    return { out: await send(a, as.v, as, args) };
  }

  async function close(args, opts) {
    if (opts.ticket) {
      const t = takeTicket(opts.ticket, 'close'); const a = ticketAccount(t);
      const fresh = await assess(a, t.payload);
      if (!fresh.v.ok) return refused(a, t.payload, fresh.v);
      fresh.v.order.closeContract = t.payload.closeContract;
      return { out: await send(a, fresh.v, fresh, args) };
    }
    const a = pick(args.account);
    const b = broker(a);
    const co = await b.closeOrder(args.symbol);
    if (!co && opts.prepare) return { ok: true };
    if (!co) return { out: `${label(a)}: there's no open position in ${String(args.symbol).toUpperCase()}.` };
    const as = await assess(a, co);
    if (opts.prepare && !(as.v.ok && as.v.needsApproval)) return { ok: true };
    if (!as.v.ok) return refused(a, co, as.v);
    as.v.order.closeContract = co.closeContract;
    if (as.v.needsApproval) return { need: as.v.summary, ticket: ticketFor('close', a, { ...co }, as.v.summary), title: 'Close This LIVE Position?', button: 'Close It' };
    return { out: await send(a, as.v, as, args) };
  }

  async function plan(args, opts) {
    if (opts.ticket) {
      const t = takeTicket(opts.ticket, 'plan'); const a = ticketAccount(t);
      const s = store.get(); store.set({ ...s, plans: { ...(s.plans || {}), [a.id]: { ...t.payload, confirmed: true, confirmedAt: now() } } });
      audit({ at: now(), account: a.name, mode: a.mode, action: 'plan', result: 'confirmed', note: t.payload.summary });
      return { out: `Plan confirmed for ${label(a)}. Orders inside it go ahead without asking until ${new Date(t.payload.expiresAt).toLocaleString()} or ${t.payload.maxOrders} orders; every account limit still applies. Losses are possible.` };
    }
    const a = pick(args.account);
    const L = R.limitsFor(a);
    const p = {
      id: 'p' + now().toString(36), accountId: a.id, summary: String(args.summary || '').slice(0, 1200),
      symbols: (args.symbols || []).map((x) => String(x).toUpperCase()).slice(0, 10), sides: (args.sides || []).filter((x) => x === 'buy' || x === 'sell'),
      maxOrders: Math.max(1, Math.min(Number(args.max_orders) || 1, L.maxOrdersPerDay)),
      maxOrderUsd: Math.min(Number(args.max_order_usd) || L.maxOrderUsd, L.maxOrderUsd),
      maxContracts: Math.min(Number(args.max_contracts) || L.maxContracts, L.maxContracts),
      expiresAt: now() + Math.max(1, Math.min(Number(args.hours) || 24, 24 * 7)) * 3600e3, used: 0, confirmed: false,
    };
    if (!p.summary) return { out: 'Error: describe the plan (strategy, entries and exits, risk per trade) in summary.' };
    const text = `${label(a)}\n${p.summary}\n\nUp to ${p.maxOrders} order(s)${p.symbols.length ? ' in ' + p.symbols.join(', ') : ''}${p.sides.length ? ' (' + p.sides.join('/') + ' only)' : ''}, each at most ${R.money(p.maxOrderUsd)} (spot) or ${p.maxContracts} contract(s) (futures), for ${Math.round((p.expiresAt - now()) / 3600e3)} hours. Every account limit still applies. Losses are possible; nothing is guaranteed.`;
    if (opts.prepare && a.mode !== 'live') return { ok: true };
    if (a.mode !== 'live') {
      const s = store.get(); store.set({ ...s, plans: { ...(s.plans || {}), [a.id]: { ...p, confirmed: true, confirmedAt: now() } } });
      return { out: `PAPER plan saved (no OK needed for paper):\n${text}` };
    }
    return { need: text, ticket: ticketFor('plan', a, p, text), title: 'Confirm This Trading Plan?', button: 'Confirm Plan' };
  }

  async function account(args) {
    const a = pick(args.account); const b = broker(a);
    const acct = await b.account();
    const d = dayState(a, acct.equity);
    const L = R.limitsFor(a); const lost = R.lossToday(d, acct.equity);
    const p = planFor(a);
    const lines = [`${label(a)} (${a.broker === 'bybit' ? 'Bybit spot' : 'ProjectX futures'})${store.get().killed ? ' — TRADING STOPPED (stop switch on)' : ''}`,
      `Equity: ${R.money(acct.equity)}${acct.cash != null && acct.cash !== acct.equity ? ` (cash ${R.money(acct.cash)})` : ''}. Today: ${acct.equity >= d.dayStartEquity ? 'up ' + R.money(acct.equity - d.dayStartEquity) : 'down ' + R.money(lost)} since the day started; ${d.ordersToday || 0} of ${L.maxOrdersPerDay} trades used; daily loss limit ${R.money(L.maxDailyLossUsd)}.`,
      `Positions: ${acct.positions.length ? acct.positions.map((x) => `${x.symbol} ${x.size}${x.avgPrice ? ' @ ' + x.avgPrice : ''}${x.usd ? ' (' + R.money(x.usd) + ')' : ''}`).join('; ') : 'none'}.`,
      `Open orders: ${acct.orders.length ? acct.orders.map((o) => `#${o.id} ${o.side} ${o.qty} ${o.symbol} ${o.type}${o.price ? ' ' + o.price : ''}`).join('; ') : 'none'}.`,
      p ? `Confirmed plan: ${p.used || 0} of ${p.maxOrders} orders used, until ${new Date(p.expiresAt).toLocaleString()}.` : 'No confirmed plan (LIVE orders each ask).'];
    return { out: lines.join('\n') };
  }

  async function quote(args) {
    const a = pick(args.account); const b = broker(a);
    const inst = await b.inst(args.symbol); const q = await b.quote(inst.symbol);
    return { out: `${q.symbol} (${a.broker === 'bybit' ? 'Bybit' : 'ProjectX'}${q.note ? ', ' + q.note : ''}): bid ${q.bid}, ask ${q.ask}, last ${q.last}${q.change24h != null && Number.isFinite(q.change24h) ? `, 24h ${(q.change24h * 100).toFixed(2)}%` : ''}.${inst.futures ? ` Tick ${inst.tickSize} = $${inst.tickValue}.` : ''}` };
  }

  async function history(args) {
    const a = pick(args.account); const b = broker(a);
    const h = await b.history(Math.max(1, Math.min(Number(args.days) || 7, 30)));
    if (!h.length) return { out: `${label(a)}: no fills in that time.` };
    return { out: `${label(a)}, recent fills (newest first):\n` + h.slice(0, 40).map((x) => `- ${x.time ? new Date(x.time).toLocaleString() : ''} ${x.side} ${x.qty} ${x.symbol} @ ${x.price}${x.pnl != null ? ` P&L ${x.pnl}` : ''}${x.fee ? ` fee ${x.fee}` : ''}`).join('\n') };
  }

  async function cancel(args) {
    const a = pick(args.account); const b = broker(a);
    const acct = await b.account();
    const which = args.order_id ? acct.orders.filter((o) => String(o.id) === String(args.order_id)) : args.all ? acct.orders : [];
    if (!which.length) return { out: args.order_id ? `${label(a)}: no open order #${args.order_id}.` : 'Say which order (order_id), or all: true.' };
    const done = [];
    for (const o of which) { try { await b.cancel(o.id, o.symbol); done.push('#' + o.id); } catch (e) { done.push(`#${o.id} (not cancelled: ${e.message})`); } }
    audit({ at: now(), account: a.name, mode: a.mode, action: 'cancel', result: 'sent', note: done.join(' ') });
    return { out: `${label(a)}: cancelled ${done.join(', ')}.` };   // cancelling only lowers risk: no OK needed
  }

  /** The stop switch: no new orders anywhere, open orders cancelled. Only the user can turn trading back on. */
  async function stopAll(args) {
    const s = store.get(); store.set({ ...s, killed: true, killedAt: now(), killReason: String(args.reason || '').slice(0, 200), plans: {} });
    const notes = [];
    for (const a of accounts()) {
      try { const b = broker(a); const acct = await b.account(); for (const o of acct.orders) { try { await b.cancel(o.id, o.symbol); notes.push(`${a.name} #${o.id} cancelled`); } catch (e) { notes.push(`${a.name} #${o.id} NOT cancelled (${e.message})`); } } }
      catch (e) { notes.push(`${a.name}: couldn't reach it (${e.message})`); }
    }
    audit({ at: now(), account: '*', mode: '', action: 'stop_all', result: 'stopped', note: [args.reason, ...notes].filter(Boolean).join('; ') });
    notify('Trading stopped', 'No new orders on any account until you turn trading back on in Me → Trading.');
    return { out: `Trading is STOPPED on every account. ${notes.length ? notes.join('; ') + '. ' : ''}Open positions were left as they are (close them with trade_close if the user wants). Only the user can turn trading back on, in Me → Trading.` };
  }

  function accountsList() {
    const list = accounts();
    if (!list.length) return { out: 'No trading accounts are connected. The user can connect one in Me → Trading.' };
    return { out: (store.get().killed ? 'TRADING IS STOPPED (stop switch on).\n' : '') + list.map((a) => { const L = R.limitsFor(a); return `- ${a.name}: ${a.mode === 'live' ? 'LIVE (real money; each order asks unless a confirmed plan covers it)' : 'PAPER (practice; no OK needed)'}, ${a.broker === 'bybit' ? 'Bybit spot' : 'ProjectX futures'}. Limits: ${a.broker === 'bybit' ? R.money(L.maxOrderUsd) + ' per order' : L.maxContracts + ' contract(s) per order'}, ${R.money(L.maxDailyLossUsd)} daily loss, ${L.maxOrdersPerDay} trades a day, ${L.maxOpenPositions} open positions${L.symbols.length ? ', only ' + L.symbols.join('/') : ''}${L.hours ? `, ${L.hours.start}–${L.hours.end}` : ''}.`; }).join('\n') };
  }

  async function run(name, args = {}, opts = {}) {
    try {
      if (opts.prepare && !['trade_order', 'trade_close', 'trade_plan'].includes(name)) return { ok: true };   // only these ever ask
      switch (name) {
        case 'trade_accounts': return accountsList();
        case 'trade_account': return await account(args);
        case 'trade_quote': return await quote(args);
        case 'trade_history': return await history(args);
        case 'trade_order': return await order(args, opts);
        case 'trade_close': return await close(args, opts);
        case 'trade_cancel': return await cancel(args);
        case 'trade_plan': return await plan(args, opts);
        case 'trade_stop_all': return await stopAll(args);
        default: return { out: `Error: no trading tool called ${name}.` };
      }
    } catch (e) {
      return { out: `Error: ${e instanceof BrokerError ? e.message : 'something went wrong (' + (e && e.message) + ')'}` };
    }
  }

  /** The user (never the model) turns trading back on. */
  function resume() { const s = store.get(); store.set({ ...s, killed: false, resumedAt: now() }); audit({ at: now(), account: '*', mode: '', action: 'resume', result: 'resumed', note: 'by the user' }); }

  return { run, resume, pick, tickets };
}

module.exports = { createTrading, TICKET_MS };
