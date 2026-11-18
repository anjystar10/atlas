// Atlas on Windows: the app's main process.
// The window is the whole app (it keeps running in the tray when closed, so reminders still come and sync goes on).
// This process only does what a web page can't: files on disk, the network without browser limits, notifications,
// the floating orb, and controlling the PC (see pc.js).
'use strict';
const { app, BrowserWindow, ipcMain, Tray, Menu, Notification, shell, screen, globalShortcut, session, nativeImage, desktopCapturer, dialog, safeStorage } = require('electron');
const path = require('path');
const fs = require('fs');
const pc = require('./pc');

const DATA = () => app.getPath('userData');
const file = (name) => path.join(DATA(), name);
let win = null, orbWin = null, tray = null, quitting = false;

// only one Atlas: a second start just brings the first one forward (and this copy stops before making any windows)
// test hook (only with ATLAS_DATA set): keep this run's data in its own folder
if (process.env.ATLAS_DATA) app.setPath('userData', process.env.ATLAS_DATA);
const firstInstance = app.requestSingleInstanceLock();
if (!firstInstance) app.exit(0);
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
  // test hook (only when ATLAS_SHOT is set): print page errors, take screenshots, then quit
  if (process.env.ATLAS_SHOT) {
    win.webContents.on('console-message', (_e, level, msg, line, src) => {
      if (level < 2) return;
      const t = `[page] ${msg} ${src ? `(${path.basename(src)}:${line})` : ''}`; console.log(t);
      try { fs.appendFileSync(path.join(path.dirname(process.env.ATLAS_SHOT), 'page.txt'), t + '\n'); } catch {}
    });
    win.webContents.on('did-finish-load', async () => {
      const steps = JSON.parse(process.env.ATLAS_STEPS || '[]');
      for (const [i, js] of [[0, ''], ...steps.map((s, k) => [k + 1, s])]) {
        if (js) { try { await win.webContents.executeJavaScript(js); } catch (err) { console.log('[step]', err.message); } }
        await new Promise((r) => setTimeout(r, Number(process.env.ATLAS_WAIT || 2500)));
        const img = await win.webContents.capturePage();
        fs.writeFileSync(process.env.ATLAS_SHOT.replace('.png', `-${i}.png`), img.toPNG());
      }
      if (process.env.ATLAS_PCTEST) {
        // on a real Windows machine: do the PC tools work?
        for (const [name, args] of [['list_apps', {}], ['run_command', { command: 'Get-Date -Format yyyy-MM-dd; $PSVersionTable.PSVersion.ToString()' }], ['list_files', { path: '~' }], ['look_at_screen', {}], ['press_keys', { keys: 'ctrl+shift+t' }]]) {
          const r = await pc.run(name, args, { desktopCapturer, screen, shell }).catch((err) => ({ error: err.message }));
          const line = `[pc] ${name}: ${r.error ? 'ERROR ' + r.error : String(r.out).slice(0, 160).replace(/\n/g, ' | ')}${r.image ? ` (+ screenshot ${r.image.base64.length} chars)` : ''}`;
          console.log(line); fs.appendFileSync(path.join(path.dirname(process.env.ATLAS_SHOT), 'pctest.txt'), line + '\n');
        }
      }
      quitting = true; app.quit();
    });
  }
  // closing hides to the tray; Quit (tray menu) really quits
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
  win.webContents.setWindowOpenHandler(({ url }) => { openSafe(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (url !== win.webContents.getURL()) { e.preventDefault(); openSafe(url); } });
}

// links leave the app only for the web or email (never file:, javascript: or other schemes)
function openSafe(url) { if (/^(https?:|mailto:)/i.test(String(url))) shell.openExternal(url); }

function showMain() { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); }

// ------------------------------------------------------------------ the floating orb (always on top)

