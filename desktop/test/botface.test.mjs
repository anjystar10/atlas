// Named bots, their faces and the Bots page's History (botface.js). The phone checks the same fixtures (BotFaceTest.kt).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as F from '../renderer/botface.js';
import { FIX, fixtureText } from './make-botface-fixtures.mjs';
import { DEFAULT_SPECIALISTS, findByName, specialistPrompt, teamIndex } from '../renderer/botx.js';
import { TEMPLATES, instantiate } from '../renderer/bottemplates.js';

test('the phone checks the same fixtures (run node test/make-botface-fixtures.mjs after editing botface.js)', () => {
  assert.equal(fs.readFileSync(FIX, 'utf8').replace(/\r\n/g, '\n'), fixtureText());
});

test('old default names become teammates; a name the user chose is never touched', () => {
  const ted = F.migrate({ id: 'sp-developer', name: 'Developer Bot', role: 'Programming' });
  assert.equal(ted.name, 'Ted'); assert.equal(ted.title, 'Developer'); assert.equal(ted.id, 'sp-developer');
  assert.equal(F.label(ted), 'Ted · Developer');
  const mine = { id: 'b', name: 'Developer bot' };
  assert.equal(F.migrate(mine), mine, 'not an exact old name: left alone');
  const again = F.migrate(ted); assert.equal(again, ted, 'migrating twice changes nothing');
  const kept = F.migrate({ id: 'x', name: 'Inbox', title: 'Mail', persona: 'Mine.', face: { shape: 'orb', color: 0 } });
  assert.equal(kept.name, 'Ivy'); assert.equal(kept.title, 'Mail'); assert.equal(kept.persona, 'Mine.'); assert.deepEqual(kept.face, { shape: 'orb', color: 0 });
});

test('every built-in and template bot has a human name, a title, a face and a personality', () => {
  const all = [...DEFAULT_SPECIALISTS, ...TEMPLATES];
  const names = new Set();
  for (const b of all) {
    assert.ok(!/\bbot\b/i.test(b.name), `${b.name} still sounds like a bot`);
    assert.ok(b.title && b.persona && F.SHAPES.includes(b.face.shape) && b.face.color >= 0 && b.face.color < F.COLORS.length, b.name);
    assert.ok(!names.has(b.name), `two bots called ${b.name}`); names.add(b.name);
  }
  const rex = instantiate('trader', {}, 1, 'bot-1');
  assert.equal(rex.name, 'Rex'); assert.equal(rex.title, 'Trader'); assert.deepEqual(rex.face, { shape: 'diamond', color: 2 }); assert.ok(rex.persona);
});

test('the prompt says who the bot is, and Atlas can still find a bot by its old name or its title', () => {
  const ted = DEFAULT_SPECIALISTS.find((b) => b.id === 'sp-developer');
  assert.match(specialistPrompt(ted), /Your name is Ted, the user's developer\. Speak in the first person as Ted/);
  assert.match(teamIndex(DEFAULT_SPECIALISTS), /- Ted \(Developer\): Programming/);
  assert.equal(findByName(DEFAULT_SPECIALISTS, 'Developer Bot').name, 'Ted');
  assert.equal(findByName(DEFAULT_SPECIALISTS, 'the researcher').name, 'Nova');
  assert.equal(findByName(DEFAULT_SPECIALISTS, 'nobody at all'), null);
});

test('faces are steady and always valid', () => {
  for (const id of ['a', 'b', 'bot-123', '']) {
    const f = F.faceOf({ id, name: 'X' });
    assert.ok(F.SHAPES.includes(f.shape) && f.color >= 0 && f.color < F.COLORS.length && F.MOTIFS.includes(f.motif));
    assert.deepEqual(F.faceOf({ id, name: 'X' }), f);
  }
  assert.equal(F.motifOf({ title: 'Developer' }), 'code');
  assert.equal(F.motifOf({ role: 'Trades your connected accounts' }), 'chart');
  assert.equal(F.motifOf({ role: 'Builds software projects' }), 'build');
});

test('History groups every job by day, newest first, and filters by bot and words', () => {
  const now = Date.UTC(2026, 10, 18, 12);
  const jobs = [
    { id: 'a', goal: 'Install FFmpeg', state: 'awaiting_approval', at: now - 1000 },
    { id: 'b', goal: 'Summarise AI news', state: 'completed', bot: 'sp-research', at: now - 86400000 },
    { id: 'c', goal: 'Old one', state: 'failed', bot: 'sp-research', at: now - 5 * 86400000 },
  ];
  const h = F.historyGroups(jobs, { now });
  assert.deepEqual(h.groups.map((g) => g.label), ['Today', 'Yesterday', '2026-11-13']);
  assert.equal(h.groups[0].items[0].bot, 'atlas', 'a job with no bot is Atlas\'s');
  assert.equal(h.waiting, 1); assert.equal(h.total, 3);
  assert.equal(F.historyGroups(jobs, { now, bot: 'sp-research' }).total, 2);
  assert.equal(F.historyGroups(jobs, { now, query: 'ffmpeg' }).total, 1);
});
