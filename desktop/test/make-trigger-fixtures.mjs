// Writes tools/trigger-fixtures.json: inputs and the PC's answers for every trigger rule, so the phone
// (TriggerRules.kt, TriggerRulesTest.kt) can be checked to give exactly the same answers. Run: node test/make-trigger-fixtures.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as R from '../renderer/trigrules.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const T0 = Date.UTC(2026, 10, 12, 9, 0, 0);
const out = { note: 'Made by desktop/test/make-trigger-fixtures.mjs from desktop/renderer/trigrules.js. Do not edit by hand.', t0: T0 };

const triggers = [
  { id: 'a', type: 'email', config: { from: 'bank, alerts@gtbank.com', subject: 'debit', goal: 'Log the spend', perHour: '5' } },
  { type: 'price', config: { symbol: 'btcusdt', above: '100000', below: 90000, movePct: 3, windowMin: 2 } },
  { type: 'price', config: { symbol: 'NQ', movePct: '1.5' } },
  { id: 'n', type: 'notification', config: { app: 'WhatsApp', contains: 'urgent' }, enabled: false },
  { type: 'file', config: { source: 'downloads', pattern: '*.pdf' } },
  { type: 'file', config: { folder: 'C:\\Users\\me\\Inbox' } },
  { type: 'schedule', config: { schedule: { kind: 'interval', everyMin: 30 } } },
  { type: 'remote', config: { name: 'deploy done' } },
  { type: 'manual', config: {} },
  { type: 'teleport', config: { x: 1 } },
];
out.clean = triggers.map((t, i) => { const c = R.cleanTrigger(t, i); return { input: t, i, out: c, summary: R.summary(c) }; });

const bot = { id: 'b1', home: 'pc', instructions: 'x', triggers: [{ type: 'email', enabled: true }] };
out.goal = [
  { bot, trigger: R.cleanTrigger(triggers[0]), event: { title: 'New email from Bank: Debit alert', data: { from: 'Bank', subject: 'Debit alert', email_id: null, n: 3 } } },
  { bot: { id: 'b2' }, trigger: { id: 't', type: 'manual', config: {} }, event: { title: 'Started', data: {} } },
].map((c) => ({ ...c, out: R.goalFor(c.bot, c.trigger, c.event), key: R.eventKey(c.bot.id, c.trigger.id, c.event) }));

out.where = [['any', 'phone'], ['phone', 'phone'], ['phone', 'pc'], ['pc', 'pc'], [undefined, 'pc']].map(([home, device]) => ({ home: home || null, device, out: R.where({ home }, device) }));
out.awake = [{ home: 'phone', triggers: [{ type: 'email', enabled: true }] }, { home: 'pc', triggers: [{ type: 'manual', enabled: true }] }, { home: 'any', triggers: [{ type: 'price', enabled: true }] }, { home: 'pc', triggers: [{ type: 'file', enabled: true }] }]
  .map((b) => ({ bot: b, out: R.awakeLabel(b) }));
out.watchable = ['email', 'notification', 'app', 'teleport'].flatMap((t) => ['phone', 'pc'].map((d) => ({ type: t, device: d, out: R.watchable(t, d) })));

{ let s = {}; out.dedupe = [['k1', T0], ['k1', T0 + 1000], ['k2', T0 + 2000], ['k1', T0 + R.DEDUPE_MS + 5000]].map(([key, at]) => { const r = R.dedupe(s, key, at); s = r.state; return { key, at, fresh: r.fresh }; }); }
{ let s = {}; out.rate = [0, 30000, 61000, 125000, 190000, 3700000].map((dt) => { const r = R.rateAllow(s, 'b|t', 2, T0 + dt); s = r.state; return { at: T0 + dt, perHour: 2, ok: r.ok }; }); }

{ const sched = { kind: 'interval', everyMin: 30 }; let s = {}; out.schedule = [0, 10 * 60000, 31 * 60000, 32 * 60000, 200 * 60000].map((dt) => { const r = R.scheduleDue(s, sched, T0 + dt); s = r.state; return { at: T0 + dt, fire: r.fire, nextAt: s.nextAt }; }); out.scheduleSpec = sched; }

{ const cfg = R.cleanTrigger(triggers[0]).config; let s = {};
  const batches = [
    [{ uid: 10, from: 'Bank <alerts@gtbank.com>', subject: 'Debit alert', unread: true }],
    [{ uid: 11, from: 'Bank <alerts@gtbank.com>', subject: 'Debit alert ₦5,000', unread: true }, { uid: 12, from: 'Mum', subject: 'debit?', unread: true }, { uid: 13, from: 'alerts@gtbank.com', subject: 'Credit alert', unread: true }, { uid: 10, from: 'x', subject: 'debit', unread: true }],
    [{ uid: 14, from: 'BANK', subject: 'DEBIT', unread: false }, { uid: 15, from: 'bank', subject: 'debit', unread: true, id: 'INBOX:15' }],
  ];
  out.email = { cfg, steps: batches.map((b) => { const r = R.emailEvents(s, b, cfg); s = r.state; return { messages: b, events: r.events, state: s }; }) };
  const cfg2 = { ...cfg, folder: 'Work' }; const r2 = R.emailEvents(s, batches[2], cfg2); out.emailFolderChange = { cfg: cfg2, before: s, messages: batches[2], events: r2.events, state: r2.state }; }