function createOrb() {
  if (orbWin) return;
  const wa = screen.getPrimaryDisplay().workArea;
  const size = 120;
  let saved = readJson('orb-pos.json', null);
  // a saved spot on a screen that's gone (a monitor unplugged, resolution changed) would hide the orb: use the corner
  if (saved && !onSomeScreen(saved.x, saved.y, size)) saved = null;
  orbWin = new BrowserWindow({
    width: size, height: size, x: saved ? saved.x : wa.x + wa.width - size - 16, y: saved ? saved.y : wa.y + wa.height - size - 16,
    frame: false, transparent: true, resizable: false, skipTaskbar: true, alwaysOnTop: true, hasShadow: false, focusable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: false }
  });
  orbWin.setAlwaysOnTop(true, 'floating');
  orbWin.loadFile(path.join(__dirname, 'renderer', 'orb-float.html'));
  orbWin.on('closed', () => { orbWin = null; });
}
function onSomeScreen(x, y, size) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return false;
  const cx = x + size / 2, cy = y + size / 2;
  return screen.getAllDisplays().some(({ workArea: a }) => cx >= a.x && cx < a.x + a.width && cy >= a.y && cy < a.y + a.height);
}
function keepOrbOnScreen() {
  if (!orbWin) return;
  const [x, y] = orbWin.getPosition(); const [w] = orbWin.getSize();
  if (onSomeScreen(x, y, w)) return;
  const wa = screen.getPrimaryDisplay().workArea;
  orbWin.setPosition(wa.x + wa.width - w - 16, wa.y + wa.height - w - 16);
}
function destroyOrb() { if (orbWin) { orbWin.destroy(); orbWin = null; } }

// ------------------------------------------------------------------ files

// only the main window may use the powerful calls (files, the network, the PC); the floating orb only moves and clicks
const fromMain = (e) => !!win && !win.isDestroyed() && e.sender === win.webContents;

function readJson(name, fallback) { try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch { return fallback; } }
function writeAtomic(name, text) {
  const f = file(name), tmp = f + '.tmp';
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, f);
}

// Atlas's own files only: a plain file name inside its data folder (no folders, no "..")
const okName = (name) => typeof name === 'string' && /^[\w-][\w.-]*$/.test(name) && !name.includes('..');
ipcMain.handle('store:read', (e, name) => { if (!fromMain(e) || !okName(name)) return null; try { return fs.readFileSync(file(name), 'utf8'); } catch { return null; } });
ipcMain.handle('store:write', (e, name, text) => {
  if (!fromMain(e) || !okName(name)) throw new Error('bad name');
  writeAtomic(name, String(text)); return true;
});

// ------------------------------------------------------------------ network without browser limits (CORS) for AI services

