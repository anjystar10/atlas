// Atlas on Windows: the app's main process.
// The window is the whole app (it keeps running in the tray when closed, so reminders still come and sync goes on).
// This process only does what a web page can't: files on disk, the network without browser limits, notifications,
// the floating orb, and controlling the PC (see pc.js).
'use strict';
const { app, BrowserWindow, ipcMain, Tray, Menu, Notification, shell, screen, globalShortcut, session, nativeImage, desktopCapturer, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const pc = require('./pc');

const DATA = () => app.getPath('userData');
const file = (name) => path.join(DATA(), name);
let win = null, orbWin = null, tray = null, quitting = false;

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.on('second-instance', () => showMain());
app.setAppUserModelId('com.anjy.atlas.desktop');

function icon() { return nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png')); }

function createMain() {
  win = new BrowserWindow({
    width: 1180, height: 820, minWidth: 760, minHeight: 560, show: false,
    backgroundColor: '#05050F', title: 'Atlas', icon: icon(), autoHideMenuBar: true,
    titleBarStyle: process.platform === 'win32' ? 'hidden' : 'default',
    titleBarOverlay: process.platform === 'win32' ? { color: '#00000000', symbolColor: '#C9CDF0', height: 36 } : undefined,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: false, backgroundThrottling: false }
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => { if (!process.argv.includes('--hidden')) win.show(); });
  // closing hides to the tray; Quit (tray menu) really quits
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file://')) { e.preventDefault(); shell.openExternal(url); } });
}

function showMain() { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); }

// ------------------------------------------------------------------ the floating orb (always on top)

function createOrb() {
  if (orbWin) return;
  const wa = screen.getPrimaryDisplay().workArea;
  const size = 120;
  const saved = readJson('orb-pos.json', null);
  orbWin = new BrowserWindow({
    width: size, height: size, x: saved ? saved.x : wa.x + wa.width - size - 16, y: saved ? saved.y : wa.y + wa.height - size - 16,
    frame: false, transparent: true, resizable: false, skipTaskbar: true, alwaysOnTop: true, hasShadow: false, focusable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: false }
  });
  orbWin.setAlwaysOnTop(true, 'floating');
  orbWin.loadFile(path.join(__dirname, 'renderer', 'orb-float.html'));
  orbWin.on('closed', () => { orbWin = null; });
}
function destroyOrb() { if (orbWin) { orbWin.destroy(); orbWin = null; } }

// ------------------------------------------------------------------ files

function readJson(name, fallback) { try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch { return fallback; } }
function writeAtomic(name, text) {
  const f = file(name), tmp = f + '.tmp';
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, f);
}

ipcMain.handle('store:read', (_e, name) => { try { return fs.readFileSync(file(name), 'utf8'); } catch { return null; } });
ipcMain.handle('store:write', (_e, name, text) => {
  if (!/^[\w.-]+$/.test(name)) throw new Error('bad name');
  writeAtomic(name, text); return true;
});

// ------------------------------------------------------------------ network without browser limits (CORS) for AI services

ipcMain.handle('net:fetch', async (_e, url, opts = {}) => {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), opts.timeout || 120000);
  try {
    const init = { method: opts.method || 'GET', headers: opts.headers || {}, signal: ctrl.signal };
    if (opts.body != null) init.body = opts.bodyBase64 ? Buffer.from(opts.body, 'base64') : opts.body;
    if (opts.form) {
      const fd = new FormData();
      for (const [k, v] of Object.entries(opts.form)) {
        if (v && typeof v === 'object' && v.base64) fd.append(k, new Blob([Buffer.from(v.base64, 'base64')], { type: v.type }), v.name || 'file');
        else fd.append(k, String(v));
      }
      init.body = fd;
    }
    const r = await fetch(url, init);
    const buf = Buffer.from(await r.arrayBuffer());
    const type = r.headers.get('content-type') || '';
    const binary = opts.binary || /^(audio|image|video)\//.test(type) || type.includes('octet-stream');
    return { status: r.status, ok: r.ok, type, text: binary ? null : buf.toString('utf8'), base64: binary ? buf.toString('base64') : null };
  } catch (err) {
    return { status: 0, ok: false, error: err.name === 'AbortError' ? 'timed out' : (err.cause && err.cause.code) || err.message };
  } finally { clearTimeout(timer); }
});

