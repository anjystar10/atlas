// Projects, end to end: the real bot loop (renderer/bots.js) with a scripted model builds two small projects through
// the real desktop/projects.js: a Node.js command-line tool and a Python script. Each one's first check fails on a
// planted bug; the bot reads the file, fixes it and checks again until it passes, runs it, and zips it to the Library.
// Along the way it tries to leave the project (a "../" path, a chained command, a PC-wide pip install): those must
// stop for the user's OK (or be refused) and nothing outside the project may change. A runaway program is stopped
// at its time limit together with the program it started.
// Run under xvfb:  npx electron --no-sandbox test/projects.e2e.js
'use strict';
const { app } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os');
const { execFileSync } = require('child_process');
const { pathToFileURL } = require('url');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlasproj-'));
app.setPath('userData', path.join(tmp, 'data'));
app.setPath('documents', path.join(tmp, 'docs'));
const assert = require('assert/strict');

const results = [];
async function ok(name, f) { try { await f(); results.push(1); console.log('ok  ', name); } catch (e) { results.push(0); console.log('FAIL', name, '\n     ', String(e.message).split('\n').slice(0, 10).join('\n      ')); } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// running (a zombie waiting for its parent to collect it counts as gone: it isn't running anything)
const alive = (pid) => { try { process.kill(pid, 0); } catch { return false; } try { return !/^State:\s+Z/m.test(fs.readFileSync(`/proc/${pid}/status`, 'utf8')); } catch { return true; } };

app.on('window-all-closed', () => {});
process.on('unhandledRejection', (e) => { console.log('FAIL unhandled', e && e.stack); app.exit(1); });
app.whenReady().then(async () => {
  const projects = require('../projects.js');
  const botFiles = require('../botfiles.js');
  // the renderer side talks to the main process through window.atlasHost.projects: here, straight to projects.js
  globalThis.window = { atlasHost: { projects: {
    run: (name, args, approved) => projects.run(name, args, { approved: approved === true, library: botFiles.saveProjectZip }),
    check: (name, args) => projects.check(name, args),
  } } };
  const esm = (f, as) => { const to = path.join(tmp, as || path.basename(f, '.js') + '.mjs'); fs.copyFileSync(path.join(__dirname, '../renderer', f), to); return import(pathToFileURL(to).href); };
  const B = await esm('bots.js');
  const P = await esm('projects.js');
  const docs = app.getPath('documents'), base = path.join(docs, 'Atlas Projects');
  const outside = path.join(docs, 'escape.txt');

  const c = (name, args = {}) => ({ name, args, id: name + Math.random() });
  const T = (...calls) => ({ text: '', calls });
  const seen = { node: [], py: [] }, posts = [];

  // ---- the Node.js tool: count words, with a bug (empty pieces from extra spaces are counted)
  const NODE = 'Word Counter';
  const bugJs = "'use strict';\nfunction count(text) {\n  return String(text).split(' ').length;\n}\nmodule.exports = { count };\nif (require.main === module) console.log(count(process.argv.slice(2).join(' ')));\n";
  const testJs = "const test = require('node:test');\nconst assert = require('node:assert');\nconst { count } = require('../index.js');\ntest('counts words', () => { assert.equal(count('hello world'), 2); });\ntest('extra spaces', () => { assert.equal(count('  a   b  '), 2); });\ntest('empty', () => { assert.equal(count(''), 0); });\n";
  const nodeScript = [
    () => T(c('update_plan', { plan: '[ ] Start the project\n[ ] Write the code and tests\n[ ] Check, fix, check\n[ ] Run it\n[ ] Zip it' }), c('project_list')),
    () => T(c('project_new', { name: NODE, goal: 'A command-line tool that counts the words in what you type', stack: 'Node.js CLI' })),
    () => T(c('project_write', { project: NODE, path: 'package.json', text: JSON.stringify({ name: 'word-counter', version: '1.0.0', scripts: { test: 'node --test' } }, null, 2) }),
      c('project_write', { project: NODE, path: 'index.js', text: bugJs }), c('project_write', { project: NODE, path: 'test/count.test.js', text: testJs })),
    () => T(c('project_check', { project: NODE })),
    () => T(c('project_read', { project: NODE, path: 'index.js' })),
    () => T(c('project_patch', { project: NODE, path: 'index.js', find: "String(text).split(' ').length", replace: 'String(text).split(/\\s+/).filter(Boolean).length' })),
    () => T(c('project_check', { project: NODE })),
    () => T(c('project_run', { project: NODE, command: 'node index.js the quick brown fox' })),
    () => T(c('project_run', { project: NODE, command: 'node ../../steal.js' })),              // leaves the project: needs the OK (denied)
    () => T(c('project_write', { project: NODE, path: '../../escape.txt', text: 'should never be written' })),   // refused outright
    () => T(c('project_run', { project: NODE, command: 'npm test && echo pwned > ../pwned.txt' })),   // a chained command: needs the OK (denied)
    () => T(c('project_zip', { project: NODE })),
    () => T(c('finish', { report: '## Word Counter\n\nBuilt and tested: `node index.js <text>` prints the word count. The zip is in the Library.' })),
  ];

  // ---- the Python script: Celsius to Fahrenheit, with a bug (minus instead of plus)
  const PY = 'Temp Converter';
  const nodeIdx = { i: 0 }, pyIdx = { i: 0 };
  const pyScript = [
    () => T(c('project_new', { name: PY, goal: 'Convert temperatures between Celsius and Fahrenheit', stack: 'Python script' })),
    () => T(c('project_write', { project: PY, path: 'converter.py', text: 'import sys\n\ndef c_to_f(c):\n    return c * 9 / 5 - 32\n\nif __name__ == "__main__":\n    print(c_to_f(float(sys.argv[1])))\n' }),
      c('project_write', { project: PY, path: 'test_converter.py', text: 'import unittest\nfrom converter import c_to_f\n\nclass T(unittest.TestCase):\n    def test_boiling(self):\n        self.assertEqual(c_to_f(100), 212)\n    def test_freezing(self):\n        self.assertEqual(c_to_f(0), 32)\n\nif __name__ == "__main__":\n    unittest.main()\n' })),
    () => T(c('project_check', { project: PY })),
    () => T(c('project_patch', { project: PY, path: 'converter.py', find: '- 32', replace: '+ 32' })),
    () => T(c('project_check', { project: PY })),
    () => T(c('project_run', { project: PY, command: (process.platform === 'win32' ? 'python' : 'python3') + ' converter.py 100' })),
    () => T(c('project_run', { project: PY, command: 'pip install requests' })),   // PC-wide install: needs the OK (denied)
    () => T(c('finish', { report: '## Temp Converter\n\nconverter.py converts Celsius to Fahrenheit; its tests pass.' })),
  ];

  let n = 0;
  const bots = B.createBots({
    load: async () => null, save: async () => {},
    provider: () => ({ id: 'fake', name: 'Fake' }),
    session: (p, brief) => {
      const which = /Temp Converter|temperatures/.test(brief) ? 'py' : 'node';
      const script = which === 'py' ? pyScript : nodeScript, idx = which === 'py' ? pyIdx : nodeIdx;
      // (briefs repeat the latest results, so only tool results go in seen)
      return { key: 'k', call: async () => { const s = script[idx.i++]; if (!s) throw new Error('script ran out: ' + which); return s(); }, results: (list) => { for (const x of list) seen[which].push(String(x.out)); } };
    },
    callWithKeys: async (p, sess, call) => call(), isSwitch: () => false, isRetryable: () => false,
    tools: [], extraTools: P.PROJECT_TOOLS, labelFor: P.projectLabel,
    // as botwire.js wires it: project tools through renderer/projects.js, approvals asked ahead with its check
    runTool: async (call, opts = {}) => (P.PROJECTS.has(call.name) ? P.runProject(call, { approved: !!opts.approved }) : { out: 'no such tool' }),
    checkApproval: (call) => (P.PROJECTS.has(call.name) ? P.projectApproval(call) : Promise.resolve(null)),
    systemPrompt: () => 'sys', notify: () => {}, post: (m) => posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => Date.now(), uid: () => 'jobPROJ' + (++n), deviceId: () => 'pc', setTimer: (f, ms) => setTimeout(f, Math.min(ms, 50)), clearTimer: (t) => clearTimeout(t),
  });
  await bots.init();

  // runs a job to its end, saying no to every approval it asks for (recording what it asked)
  async function runJob(goal) {
    const asked = [];
    const j = bots.deploy(goal, '');
    const t0 = Date.now();
    while (!B.FINAL.has(j.state) && Date.now() - t0 < 240000) {
      if (j.state === B.S.AWAITING_APPROVAL) { asked.push(j.approval.what); bots.approve(j.id, false); }
      await sleep(100);
    }
    return { j, asked };
  }

  try {
    const a = await runJob('Build me a command-line word counter');
    const nodeDir = path.join(base, NODE);
    await ok('Node: the job completed, every scripted step used', () => { assert.equal(a.j.state, B.S.COMPLETED, 'state ' + a.j.state + ' ' + (a.j.error || '')); assert.equal(nodeIdx.i, nodeScript.length); });
    await ok('Node: the first check failed on the planted bug, then passed after the fix', () => {
      const checks = seen.node.filter((s) => /The check (PASSED|FAILED)/.test(s));
      assert.equal(checks.length, 2, checks.join('\n---\n'));
      assert.match(checks[0], /The check FAILED/); assert.match(checks[0], /extra spaces|empty/);
      assert.match(checks[1], /The check PASSED/);
    });
    await ok('Node: it ran the tool and got the right answer (4 words)', () => assert.ok(seen.node.some((s) => /\$ node index\.js the quick brown fox\n4\n/.test(s)), seen.node.filter((s) => s.includes('quick')).join('\n')));
    await ok('Node: leaving the project and a chained command both stopped for the OK (and nothing ran when the user said no)', () => {
      assert.equal(a.asked.length, 2, a.asked.join(' | '));
      assert.match(a.asked[0], /points outside the project.*node \.\.\/\.\.\/steal\.js/);
      assert.match(a.asked[1], /chains, redirects or substitutes.*npm test && echo pwned/);
      assert.equal(fs.existsSync(path.join(base, 'pwned.txt')), false);
    });
    await ok('Node: a "../../" file path was refused and nothing was written outside the project', () => {
      assert.ok(seen.node.some((s) => /that path is outside the project/.test(s)));
      assert.equal(fs.existsSync(outside), false); assert.equal(fs.existsSync(path.join(base, 'escape.txt')), false);
    });
    await ok('Node: the manifest says the checks pass, with the history of what happened', () => {
      const m = JSON.parse(fs.readFileSync(path.join(nodeDir, '.atlas-project.json'), 'utf8'));
      assert.equal(m.status, 'checks pass'); assert.equal(m.stack, 'Node.js CLI'); assert.equal(m.lastCheck.passed, true);
      assert.ok(m.history.some((h) => /Checked with npm test → failed/.test(h.what)) && m.history.some((h) => /Checked with npm test → passed/.test(h.what)));
    });
    await ok('Node: the zip is in the Library, with the code and tests but not the manifest', () => {
      const zip = path.join(docs, 'Atlas Bot', 'Projects', NODE + '.zip');
      assert.ok(fs.existsSync(zip), 'no zip at ' + zip);
      const names = execFileSync('python3', ['-c', 'import zipfile,sys;print("\\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))', zip], { encoding: 'utf8' }).trim().split('\n').sort();
      assert.deepEqual(names, ['Word Counter/index.js', 'Word Counter/package.json', 'Word Counter/test/count.test.js']);
      const lib = botFiles.library().find((x) => x.file === zip);
      assert.ok(lib && lib.kind === 'project', JSON.stringify(lib));
    });

    const b = await runJob('Build a Celsius to Fahrenheit converter in Python (Temp Converter)');
    await ok('Python: the job completed, every scripted step used', () => { assert.equal(b.j.state, B.S.COMPLETED, 'state ' + b.j.state + ' ' + (b.j.error || '')); assert.equal(pyIdx.i, pyScript.length); });
    await ok('Python: the unittest check failed (212 ≠ 148), then passed after the fix', () => {
      const checks = seen.py.filter((s) => /The check (PASSED|FAILED)/.test(s));
      assert.equal(checks.length, 2, checks.join('\n---\n'));
      assert.match(checks[0], /unittest discover/); assert.match(checks[0], /FAILED/); assert.match(checks[1], /PASSED/);
    });
    await ok('Python: running it prints 212.0', () => assert.ok(seen.py.some((s) => /converter\.py 100\n212\.0\n/.test(s))));
    await ok('Python: a PC-wide pip install stopped for the OK', () => { assert.equal(b.asked.length, 1); assert.match(b.asked[0], /PC-wide Python.*pip install requests/); });

    // ---- a runaway program and the one it started are both stopped at the time limit
    await ok('a command that runs past its time limit is stopped, together with the program it started', async () => {
      await projects.run('project_write', { project: NODE, path: 'hang.js', text: "const { spawn } = require('child_process');\nconst k = spawn(process.execPath, ['-e', 'setInterval(function(){},1000)'], { stdio: 'ignore' });\nrequire('fs').writeFileSync('child.pid', String(k.pid));\nsetInterval(() => {}, 1000);\n" });
      const t0 = Date.now();
      const r = await projects.run('project_run', { project: NODE, command: 'node hang.js', timeout_s: 5 });
      assert.match(r.out, /ran past its time limit/);
      assert.ok(Date.now() - t0 < 15000, 'took ' + (Date.now() - t0));
      const pid = Number(fs.readFileSync(path.join(nodeDir, 'child.pid'), 'utf8'));
      await sleep(800);
      assert.equal(alive(pid), false, 'the child program is still running');
    });
    await ok('deleting a project always asks; with the OK it goes', async () => {
      const q = await projects.run('project_delete', { project: PY });
      assert.match(q.needs, /Delete the whole project "Temp Converter"/);
      assert.ok(fs.existsSync(path.join(base, PY)));
      await projects.run('project_delete', { project: PY }, { approved: true });
      assert.equal(fs.existsSync(path.join(base, PY)), false);
    });
    await ok('the chat asks right there for a risky command, and runs it after a yes', async () => {
      let asked = null;
      const r = await P.runProject(c('project_run', { project: NODE, command: 'git push origin main' }), { confirm: async (title, what) => { asked = what; return false; } });
      assert.match(asked, /sends your code out/); assert.equal(r.out, 'The user said no to that.');
      const r2 = await P.runProject(c('project_run', { project: NODE, command: 'node index.js a b' }), { confirm: async () => { throw new Error('should not ask'); } });
      assert.match(r2.out, /\n2\n/);
    });
  } finally {
    const passed = results.filter(Boolean).length;
    console.log(`\n${passed}/${results.length} passed`);
    app.exit(passed === results.length ? 0 : 1);
  }
});
