// Atlas trading on the PC: the app's side (main process). Accounts and today's numbers in Atlas's data folder;
// the API keys encrypted by Windows for this user (Electron safeStorage = DPAPI), never synced and never shown to the
// page or the model; every action written to Documents/Atlas Bot/Trading/trading-log.csv (it shows in the Library).
// What each tool does is in tradecore.js; the risk engine in traderules.js; the brokers in brokers.js.
'use strict';
const { app, safeStorage, dialog, Notification, BrowserWindow } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const brokers = require('./brokers');
const R = require('./traderules');
const { createTrading } = require('./tradecore');

const FILE = () => path.join(app.getPath('userData'), 'trading.json');
const SECRETS = () => path.join(app.getPath('userData'), 'trading-keys.bin');
const LOG_DIR = () => path.join(app.getPath('documents'), 'Atlas Bot', 'Trading');

function readJson(f, fb) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return fb; } }
function writeAtomic(f, text) { const tmp = f + '.tmp'; fs.writeFileSync(tmp, text); fs.renameSync(tmp, f); }

let data = null;
const store = {
  get() { if (!data) data = readJson(FILE(), { accounts: [] }); return data; },
  set(d) { data = d; writeAtomic(FILE(), JSON.stringify(d)); },
};
function secrets() {
  try { if (!fs.existsSync(SECRETS())) return {}; return JSON.parse(safeStorage.decryptString(fs.readFileSync(SECRETS()))); } catch { return {}; }
}
function saveSecrets(all) {
  if (!safeStorage.isEncryptionAvailable()) throw new Error("This PC can't encrypt the keys safely, so Atlas won't store them.");
  const f = SECRETS(), tmp = f + '.tmp';
  fs.writeFileSync(tmp, safeStorage.encryptString(JSON.stringify(all))); fs.renameSync(tmp, f);
}

