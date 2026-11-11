// A tiny MCP server for Atlas's tests, made with the official SDK so the client is tested against the real protocol.
//   node mcp-server.js stdio              → speaks MCP over stdin/stdout
//   node mcp-server.js http PORT [TOKEN]   → Streamable HTTP at http://127.0.0.1:PORT/mcp (answers as SSE streams);
//                                            with TOKEN, requests must carry "Authorization: Bearer TOKEN"
'use strict';
const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { z } = require('zod');

// a 1×1 PNG
const PIXEL = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

function makeServer() {
  const s = new McpServer({ name: 'atlas-test', version: '1.0.0' });
  s.registerTool('add', { description: 'Adds two numbers', inputSchema: { a: z.number(), b: z.number() }, annotations: { readOnlyHint: true } },
    async ({ a, b }) => ({ content: [{ type: 'text', text: String(a + b) }] }));
  s.registerTool('save_note', { description: 'Saves a note (changes things)', inputSchema: { text: z.string(), tags: z.array(z.string()).optional() }, annotations: { destructiveHint: true } },
    async ({ text, tags }) => ({ content: [{ type: 'text', text: `saved: ${text}${tags && tags.length ? ' [' + tags.join(',') + ']' : ''}` }] }));
  s.registerTool('picture', { description: 'Returns a picture', inputSchema: {}, annotations: { readOnlyHint: true } },
    async () => ({ content: [{ type: 'text', text: 'here it is' }, { type: 'image', data: PIXEL, mimeType: 'image/png' }] }));
  s.registerTool('big', { description: 'Returns a lot of text', inputSchema: { n: z.number().int() }, annotations: { readOnlyHint: true } },
    async ({ n }) => ({ content: [{ type: 'text', text: 'x'.repeat(n) }] }));
  s.registerTool('fails', { description: 'Always reports an error', inputSchema: {} },
    async () => ({ isError: true, content: [{ type: 'text', text: 'it went wrong on purpose' }] }));
  s.registerTool('shape', {
    description: 'Takes a nested shape',
    inputSchema: { where: z.object({ city: z.string(), zip: z.string().nullable() }), mode: z.enum(['fast', 'slow']).default('fast'), extra: z.record(z.string(), z.any()).optional() },
  }, async (args) => ({ content: [{ type: 'text', text: JSON.stringify(args) }] }));
  return s;
}

async function main() {
  const mode = process.argv[2];
  if (mode === 'stdio') {
    const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
    await makeServer().connect(new StdioServerTransport());
    return;
  }
  if (mode === 'http') {
    const http = require('http'); const { randomUUID } = require('crypto');
    const { StreamableHTTPServerTransport } = require('@modelcontextprotocol/sdk/server/streamableHttp.js');
    const port = Number(process.argv[3] || 0); const token = process.argv[4] || '';
    const sessions = new Map();
    const srv = http.createServer(async (req, res) => {
      if (!req.url.startsWith('/mcp')) { res.writeHead(404).end(); return; }
      if (token && req.headers.authorization !== 'Bearer ' + token) { res.writeHead(401, { 'Content-Type': 'application/json' }).end('{"error":"unauthorized"}'); return; }
      let body = '';
      for await (const c of req) body += c;
      const parsed = body ? JSON.parse(body) : undefined;
      const sid = req.headers['mcp-session-id'];
      let t = sid ? sessions.get(sid) : null;
      if (!t) {
        if (sid) { res.writeHead(404).end(); return; }
        t = new StreamableHTTPServerTransport({ sessionIdGenerator: () => randomUUID(), onsessioninitialized: (id) => sessions.set(id, t) });
        t.onclose = () => { if (t.sessionId) sessions.delete(t.sessionId); };
        await makeServer().connect(t);
      }
      await t.handleRequest(req, res, parsed);
    });
    srv.listen(port, '127.0.0.1', () => { process.stdout.write('PORT ' + srv.address().port + '\n'); });
    return;
  }
  console.error('usage: mcp-server.js stdio | http PORT [TOKEN]'); process.exit(2);
}
main().catch((e) => { console.error(e); process.exit(1); });
