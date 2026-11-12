// The user's software projects on this PC: Documents/Atlas Projects/<name>. Atlas builds them here (from the chat,
// from a bot, or for the phone through the Device Link): writes the files, runs the build and the tests, reads the
// errors, fixes them, and hands over a zip in the Library.
// Runs in the main process. Every path a bot gives stays inside its project (projectrules.relPath); every command runs
// in the project folder and is checked first (projectrules.checkCommand): building, testing and package installs
// inside the project go ahead, anything reaching outside it comes back as "needs the user's OK" unless already approved.
'use strict';
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn, execFile } = require('child_process');
const rules = require('./projectrules');
const el = (() => { try { const e = require('electron'); return typeof e === 'object' ? e : {}; } catch { return {}; } })();

const MANIFEST = '.atlas-project.json';
const SKIP = new Set(['node_modules', '.git', '.venv', 'venv', 'env', '__pycache__', '.pytest_cache', '.mypy_cache', 'target', 'bin', 'obj', '.gradle', '.dart_tool', '.next', '.idea', '.vs']);
const MAX_READ = 24000, MAX_WRITE = 2 * 1024 * 1024, MAX_TREE = 400, MAX_ZIP = 200 * 1024 * 1024;
const DEFAULT_TIMEOUT = 180, MAX_TIMEOUT = 1800;
const isWin = process.platform === 'win32';

let baseOverride = null;
/** Documents/Atlas Projects (tests point it elsewhere). */
const base = () => baseOverride || path.join(el.app ? el.app.getPath('documents') : os.homedir(), 'Atlas Projects');
function configure({ dir } = {}) { if (dir) baseOverride = dir; }

// ---------------------------------------------------------------- projects and their manifests

function dirOf(name) {
  const n = rules.projectName(name);
  if (!n) return null;
  return path.join(base(), n);
}
function readManifest(dir) { try { return JSON.parse(fs.readFileSync(path.join(dir, MANIFEST), 'utf8')); } catch { return null; } }
function writeManifest(dir, m) {
  const f = path.join(dir, MANIFEST), tmp = f + '.tmp';
  m.history = (m.history || []).slice(-80);
  fs.writeFileSync(tmp, JSON.stringify(m, null, 2), 'utf8'); fs.renameSync(tmp, f);
}
function note(dir, what, extra = {}) {
  const m = readManifest(dir); if (!m) return;
  Object.assign(m, extra, { updatedAt: Date.now() });
  m.history = [...(m.history || []), { at: Date.now(), what: String(what).slice(0, 300) }];
  writeManifest(dir, m);
}
/** The project folder for [name], which must exist (made with project_new). */
function existing(name) {
  const dir = dirOf(name);
  if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return null;
  return dir;
}
/** A path inside the project, or null. Atlas's own manifest is kept out of the bot's reach. */
function inside(dir, p) {
  const parts = rules.relPath(p);
  if (!parts || parts[0] === MANIFEST) return null;
  const f = path.join(dir, ...parts);
  const r = path.resolve(f), d = path.resolve(dir) + path.sep;
  return r.startsWith(d) ? r : null;
}
const hasVenv = (dir) => ['.venv', 'venv'].some((v) => fs.existsSync(path.join(dir, v)));

