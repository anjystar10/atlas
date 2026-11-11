// Me → Connections: the MCP servers and web APIs Atlas can use. Add one (paste a setup, or fill the form), see its
// status and tools, choose who may use it and how much to ask, connect, disconnect, remove.
import * as conn from './connect.js';
const host = window.atlasHost;

const STATUS = { connected: ['Connected', 'ok'], connecting: ['Connecting…', 'muted'], error: ['Problem', 'bad'], off: ['Off', 'muted'] };

/** Draws the Connections section into [col]. [ui]: { h, esc, sheet, toast, render, settingRow } from the app. */
export function renderConnections(col, ui) {
  const { h, esc, sheet, toast, render, settingRow } = ui;
  const snap = conn.state.snapshot;
  col.appendChild(h('<div class="eyebrow">Connections</div>'));
  const card = h('<div class="card"></div>'); col.appendChild(card);
  const n = snap.servers.length + snap.apis.length;
  card.appendChild(h(`<div style="font:400 22px var(--serif)">${n ? `${n} connection${n === 1 ? '' : 's'}` : 'None yet'}</div>`));
  card.appendChild(h('<p class="muted" style="margin:4px 0 12px">Connect outside services so Atlas and Atlas Bot can use them: MCP servers (the standard many apps offer) and plain web APIs. Tokens stay on this PC, encrypted; they don\'t sync.</p>'));

  const list = h('<div class="list"></div>');
  for (const s of snap.servers) {
    const [word, cls] = STATUS[s.status] || STATUS.off;
    const where = s.kind === 'http' ? s.url : [s.command, ...s.args].join(' ');
    const row = h(`<div class="item" style="display:block">
      <div class="row" style="align-items:flex-start"><div class="grow" style="min-width:0"><div class="t">${esc(s.name)} <span class="tag ${cls}">${esc(word)}</span></div>
        <div class="m mono" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(where)}</div>
        <div class="small ${s.status === 'error' ? 'bad' : 'muted'}">${esc(s.status === 'error' ? s.error : s.status === 'connected' ? `${s.tools.length} tool${s.tools.length === 1 ? '' : 's'}` + (s.tools.length ? ': ' + s.tools.slice(0, 6).map((t) => t.name).join(', ') + (s.tools.length > 6 ? '…' : '') : '') : '')}</div></div>
        <button class="btn" data-k="toggle">${s.status === 'connected' || s.status === 'connecting' ? 'Disconnect' : 'Connect'}</button><button class="btn" data-k="more">Options</button></div></div>`);
    row.querySelector('[data-k=toggle]').onclick = async () => { await host.conn(s.status === 'connected' || s.status === 'connecting' ? 'disconnect' : 'connect', s.name); await conn.refresh(); render(true); };
    row.querySelector('[data-k=more]').onclick = () => serverOptions(s, ui);
    list.appendChild(row);
  }
  for (const a of snap.apis) {
    const row = h(`<div class="item"><div class="grow" style="min-width:0"><div class="t">${esc(a.name)} <span class="tag">Web API</span></div><div class="m mono" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(a.base)}</div>
      ${a.description ? `<div class="small muted">${esc(a.description.slice(0, 120))}</div>` : ''}</div><button class="btn danger">Remove</button></div>`);
    row.querySelector('button').onclick = async () => { if (await sheet({ title: `Remove ${a.name}?`, body: 'Atlas stops using this API. Its token is deleted from this PC.', primary: 'Remove', danger: true })) { await host.conn('removeApi', a.name); await conn.refresh(); render(true); } };
    list.appendChild(row);
  }
  if (n) card.appendChild(list);

  const add = h('<div class="row" style="margin-top:12px"><button class="btn primary">Add an MCP Server</button><button class="btn">Paste a Setup</button><button class="btn">Add a Web API</button></div>');
  add.children[0].onclick = () => addServer(ui);
  add.children[1].onclick = () => pasteSetup(ui);
  add.children[2].onclick = () => addApi(ui);
  card.appendChild(add);
  card.appendChild(h('<p class="small muted" style="margin-top:10px">Atlas asks before a connection\'s tool changes anything, unless you choose otherwise in its Options. Only connect services you trust: what they return is read by the AI.</p>'));
  void settingRow;
}