ipcMain.handle('net:fetch', async (e, url, opts = {}) => {
  if (!fromMain(e) || !/^https?:\/\//i.test(String(url))) return { status: 0, ok: false, error: 'not allowed' };
  // test hook (only with ATLAS_LINK_RELAY set): the Device Link and sync talk to a local stand-in for the cloud
  if (process.env.ATLAS_LINK_RELAY && /\/rest\/v1\/rpc\/(link_|sync_claim)/.test(String(url))) url = process.env.ATLAS_LINK_RELAY + String(url).replace(/^.*\/rpc\//, '/rpc/');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), opts.timeout || 120000);
  try {
    const init = { method: opts.method || 'GET', headers: opts.headers || {}, signal: ctrl.signal };
    if (opts.body != null) init.body = opts.bodyBase64 ? Buffer.from(opts.body, 'base64') : opts.body;
    if (opts.form) {
      const fd = new FormData();
      for (const [k, v] of Object.entries(opts.form)) {
        if (v && typeof v === 'object' && v.base64) fd.append(k, new Blob([Buffer.from(v.base64, 'base64')], { type: v.type }), v.name || 'file');
        else if (Array.isArray(v)) v.forEach((x) => fd.append(k, String(x)));   // a list: the same field once per item (e.g. keywords[])
        else fd.append(k, String(v));
      }
      init.body = fd;
    }
    const r = await fetch(url, init);
    const buf = Buffer.from(await r.arrayBuffer());
    const type = r.headers.get('content-type') || '';
    const binary = opts.binary || /^(audio|image|video)\//.test(type) || type.includes('octet-stream');
    return { status: r.status, ok: r.ok, type, retryAfter: r.headers.get('retry-after'), text: binary ? null : buf.toString('utf8'), base64: binary ? buf.toString('base64') : null };
  } catch (err) {
    return { status: 0, ok: false, error: err.name === 'AbortError' ? 'timed out' : (err.cause && err.cause.code) || err.message };
  } finally { clearTimeout(timer); }
});

// ------------------------------------------------------------------ the window, notifications, focus

ipcMain.handle('win:show', (e) => { if (!fromMain(e)) return false; showMain(); return true; });
ipcMain.handle('win:attention', (_e, on) => {
  // an alarm: come to the front, over everything, until answered
  if (!win) return;
  showMain();
  win.setAlwaysOnTop(!!on, 'screen-saver');
  if (on) win.flashFrame(true); else win.flashFrame(false);
});
ipcMain.handle('win:fullscreen', (_e, on) => { if (win) { showMain(); win.setFullScreen(!!on); } });
// notifications are held on to until clicked or closed: one that gets garbage-collected loses its click on Windows
const liveNotes = new Set();
ipcMain.handle('notify', (e, title, body, tag) => {
  if (!fromMain(e) || !Notification.isSupported()) return false;
  const n = new Notification({ title: String(title || 'Atlas'), body: String(body || ''), icon: icon(), silent: false, urgency: 'normal' });
  liveNotes.add(n);
  const drop = () => liveNotes.delete(n);
  n.on('click', () => { drop(); showMain(); if (win) win.webContents.send('notification-click', tag); });
  n.on('close', drop); n.on('failed', drop);
  n.show(); return true;
});
ipcMain.handle('orb:show', (e, on) => { if (!fromMain(e)) return false; if (on) createOrb(); else destroyOrb(); return true; });
ipcMain.on('orb:clicked', () => { showMain(); if (win) win.webContents.send('orb-clicked'); });
let orbSaveTimer = 0;
ipcMain.on('orb:moveBy', (_e, dx, dy) => {
  if (!orbWin || !Number.isFinite(dx) || !Number.isFinite(dy)) return;
  const [x, y] = orbWin.getPosition(); orbWin.setPosition(Math.round(x + dx), Math.round(y + dy));
  // saved once the dragging settles, not on every mouse move
  clearTimeout(orbSaveTimer);
  orbSaveTimer = setTimeout(() => { if (!orbWin) return; const [nx, ny] = orbWin.getPosition(); try { writeAtomic('orb-pos.json', JSON.stringify({ x: nx, y: ny })); } catch {} }, 400);
});
// the floating orb mirrors what Atlas is doing (listening, thinking)
ipcMain.on('orb:state', (_e, st) => { if (orbWin) orbWin.webContents.send('orb-state', st); });
ipcMain.handle('app:autostart', (e, on) => {
  if (typeof on === 'boolean' && fromMain(e)) app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] });
  return app.getLoginItemSettings().openAtLogin;
});
ipcMain.handle('app:info', () => ({ version: app.getVersion(), platform: process.platform, data: DATA() }));
// >>> self-awareness: the changelog, what's around on this PC, and updating Atlas (selfhost.js)
const selfhost = require('./selfhost');
ipcMain.handle('self:changelog', (e) => (fromMain(e) ? selfhost.changelog() : ''));
ipcMain.handle('self:facts', (e) => (fromMain(e) ? selfhost.facts(DATA()) : {}));
ipcMain.handle('self:download', (e, url, sha256, version) => (fromMain(e) ? selfhost.download(DATA(), url, sha256, version) : { error: 'not allowed' }));
ipcMain.handle('self:install', (e, version) => (fromMain(e) ? selfhost.install({ dialog, app }, DATA(), version) : { error: 'not allowed' }));
// <<< self-awareness
// ---- this PC's hardware, measured (device.js): for picking local models that fit and pacing bots ----
ipcMain.handle('device:profile', async (e) => { if (!fromMain(e)) return null; try { return await require('./device').profile(DATA()); } catch { return null; } });
// ---- end hardware ----
ipcMain.handle('open:external', (e, url) => { if (fromMain(e)) openSafe(url); });

