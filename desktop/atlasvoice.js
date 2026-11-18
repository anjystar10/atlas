// Atlas Voice: Atlas's own designed voice, run on this laptop's NVIDIA card with Qwen3-TTS (Apache-2.0).
// Optional and one click: Atlas finds Python and the card, makes its own Python environment in its data folder,
// installs Qwen3-TTS with CUDA, downloads the model that fits the card, and keeps a small voice server running on
// 127.0.0.1 (voice-server/server.py) that speaks every reply by cloning the reference clip of Atlas's designed voice
// (voice-server/atlas-voice.wav). When it isn't installed or can't run, Atlas uses its other voices as before.
'use strict';
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');

const MODELS = { large: 'Qwen/Qwen3-TTS-12Hz-1.7B-Base', small: 'Qwen/Qwen3-TTS-12Hz-0.6B-Base' };
const TORCH_INDEX = 'https://download.pytorch.org/whl/cu124';

// ------------------------------------------------------------------ pure logic (tested in test/atlasvoice.test.mjs)

/** nvidia-smi --query-gpu=name,memory.total --format=csv,noheader,nounits  →  [{ name, vramMb }] */
function parseGpus(out) {
  return String(out || '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean).map((l) => {
    const i = l.lastIndexOf(','); const name = l.slice(0, i).trim(); const vramMb = Number(l.slice(i + 1).trim());
    return { name, vramMb };
  }).filter((g) => g.name && Number.isFinite(g.vramMb) && g.vramMb > 0);
}

/** The model for a card: 1.7B with 6 GB or more, 0.6B with 3 GB or more, else none (too small to be worth it). */
function pickModel(vramMb) {
  if (vramMb >= 6 * 1024) return { id: MODELS.large, size: '1.7B', why: `${(vramMb / 1024).toFixed(1)} GB of graphics memory: the larger model fits` };
  if (vramMb >= 3 * 1024) return { id: MODELS.small, size: '0.6B', why: `${(vramMb / 1024).toFixed(1)} GB of graphics memory: the smaller model fits` };
  return null;
}

/** "Python 3.12.4"  →  [3, 12]; Qwen3-TTS wants 3.10–3.12 (3.12 recommended). */
function pythonVersion(out) { const m = /Python (\d+)\.(\d+)/.exec(String(out || '')); return m ? [Number(m[1]), Number(m[2])] : null; }
const pythonOk = (v) => !!v && v[0] === 3 && v[1] >= 10 && v[1] <= 12;

/** The install, as the commands it runs (so the UI can show progress and the tests can check it). */
function installPlan(python, venvDir, model) {
  const py = process.platform === 'win32' ? path.join(venvDir, 'Scripts', 'python.exe') : path.join(venvDir, 'bin', 'python');
  return [
    { label: 'Making Atlas Voice its own Python environment', cmd: python.cmd, args: [...python.args, '-m', 'venv', venvDir] },
    { label: 'Updating pip', cmd: py, args: ['-m', 'pip', 'install', '-q', '-U', 'pip'] },
    { label: 'Installing PyTorch for your graphics card (about 2.5 GB)', cmd: py, args: ['-m', 'pip', 'install', '-q', 'torch', 'torchaudio', '--index-url', TORCH_INDEX] },
    { label: 'Installing Qwen3-TTS', cmd: py, args: ['-m', 'pip', 'install', '-q', '-U', 'qwen-tts', 'soundfile', 'huggingface_hub'] },
    { label: `Downloading the voice model (${model.size})`, cmd: py, args: ['-c', `from huggingface_hub import snapshot_download as d; d(${JSON.stringify(model.id)}); d("Qwen/Qwen3-TTS-Tokenizer-12Hz")`] },
  ];
}