{ const cfg = R.cleanTrigger(triggers[1]).config; let s = {}; const prices = [[95000, 0], [99000, 10000], [100500, 20000], [101000, 30000], [99900, 40000], [99700, 50000], [100100, 60000], [89000, 70000], [89500, 80000], [92000, 300000], [95000, 310000], ['bad', 320000]];
  out.price = { cfg, steps: prices.map(([p, dt]) => { const r = R.priceEvents(s, p, T0 + dt, cfg); s = r.state; return { price: p, at: T0 + dt, events: r.events, state: s }; }) }; }
{ const cfg = R.cleanTrigger({ type: 'price', config: { symbol: 'X', above: 50 } }).config; let s = {}; out.priceStartAbove = { cfg, steps: [[60, 0], [70, 1000], [49, 2000], [51, 3000]].map(([p, dt]) => { const r = R.priceEvents(s, p, T0 + dt, cfg); s = r.state; return { price: p, at: T0 + dt, events: r.events, state: s }; }) }; }

out.notification = [
  [{ app: 'WhatsApp', contains: 'urgent, asap' }, { pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Mum', text: 'Call me ASAP' }],
  [{ app: 'com.whatsapp', contains: '' }, { pkg: 'com.whatsapp', app: 'WA', title: 'x', text: '' }],
  [{ app: 'whats', contains: 'urgent' }, { pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Mum', text: 'hello' }],
  [{ app: 'Telegram', contains: '' }, { pkg: 'com.whatsapp', app: 'WhatsApp', title: 'Mum', text: 'hello' }],
  [{ app: '', contains: 'otp' }, { pkg: 'com.gtbank', app: 'GTWorld', title: 'Your OTP', text: '123456' }],
].map(([cfg, n]) => ({ cfg, n, out: R.notificationMatches(cfg, n), event: R.notificationEvent(n) }));

out.app = [
  [{ app: 'Instagram' }, { pkg: 'com.instagram.android', app: 'Instagram' }],
  [{ app: 'com.whatsapp' }, { pkg: 'com.whatsapp', app: 'WhatsApp' }],
  [{ app: 'gram' }, { pkg: 'org.telegram.messenger', app: 'Telegram' }],
  [{ app: '' }, { pkg: 'com.whatsapp', app: 'WhatsApp' }],
  [{ app: 'Chrome' }, { pkg: 'com.whatsapp', app: 'WhatsApp' }],
].map(([cfg, o]) => ({ cfg, o, at: T0 + 125000, out: R.appMatches(cfg, o), event: R.appEvent(o, T0 + 125000) }));
out.clean.push(...[{ type: 'app', config: { app: '  Instagram ' } }, { type: 'app', config: {} }].map((t, i) => { const c = R.cleanTrigger(t, 20 + i); return { input: t, i: 20 + i, out: c, summary: R.summary(c) }; }));

out.glob = [['*.pdf', 'Report.PDF'], ['*.pdf', 'a.pdf.tmp'], ['report*', 'report-2026.xlsx'], ['*', 'x'], ['a?c.txt', 'abc.txt'], ['a?c.txt', 'abbc.txt'], ['(x).txt', '(x).txt']].map(([p, n]) => ({ pattern: p, name: n, out: R.globMatch(p, n) }));

{ const cfg = { pattern: '*.pdf' }; let s = null;
  const steps = [
    [[{ name: 'old.pdf', size: 10, mtime: 1 }, { name: 'x.txt', size: 1, mtime: 1 }], 0],
    [[{ name: 'old.pdf', size: 10, mtime: 1 }, { name: 'new.pdf', size: 5, mtime: 2 }], 1000],
    [[{ name: 'old.pdf', size: 10, mtime: 1 }, { name: 'new.pdf', size: 9, mtime: 3 }], 2000],
    [[{ name: 'old.pdf', size: 10, mtime: 1 }, { name: 'new.pdf', size: 9, mtime: 3 }], 4000],
    [[{ name: 'old.pdf', size: 10, mtime: 1 }, { name: 'new.pdf', size: 9, mtime: 3 }], 6000],
    [[{ name: 'old.pdf', size: 12, mtime: 9 }, { name: 'new.pdf', size: 9, mtime: 3 }], 7000],
    [[{ name: 'old.pdf', size: 12, mtime: 9 }], 11000],
  ];
  out.file = { cfg, steps: steps.map(([listing, dt]) => { const r = R.fileEvents(s, listing, T0 + dt, cfg); s = r.state; return { listing, at: T0 + dt, events: r.events, state: s }; }) }; }

fs.writeFileSync(path.join(here, '../../tools/trigger-fixtures.json'), JSON.stringify(out, null, 1) + '\n');
console.log('wrote tools/trigger-fixtures.json');