// ------------------------------------------------------------------ files the user picks or drops (attachments)

ipcMain.handle('file:pick', async (e) => {
  if (!fromMain(e)) return [];
  // any kind of file (attach.js and renderer/attachrules.js decide how Atlas reads each)
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'], filters: [{ name: 'All files', extensions: ['*'] }] });
  return r.canceled ? [] : r.filePaths;
});
// >>> attachments: any file, any size (attach.js)
const attach = require('./attach');
const MIME = { mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime', m4v: 'video/mp4', mkv: 'video/x-matroska', avi: 'video/x-msvideo', '3gp': 'video/3gpp',
  mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/aac', aac: 'audio/aac', ogg: 'audio/ogg', oga: 'audio/ogg', opus: 'audio/ogg', flac: 'audio/flac', amr: 'audio/amr', weba: 'audio/webm',
  pdf: 'application/pdf', zip: 'application/zip', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' };
let rulesP = null;
// the renderer's ES module, loaded here as a data: module (this package is CommonJS, so a .js import would fail)
const rules = () => rulesP || (rulesP = import('data:text/javascript;charset=utf-8,' + encodeURIComponent(fs.readFileSync(path.join(__dirname, 'renderer', 'attachrules.js'), 'utf8'))));
ipcMain.handle('attach:take', async (e, p, id) => {
  if (!fromMain(e)) throw new Error('not allowed');
  p = String(p); const R = await rules(); const name = path.basename(p); const ext = R.ext(name);
  const k = R.kind(name, MIME[ext] || '');
  if (k === 'image') { try { return { ...pc.loadAttachment(p), size: fs.statSync(p).size }; } catch { /* a picture this PC can't open is still attached, as a file */ } }
  const dest = await attach.copyIn(String(id), p, name, (done, total) => { try { e.sender.send('attach:progress', String(id), done, total); } catch {} });
  const size = fs.statSync(dest).size;
  const kind = k === 'image' ? 'binary' : k;
  const out = { kind, mime: MIME[ext] || (R.readsAsText(kind) ? 'text/plain' : 'application/octet-stream'), name, path: dest, size, chars: 0 };
  if (kind === 'docx' || kind === 'xlsx' || kind === 'pptx') { try { await attach.officeText(dest, kind); } catch { out.kind = 'binary'; } }
  if (R.readsAsText(out.kind)) {
    const src = fs.existsSync(attach.sidecar(dest)) ? attach.sidecar(dest) : dest;
    out.chars = attach.chars(src);
    if (out.chars <= R.TEXT_INLINE_CHARS) out.text = fs.readFileSync(src, 'utf8');
  }
  if (out.kind === 'zip') { try { out.listing = attach.listing(dest); } catch { out.kind = 'binary'; } }
  if (out.kind === 'binary' || out.kind === 'audio' || out.kind === 'pdf') out.head = attach.describeHead(dest);
  try { require('./botfiles').keepUpload(dest, name); } catch {}
  return out;
});
ipcMain.handle('attach:cancel', (e, id) => { if (fromMain(e)) attach.cancel(String(id)); });
ipcMain.handle('attach:bytes', (e, p) => { if (!fromMain(e)) throw new Error('not allowed'); return attach.bytes(String(p)); });
ipcMain.handle('attach:upload', (e, key, p, mime, name, id) => {
  if (!fromMain(e) || !attach.readable(String(p))) throw new Error('not allowed');
  return attach.geminiUpload(String(key), String(p), String(mime), String(name), (done, total) => { try { e.sender.send('attach:progress', String(id), done, total); } catch {} });
});
ipcMain.handle('attach:read', (e, p, offset, length, member) => (fromMain(e) ? attach.read(String(p), Number(offset) || 0, Number(length) || 20000, String(member || '')) : 'Error: not allowed'));
ipcMain.handle('attach:search', (e, p, query, max) => (fromMain(e) ? attach.search(String(p), String(query || ''), Number(max) || 30) : 'Error: not allowed'));
// <<< attachments
ipcMain.handle('file:load', (e, p) => { if (!fromMain(e)) throw new Error('not allowed'); return pc.loadAttachment(String(p)); });

// ------------------------------------------------------------------ controlling the PC

ipcMain.handle('pc:run', async (e, name, args) => {
  if (!fromMain(e)) return { error: 'not allowed' };
  args = args || {};
  // opening a program, script or installer runs it: the user says yes first (a web page Atlas read can't make it run things)
  const target = name === 'open_path' ? args.path : name === 'open_app' && /[\\/]|\.\w{2,5}$/.test(String(args.name || '')) ? args.name : null;
  if (target && pc.runsSomething(target)) {
    const r = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Open It', 'Cancel'], defaultId: 1, cancelId: 1, title: 'Atlas',
      message: 'Open this program?', detail: `Atlas wants to open:\n${String(target).slice(0, 300)}\n\nThis runs it. Only say yes if you asked for it.` });
    if (r.response !== 0) return { error: 'The user said no to opening that program.' };
  }
  try { return await pc.run(name, args, { desktopCapturer, screen, shell }); }
  catch (err) { return { error: err.message || String(err) }; }
});

