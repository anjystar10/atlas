// The desktop app's logic, tested in Node: cleaning replies, Markdown, the sync code and encryption.
import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window = { atlasHost: { read: async () => null, write: async () => true } };
const { plainReply, plainMemory, plainSpoken } = await import('../renderer/plain.js');
const { renderMarkdown } = await import('../renderer/md.js');
const sync = await import('../renderer/sync.js');

test('ids, notes and printed tool calls disappear', () => {
  assert.equal(plainReply('Saved memory [3f2a9c1e-1b2c-4d5e-8f90-1234567890ab].'), 'Saved memory.');
  assert.equal(plainReply('Done.\n(What I did: Logged “Gym”)'), 'Done.');
  assert.equal(plainReply('Sure!\n```tool_code\nprint(default_api.remember(text="x"))\n```\nSaved.'), 'Sure!\n\nSaved.');
  assert.equal(plainReply('Ok\nremember(text="x")'), 'Ok');
  const code = 'Here:\n```python\nprint(1)\n```';
  assert.equal(plainReply(code), code);
});
test('memories are plain sentences', () => {
  assert.equal(plainMemory('`my chess goal is 1500`'), 'My chess goal is 1500');
  assert.equal(plainMemory('{"a": "Chess goal", "b": "1500"}'), 'Chess goal. 1500');
});
test('spoken text has no symbols', () => {
  const s = plainSpoken('## Plan\n- **Gym** at 10\n- Read');
  assert.ok(!/[*#]/.test(s)); assert.ok(s.includes('Gym at 10'));
});
test('markdown is escaped and structured', () => {
  const h = renderMarkdown('# Hi\n- **a** <b>x</b>\n1. one\n```js\nlet a = "<x>"\n```');
  assert.ok(h.includes('<h2>Hi</h2>')); assert.ok(h.includes('<strong>a</strong>')); assert.ok(h.includes('&lt;b&gt;'));
  assert.ok(h.includes('<ol start="1">')); assert.ok(h.includes('class="codebox"')); assert.ok(!h.includes('<b>x</b>'));
});
test('link codes', () => {
  const c = sync.newCode(); assert.match(c, /^ATLS(-[A-HJ-NP-TV-Z2-9]{4}){5}$/);
  assert.equal(sync.normalize(c.toLowerCase().replace(/-/g, ' ')), c);
  assert.equal(sync.normalize('ATLS-ABCD'), null);
  assert.equal(sync.normalize('ATLS-0000-0000-0000-0000-0000'), null);
});
test('encryption round trip, and the same owner as the phone', async () => {
  const c = 'ATLS-ABCD-EFGH-JKMN-PQRS-TV23';
  assert.equal(await sync.owner(c), 'cb1625882351349ba10af21b26b051c4af29c9e205a34e77ef37eaf3327cf965');
  const msg = JSON.stringify({ title: 'Pick up my sister ✓ é' });
  assert.equal(await sync.decrypt(c, await sync.encrypt(c, msg)), msg);
  await assert.rejects(sync.decrypt('ATLS-ABCD-EFGH-JKMN-PQRS-TV24', await sync.encrypt(c, msg)));
});
test('canonical form ignores field order', () => {
  assert.equal(sync.canonical({ b: 1, a: [1, { d: 2, c: null }] }), sync.canonical({ a: [1, { c: null, d: 2 }], b: 1 }));
});
