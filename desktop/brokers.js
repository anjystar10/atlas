// Atlas trading: the brokers. Each one turns Atlas's few trading actions into its own API calls.
//  - Bybit (spot only, V5): bybit-exchange.github.io/docs/v5 . PAPER = Bybit's Demo Trading (api-demo.bybit.com).
//  - ProjectX Gateway (TopstepX and other prop firms on ProjectX): gateway.docs.projectx.com . Futures.
// No margin, no leverage, no perpetuals: Bybit orders are always category=spot with isLeverage=0.
// The network is passed in (fetch), so tests run against a local copy of each API.
'use strict';
const crypto = require('crypto');
const { bybitPayload } = require('./traderules');

class BrokerError extends Error { constructor(msg, code) { super(msg); this.code = code; } }

async function readJson(r) {
  const text = await r.text();
  try { return JSON.parse(text); } catch { throw new BrokerError(`The broker sent back something unreadable (HTTP ${r.status}).`, 'bad_reply'); }
}

// ================================================================ Bybit (spot)

const BYBIT = { paper: 'https://api-demo.bybit.com', live: 'https://api.bybit.com', testnet: 'https://api-testnet.bybit.com' };

function bybit({ key, secret, base, fetch: f = fetch, now = () => Date.now() }) {
  let offset = 0;   // the server's clock minus ours, learnt when Bybit says our time is off
  const RECV = '5000';
  async function call(method, path, params = {}, signed = false, retried = false) {
    const ts = String(now() + offset);
    let url = base + path, body;
    const headers = { 'Content-Type': 'application/json' };
    let toSign = '';
    if (method === 'GET') {
      const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== '')).toString();
      if (q) url += '?' + q;
      toSign = q;
    } else { body = JSON.stringify(params); toSign = body; }
    if (signed) {
      headers['X-BAPI-API-KEY'] = key; headers['X-BAPI-TIMESTAMP'] = ts; headers['X-BAPI-RECV-WINDOW'] = RECV;
      headers['X-BAPI-SIGN'] = crypto.createHmac('sha256', secret).update(bybitPayload(ts, key, RECV, toSign)).digest('hex');
    }
    let r;
    try { r = await f(url, { method, headers, body, signal: AbortSignal.timeout(15000) }); }
    catch (e) { throw new BrokerError(`Couldn't reach Bybit (${e.cause && e.cause.code || e.name || e.message}).`, 'network'); }
    if (r.status === 401 || r.status === 403) throw new BrokerError('Bybit turned down the API key (check it, its permissions and its IP restriction).', 'auth');
    const j = await readJson(r);
    if (j.retCode === 10002 && !retried) {   // our clock is off: learn the server's time and try once more
      try { const t = await readJson(await f(base + '/v5/market/time', { signal: AbortSignal.timeout(10000) })); offset = Number(t.time || t.result && t.result.timeNano / 1e6) - now(); } catch {}
      return call(method, path, params, signed, true);
    }
    if (j.retCode !== 0) throw new BrokerError(`Bybit said: ${j.retMsg || 'error'} (code ${j.retCode}).`, j.retCode === 10003 || j.retCode === 10004 || j.retCode === 10005 ? 'auth' : 'refused');
    return j.result || {};
  }
  const insts = new Map();
  return {
    name: 'Bybit',
    async inst(symbol) {
      const s = String(symbol).toUpperCase();
      if (insts.has(s)) return insts.get(s);
      const res = await call('GET', '/v5/market/instruments-info', { category: 'spot', symbol: s });
      const i = (res.list || [])[0];
      if (!i) throw new BrokerError(`Bybit has no spot market called ${s}. Use the pair name, like BTCUSDT.`, 'symbol');
      const lot = i.lotSizeFilter || {}, pf = i.priceFilter || {};
      const out = { symbol: s, tickSize: pf.tickSize, qtyStep: lot.basePrecision, minQty: lot.minOrderQty, minNotional: lot.minOrderAmt, pointValue: 1, futures: false, tpslLimitOnly: true, quote: i.quoteCoin, base: i.baseCoin };
      insts.set(s, out); return out;
    },
    async quote(symbol) {
      const res = await call('GET', '/v5/market/tickers', { category: 'spot', symbol: String(symbol).toUpperCase() });
      const t = (res.list || [])[0];
      if (!t) throw new BrokerError(`No price for ${symbol} on Bybit.`, 'symbol');
      return { symbol: t.symbol, bid: Number(t.bid1Price), ask: Number(t.ask1Price), last: Number(t.lastPrice), change24h: Number(t.price24hPcnt) };
    },
    /** equity in USD, the coins held (as positions: anything worth more than $1 other than dollars) and open orders. */
    async account() {
      const w = await call('GET', '/v5/account/wallet-balance', { accountType: 'UNIFIED' }, true);
      const acct = (w.list || [])[0] || {};
      const coins = (acct.coin || []).map((c) => ({ coin: c.coin, amount: Number(c.walletBalance), free: Number(c.walletBalance) - Number(c.locked || 0), usd: Number(c.usdValue), pnl: Number(c.unrealisedPnl || 0) }));
      const stable = new Set(['USDT', 'USDC', 'USD', 'DAI', 'FDUSD']);
      const positions = coins.filter((c) => !stable.has(c.coin) && c.usd > 1).map((c) => ({ symbol: c.coin + 'USDT', size: c.amount, free: c.free, usd: c.usd }));
      const o = await call('GET', '/v5/order/realtime', { category: 'spot' }, true);
      const orders = (o.list || []).map((x) => ({ id: x.orderId, symbol: x.symbol, side: String(x.side).toLowerCase(), type: String(x.orderType).toLowerCase(), qty: x.qty, price: x.price, status: x.orderStatus }));
      return { equity: Number(acct.totalEquity), cash: coins.filter((c) => stable.has(c.coin)).reduce((a, c) => a + c.usd, 0), coins, positions, orders };
    },
    async place(o, tag) {
      if (o.type === 'stop') throw new BrokerError('Stop entry orders aren\'t supported on Bybit spot here. Use a market or limit order with a stop loss.', 'unsupported');
      const p = { category: 'spot', symbol: o.symbol, side: o.side === 'buy' ? 'Buy' : 'Sell', orderType: o.type === 'limit' ? 'Limit' : 'Market', qty: String(o.qty), isLeverage: 0, orderLinkId: tag };
      if (o.type === 'market') p.marketUnit = 'baseCoin';
      if (o.type === 'limit') { p.price = String(o.price); p.timeInForce = 'GTC'; }
      if (o.stopLoss) p.stopLoss = String(o.stopLoss);
      if (o.takeProfit) p.takeProfit = String(o.takeProfit);
      const r = await call('POST', '/v5/order/create', p, true);
      return { id: r.orderId, tag: r.orderLinkId || tag };
    },
    async cancel(id, symbol) { await call('POST', '/v5/order/cancel', { category: 'spot', symbol: String(symbol).toUpperCase(), orderId: String(id) }, true); return true; },
    /** A closing order for everything held of [symbol] (sell the free balance). */
    async closeOrder(symbol) {
      const a = await this.account(); const s = String(symbol).toUpperCase();
      const pos = a.positions.find((p) => p.symbol === s);
      if (!pos) return null;
      return { symbol: s, side: 'sell', qty: pos.free, type: 'market', closing: true };
    },
    async history(days = 7) {
      const r = await call('GET', '/v5/execution/list', { category: 'spot', limit: 50, startTime: now() - Math.min(days, 7) * 86400000 }, true);
      return (r.list || []).map((x) => ({ time: Number(x.execTime), symbol: x.symbol, side: String(x.side).toLowerCase(), qty: x.execQty, price: x.execPrice, fee: x.execFee, orderId: x.orderId }));
    },
  };
}

