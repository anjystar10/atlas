// Writes tools/bots2-fixtures.json from desktop/renderer/crew.js: the answers the phone's Crew.kt must give too.
// Run: node desktop/test/make-bots2-fixtures.mjs   (then both test suites check them)
import fs from 'node:fs';
import * as C from '../renderer/crew.js';
const tools = ['web_search', 'read_page', 'browser_read', 'browser_click', 'browser_fill_login', 'vault_list', 'make_document', 'run_command', 'look_at_screen',
  'read_log', 'set_reminder', 'email_read', 'email_send', 'trade_quote', 'trade_order', 'trade_stop_all', 'project_read', 'project_run', 'calendar_events', 'phone_alarm',
  'on_laptop', 'mcp__github__create_issue', 'call_api', 'update_plan', 'finish', 'spawn_agent', 'verdict', 'some_new_tool', ''];
const now = 1762900000000;
const bots = [
  { id: 'sp-research', name: 'Research Bot', role: 'Finds things out', instructions: 'Search widely.', groups: ['web'], notes: ['likes sources'], updatedAt: 5 },
  { name: '  Trader  ', v: 2, role: 'Trades', access: { trading: 'write', web: 'read', email: 'bogus' }, home: 'pc', budget: { minutesPerDay: 30, callsPerDay: '50', moneyPerDay: 20 }, triggers: [{ type: 'price', config: { symbol: 'BTCUSDT', above: 100000 } }, { config: {} }], icon: '📈' },
  { id: 'b-inbox', name: 'Inbox', v: 2, state: 'paused', createdBy: 'atlas', budget: { minutesPerDay: 99999, callsPerDay: -4 }, log: [{ at: 1, mission: 'm1', text: 'Triaged 12 emails' }, { text: '' }], extra: { keep: true } },
  {},
  { id: 'sp-x', v: 2, access: null, groups: ['web'], name: 'Normalised v1' },
  { id: 'b-y', v: 2, access: 'nonsense', name: 'Bad access' },
];
const fx = {
  capabilityOf: tools.map((t) => ({ tool: t, out: C.capabilityOf(t) })),
  defaultAccess: C.defaultAccess(),
  normalizeBot: bots.map((b, i) => ({ in: b, now, id: 'gen-' + i, out: C.normalizeBot(b, now, 'gen-' + i) })),
  allows: [],
  narrowAccess: [
    { parent: null, child: { web: 'write', email: 'write' }, out: null },
    { parent: { web: 'read', browser: 'write', trading: 'off' }, child: { web: 'write', browser: 'read', trading: 'write' }, out: null },
    { parent: { web: 'write' }, child: null, out: null },
  ],
  budgetCheck: [
    { budget: null, u: { day: 'd', calls: 0, ms: 0, money: 0 } },
    { budget: { callsPerDay: 10, minutesPerDay: 5 }, u: { day: 'd', calls: 10, ms: 0, money: 0 } },
    { budget: { callsPerDay: 10, minutesPerDay: 5 }, u: { day: 'd', calls: 3, ms: 300000, money: 0 } },
    { budget: { callsPerDay: 10, minutesPerDay: 5 }, u: { day: 'd', calls: 10, ms: 0, money: 0, extraCalls: 5 } },
    { budget: { callsPerDay: 10, minutesPerDay: 5 }, u: { day: 'd', calls: 1, ms: 400000, money: 0, extraMs: 600000 } },
  ],
  moneyAllowed: [
    { budget: null, u: { money: 0 }, amount: 1 }, { budget: { moneyPerDay: 50 }, u: { money: 20 }, amount: 30 }, { budget: { moneyPerDay: 50 }, u: { money: 20 }, amount: 31 },
  ],
  agentSlice: [[300, 7200000], [3, 10000], [0, 0]].map(([c, m]) => ({ callsLeft: c, msLeft: m, out: C.agentSlice(c, m) })),
  usageToday: [{ u: { day: '2026-11-12', calls: 4, ms: 9, money: 1, extraCalls: 2 }, day: '2026-11-12' }, { u: { day: '2026-11-11', calls: 4 }, day: '2026-11-12' }, { u: null, day: '2026-11-12' }],
  dayOf: [[1762900000000, 60], [1762900000000, -480], [1762905600000, 60]].map(([ms, off]) => ({ ms, offsetMin: off, out: C.dayOf(ms, off) })),
  parsePlanTree: [
    '1. [x] Search for reviews\n2. [ ] Compare prices',
    'Find the products\n  [x] Search Jumia\n  [ ] Search Konga\n    - read the specs\nWrite the report',
    '\tTab step\n- bullet [x] not done mark\n* [X] Starred done\n1.2) [x] Numbered sub\n\n   \n' + 'x'.repeat(250),
    '',
  ].map((t) => ({ text: t, out: C.parsePlanTree(t) })),
  userSaid: ['yes', 'Yes please go ahead', 'ok', 'okay sure', 'go ahead', 'do it', '👍', 'no', "don't", 'Nope', 'stop that', 'wait a sec', 'maybe later?', 'what is it?', '', '  …yes', 'not now', 'approve', 'never mind'].map((t) => ({ text: t, out: C.userSaid(t) })),
  consent: ['yes', 'please approve it', 'ok, always allow that', 'yes from now on', 'no, wait', "don't send it", 'what did the bot find?', '', '👍 go', 'sure thing', 'Deny it', 'fine by me', 'can you check it first', 'Yes’ please'].map((t) => ({ text: t, out: C.consent(t) })),
  dryRunSkips: [['web_search', false], ['browser_click', true], ['email_send', false], ['email_read', false], ['trade_order', false], ['trade_quote', false], ['make_document', false], ['mcp__x__y', false], ['browser_fill_login', false]]
    .map(([t, n]) => ({ tool: t, needsApproval: n, out: C.dryRunSkips(t, n) })),
  missionDefaults: [
    { in: { id: 'j1', createdAt: 7, specialist: 'sp-research', workMs: 1000, plan: [{ text: 'a', done: false }] } },
    { in: { id: 'j2', createdAt: 8, routine: 'r1' } },
    { in: { id: 'j3', createdAt: 9, parent: 'j1' } },
    { in: { id: 'j4', v: 2, bot: 'b-inbox', source: { type: 'trigger', ref: 'tr-0', at: 3 }, cost: { calls: 2, tokensIn: 10, tokensOut: 5, ms: 3 }, verify: { status: 'passed', rounds: 1, notes: [] }, dryRun: true, plan: [] } },
  ],
};
const norm = fx.normalizeBot.map((x) => x.out);
for (const b of norm) for (const t of tools) fx.allows.push({ botIndex: norm.indexOf(b), tool: t, access: null, out: C.allows(b, t, () => true) });
fx.allows.push({ botIndex: 1, tool: 'trade_order', access: { trading: 'read' }, out: C.allows(norm[1], 'trade_order', null, { trading: 'read' }) });
for (const n of fx.narrowAccess) n.out = C.narrowAccess(n.parent, n.child);
for (const b of fx.budgetCheck) b.out = C.budgetCheck(b.budget, b.u);
for (const m of fx.moneyAllowed) m.out = C.moneyAllowed(m.budget, m.u, m.amount);
for (const u of fx.usageToday) u.out = C.usageToday(u.u, u.day);
for (const m of fx.missionDefaults) m.out = C.missionDefaults(JSON.parse(JSON.stringify(m.in)));
fs.writeFileSync(new URL('../../tools/bots2-fixtures.json', import.meta.url), JSON.stringify(fx, null, 1) + '\n');
console.log('wrote', Object.keys(fx).map((k) => `${k}:${Array.isArray(fx[k]) ? fx[k].length : 1}`).join(' '));