function pairs(text) {   // "NAME=value" lines → {NAME: value}
  const out = {}; for (const line of String(text || '').split('\n')) { const i = line.indexOf('='); const j = line.indexOf(':'); const k = i < 0 ? j : j < 0 ? i : Math.min(i, j); if (k > 0) out[line.slice(0, k).trim()] = line.slice(k + 1).trim(); }
  return out;
}
/** A command line split into words, keeping "quoted parts" together. */
export function words(cmd) { return (String(cmd).match(/"[^"]*"|'[^']*'|\S+/g) || []).map((w) => w.replace(/^(["'])(.*)\1$/, '$2')); }

async function save(cfgs, ui) {
  let ok = 0;
  for (const c of cfgs) { const r = await host.conn('saveServer', c); if (r && r.error) ui.toast(`${c.name}: ${r.error}`, 4000); else ok++; }
  await conn.refresh(); ui.render(true);
  if (ok) ui.toast(ok === 1 ? 'Added. Connecting…' : `Added ${ok}. Connecting…`);
}

function addServer(ui) {
  ui.sheet({ title: 'Add an MCP Server', body: 'A server on the internet has an address. One that runs on this PC has a command.', primary: 'Add',
    html: `<input class="field" id="c-name" placeholder="Name (e.g. notion)" maxlength="32" style="margin-bottom:8px">
      <input class="field mono" id="c-where" placeholder="https://…/mcp   or   npx -y some-mcp-server" style="margin-bottom:8px">
      <input class="field mono" id="c-token" type="password" placeholder="Token (optional; sent as Authorization: Bearer …)" style="margin-bottom:8px">
      <textarea class="field mono" id="c-extra" rows="2" placeholder="Optional, one per line: NAME=value (headers for an address, environment for a command)"></textarea>`,
    onPrimary: async (o) => {
      const name = o.querySelector('#c-name').value.trim(), where = o.querySelector('#c-where').value.trim(), token = o.querySelector('#c-token').value.trim(), extra = pairs(o.querySelector('#c-extra').value);
      if (!/^[A-Za-z0-9_-]{1,32}$/.test(name)) { ui.toast('Give it a short name: letters, numbers, - or _.'); return false; }
      if (!where) { ui.toast('Give its address or its command.'); return false; }
      if (/^https?:\/\//i.test(where)) await save([{ name, kind: 'http', url: where, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...extra } }], ui);
      else { const w = words(where); await save([{ name, kind: 'stdio', command: w[0], args: w.slice(1), env: { ...(token ? { API_KEY: token } : {}), ...extra } }], ui); }
      return true;
    } });
}
function pasteSetup(ui) {
  ui.sheet({ title: 'Paste a Setup', body: 'The JSON block a service gives you for apps like this one ("mcpServers").', primary: 'Add',
    html: '<textarea class="field mono" id="c-json" rows="9" placeholder=\'{ "mcpServers": { "name": { "command": "npx", "args": ["-y", "…"] } } }\'></textarea>',
    onPrimary: async (o) => {
      let cfgs; try { cfgs = conn.parseConfig(o.querySelector('#c-json').value); } catch (e) { ui.toast(e.message, 4000); return false; }
      await save(cfgs, ui); return true;
    } });
}
function addApi(ui) {
  ui.sheet({ title: 'Add a Web API', body: 'Atlas can then call it. Reading (GET) goes ahead; anything that changes things asks you first.', primary: 'Add',
    html: `<input class="field" id="a-name" placeholder="Name (e.g. myserver)" maxlength="32" style="margin-bottom:8px">
      <input class="field mono" id="a-base" placeholder="https://api.example.com/v1" style="margin-bottom:8px">
      <input class="field mono" id="a-auth" type="password" placeholder="Authorization header (optional), e.g. Bearer abc123" style="margin-bottom:8px">
      <textarea class="field" id="a-desc" rows="3" placeholder="What it is and its main paths, so Atlas knows how to use it (or a link to its docs)"></textarea>`,
    onPrimary: async (o) => {
      const auth = o.querySelector('#a-auth').value.trim();
      const r = await host.conn('saveApi', { name: o.querySelector('#a-name').value.trim(), base: o.querySelector('#a-base').value.trim(), description: o.querySelector('#a-desc').value.trim(), headers: auth ? { Authorization: auth } : {} });
      if (r && r.error) { ui.toast(r.error, 4000); return false; }
      await conn.refresh(); ui.render(true); ui.toast('Added.'); return true;
    } });
}
function serverOptions(s, ui) {
  const { esc } = ui;
  const tools = s.tools.length ? `<div class="list" style="max-height:180px;overflow:auto;margin-top:8px">${s.tools.map((t) => `<div class="item" style="display:block"><div class="t mono" style="font-size:13px">${esc(t.name)}${t.readOnly ? ' <span class="tag ok">read-only</span>' : ''}</div><div class="small muted">${esc((t.description || '').slice(0, 140))}</div></div>`).join('')}</div>` : '';
  ui.sheet({ title: s.name, body: s.kind === 'http' ? s.url : [s.command, ...s.args].join(' '), primary: 'Save', secondary: 'Close',
    html: `<div class="small muted" style="margin:8px 0 4px">Before using its tools</div>
      <select class="field" id="o-trust">${Object.entries(conn.TRUST).map(([k, v]) => `<option value="${k}" ${k === s.trust ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select>
      <label class="row small" style="margin-top:10px;gap:8px"><input type="checkbox" id="o-chat" ${s.chat ? 'checked' : ''}> Atlas may use it in chat</label>
      <label class="row small" style="margin-top:6px;gap:8px"><input type="checkbox" id="o-bots" ${s.bots ? 'checked' : ''}> Atlas Bot may use it</label>
      ${s.envKeys.length || s.headerKeys.length ? `<div class="small muted" style="margin-top:10px">Stored secrets: ${esc([...s.envKeys, ...s.headerKeys].join(', '))} (values hidden)</div>` : ''}
      ${tools}
      <button class="btn danger" id="o-remove" style="margin-top:12px">Remove This Connection</button>`,
    onOpen: (o) => { o.querySelector('#o-remove').onclick = async () => { await host.conn('removeServer', s.name); await conn.refresh(); o.remove(); ui.render(true); ui.toast('Removed.'); }; },
    onPrimary: async (o) => {
      await host.conn('setServer', s.name, { trust: o.querySelector('#o-trust').value, chat: o.querySelector('#o-chat').checked, bots: o.querySelector('#o-bots').checked });
      await conn.refresh(); ui.render(true); return true;
    } });
}
