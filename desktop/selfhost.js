// Atlas aware of the PC it runs on, and able to update itself (main process; the renderer's selfwire.js asks).
//
// - facts(): CPU, memory and GPU load, the window in front, network, free disk. Cheap to call every turn: the slow
//   parts (the window in front, the GPU) are cached and refreshed in the background.
// - changelog(): CHANGELOG.json, bundled next to the app (extraResources) or read from the repo when developing.
// - download(url, sha256, version) / install(file): the newest installer into Atlas's own folder, checked against
//   the published SHA-256; it runs only after the user clicks Install in a dialog.
// Nothing here is stored or sent anywhere: the renderer passes it to the AI service in the turn that uses it.
'use strict';
const { spawn, execFile } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const WIN = process.platform === 'win32';

// ------------------------------------------------------------------ load

let lastCpu = null, cpuPct = null;
function sampleCpu() {
  const t = os.cpus().reduce((a, c) => { const v = c.times; a.idle += v.idle; a.total += v.user + v.nice + v.sys + v.idle + v.irq; return a; }, { idle: 0, total: 0 });
  if (lastCpu && t.total > lastCpu.total) cpuPct = Math.max(0, Math.min(100, 100 * (1 - (t.idle - lastCpu.idle) / (t.total - lastCpu.total))));
  lastCpu = t;
}
let sampler = null;
function startSampling() { if (sampler) return; sampleCpu(); sampler = setInterval(sampleCpu, 5000); if (sampler.unref) sampler.unref(); }

// slow facts: cached, refreshed in the background when older than this
const FRESH_MS = 15000;
const slow = { gpu: null, gpuName: '', gpuAt: 0, gpuBusy: false, win: null, winAt: 0, winBusy: false };

function refreshGpu() {
  if (slow.gpuBusy || Date.now() - slow.gpuAt < 30000) return;
  slow.gpuBusy = true;
  execFile('nvidia-smi', ['--query-gpu=utilization.gpu,name', '--format=csv,noheader,nounits'], { timeout: 4000, windowsHide: true }, (err, out) => {
    slow.gpuBusy = false; slow.gpuAt = Date.now();
    if (err || !out) { slow.gpu = null; return; }
    const [u, ...name] = String(out).split(/\r?\n/)[0].split(',');
    const n = Number(u); slow.gpu = Number.isFinite(n) ? n : null; slow.gpuName = name.join(',').trim();
  });
}

const FG_SCRIPT = `$ProgressPreference='SilentlyContinue'
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class AtlasFg { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p); }
"@
$h=[AtlasFg]::GetForegroundWindow(); $sb=New-Object System.Text.StringBuilder 512; [void][AtlasFg]::GetWindowText($h,$sb,512)
$p=0; [void][AtlasFg]::GetWindowThreadProcessId($h,[ref]$p); $pr=Get-Process -Id $p -ErrorAction SilentlyContinue
$app = if ($pr) { if ($pr.MainModule -and $pr.MainModule.FileVersionInfo.FileDescription) { $pr.MainModule.FileVersionInfo.FileDescription } else { $pr.ProcessName } } else { '' }
[Console]::Out.Write((@{ app = $app; title = $sb.ToString() } | ConvertTo-Json -Compress))`;

function refreshWindow() {
  if (!WIN || slow.winBusy || Date.now() - slow.winAt < FRESH_MS) return;
  slow.winBusy = true;
  const enc = Buffer.from(FG_SCRIPT, 'utf16le').toString('base64');
  const p = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', enc], { windowsHide: true });
  let out = '';
  const t = setTimeout(() => { try { p.kill(); } catch {} }, 8000);
  p.stdout.on('data', (d) => { if (out.length < 4000) out += d.toString(); });
  const done = () => { clearTimeout(t); slow.winBusy = false; slow.winAt = Date.now(); try { const o = JSON.parse(out); slow.win = { app: String(o.app || '').slice(0, 60), title: String(o.title || '').slice(0, 120) }; } catch {} };
  p.on('close', done); p.on('error', done);
}

function network() {
  let wifi = false, wired = false;
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (!(addrs || []).some((a) => !a.internal && a.family === 'IPv4')) continue;
    if (/wi-?fi|wlan|wireless/i.test(name)) wifi = true;
    else if (!/vethernet|virtual|vmware|vbox|loopback|bluetooth|tailscale|zerotier|wsl|docker/i.test(name)) wired = true;
  }
  return wifi ? 'wifi' : wired ? 'ethernet' : 'none';
}