// ------------------------------------------------------------------ Atlas Bot's own browser (see botbrowser.js)
// >>> bot browser
const botBrowser = require('./botbrowser');
const okJob = (id) => typeof id === 'string' && /^[\w-]{1,64}$/.test(id);
ipcMain.handle('bot:browser', async (e, jobId, title, name, args) => {
  if (!fromMain(e) || !okJob(jobId) || !/^browser_\w+$/.test(String(name))) return { out: 'Error: not allowed' };
  try { return await botBrowser.run(jobId, String(title || ''), name, args || {}); } catch (err) { return { out: 'Error: ' + (err.message || err) }; }
});
ipcMain.handle('bot:browser-check', async (e, jobId, title, name, args) => {
  if (!fromMain(e) || !okJob(jobId)) return null;
  try { return await botBrowser.check(jobId, String(title || ''), name, args || {}); } catch { return null; }
});
ipcMain.handle('bot:browser-show', (e, jobId, title) => { if (!fromMain(e) || !okJob(jobId)) return false; return botBrowser.show(jobId, String(title || '')); });
ipcMain.handle('bot:browser-close', (e, jobId) => { if (fromMain(e) && okJob(jobId)) botBrowser.close(jobId); return true; });
ipcMain.handle('bot:browser-info', (e, jobId) => (fromMain(e) && okJob(jobId) ? botBrowser.info(jobId) : { open: false }));
// <<< bot browser
// >>> bot files (see botfiles.js): the job folder, documents, spreadsheets, pages, and the Library
const botFiles = require('./botfiles');
ipcMain.handle('bot:files', async (e, jobId, goal, name, args) => {
  if (!fromMain(e) || !okJob(jobId) || !botFiles.NAMES.includes(String(name))) return { out: 'Error: not allowed' };
  try { return await botFiles.run(jobId, String(goal || ''), name, args || {}); } catch (err) { return { out: 'Error: ' + (err.message || err) }; }
});
ipcMain.handle('library:list', (e) => (fromMain(e) ? botFiles.library() : []));
ipcMain.handle('library:open', (e, file) => (fromMain(e) ? botFiles.open(String(file || '')) : false));
ipcMain.handle('library:show', (e, file) => (fromMain(e) ? botFiles.showInFolder(String(file || '')) : false));
ipcMain.handle('library:delete', (e, file) => (fromMain(e) ? botFiles.remove(String(file || '')) : false));
// <<< bot files
// >>> projects (see projects.js): the user's software projects in Documents/Atlas Projects, built by Atlas
const projects = require('./projects');
ipcMain.handle('projects:run', async (e, name, args, approved) => {
  if (!fromMain(e) || !projects.NAMES.includes(String(name))) return { out: 'Error: not allowed' };
  try { return await projects.run(String(name), args || {}, { approved: approved === true, library: botFiles.saveProjectZip }); } catch (err) { return { out: 'Error: ' + (err.message || err) }; }
});
ipcMain.handle('projects:check', async (e, name, args) => { if (!fromMain(e)) return null; try { return await projects.check(String(name), args || {}); } catch { return null; } });
ipcMain.handle('projects:list', (e) => (fromMain(e) ? projects.list() : []));
// <<< projects

