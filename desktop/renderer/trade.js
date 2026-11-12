// Trading on the PC, the page's side: the tools Atlas and Atlas Bot get, how a LIVE order waits for the user's tap,
// and Me → Trading (accounts, limits, the stop switch). The keys, the risk engine and every order live in the main
// process (trading.js); this side can only ask it. The model never approves an order and can't lift the stop switch.
import * as aware from './aware.js';
const host = window.atlasHost;

const S = (d) => ({ type: 'string', description: d });
const N = (d) => ({ type: 'number', description: d });
const ACCOUNT = S('Which trading account (its name). Leave empty when there is only one.');
export const TOOLS = [
  { name: 'trade_accounts', description: "The user's connected trading accounts: PAPER (practice) or LIVE (real money), the broker, and each account's limits. Check this before trading.", parameters: { type: 'object', properties: {} } },
  { name: 'trade_account', description: 'One account now: equity, today\'s gain or loss, trades used today, positions, open orders, and any confirmed plan.', parameters: { type: 'object', properties: { account: ACCOUNT } } },
  { name: 'trade_quote', description: 'The current price of a symbol. Bybit: spot pairs like BTCUSDT. ProjectX (prop firm futures): NQ, MNQ, ES, MES, GC, MGC, or a full contract like NQZ6.', parameters: { type: 'object', properties: { symbol: S('The symbol.'), account: ACCOUNT }, required: ['symbol'] } },
  { name: 'trade_history', description: 'Recent fills (trades that happened) on an account.', parameters: { type: 'object', properties: { account: ACCOUNT, days: N('How many days back (1–30, default 7).') } } },
  { name: 'trade_order', description: 'Place an order. Atlas\'s risk engine checks it first against the account\'s limits (size, trades a day, daily loss, symbols, hours, the stop switch) and refuses what breaks them. PAPER orders go straight through. A LIVE order shows the user exactly what it is and its worst case and waits for their tap, unless a plan they confirmed (trade_plan) covers it. Spot: give qty (in the coin) or notional (dollars). Futures: qty in contracts, and LIVE futures need stop_loss. Never promise profit.', parameters: { type: 'object', properties: {
    account: ACCOUNT, symbol: S('The symbol (BTCUSDT; NQ, ES, GC…).'), side: { type: 'string', enum: ['buy', 'sell'] },
    qty: N('Quantity (coins, or contracts).'), notional: N('Spot only: how many dollars to spend instead of a quantity.'),
    type: { type: 'string', enum: ['market', 'limit', 'stop'], description: 'Default market.' }, limit: N('Limit price (limit orders).'), stop: N('Stop price (stop entry orders, futures).'),
    stop_loss: N('Stop-loss price: where to get out if it goes wrong.'), take_profit: N('Take-profit price.') }, required: ['symbol', 'side'] } },
  { name: 'trade_close', description: 'Close (sell out of) a whole position. LIVE: waits for the user\'s tap.', parameters: { type: 'object', properties: { account: ACCOUNT, symbol: S('The symbol to close.') }, required: ['symbol'] } },
  { name: 'trade_cancel', description: 'Cancel an open order (order_id), or all open orders (all: true). Cancelling never needs an OK.', parameters: { type: 'object', properties: { account: ACCOUNT, order_id: S('The order id from trade_account.'), all: { type: 'boolean' } } } },
  { name: 'trade_plan', description: 'When the user says "trade this money for me": propose a plan first (strategy, entries and exits, risk per trade, and that losses are possible). The user confirms it once (LIVE) and then orders inside it go ahead without asking each time, within the account limits; report each fill. The plan can\'t raise any limit.', parameters: { type: 'object', properties: {
    account: ACCOUNT, summary: S('The plan in plain words: what, why, entries, exits, risk per trade, and that losses are possible.'), symbols: { type: 'array', items: { type: 'string' }, description: 'The only symbols it may trade.' },
    sides: { type: 'array', items: { type: 'string', enum: ['buy', 'sell'] } }, max_orders: N('How many orders at most.'), max_order_usd: N('Spot: the most per order, in dollars.'), max_contracts: N('Futures: the most contracts per order.'), hours: N('How long the plan lasts (hours, default 24, at most a week).') }, required: ['summary'] } },
  { name: 'trade_stop_all', description: 'The stop switch: stop all trading on every account at once (open orders are cancelled; no new orders). Use it whenever the user says stop trading, or something looks wrong. Only the user can turn trading back on (Me → Trading).', parameters: { type: 'object', properties: { reason: S('Why, in a few words.') } } },
];
export const NAMES = new Set(TOOLS.map((t) => t.name));

