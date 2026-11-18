// Bot templates: the phone's copy matches the PC's list, every template makes a valid standing bot, and Atlas picks
// the right one for everyday requests.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { ASSET, assetText } from './make-bot-templates.mjs';
import { TEMPLATES, instantiate, propose, matchTemplates } from '../renderer/bottemplates.js';
import { CAP_IDS } from '../renderer/crew.js';
import * as TR from '../renderer/trigrules.js';

test('the phone has the same templates (run node test/make-bot-templates.mjs after editing)', () => {
  assert.equal(fs.readFileSync(ASSET, 'utf8').replace(/\r\n/g, '\n'), assetText());   // Windows checkouts may turn \n into \r\n
});

test('every template makes a complete standing bot', () => {
  const ids = new Set();
  for (const t of TEMPLATES) {
    assert.ok(!ids.has(t.id), `duplicate ${t.id}`); ids.add(t.id);
    const b = instantiate(t.id, { createdBy: 'user' }, Date.UTC(2026, 10, 18, 9), 'b-' + t.id);
    assert.equal(b.v, 2); assert.equal(b.id, 'b-' + t.id); assert.equal(b.name, t.name); assert.equal(b.createdBy, 'user');
    for (const id of CAP_IDS) assert.ok(['off', 'read', 'write'].includes(b.access[id]), `${t.id}: ${id}`);
    for (const tr of b.triggers) assert.ok(TR.TYPES[tr.type], `${t.id}: trigger type ${tr.type}`);
    assert.ok(b.budget.minutesPerDay > 0 && b.budget.callsPerDay > 0);
  }
});

test('the trader starts on demo-safe terms: trading is the only thing it can act on, and LIVE orders still ask', () => {
  const t = TEMPLATES.find((x) => x.id === 'trader');
  assert.equal(t.access.trading, 'write'); assert.equal(t.access.email, 'off'); assert.equal(t.access.phone, 'off');
  assert.ok(t.asks.some((a) => /LIVE order/.test(a)));
  assert.ok(t.setup.some((s) => /Demo|PAPER|practice/.test(s)));
});

test('requests pick the right template', () => {
  assert.equal(matchTemplates('check my email every morning')[0].template.id, 'inbox');
  assert.equal(matchTemplates('post a reel on instagram daily')[0].template.id, 'reels');
  assert.equal(matchTemplates('trade for me on bybit')[0].template.id, 'trader');
  assert.equal(matchTemplates('do deep research on solar panels')[0].template.id, 'research');
  const p = propose('check my email every weekday at 8am', {}, Date.UTC(2026, 10, 18, 9));
  assert.equal(p.template, 'inbox'); assert.ok(p.timing.length >= 1);
});