// >>> vault (see vault.js): sign-ins for Atlas Bot, encrypted by Windows for this user; passwords stay in this process
const vault = require('./vault');
ipcMain.handle('vault:list', (e) => (fromMain(e) ? vault.list() : []));
ipcMain.handle('vault:model', (e) => (fromMain(e) ? vault.forModelNow() : ''));
ipcMain.handle('vault:save', (e, entry) => { if (!fromMain(e) || !entry || typeof entry !== 'object') return { error: 'not allowed' }; try { return { ok: vault.save(entry) }; } catch (err) { return { error: err.message } } });
ipcMain.handle('vault:remove', (e, id) => (fromMain(e) && typeof id === 'string' ? vault.remove(id) : false));
ipcMain.handle('vault:reveal', (e, id) => (fromMain(e) && typeof id === 'string' ? vault.reveal(id, BrowserWindow.fromWebContents(e.sender)) : null));
// <<< vault

// >>> device link: your phone controlling this PC (remote.js). Only the main window may ask.
const remote = require('./remote');
ipcMain.handle('remote:frame', async (e, maxW, q) => { if (!fromMain(e)) return { error: 'not allowed' }; try { return await remote.frame({ screen, desktopCapturer }, maxW, q); } catch (err) { return { error: err.message }; } });
ipcMain.handle('remote:input', async (e, plan) => { if (!fromMain(e)) return 'not allowed'; return remote.input(plan); });
ipcMain.handle('remote:banner', (e, text) => {
  if (!fromMain(e)) return false;
  remote.showBanner(BrowserWindow, screen, text ? String(text).slice(0, 120) : '', () => { if (win && !win.isDestroyed()) win.webContents.send('remote-stop'); });
  return true;
});
ipcMain.handle('remote:done', (e) => { if (fromMain(e)) remote.stopInput(); return true; });
app.on('will-quit', () => { try { remote.stopInput(); } catch {} });
// <<< device link

// >>> email (see mail.js): read, search, draft and send over IMAP/SMTP with an app password; never synced
let mailbox = null;
function mailer() {
  if (mailbox) return mailbox;
  const can = () => { try { return safeStorage.isEncryptionAvailable(); } catch { return false; } };
  mailbox = require('./mail').createMail({
    load: () => { try { return fs.readFileSync(file('email.json'), 'utf8'); } catch { return null; } },
    save: (text) => writeAtomic('email.json', text),
    encrypt: (t) => (can() ? 'enc:' + safeStorage.encryptString(t).toString('base64') : 'raw:' + Buffer.from(t, 'utf8').toString('base64')),
    decrypt: (v) => (String(v).startsWith('enc:') ? safeStorage.decryptString(Buffer.from(String(v).slice(4), 'base64')) : Buffer.from(String(v).slice(4), 'base64').toString('utf8')),
    attachmentsDir: () => path.join(app.getPath('documents'), 'Atlas Bot', 'Email attachments'),
  });
  return mailbox;
}
ipcMain.handle('mail', async (e, op, args) => (fromMain(e) ? mailer().op(String(op || ''), args && typeof args === 'object' ? args : {}) : { error: 'not allowed' }));
app.on('will-quit', () => { if (mailbox) mailbox.close(); });
// <<< email