const LABEL = { trade_accounts: 'Checked your trading accounts', trade_account: 'Checked a trading account', trade_quote: 'Checked a price', trade_history: 'Checked recent trades', trade_order: 'Placed an order', trade_close: 'Closed a position', trade_cancel: 'Cancelled an order', trade_plan: 'Proposed a trading plan', trade_stop_all: 'Stopped all trading' };
export const label = (c) => (NAMES.has(c.name) ? (c.name === 'trade_order' && c.args ? `Order: ${c.args.side || ''} ${c.args.symbol || ''}`.trim() : LABEL[c.name]) : null);

let confirm = async () => false;   // the app's tap-to-confirm sheet (set by the app)
let last = null;                   // the latest account list, for the brief
export function setConfirm(fn) { confirm = fn; }
export async function refresh() { try { last = await host.trading.list(); } catch {} return last; }

/** Chat: a LIVE order shows the confirm sheet right away; only a tap sends it ("yes" in chat isn't enough for money). */
async function runChat(call) {
  if (!host || !host.trading) return { out: "Error: trading isn't available here." };
  const args = call.args || {};
  let r = await host.trading.run(call.name, args, {});
  if (r && r.need) {
    const yes = await confirm(r.title || 'Allow This?', r.need, r.button || 'Allow');
    if (!yes) return { out: `The user didn't approve it, so nothing was sent: ${r.need}`, step: 'You said no: ' + r.need.slice(0, 60) };
    r = await host.trading.run(call.name, {}, { ticket: r.ticket });
  }
  refresh();
  return { out: r.out, step: label(call) };
}

// ---------------------------------------------------------------- Atlas Bot: the approval goes through the job page

const ticketsByCall = new Map();   // the approval a bot is waiting on, by job and call
const key = (job, call) => `${job || ''}|${call.name}|${JSON.stringify(call.args || {})}`;
/** Before a bot's trade runs: does it need the user's tap? (LIVE, and no confirmed plan covers it.) Money: always asks. */
export async function checkForBot(call, job) {
  if (!NAMES.has(call.name) || !host || !host.trading) return null;
  const r = await host.trading.run(call.name, call.args || {}, { prepare: true });
  if (!r || !r.need) return null;
  ticketsByCall.set(key(job && job.id, call), r.ticket);
  return { what: r.need, mustAsk: true, riskText: 'buy pay order' };
}
export async function runForBot(call, opts = {}) {
  const k = key(opts.job && opts.job.id, call);
  const ticket = opts.approved ? ticketsByCall.get(k) : undefined;
  ticketsByCall.delete(k);
  const r = await host.trading.run(call.name, call.args || {}, ticket ? { ticket } : {});
  refresh();
  if (r && r.need) return { out: 'Not sent: this LIVE order needs the user\'s OK and the earlier approval expired. Ask again (it will wait for their tap).' };
  return { out: r.out, step: label(call) };
}

// ---------------------------------------------------------------- the main Atlas sees it

if (host && host.trading) {
  refresh();
  aware.register({
    id: 'trading', forBots: false,   // bots get trading through botwire (their approvals go through the job page)
    tools: () => TOOLS,
    run: async (call) => (NAMES.has(call.name) ? runChat(call) : null),
    brief: () => {
      if (!last || !last.accounts || !last.accounts.length) return null;
      const parts = last.accounts.map((a) => `${a.name} ${a.mode === 'live' ? 'LIVE' : 'PAPER'}${a.today ? `, ${a.today.ordersToday || 0}/${a.limits.maxOrdersPerDay} trades today` : ''}${a.plan && a.plan.confirmed ? ', plan active' : ''}`);
      return `Trading${last.killed ? ' (STOPPED: the stop switch is on)' : ''}: ${parts.join('; ')}. "Stop all trading" → trade_stop_all.`;
    },
  });
  try { host.on('trading-changed', () => refresh()); } catch {}
}

