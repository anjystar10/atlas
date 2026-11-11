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
  device: () => ipcRenderer.invoke('device:profile'),
  openExternal: (url) => ipcRenderer.invoke('open:external', url),
  pickFiles: () => ipcRenderer.invoke('file:pick'),
  loadFile: (p) => ipcRenderer.invoke('file:load', p),
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
  // >>> vault
  vault: {
    list: () => ipcRenderer.invoke('vault:list'),
    model: () => ipcRenderer.invoke('vault:model'),
    save: (entry) => ipcRenderer.invoke('vault:save', entry),
    remove: (id) => ipcRenderer.invoke('vault:remove', id),
    reveal: (id) => ipcRenderer.invoke('vault:reveal', id),
  },
  // <<< vault
  // >>> connections
  conn: (op, ...args) => ipcRenderer.invoke('conn', op, ...args),
  // <<< connections
  on: (channel, fn) => {
    const ok = ['notification-click', 'orb-clicked', 'orb-state', 'connections-changed'];
    if (!ok.includes(channel)) return;
    ipcRenderer.on(channel, (_e, ...args) => fn(...args));
  }
});
