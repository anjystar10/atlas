// Atlas's connections to outside services: MCP servers (programs on this PC, or remote ones over HTTP) and plain
// web APIs. This runs in the app's main process; the pages only ever see names, statuses and tool lists, never the
// secrets (tokens, environment values), which are stored encrypted on this PC and are not synced.
'use strict';

const NAME = /^[A-Za-z0-9_-]{1,32}$/;
const TRUST = ['ask', 'reads', 'all'];
const MAX_OUT = 12000;
const cap = (s) => { s = String(s ?? ''); return s.length > MAX_OUT ? s.slice(0, MAX_OUT) + '\n(cut short: the answer was longer)' : s; };
const withTimeout = (p, ms, what) => { let t; return Promise.race([p, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(what + ' took too long')), ms); })]).finally(() => clearTimeout(t)); };

/**
 * deps: load(): string|null and save(text): the connections file; encrypt(text) → string and decrypt(string) → text
 * for the secrets; changed(): called when a status or list changes; fetch (defaults to the global one).
 */
function createConnections(deps) {
  const sdk = {
    Client: require('@modelcontextprotocol/sdk/client/index.js').Client,
    Stdio: require('@modelcontextprotocol/sdk/client/stdio.js'),
    Http: require('@modelcontextprotocol/sdk/client/streamableHttp.js').StreamableHTTPClientTransport,
    Sse: require('@modelcontextprotocol/sdk/client/sse.js').SSEClientTransport,
  };
  const doFetch = deps.fetch || fetch;
  const live = new Map();   // server name → { client, transport, status, error, tools, stderr }
  let data = { servers: [], apis: [] };
  try { const t = deps.load(); if (t) { const d = JSON.parse(t); data = { servers: Array.isArray(d.servers) ? d.servers : [], apis: Array.isArray(d.apis) ? d.apis : [] }; } } catch {}
  const persist = () => deps.save(JSON.stringify(data, null, 1));
  const changed = () => { try { deps.changed && deps.changed(); } catch {} };
  const secret = (rec) => { try { return rec.secret ? JSON.parse(deps.decrypt(rec.secret)) : {}; } catch { return {}; } };
  const seal = (obj) => deps.encrypt(JSON.stringify(obj));
  const server = (name) => data.servers.find((s) => s.name === name);
  const clean = (o) => { const out = {}; if (o && typeof o === 'object') for (const [k, v] of Object.entries(o)) if (typeof k === 'string' && k.trim() && v != null) out[k.trim()] = String(v); return out; };

  /** What the pages may see: everything but the secret values. */
  function list() {
    return {
      servers: data.servers.map((s) => {
        const l = live.get(s.name) || {}; const sec = secret(s);
        return { name: s.name, kind: s.kind, command: s.command || '', args: s.args || [], url: s.url || '', envKeys: Object.keys(sec.env || {}), headerKeys: Object.keys(sec.headers || {}),
          trust: s.trust || 'reads', chat: s.chat !== false, bots: s.bots !== false, on: s.on !== false, status: l.status || 'off', error: l.error || '', tools: l.tools || [] };
      }),
      apis: data.apis.map((a) => ({ name: a.name, base: a.base, description: a.description || '', headerKeys: Object.keys(secret(a).headers || {}), chat: a.chat !== false, bots: a.bots !== false })),
    };
  }

  /** Adds or changes a server. env / headers left out keep what was stored. */
  async function saveServer(cfg) {
    const name = String(cfg.name || '').trim();
    if (!NAME.test(name)) throw new Error('Give it a short name: letters, numbers, - or _ (no spaces).');
    const kind = cfg.kind === 'http' || (!cfg.kind && cfg.url) ? 'http' : 'stdio';
    if (kind === 'http' && !/^https?:\/\//i.test(String(cfg.url || ''))) throw new Error('The address must start with https:// (or http://).');
    if (kind === 'stdio' && !String(cfg.command || '').trim()) throw new Error('Give the command that starts the server.');
    const old = server(name); const oldSec = old ? secret(old) : {};
    const rec = { name, kind, command: kind === 'stdio' ? String(cfg.command).trim() : '', args: kind === 'stdio' && Array.isArray(cfg.args) ? cfg.args.map(String) : [], cwd: cfg.cwd ? String(cfg.cwd) : '',
      url: kind === 'http' ? String(cfg.url).trim() : '', trust: TRUST.includes(cfg.trust) ? cfg.trust : (old && old.trust) || 'reads',
      chat: cfg.chat ?? (old ? old.chat !== false : true), bots: cfg.bots ?? (old ? old.bots !== false : true), on: cfg.on ?? true,
      secret: seal({ env: cfg.env !== undefined ? clean(cfg.env) : oldSec.env || {}, headers: cfg.headers !== undefined ? clean(cfg.headers) : oldSec.headers || {} }) };
    await disconnect(name, true);
    if (old) data.servers[data.servers.indexOf(old)] = rec; else data.servers.push(rec);
    persist(); changed();
    return name;
  }
  function setServer(name, patch) {
    const s = server(name); if (!s) throw new Error('No connection called ' + name);
    if (TRUST.includes(patch.trust)) s.trust = patch.trust;
    if (typeof patch.chat === 'boolean') s.chat = patch.chat;
    if (typeof patch.bots === 'boolean') s.bots = patch.bots;
    persist(); changed();
  }
  async function removeServer(name) { await disconnect(name, true); data.servers = data.servers.filter((s) => s.name !== name); persist(); changed(); }

  async function connect(name) {
    const s = server(name); if (!s) throw new Error('No connection called ' + name);
    await disconnect(name, true);
    const st = { status: 'connecting', error: '', tools: [], client: null, transport: null, stderr: '' };
    live.set(name, st); s.on = true; persist(); changed();
    const sec = secret(s);
    const open = async (make) => {
      const client = new sdk.Client({ name: 'atlas', version: '1' }, { capabilities: {} });
      const transport = make();
      try { await withTimeout(client.connect(transport), 30000, 'Connecting'); return { client, transport }; }
      catch (e) { try { await transport.close(); } catch {} throw e; }
    };
    try {
      let c;
      if (s.kind === 'http') {
        const url = new URL(s.url); const requestInit = { headers: sec.headers || {} };
        try { c = await open(() => new sdk.Http(url, { requestInit })); }
        catch (first) {
          // older servers speak the earlier SSE way
          try { c = await open(() => new sdk.Sse(url, { requestInit, eventSourceInit: { fetch: (u, init) => doFetch(u, { ...init, headers: { ...(init && init.headers), ...(sec.headers || {}) } }) } })); }
          catch { throw first; }
        }
      } else {
        c = await open(() => {
          const t = new sdk.Stdio.StdioClientTransport({ command: s.command, args: s.args || [], env: { ...sdk.Stdio.getDefaultEnvironment(), ...(sec.env || {}) }, stderr: 'pipe', ...(s.cwd ? { cwd: s.cwd } : {}) });
          if (t.stderr) t.stderr.on('data', (d) => { st.stderr = (st.stderr + d).slice(-600); });
          return t;
        });
      }
      if (live.get(name) !== st) { try { await c.client.close(); } catch {} return list(); }   // removed or restarted meanwhile
      st.client = c.client; st.transport = c.transport;
      c.client.onclose = () => { if (live.get(name) === st && st.status === 'connected') { st.status = 'error'; st.error = 'The connection closed.' + (st.stderr ? ' ' + st.stderr.trim().split('\n').pop() : ''); st.tools = []; changed(); } };
      st.tools = await readTools(c.client);
      st.status = 'connected';
    } catch (e) {
      if (live.get(name) === st) { st.status = 'error'; st.error = explain(e, st.stderr); }
    }
    changed();
    return list();
  }
  function explain(e, stderr) {
    const m = String((e && e.message) || e);
    if (/ENOENT/.test(m)) return "Couldn't start it: that command wasn't found on this PC.";
    if (/401|403|Unauthorized/i.test(m)) return 'The server turned Atlas down (401/403). Check the token.';
    const tail = stderr && stderr.trim() ? ' — ' + stderr.trim().split('\n').pop().slice(0, 160) : '';
    return m.slice(0, 200) + tail;
  }
  async function readTools(client) {
    const out = []; let cursor;
    for (let page = 0; page < 20; page++) {
      const r = await withTimeout(client.listTools(cursor ? { cursor } : undefined), 30000, 'Listing tools');
      for (const t of r.tools || []) {
        const a = t.annotations || {};
        out.push({ name: t.name, description: String(t.description || a.title || t.title || '').slice(0, 1024), inputSchema: t.inputSchema || { type: 'object', properties: {} }, readOnly: a.readOnlyHint === true, destructive: a.destructiveHint === true });
      }
      cursor = r.nextCursor; if (!cursor) break;
    }
    return out.slice(0, 200);
  }
  async function disconnect(name, quiet) {
    const st = live.get(name); live.delete(name);
    if (st && st.client) { try { await withTimeout(st.client.close(), 5000, 'Closing'); } catch {} }
    if (!quiet) { const s = server(name); if (s) { s.on = false; persist(); } changed(); }
  }
  /** At start: the servers that were on come back on (one failing doesn't stop the others). */
  async function connectAll() { await Promise.all(data.servers.filter((s) => s.on !== false).map((s) => connect(s.name).catch(() => {}))); }
  async function close() { await Promise.all([...live.keys()].map((n) => disconnect(n, true))); }

  /** Calls one tool. → { out, image? } or { error }. */
  async function call(name, tool, args, timeoutMs = 120000) {
    const st = live.get(name);
    if (!st || st.status !== 'connected') return { error: `${name} isn't connected. The user can connect it in Me → Connections.` };
    if (!st.tools.some((t) => t.name === tool)) return { error: `${name} has no tool called ${tool}.` };
    try {
      const r = await st.client.callTool({ name: tool, arguments: args && typeof args === 'object' ? args : {} }, undefined, { timeout: timeoutMs });
      let text = '', image = null;
      for (const c of r.content || []) {
        if (c.type === 'text') text += (text ? '\n' : '') + c.text;
        else if (c.type === 'image' && !image && c.data && String(c.data).length < 6_000_000) image = { mime: c.mimeType || 'image/png', base64: c.data };
        else if (c.type === 'resource' && c.resource && c.resource.text) text += (text ? '\n' : '') + c.resource.text;
        else if (c.type === 'resource_link') text += (text ? '\n' : '') + `(link: ${c.uri})`;
      }
      if (!text && r.structuredContent) text = JSON.stringify(r.structuredContent);
      if (r.isError) return { error: cap(text || 'The tool reported an error.') };
      return { out: cap(text || (image ? '(a picture)' : 'Done.')), image };
    } catch (e) { return { error: String((e && e.message) || e).slice(0, 400) }; }
  }

  // ---- plain web APIs

  async function saveApi(cfg) {
    const name = String(cfg.name || '').trim();
    if (!NAME.test(name)) throw new Error('Give it a short name: letters, numbers, - or _ (no spaces).');
    if (!/^https?:\/\//i.test(String(cfg.base || ''))) throw new Error('The address must start with https:// (or http://).');
    const old = data.apis.find((a) => a.name === name);
    const rec = { name, base: String(cfg.base).trim().replace(/\/+$/, ''), description: String(cfg.description || '').slice(0, 600), chat: cfg.chat ?? true, bots: cfg.bots ?? true,
      secret: seal({ headers: cfg.headers !== undefined ? clean(cfg.headers) : (old ? secret(old).headers : {}) || {} }) };
    if (old) data.apis[data.apis.indexOf(old)] = rec; else data.apis.push(rec);
    persist(); changed(); return name;
  }
  function removeApi(name) { data.apis = data.apis.filter((a) => a.name !== name); persist(); changed(); }

  /** The address a call goes to; throws when [path] would leave the API's own address. */
  function apiUrl(base, path, query) {
    const b = new URL(base.replace(/\/+$/, '') + '/');
    const p = String(path || '').trim();
    if (/^[a-z][a-z0-9+.-]*:/i.test(p) || p.startsWith('//') || p.includes('\\')) throw new Error('Give a path inside the API, like /v1/items, not a whole address.');
    const u = new URL(p.replace(/^\/+/, ''), b);
    if (u.origin !== b.origin || !u.pathname.startsWith(b.pathname)) throw new Error('That path leaves the API.');
    if (query && typeof query === 'object') for (const [k, v] of Object.entries(query)) if (v != null) u.searchParams.set(k, String(v));
    return u.toString();
  }
  async function callApi(name, method, path, body, query) {
    const a = data.apis.find((x) => x.name === name);
    if (!a) return { error: `There's no API connection called ${name}.` };
    const m = String(method || 'GET').toUpperCase();
    if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(m)) return { error: 'Method must be GET, POST, PUT, PATCH or DELETE.' };
    let url; try { url = apiUrl(a.base, path, query); } catch (e) { return { error: e.message }; }
    const headers = { Accept: 'application/json, text/plain;q=0.9, */*;q=0.5', ...(secret(a).headers || {}) };
    const init = { method: m, headers, redirect: 'error' };
    if (body != null && m !== 'GET') { init.body = typeof body === 'string' ? body : JSON.stringify(body); if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) headers['Content-Type'] = 'application/json'; }
    try {
      const r = await withTimeout(doFetch(url, init), 60000, 'The API');
      const text = await r.text();
      return { out: cap(`HTTP ${r.status}\n${text}`), status: r.status };
    } catch (e) { return { error: String((e && e.cause && e.cause.code) || (e && e.message) || e).slice(0, 300) }; }
  }

  return { list, saveServer, setServer, removeServer, connect, disconnect, connectAll, close, call, saveApi, removeApi, callApi, apiUrl };
}

module.exports = { createConnections, NAME };