// ---------------------------------------------------------------- Me → Trading

const money = (x) => '$' + Number(x || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Draws Me → Trading into [col]. [ui]: { h, esc, sheet, toast, render } from the app. */
export function renderTrading(col, ui) {
  const { h, esc, sheet, toast, render } = ui;
  col.appendChild(h('<div class="eyebrow">Trading</div>'));
  const card = h('<div class="card"></div>'); col.appendChild(card);
  const d = last || { accounts: [], killed: false };
  card.appendChild(h(`<div style="font:400 22px var(--serif)">${d.killed ? 'Trading is stopped' : d.accounts.length ? `${d.accounts.length} account${d.accounts.length === 1 ? '' : 's'}` : 'No accounts yet'}</div>`));
  card.appendChild(h('<p class="muted" style="margin:4px 0 12px">Atlas can trade for you through Bybit (spot crypto) or a prop firm on ProjectX (TopstepX and others: futures). Practice (PAPER) accounts go straight through; every LIVE order shows you exactly what it is and its worst case and waits for your tap, unless you confirmed a plan. Your limits are checked in code before anything is sent. Keys stay on this PC, encrypted; they don\'t sync. Trading can lose money.</p>'));
  const list = h('<div class="list"></div>');
  for (const a of d.accounts) {
    const L = a.limits;
    const row = h(`<div class="item" style="display:block"><div class="row" style="align-items:flex-start"><div class="grow" style="min-width:0">
      <div class="t">${esc(a.name)} <span class="tag ${a.mode === 'live' ? 'bad' : 'ok'}">${a.mode === 'live' ? 'LIVE' : 'PAPER'}</span></div>
      <div class="small muted">${a.broker === 'bybit' ? 'Bybit spot' : 'ProjectX futures · ' + esc(a.base || '')}${a.remoteId ? ' · account ' + esc(String(a.remoteId)) : ''}</div>
      <div class="small muted">${a.broker === 'bybit' ? money(L.maxOrderUsd) + ' per order' : L.maxContracts + ' contract(s) per order'} · ${money(L.maxDailyLossUsd)} daily loss · ${L.maxOrdersPerDay} trades a day · ${L.maxOpenPositions} open${L.symbols && L.symbols.length ? ' · only ' + esc(L.symbols.join(', ')) : ''}${L.hours ? ' · ' + esc(L.hours.start + '–' + L.hours.end) : ''}${a.broker === 'projectx' && a.mode === 'live' ? (L.allowDerivatives ? ' · futures on' : ' · futures off') : ''}</div></div>
      <button class="btn" data-k="limits">Limits</button><button class="btn" data-k="edit">Edit</button></div></div>`);
    row.querySelector('[data-k=limits]').onclick = () => limitsSheet(a, ui);
    row.querySelector('[data-k=edit]').onclick = () => accountSheet(a, ui);
    list.appendChild(row);
  }
  if (d.accounts.length) card.appendChild(list);
  const actions = h('<div class="row" style="gap:8px;margin-top:12px;flex-wrap:wrap"></div>');
  const add = h('<button class="btn primary">Connect an Account</button>'); add.onclick = () => accountSheet(null, ui); actions.appendChild(add);
  if (d.killed) { const on = h('<button class="btn">Turn Trading Back On</button>'); on.onclick = async () => { if (await host.trading.resume()) { await refresh(); toast('Trading is back on'); render(true); } }; actions.appendChild(on); }
  else if (d.accounts.length) { const stop = h('<button class="btn danger">Stop All Trading</button>'); stop.onclick = async () => { if (await sheet({ title: 'Stop All Trading?', body: 'Open orders are cancelled and no new orders go out, on every account, until you turn trading back on here. Open positions stay as they are.', primary: 'Stop Trading', danger: true })) { await host.trading.run('trade_stop_all', { reason: 'the user, in Me → Trading' }); await refresh(); toast('Trading stopped'); render(true); } }; actions.appendChild(stop); }
  card.appendChild(actions);
}

async function accountSheet(a, ui) {
  const { h, esc, sheet, toast, render } = ui;
  const isPx = (b) => b === 'projectx';
  const html = `<div class="list" style="gap:10px">
    <label class="small muted">Broker<select data-f="broker" class="field"><option value="bybit">Bybit (spot crypto)</option><option value="projectx">Prop firm on ProjectX (TopstepX and others)</option></select></label>
    <label class="small muted">Name<input data-f="name" class="field" placeholder="e.g. Bybit demo" value="${esc(a ? a.name : '')}"></label>
    <label class="small muted">Mode<select data-f="mode" class="field"><option value="paper">PAPER: practice, no real money (Bybit Demo Trading, or a ProjectX practice account)</option><option value="live">LIVE: real money (or an evaluation you'd lose by breaking its rules)</option></select></label>
    <div data-g="bybit"><label class="small muted">API key<input data-f="key" class="field" autocomplete="off" placeholder="${a ? '(unchanged)' : ''}"></label><label class="small muted">API secret<input data-f="secret" type="password" class="field" autocomplete="off" placeholder="${a ? '(unchanged)' : ''}"></label>
      <p class="small muted">Make the key on Bybit (for PAPER: switch to Demo Trading first, then API). Give it Trade permission only for Spot, no withdrawals, and a read-only key is fine to start.</p></div>
    <div data-g="projectx"><label class="small muted">Firm's API address<input data-f="base" class="field" value="${esc(a && a.base ? a.base : 'https://api.topstepx.com')}"></label><label class="small muted">Platform username (not the email)<input data-f="userName" class="field" autocomplete="off" placeholder="${a ? '(unchanged)' : ''}"></label><label class="small muted">API key<input data-f="apiKey" type="password" class="field" autocomplete="off" placeholder="${a ? '(unchanged)' : ''}"></label>
      <label class="small muted">Account<select data-f="remoteId" class="field">${a && a.remoteId ? `<option value="${esc(String(a.remoteId))}">${esc(String(a.remoteId))}</option>` : '<option value="">Tap Check to load your accounts</option>'}</select></label>
      <p class="small muted">TopstepX: API Access is a paid add-on, set up in TopstepX → Settings → API. Topstep doesn't allow trading through the API on funded live accounts, or from a VPS; Atlas runs on this PC.</p></div>
    <div class="row" style="gap:8px"><button class="btn" data-k="check" type="button">Check</button><span class="small muted" data-f="status"></span></div></div>`;
  let saved = false;
  const ok = await sheet({ title: a ? 'Edit ' + a.name : 'Connect a Trading Account', html, primary: 'Save', onOpen: (o) => {
    const f = (n) => o.querySelector(`[data-f=${n}]`);
    f('broker').value = a ? a.broker : 'bybit'; f('mode').value = a ? a.mode : 'paper';
    if (a) f('broker').disabled = true;
    const show = () => { o.querySelector('[data-g=bybit]').style.display = isPx(f('broker').value) ? 'none' : ''; o.querySelector('[data-g=projectx]').style.display = isPx(f('broker').value) ? '' : 'none'; };
    f('broker').onchange = show; show();
    o.querySelector('[data-k=check]').onclick = async () => {
      f('status').textContent = 'Checking…';
      const { acc, secret } = read(o, a);
      const r = await host.trading.test(acc, secret);
      if (r.error) { f('status').textContent = r.error; return; }
      if (r.accounts) { f('remoteId').innerHTML = r.accounts.map((x) => `<option value="${esc(String(x.id))}">${esc(x.name)} · ${money(x.balance)}${x.canTrade ? '' : ' (can\'t trade)'}</option>`).join(''); f('status').textContent = `Signed in. ${r.accounts.length} account(s).`; }
      else f('status').textContent = `Works. Equity ${money(r.equity)}.`;
    };
  }, onPrimary: async (o) => {
    const { acc, secret } = read(o, a);
    const r = await host.trading.save(acc, secret);
    if (r.error) { o.querySelector('[data-f=status]').textContent = r.error; return false; }
    saved = true; return true;
  } });
  if (ok && saved) { await refresh(); toast('Saved'); render(true); }
  if (a && !ok) { /* closed without saving */ }
}
function read(o, a) {
  const v = (n) => (o.querySelector(`[data-f=${n}]`) || {}).value || '';
  const broker = a ? a.broker : v('broker');
  const acc = { id: a ? a.id : undefined, name: v('name'), broker, mode: v('mode'), base: v('base'), remoteId: v('remoteId'), limits: a ? a.limits : undefined };
  const secret = broker === 'projectx' ? { userName: v('userName').trim(), apiKey: v('apiKey').trim() } : { key: v('key').trim(), secret: v('secret').trim() };
  return { acc, secret: Object.values(secret).every((x) => x) ? secret : null };
}

async function limitsSheet(a, ui) {
  const { h, esc, sheet, toast, render } = ui;
  const L = a.limits;
  const fut = a.broker === 'projectx';
  const html = `<div class="list" style="gap:10px">
    ${fut ? `<label class="small muted">Most contracts per order<input data-f="maxContracts" type="number" min="1" class="field" value="${L.maxContracts}"></label>` : `<label class="small muted">Most per order (dollars)<input data-f="maxOrderUsd" type="number" min="1" class="field" value="${L.maxOrderUsd}"></label>`}
    <label class="small muted">Stop for the day after losing (dollars)<input data-f="maxDailyLossUsd" type="number" min="1" class="field" value="${L.maxDailyLossUsd}"></label>
    <label class="small muted">Trades a day<input data-f="maxOrdersPerDay" type="number" min="1" class="field" value="${L.maxOrdersPerDay}"></label>
    <label class="small muted">Open positions at once<input data-f="maxOpenPositions" type="number" min="1" class="field" value="${L.maxOpenPositions}"></label>
    <label class="small muted">Only these symbols (comma separated; empty = any)<input data-f="symbols" class="field" value="${esc((L.symbols || []).join(', '))}"></label>
    <label class="small muted">Trading hours (your time; empty = any)<div class="row" style="gap:8px"><input data-f="start" class="field" placeholder="09:00" value="${esc(L.hours ? L.hours.start : '')}"><input data-f="end" class="field" placeholder="17:00" value="${esc(L.hours ? L.hours.end : '')}"></div></label>
    ${fut && a.mode === 'live' ? `<label class="row small" style="gap:8px"><input data-f="allowDerivatives" type="checkbox" ${L.allowDerivatives ? 'checked' : ''}> Allow LIVE futures on this account (each still needs a stop loss and your OK)</label>` : ''}
    <p class="small muted">Atlas can't change these; only you can, here.</p></div>`;
  await sheet({ title: 'Limits: ' + a.name, html, primary: 'Save', onPrimary: async (o) => {
    const v = (n) => (o.querySelector(`[data-f=${n}]`) || {});
    const start = v('start').value.trim(), end = v('end').value.trim();
    const next = { ...L, maxDailyLossUsd: Number(v('maxDailyLossUsd').value), maxOrdersPerDay: Number(v('maxOrdersPerDay').value), maxOpenPositions: Number(v('maxOpenPositions').value),
      symbols: v('symbols').value.split(',').map((s) => s.trim()).filter(Boolean), hours: start && end ? { start, end, days: [] } : null };
    if (fut) next.maxContracts = Number(v('maxContracts').value); else next.maxOrderUsd = Number(v('maxOrderUsd').value);
    if (v('allowDerivatives').type === 'checkbox') next.allowDerivatives = v('allowDerivatives').checked;
    await host.trading.limits(a.id, next); return true;
  } }) && (await refresh(), toast('Limits saved'), render(true));
}