const csv = (v) => { const s = String(v == null ? '' : v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
const COLS = ['at', 'account', 'mode', 'action', 'symbol', 'side', 'qty', 'type', 'price', 'stopLoss', 'takeProfit', 'result', 'orderId', 'note'];
function audit(e) {
  try {
    fs.mkdirSync(LOG_DIR(), { recursive: true });
    const f = path.join(LOG_DIR(), 'trading-log.csv');
    if (!fs.existsSync(f)) fs.writeFileSync(f, COLS.join(',') + '\n');
    fs.appendFileSync(f, COLS.map((k) => csv(k === 'at' ? new Date(e.at).toISOString() : e[k])).join(',') + '\n');
  } catch {}
}
function local() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return { day: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, minuteOfDay: d.getHours() * 60 + d.getMinutes(), isoDay: d.getDay() === 0 ? 7 : d.getDay() };
}
function notify(title, body) { try { if (Notification.isSupported()) new Notification({ title, body: String(body || '').slice(0, 240) }).show(); } catch {} }

const core = createTrading({ store, secretFor: (id) => secrets()[id] || null, makeBroker: (a, s) => brokers.make(a, s), audit, local, notify });

// ---------------------------------------------------------------- what the app's page may see and do

/** Accounts without secrets, for Me → Trading and the brief. */
function list() {
  const d = store.get();
  return { killed: !!d.killed, accounts: (d.accounts || []).map((a) => ({ ...a, limits: R.limitsFor(a), today: (d.days || {})[a.id] || null, plan: (d.plans || {})[a.id] || null })) };
}
/** Add or change an account. A secret is only replaced when a new one is given. Changing mode to LIVE asks on screen. */
async function save(win, acc, secret) {
  const d = store.get();
  const accounts = [...(d.accounts || [])];
  const was = accounts.find((x) => x.id === acc.id);
  const a = { id: was ? was.id : 'acct-' + crypto.randomBytes(5).toString('hex'), name: String(acc.name || '').trim().slice(0, 60) || 'Trading account',
    broker: acc.broker === 'projectx' ? 'projectx' : 'bybit', mode: acc.mode === 'live' ? 'live' : 'paper',
    base: acc.broker === 'projectx' ? (String(acc.base || '').trim() || 'https://api.topstepx.com') : undefined,
    remoteId: acc.remoteId != null && acc.remoteId !== '' ? Number(acc.remoteId) : undefined,
    limits: acc.limits || (was && was.limits) || undefined, default: !!acc.default, updatedAt: Date.now() };
  if (a.broker === 'projectx' && !/^https:\/\/[a-z0-9.-]+(:\d+)?$/i.test(a.base)) return { error: 'The prop firm address must look like https://api.topstepx.com' };
  if (a.mode === 'live' && (!was || was.mode !== 'live')) {
    const r = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Make It LIVE', 'Keep It Paper'], defaultId: 1, cancelId: 1, title: 'Real money',
      message: `Make “${a.name}” a LIVE account?`, detail: 'Orders will use real money. Each LIVE order shows you what it is and its worst case, and waits for your OK (or a plan you confirmed). Losses are possible.' });
    if (r.response !== 0) return { error: 'Kept as paper.' };
  }
  if (secret && Object.values(secret).some((v) => v)) { const all = secrets(); all[a.id] = secret; saveSecrets(all); }
  else if (!was) return { error: 'The keys are needed.' };
  const i = accounts.findIndex((x) => x.id === a.id); if (i >= 0) accounts[i] = a; else accounts.push(a);
  store.set({ ...d, accounts });
  return { ok: a.id };
}
function remove(id) {
  const d = store.get();
  store.set({ ...d, accounts: (d.accounts || []).filter((a) => a.id !== id) });
  const all = secrets(); delete all[id]; saveSecrets(all);
  return true;
}
/** Limits are the user's alone (the page, never the model). */
function setLimits(id, limits) {
  const d = store.get(); const accounts = (d.accounts || []).map((a) => a.id === id ? { ...a, limits: cleanLimits(limits), updatedAt: Date.now() } : a);
  store.set({ ...d, accounts }); return true;
}
function cleanLimits(l = {}) {
  const n = (v, lo, hi) => { const x = Number(v); return Number.isFinite(x) ? Math.max(lo, Math.min(hi, x)) : undefined; };
  const out = { maxOrderUsd: n(l.maxOrderUsd, 1, 1e7), maxContracts: n(l.maxContracts, 1, 100), maxDailyLossUsd: n(l.maxDailyLossUsd, 1, 1e7),
    maxOpenPositions: n(l.maxOpenPositions, 1, 50), maxOrdersPerDay: n(l.maxOrdersPerDay, 1, 200),
    symbols: Array.isArray(l.symbols) ? l.symbols.map((s) => String(s).trim().toUpperCase()).filter(Boolean).slice(0, 30) : undefined,
    hours: l.hours && /^\d{1,2}:\d{2}$/.test(l.hours.start || '') && /^\d{1,2}:\d{2}$/.test(l.hours.end || '') ? { start: l.hours.start, end: l.hours.end, days: (l.hours.days || []).filter((x) => x >= 1 && x <= 7) } : null,
    allowDerivatives: !!l.allowDerivatives };
  Object.keys(out).forEach((k) => out[k] === undefined && delete out[k]);
  return out;
}
/** Checks a connection works; for ProjectX also lists the firm's accounts so the user can pick one. */
async function test(acc, secret) {
  try {
    const s = secret && Object.values(secret).some((v) => v) ? secret : secrets()[acc.id];
    if (!s) return { error: 'The keys are needed.' };
    const b = brokers.make({ ...acc, remoteId: acc.remoteId || undefined }, s);
    if (acc.broker === 'projectx') { const list = await b.accounts(); return { ok: true, accounts: list }; }
    const a = await b.account();
    return { ok: true, equity: a.equity };
  } catch (e) { return { error: e.message }; }
}
async function resume(win) {
  const r = await dialog.showMessageBox(win, { type: 'question', buttons: ['Turn Trading Back On', 'Keep It Stopped'], defaultId: 1, cancelId: 1, title: 'Trading', message: 'Turn trading back on?', detail: 'Atlas will be able to place orders again, inside each account\'s limits (LIVE orders still ask you).' });
  if (r.response !== 0) return false;
  core.resume(); return true;
}

/**
 * Pre-approved LIVE limits: LIVE orders inside them go without a tap. Only from Me → Trading, and only after the user
 * confirms in a native dialog (the page and the model can't answer it). lim null turns them off (no dialog needed).
 */
async function setStanding(win, id, lim) {
  if (!lim) { core.setStanding(id, null); return { ok: true }; }
  const a = (store.get().accounts || []).find((x) => x.id === id); if (!a) return { error: 'No such account.' };
  const syms = (Array.isArray(lim.symbols) ? lim.symbols : String(lim.symbols || '').split(/[\s,]+/)).map((x) => String(x).trim().toUpperCase()).filter(Boolean);
  if (!syms.length) return { error: 'List the instruments these limits cover.' };
  const r = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Pre-approve These Trades', 'Cancel'], defaultId: 1, cancelId: 1, title: 'LIVE trading without a tap',
    message: `Let Atlas place LIVE orders on ${a.name} without asking you each time?`,
    detail: `Only ${syms.join(', ')}; at most $${Number(lim.maxOrderUsd) || 0} (spot) or ${Number(lim.maxContracts) || 0} contract(s) per order; at most $${Number(lim.maxDailyLossUsd) || 0} lost in a day; for ${Math.max(1, Math.min(Number(lim.days) || 7, 90))} days. Every account limit still applies, and anything outside these still asks. This is real money: losses are possible.` });
  if (r.response !== 0) return { ok: false };
  const v = core.setStanding(id, lim);
  return v && v.error ? v : { ok: true, standing: v };
}

module.exports = { core, list, save, remove, setLimits, setStanding, test, resume, stopAll: (reason) => core.run('trade_stop_all', { reason: reason || 'from the tray' }) };
