// A whole Atlas Bot job, end to end: the real bot loop (renderer/bots.js) with a scripted model, driving the real
// browser window (botbrowser.js) through a local test site. Run under xvfb:  npx electron --no-sandbox test/botjob.e2e.js
'use strict';
const { app } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os');
const { pathToFileURL } = require('url');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlasjob-'));
app.setPath('userData', path.join(tmp, 'data')); app.setPath('documents', path.join(tmp, 'docs'));
const assert = require('assert/strict');
const site = require('./fixtures/site.js');

const results = [];
function ok(name, f) { try { f(); results.push(1); console.log('ok  ', name); } catch (e) { results.push(0); console.log('FAIL', name, '\n     ', e.message.split('\n').slice(0, 8).join('\n      ')); } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

app.on('window-all-closed', () => {});   // the test decides when to quit, not the last window closing
process.on('unhandledRejection', (e) => { console.log('FAIL unhandled', e && e.message); app.exit(1); });
app.whenReady().then(async () => {
  const bb = require('../botbrowser.js');
  // the renderer's modules are ES modules in .js files: Electron's Node needs them as .mjs to import them here
  const esm = (f) => { const to = path.join(tmp, path.basename(f, '.js') + '.mjs'); fs.copyFileSync(path.join(__dirname, '../renderer', f), to); return import(pathToFileURL(to).href); };
  const B = await esm('bots.js');
  const { BROWSER_TOOLS, BROWSER, browserLabel } = await esm('botbrowse.js');
  const { server, got, base } = await site.start();
  const seen = []; let posts = [], notes = [];
  // the "model": each turn looks at what the bot was last shown, finds the numbers it needs, and acts
  const last = () => seen[seen.length - 1] || '';
  const n = (re) => { const m = last().match(re); if (!m) throw new Error('model could not find ' + re + ' in:\n' + last().slice(0, 1500)); return m[1]; };
  const c = (name, args = {}) => ({ name, args, id: name + Math.random() });
  const T = (...calls) => ({ text: '', calls });
  let jobRef = null;
  const script = [
    () => T(c('update_plan', { plan: '[ ] Search the shop for kettles\n[ ] Fill in the newsletter form\n[ ] Download the price list\n[ ] Order a kettle\n[ ] Report' }), c('browser_open', { url: base + '/' })),
    () => T(c('browser_type', { target: n(/\[(\d+)\] (?:search )?field "Search the shop"/), text: 'kettle', submit: true })),
    () => T(c('note', { text: 'Blue Kettle $25, Red Kettle $30 (the shop search)' }), c('browser_open', { url: base + '/form' })),
    () => T(c('browser_type', { target: n(/\[(\d+)\] field "Your name"/), text: 'King Anjy' }), c('browser_select', { target: n(/\[(\d+)\] dropdown "Country"/), option: 'Nigeria' })),
    () => T(c('browser_click', { target: n(/\[(\d+)\] button "Next"/) })),
    () => { fs.mkdirSync(bb.jobs.get(jobRef.id).folder, { recursive: true }); fs.writeFileSync(path.join(bb.jobs.get(jobRef.id).folder, 'notes.txt'), 'hello from atlas'); return T(c('browser_upload', { target: n(/\[(\d+)\] file upload/), file: 'notes.txt' })); },
    () => T(c('browser_click', { target: 'Finish' })),
    () => T(c('browser_open', { url: base + '/' }), c('browser_download', { target: 'Download the price list' })),
    () => T(c('browser_open', { url: base + '/shop' })),
    () => T(c('browser_click', { target: n(/\[(\d+)\] button "Place order"/) })),
    () => T(c('browser_open', { url: base + '/login' })),
    () => T(c('browser_handover', { reason: 'The shop wants you to sign in.' })),
    () => T(c('browser_read')),
    () => T(c('finish', { report: '## Kettles\n\nSearched, filled the form, downloaded the price list and placed order 1001.' })),
  ];
  let k = 0;
  const bots = B.createBots({
    load: async () => null, save: async () => {},
    provider: () => ({ id: 'fake', name: 'Fake' }),
    session: (p, brief) => { seen.push(brief); return { key: 'k', call: async () => { const s = script[k++]; if (!s) throw new Error('script ran out'); return s(); },
      results: (list) => { for (const x of list) seen.push(String(x.out)); } }; },
    callWithKeys: async (p, sess, call) => call(), isSwitch: () => false, isRetryable: () => false,
    tools: [], extraTools: BROWSER_TOOLS, labelFor: browserLabel,
    runTool: async (call, { approved, job, approvedWhat } = {}) => {
      if (!BROWSER.has(call.name)) return { out: 'no such tool' };
      if (approved && approvedWhat && ['browser_click', 'browser_type', 'browser_press'].includes(call.name)) {
        const now = await bb.check(job.id, job.goal, call.name, call.args || {});
        if (now !== approvedWhat) return { out: 'The page changed since the user said yes.' };
      }
      return bb.run(job.id, job.goal, call.name, call.args || {});
    },
    checkApproval: (call, job) => (BROWSER.has(call.name) ? bb.check(job.id, job.goal, call.name, call.args || {}) : Promise.resolve(null)),
    onFinal: (job) => { if (job.browser) bb.close(job.id); },
    systemPrompt: () => 'sys', notify: (t, b) => notes.push([t, b]), post: (m) => posts.push(m),
    plainReply: (s) => String(s).trim(), plainMemory: (s) => String(s).trim(),
    now: () => Date.now(), uid: () => 'jobE2E', deviceId: () => 'pc', setTimer: (f, ms) => setTimeout(f, Math.min(ms, 50)), clearTimer: (t) => clearTimeout(t),
  });
  await bots.init();
  const j = jobRef = bots.deploy('Order a kettle from the test shop', '');
  const until = async (pred, what, ms = 60000) => { const t0 = Date.now(); while (!pred()) { if (Date.now() - t0 > ms) throw new Error('timed out waiting for ' + what + ' (state ' + j.state + ', step ' + k + ')'); await sleep(50); } };
  try {
    await until(() => j.state === B.S.AWAITING_APPROVAL, 'the upload approval');
    ok('uploading a file waits for the OK first', () => { assert.match(j.approval.what, /Upload notes\.txt to 127\.0\.0\.1/); assert.equal(got.upload, null); });
    ok('by then: searched the shop, step 1 sent with the right values', () => { assert.deepEqual(got.searches, ['kettle']); assert.deepEqual(got.step1, { name: 'King Anjy', country: 'ng' }); });
    bots.approve(j.id, true);
    await until(() => j.state === B.S.AWAITING_APPROVAL && /Place order/.test(j.approval.what), 'the order approval');
    ok('approved upload went through, form finished', () => { assert.equal(got.done, true); assert.deepEqual(got.upload, { filename: 'notes.txt', content: 'hello from atlas' }); });
    ok('the price list downloaded into the job folder', () => {
      const f = path.join(bb.jobs.get(j.id).folder, 'price list.csv'); assert.equal(fs.readFileSync(f, 'utf8'), 'item,price\nBlue Kettle,25\nRed Kettle,30\n');
    });
    ok('"Place order" waits for the OK; nothing ordered yet', () => { assert.match(j.approval.what, /Click “Place order” on 127\.0\.0\.1/); assert.equal(got.orders, 0); });
    bots.approve(j.id, true);
    await until(() => j.state === B.S.AWAITING_ANSWER, 'the sign-in hand-over');
    ok('approved order placed exactly once', () => assert.equal(got.orders, 1));
    ok('sign-in is handed to the user, with the window shown', () => { assert.equal(j.handover, true); assert.match(j.question, /sign in.*Hand Back/s); assert.equal(bb.info(j.id).visible, true); });
    bots.handBack(j.id);
    await until(() => B.FINAL.has(j.state), 'the finish');
    ok('the job finishes with a report posted to the chat', () => { assert.equal(j.state, B.S.COMPLETED); assert.equal(posts.length, 1); assert.match(posts[0], /order 1001/); });
    ok('localhost pages are not listed as sources; steps are plain words', () => { assert.deepEqual(j.sources, []); assert.ok(j.steps.some((s) => /Clicked “Place order”|Typed into “Search the shop” and pressed Enter/.test(s)), j.steps.join(' | ')); });
    ok('the bot was told the user handed the browser back', () => assert.ok(j.notes.some((x) => /handed it back/i.test(x)), j.notes.join(' | ')));
    ok('its browser window is closed once the job is done', () => assert.equal(bb.info(j.id).open, false));
  } catch (e) { results.push(0); console.log('FAIL', e.message, '\n     error:', j.error, '\n     steps:', j.steps.join(' | ')); }
  server.close();
  const bad = results.filter((x) => !x).length;
  console.log(`\n${results.length - bad}/${results.length} passed`);
  app.exit(bad ? 1 : 0);
});
