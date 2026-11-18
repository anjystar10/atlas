// HUD mode's logic (renderer/hudcore.js): the results-wall panel format, cards for running work, the terminal's
// commands and the meters. The screen itself is checked by test/hud.shots.js (screenshots under xvfb).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as H from '../renderer/hudcore.js';

test('only plain web addresses get through: no scripts, files, local addresses or passwords', () => {
  assert.equal(H.safeUrl('https://www.theverge.com/a/b?x=1'), 'https://www.theverge.com/a/b?x=1');
  for (const bad of ['javascript:alert(1)', 'data:image/png;base64,AAAA', 'file:///C:/x.png', 'http://localhost:3000/x', 'http://127.0.0.1/x', 'http://192.168.1.4/a.jpg',
    'http://10.0.0.2/a', 'http://172.20.1.1/a', 'https://user:pw@example.com/', 'ftp://example.com/x', 'example.com/x', '', null, 'https://nodot/x'])
    assert.equal(H.safeUrl(bad), null, String(bad));
});

test('YouTube addresses of every shape give the video id', () => {
  for (const u of ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://youtu.be/dQw4w9WgXcQ', 'https://youtube.com/shorts/dQw4w9WgXcQ', 'https://www.youtube.com/watch?feature=x&v=dQw4w9WgXcQ', 'https://www.youtube.com/embed/dQw4w9WgXcQ'])
    assert.equal(H.youtubeId(u), 'dQw4w9WgXcQ', u);
  assert.equal(H.youtubeId('https://vimeo.com/123'), null);
});

test('show_panels: every kind of panel is cleaned and kept; broken ones are dropped with a reason', () => {
  const r = H.cleanPanels({
    title: 'State of VR',
    panels: [
      { type: 'text', title: 'Meta Quest 3S', body: 'Cheaper headset.\n\n\n\nShipping now.', source: 'https://www.meta.com/quest' },
      { type: 'stat', value: '≈20m', label: 'Quest headsets sold by early 2023', source: 'https://www.theverge.com/x' },
      { type: 'chart', title: 'Sales', unit: 'm', series: [{ label: 'Q1', value: 3 }, { label: 'Q2', value: '4.5' }, { label: 'bad', value: 'x' }] },
      { type: 'table', columns: ['Headset', 'Price'], rows: [['Quest 3', '$499'], ['Vision Pro', '$3,499', 'extra']] },
      { type: 'image', url: 'https://cdn.example.com/q3.jpg', caption: 'Quest 3' },
      { type: 'video', url: 'https://youtu.be/dQw4w9WgXcQ', title: 'Review' },
      { type: 'link', url: 'https://www.uploadvr.com/x', title: 'UploadVR', body: 'Coverage' },
      { type: 'image', url: 'javascript:alert(1)' },
      { type: 'chart', series: [{ label: 'only', value: 1 }] },
      { type: 'poll', title: 'x' },
      { type: 'text', body: '   ' },
      'nonsense',
    ],
  });
  assert.equal(r.title, 'State of VR');
  assert.deepEqual(r.panels.map((p) => p.type), ['text', 'stat', 'chart', 'table', 'image', 'video', 'link']);
  assert.equal(r.panels[0].body, 'Cheaper headset.\n\nShipping now.');
  assert.deepEqual(r.panels[2].series, [{ label: 'Q1', value: 3 }, { label: 'Q2', value: 4.5 }]);
  assert.deepEqual(r.panels[3].rows[1], ['Vision Pro', '$3,499']);
  assert.equal(r.panels[5].youtube, 'dQw4w9WgXcQ');
  assert.equal(r.panels[6].host, 'uploadvr.com');
  assert.equal(r.dropped.length, 5);
  assert.ok(r.dropped.some((d) => /unknown panel type "poll"/.test(d)));
  assert.ok(r.dropped.some((d) => /image panel had no usable web address/.test(d)));
});

test('show_panels: a title is always there, text is cut to size, and at most 12 panels', () => {
  const many = Array.from({ length: 30 }, (_, i) => ({ type: 'text', body: 'x'.repeat(2000), title: 'T' + i }));
  const r = H.cleanPanels({ panels: many });
  assert.equal(r.title, 'Results');
  assert.equal(r.panels.length, H.MAX_PANELS);
  assert.ok(r.panels.every((p) => p.body.length <= 900));
  assert.ok(r.dropped.includes('too many panels (12 at most)'));
  assert.deepEqual(H.cleanPanels(null).panels, []);
});

test('the tool declaration offers exactly the panel types the cleaner accepts, and warns against made-up addresses', () => {
  const t = H.SHOW_PANELS;
  assert.equal(t.name, 'show_panels');
  assert.deepEqual(t.parameters.properties.panels.items.properties.type.enum, H.PANEL_TYPES);
  assert.match(t.description, /never make up or guess an address/);
});

test('every panel gets a sensible place on the 12-column wall', () => {
  for (const type of H.PANEL_TYPES) { const s = H.spanOf({ type, body: '' }); assert.ok(s.c >= 3 && s.c <= 6 && s.r >= 1 && s.r <= 2, type); }
  assert.equal(H.spanOf({ type: 'text', body: 'x'.repeat(500) }).c, 4);
});

const job = (over) => ({ id: 'j1', goal: 'Research the state of VR in 2026', state: 'running', createdAt: 1000, updatedAt: 5000, history: [{ at: 1000, state: 'queued' }, { at: 2000, state: 'running' }],
  plan: [{ text: 'Search', done: true }, { text: 'Read reviews', done: true }, { text: 'Sub', done: false, depth: 1 }, { text: 'Write it up', done: false }, { text: 'Check', done: false }],
  steps: ['Searched the web: Quest 3S sales'], sources: ['a', 'b', 'c'], toolCalls: 7, ...over });

test('a running job becomes a card: kind and status, plan progress, counts and time', () => {
  const c = H.cardFor(job(), 2000 + 83000, 2);
  assert.equal(c.label, 'RESEARCH // ACTIVE');
  assert.equal(c.title, 'RESEARCH THE STATE OF VR IN 2026');
  assert.equal(c.step, 'Searched the web: Quest 3S sales');
  assert.equal(c.lit, Math.round((2 / 4) * H.SEGMENTS));   // the top-level plan only
  assert.deepEqual(c.counts, ['2/4 STEPS', '3 SOURCES', '2 AGENTS']);
  assert.equal(c.time, '01:23');
  assert.equal(c.waiting, false);
});

test('cards: waiting for you shows what it wants; no plan creeps but never fills; done fills', () => {
  const w = H.cardFor(job({ state: 'awaiting_approval', approval: { what: 'Send the email to Ada' } }), 9000);
  assert.equal(w.label, 'RESEARCH // NEEDS YOU'); assert.ok(w.waiting); assert.equal(w.step, 'Wants to: Send the email to Ada'); assert.deepEqual(w.approval, { what: 'Send the email to Ada' });
  const q = H.cardFor(job({ state: 'awaiting_answer', question: 'Which account?' }), 9000);
  assert.equal(q.question, 'Which account?');
  const np = H.cardFor(job({ plan: [], toolCalls: 400 }), 9000);
  assert.ok(np.lit < H.SEGMENTS);
  assert.equal(H.cardFor(job({ state: 'completed', updatedAt: 62000 }), 99999).lit, H.SEGMENTS);
  assert.equal(H.cardFor(job({ state: 'completed', updatedAt: 62000 }), 99999).time, '01:00');
  assert.equal(H.kindOf('Check my inbox and reply'), 'mail');
  assert.equal(H.kindOf('Buy 0.01 BTC on the demo account'), 'trade');
  assert.equal(H.kindOf('Post a reel to Instagram'), 'post');
  assert.equal(H.kindOf('Tidy my desktop'), 'task');
});

test('which jobs get cards: waiting first, then running; helpers and old finished jobs never', () => {
  const now = 1_000_000;
  const jobs = [
    { id: 'a', state: 'running', updatedAt: now - 10 }, { id: 'b', state: 'awaiting_approval', updatedAt: now - 50 },
    { id: 'c', state: 'completed', updatedAt: now - 60000 }, { id: 'd', state: 'completed', updatedAt: now - 600000 },
    { id: 'e', state: 'running', parent: 'a', updatedAt: now }, { id: 'f', state: 'queued', updatedAt: now - 5 },
  ];
  assert.deepEqual(H.cardsFor(jobs, now).map((j) => j.id), ['b', 'a', 'f', 'c']);
});

test('elapsed time reads like a clock', () => {
  assert.equal(H.elapsed(0), '00:00'); assert.equal(H.elapsed(61_000), '01:01'); assert.equal(H.elapsed(3_723_000), '1:02:03'); assert.equal(H.elapsed(-5), '00:00');
});

test('terminal commands: known ones, short forms, arguments; plain text is a message, a typo is named', () => {
  assert.deepEqual(H.parseCommand('/approve 3f2a'), { cmd: 'approve', arg: '3f2a' });
  assert.deepEqual(H.parseCommand('  /Status  '), { cmd: 'status', arg: '' });
  assert.deepEqual(H.parseCommand('/y'), { cmd: 'approve', arg: '' });
  assert.deepEqual(H.parseCommand('/n bot:12'), { cmd: 'deny', arg: 'bot:12' });
  assert.deepEqual(H.parseCommand('/history 2'), { cmd: 'history', arg: '2' });
  assert.deepEqual(H.parseCommand('/bogus x'), { cmd: null, word: 'bogus' });
  assert.equal(H.parseCommand('what is on my calendar'), null);
  assert.equal(H.parseCommand(''), null);
  const help = H.helpText();
  for (const k of Object.keys(H.COMMANDS)) assert.match(help, new RegExp('/' + k + '\\s'));
});

test('meters: decibels and lit segments', () => {
  assert.equal(H.dbOf(0), '-∞ dB'); assert.equal(H.dbOf(1), '0.0 dB'); assert.equal(H.dbOf(0.1), '-20.0 dB'); assert.equal(H.dbOf(1e-9), '-∞ dB');
  assert.equal(H.meterLit(0), 0); assert.equal(H.meterLit(1), 24); assert.equal(H.meterLit(0.001), 0); assert.equal(H.meterLit(0.0316, 24), 12);
});

test("the line under the core is Atlas's reply as plain words, cut at a sentence", () => {
  assert.equal(H.spokenLine('**Done.** See [the page](https://x.com).'), 'Done. See the page.');
  const long = 'The Quest 3S is the cheapest way in. It ships now. ' + 'More words follow here. '.repeat(20);
  const s = H.spokenLine(long, 80);
  assert.ok(s.length <= 81); assert.ok(/[.…]$/.test(s));
  assert.equal(H.spokenLine('```js\ncode\n``` Hi'), 'Hi');
});

test('the wall history keeps the newest, without repeats or empty walls', () => {
  let w = [];
  for (let i = 0; i < 25; i++) w = H.pushWall(w, { id: 'w' + i, panels: [{ type: 'text' }] });
  assert.equal(w.length, H.MAX_WALLS); assert.equal(w[0].id, 'w24');
  w = H.pushWall(w, { id: 'w20', panels: [{ type: 'text' }] }); assert.equal(w[0].id, 'w20'); assert.equal(w.filter((x) => x.id === 'w20').length, 1);
  assert.equal(H.pushWall(w, { id: 'e', panels: [] }).length, w.length);
});

test('the wall layout fills every row edge to edge, keeps order, and stacks pairs of small panels', () => {
  const kinds = [['image', 'text', 'text', 'video', 'chart', 'stat', 'image', 'table', 'link'], ['stat', 'link', 'text', 'video'], ['text'], ['table', 'table', 'chart'], ['stat', 'stat', 'stat']];
  for (const ks of kinds) {
    const ps = ks.map((type) => ({ type, body: '' }));
    const L = H.layout(ps);
    assert.equal(L.length, ps.length);
    // every cell of the grid is covered exactly once, and nothing goes past column 12
    const cells = new Map();
    L.forEach((s, i) => { assert.ok(s.col >= 1 && s.col + s.cols - 1 <= 12, `${ks} ${i}`); for (let r = s.row; r < s.row + s.rows; r++) for (let c = s.col; c < s.col + s.cols; c++) { const k = r + ':' + c; assert.ok(!cells.has(k), `overlap ${ks} at ${k}`); cells.set(k, i); } });
    const rows = Math.max(...L.map((s) => s.row + s.rows - 1));
    for (let r = 1; r <= rows; r++) for (let c = 1; c <= 12; c++) assert.ok(cells.has(r + ':' + c), `gap in ${ks} at row ${r} col ${c}`);
    // reading order: each panel starts no earlier (row, then column) than the one before it, except a stacked pair
    for (let i = 1; i < L.length; i++) assert.ok(L[i].row > L[i - 1].row || L[i].col > L[i - 1].col || (L[i].col === L[i - 1].col && L[i].row === L[i - 1].row + 1), `${ks} order at ${i}`);
  }
  const pair = H.layout([{ type: 'stat' }, { type: 'link' }, { type: 'text', body: '' }]);
  assert.deepEqual([pair[0].col, pair[1].col, pair[0].row, pair[1].row], [1, 1, 1, 2]);
});
