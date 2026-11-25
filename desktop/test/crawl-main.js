// The deep crawl's way into the real app (desktop/test/crawl.mjs starts Electron with this file): it makes the main
// process safe to poke at, then loads main.js unchanged.
//  - every request to the internet goes to the stand-in at DEEP_STUB instead (tools/deep/stub.py: it answers the AI
//    services in their own shapes, and "not found" for everything else), with the real host in X-Deep-Host
//  - dialogs answer Cancel by themselves (nobody is there to click), links and folders don't open, autostart isn't set
//  - anything the main process throws or logs as an error is written to DEEP_LOG, with what the crawler was doing
'use strict';
const fs = require('fs'), path = require('path');
const electron = require('electron');
const { app, dialog, shell } = electron;
const LOG = process.env.DEEP_LOG || path.join(require('os').tmpdir(), 'deep-main.log');
const note = (kind, detail) => { try { fs.appendFileSync(LOG, JSON.stringify({ t: Date.now(), kind, detail: String(detail).slice(0, 4000) }) + '\n'); } catch {} };

const STUB = process.env.DEEP_STUB || '';
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init = {}) => {
  const u = new URL(String(url instanceof Request ? url.url : url));
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname)) {
    note('net', `${(init && init.method) || 'GET'} ${u.host}${u.pathname}`);
    if (!STUB) throw Object.assign(new TypeError('fetch failed'), { cause: { code: 'ENOTFOUND' } });
    const headers = new Headers((init && init.headers) || {});
    headers.set('X-Deep-Host', u.host);
    return realFetch(STUB + u.pathname + u.search, { ...init, headers });
  }
  return realFetch(url, init);
};
// node's own https/http clients (mail, MCP over the web, brokers): refused, as if offline
for (const mod of ['https', 'http']) {
  const m = require(mod); const real = m.request;
  m.request = function (opts, ...rest) {
    const host = typeof opts === 'string' ? new URL(opts).hostname : opts instanceof URL ? opts.hostname : (opts && (opts.hostname || opts.host)) || '';
    if (!/^(127\.0\.0\.1|localhost)$/.test(String(host).replace(/:\d+$/, ''))) note('net-' + mod, host);
    return real.call(this, opts, ...rest);
  };
}

const cancelOf = (o) => (o && Number.isInteger(o.cancelId) ? o.cancelId : o && Array.isArray(o.buttons) ? o.buttons.length - 1 : 0);
dialog.showMessageBox = async (...a) => { const o = a.find((x) => x && typeof x === 'object' && x.message); note('dialog', JSON.stringify({ message: o && o.message, detail: o && o.detail, buttons: o && o.buttons })); return { response: cancelOf(o), checkboxChecked: false }; };
dialog.showMessageBoxSync = (...a) => { const o = a.find((x) => x && typeof x === 'object' && x.message); note('dialog-sync', JSON.stringify({ message: o && o.message, buttons: o && o.buttons })); return cancelOf(o); };
dialog.showErrorBox = (title, content) => note('error-box', `${title}: ${content}`);
dialog.showOpenDialog = async (...a) => { note('open-dialog', JSON.stringify(a.find((x) => x && x.properties) || {})); return { canceled: true, filePaths: [] }; };
dialog.showSaveDialog = async () => { note('save-dialog', ''); return { canceled: true }; };
shell.openExternal = async (url) => { note('open-external', url); };
shell.openPath = async (p) => { note('open-path', p); return ''; };
shell.showItemInFolder = (p) => note('show-in-folder', p);
app.setLoginItemSettings = (s) => note('autostart', JSON.stringify(s));

process.on('uncaughtException', (e) => note('main-uncaught', e && e.stack || e));
process.on('unhandledRejection', (e) => note('main-unhandled-rejection', e && e.stack || e));
const realError = console.error;
console.error = (...a) => { note('main-console-error', a.map((x) => (x && x.stack) || String(x)).join(' ')); realError(...a); };
app.on('render-process-gone', (_e, wc, d) => note('render-process-gone', JSON.stringify(d)));
app.on('child-process-gone', (_e, d) => note('child-process-gone', JSON.stringify(d)));

// started as `electron test/crawl-main.js`, the app would report Electron's version: give it Atlas's own
try { app.setVersion(require('../package.json').version); app.setName('Atlas'); } catch {}
require('../main.js');
