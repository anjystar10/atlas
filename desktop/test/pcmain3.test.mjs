// Scan 3 (pc-main): the main process's own rules, run in plain Node (no Electron window needed).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

test('a connection is asked about as the same kind it is saved and run as', () => {
  const { kindOf } = require('../mcp.js');
  // main.js asks "Add this connection? It runs this program" only for stdio; mcp.js runs stdio commands
  assert.equal(kindOf({ kind: 'stdio', url: 'https://x.example/mcp', command: 'evil.exe' }), 'stdio');
  assert.equal(kindOf({ command: 'npx', args: ['-y', 'x'] }), 'stdio');
  assert.equal(kindOf({ kind: 'http', url: 'https://x.example/mcp' }), 'http');
  assert.equal(kindOf({ url: 'https://x.example/mcp' }), 'http');
  assert.equal(kindOf({}), 'stdio');
  assert.equal(kindOf(null), 'stdio');
});