function diskFreeGb(dir) {
  try { const s = fs.statfsSync(dir); return (s.bavail * s.bsize) / 1073741824; } catch { return null; }
}

/** What's known about this PC right now. [dataDir]: where Atlas keeps its files (for free disk space). */
function facts(dataDir) {
  startSampling(); refreshGpu(); refreshWindow();
  const total = os.totalmem(), free = os.freemem();
  const f = { cpu: cpuPct, ram: total ? 100 * (1 - free / total) : null, ramTotalGb: total / 1073741824, network: network(), storageFreeGb: diskFreeGb(dataDir) };
  if (slow.gpu != null) { f.gpu = slow.gpu; f.gpuName = slow.gpuName; }
  if (slow.win && slow.win.app) { f.foreground = slow.win.app; f.activeWindow = slow.win.title ? `${slow.win.title} (${slow.win.app})` : slow.win.app; }
  for (const k of Object.keys(f)) if (f[k] == null || f[k] === '') delete f[k];
  return f;
}

// ------------------------------------------------------------------ the changelog

function changelog() {
  // installed: next to the app (electron-builder extraResources); developing: the repo's own copy
  const places = [process.resourcesPath && path.join(process.resourcesPath, 'CHANGELOG.json'), path.join(__dirname, '..', 'CHANGELOG.json')].filter(Boolean);
  for (const p of places) { try { return fs.readFileSync(p, 'utf8'); } catch {} }
  return '';
}

// ------------------------------------------------------------------ updating

const okVersion = (v) => typeof v === 'string' && /^\d+\.\d+(\.\d+)?$/.test(v);
let downloading = false;

/** Downloads the installer into [dir]/updates; returns { ok, file } or { error }. */
async function download(dir, url, sha256, version) {
  if (!/^https:\/\/github\.com\/anjystar10\/atlas\/releases\/download\//.test(String(url))) return { error: 'That download address is not Atlas’s own release.' };
  if (!okVersion(version)) return { error: 'Bad version.' };
  if (downloading) return { error: 'The update is already downloading.' };
  downloading = true;
  try {
    const up = path.join(dir, 'updates'); fs.mkdirSync(up, { recursive: true });
    for (const n of fs.readdirSync(up)) if (n !== `Atlas-Setup-${version}.exe`) { try { fs.unlinkSync(path.join(up, n)); } catch {} }
    const file = path.join(up, `Atlas-Setup-${version}.exe`);
    const r = await fetch(url, { redirect: 'follow' });
    if (!r.ok) return { error: `The download failed (the server said ${r.status}).` };
    const buf = Buffer.from(await r.arrayBuffer());
    const got = crypto.createHash('sha256').update(buf).digest('hex');
    if (sha256 && got !== String(sha256).toLowerCase()) return { error: 'The download did not match what was published, so it was thrown away. Try again in a bit.' };
    fs.writeFileSync(file, buf);
    return { ok: true, file, sha256: got, size: buf.length };
  } catch (e) { return { error: 'The download failed: ' + (e.message || e) }; }
  finally { downloading = false; }
}

/** Asks the user; on Install, starts the installer and closes Atlas so it can replace it. */
async function install(electron, dir, version) {
  const { dialog, app } = electron;
  if (!okVersion(version)) return { error: 'Bad version.' };
  const file = path.join(dir, 'updates', `Atlas-Setup-${version}.exe`);
  if (!fs.existsSync(file)) return { error: 'The update is not downloaded yet.' };
  const r = await dialog.showMessageBox({ type: 'question', buttons: ['Install', 'Not now'], defaultId: 0, cancelId: 1, title: 'Update Atlas',
    message: `Install Atlas ${version} now?`, detail: 'Atlas closes while the installer runs, then opens again on the new version.' });
  if (r.response !== 0) return { ok: false, said: 'not now' };
  const p = spawn(file, [], { detached: true, stdio: 'ignore', windowsHide: false });
  p.unref();
  setTimeout(() => app.quit(), 800);
  return { ok: true };
}

module.exports = { facts, changelog, download, install, network };
