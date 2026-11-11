// Atlas Bot's browser, end to end in a real Electron window against a local test site (run under xvfb):
//   npx electron test/browser.e2e.js            the tools one by one
// Exits 0 when every check passes.
'use strict';
const { app } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'atlasbb-'));
app.setPath('userData', path.join(tmp, 'data')); app.setPath('documents', path.join(tmp, 'docs'));
const assert = require('assert/strict');
const site = require('./fixtures/site.js');

const results = [];
async function check(name, f) { try { await f(); results.push(['ok', name]); console.log('ok  ', name); } catch (e) { results.push(['FAIL', name, e.message]); console.log('FAIL', name, '\n     ', e.message.split('\n').slice(0, 6).join('\n      ')); } }
const num = (out, re) => { const m = out.match(re); assert.ok(m, 'no element matching ' + re + ' in:\n' + out.slice(-1500)); return m[1]; };

app.whenReady().then(async () => {
  const bb = require('../botbrowser.js');
  const { server, got, base } = await site.start();
  const J = 'job12345', T = 'Test job: kettles';
  const run = (n, a) => bb.run(J, T, n, a);
  let r;
  await check('open a page and read it', async () => {
    r = await run('browser_open', { url: base + '/' });
    assert.match(r.out, /Page: Test Shop Home/); assert.match(r.out, /Things you can use/); assert.match(r.out, /field "Search the shop"/);
  });
  await check('type into the search box and press Enter: the site gets the search', async () => {
    const n = num(r.out, /\[(\d+)\] (?:search )?field "Search the shop"/);
    r = await run('browser_type', { target: n, text: 'kettle', submit: true });
    assert.deepEqual(got.searches, ['kettle']); assert.match(r.out, /Results for kettle/);
  });
  await check('click a link by its words', async () => {
    r = await run('browser_open', { url: base + '/' });
    r = await run('browser_click', { target: 'Join the newsletter form' });
    assert.match(r.out, /Step 1 of 2/); assert.match(r.out, /The page changed/);
  });
  await check('fill step 1 (text + dropdown), click Next: the server has the values', async () => {
    r = await run('browser_type', { target: num(r.out, /\[(\d+)\] field "Your name"/), text: 'King Anjy' });
    r = await run('browser_select', { target: num(r.out, /\[(\d+)\] dropdown "Country"/), option: 'Nigeria' });
    assert.match(r.out, /chosen: "Nigeria"/);
    r = await run('browser_click', { target: num(r.out, /\[(\d+)\] button "Next"/) });
    assert.deepEqual(got.step1, { name: 'King Anjy', country: 'ng' }); assert.match(r.out, /Step 2 of 2/);
  });
  await check('upload a file from the job folder, then finish', async () => {
    const e = bb.jobs.get(J); fs.mkdirSync(e.folder, { recursive: true }); fs.writeFileSync(path.join(e.folder, 'notes.txt'), 'hello from atlas');
    const bad = await run('browser_upload', { target: num(r.out, /\[(\d+)\] file upload/), file: '/etc/passwd' });
    assert.match(bad.out, /isn't in the job folder/);
    r = await run('browser_upload', { target: num(r.out, /\[(\d+)\] file upload/), file: 'notes.txt' });
    assert.match(r.out, /attached: notes.txt/);
    r = await run('browser_click', { target: 'Finish' });
    assert.equal(got.done, true); assert.deepEqual(got.upload, { filename: 'notes.txt', content: 'hello from atlas' });
  });
  await check('download a file by its link: it lands in the job folder', async () => {
    r = await run('browser_open', { url: base + '/' });
    const d = await run('browser_download', { target: num(r.out, /\[(\d+)\] link "Download the price list"/) });
    assert.match(d.out, /Downloaded price list\.csv/);
    assert.equal(fs.readFileSync(d.file, 'utf8'), 'item,price\nBlue Kettle,25\nRed Kettle,30\n');
    assert.ok(d.file.startsWith(path.join(tmp, 'docs', 'Atlas Bot')));
  });
  await check('"Place order" needs the OK; a search does not', async () => {
    r = await run('browser_open', { url: base + '/shop' });
    const n = num(r.out, /\[(\d+)\] button "Place order"/);
    assert.match(await bb.check(J, T, 'browser_click', { target: n }), /Click “Place order” on 127\.0\.0\.1/);
    assert.match(await bb.check(J, T, 'browser_type', { target: num(r.out, /\[(\d+)\] (?:number )?field "Quantity"/), text: '2', submit: true }), /Submit the form “Place order”/);
    assert.equal(got.orders, 0);
    r = await run('browser_open', { url: base + '/' });
    assert.equal(await bb.check(J, T, 'browser_type', { target: num(r.out, /\[(\d+)\] (?:search )?field "Search the shop"/), text: 'x', submit: true }), null);
    assert.equal(await bb.check(J, T, 'browser_click', { target: 'Go to the shop' }), null);
    assert.match(await bb.check(J, T, 'browser_upload', { target: 1, file: 'notes.txt' }), /Upload notes\.txt/);
  });
  await check('approved: the order button really clicks', async () => {
    r = await run('browser_open', { url: base + '/shop' });
    r = await run('browser_click', { target: 'Place order' });
    assert.equal(got.orders, 1, r.out.slice(0, 600)); assert.match(r.out, /Order number 1001/);
  });
  await check('passwords are refused; the sign-in page is flagged', async () => {
    r = await run('browser_open', { url: base + '/login' });
    assert.match(r.out, /asks for a password/);
    const p = await run('browser_type', { target: num(r.out, /\[(\d+)\] password field/), text: 'hunter2' });
    assert.match(p.out, /never typed/);
  });
  await check('a screenshot comes back as a picture', async () => {
    const s = await run('browser_screenshot', {});
    assert.ok(s.image && s.image.base64.length > 2000, 'no picture: ' + s.out);
  });
  await check('back, scroll, wait for text, keys', async () => {
    r = await run('browser_open', { url: base + '/' }); r = await run('browser_click', { target: 'Go to the shop' });
    r = await run('browser_back', {}); assert.match(r.out, /Test Shop Home/);
    r = await run('browser_scroll', { direction: 'down' }); assert.match(r.out, /Scrolled down/);
    r = await run('browser_wait', { text: 'Test Shop' }); assert.match(r.out, /is on the page now/);
    assert.match((await run('browser_press', { key: 'Tab' })).out, /Pressed Tab/);
    assert.match((await run('browser_open', { url: 'file:///etc/passwd' })).out, /only web addresses/);
  });
  await check('handover shows the window', async () => {
    const h = await run('browser_handover', { reason: 'The site wants you to sign in.' });
    assert.match(h.handover, /Hand Back/); assert.equal(bb.info(J).visible, true);
  });
  if (process.env.ATLAS_NET) await check('real internet: example.com, then its link', async () => {
    r = await run('browser_open', { url: 'https://example.com' }); assert.match(r.out, /Example Domain/);
    r = await run('browser_click', { target: num(r.out, /\[(\d+)\] link "[^"]*"/) }); assert.doesNotMatch(r.out, /^Page: Example Domain/m);
  });
  bb.close(J); server.close();
  const bad = results.filter((x) => x[0] !== 'ok');
  console.log(`\n${results.length - bad.length}/${results.length} passed`);
  app.exit(bad.length ? 1 : 0);
});
