// Writes tools/threads-fixtures.json: inputs and the PC's answers for the group-chat rules (renderer/threadcore.js), so
// the phone (ThreadRules in Threads.kt, ThreadsTest.kt) can be checked to give exactly the same answers.
// Run: node test/make-thread-fixtures.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as T from '../renderer/threadcore.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const bots = [{ id: 'bot-reels', name: 'Reel Poster' }, { id: 'bot-r', name: 'Reel' }, { id: 'sp-research', name: 'Research Bot' }, { id: 'bot-inbox', name: 'Inbox' }];
const out = { note: 'Made by desktop/test/make-thread-fixtures.mjs from desktop/renderer/threadcore.js. Do not edit by hand.', bots,
  limits: { MAX_MESSAGES: T.MAX_MESSAGES, MAX_TEXT: T.MAX_TEXT, MAX_BOT_TURNS: T.MAX_BOT_TURNS, MAX_PAIR_HOPS: T.MAX_PAIR_HOPS, MAX_PARTICIPANTS: T.MAX_PARTICIPANTS } };

const texts = [
  '@Reel Poster post the launch video tonight',
  '@reel quick one',
  '@Research Bot and @Inbox: find the invoice, then @inbox reply',
  'email me at me@reel.com, thanks',
  '@Atlas sum up',
  '@Reelx not a bot',
  'hey @sp-research, check this',
  'no mentions here',
  '@Research Bot, @Research Bot twice',
];
out.mentions = texts.map((text) => ({ text, out: T.mentionsIn(text, bots), stripped: T.stripMentions(text, bots) }));

const msg = (id, from, text, extra = {}) => ({ id, at: Number(id.slice(1)) || 1, from, text, mentions: T.mentionsIn(text, bots), mission: null, kind: 'message', ...extra });
const base = (participants, messages) => T.cleanThread({ id: 'th-1', title: 'Launch week', participants, messages }, 5);
const cases = [];
const add = (name, participants, history, last) => { const t = base(participants, [...history, last]); cases.push({ name, thread: t, msg: t.messages[t.messages.length - 1], out: T.decide(t, t.messages[t.messages.length - 1], bots) }); };
add('user mentions one bot', ['bot-reels', 'sp-research'], [], msg('m1', 'user', '@Reel Poster post it'));
add('user, no mention, one bot', ['bot-reels'], [], msg('m1', 'user', 'post it'));
add('user, no mention, two bots: Atlas takes it', ['bot-reels', 'sp-research'], [], msg('m1', 'user', 'post it'));
add('user mentions Atlas', ['bot-reels'], [], msg('m1', 'user', '@Atlas what happened?'));
add('bot hands to another bot in the chat', ['bot-reels', 'sp-research'], [msg('m1', 'user', '@Research Bot go')], msg('m2', 'sp-research', '@Reel Poster please post this'));
add('bot mentions a bot not in the chat', ['bot-reels', 'sp-research'], [msg('m1', 'user', '@Research Bot go')], msg('m2', 'sp-research', '@Inbox please'));
add('bot mentions itself', ['sp-research'], [msg('m1', 'user', 'go')], msg('m2', 'sp-research', '@Research Bot again'));
const ping = [msg('m1', 'user', '@Research Bot go')];
for (let i = 2; i < 2 + T.MAX_BOT_TURNS; i++) ping.push(msg('m' + i, i % 2 ? 'bot-reels' : 'sp-research', i % 2 ? '@Research Bot back to you' : '@Reel Poster your turn'));
add('too many bot turns without the user', ['bot-reels', 'sp-research'], ping, msg('m99', 'bot-reels', '@Research Bot more'));
add('pair hops limit', ['bot-reels', 'sp-research', 'bot-inbox'], [msg('m1', 'user', 'go'), msg('m2', 'sp-research', '@Reel Poster a'), msg('m3', 'bot-reels', '@Research Bot b'), msg('m4', 'sp-research', '@Reel Poster c'), msg('m5', 'bot-reels', '@Research Bot d')], msg('m6', 'sp-research', '@Reel Poster e and @Inbox f'));
add('a note starts nothing', ['bot-reels'], [], msg('m1', 'atlas', '@Reel Poster', { kind: 'note' }));
out.decide = cases;

out.clean = [
  { in: { id: 'th-x', title: '', participants: ['user', 'atlas', 'bot-a', 'bot-a', 'bot-b', 'c', 'd', 'e', 'f', 'g', 'h'], messages: [{ id: 'm1', at: 3, text: 'hi' }, { id: '', text: 'no id' }, { id: 'm2', from: 'bot-a', text: '', kind: 'approval' }, { id: 'm3', text: '' }] }, now: 9 },
  { in: { title: 'A very long title that goes on and on and on and on and on and on and on and on and on and on', participants: 'nope' }, now: 1, id: 'th-y' },
].map((c) => ({ ...c, out: T.cleanThread(c.in, c.now, c.id || '') }));

const conv = base(['bot-reels', 'sp-research'], [msg('m1', 'user', '@Research Bot find it'), msg('m2', 'sp-research', 'Found it.\n@Reel Poster please post', { kind: 'report' }), msg('m3', 'bot-reels', 'Want me to post?', { kind: 'approval' }), msg('m4', 'atlas', 'Paused.', { kind: 'note' })]);
out.transcript = [{ thread: conv, max: 6000, out: T.transcript(conv, bots, 6000) }, { thread: conv, max: 60, out: T.transcript(conv, bots, 60) }];
out.goal = [
  { msg: conv.messages[0], fromName: 'You', out: T.goalFrom(conv.messages[0], bots, 'You') },
  { msg: conv.messages[1], fromName: 'Research Bot', out: T.goalFrom(conv.messages[1], bots, 'Research Bot') },
  { msg: msg('m9', 'user', '@Reel Poster'), fromName: 'You', out: T.goalFrom(msg('m9', 'user', '@Reel Poster'), bots, 'You') },
];
out.turns = { thread: base(['bot-reels', 'sp-research'], ping), botTurns: T.botTurnsSinceUser(base(['bot-reels', 'sp-research'], ping)), pairHops: T.pairHops(base(['bot-reels', 'sp-research'], ping), 'bot-reels', 'sp-research') };

fs.writeFileSync(path.join(here, '../../tools/threads-fixtures.json'), JSON.stringify(out, null, 1) + '\n');
console.log('wrote tools/threads-fixtures.json:', out.mentions.length, 'mentions,', out.decide.length, 'decisions');
