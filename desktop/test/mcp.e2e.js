// Connections, end to end in Node: Atlas's MCP client (mcp.js) against real MCP servers made with the official SDK
// (test/fixtures/mcp-server.js) over stdio and Streamable HTTP, plus web API connections against a local server.
// Also checks the tool declarations Atlas gives every AI service (connect.js). Run: node test/mcp.e2e.js
'use strict';
const path = require('path');
const http = require('http');
const { spawn } = require('child_process');
const assert = require('assert');
const { createConnections } = require('../mcp');

const FIX = path.join(__dirname, 'fixtures', 'mcp-server.js');
let passed = 0, failed = 0;
async function check(name, fn) {
  try { await fn(); passed++; console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '\n     ', e && e.stack ? e.stack.split('\n').slice(0, 3).join('\n      ') : e); }
}

function memory() {
  let saved = null;
  const deps = {
    load: () => saved, save: (t) => { saved = t; },
    // stands in for Windows' encryption: what matters here is that secrets only ever pass through these two
    encrypt: (t) => 'enc:' + Buffer.from(t, 'utf8').toString('base64').split('').reverse().join(''),
    decrypt: (v) => Buffer.from(String(v).slice(4).split('').reverse().join(''), 'base64').toString('utf8'),
    changed: () => {},
  };
  return { deps, file: () => saved };
}

function startHttpServer(token) {
  return new Promise((resolve, reject) => {
    const p = spawn(process.execPath, [FIX, 'http', '0', token || ''], { stdio: ['ignore', 'pipe', 'inherit'] });
    p.stdout.on('data', (d) => { const m = String(d).match(/PORT (\d+)/); if (m) resolve({ proc: p, url: `http://127.0.0.1:${m[1]}/mcp` }); });
    p.on('error', reject); setTimeout(() => reject(new Error('the test server did not start')), 15000);
  });
}

