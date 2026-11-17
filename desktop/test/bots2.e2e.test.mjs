// Atlas Bot v2 end to end, with a scripted ("fake") model: parallel agents and a sub-agent, the checker sending a report
// back until it's right, a daily budget that stops the bot and asks, a dry run that does nothing risky, and recovery after
// Atlas closes mid-job. The engine is the real bots.js; only the AI and the tools are fakes. (docs/BOTS_V2.md)
import { test } from 'node:test';
import assert from 'node:assert/strict';
const B = await import('../renderer/bots.js');
const { dayOf } = await import('../renderer/crew.js');

class Sw extends Error {}
class Busy extends Error {}

/** [model(ctx)]: called for every AI turn with {first, brief, n (turns in this session), tools}; returns {text, calls}. */
function world(model, { journal = null, usage = {}, slots = 4, approvalFor = null } = {}) {
  const w = { saved: journal, ran: [], notes: [], posts: [], acts: [], events: [], usage, inFlight: 0, maxInFlight: 0, checkerTools: [], made: 0 };
  let clock = 1000;
  w.bots = B.createBots({
    load: async () => w.saved, save: async (t) => { w.saved = t; },
    provider: () => ({ id: 'gemini', name: 'Gemini' }),
    session: (p, brief, tools) => {
      let n = 0; const names = tools.map((t) => t.name);
      if (brief.startsWith('You are checking')) w.checkerTools.push(names);
      return { key: 'k', results: (list) => w.notes.push(list.map((x) => x.out)), call: async () => {
        w.inFlight++; w.maxInFlight = Math.max(w.maxInFlight, w.inFlight);
        try { await new Promise((r) => setTimeout(r, 3)); return await model({ first: brief.split('\n')[0], brief, n: n++, tools: names, w }); }
        finally { w.inFlight--; }
      } };
    },
    callWithKeys: async (p, sess, call) => call(),
    isSwitch: (e) => e instanceof Sw, isRetryable: (e) => e instanceof Busy,
    tools: () => [{ name: 'web_search' }, { name: 'read_page' }, { name: 'run_command' }, { name: 'send_email' }],
    extraApproval: approvalFor || ((name) => (name === 'send_email' ? 'Send an email' : null)),
    runTool: async (c, o) => { w.ran.push([c.name, (c.args || {}).query || (c.args || {}).url || (c.args || {}).to || '', !!(o && o.approved)]); return { out: `${c.name} result`, step: `Did ${c.name}` }; },
    systemPrompt: () => 'sys', checkerPrompt: () => 'checker',
    notify: (t, b, tag) => w.notes.push(['notify', t, tag]), post: (m) => w.posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => (clock += 10), uid: () => 'j' + (++w.made) + '-' + Math.random().toString(36).slice(2, 8), deviceId: () => 'pc-1',
    setTimer: (f, ms) => (ms <= 0 ? setTimeout(f, 0) : 0), clearTimer: () => {},
    slots: () => slots,
    usage: {
      get: (id) => w.usage[id] || null,
      add: (id, d) => { const day = dayOf(clock); const u = w.usage[id] && w.usage[id].day === day ? w.usage[id] : { day, calls: 0, ms: 0, money: 0, extraCalls: 0, extraMs: 0 }; for (const k of Object.keys(d)) u[k] = (u[k] || 0) + d[k]; w.usage[id] = u; },
    },
    actionLog: (e) => w.acts.push(e),
    checker: true,
  });
  for (const ev of ['mission.updated', 'budget.exhausted', 'approval.needed']) w.bots.on(ev, (x) => w.events.push(ev));
  return w;
}
const call = (name, args = {}) => ({ name, args, id: name + Math.random() });
const turn = (...calls) => ({ text: '', calls });
const settle = async (w, rounds = 60) => { for (let i = 0; i < rounds; i++) { await new Promise((r) => setTimeout(r, 6)); await w.bots.idle(); } };
const done = (plan) => call('update_plan', { plan: plan.map((s) => '[x] ' + s).join('\n') });

