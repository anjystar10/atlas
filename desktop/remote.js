// Device Link on this PC, the parts only the main process can do: capturing the screen for a remote viewer (your
// phone), turning its taps and typing into real mouse and keyboard input, and the always-on-top banner that says this
// PC is being controlled (with Stop).
//
// Input goes through one long-lived PowerShell process (started on the first remote input, ended with the session),
// so a tap from the phone lands in tens of milliseconds instead of starting PowerShell each time.
'use strict';
const { spawn } = require('child_process');
const path = require('path');
const { sendKeysText, sendKeysCombo } = require('./pc');

const WIN = process.platform === 'win32';

// ------------------------------------------------------------------ the screen

/** The primary screen as a JPEG at most [maxW] wide. Coordinates for input are real screen pixels (realW × realH). */
async function frame(e, maxW = 1280, quality = 55) {
  const d = e.screen.getPrimaryDisplay();
  const realW = Math.round(d.size.width * d.scaleFactor), realH = Math.round(d.size.height * d.scaleFactor);
  const s = Math.min(1, Math.max(320, Math.min(1920, Number(maxW) || 1280)) / realW);
  const w = Math.round(realW * s), h = Math.round(realH * s);
  const src = await e.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: w, height: h } });
  if (!src.length) throw new Error("Couldn't capture the screen.");
  const img = src[0].thumbnail, sz = img.getSize();
  const q = Math.max(25, Math.min(85, Number(quality) || 55));
  return { jpg: img.toJPEG(q).toString('base64'), w: sz.width, h: sz.height, realW, realH };
}

// ------------------------------------------------------------------ input

const SERVER = `
$ProgressPreference = 'SilentlyContinue'
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class AtlasRemote {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint f, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool LockWorkStation();
}
"@
Add-Type -AssemblyName System.Windows.Forms
[void][AtlasRemote]::SetProcessDPIAware()
function Key([byte]$vk) { [AtlasRemote]::keybd_event($vk, 0, 0, [UIntPtr]::Zero); [AtlasRemote]::keybd_event($vk, 0, 2, [UIntPtr]::Zero) }
function Click([uint32]$down, [uint32]$up, [int]$times) { for ($i = 0; $i -lt $times; $i++) { [AtlasRemote]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero); [AtlasRemote]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60 } }
[Console]::Out.WriteLine('ready')
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  $p = $line.Split(' ')
  try {
    switch ($p[0]) {
      'move'   { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]) }
      'tap'    { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]); Start-Sleep -Milliseconds 30; Click 2 4 1 }
      'double' { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]); Start-Sleep -Milliseconds 30; Click 2 4 2 }
      'right'  { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]); Start-Sleep -Milliseconds 30; Click 8 16 1 }
      'long'   { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]); [AtlasRemote]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 650; [AtlasRemote]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero) }
      'drag'   {
        $x1 = [int]$p[1]; $y1 = [int]$p[2]; $x2 = [int]$p[3]; $y2 = [int]$p[4]
        [void][AtlasRemote]::SetCursorPos($x1, $y1); [AtlasRemote]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
        for ($i = 1; $i -le 12; $i++) { [void][AtlasRemote]::SetCursorPos([int]($x1 + ($x2 - $x1) * $i / 12), [int]($y1 + ($y2 - $y1) * $i / 12)); Start-Sleep -Milliseconds 12 }
        [AtlasRemote]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
      }
      'scroll' { [void][AtlasRemote]::SetCursorPos([int]$p[1], [int]$p[2]); [AtlasRemote]::mouse_event(0x0800, 0, 0, ([int]$p[3]) * 120, [UIntPtr]::Zero) }
      'keys'   { [System.Windows.Forms.SendKeys]::SendWait([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))) }
      'lock'   { [void][AtlasRemote]::LockWorkStation() }
      'vk'     { Key ([byte][int]$p[1]) }
      'desktop' { [AtlasRemote]::keybd_event(0x5B, 0, 0, [UIntPtr]::Zero); Key 0x44; [AtlasRemote]::keybd_event(0x5B, 0, 2, [UIntPtr]::Zero) }
    }
    [Console]::Out.WriteLine('ok')
  } catch { [Console]::Out.WriteLine('err ' + $_.Exception.Message) }
}
`;