// ------------------------------------------------------------------ the window, notifications, focus

ipcMain.handle('win:show', () => { showMain(); return true; });
ipcMain.handle('win:attention', (_e, on) => {
  // an alarm: come to the front, over everything, until answered
  if (!win) return;
  showMain();
  win.setAlwaysOnTop(!!on, 'screen-saver');
  if (on) win.flashFrame(true); else win.flashFrame(false);
});
ipcMain.handle('win:fullscreen', (_e, on) => { if (win) { showMain(); win.setFullScreen(!!on); } });
ipcMain.handle('notify', (_e, title, body, tag) => {
  if (!Notification.isSupported()) return false;
  const n = new Notification({ title, body, icon: icon(), silent: false, urgency: 'normal' });
  n.on('click', () => { showMain(); if (win) win.webContents.send('notification-click', tag); });
  n.show(); return true;
});
ipcMain.handle('orb:show', (_e, on) => { if (on) createOrb(); else destroyOrb(); return true; });
ipcMain.on('orb:clicked', () => { showMain(); if (win) win.webContents.send('orb-clicked'); });
ipcMain.on('orb:moveBy', (_e, dx, dy) => {
  if (!orbWin) return;
  const [x, y] = orbWin.getPosition(); orbWin.setPosition(Math.round(x + dx), Math.round(y + dy));
  const [nx, ny] = orbWin.getPosition(); writeAtomic('orb-pos.json', JSON.stringify({ x: nx, y: ny }));
});
// the floating orb mirrors what Atlas is doing (listening, thinking)
ipcMain.on('orb:state', (_e, st) => { if (orbWin) orbWin.webContents.send('orb-state', st); });
ipcMain.handle('app:autostart', (_e, on) => {
  if (typeof on === 'boolean') app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] });
  return app.getLoginItemSettings().openAtLogin;
});
ipcMain.handle('app:info', () => ({ version: app.getVersion(), platform: process.platform, data: DATA() }));
ipcMain.handle('open:external', (_e, url) => { if (/^(https?:|mailto:)/.test(url)) shell.openExternal(url); });

// ------------------------------------------------------------------ files the user picks or drops (attachments)

ipcMain.handle('file:pick', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Pictures, videos and files', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif', 'mp4', 'webm', 'mov', 'pdf', 'txt', 'md', 'csv', 'json', 'docx', 'xml', 'html', 'js', 'ts', 'py', 'kt', 'java', 'c', 'cpp', 'cs', 'go', 'rs', 'log'] }, { name: 'All files', extensions: ['*'] }] });
  return r.canceled ? [] : r.filePaths;
});
ipcMain.handle('file:load', (_e, p) => pc.loadAttachment(p));

// ------------------------------------------------------------------ controlling the PC

ipcMain.handle('pc:run', async (_e, name, args) => {
  try { return await pc.run(name, args || {}, { desktopCapturer, screen, shell }); }
  catch (err) { return { error: err.message || String(err) }; }
});

// ------------------------------------------------------------------ start

app.whenReady().then(() => {
  // the microphone (for talking to Atlas) and the screen (to look at it) are allowed for Atlas's own pages
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'microphone', 'audioCapture', 'clipboard-read', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => ['media', 'microphone', 'audioCapture', 'clipboard-read', 'clipboard-sanitized-write', 'notifications'].includes(perm));
  createMain();
  tray = new Tray(icon().resize({ width: 16, height: 16 }));
  tray.setToolTip('Atlas');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Atlas', click: showMain },
    { label: 'Talk to Atlas', click: () => { showMain(); win.webContents.send('orb-clicked'); } },
    { type: 'separator' },
    { label: 'Quit Atlas', click: () => { quitting = true; app.quit(); } }
  ]));
  tray.on('click', showMain);
  // Ctrl+Shift+Space from anywhere: Atlas listens
  try { globalShortcut.register('CommandOrControl+Shift+Space', () => { showMain(); win.webContents.send('orb-clicked'); }); } catch {}
});
app.on('before-quit', () => { quitting = true; });
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', (e) => { if (!quitting) e.preventDefault && e.preventDefault(); });
