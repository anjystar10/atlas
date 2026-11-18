// Writes tools/autonomy-fixtures.json from desktop/renderer/autonomy.js: the answers the phone's AutonomyRules.kt must
// give too. Run: node desktop/test/make-autonomy-fixtures.mjs   (then both test suites check them)
import fs from 'node:fs';
import * as A from '../renderer/autonomy.js';
import { guessCategory } from '../renderer/botx.js';

const policies = [
  null,
  { level: 'careful' },
  { level: 'balanced' },
  { level: 'autopilot' },
  { level: 'autopilot', botLevels: { 'bot-t': 'careful', 'bot-x': 'nonsense' } },
  { level: 'balanced', botLevels: { 'bot-t': 'autopilot' } },   // a bot can't be bolder than the global level
  { level: 'balanced', grants: [{ kind: 'message_send', scope: 'always', at: 1 }, { kind: 'publish', scope: 'today', day: '2026-11-18', at: 2 },
    { kind: 'command', scope: 'bot', botId: 'bot-t', at: 3 }, { kind: 'write_file', scope: 'mission', missionId: 'm1', at: 4 }, { kind: 'purchase', scope: 'always', at: 5 }] },
  { level: 'careful', grants: [{ kind: 'message_send', scope: 'always', at: 1 }], modes: { delete: 'allow', publish: 'deny' } },
  { level: 'autopilot', modes: { message: 'deny', account: 'allow', screen: 'allow' } },
  { level: 'weird', modes: { terms: 'allow' } },
];
const kinds = [...A.KIND_IDS, 'nonsense'];
const wheres = [{}, { botId: 'bot-t' }, { botId: 'bot-x', missionId: 'm1' }, { day: '2026-11-18' }, { day: '2026-11-19' }, { mustAsk: true }, { planKinds: ['other', 'terms', 'delete'] }];
const decide = [];
policies.forEach((policy, pi) => kinds.forEach((kind) => wheres.forEach((w) => decide.push({ policy: pi, kind, where: w, out: A.decide(policy, { kind, ...w }) }))));

const kindCases = [
  ['email_send', { to: 'Bob <BOB@x.com>' }, null, { known: ['bob@x.com'] }],
  ['email_send', { to: 'bob@x.com, eve@y.org' }, null, { known: ['bob@x.com'] }],
  ['email_send', {}, null, { to: 'bob@x.com', known: ['Bob@X.com'] }],
  ['email_send', { to: 'eve@y.org' }, null, { replyThread: true }],
  ['email_send', { to: '' }, null, { known: [] }],
  ['send_sms', { to: '+234 801 234 5678' }, null, { known: ['+2348012345678'] }],
  ['send_sms', { to: 'Mum' }, null, { known: ['mum'] }],
  ['send_sms', { to: '0801' }, null, { known: [] }],
  ['reply_message', {}, null, {}], ['email_delete', {}, null, {}], ['email_archive', {}, null, {}], ['mark_read', {}, null, {}], ['email_draft', {}, null, {}],
  ['forget', {}, null, {}], ['update_reminder', { action: 'delete' }, null, {}], ['update_reminder', { action: 'move' }, null, {}], ['calendar_change', {}, null, {}],
  ['run_command', {}, null, {}], ['write_file', {}, null, {}], ['browser_fill_login', {}, null, {}], ['browser_fill_login', {}, null, { money: true }],
  ['open_link', { url: 'https://x.com' }, null, {}], ['open_link', { url: ' TEL:123' }, null, {}], ['trade_order', {}, null, { live: true }], ['trade_order', {}, null, {}], ['trade_order', {}, 'purchase', { money: true }],
  ['browser_click', {}, 'publish', {}], ['browser_click', {}, 'purchase', {}], ['browser_click', {}, 'message', { money: true }], ['tap', {}, 'command', { what: 'Install the app' }],
  ['tap', {}, 'command', { what: 'Run it' }], ['tap', {}, 'screen', {}], ['tap', {}, 'bogus', {}], [null, {}, null, {}], ['mcp__github__create_issue', {}, 'other', {}],
];
const kindFor = kindCases.map(([tool, args, category, ctx]) => ({ tool, args, category, ctx, out: A.kindFor(tool, args, category, ctx) }));