// ================================================================ ProjectX Gateway (prop-firm futures)

const PX_TYPE = { limit: 1, market: 2, stop: 4 };
const PX_SIDE = { buy: 0, sell: 1 };

function projectx({ userName, apiKey, base = 'https://api.topstepx.com', accountId, fetch: f = fetch, now = () => Date.now() }) {
  let token = null, tokenAt = 0;
  async function login() {
    let r;
    try { r = await f(base + '/api/Auth/loginKey', { method: 'POST', headers: { 'Content-Type': 'application/json', accept: 'text/plain' }, body: JSON.stringify({ userName, apiKey }), signal: AbortSignal.timeout(15000) }); }
    catch (e) { throw new BrokerError(`Couldn't reach ${base} (${e.cause && e.cause.code || e.name || e.message}).`, 'network'); }
    const j = await readJson(r);
    if (!j.success || !j.token) throw new BrokerError(`The prop firm turned down the sign-in: ${j.errorMessage || 'check the username (not the email) and the API key'}.`, 'auth');
    token = j.token; tokenAt = now();
  }
  async function call(path, body, retried = false) {
    if (!token || now() - tokenAt > 23 * 3600e3) await login();   // a token lasts 24 hours
    let r;
    try { r = await f(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', accept: 'text/plain', Authorization: 'Bearer ' + token }, body: JSON.stringify(body), signal: AbortSignal.timeout(15000) }); }
    catch (e) { throw new BrokerError(`Couldn't reach ${base} (${e.cause && e.cause.code || e.name || e.message}).`, 'network'); }
    if (r.status === 401 && !retried) { token = null; return call(path, body, true); }
    if (r.status === 429) throw new BrokerError('The prop firm says too many requests; wait a little and try again.', 'busy');
    const j = await readJson(r);
    if (j.success === false || (j.errorCode && j.errorCode !== 0)) { const e = new BrokerError(`The prop firm said: ${j.errorMessage || 'error'} (code ${j.errorCode}).`, 'refused'); e.pxCode = j.errorCode; throw e; }
    return j;
  }
  const contracts = new Map();
  const acct = () => { if (accountId == null || accountId === '') throw new BrokerError('No trading account is chosen for this connection yet (Me → Trading).', 'setup'); return Number(accountId); };
  async function contract(symbol) {
    const s = String(symbol).toUpperCase();
    if (contracts.has(s)) return contracts.get(s);
    let c;
    if (s.startsWith('CON.')) {
      const j = await call('/api/Contract/searchById', { contractId: s });
      c = j.contract;
    } else {
      const j = await call('/api/Contract/search', { searchText: s, live: false });
      const list = j.contracts || [];
      c = list.find((x) => String(x.name).toUpperCase() === s) || list.find((x) => x.activeContract && String(x.name).toUpperCase().startsWith(s)) || list.find((x) => x.activeContract);
    }
    if (!c) throw new BrokerError(`No contract called ${s} found. Try the root (NQ, ES, GC, MNQ) or the full name (NQZ6).`, 'symbol');
    const out = { id: c.id, name: String(c.name).toUpperCase(), description: c.description, tickSize: String(c.tickSize), tickValue: Number(c.tickValue), pointValue: Number(c.tickValue) / Number(c.tickSize), futures: true, qtyStep: '1', minQty: '1' };
    contracts.set(s, out); contracts.set(out.name, out); return out;
  }
  return {
    name: 'ProjectX',
    async accounts() { const j = await call('/api/Account/search', { onlyActiveAccounts: true }); return (j.accounts || []).map((a) => ({ id: a.id, name: a.name, balance: a.balance, canTrade: a.canTrade })); },
    async inst(symbol) { const c = await contract(symbol); return { ...c, symbol: c.name }; },
    async quote(symbol) {
      const c = await contract(symbol);
      const end = new Date(now()), start = new Date(now() - 20 * 60e3);
      const j = await call('/api/History/retrieveBars', { contractId: c.id, live: false, startTime: start.toISOString(), endTime: end.toISOString(), unit: 2, unitNumber: 1, limit: 20, includePartialBar: true });
      const bars = j.bars || [];
      const b = bars.reduce((a, x) => (!a || String(x.t) > String(a.t) ? x : a), null);
      if (!b) throw new BrokerError(`No recent price for ${c.name} (the market may be closed).`, 'no_price');
      return { symbol: c.name, bid: Number(b.c), ask: Number(b.c), last: Number(b.c), at: b.t, note: 'last 1-minute close' };
    },
    async account() {
      const id = acct();
      const accts = await call('/api/Account/search', { onlyActiveAccounts: true });
      const a = (accts.accounts || []).find((x) => Number(x.id) === id);
      if (!a) throw new BrokerError(`Account ${id} isn't active at the prop firm any more.`, 'setup');
      const pos = await call('/api/Position/searchOpen', { accountId: id });
      const ord = await call('/api/Order/searchOpen', { accountId: id });
      const byId = new Map(); for (const c of contracts.values()) byId.set(c.id, c.name);
      const nameOf = (cid) => byId.get(cid) || String(cid);
      return {
        equity: Number(a.balance), cash: Number(a.balance), canTrade: a.canTrade,
        positions: (pos.positions || []).map((p) => ({ symbol: nameOf(p.contractId), contractId: p.contractId, size: p.size, avgPrice: p.averagePrice, type: p.type })),
        orders: (ord.orders || []).map((o) => ({ id: o.id, symbol: nameOf(o.contractId), contractId: o.contractId, side: o.side === 0 ? 'buy' : 'sell', type: Object.keys(PX_TYPE).find((k) => PX_TYPE[k] === o.type) || String(o.type), qty: o.size, price: o.limitPrice ?? o.stopPrice, status: o.status })),
      };
    },
    /** Entry plus its protection. Brackets (stop loss / take profit in ticks) only work in the account's Auto OCO mode;
     *  otherwise a separate stop order guards the position, and if even that fails the position is closed at once. */
    async place(o, tag) {
      const id = acct(), c = await contract(o.symbol), tick = Number(c.tickSize);
      const body = { accountId: id, contractId: c.id, type: PX_TYPE[o.type], side: PX_SIDE[o.side], size: Number(o.qty), limitPrice: o.type === 'limit' ? Number(o.price) : null, stopPrice: o.type === 'stop' ? Number(o.stop) : null, trailPrice: null, customTag: tag };
      const ref = Number(o.refPrice);
      const ticks = (p) => Math.max(1, Math.round(Math.abs(ref - Number(p)) / tick));
      if (o.stopLoss) body.stopLossBracket = { ticks: ticks(o.stopLoss), type: PX_TYPE.stop };
      if (o.takeProfit) body.takeProfitBracket = { ticks: ticks(o.takeProfit), type: PX_TYPE.limit };
      try { const j = await call('/api/Order/place', body); return { id: j.orderId, tag }; }
      catch (e) {
        if (!(e.pxCode === 2 && (body.stopLossBracket || body.takeProfitBracket))) throw e;
        // the stop and target can only go as their own orders. That is safe once a market entry has filled; a limit or stop
        // entry may not fill for a while, and a stop or target resting without its position would open one the other way
        if (o.type !== 'market') throw new BrokerError("This prop firm account doesn't take a stop loss or target attached to an order (it's in Position Brackets mode), so a limit or stop entry can't carry its protection. Nothing was placed. Use a market order, or turn on Auto OCO Brackets in the platform's Risk Settings.", 'refused');
      }
      // brackets refused (Position Brackets mode): entry, then a protective stop (and the target) as their own orders
      delete body.stopLossBracket; delete body.takeProfitBracket;
      const entry = await call('/api/Order/place', body);
      const notes = [];
      const opp = o.side === 'buy' ? PX_SIDE.sell : PX_SIDE.buy;
      if (o.stopLoss) {
        try { await call('/api/Order/place', { accountId: id, contractId: c.id, type: PX_TYPE.stop, side: opp, size: Number(o.qty), limitPrice: null, stopPrice: Number(o.stopLoss), trailPrice: null, customTag: tag + '-sl' }); notes.push('stop placed as its own order'); }
        catch (e) {
          try { await call('/api/Position/closeContract', { accountId: id, contractId: c.id }); } catch {}
          throw new BrokerError(`The entry filled but the protective stop was refused (${e.message}), so Atlas closed the position straight away.`, 'unprotected');
        }
      }
      if (o.takeProfit) { try { await call('/api/Order/place', { accountId: id, contractId: c.id, type: PX_TYPE.limit, side: opp, size: Number(o.qty), limitPrice: Number(o.takeProfit), stopPrice: null, trailPrice: null, customTag: tag + '-tp' }); notes.push('take profit placed as its own order'); } catch (e) { notes.push('take profit could not be placed: ' + e.message); } }
      return { id: entry.orderId, tag, notes };
    },
    async cancel(orderId) { await call('/api/Order/cancel', { accountId: acct(), orderId: Number(orderId) }); return true; },
    async closeOrder(symbol) {
      const a = await this.account(); const c = await contract(symbol);
      const p = a.positions.find((x) => x.contractId === c.id);
      if (!p) return null;
      return { symbol: c.name, side: p.type === 2 ? 'buy' : 'sell', qty: String(p.size), type: 'market', closing: true, closeContract: c.id };
    },
    async closeContract(contractId) { await call('/api/Position/closeContract', { accountId: acct(), contractId }); return true; },
    async history(days = 7) {
      const j = await call('/api/Trade/search', { accountId: acct(), startTimestamp: new Date(now() - days * 86400e3).toISOString(), endTimestamp: null });
      return (j.trades || []).filter((t) => !t.voided).map((t) => ({ time: Date.parse(t.creationTimestamp) || null, symbol: String(t.contractId), side: t.side === 0 ? 'buy' : 'sell', qty: t.size, price: t.price, pnl: t.profitAndLoss, fee: (Number(t.fees) || 0) + (Number(t.commissions) || 0), orderId: t.orderId }));
    },
  };
}

function make(account, secret, deps = {}) {
  if (account.broker === 'bybit') return bybit({ key: secret.key, secret: secret.secret, base: account.base || (account.mode === 'live' ? BYBIT.live : BYBIT.paper), ...deps });
  if (account.broker === 'projectx') return projectx({ userName: secret.userName, apiKey: secret.apiKey, base: account.base || 'https://api.topstepx.com', accountId: account.remoteId, ...deps });
  throw new BrokerError(`Atlas doesn't know the broker "${account.broker}".`, 'setup');
}

module.exports = { make, bybit, projectx, BYBIT, BrokerError };