function list() {
  let names = [];
  try { names = fs.readdirSync(base(), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); } catch { return []; }
  return names.map((n) => { const m = readManifest(path.join(base(), n)) || {}; return { name: n, goal: m.goal || '', status: m.status || '', stack: m.stack || '', updatedAt: m.updatedAt || 0, dir: path.join(base(), n) }; })
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

function tree(dir, sub = '') {
  const out = []; let more = 0;
  const walk = (d, rel, depth) => {
    let ents = [];
    try { ents = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    ents.sort((a, b) => (b.isDirectory() - a.isDirectory()) || a.name.localeCompare(b.name));
    for (const e of ents) {
      if (e.name === MANIFEST || e.name.endsWith('.tmp')) continue;
      const r = rel ? rel + '/' + e.name : e.name;
      if (out.length >= MAX_TREE) { more++; continue; }
      if (e.isDirectory()) {
        if (SKIP.has(e.name)) { out.push(r + '/ (not listed)'); continue; }
        out.push(r + '/');
        if (depth < 8) walk(path.join(d, e.name), r, depth + 1);
      } else {
        let size = 0; try { size = fs.statSync(path.join(d, e.name)).size; } catch {}
        out.push(`${r}  (${size < 1024 ? size + ' B' : Math.round(size / 1024) + ' KB'})`);
      }
    }
  };
  walk(sub ? path.join(dir, sub) : dir, sub, 0);
  return out.join('\n') + (more ? `\n…and ${more} more` : '') || '(empty)';
}

// ---------------------------------------------------------------- what's installed on this PC (to pick a stack)

let toolCache = null;
function version(cmd) {
  return new Promise((resolve) => {
    const opts = { timeout: 6000, windowsHide: true, shell: isWin, maxBuffer: 256 * 1024 };
    execFile(cmd, ['--version'], opts, (err, out, errOut) => {
      if (err) return resolve(null);
      const line = String(out || errOut || '').split(/\r?\n/).find((l) => /\d/.test(l)) || '';
      resolve(line.trim().slice(0, 60) || 'installed');
    });
  });
}
async function toolchains() {
  if (toolCache && Date.now() - toolCache.at < 10 * 60000) return toolCache.list;
  const names = isWin ? ['node', 'npm', 'python', 'py', 'git', 'code', 'dotnet', 'java', 'cargo', 'go', 'flutter'] : ['node', 'npm', 'python3', 'git', 'code', 'dotnet', 'java', 'cargo', 'go', 'flutter'];
  const vs = await Promise.all(names.map(version));
  const list = names.map((n, i) => ({ name: n, version: vs[i] })).filter((x) => x.version);
  toolCache = { at: Date.now(), list };
  return list;
}
const pythonCmd = async () => { const t = await toolchains(); return t.some((x) => x.name === 'python') ? 'python' : t.some((x) => x.name === 'py') ? 'py' : 'python3'; };

// ---------------------------------------------------------------- running commands in a project

const running = new Map();   // dir → the command running there now (one at a time per project)
let active = 0;              // across all projects; builds wait for a slot so the PC stays usable
const MAX_ACTIVE = 2;
const apps = new Map();      // dir → a long-running app started with project_open how=app

const stripAnsi = (s) => String(s).replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\r(?!\n)/g, '\n');
function killTree(child) {
  if (!child || child.exitCode != null) return;
  try {
    if (isWin) execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true }, () => {});
    else process.kill(-child.pid, 'SIGKILL');
  } catch { try { child.kill('SIGKILL'); } catch {} }
}
function shellSpawn(command, cwd, detached) {
  const env = { ...process.env, CI: '1', FORCE_COLOR: '0', NO_COLOR: '1', PYTHONUNBUFFERED: '1', PYTHONIOENCODING: 'utf-8', NPM_CONFIG_FUND: 'false', NPM_CONFIG_AUDIT: 'false', NPM_CONFIG_UPDATE_NOTIFIER: 'false' };
  const child = isWin
    ? spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `"${command}"`], { cwd, env, windowsHide: true, windowsVerbatimArguments: true, stdio: ['ignore', 'pipe', 'pipe'], detached: !!detached })
    : spawn('/bin/sh', ['-c', command], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  // builds run a notch below normal so the PC stays responsive while Atlas works
  try { os.setPriority(child.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
  return child;
}
async function waitTurn(dir) {
  while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));
}
/** Runs [command] in [dir] and resolves with { code, out, timedOut }. Output is kept to the last part. */
async function exec(dir, command, timeoutS) {
  await waitTurn(dir);
  running.set(dir, command); active++;
  try {
    return await new Promise((resolve) => {
      let out = '', timedOut = false;
      const child = shellSpawn(command, dir, false);
      const add = (b) => { out += b.toString('utf8'); if (out.length > 400000) out = out.slice(-200000); };
      child.stdout.on('data', add); child.stderr.on('data', add);
      const t = setTimeout(() => { timedOut = true; killTree(child); }, Math.min(MAX_TIMEOUT, Math.max(5, timeoutS || DEFAULT_TIMEOUT)) * 1000);
      child.on('error', (e) => { clearTimeout(t); resolve({ code: -1, out: String(e.message || e), timedOut }); });
      child.on('close', (code) => { clearTimeout(t); resolve({ code: code == null ? -1 : code, out: stripAnsi(out), timedOut }); });
    });
  } finally { running.delete(dir); active--; }
}
const result = (command, r) => `$ ${command}\n${rules.tail(r.out).trim() || '(no output)'}\n\n` + (r.timedOut ? 'Stopped: it ran past its time limit (raise timeout_s if it needs longer, or run it in the background with project_open how=app).' : `Finished with exit code ${r.code}${r.code === 0 ? ' (success)' : ' (failed)'}.`);