const recips = ['Bob@X.com', ' +234 801-234-5678 ', 'Mum', '', 'Name <a.b+c@d.co.uk>', '12345', '+1 (555) 010-9999 ext', 'call +44 20 7946 0958'];
const steps = ['Search for flights to Lagos', 'Email the shortlist to Ada', 'Post the summary on X', 'Pay for the ticket', 'Delete the old drafts', 'Sign up for the newsletter', 'Run the build script', 'Install the app'];
const grantCases = [
  ['message_send', 'always', {}], ['message_send', 'today', { day: '2026-11-18' }], ['message_send', 'today', {}], ['command', 'bot', { botId: 'b' }], ['command', 'bot', {}],
  ['write_file', 'mission', { missionId: 'm' }], ['purchase', 'always', {}], ['live_trade', 'today', { day: 'd' }], ['read', 'always', {}], ['screen', 'always', {}], ['nope', 'always', {}],
  ['publish', 'forever', {}],
].map(([kind, scope, o]) => ({ kind, scope, o, out: A.makeGrant(kind, scope, { ...o, at: 7 }) }));
const g0 = [{ kind: 'publish', scope: 'today', day: '2026-11-17', at: 1 }, { kind: 'publish', scope: 'always', at: 2 }, { kind: 'command', scope: 'bot', botId: 'a', at: 3 }];
const addGrant = [
  { grants: g0, g: { kind: 'publish', scope: 'always', at: 9 }, today: '2026-11-18' },
  { grants: g0, g: { kind: 'command', scope: 'bot', botId: 'b', at: 9 }, today: null },
  { grants: [], g: { kind: 'terms', scope: 'mission', missionId: 'm', at: 9 }, today: '2026-11-18' },
].map((c) => ({ ...c, out: A.addGrant(c.grants, c.g, c.today) }));
const fx = {
  levels: A.LEVELS, kinds: Object.fromEntries(A.KIND_IDS.map((k) => [k, A.KINDS[k].tier])),
  policies, decide, kindFor,
  normRecipient: recips.map((r) => ({ in: r, out: A.normRecipient(r) })),
  recipientsIn: ['a@b.com; Bob <BOB@b.com>, ,', '', '+234 801 234 5678'].map((t) => ({ in: t, out: A.recipientsIn(t) })),
  effectiveLevel: policies.map((p, i) => ({ policy: i, out: ['bot-t', 'bot-x', null].map((b) => A.effectiveLevel(p, b)) })),
  planKindsOf: { steps, out: A.planKindsOf(steps, guessCategory) },
  makeGrant: grantCases, addGrant,
  afterApproval: [[{}, [], 'message_send'], [{ message_send: 2 }, [], 'message_send'], [{ message_send: 5 }, ['message_send'], 'message_send'], [{ purchase: 9 }, [], 'purchase'], [{ screen: 9 }, [], 'screen']]
    .map(([counts, suggested, kind]) => ({ counts, suggested, kind, out: A.afterApproval(counts, suggested, kind) })),
  batch: [[], [{ ref: 'a', kind: 'message_send' }], [{ ref: 'a', kind: 'message_send' }, { ref: 'b', kind: 'purchase' }, { ref: 'c', kind: 'other', mustAsk: true }, { ref: 'd', kind: 'publish', question: true }, { ref: 'e' }]]
    .map((p) => { const b = A.batch(p); return { pending: p, out: { bulk: b.bulk.map((x) => x.ref), single: b.single.map((x) => x.ref), title: b.title } }; }),
  expired: [[0, 5e9, 'bot'], [1000, 1000 + 12 * 3600e3, 'bot'], [1000, 1001 + 12 * 3600e3, 'bot'], [1000, 1001 + 30 * 60e3, 'chat'], [1000, 1001 + 30 * 60e3, 'laptop'], [1000, 1001 + 12 * 3600e3, 'odd']]
    .map(([at, now, where]) => ({ at, now, where, out: A.expired(at, now, where) })),
  canChangeLevel: [['balanced', 'autopilot', {}], ['balanced', 'autopilot', { consentYes: true }], ['balanced', 'autopilot', { byTap: true }], ['autopilot', 'careful', {}], ['careful', 'x', { byTap: true }], ['careful', 'balanced', {}]]
    .map(([from, to, o]) => ({ from, to, o, out: A.canChangeLevel(from, to, o) })),
  briefLine: policies.map((p, i) => ({ policy: i, out: A.briefLine(p) })),
};
const out = new URL('../../tools/autonomy-fixtures.json', import.meta.url);
fs.writeFileSync(out, JSON.stringify(fx) + '\n');
console.log(`wrote ${decide.length} decisions, ${kindFor.length} kinds`);