// ------------------------------------------------------------------ connections (MCP servers and web APIs; see mcp.js)

let connections = null;
function conns() {
  if (connections) return connections;
  // secrets (tokens, environment values) are encrypted with Windows' own protection for this user; they never sync
  const can = () => { try { return safeStorage.isEncryptionAvailable(); } catch { return false; } };
  connections = require('./mcp').createConnections({
    load: () => { try { return fs.readFileSync(file('connections.json'), 'utf8'); } catch { return null; } },
    save: (text) => writeAtomic('connections.json', text),
    encrypt: (t) => (can() ? 'enc:' + safeStorage.encryptString(t).toString('base64') : 'raw:' + Buffer.from(t, 'utf8').toString('base64')),
    decrypt: (v) => (String(v).startsWith('enc:') ? safeStorage.decryptString(Buffer.from(String(v).slice(4), 'base64')) : Buffer.from(String(v).slice(4), 'base64').toString('utf8')),
    changed: () => { if (win && !win.isDestroyed()) win.webContents.send('connections-changed'); },
  });
  return connections;
}
ipcMain.handle('conn', async (e, op, ...a) => {
  if (!fromMain(e)) return { error: 'not allowed' };
  const c = conns();
  try {
    switch (op) {
      case 'list': return c.list();
      case 'saveServer': {
        const cfg = a[0] || {};
        // a server that is a program on this PC gets run: the user says yes here, outside the page
        if (cfg.kind !== 'http' && !cfg.url) {
          const r = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Add It', 'Cancel'], defaultId: 1, cancelId: 1, title: 'Atlas', message: 'Add this connection?',
            detail: `It runs this program on your PC whenever Atlas connects:\n\n${[cfg.command, ...(cfg.args || [])].join(' ').slice(0, 400)}\n\nOnly add connections from sources you trust.` });
          if (r.response !== 0) return { error: 'Not added.' };
        }
        const name = await c.saveServer(cfg); c.connect(name); return { ok: true, name };
      }
      case 'setServer': c.setServer(String(a[0]), a[1] || {}); return { ok: true };
      case 'removeServer': await c.removeServer(String(a[0])); return { ok: true };
      case 'connect': await c.connect(String(a[0])); return { ok: true };
      case 'disconnect': await c.disconnect(String(a[0])); return { ok: true };
      case 'call': return await c.call(String(a[0]), String(a[1]), a[2]);
      case 'saveApi': return { ok: true, name: await c.saveApi(a[0] || {}) };
      case 'removeApi': c.removeApi(String(a[0])); return { ok: true };
      case 'api': return await c.callApi(String(a[0]), a[1], a[2], a[3]);
    }
    return { error: 'unknown' };
  } catch (err) { return { error: err.message || String(err) }; }
});

// ------------------------------------------------------------------ start