/** A WAV file's 16-bit mono PCM and rate (what the renderer's finish and player take). */
function wavPcm(buf) {
  if (buf.length < 44 || buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') throw new Error('not a WAV');
  let off = 12, rate = 0, bits = 0, ch = 0;
  while (off + 8 <= buf.length) {
    const id = buf.toString('ascii', off, off + 4), size = buf.readUInt32LE(off + 4);
    if (id === 'fmt ') { ch = buf.readUInt16LE(off + 10); rate = buf.readUInt32LE(off + 12); bits = buf.readUInt16LE(off + 22); }
    if (id === 'data') {
      if (bits !== 16 || ch !== 1) throw new Error(`expected 16-bit mono, got ${bits}-bit ${ch}ch`);
      return { rate, pcm: buf.subarray(off + 8, off + 8 + size) };
    }
    off += 8 + size + (size & 1);
  }
  throw new Error('WAV has no data');
}

// ------------------------------------------------------------------ the running side (main process)

function create({ dataDir, appDir }) {
  const home = path.join(dataDir, 'atlas-voice');
  const venv = path.join(home, 'venv');
  const py = process.platform === 'win32' ? path.join(venv, 'Scripts', 'python.exe') : path.join(venv, 'bin', 'python');
  const serverDir = path.join(appDir, 'voice-server').replace('app.asar', 'app.asar.unpacked');
  const cfgFile = path.join(home, 'config.json');
  const st = { phase: 'unknown', step: '', error: null, gpu: null, model: null, port: 0, ready: false, installing: false };
  let proc = null; const token = crypto.randomBytes(24).toString('hex');

  const readCfg = () => { try { return JSON.parse(fs.readFileSync(cfgFile, 'utf8')); } catch { return {}; } };
  const writeCfg = (c) => { fs.mkdirSync(home, { recursive: true }); fs.writeFileSync(cfgFile, JSON.stringify(c, null, 1)); };
  const run = (cmd, args, ms = 120_000) => new Promise((res) => execFile(cmd, args, { timeout: ms, windowsHide: true }, (err, out, errOut) => res({ ok: !err, out: String(out || '') + String(errOut || ''), err })));

  async function detect() {
    const smi = await run('nvidia-smi', ['--query-gpu=name,memory.total', '--format=csv,noheader,nounits'], 15_000);
    const gpus = smi.ok ? parseGpus(smi.out) : [];
    const gpu = gpus.sort((a, b) => b.vramMb - a.vramMb)[0] || null;
    let python = null;
    for (const c of process.platform === 'win32' ? [['py', ['-3.12']], ['py', ['-3.11']], ['py', ['-3.10']], ['python', []]] : [['python3.12', []], ['python3', []]]) {
      const r = await run(c[0], [...c[1], '--version'], 15_000); const v = pythonVersion(r.out);
      if (r.ok && pythonOk(v)) { python = { cmd: c[0], args: c[1], version: v.join('.') }; break; }
    }
    st.gpu = gpu; st.model = gpu ? pickModel(gpu.vramMb) : null;
    const cfg = readCfg();
    st.phase = cfg.installed && fs.existsSync(py) ? 'installed' : !gpu ? 'no-gpu' : !st.model ? 'gpu-too-small' : !python ? 'no-python' : 'can-install';
    return { ...status(), python };
  }

  async function install(onStep) {
    if (st.installing) return status();
    const d = await detect();
    if (!d.python) throw new Error('Atlas Voice needs Python 3.10–3.12 on this laptop (python.org, 3.12 recommended).');
    if (!st.model) throw new Error(st.gpu ? `${st.gpu.name} has too little graphics memory for Atlas Voice (3 GB or more needed).` : 'Atlas Voice needs an NVIDIA graphics card.');
    st.installing = true; st.error = null; fs.mkdirSync(home, { recursive: true });
    try {
      for (const s of installPlan(d.python, venv, st.model)) {
        st.step = s.label; onStep && onStep(status());
        const r = await run(s.cmd, s.args, 60 * 60_000);
        if (!r.ok) throw new Error(`${s.label} failed: ${r.out.trim().split('\n').slice(-3).join(' ').slice(0, 300)}`);
      }
      writeCfg({ installed: true, model: st.model.id, at: Date.now() });
      st.phase = 'installed'; st.step = '';
      await start();
    } catch (e) { st.error = e.message; st.phase = 'failed'; throw e; } finally { st.installing = false; onStep && onStep(status()); }
    return status();
  }

  async function health() {
    if (!st.port) return null;
    try { const r = await fetch(`http://127.0.0.1:${st.port}/health`, { headers: { 'X-Atlas-Token': token } }); return r.ok ? await r.json() : null; } catch { return null; }
  }

  async function start() {
    const cfg = readCfg(); if (!cfg.installed || !fs.existsSync(py)) return status();
    if (proc) return status();
    st.port = 47600 + Math.floor(Math.random() * 300); st.ready = false; st.error = null;
    proc = spawn(py, [path.join(serverDir, 'server.py'), '--model', cfg.model, '--ref', path.join(serverDir, 'atlas-voice.wav'),
      '--ref-text', path.join(serverDir, 'atlas-voice.txt'), '--port', String(st.port), '--token', token], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    proc.stdout.on('data', (b) => { const s = String(b); if (/^ready/m.test(s)) st.ready = true; const m = /load failed: (.*)/.exec(s); if (m) st.error = m[1]; });
    proc.stderr.on('data', () => {});
    proc.on('exit', () => { proc = null; st.ready = false; st.port = 0; });
    return status();
  }

  function stop() { if (proc) { try { proc.kill(); } catch {} proc = null; } st.ready = false; st.port = 0; }

  /** One piece of speech in Atlas's voice: { pcm: Uint8Array, rate }. Throws when it isn't ready (the caller falls back). */
  async function speak(text, language) {
    if (!st.ready || !st.port) throw new Error('Atlas Voice is not running');
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 30_000);
    try {
      const r = await fetch(`http://127.0.0.1:${st.port}/speak`, { method: 'POST', signal: ctl.signal,
        headers: { 'Content-Type': 'application/json', 'X-Atlas-Token': token }, body: JSON.stringify({ text: String(text).slice(0, 2000), language: language || 'English' }) });
      const buf = Buffer.from(await r.arrayBuffer());
      if (!r.ok) throw new Error(`Atlas Voice: ${buf.toString('utf8').slice(0, 200)}`);
      const { pcm, rate } = wavPcm(buf);
      return { pcm: new Uint8Array(pcm), rate };
    } finally { clearTimeout(t); }
  }

  function status() {
    return { phase: st.phase, step: st.step, error: st.error, installing: st.installing, ready: st.ready,
      gpu: st.gpu, model: st.model && { id: st.model.id, size: st.model.size, why: st.model.why }, installedModel: readCfg().model || null };
  }

  return { detect, install, start, stop, speak, status, health };
}

module.exports = { create, parseGpus, pickModel, pythonVersion, pythonOk, installPlan, wavPcm, MODELS };
