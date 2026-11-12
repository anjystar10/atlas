// A local stand-in for the two brokers' APIs, shaped like their docs (Bybit V5, ProjectX Gateway), for tests.
// Bybit requests are signature-checked exactly as Bybit does (HMAC-SHA256 of ts + key + recv + query/body).
'use strict';
const http = require('http');
const crypto = require('crypto');

function start({ bybitKey = 'k1', bybitSecret = 's1', pxUser = 'jane', pxKey = 'pk1' } = {}) {
  const log = [];          // every order that reached the "broker"
  const st = {
    btc: 0.002, usdt: 500, price: 67000,
    bybitOrders: [],
    px: { balance: 50000, positions: [], orders: [], bracketsMode: true, token: 'tok-1' },
  };
  const send = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
  const ok = (result) => ({ retCode: 0, retMsg: 'OK', result, retExtInfo: {}, time: Date.now() });

  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (d) => { body += d; });
    req.on('end', () => {
      const u = new URL(req.url, 'http://x');
      // ---------------- Bybit
      if (u.pathname.startsWith('/v5/')) {
        const signed = !u.pathname.startsWith('/v5/market/');
        if (signed) {
          const ts = req.headers['x-bapi-timestamp'], key = req.headers['x-bapi-api-key'], recv = req.headers['x-bapi-recv-window'], sign = req.headers['x-bapi-sign'];
          const payload = ts + key + recv + (req.method === 'GET' ? u.search.slice(1) : body);
          const want = crypto.createHmac('sha256', bybitSecret).update(payload).digest('hex');
          if (key !== bybitKey || sign !== want) return send(res, 200, { retCode: 10004, retMsg: 'error sign!', result: {} });
        }
        const q = Object.fromEntries(u.searchParams);
        const b = body ? JSON.parse(body) : {};
        switch (u.pathname) {
          case '/v5/market/instruments-info':
            if (q.symbol !== 'BTCUSDT') return send(res, 200, ok({ category: 'spot', list: [] }));
            return send(res, 200, ok({ category: 'spot', list: [{ symbol: 'BTCUSDT', baseCoin: 'BTC', quoteCoin: 'USDT', lotSizeFilter: { basePrecision: '0.000001', quotePrecision: '0.00000001', minOrderQty: '0.000048', maxOrderQty: '71.73956243', minOrderAmt: '1', maxOrderAmt: '2000000' }, priceFilter: { tickSize: '0.01' } }] }));
          case '/v5/market/tickers':
            return send(res, 200, ok({ category: 'spot', list: [{ symbol: 'BTCUSDT', bid1Price: String(st.price - 0.1), ask1Price: String(st.price + 0.1), lastPrice: String(st.price), price24hPcnt: '0.0123' }] }));
          case '/v5/account/wallet-balance': {
            const btcUsd = st.btc * st.price;
            return send(res, 200, ok({ list: [{ accountType: 'UNIFIED', totalEquity: String(btcUsd + st.usdt), coin: [{ coin: 'BTC', walletBalance: String(st.btc), locked: '0', usdValue: String(btcUsd), unrealisedPnl: '0' }, { coin: 'USDT', walletBalance: String(st.usdt), locked: '0', usdValue: String(st.usdt), unrealisedPnl: '0' }] }] }));
          }
          case '/v5/order/realtime': return send(res, 200, ok({ list: st.bybitOrders.map((o) => ({ orderId: o.id, symbol: o.symbol, side: o.side, orderType: o.orderType, qty: o.qty, price: o.price || '0', orderStatus: 'New' })) }));
          case '/v5/order/create': {
            if (b.category !== 'spot' || String(b.isLeverage) !== '0') return send(res, 200, { retCode: 170001, retMsg: 'only plain spot is allowed in this test', result: {} });
            const id = 'by' + (log.length + 1);
            log.push({ broker: 'bybit', ...b, id });
            if (b.orderType === 'Market') { const qn = Number(b.qty); if (b.side === 'Buy') { st.btc += qn; st.usdt -= qn * st.price; } else { st.btc -= qn; st.usdt += qn * st.price; } }
            else st.bybitOrders.push({ id, symbol: b.symbol, side: b.side, orderType: b.orderType, qty: b.qty, price: b.price });
            return send(res, 200, ok({ orderId: id, orderLinkId: b.orderLinkId }));
          }
          case '/v5/order/cancel': st.bybitOrders = st.bybitOrders.filter((o) => o.id !== b.orderId); log.push({ broker: 'bybit', cancel: b.orderId }); return send(res, 200, ok({ orderId: b.orderId }));
          case '/v5/execution/list': return send(res, 200, ok({ list: log.filter((x) => x.broker === 'bybit' && x.orderType === 'Market').map((x) => ({ execTime: String(Date.now()), symbol: x.symbol, side: x.side, execQty: x.qty, execPrice: String(st.price), execFee: '0.01', orderId: x.id })) }));
          default: return send(res, 404, { retCode: 404, retMsg: 'not found' });
        }
      }
      // ---------------- ProjectX
      if (u.pathname.startsWith('/api/')) {
        const b = body ? JSON.parse(body) : {};
        if (u.pathname === '/api/Auth/loginKey') return send(res, 200, b.userName === pxUser && b.apiKey === pxKey ? { token: st.px.token, success: true, errorCode: 0, errorMessage: null } : { token: null, success: false, errorCode: 3, errorMessage: 'bad key' });
        if (req.headers.authorization !== 'Bearer ' + st.px.token) return send(res, 401, {});
        const done = (x = {}) => send(res, 200, { ...x, success: true, errorCode: 0, errorMessage: null });
        const NQ = { id: 'CON.F.US.ENQ.Z26', name: 'NQZ6', description: 'E-mini NASDAQ-100: December 2026', tickSize: 0.25, tickValue: 5, activeContract: true, symbolId: 'F.US.ENQ' };
        switch (u.pathname) {
          case '/api/Account/search': return done({ accounts: [{ id: 465, name: 'PRAC-V2-1', balance: st.px.balance, canTrade: true, isVisible: true }] });
          case '/api/Contract/search': return done({ contracts: String(b.searchText).toUpperCase().startsWith('NQ') ? [NQ] : [] });
          case '/api/Contract/searchById': return done({ contract: b.contractId === NQ.id ? NQ : null });
          case '/api/History/retrieveBars': return done({ bars: [{ t: new Date(Date.now() - 60e3).toISOString(), o: 21000, h: 21002, l: 20998, c: 21000, v: 10 }] });
          case '/api/Position/searchOpen': return done({ positions: st.px.positions });
          case '/api/Order/searchOpen': return done({ orders: st.px.orders });
          case '/api/Order/place': {
            if ((b.stopLossBracket || b.takeProfitBracket) && !st.px.bracketsMode) return send(res, 200, { orderId: null, success: false, errorCode: 2, errorMessage: 'Brackets need Auto OCO mode' });
            const id = 9000 + log.length;
            log.push({ broker: 'projectx', ...b, id });
            if (b.type === 2) st.px.positions = [{ id: 1, accountId: b.accountId, contractId: b.contractId, creationTimestamp: new Date().toISOString(), type: b.side === 0 ? 1 : 2, size: b.size, averagePrice: 21000 }];
            else st.px.orders.push({ id, accountId: b.accountId, contractId: b.contractId, status: 1, type: b.type, side: b.side, size: b.size, limitPrice: b.limitPrice, stopPrice: b.stopPrice });
            return done({ orderId: id });
          }
          case '/api/Order/cancel': st.px.orders = st.px.orders.filter((o) => o.id !== b.orderId); log.push({ broker: 'projectx', cancel: b.orderId }); return done();
          case '/api/Position/closeContract': st.px.positions = st.px.positions.filter((p) => p.contractId !== b.contractId); log.push({ broker: 'projectx', close: b.contractId }); return done();
          case '/api/Trade/search': return done({ trades: [{ id: 1, accountId: 465, contractId: NQ.id, creationTimestamp: new Date().toISOString(), price: 21000, profitAndLoss: null, fees: 1.4, commissions: 0, side: 0, size: 1, voided: false, orderId: 9001 }] });
          default: return send(res, 404, { success: false, errorCode: 404, errorMessage: 'not found' });
        }
      }
      send(res, 404, {});
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ base: `http://127.0.0.1:${server.address().port}`, log, st, close: () => server.close() })));
}

module.exports = { start };