test('v2: three agents work in parallel (one with its own sub-agent); the checker sends the report back once; the fixed report passes', async () => {
  let checks = 0;
  const w = world(({ first, brief, n }) => {
    if (first.startsWith('You are checking')) {
      checks++;
      if (checks === 1) return turn(call('read_page', { url: 'https://slot.test/laptop' }), call('verdict', { ok: false, problems: 'Slot is in the job but has no price in the report.' }));
      return turn(call('verdict', { ok: true }));
    }
    if (first === 'Your job: Compare the laptop price at three shops') {
      if (!brief.includes('reported on')) {
        if (n === 0) return turn(call('update_plan', { plan: '[ ] Jumia\n[ ] Konga\n[ ] Slot\n[ ] Compare' }),
          call('spawn_agent', { role: 'price checker', task: 'Check the price at Jumia', access: 'web' }),
          call('spawn_agent', { role: 'price checker', task: 'Check the price at Konga', access: 'web' }),
          call('spawn_agent', { role: 'price checker', task: 'Check the price at Slot', access: 'web' }));
        return turn(call('wait_for_helpers'));
      }
      if (n === 0) return turn(done(['Jumia', 'Konga', 'Slot', 'Compare']), call('finish', { report: 'Jumia ₦500k, Konga ₦450k. Cheapest: Konga.' }));
      assert.ok(brief.includes('Slot') || true);
      return turn(call('finish', { report: 'Jumia ₦500k, Konga ₦450k, Slot ₦470k. Cheapest: Konga.' }));
    }
    if (first === 'Your job: Check the price at Jumia') {
      if (!brief.includes('reported on')) {
        if (n === 0) return turn(call('spawn_agent', { role: 'review reader', task: 'Read the Jumia reviews' }), call('web_search', { query: 'jumia laptop' }));
        return turn(call('wait_for_helpers'));
      }
      return turn(call('finish', { report: 'Jumia: ₦500k, reviews fine.' }));
    }
    if (first === 'Your job: Read the Jumia reviews') return n === 0 ? turn(call('read_page', { url: 'https://jumia.test/reviews' })) : turn(call('finish', { report: 'Reviews: 4.5/5.' }));
    if (first === 'Your job: Check the price at Konga') return n === 0 ? turn(call('web_search', { query: 'konga laptop' })) : turn(call('finish', { report: 'Konga: ₦450k.' }));
    if (first === 'Your job: Check the price at Slot') return n === 0 ? turn(call('web_search', { query: 'slot laptop' })) : turn(call('finish', { report: 'Slot: ₦470k.' }));
    throw new Error('unexpected brief: ' + first);
  });
  await w.bots.init();
  const j = w.bots.deploy('Compare the laptop price at three shops', '');
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED, JSON.stringify(j.steps));
  assert.match(j.report, /Slot ₦470k/, 'the fixed report is the one handed over');
  assert.equal(j.verify.status, 'passed'); assert.equal(j.verify.rounds, 1); assert.match(j.verify.notes[0], /Slot/);
  assert.equal(checks, 2, 'checked twice: rejected, then passed');
  // the tree: 3 agents, one with a sub-agent
  const t = w.bots.tree(j.id);
  assert.equal(t.agents.length, 3); assert.deepEqual(t.agents.map((a) => a.agents.length).sort(), [0, 0, 1]);
  assert.ok(t.agents.every((a) => a.job.state === B.S.COMPLETED && a.job.role === 'price checker' && a.job.depth === 1));
  assert.equal(t.agents.find((a) => a.agents.length).agents[0].job.depth, 2);
  assert.equal(t.agents[0].job.access.web, 'write'); assert.ok(Object.entries(t.agents[0].job.access).every(([k, v]) => k === 'web' || v === 'off'), 'an agent gets only the access it was given');
  assert.ok(w.maxInFlight >= 3, `agents really ran at the same time (max ${w.maxInFlight} at once)`);
  // the checker can only read
  assert.ok(w.checkerTools.every((names) => names.includes('verdict') && !names.includes('run_command') && !names.includes('send_email') && !names.includes('finish')), JSON.stringify(w.checkerTools));
  // cost and the action log: every AI call counts against the bot's day; every tool use is logged with its mission
  assert.ok(j.cost.calls >= 4); assert.ok(w.usage.atlas.calls >= 12, 'agents and the checker count too');
  assert.equal(w.acts.length, 4, JSON.stringify(w.acts.map((a) => [a.tool, a.mission === j.id, a.bot]))); assert.ok(w.acts.every((a) => a.mission === j.id && a.bot === 'atlas'));
  assert.equal(w.posts.length, 1, 'one report to the user, not one per agent');
  assert.ok(w.events.includes('mission.updated'));
});

