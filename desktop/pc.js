// Atlas using the PC: open apps, links and files; look at the screen; click, type and press keys; run commands.
// Everything here runs only when Atlas's brain asks for it; anything that changes files or runs a command is
// confirmed by the user in the app first (see renderer/brain.js).
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const zlib = require('zlib');

const WIN = process.platform === 'win32';
let lastShot = null;   // size of the last screenshot, to turn its coordinates into real screen pixels

/** Runs a PowerShell script (passed encoded, so quotes in it can't break anything). */
function ps(script, timeoutMs = 60000) {
  return new Promise((resolve) => {
    if (!WIN) return resolve({ code: -1, out: '', err: 'This only works on Windows.' });
    // no progress bars (PowerShell sends them as noise on the error stream when run like this)
    // output as UTF-8 (piped, Windows PowerShell otherwise writes the OEM code page: "Résumé" came back garbled)
    const enc = Buffer.from("$ProgressPreference = 'SilentlyContinue'\ntry { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch {}\n" + script, 'utf16le').toString('base64');
    const p = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', enc], { windowsHide: true });
    let out = '', err = '', done = false, code = null;
    const finish = (r) => { if (done) return; done = true; clearTimeout(t); clearTimeout(late); resolve(r); };
    // a program the script started (a GUI app, "ping -t", a dev server) keeps the output pipes open after PowerShell
    // ends, so 'close' may never come: settle shortly after the exit, and on timeout stop the whole process tree
    let late = null;
    const t = setTimeout(() => {
      err += '\n(stopped after ' + Math.round(timeoutMs / 1000) + 's)';
      try { spawn('taskkill', ['/pid', String(p.pid), '/T', '/F'], { windowsHide: true }).on('error', () => {}); } catch {}
      try { p.kill(); } catch {}
      late = setTimeout(() => finish({ code, out: out.trim(), err: cleanErr(err) }), 3000);
    }, timeoutMs);
    p.stdout.setEncoding('utf8'); p.stderr.setEncoding('utf8');
    p.stdout.on('data', (d) => { if (out.length < 200000) out += d; });
    p.stderr.on('data', (d) => { if (err.length < 50000) err += d; });
    p.on('exit', (c) => { code = c; clearTimeout(late); late = setTimeout(() => finish({ code, out: out.trim(), err: cleanErr(err) }), 1500); });
    p.on('close', (c) => finish({ code: c ?? code, out: out.trim(), err: cleanErr(err) }));
    p.on('error', (e) => finish({ code: -1, out: '', err: e.message }));
  });
}
// a PowerShell single-quoted string (nothing inside it runs); PowerShell also ends these at the curly quotes ‘ ’ ‚ ‛
const q = (s) => "'" + String(s).replace(/['\u2018\u2019\u201A\u201B]/g, "''") + "'";
/** A whole number from what the model sent, or [d] when it isn't one. */
const num = (v, d) => { const n = Math.round(Number(v)); return Number.isFinite(n) ? n : d; };
/** PowerShell's error stream in plain lines: its XML wrapping ("#< CLIXML") becomes just the error messages. */
function cleanErr(e) {
  const t = String(e || '').trim();
  if (!t.startsWith('#< CLIXML')) return t;
  return [...t.matchAll(/<S S="Error">([\s\S]*?)<\/S>/g)].map((m) => m[1].replace(/_x000D__x000A_/g, '\n').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')).join('').trim();
}

const INPUT = `
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class AtlasInput {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int data, UIntPtr extra);
}
"@
[void][AtlasInput]::SetProcessDPIAware()
`;

/** SendKeys spelling: special characters wrapped in braces so they're typed as themselves. */
function sendKeysText(t) { return String(t).replace(/[+^%~(){}\[\]]/g, (c) => '{' + c + '}').replace(/\r?\n/g, '{ENTER}'); }
const KEY = { enter: '{ENTER}', return: '{ENTER}', tab: '{TAB}', esc: '{ESC}', escape: '{ESC}', backspace: '{BACKSPACE}', delete: '{DELETE}', del: '{DELETE}',
  up: '{UP}', down: '{DOWN}', left: '{LEFT}', right: '{RIGHT}', home: '{HOME}', end: '{END}', pageup: '{PGUP}', pagedown: '{PGDN}', space: ' ',
  f1: '{F1}', f2: '{F2}', f3: '{F3}', f4: '{F4}', f5: '{F5}', f6: '{F6}', f7: '{F7}', f8: '{F8}', f9: '{F9}', f10: '{F10}', f11: '{F11}', f12: '{F12}' };
function sendKeysCombo(combo) {
  const parts = String(combo).toLowerCase().split('+').map((s) => s.trim()).filter(Boolean);
  let mods = '', key = '';
  for (const p of parts) {
    if (p === 'ctrl' || p === 'control') mods += '^';
    else if (p === 'alt') mods += '%';
    else if (p === 'shift') mods += '+';
    else if (p === 'win' || p === 'windows' || p === 'meta') throw new Error('The Windows key can\'t be pressed this way; use open_app instead.');
    else key = KEY[p] || (p.length === 1 ? sendKeysText(p) : null);
    if (key === null) throw new Error('Unknown key: ' + p);
  }
  return mods + (key || '');
}

// ------------------------------------------------------------------ reading files (also for attachments)

const TEXT_EXT = new Set(['txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'xml', 'yaml', 'yml', 'html', 'htm', 'log', 'ini', 'toml', 'kt', 'kts', 'java', 'py', 'js', 'ts', 'tsx', 'jsx', 'c', 'h', 'cpp', 'cs', 'go', 'rs', 'rb', 'php', 'swift', 'sh', 'ps1', 'bat', 'sql', 'lua', 'dart', 'css']);
const IMG = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif' };
const VID = { mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime' };
const TEXT_MAX = 120000;

/** The words in a Word (.docx) file: reads its document.xml straight out of the zip. */
function docxText(buf) {
  // find the central directory, then the entry "word/document.xml"
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a Word file');
  const count = buf.readUInt16LE(eocd + 10); let off = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n++) {
    const method = buf.readUInt16LE(off + 10), csize = buf.readUInt32LE(off + 20);
    const nlen = buf.readUInt16LE(off + 28), xlen = buf.readUInt16LE(off + 30), clen = buf.readUInt16LE(off + 32), local = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nlen);
    if (name === 'word/document.xml') {
      const lnl = buf.readUInt16LE(local + 26), lxl = buf.readUInt16LE(local + 28);
      const data = buf.subarray(local + 30 + lnl + lxl, local + 30 + lnl + lxl + csize);
      const xml = (method === 8 ? zlib.inflateRawSync(data) : data).toString('utf8');
      return xml.replace(/<\/w:p>/g, '\n').replace(/<w:tab\/>/g, '\t').replace(/<[^>]+>/g, '')
        .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
        .split('\n').map((l) => l.trimEnd()).join('\n').replace(/\n{3,}/g, '\n\n').trim();
    }
    off += 46 + nlen + xlen + clen;
  }
  return '';
}

/** A file as an attachment: {kind: image|video|pdf|text, mime, name, base64 | text}. Throws past the limits. */
function loadAttachment(p) {
  const { nativeImage } = require('electron');
  const name = path.basename(p), ext = path.extname(p).slice(1).toLowerCase();
  const size = fs.statSync(p).size;
  if (IMG[ext]) {
    // pictures are shrunk to at most 1600 px and sent as JPEG (quick, accepted everywhere)
    let img = nativeImage.createFromPath(p);
    if (img.isEmpty()) throw new Error(`Couldn't open “${name}”.`);
    const { width, height } = img.getSize(); const s = Math.min(1, 1600 / Math.max(width, height));
    if (s < 1) img = img.resize({ width: Math.round(width * s), height: Math.round(height * s), quality: 'best' });
    return { kind: 'image', mime: 'image/jpeg', name, base64: img.toJPEG(85).toString('base64') };
  }
  if (VID[ext]) {
    if (size > 15 * 1024 * 1024) throw new Error(`“${name}” is ${Math.round(size / 1048576)} MB; videos can be up to 15 MB.`);
    return { kind: 'video', mime: VID[ext], name, base64: fs.readFileSync(p).toString('base64') };
  }
  if (size > 10 * 1024 * 1024) throw new Error(`“${name}” is ${Math.round(size / 1048576)} MB; files can be up to 10 MB.`);
  if (ext === 'pdf') return { kind: 'pdf', mime: 'application/pdf', name, base64: fs.readFileSync(p).toString('base64') };
  let text;
  if (ext === 'docx') text = docxText(fs.readFileSync(p));
  else if (TEXT_EXT.has(ext)) text = fs.readFileSync(p, 'utf8');
  else throw new Error(`Atlas can take pictures, videos, PDFs, text files and Word documents. “${name}” is none of those.`);
  if (!text.trim()) throw new Error(`“${name}” has no text I can read.`);
  if (text.length > TEXT_MAX) text = text.slice(0, TEXT_MAX) + '\n…(the rest of the file was cut: it\'s very long)';
  return { kind: 'text', mime: 'text/plain', name, text };
}

// The user's own folders, where Windows really keeps them (OneDrive often moves Desktop and Documents).
const KNOWN = ['Desktop', 'Documents', 'Downloads', 'Pictures', 'Music', 'Videos'];
function knownFolder(k) {
  try { const { app } = require('electron'); if (app && app.getPath) return app.getPath(k.toLowerCase()); } catch {}
  return path.join(os.homedir(), k);
}
/** A path the model gave, as a full path: "~", "Desktop/notes.txt", "~/Documents/x" and bare names all start at the user's
 *  home or their real known folders, never at the app's own working folder (its install folder, or System32 at sign-in). */
function expand(p, folder = knownFolder) {
  let s = String(p || '').trim().replace(/^["']|["']$/g, '');
  s = s.replace(/%([^%]+)%/g, (_m, v) => process.env[v] || _m);
  if (/^~([\\/]|$)/.test(s)) s = s.replace(/^~[\\/]*/, '');
  else if (path.isAbsolute(s)) return path.resolve(s);
  const [first, ...rest] = s.split(/[\\/]+/);
  const k = KNOWN.find((n) => n.toLowerCase() === String(first).toLowerCase());
  if (k) return path.join(folder(k), ...rest);
  return path.resolve(os.homedir(), s);
}

// ------------------------------------------------------------------ the tools

async function run(name, a, e) {
  switch (name) {
    case 'open_app': {
      const n = String(a.name || '').trim();
      if (!n) return { error: 'Say which app.' };
      const r = await ps(`$a = Get-StartApps | Where-Object { $_.Name -like ${q('*' + n + '*')} } | Sort-Object { $_.Name.Length } | Select-Object -First 1
if ($a) { Start-Process ("shell:AppsFolder\\" + $a.AppID); "Opened " + $a.Name }
else { try { Start-Process ${q(n)} -ErrorAction Stop; "Opened " + ${q(n)} } catch { "NOTFOUND" } }`, 20000);
      if (r.code === -1) return { error: r.err };
      if (r.out.includes('NOTFOUND') || !r.out) return { error: `No app called “${n}” was found on this PC.` };
      return { out: r.out, step: r.out };
    }
    case 'list_apps': {
      const r = await ps(`Get-StartApps | Sort-Object Name | ForEach-Object { $_.Name }`, 20000);
      return r.code === -1 ? { error: r.err } : { out: 'Apps on this PC:\n' + r.out.split(/\r?\n/).slice(0, 400).join(', ') };
    }
    case 'open_link': { const u = String(a.url || ''); if (!/^(https?:|mailto:)/i.test(u)) return { error: 'Only web and email links.' }; await e.shell.openExternal(u); return { out: 'Opened ' + u, step: 'Opened ' + u.replace(/^https?:\/\//, '').slice(0, 50) }; }
    case 'open_path': { const p = expand(a.path); if (!fs.existsSync(p)) return { error: 'Nothing at ' + p }; const err = await e.shell.openPath(p); return err ? { error: err } : { out: 'Opened ' + p, step: 'Opened ' + path.basename(p) }; }
    case 'list_files': {
      const p = expand(a.path);
      const items = fs.readdirSync(p, { withFileTypes: true }).slice(0, 300).map((d) => {
        let info = ''; try { if (d.isFile()) { const st = fs.statSync(path.join(p, d.name)); info = ` (${Math.max(1, Math.round(st.size / 1024))} KB, ${st.mtime.toISOString().slice(0, 10)})`; } } catch {}
        return (d.isDirectory() ? '[folder] ' : '') + d.name + info;
      });
      return { out: `In ${p}:\n` + (items.join('\n') || '(empty)') };
    }
    case 'read_file': {
      const p = expand(a.path);
      const att = loadAttachment(p);
      if (att.kind !== 'text') return { error: `“${att.name}” isn't a text file; attach it to a message instead.` };
      return { out: `Contents of ${p}:\n${att.text}`, step: 'Read ' + path.basename(p) };
    }
    case 'write_file': {
      const p = expand(a.path);
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, String(a.text ?? ''), 'utf8');
      return { out: 'Saved ' + p, step: 'Saved ' + path.basename(p) };
    }
    case 'run_command': {
      const r = await ps(String(a.command || ''), Math.min(180, Math.max(5, num(a.timeout_s, 60))) * 1000);
      let out = (r.out || '') + (r.err ? '\n[errors]\n' + r.err : '');
      if (out.length > 8000) out = out.slice(0, 8000) + '\n…(cut)';
      return { out: `Exit code ${r.code}.\n${out || '(no output)'}`, step: 'Ran a command' };
    }
    case 'look_at_screen': {
      const d = e.screen.getPrimaryDisplay();
      const real = { w: Math.round(d.size.width * d.scaleFactor), h: Math.round(d.size.height * d.scaleFactor) };
      const s = Math.min(1, 1600 / real.w);
      const shotW = Math.round(real.w * s), shotH = Math.round(real.h * s);
      const src = await e.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: shotW, height: shotH } });
      if (!src.length) return { error: 'Couldn\'t capture the screen.' };
      // the primary screen (clicks are placed on it); with a second monitor it isn't always listed first
      const img = (src.find((x) => String(x.display_id) === String(d.id)) || src[0]).thumbnail;
      const sz = img.getSize();
      lastShot = { w: sz.width, h: sz.height, realW: real.w, realH: real.h };
      return { out: `Here is the screen (${sz.width}×${sz.height}). Give clicks in these coordinates.`, image: { mime: 'image/jpeg', base64: img.toJPEG(80).toString('base64') }, step: 'Looked at the screen' };
    }
    case 'click': {
      if (!lastShot) return { error: 'Look at the screen first (look_at_screen).' };
      const ax = num(a.x, NaN), ay = num(a.y, NaN);
      if (!Number.isFinite(ax) || !Number.isFinite(ay) || ax < 0 || ay < 0 || ax > lastShot.w || ay > lastShot.h) return { error: `Give x and y inside the screenshot (0-${lastShot.w}, 0-${lastShot.h}).` };
      const x = Math.round(ax * lastShot.realW / lastShot.w), y = Math.round(ay * lastShot.realH / lastShot.h);
      const [down, up] = a.button === 'right' ? [8, 16] : [2, 4];
      const times = a.double ? 2 : 1;
      const r = await ps(INPUT + `[void][AtlasInput]::SetCursorPos(${x}, ${y}); Start-Sleep -Milliseconds 60
for ($i = 0; $i -lt ${times}; $i++) { [AtlasInput]::mouse_event(${down}, 0, 0, 0, [UIntPtr]::Zero); [AtlasInput]::mouse_event(${up}, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 80 }
"clicked"`, 15000);
      return r.code === -1 ? { error: r.err } : { out: `Clicked at ${a.x},${a.y}. Look at the screen again to see what happened.`, step: 'Clicked' };
    }
    case 'type_text': {
      const r = await ps(`Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait(${q(sendKeysText(a.text || ''))}); "typed"`, 30000);
      return r.code === -1 ? { error: r.err } : { out: 'Typed it.', step: 'Typed' };
    }
    case 'press_keys': {
      let k; try { k = sendKeysCombo(a.keys || ''); } catch (err) { return { error: err.message }; }
      const r = await ps(`Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait(${q(k)}); "pressed"`, 15000);
      return r.code === -1 ? { error: r.err } : { out: 'Pressed ' + a.keys + '.', step: 'Pressed ' + a.keys };
    }
    case 'scroll': {
      const amount = (a.direction === 'up' ? 1 : -1) * 120 * Math.min(20, Math.max(1, num(a.amount, 5)));
      const r = await ps(INPUT + `[AtlasInput]::mouse_event(0x0800, 0, 0, ${amount}, [UIntPtr]::Zero); "scrolled"`, 15000);
      return r.code === -1 ? { error: r.err } : { out: 'Scrolled ' + (a.direction || 'down') + '.', step: 'Scrolled' };
    }
    default: return { error: 'Unknown PC action ' + name };
  }
}

/** Files that run something when opened (programs, scripts, installers, shortcuts): Atlas asks before opening these. */
const RUNS = new Set(['exe', 'com', 'bat', 'cmd', 'ps1', 'psm1', 'psd1', 'vbs', 'vbe', 'vb', 'js', 'jse', 'wsf', 'wsh', 'ws', 'msi', 'msp', 'msix', 'msixbundle', 'appx',
  'appxbundle', 'application', 'scr', 'hta', 'lnk', 'reg', 'cpl', 'msc', 'jar', 'pif', 'appref-ms', 'url', 'scf', 'inf', 'chm', 'settingcontent-ms',
  'diagcab', 'gadget', 'xbap', 'iso', 'img', 'vhd', 'vhdx', 'py', 'pyw', 'pyz', 'pyzw', 'sh']);   // Python and Git Bash run their scripts on open
// Windows ignores trailing dots and spaces ("setup.exe." runs setup.exe) and a "::$DATA" stream suffix, so look past them
function runsSomething(p) {
  const s = String(p || '').trim().replace(/["']/g, '').replace(/::\$data$/i, '').replace(/[. ]+$/, '');
  return RUNS.has(path.extname(s).slice(1).toLowerCase());
}

module.exports = { run, loadAttachment, docxText, sendKeysText, sendKeysCombo, expand, q, runsSomething };
