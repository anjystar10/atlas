// Atlas Bot's files, end to end: the real bot loop (renderer/bots.js) with a scripted model makes a document (PDF), a
// spreadsheet (Excel) and a page through the real desktop/botfiles.js; then the files are checked with outside tools
// (pdftotext, LibreOffice), the page is opened in Atlas's viewer, and the Library lists them.
// Run under xvfb:  npx electron --no-sandbox test/files.e2e.js   (ATLAS_DOCS=<dir> keeps the files there for a look)
'use strict';
const { app } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os'), http = require('http');
const { execFileSync } = require('child_process');
const { pathToFileURL } = require('url');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlasfiles-'));
app.setPath('userData', path.join(tmp, 'data'));
app.setPath('documents', process.env.ATLAS_DOCS || path.join(tmp, 'docs'));
const assert = require('assert/strict');

const results = [];
async function ok(name, f) { try { await f(); results.push(1); console.log('ok  ', name); } catch (e) { results.push(0); console.log('FAIL', name, '\n     ', String(e.message).split('\n').slice(0, 8).join('\n      ')); } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const have = (cmd) => { try { execFileSync('which', [cmd]); return true; } catch { return false; } };

app.on('window-all-closed', () => {});
process.on('unhandledRejection', (e) => { console.log('FAIL unhandled', e && e.message); app.exit(1); });
app.whenReady().then(async () => {
  const bf = require('../botfiles.js');
  const esm = (f) => { const to = path.join(tmp, path.basename(f, '.js') + '.mjs'); fs.copyFileSync(path.join(__dirname, '../renderer', f), to); return import(pathToFileURL(to).href); };
  const B = await esm('bots.js');
  const { renderMarkdown } = await esm('md.js');
  // botfiles.js (renderer side) imports './md.js': give the copy its .mjs twin to import
  fs.writeFileSync(path.join(tmp, 'botfiles.mjs'), fs.readFileSync(path.join(__dirname, '../renderer/botfiles.js'), 'utf8').replace("'./md.js'", "'./md.mjs'"));
  const F = await import(pathToFileURL(path.join(tmp, 'botfiles.mjs')).href);

  // a local server that must never be reached by a page (pages have no internet)
  let hits = 0;
  const srv = http.createServer((q, r) => { hits++; r.end('reached'); }); await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const spy = `http://127.0.0.1:${srv.address().port}`;

  const c = (name, args = {}) => ({ name, args, id: name + Math.random() });
  const T = (...calls) => ({ text: '', calls });
  const seen = [], posts = [];
  const script = [
    () => T(c('update_plan', { plan: '[ ] Gather prices\n[ ] Spreadsheet\n[ ] Document\n[ ] Page\n[ ] Report' }), c('workspace_write', { name: 'prices.csv', text: 'Phone,Price,Qty\nPixel 9a,499,2\nGalaxy A56,449,3\n' })),
    () => T(c('workspace_read', { name: 'prices.csv' }), c('workspace_write', { name: '../../escape.txt', text: 'should stay in the job folder' })),
    () => T(c('make_spreadsheet', { title: 'Phone prices', columns: ['Phone', 'Price', 'Qty', 'Total'], rows: [['Pixel 9a', '499', '2', '=B2*C2'], ['Galaxy A56', '449', '3', '=B3*C3'], ['Total', '', '', '=SUM(D2:D3)']] })),
    () => T(c('make_document', { title: 'Budget phones, compared', markdown: '## Summary\n\nThe **Pixel 9a** has the best camera; the *Galaxy A56* is cheaper.\n\n| Phone | Price |\n|---|---|\n| Pixel 9a | $499 |\n| Galaxy A56 | $449 |\n\n- Battery: both last a day\n- Updates: 7 years vs 6 years\n\n<script>document.title="hacked"</script>' })),
    () => T(c('make_page', { title: 'Phone dashboard', intro: '## Overview\n\nTwo phones, **two prices**.\n- One\n- Two',
      tables: [{ title: 'Prices', columns: ['Phone', 'Price'], rows: [['Pixel 9a', '499'], ['Galaxy A56', '449'], ['Moto G', '199']] }],
      charts: [{ title: 'Price', type: 'bar', labels: ['Pixel 9a', 'Galaxy A56', 'Moto G'], series: [{ name: 'USD', values: [499, 449, 199] }] }, { title: 'Share', type: 'pie', labels: ['A', 'B'], series: [{ values: [1, 3] }] }] })),
    () => T(c('make_page', { title: 'Raw page', html: `<!doctype html><title>raw</title><img id="i" src="${spy}/img.png"><script>fetch('${spy}/fetch').then(()=>document.title='reached',()=>document.title='blocked')</script>` })),
    () => T(c('workspace_list')),
    () => T(c('finish', { report: 'early' })),   // unticked plan steps: one reminder (0.10.1)
    () => T(c('finish', { report: '## Budget phones\n\nThe Pixel 9a is the pick; the spreadsheet, document and page have the details.' })),
  ];
  let k = 0;
  const bots = B.createBots({
    load: async () => null, save: async () => {},
    provider: () => ({ id: 'fake', name: 'Fake' }),
    session: (p, brief) => { seen.push(brief); return { key: 'k', call: async () => { const s = script[k++]; if (!s) throw new Error('script ran out'); return s(); }, results: (list) => { for (const x of list) seen.push(String(x.out)); } }; },
    callWithKeys: async (p, sess, call) => call(), isSwitch: () => false, isRetryable: () => false,
    tools: [], extraTools: F.FILE_TOOLS, labelFor: F.filesLabel,
    // the renderer's runFiles, with the host bridge replaced by botfiles.js itself
    runTool: async (call, { job } = {}) => {
      if (!F.FILES.has(call.name)) return { out: 'no such tool' };
      const args = { ...(call.args || {}) };
      if (call.name === 'make_document') args.html = renderMarkdown(String(args.markdown || ''));
      const r = await bf.run(job.id, job.goal, call.name, args);
      if (r && r.made) { job.made = job.made || []; if (!job.made.includes(r.made)) job.made.push(r.made); }
      return r;
    },
    reportFooter: F.filesFooter,
    systemPrompt: () => 'sys', notify: () => {}, post: (m) => posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => Date.now(), uid: () => 'jobFILES1', deviceId: () => 'pc', setTimer: (f, ms) => setTimeout(f, Math.min(ms, 50)), clearTimer: (t) => clearTimeout(t),
  });
  await bots.init();
  const j = bots.deploy('Compare budget phones', '');
  const t0 = Date.now(); while (!B.FINAL.has(j.state) && Date.now() - t0 < 90000) await sleep(100);
  const dir = bf.folderFor(j.id, j.goal);
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
  try {
    await ok('the job completed with all its steps', () => { assert.equal(j.state, B.S.COMPLETED, 'state ' + j.state + ' ' + (j.error || '')); assert.equal(k, script.length); });
    await ok('the job folder has the document, its Markdown, the spreadsheet, its CSV, both pages and the written files', () => {
      assert.deepEqual(files, ['Budget phones, compared.md', 'Budget phones, compared.pdf', 'Phone dashboard.html', 'Phone prices.csv', 'Phone prices.xlsx', 'Raw page.html', 'escape.txt', 'prices.csv']);
    });
    await ok('a "../../" name was written inside the job folder, nowhere else', () => {
      assert.ok(fs.existsSync(path.join(dir, 'escape.txt')));
      assert.equal(fs.existsSync(path.join(app.getPath('documents'), 'escape.txt')), false);
      assert.equal(fs.existsSync(path.join(path.dirname(dir), '..', 'escape.txt')), false);
    });
    await ok('the bot read back what it wrote, and listed its files', () => { assert.ok(seen.some((s) => s.includes('Pixel 9a,499,2'))); assert.ok(seen.some((s) => /Files in this job's folder:[\s\S]*Phone prices\.xlsx/.test(s))); });
    await ok('the report names the files and where they are; the chat got it', () => {
      assert.match(j.report, /\*\*Files:\*\* Phone prices\.xlsx, Budget phones, compared\.pdf, Phone dashboard\.html, Raw page\.html/);
      assert.match(j.report, /Atlas Bot › Library/);
      assert.equal(posts.length, 1); assert.match(posts[0], /Files:/);
    });
    await ok('the PDF is a real PDF with the text in it (pdftotext), and the <script> in the Markdown is just text', () => {
      const pdf = fs.readFileSync(path.join(dir, 'Budget phones, compared.pdf'));
      assert.equal(pdf.slice(0, 5).toString(), '%PDF-');
      if (!have('pdftotext')) return;
      const text = execFileSync('pdftotext', ['-layout', path.join(dir, 'Budget phones, compared.pdf'), '-'], { encoding: 'utf8' });
      for (const w of ['Budget phones, compared', 'Summary', 'Pixel 9a', 'best camera', 'Galaxy A56', '$449', 'Battery: both last a day', '7 years vs 6 years']) assert.ok(text.includes(w), 'missing in PDF: ' + w + '\n' + text.slice(0, 800));
      assert.ok(text.includes('document.title'), 'the script tag should show as text');
    });
    await ok('the PDF uses the Newsreader font', () => {
      if (!have('pdffonts')) return;
      const f = execFileSync('pdffonts', [path.join(dir, 'Budget phones, compared.pdf')], { encoding: 'utf8' });
      assert.match(f, /Newsreader/i, f);
    });
    await ok('LibreOffice opens the Excel file and works out the formulas', () => {
      if (!have('soffice')) return;
      const out = path.join(tmp, 'lo'); fs.mkdirSync(out, { recursive: true });
      execFileSync('soffice', ['--headless', '--convert-to', 'csv', '--outdir', out, path.join(dir, 'Phone prices.xlsx')], { stdio: 'ignore', timeout: 120000 });
      const csv = fs.readFileSync(path.join(out, 'Phone prices.csv'), 'utf8').trim().split(/\r?\n/);
      assert.deepEqual(csv, ['Phone,Price,Qty,Total', 'Pixel 9a,499,2,998', 'Galaxy A56,449,3,1347', 'Total,,,2345']);
    });
    await ok("the spreadsheet's CSV twin has the header and rows", () => {
      const t = fs.readFileSync(path.join(dir, 'Phone prices.csv'), 'utf8');
      assert.ok(t.startsWith('﻿Phone,Price,Qty,Total\r\nPixel 9a,499,2,=B2*C2'));
    });
    // the page, in Atlas's own viewer
    const pw = bf.open(path.join(dir, 'Phone dashboard.html')) && require('electron').BrowserWindow.getAllWindows().find((w) => /Phone dashboard/.test(w.getTitle()) || /Phone dashboard/.test(decodeURIComponent(w.webContents.getURL())));
    await sleep(1500);
    await ok('the page draws: title, intro, a 3-row table, a bar chart and a pie chart', async () => {
      assert.ok(pw, 'no viewer window');
      const r = await pw.webContents.executeJavaScript(`({ h1: document.querySelector('h1').textContent, strong: document.querySelector('section strong') && document.querySelector('section strong').textContent,
        rows: document.querySelectorAll('tbody tr').length, rects: document.querySelectorAll('svg rect').length, paths: document.querySelectorAll('svg path').length, li: document.querySelectorAll('ul li').length, title: document.title })`);
      assert.deepEqual(r, { h1: 'Phone dashboard', strong: 'two prices', rows: 3, rects: 3, paths: 2, li: 2, title: 'Phone dashboard' });
    });
    await ok('on the page: typing in the filter narrows the table; clicking a heading sorts it', async () => {
      const r = await pw.webContents.executeJavaScript(`(() => { const box = document.querySelector('input'); box.value = 'pixel'; box.dispatchEvent(new Event('input'));
        const a = document.querySelectorAll('tbody tr').length; const cnt = document.querySelector('.count').textContent; box.value = ''; box.dispatchEvent(new Event('input'));
        document.querySelectorAll('th')[1].click(); const first = document.querySelector('tbody tr td').textContent; document.querySelectorAll('th')[1].click(); const firstDesc = document.querySelector('tbody tr td').textContent;
        return { a, cnt, first, firstDesc }; })()`);
      assert.deepEqual(r, { a: 1, cnt: '1 of 3 rows', first: 'Moto G', firstDesc: 'Pixel 9a' });
    });
    bf.open(path.join(dir, 'Raw page.html'));
    await sleep(2000);
    const rw = require('electron').BrowserWindow.getAllWindows().find((w) => /Raw page/.test(decodeURIComponent(w.webContents.getURL())));
    await ok("a page can't reach the internet: its fetch and image both fail, and the server never hears from it", async () => {
      assert.ok(rw, 'no viewer window for the raw page');
      const t = await rw.webContents.executeJavaScript('document.title');
      assert.equal(t, 'blocked'); assert.equal(hits, 0);
    });
    await ok("a page has no Node and no Atlas bridge", async () => {
      const r = await rw.webContents.executeJavaScript('({ req: typeof require, proc: typeof process, host: typeof window.atlasHost })');
      assert.deepEqual(r, { req: 'undefined', proc: 'undefined', host: 'undefined' });
    });
    await ok('the Library lists the document, spreadsheet and pages it made (not their .md/.csv twins), with the job', () => {
      const lib = bf.library();
      const made = lib.filter((x) => x.made).map((x) => `${x.kind}:${x.title}`).sort();
      assert.deepEqual(made, ['document:Budget phones, compared', 'file:escape.txt', 'file:prices.csv', 'page:Phone dashboard', 'page:Raw page', 'spreadsheet:Phone prices']);
      assert.ok(!lib.some((x) => x.name === 'Phone prices.csv' || x.name === 'Budget phones, compared.md'));
      assert.ok(lib.every((x) => x.goal === 'Compare budget phones'));
    });
    await ok('the Library only opens, shows or deletes files inside Documents › Atlas Bot', () => {
      const outside = path.join(tmp, 'secret.txt'); fs.writeFileSync(outside, 'x');
      assert.equal(bf.inside(outside), false); assert.equal(bf.open(outside), false); assert.equal(bf.showInFolder(outside), false); assert.equal(bf.remove(outside), false);
      assert.ok(fs.existsSync(outside));
      assert.equal(bf.inside(path.join(app.getPath('documents'), 'Atlas Bot')), false, 'the folder itself is not a file to delete');
      assert.equal(bf.inside(path.join(app.getPath('documents'), 'Atlas Bot', '..', 'x')), false);
    });
    await ok('deleting from the Library removes the file and its entry', () => {
      const f = path.join(dir, 'Raw page.html');
      assert.equal(bf.remove(f), true); assert.equal(fs.existsSync(f), false);
      assert.ok(!bf.library().some((x) => x.file === f));
    });
  } finally {
    srv.close();
    const n = results.filter(Boolean).length;
    console.log(`\n${n}/${results.length} passed`);
    app.exit(n === results.length ? 0 : 1);
  }
});
