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
  on: (channel, fn) => {
    const ok = ['notification-click', 'orb-clicked', 'orb-state'];
    if (!ok.includes(channel)) return;
    ipcRenderer.on(channel, (_e, ...args) => fn(...args));
  }
});
