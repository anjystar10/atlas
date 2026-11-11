// What this PC has, measured (never assumed from its name): processor, memory, graphics cards with their own memory,
// free disk, and which local-model servers are running with which models. The renderer turns this into decisions
// (renderer/fit.js). Everything stays on this PC.
'use strict';
const os = require('os');
const fs = require('fs');
const { execFile } = require('child_process');

const MB = 1024 * 1024;

/** Runs a program directly (no shell, so nothing in its arguments is interpreted) and gives back what it printed. */
function run(file, args, timeout = 5000) {
  return new Promise((resolve) => {
    try {
      execFile(file, args, { timeout, windowsHide: true, maxBuffer: 2 * MB }, (err, out) => resolve(err ? null : String(out || '')));
    } catch { resolve(null); }
  });
}

/** NVIDIA cards, from the driver's own tool: exact total and free memory. Null when there's no NVIDIA driver. */
async function nvidia() {
  const out = await run('nvidia-smi', ['--query-gpu=name,memory.total,memory.free,driver_version', '--format=csv,noheader,nounits']);
  if (!out) return null;
  const gpus = [];
  for (const line of out.split(/\r?\n/)) {
    const [name, total, free, driver] = line.split(',').map((s) => s.trim());
    if (!name || !Number.isFinite(+total)) continue;
    gpus.push({ name, vramTotalMb: Math.round(+total), vramFreeMb: Number.isFinite(+free) ? Math.round(+free) : -1, driver: driver || '', source: 'nvidia-smi' });
  }
  return gpus.length ? gpus : null;
}

/**
 * Every graphics adapter Windows knows, with its memory from the driver's registry entry (a 64-bit number).
 * Win32_VideoController's AdapterRAM is avoided: it is 32-bit and reads at most 4 GB, which would be wrong for most cards.
 */
async function windowsAdapters() {
  const ps = [
    "$ErrorActionPreference='SilentlyContinue'",
    "$k='HKLM:\\SYSTEM\\ControlSet001\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}'",
    "Get-ChildItem $k | ForEach-Object { $p = Get-ItemProperty $_.PSPath; if ($p.DriverDesc) { [pscustomobject]@{ name = $p.DriverDesc; bytes = $p.'HardwareInformation.qwMemorySize' } } } | ConvertTo-Json -Compress",
  ].join('; ');
  const out = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], 8000);
  if (!out || !out.trim()) return [];
  try {
    let list = JSON.parse(out); if (!Array.isArray(list)) list = [list];
    const seen = new Set();
    return list.filter((g) => g && g.name && !/basic display|remote|virtual|parsec|meta/i.test(g.name) && !seen.has(g.name) && seen.add(g.name))
      .map((g) => ({ name: g.name, vramTotalMb: Number(g.bytes) > 0 ? Math.round(Number(g.bytes) / MB) : -1, vramFreeMb: -1, driver: '', source: 'registry' }));
  } catch { return []; }
}

async function physicalCores() {
  if (process.platform !== 'win32') return -1;
  const out = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', '(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum'], 8000);
  const n = parseInt(out || '', 10);
  return Number.isFinite(n) && n > 0 ? n : -1;
}

function diskFreeMb(dir) {
  try { const s = fs.statfsSync(dir); return Math.round((s.bavail * s.bsize) / MB); } catch { return -1; }
}

async function getJson(url, timeout = 2500) {
  const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), timeout);
  try { const r = await fetch(url, { signal: ctrl.signal }); return r.ok ? await r.json() : null; } catch { return null; } finally { clearTimeout(t); }
}

/** Local model servers on this PC and their models. Ollama tells each model's size; LM Studio may not. */
async function runtimes() {
  const found = [];
  const tags = await getJson('http://localhost:11434/api/tags');
  if (tags) {
    const loaded = await getJson('http://localhost:11434/api/ps');
    const inMem = new Map(((loaded && loaded.models) || []).map((m) => [m.name, m]));
    found.push({ name: 'Ollama', base: 'http://localhost:11434/v1', models: (tags.models || []).map((m) => ({
      id: m.name, sizeMb: m.size > 0 ? Math.round(m.size / MB) : -1,
      params: (m.details && m.details.parameter_size) || '', quant: (m.details && m.details.quantization_level) || '',
      loaded: inMem.has(m.name), vramMb: inMem.has(m.name) && inMem.get(m.name).size_vram > 0 ? Math.round(inMem.get(m.name).size_vram / MB) : 0,
    })) });
  }
  const lms = await getJson('http://localhost:1234/v1/models');
  if (lms) found.push({ name: 'LM Studio', base: 'http://localhost:1234/v1', models: (lms.data || []).map((m) => ({ id: m.id, sizeMb: -1, params: '', quant: '', loaded: false, vramMb: 0 })) });
  return found;
}

let slow = null, slowAt = 0;
/**
 * The PC right now. The parts that don't change (processor, cards and their size) are read once every 10 minutes;
 * free memory, free graphics memory and the model servers are read fresh on every call.
 */
async function profile(dataDir) {
  const now = Date.now();
  if (!slow || now - slowAt > 10 * 60000) {
    const [cores, adapters] = await Promise.all([physicalCores(), process.platform === 'win32' ? windowsAdapters() : Promise.resolve([])]);
    slow = { cores, adapters }; slowAt = now;
  }
  const cpus = os.cpus() || [];
  const nv = await nvidia();
  // NVIDIA's own numbers win for its cards; other adapters (built-in graphics) come from the registry
  const gpus = nv ? [...nv, ...slow.adapters.filter((a) => !nv.some((n) => a.name.includes(n.name) || n.name.includes(a.name)))] : slow.adapters;
  return {
    platform: process.platform, arch: process.arch,
    cpu: (cpus[0] && cpus[0].model || '').replace(/\s+/g, ' ').trim(),
    logicalCores: cpus.length, physicalCores: slow.cores,
    totalRamMb: Math.round(os.totalmem() / MB), freeRamMb: Math.round(os.freemem() / MB),
    gpus, nvidiaDriver: !!nv,
    diskFreeMb: diskFreeMb(dataDir || os.homedir()),
    runtimes: await runtimes(),
    at: now,
  };
}

module.exports = { profile };