(async () => {
  const conn = await import('../renderer/connect.js');
  const mem = memory();
  const c = createConnections(mem.deps);
  const TOKEN = 'secret-token-123';
  const httpSrv = await startHttpServer(TOKEN);

  // ---------------------------------------------------------------- stdio
  await check('a program on this PC connects over stdio and lists its tools', async () => {
    await c.saveServer({ name: 'notes', kind: 'stdio', command: process.execPath, args: [FIX, 'stdio'], env: { NOTES_KEY: 'hidden-env-value' } });
    await c.connect('notes');
    const s = c.list().servers.find((x) => x.name === 'notes');
    assert.strictEqual(s.status, 'connected', s.error);
    assert.deepStrictEqual(s.tools.map((t) => t.name).sort(), ['add', 'big', 'fails', 'picture', 'save_note', 'shape']);
    assert.strictEqual(s.tools.find((t) => t.name === 'add').readOnly, true);
    assert.strictEqual(s.tools.find((t) => t.name === 'save_note').destructive, true);
  });
  await check('calling a tool returns its answer', async () => {
    assert.deepStrictEqual(await c.call('notes', 'add', { a: 2, b: 3 }), { out: '5', image: null });
  });
  await check('a picture from a tool comes back as an image', async () => {
    const r = await c.call('notes', 'picture', {});
    assert.strictEqual(r.out, 'here it is'); assert.strictEqual(r.image.mime, 'image/png'); assert.ok(r.image.base64.length > 20);
  });
  await check('a huge answer is cut short', async () => {
    const r = await c.call('notes', 'big', { n: 50000 });
    assert.ok(r.out.length < 12200 && /cut short/.test(r.out), r.out.length);
  });
  await check("a tool's own error is reported as an error", async () => {
    const r = await c.call('notes', 'fails', {});
    assert.ok(r.error && /on purpose/.test(r.error), JSON.stringify(r));
  });
  await check('an unknown tool and an unknown server are refused politely', async () => {
    assert.ok(/no tool called nope/.test((await c.call('notes', 'nope', {})).error));
    assert.ok(/isn't connected/.test((await c.call('ghost', 'add', {})).error));
  });

  // ---------------------------------------------------------------- what the AI is given
  await check('tool declarations are named mcp__server__tool and use only what every AI service accepts', async () => {
    const decl = conn.declarationsFrom(c.list(), 'bots');
    const names = decl.map((d) => d.name);
    assert.ok(names.includes('mcp__notes__add') && names.includes('mcp__notes__shape'), names.join());
    const allowed = new Set(['type', 'description', 'enum', 'properties', 'required', 'items', 'nullable']);
    const walk = (s, at) => { for (const k of Object.keys(s)) assert.ok(allowed.has(k), `${at}: "${k}" isn't accepted by Gemini`); if (s.properties) for (const [k, v] of Object.entries(s.properties)) walk(v, at + '.' + k); if (s.items) walk(s.items, at + '[]'); };
    for (const d of decl) { assert.ok(/^[A-Za-z0-9_]{1,64}$/.test(d.name), d.name); walk(d.parameters, d.name); assert.strictEqual(d.parameters.type, 'object'); }
    const shape = decl.find((d) => d.name === 'mcp__notes__shape').parameters;
    assert.strictEqual(shape.properties.where.properties.zip.nullable, true);
    assert.deepStrictEqual(shape.properties.mode.enum, ['fast', 'slow']);
    assert.strictEqual(shape.properties.extra.type, 'string', 'an open object becomes JSON text');
  });
  await check("arguments are turned back into the server's own shapes before the call", async () => {
    const decl = new Map(); conn.declarationsFrom(c.list(), 'bots', decl);
    const e = decl.get('mcp__notes__shape');
    const args = conn.coerceArgs(e.schema, { where: { city: 'Lagos', zip: null }, mode: 'slow', extra: '{"floor":3}' });
    assert.deepStrictEqual(args.extra, { floor: 3 });
    const r = await c.call('notes', 'shape', args);
    assert.deepStrictEqual(JSON.parse(r.out), { where: { city: 'Lagos', zip: null }, mode: 'slow', extra: { floor: 3 } });
    assert.strictEqual(conn.coerceArgs(decl.get('mcp__notes__add').schema, { a: '2', b: 3 }).a, 2);
  });
  await check('approvals: read-only tools go ahead; changes ask; "ask" asks for everything; "all" never asks', async () => {
    let idx = new Map(); conn.declarationsFrom(c.list(), 'bots', idx);
    assert.strictEqual(conn.approvalFrom(idx, 'mcp__notes__add', { a: 1, b: 2 }), null);
    assert.ok(/Use notes: save_note/.test(conn.approvalFrom(idx, 'mcp__notes__save_note', { text: 'hi' })));
    assert.ok(conn.approvalFrom(idx, 'mcp__notes__fails', {}), 'no read-only mark: asks');
    c.setServer('notes', { trust: 'ask' }); idx = new Map(); conn.declarationsFrom(c.list(), 'bots', idx);
    assert.ok(conn.approvalFrom(idx, 'mcp__notes__add', { a: 1, b: 2 }));
    c.setServer('notes', { trust: 'all' }); idx = new Map(); conn.declarationsFrom(c.list(), 'bots', idx);
    assert.strictEqual(conn.approvalFrom(idx, 'mcp__notes__save_note', { text: 'x' }), null);
    c.setServer('notes', { trust: 'reads' });
  });
  await check('a server switched off for bots is not offered to bots, but still to chat', async () => {
    c.setServer('notes', { bots: false });
    assert.ok(!conn.declarationsFrom(c.list(), 'bots').some((d) => d.name.startsWith('mcp__notes__')));
    assert.ok(conn.declarationsFrom(c.list(), 'chat').some((d) => d.name.startsWith('mcp__notes__')));
    c.setServer('notes', { bots: true });
  });

  // ---------------------------------------------------------------- Streamable HTTP
  await check('a server on the internet connects over Streamable HTTP with its token', async () => {
    await c.saveServer({ name: 'remote', kind: 'http', url: httpSrv.url, headers: { Authorization: 'Bearer ' + TOKEN } });
    await c.connect('remote');
    const s = c.list().servers.find((x) => x.name === 'remote');
    assert.strictEqual(s.status, 'connected', s.error);
    assert.strictEqual((await c.call('remote', 'add', { a: 40, b: 2 })).out, '42');
    assert.strictEqual((await c.call('remote', 'save_note', { text: 'hello', tags: ['a', 'b'] })).out, 'saved: hello [a,b]');
  });
  await check('a wrong token is reported plainly', async () => {
    await c.saveServer({ name: 'badtoken', kind: 'http', url: httpSrv.url, headers: { Authorization: 'Bearer wrong' } });
    await c.connect('badtoken');
    const s = c.list().servers.find((x) => x.name === 'badtoken');
    assert.strictEqual(s.status, 'error'); assert.ok(/401|turned Atlas down/i.test(s.error), s.error);
    await c.removeServer('badtoken');
  });
  await check('a command that does not exist is reported plainly', async () => {
    await c.saveServer({ name: 'missing', kind: 'stdio', command: 'atlas-no-such-program-xyz', args: [] });
    await c.connect('missing');
    const s = c.list().servers.find((x) => x.name === 'missing');
    assert.strictEqual(s.status, 'error'); assert.ok(/wasn't found|ENOENT|closed|spawn/i.test(s.error), s.error);
    await c.removeServer('missing');
  });

  // ---------------------------------------------------------------- secrets
  await check('tokens and environment values never show in the list, and are not stored as plain text', async () => {
    const shown = JSON.stringify(c.list());
    assert.ok(!shown.includes(TOKEN) && !shown.includes('hidden-env-value'), 'a secret leaked into the list');
    assert.ok(shown.includes('"headerKeys":["Authorization"]') && shown.includes('"envKeys":["NOTES_KEY"]'));
    const file = mem.file();
    assert.ok(!file.includes(TOKEN) && !file.includes('hidden-env-value'), 'a secret was stored in the clear');
  });
  await check('changing a server without giving its token again keeps the stored token', async () => {
    await c.saveServer({ name: 'remote', kind: 'http', url: httpSrv.url });   // no headers given
    await c.connect('remote');
    assert.strictEqual(c.list().servers.find((x) => x.name === 'remote').status, 'connected');
  });
  await check('connections come back after a restart (from the saved file)', async () => {
    const again = createConnections({ ...mem.deps, load: () => mem.file() });
    await again.connectAll();
    const st = Object.fromEntries(again.list().servers.map((s) => [s.name, s.status]));
    assert.deepStrictEqual(st, { notes: 'connected', remote: 'connected' });
    await again.close();
  });
  await check('disconnect and remove', async () => {
    await c.disconnect('remote');
    assert.strictEqual(c.list().servers.find((x) => x.name === 'remote').status, 'off');
    assert.ok(/isn't connected/.test((await c.call('remote', 'add', { a: 1, b: 1 })).error));
    await c.removeServer('remote');
    assert.ok(!c.list().servers.some((x) => x.name === 'remote'));
  });

  // ---------------------------------------------------------------- pasted setups
  await check('a pasted mcpServers block is read (programs and addresses)', async () => {
    const cfgs = conn.parseConfig(JSON.stringify({ mcpServers: { 'my files': { command: 'npx', args: ['-y', 'x'], env: { K: 'v' } }, web: { url: 'https://example.com/mcp', headers: { Authorization: 'Bearer t' } } } }));
    assert.deepStrictEqual(cfgs.map((x) => [x.name, x.kind]), [['my_files', 'stdio'], ['web', 'http']]);
    assert.throws(() => conn.parseConfig('not json'), /valid JSON/);
    assert.throws(() => conn.parseConfig('{"mcpServers":{"x":{}}}'), /No servers/);
  });

  // ---------------------------------------------------------------- web APIs
  const seen = [];
  const api = http.createServer((req, res) => { let b = ''; req.on('data', (d) => (b += d)); req.on('end', () => { seen.push({ m: req.method, u: req.url, auth: req.headers.authorization, b }); res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, method: req.method })); }); });
  await new Promise((r) => api.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${api.address().port}/v1`;
  await check('a web API connection: GET reads with the stored header; POST sends JSON', async () => {
    await c.saveApi({ name: 'shop', base, description: 'Test shop', headers: { Authorization: 'Bearer api-key-xyz' } });
    const r = await c.callApi('shop', 'GET', '/items', null, { limit: 5 });
    assert.ok(/^HTTP 200/.test(r.out), r.out);
    assert.strictEqual(seen[0].u, '/v1/items?limit=5'); assert.strictEqual(seen[0].auth, 'Bearer api-key-xyz');
    await c.callApi('shop', 'POST', 'items', { name: 'pen' });
    assert.strictEqual(seen[1].m, 'POST'); assert.deepStrictEqual(JSON.parse(seen[1].b), { name: 'pen' });
    assert.ok(!JSON.stringify(c.list()).includes('api-key-xyz'));
  });
  await check("a web API call can't leave the API's own address", async () => {
    for (const p of ['https://evil.example/x', '//evil.example/x', '../../admin', '/..%2f..%2fadmin', 'http:evil']) {
      const r = await c.callApi('shop', 'GET', p);
      assert.ok(r.error, `${p} was allowed`);
    }
    assert.strictEqual(seen.length, 2, 'nothing more reached the server');
  });
  await check('API approvals: GET goes ahead, anything else asks', async () => {
    const idx = new Map(); const decl = conn.declarationsFrom(c.list(), 'chat', idx);
    assert.ok(decl.some((d) => d.name === 'call_api'));
    assert.strictEqual(conn.approvalFrom(idx, 'call_api', { connection: 'shop', method: 'GET', path: '/items' }), null);
    assert.ok(/DELETE \/items\/1 on shop/.test(conn.approvalFrom(idx, 'call_api', { connection: 'shop', method: 'DELETE', path: '/items/1' })));
  });

  await c.close(); httpSrv.proc.kill(); api.close();
  console.log(`\n${passed}/${passed + failed} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