// >>> trading (see trading.js): accounts, limits and the stop switch; keys stay in this process, encrypted by Windows
let trading = null;
const trade = () => (trading = trading || require('./trading'));
ipcMain.handle('trade:run', async (e, name, args, opts) => {
  if (!fromMain(e) || !/^trade_[a-z_]+$/.test(String(name))) return { out: 'Error: not allowed' };
  const o = opts && typeof opts === 'object' ? { ticket: typeof opts.ticket === 'string' ? opts.ticket : undefined, prepare: !!opts.prepare } : {};
  return trade().core.run(String(name), args && typeof args === 'object' ? args : {}, o);
});
ipcMain.handle('trade:list', (e) => (fromMain(e) ? trade().list() : { accounts: [] }));
ipcMain.handle('trade:save', (e, acc, secret) => (fromMain(e) && acc && typeof acc === 'object' ? trade().save(BrowserWindow.fromWebContents(e.sender), acc, secret && typeof secret === 'object' ? secret : null).catch((err) => ({ error: err.message })) : { error: 'not allowed' }));
ipcMain.handle('trade:remove', (e, id) => (fromMain(e) && typeof id === 'string' ? trade().remove(id) : false));
ipcMain.handle('trade:limits', (e, id, limits) => (fromMain(e) && typeof id === 'string' ? trade().setLimits(id, limits || {}) : false));
ipcMain.handle('trade:test', (e, acc, secret) => (fromMain(e) && acc ? trade().test(acc, secret) : { error: 'not allowed' }));
ipcMain.handle('trade:resume', (e) => (fromMain(e) ? trade().resume(BrowserWindow.fromWebContents(e.sender)) : false));
// <<< trading

// >>> attachments: atlasfile://f/?p=<path> plays an attached video from disk (for taking stills), Atlas Bot folder only
const { protocol } = require('electron');
protocol.registerSchemesAsPrivileged([{ scheme: 'atlasfile', privileges: { stream: true, supportFetchAPI: true } }]);
// <<< attachments
app.whenReady().then(() => {
  protocol.handle('atlasfile', (request) => {   // >>> attachments
    const p = new URL(request.url).searchParams.get('p') || '';
    if (!attach.readable(p)) return new Response('not allowed', { status: 403 });
    // byte ranges, so the video can seek (stills are taken all through it) without reading the whole file
    const size = fs.statSync(p).size; const type = MIME[path.extname(p).slice(1).toLowerCase()] || 'application/octet-stream';
    const m = /bytes=(\d*)-(\d*)/.exec(request.headers.get('range') || '');
    const { Readable } = require('stream');
    if (!m) return new Response(Readable.toWeb(fs.createReadStream(p)), { status: 200, headers: { 'Content-Type': type, 'Content-Length': String(size), 'Accept-Ranges': 'bytes' } });
    let start = m[1] === '' ? Math.max(0, size - Number(m[2])) : Number(m[1]); let end = m[1] !== '' && m[2] !== '' ? Number(m[2]) : size - 1;
    end = Math.min(end, size - 1);
    if (start > end || start >= size) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
    return new Response(Readable.toWeb(fs.createReadStream(p, { start, end })), { status: 206, headers: { 'Content-Type': type, 'Content-Length': String(end - start + 1), 'Content-Range': `bytes ${start}-${end}/${size}`, 'Accept-Ranges': 'bytes' } });
  });
  if (!firstInstance) return;
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
    { label: 'Stop All Trading', click: () => { trade().stopAll('from the tray').then((r) => { if (win) win.webContents.send('trading-changed'); }).catch(() => {}); } },   // trading: the stop switch
    { type: 'separator' },
    { label: 'Quit Atlas', click: () => { quitting = true; app.quit(); } }
  ]));
  tray.on('click', showMain);
  // Ctrl+Shift+Space from anywhere: Atlas listens
  try { globalShortcut.register('CommandOrControl+Shift+Space', () => { showMain(); win.webContents.send('orb-clicked'); }); } catch {}
  conns().connectAll();   // connections that were on come back on
  // a screen unplugged or rearranged: bring the orb back where it can be seen
  screen.on('display-removed', keepOrbOnScreen); screen.on('display-metrics-changed', keepOrbOnScreen);
});
app.on('before-quit', () => { quitting = true; });
app.on('will-quit', () => { globalShortcut.unregisterAll(); if (connections) connections.close(); });
app.on('window-all-closed', (e) => { if (!quitting) e.preventDefault && e.preventDefault(); });