/** The command that checks this project (its tests, or a build), or null when Atlas can't tell. */
async function checkCommandFor(dir, m) {
  const has = (n) => fs.existsSync(path.join(dir, n));
  let cmd = rules.checkPlan(has, m || {});
  if (cmd === 'npm test') {
    let pkg = {}; try { pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')); } catch {}
    const t = pkg.scripts && pkg.scripts.test;
    if (!t || /no test specified/.test(t)) cmd = pkg.scripts && pkg.scripts.build ? 'npm run build' : null;
  }
  if (cmd) return cmd;
  const isPy = ['pyproject.toml', 'requirements.txt', 'setup.py', 'main.py', 'app.py'].some(has) || fs.readdirSync(dir).some((f) => /\.py$/.test(f));
  if (isPy) {
    const venv = ['.venv', 'venv'].find(has);
    const py = venv ? path.join(venv, isWin ? 'Scripts' : 'bin', 'python') : await pythonCmd();
    const pytest = venv && [path.join(venv, 'Lib', 'site-packages', 'pytest'), ...(() => { try { return fs.readdirSync(path.join(dir, venv, 'lib')).map((p) => path.join(venv, 'lib', p, 'site-packages', 'pytest')); } catch { return []; } })()].some(has);
    return pytest ? `${py} -m pytest -q` : `${py} -m unittest discover -v`;
  }
  if (fs.readdirSync(dir).some((f) => /\.(csproj|sln)$/.test(f))) return 'dotnet test';
  return null;
}

// ---------------------------------------------------------------- the tools

const NAMES = ['project_list', 'project_new', 'project_files', 'project_read', 'project_write', 'project_patch', 'project_run', 'project_check', 'project_open', 'project_zip', 'project_delete'];

/**
 * Would [name] with [args] need the user's OK? A sentence saying what would happen, or null.
 * The same check run() makes, asked ahead (for Atlas Bot's approval step).
 */
async function check(name, args = {}) {
  const dir = existing(args.project);
  if (name === 'project_delete') return `Delete the whole project "${rules.projectName(args.project) || args.project}" from Documents\\Atlas Projects`;
  if (!dir) return null;   // the tool itself will say the project doesn't exist
  if (name === 'project_run' || (name === 'project_check' && args.command) || (name === 'project_open' && args.how === 'app')) {
    const command = String(args.command || (name === 'project_open' ? (readManifest(dir) || {}).run || '' : ''));
    if (!command && name !== 'project_run') return null;
    const r = rules.checkCommand(command, { hasVenv: hasVenv(dir) });
    return r.ask ? `Run this in the project "${path.basename(dir)}" (${r.why}): ${command.slice(0, 160)}` : null;
  }
  return null;
}

/** Runs one project tool. { out, step?, needs?, made? }. Without [approved], something risky comes back as { needs } instead of running. */
async function run(name, args = {}, { approved = false, library = null } = {}) {
  if (!NAMES.includes(name)) return { out: 'Error: there is no such project tool.' };
  if (name === 'project_list') {
    const l = list();
    const tc = await toolchains();
    return { out: (l.length ? 'Projects (newest first):\n' + l.slice(0, 40).map((p) => `- ${p.name}${p.status ? ` [${p.status}]` : ''}${p.goal ? ': ' + p.goal.slice(0, 120) : ''}`).join('\n') : 'No projects yet.')
      + `\n\nInstalled on this PC: ${tc.map((t) => `${t.name} (${t.version})`).join(', ') || 'none of the usual developer tools were found'}.\nProjects live in ${base()}.` };
  }
  if (name === 'project_new') {
    const n = rules.projectName(args.name);
    if (!n) return { out: 'Error: give the project a name.' };
    const dir = path.join(base(), n);
    const existed = fs.existsSync(dir);
    fs.mkdirSync(dir, { recursive: true });
    if (!readManifest(dir)) writeManifest(dir, { name: n, goal: String(args.goal || '').slice(0, 4000), stack: String(args.stack || '').slice(0, 200), run: '', check: '', status: 'started', createdAt: Date.now(), updatedAt: Date.now(), history: [{ at: Date.now(), what: 'Started the project' }] });
    else if (args.goal || args.stack) note(dir, 'Updated the goal', { ...(args.goal ? { goal: String(args.goal).slice(0, 4000) } : {}), ...(args.stack ? { stack: String(args.stack).slice(0, 200) } : {}) });
    const tc = await toolchains();
    return { out: `${existed ? 'Opened the existing' : 'Made the'} project "${n}" at ${dir}.\nInstalled on this PC: ${tc.map((t) => `${t.name} (${t.version})`).join(', ') || 'none of the usual developer tools'}.\nPick a stack that is installed. Write files with project_write, run with project_run, check with project_check.`, step: (existed ? 'Opened the project ' : 'Started the project ') + n };
  }
  const dir = existing(args.project);
  if (!dir) return { out: `Error: there's no project called "${args.project}". Start it with project_new (project_list shows the ones that exist).` };
  const pname = path.basename(dir);
  switch (name) {
    case 'project_files': {
      const sub = args.path ? rules.relPath(args.path) : null;
      if (args.path && !sub) return { out: 'Error: that path is outside the project.' };
      return { out: `Files in ${pname}${sub ? '/' + sub.join('/') : ''}:\n` + tree(dir, sub ? sub.join('/') : '') };
    }
    case 'project_read': {
      const f = inside(dir, args.path); if (!f) return { out: 'Error: that path is outside the project.' };
      if (!fs.existsSync(f) || !fs.statSync(f).isFile()) return { out: `Error: ${args.path} doesn't exist (project_files lists what's there).` };
      const buf = fs.readFileSync(f);
      if (buf.includes(0)) return { out: `${args.path} is a binary file (${buf.length} bytes); it can't be read as text.` };
      const text = buf.toString('utf8'), from = Math.max(0, Number(args.from) || 0);
      const part = text.slice(from, from + MAX_READ);
      return { out: part + (text.length > from + MAX_READ ? `\n\n[${text.length - from - MAX_READ} more characters: read again with from=${from + MAX_READ}]` : ''), step: 'Read ' + args.path };
    }
    case 'project_write': {
      const f = inside(dir, args.path); if (!f) return { out: 'Error: that path is outside the project.' };
      const text = String(args.text ?? '');
      if (Buffer.byteLength(text) > MAX_WRITE) return { out: 'Error: that is too long for one file (2 MB at most).' };
      fs.mkdirSync(path.dirname(f), { recursive: true });
      const existed = fs.existsSync(f);
      fs.writeFileSync(f, text, 'utf8');
      return { out: `${existed ? 'Replaced' : 'Wrote'} ${args.path} (${text.split('\n').length} lines).`, step: (existed ? 'Rewrote ' : 'Wrote ') + args.path };
    }
    case 'project_patch': {
      const f = inside(dir, args.path); if (!f) return { out: 'Error: that path is outside the project.' };
      if (!fs.existsSync(f)) return { out: `Error: ${args.path} doesn't exist; write it with project_write.` };
      const text = fs.readFileSync(f, 'utf8'), find = String(args.find ?? ''), repl = String(args.replace ?? '');
      if (!find) return { out: 'Error: give the exact text to find.' };
      const n = text.split(find).length - 1;
      if (!n) return { out: `Error: that exact text isn't in ${args.path}. Read the file again (project_read) and copy the text exactly, including spaces.` };
      if (n > 1 && !args.all) return { out: `Error: that text appears ${n} times in ${args.path}. Include more of the surrounding lines so it's unique, or set all=true to change every one.` };
      fs.writeFileSync(f, args.all ? text.split(find).join(repl) : text.replace(find, () => repl), 'utf8');
      return { out: `Changed ${n > 1 ? n + ' places' : 'one place'} in ${args.path}.`, step: 'Edited ' + args.path };
    }
    case 'project_run': {
      const command = String(args.command || '').trim();
      if (!command) return { out: 'Error: give the command to run.' };
      if (!approved) { const c = rules.checkCommand(command, { hasVenv: hasVenv(dir) }); if (c.ask) return { needs: `Run this in the project "${pname}" (${c.why}): ${command.slice(0, 160)}`, category: c.category, out: '' }; }
      const r = await exec(dir, command, Number(args.timeout_s) || DEFAULT_TIMEOUT);
      note(dir, `Ran ${command.slice(0, 120)} → ${r.timedOut ? 'timed out' : 'exit ' + r.code}`);
      return { out: result(command, r), step: `Ran ${command.slice(0, 60)}${r.code === 0 ? '' : r.timedOut ? ' (timed out)' : ' (failed)'}` };
    }
    case 'project_check': {
      const m = readManifest(dir) || {};
      let command = String(args.command || '').trim();
      if (command && !approved) { const c = rules.checkCommand(command, { hasVenv: hasVenv(dir) }); if (c.ask) return { needs: `Use this as the project's check (${c.why}): ${command.slice(0, 160)}`, category: c.category, out: '' }; }
      if (command) note(dir, 'Set the check command', { check: command });
      else command = await checkCommandFor(dir, m);
      if (!command) return { out: "Error: Atlas can't tell how to check this project yet. Give the command (e.g. project_check with command \"npm test\" or \"python -m pytest -q\"); it's remembered for next time." };
      // a Node project whose packages aren't installed yet: install them first
      let pre = '';
      if (/^npm (test|run )/.test(command) && fs.existsSync(path.join(dir, 'package.json')) && !fs.existsSync(path.join(dir, 'node_modules'))) {
        let pkg = {}; try { pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')); } catch {}
        if (Object.keys({ ...(pkg.dependencies || {}), ...(pkg.devDependencies || {}) }).length) {
          const ri = await exec(dir, 'npm install', 600);
          pre = result('npm install', ri) + '\n\n';
          if (ri.code !== 0) return { out: pre + 'The packages failed to install, so the check didn\'t run.', step: 'Checked: the install failed' };
        }
      }
      const r = await exec(dir, command, Number(args.timeout_s) || 600);
      const passed = r.code === 0 && !r.timedOut;
      note(dir, `Checked with ${command} → ${passed ? 'passed' : 'failed'}`, { status: passed ? 'checks pass' : 'checks fail', lastCheck: { at: Date.now(), command, passed } });
      return { out: pre + result(command, r) + (passed ? '\nThe check PASSED.' : '\nThe check FAILED: read the errors above, fix the code, and check again.'), step: passed ? `Checked: it passes (${command})` : `Checked: it fails (${command})` };
    }
    case 'project_open': {
      const how = String(args.how || 'folder');
      if (how === 'folder') { if (el.shell) el.shell.openPath(dir); return { out: `Opened the folder ${dir}.`, step: 'Opened the project folder' }; }
      if (how === 'editor') {
        const hasCode = (await toolchains()).some((t) => t.name === 'code');
        if (!hasCode) { if (el.shell) el.shell.openPath(dir); return { out: "VS Code isn't installed, so the folder was opened instead.", step: 'Opened the project folder' }; }
        const c = shellSpawn('code .', dir, true); c.unref(); c.stdout.resume(); c.stderr.resume();
        return { out: 'Opened the project in VS Code.', step: 'Opened the project in VS Code' };
      }
      if (how === 'stop') { const a = apps.get(dir); if (!a) return { out: 'Nothing of this project is running.' }; killTree(a.child); apps.delete(dir); return { out: `Stopped ${a.command}.`, step: 'Stopped the app' }; }
      if (how === 'app') {
        const m = readManifest(dir) || {};
        const command = String(args.command || m.run || '').trim();
        if (!command) return { out: "Error: say how to start it (command), e.g. \"npm start\" or \"python app.py\"; it's remembered for next time." };
        if (!approved) { const c = rules.checkCommand(command, { hasVenv: hasVenv(dir) }); if (c.ask) return { needs: `Start this from the project "${pname}" (${c.why}): ${command.slice(0, 160)}`, category: c.category, out: '' }; }
        const prev = apps.get(dir); if (prev) killTree(prev.child);
        const child = shellSpawn(command, dir, true);
        let out = ''; const add = (b) => { out = (out + b.toString('utf8')).slice(-20000); };
        child.stdout.on('data', add); child.stderr.on('data', add);
        apps.set(dir, { child, command, get out() { return out; } });
        child.on('close', () => { if (apps.get(dir) && apps.get(dir).child === child) apps.delete(dir); });
        if (args.command) note(dir, 'Set how to start it', { run: command });
        await new Promise((r) => setTimeout(r, 4000));
        const alive = child.exitCode == null;
        return { out: `${alive ? 'Started' : 'It started and already ended'}: ${command}\nFirst output:\n${rules.tail(stripAnsi(out), 3000).trim() || '(nothing yet)'}${alive ? '\nIt keeps running in the background (project_open how=stop ends it).' : `\nExit code ${child.exitCode}.`}`, step: alive ? 'Started the app: ' + command.slice(0, 60) : 'Ran the app: ' + command.slice(0, 60) };
      }
      return { out: 'Error: how is one of folder, editor, app or stop.' };
    }
    case 'project_zip': {
      const files = []; let total = 0, skipped = 0;
      const walk = (d, rel) => {
        for (const e of fs.readdirSync(d, { withFileTypes: true })) {
          const r = rel ? rel + '/' + e.name : e.name;
          if (e.isDirectory()) { if (SKIP.has(e.name) && e.name !== 'bin') { skipped++; continue; } walk(path.join(d, e.name), r); continue; }
          if (!e.isFile() || e.name.endsWith('.tmp')) continue;
          const f = path.join(d, e.name), size = fs.statSync(f).size;
          if (total + size > MAX_ZIP) { skipped++; continue; }
          total += size; files.push({ name: path.basename(dir) + '/' + r, data: fs.readFileSync(f) });
        }
      };
      walk(dir, '');
      const zip = require('./sheets').zip(files);
      if (!library) return { out: "Error: the Library isn't available." };
      const made = library(pname, zip);
      note(dir, 'Zipped it to the Library');
      return { out: `Zipped ${files.length} files (${Math.round(zip.length / 1024)} KB) to ${made.name} in the Library${skipped ? ` (left out ${skipped} things like node_modules, .git and the Python environment, which can be rebuilt)` : ''}.`, step: 'Zipped the project to the Library', made: made.name };
    }
    case 'project_delete': {
      if (!approved) return { needs: `Delete the whole project "${pname}" from Documents\\Atlas Projects`, category: 'delete', out: '' };
      const a = apps.get(dir); if (a) { killTree(a.child); apps.delete(dir); }
      fs.rmSync(dir, { recursive: true, force: true });
      return { out: `Deleted the project ${pname}.`, step: 'Deleted the project ' + pname };
    }
    default: return { out: 'Error: there is no such project tool.' };
  }
}

module.exports = { run, check, list, configure, toolchains, NAMES, base };