let proc = null, queue = [], ready = false, idleTimer = null;
function server() {
  if (proc) return proc;
  const enc = Buffer.from(SERVER, 'utf16le').toString('base64');
  proc = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', enc], { windowsHide: true });
  ready = false; let buf = '';
  proc.stdout.on('data', (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (line === 'ready') { ready = true; flush(); continue; }
      const w = waiting.shift(); if (w) w(line.startsWith('err') ? line.slice(4) : null);
    }
  });
  proc.on('exit', () => { proc = null; ready = false; for (const w of waiting.splice(0)) w('input stopped'); });
  proc.on('error', () => {});
  return proc;
}
const waiting = [];
function flush() { while (ready && proc && queue.length) proc.stdin.write(queue.shift() + '\n'); }
function cmd(line) {
  return new Promise((resolve) => {
    if (!WIN) return resolve('This only works on Windows.');
    server(); waiting.push(resolve); queue.push(line); flush();
    clearTimeout(idleTimer); idleTimer = setTimeout(stopInput, 120_000);   // ends itself when nobody's controlling
  });
}
function stopInput() { clearTimeout(idleTimer); if (proc) { try { proc.stdin.end(); proc.kill(); } catch {} proc = null; } }

const int = (v) => Math.round(Number(v)) | 0;
const VK = { volume_up: 0xAF, volume_down: 0xAE, mute: 0xAD };
/** One checked input (linkcore.inputPlan) on this PC. Resolves with an error message, or null when done. */
async function input(plan) {
  if (!plan || typeof plan.t !== 'string') return 'bad input';
  switch (plan.t) {
    case 'tap': case 'double': case 'right': case 'long': return cmd(`${plan.t} ${int(plan.x)} ${int(plan.y)}`);
    case 'drag': return cmd(`drag ${int(plan.x)} ${int(plan.y)} ${int(plan.x2)} ${int(plan.y2)}`);
    case 'scroll': return cmd(`scroll ${int(plan.x)} ${int(plan.y)} ${-int(plan.dy)}`);   // dy > 0: content moves up (scroll down)
    case 'text': return cmd('keys ' + Buffer.from(sendKeysText(plan.text), 'utf8').toString('base64'));
    case 'key': { let k; try { k = sendKeysCombo(plan.key); } catch (err) { return err.message; } return cmd('keys ' + Buffer.from(k, 'utf8').toString('base64')); }
    case 'act':
      if (plan.act === 'lock') return cmd('lock');
      if (plan.act === 'show_desktop') return cmd('desktop');
      if (plan.act === 'task_manager') return cmd('keys ' + Buffer.from('^+{ESC}', 'utf8').toString('base64'));
      if (VK[plan.act]) return cmd('vk ' + VK[plan.act]);
      return "That isn't something a PC does.";
    default: return 'bad input';
  }
}

// ------------------------------------------------------------------ the banner

let banner = null;
/** Shows (or hides) the always-on-top strip "Your phone is controlling this PC — Stop". [onStop] runs if Stop is clicked. */
function showBanner(BrowserWindow, screen, text, onStop) {
  if (!text) { if (banner) { const b = banner; banner = null; b.removeAllListeners('closed'); b.destroy(); } return; }
  if (banner) { banner.webContents.executeJavaScript(`document.getElementById('t').textContent = ${JSON.stringify(text)}`).catch(() => {}); return; }
  const wa = screen.getPrimaryDisplay().workArea, W = 470, H = 46;
  banner = new BrowserWindow({ width: W, height: H, x: Math.round(wa.x + (wa.width - W) / 2), y: wa.y + 8, frame: false, resizable: false, movable: true,
    alwaysOnTop: true, skipTaskbar: true, focusable: true, transparent: true, hasShadow: false, show: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true } });
  banner.setAlwaysOnTop(true, 'screen-saver');
  const html = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style>html,body{margin:0;height:100%;background:transparent;font:600 13px Segoe UI,system-ui,sans-serif;-webkit-user-select:none;user-select:none}
.b{height:38px;margin:4px;border-radius:19px;background:rgba(20,14,48,.94);border:1px solid rgba(255,190,90,.75);display:flex;align-items:center;gap:10px;padding:0 6px 0 14px;color:#fff;-webkit-app-region:drag}
.d{width:9px;height:9px;border-radius:50%;background:#ff5a5a;box-shadow:0 0 8px #ff5a5a;animation:p 1.2s infinite}@keyframes p{50%{opacity:.35}}
#t{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}button{-webkit-app-region:no-drag;border:0;border-radius:15px;height:28px;padding:0 16px;background:#F2B866;color:#1a1030;font:700 13px Segoe UI,system-ui,sans-serif;cursor:pointer}</style></head>
<body><div class="b"><span class="d"></span><span id="t"></span><button onclick="window.close()">Stop</button></div><script>document.getElementById('t').textContent=${JSON.stringify(text)}</script></body></html>`;
  banner.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  banner.once('ready-to-show', () => banner && banner.showInactive());
  banner.on('closed', () => { banner = null; try { onStop(); } catch {} });
}

module.exports = { frame, input, stopInput, showBanner };