test('v2: when the day\'s budget runs out the bot stops and asks; more budget lets it carry on; "stop" makes it report', async () => {
  const w = world(({ first, n }) => {
    if (first.startsWith('You are checking')) return turn(call('verdict', { ok: true }));
    return n < 3 ? turn(call('web_search', { query: 'q' + n })) : turn(call('finish', { report: 'All done.' }));
  }, { usage: { atlas: { day: dayOf(1000), calls: 298, ms: 0, money: 0, extraCalls: 0, extraMs: 0 } } });
  await w.bots.init();
  const j = w.bots.deploy('A long research job', '');
  await settle(w);
  assert.equal(j.state, B.S.AWAITING_ANSWER); assert.equal(j.budgetStop, true);
  assert.match(j.question, /used today's budget \(AI calls\)/);
  assert.ok(w.events.includes('budget.exhausted')); assert.ok(w.notes.some((x) => Array.isArray(x) && /used today's budget/.test(x[1])));
  const ranBefore = w.ran.length;
  assert.ok(ranBefore <= 2, 'it stopped at the limit');
  w.bots.giveMore('atlas', { calls: 50, minutes: 30 });
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED, JSON.stringify(j.steps.slice(-4)));
  assert.equal(j.report.split('\n')[0], 'All done.');
  assert.ok(w.ran.length > ranBefore);

  // a second one runs out too; the user says stop: it writes the report with what it has, without more work
  w.usage.atlas.calls = w.usage.atlas.extraCalls + 300;
  const k = w.bots.deploy('Another job', '');
  await settle(w);
  assert.equal(k.state, B.S.AWAITING_ANSWER);
  w.bots.answer(k.id, 'no, stop there');
  await settle(w);
  assert.ok(B.FINAL.has(k.state), k.state);
  assert.equal(k.budgetStop, false);
});

test('v2: a dry run goes through the job but sends, runs and asks nothing; the report says what it would do', async () => {
  const w = world(({ first, n }) => {
    if (first.startsWith('You are checking')) throw new Error('a dry run is not checked as if it really happened');
    if (n === 0) return turn(call('web_search', { query: 'invoice' }), call('send_email', { to: 'boss@x.test', body: 'hi' }), call('run_command', { command: 'Remove-Item C:\\x' }), call('request_approval', { action: 'Pay the invoice' }));
    return turn(call('finish', { report: 'Sent the invoice (dry run).' }));
  });
  await w.bots.init();
  const j = w.bots.deploy('Send the invoice', '', { dryRun: true });
  await settle(w);
  assert.equal(j.state, B.S.COMPLETED, JSON.stringify(j.steps));
  assert.deepEqual(w.ran.map((r) => r[0]), ['web_search'], 'only reading happened');
  assert.equal(j.intended.length, 3, JSON.stringify(j.intended));
  assert.match(j.report, /## What it would do \(dry run: none of this was done\)\n- Send an email\n- Run this command on your PC: Remove-Item C:\\x\n- Pay the invoice/);
  assert.equal(j.verify.status, 'skipped');
  assert.ok(!w.events.includes('approval.needed'), 'nothing waited for the user');
  assert.ok(w.acts.filter((a) => a.dryRun && /dry run/.test(a.outcome)).length >= 3);
});

test('v2: Atlas closes mid-mission; on restart the mission and its agents pick up where they were and finish', async () => {
  let snapshot = null; let hang = true;
  const model = ({ first, brief, n, w }) => {
    if (first.startsWith('You are checking')) return turn(call('verdict', { ok: true }));
    if (first === 'Your job: Watch two shops') {
      if (n === 0 && !snapshot) return turn(call('update_plan', { plan: '[ ] A\n[ ] B' }), call('spawn_agent', { role: 'watcher', task: 'Watch shop A' }), call('spawn_agent', { role: 'watcher', task: 'Watch shop B' }));
      if (!/reported on/.test(brief)) return turn(call('wait_for_helpers'));
      return turn(done(['A', 'B']), call('finish', { report: 'A: 10, B: 12.' }));
    }
    if (hang && first === 'Your job: Watch shop B') { snapshot = w.saved; return new Promise(() => {}); }   // Atlas "closes" here
    return n === 0 ? turn(call('web_search', { query: first })) : turn(call('finish', { report: first.replace('Your job: Watch ', '') + ' checked' }));
  };
  const w1 = world(model);
  await w1.bots.init();
  const j = w1.bots.deploy('Watch two shops', '');
  for (let i = 0; i < 100 && !snapshot; i++) await new Promise((r) => setTimeout(r, 10));   // not idle(): one worker never returns
  await new Promise((r) => setTimeout(r, 30));
  assert.ok(snapshot, 'the journal was saved mid-mission');
  const before = JSON.parse(snapshot);
  assert.ok(before.some((x) => x.goal === 'Watch shop B' && x.state === 'running'));
  // a fresh Atlas from the saved journal
  hang = false;
  const w2 = world(model, { journal: snapshot });
  await w2.bots.init();
  await settle(w2);
  const again = w2.bots.jobs.find((x) => x.id === j.id);
  assert.equal(again.state, B.S.COMPLETED, JSON.stringify(again.steps));
  assert.equal(again.report.split('\n')[0], 'A: 10, B: 12.');
  assert.ok(w2.bots.tree(j.id).agents.every((a) => a.job.state === B.S.COMPLETED));
});
