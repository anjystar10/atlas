// What Atlas's pages may ask of the app (and nothing more).
'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('atlasHost', {
  read: (name) => ipcRenderer.invoke('store:read', name),
  write: (name, text) => ipcRenderer.invoke('store:write', name, text),
  fetch: (url, opts) => ipcRenderer.invoke('net:fetch', url, opts),
  show: () => ipcRenderer.invoke('win:show'),
  attention: (on) => ipcRenderer.invoke('win:attention', on),
  fullscreen: (on) => ipcRenderer.invoke('win:fullscreen', on),
  notify: (title, body, tag) => ipcRenderer.invoke('notify', title, body, tag),
  showOrb: (on) => ipcRenderer.invoke('orb:show', on),
  orbClicked: () => ipcRenderer.send('orb:clicked'),
  orbMoveBy: (dx, dy) => ipcRenderer.send('orb:moveBy', dx, dy),
  orbState: (st) => ipcRenderer.send('orb:state', st),
  autostart: (on) => ipcRenderer.invoke('app:autostart', on),
  info: () => ipcRenderer.invoke('app:info'),
  // >>> self-awareness
  self: {
    changelog: () => ipcRenderer.invoke('self:changelog'),
    facts: () => ipcRenderer.invoke('self:facts'),
    download: (url, sha256, version) => ipcRenderer.invoke('self:download', url, sha256, version),
    install: (version) => ipcRenderer.invoke('self:install', version),
  },
  device: () => ipcRenderer.invoke('device:profile'),
  openExternal: (url) => ipcRenderer.invoke('open:external', url),
  pickFiles: () => ipcRenderer.invoke('file:pick'),
  loadFile: (p) => ipcRenderer.invoke('file:load', p),
  // >>> attachments
  attach: {
    take: (p, id) => ipcRenderer.invoke('attach:take', p, id),
    cancel: (id) => ipcRenderer.invoke('attach:cancel', id),
    bytes: (p) => ipcRenderer.invoke('attach:bytes', p),
    upload: (key, p, mime, name, id) => ipcRenderer.invoke('attach:upload', key, p, mime, name, id),
    read: (p, offset, length, member) => ipcRenderer.invoke('attach:read', p, offset, length, member),
    search: (p, query, max) => ipcRenderer.invoke('attach:search', p, query, max),
    onProgress: (fn) => ipcRenderer.on('attach:progress', (_e, id, done, total) => fn(id, done, total)),
  },
  // <<< attachments
  pathOf: (file) => { try { return webUtils.getPathForFile(file); } catch { return null; } },
  pc: (name, args) => ipcRenderer.invoke('pc:run', name, args),
  // >>> bot browser
  botBrowser: {
    run: (jobId, title, name, args) => ipcRenderer.invoke('bot:browser', jobId, title, name, args),
    check: (jobId, title, name, args) => ipcRenderer.invoke('bot:browser-check', jobId, title, name, args),
    show: (jobId, title) => ipcRenderer.invoke('bot:browser-show', jobId, title),
    close: (jobId) => ipcRenderer.invoke('bot:browser-close', jobId),
    info: (jobId) => ipcRenderer.invoke('bot:browser-info', jobId),
  },
  // <<< bot browser
  // >>> bot files
  botFiles: {
    run: (jobId, goal, name, args) => ipcRenderer.invoke('bot:files', jobId, goal, name, args),
    library: () => ipcRenderer.invoke('library:list'),
    open: (file) => ipcRenderer.invoke('library:open', file),
    show: (file) => ipcRenderer.invoke('library:show', file),
    remove: (file) => ipcRenderer.invoke('library:delete', file),
  },
  // <<< bot files
  // >>> projects
  projects: {
    run: (name, args, approved) => ipcRenderer.invoke('projects:run', name, args, approved === true),
    check: (name, args) => ipcRenderer.invoke('projects:check', name, args),
    list: () => ipcRenderer.invoke('projects:list'),
  },
  // <<< projects
  // >>> vault
  vault: {
    list: () => ipcRenderer.invoke('vault:list'),
    model: () => ipcRenderer.invoke('vault:model'),
    save: (entry) => ipcRenderer.invoke('vault:save', entry),
    remove: (id) => ipcRenderer.invoke('vault:remove', id),
    reveal: (id) => ipcRenderer.invoke('vault:reveal', id),
  },
  // <<< vault
  // >>> device link (control this PC from the phone)
  remote: {
    frame: (maxW, q) => ipcRenderer.invoke('remote:frame', maxW, q),
    input: (plan) => ipcRenderer.invoke('remote:input', plan),
    banner: (text) => ipcRenderer.invoke('remote:banner', text),
    done: () => ipcRenderer.invoke('remote:done'),
  },
  // <<< device link
  // >>> email (mail.js): the account and the password stay in the main process
  mail: (op, args) => ipcRenderer.invoke('mail', op, args),
  // <<< email
  // >>> bot triggers: a folder's file list (names, sizes, times) for "when a file appears"
  trigList: (source, folder) => ipcRenderer.invoke('trig:list', source, folder),
  // >>> trading
  trading: {
    run: (name, args, opts) => ipcRenderer.invoke('trade:run', name, args, opts),
    list: () => ipcRenderer.invoke('trade:list'),
    save: (acc, secret) => ipcRenderer.invoke('trade:save', acc, secret),
    remove: (id) => ipcRenderer.invoke('trade:remove', id),
    limits: (id, limits) => ipcRenderer.invoke('trade:limits', id, limits),
    standing: (id, lim) => ipcRenderer.invoke('trade:standing', id, lim),
    standingGet: (id) => ipcRenderer.invoke('trade:standingGet', id),
    test: (acc, secret) => ipcRenderer.invoke('trade:test', acc, secret),
    resume: () => ipcRenderer.invoke('trade:resume'),
  },
  // <<< trading
  // >>> atlas voice
  atlasVoice: {
    status: () => ipcRenderer.invoke('voice:status'),
    detect: () => ipcRenderer.invoke('voice:detect'),
    install: () => ipcRenderer.invoke('voice:install'),
    start: () => ipcRenderer.invoke('voice:start'),
    stop: () => ipcRenderer.invoke('voice:stop'),
    speak: (text, language) => ipcRenderer.invoke('voice:speak', text, language),
  },
  // <<< atlas voice
  // >>> connections
  conn: (op, ...args) => ipcRenderer.invoke('conn', op, ...args),
  // <<< connections
  on: (channel, fn) => {
    const ok = ['notification-click', 'orb-clicked', 'orb-state', 'connections-changed', 'remote-stop', 'trading-changed', 'voice-local'];
    if (!ok.includes(channel)) return;
    ipcRenderer.on(channel, (_e, ...args) => fn(...args));
  }
});
