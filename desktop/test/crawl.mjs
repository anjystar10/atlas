// The deep crawl of Atlas for Windows (part of the deep test rig, .github/workflows/deep.yml), run on Linux:
//   xvfb-run -a -s "-screen 0 2560x1440x24" node test/crawl.mjs OUTDIR [--quick]
// It starts the real app (test/crawl-main.js → main.js) with a fresh data folder and odd seed data, through
// Playwright's Electron driver, and:
//   - runs once with no key, then with a fake Gemini, OpenAI and Claude key whose requests go to the stand-in AI
//     (tools/deep/stub.py), which answers ok / markdown / long / 429 / 500 / 503 / bad key / 403 / an HTML 502 /
//     cut-off JSON / blocked / MAX_TOKENS / slow, so every error path shows on screen
//   - the HUD (every button, the terminal and each of its commands, the keys), then the classic window: every tab
//     (Atlas, Log, Bots, Me) scrolled top to bottom, every visible button, link, chip and toggle clicked, every
//     sheet it opens explored, every field typed into (empty, long, unicode, emoji, markup, numbers), Esc / Enter /
//     Space / Tab, the window small and large, then each destructive button (does it ask first?)
//   - records console errors and warnings, page errors (uncaught and unhandled rejections), renderer crashes, the
//     main process's stderr and its own errors (DEEP_LOG), HTML that got into the page from text, replies that show
//     raw JSON / stacks / "undefined", and a screenshot of every new screen
// Writes OUTDIR/shots/*.png, OUTDIR/events.jsonl, OUTDIR/replies.json, OUTDIR/desktop-findings.md.
import { _electron } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const DESK = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = path.resolve(DESK, '..');
const OUT = path.resolve(process.argv.slice(2).find((a) => !a.startsWith('--')) || path.join(os.tmpdir(), 'atlas-deep-desktop'));
const QUICK = process.argv.includes('--quick');
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
const EV = fs.createWriteStream(path.join(OUT, 'events.jsonl'), { flags: 'a' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T0 = Date.now();
let last = 'start', run = '', shotN = 0;
const events = [];
const replies = {};
const coverage = {};
function ev(kind, detail, extra = {}) {
  const e = { t: ((Date.now() - T0) / 1000).toFixed(1), run, kind, detail: String(detail).slice(0, 3000), after: last, ...extra };
  events.push(e); EV.write(JSON.stringify(e) + '\n');
  if (!/^(console-warning|net)$/.test(kind)) console.log(`[${e.t}s] ${run} ${kind}: ${e.detail.slice(0, 200)}  (after: ${last})`);
}
const slug = (s, n = 40) => String(s || '').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, n) || 'x';

// ------------------------------------------------------------------ the stand-in AI
const PORT = 18800 + Math.floor(Math.random() * 900);
const STUB = `http://127.0.0.1:${PORT}`;
const stubProc = spawn('python3', [path.join(ROOT, 'tools', 'deep', 'stub.py'), String(PORT), path.join(OUT, 'stub')], { stdio: 'ignore' });
async function mode(m, once = false) { try { await fetch(`${STUB}/__mode?m=${encodeURIComponent(m)}${once ? '&once=1' : ''}`); } catch (e) { ev('rig', 'stub unreachable: ' + e.message); } }

// ------------------------------------------------------------------ odd text for every field
const LONG = 'long '.repeat(1000);
const INPUTS = ['', LONG, 'Ünïcødé 漢字 العربية עברית हिन्दी 👨‍👩‍👧‍👦🎉', '<script>window.__xss=(window.__xss||0)+1</script><img src=x onerror="window.__xss=(window.__xss||0)+1"><b>bold</b>',
  '-1', '0', '   ', 'NaN', '1e309', '99999999999999999999', "Robert'); DROP TABLE tasks;--", '../../../etc/passwd', 'javascript:alert(1)', '@everyone /approve', '‮evil‬', 'a\nb\tc'];
let inputN = 0;

// ------------------------------------------------------------------ what can be clicked on screen (runs in the page)
const SCAN = (scope) => {
  const root = scope ? document.querySelector(scope) : document.body;
  if (!root) return [];
  const vis = (el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el); return r.width > 2 && r.height > 2 && s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth; };
  const sel = 'button,a[href],[role=button],[role=tab],[role=switch],[role=checkbox],input,select,textarea,summary,label[for],[onclick],[data-k],[data-tab],.chip,.nav,.item,.btn,.tag,.toggle,.switch';
  const cands = new Set(root.querySelectorAll(sel));
  for (const el of root.querySelectorAll('*')) if (el.onclick || getComputedStyle(el).cursor === 'pointer') cands.add(el);
  const native = (el) => ['BUTTON', 'A', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY', 'LABEL'].includes(el.tagName);
  const out = []; const seen = new Set();
  for (const el of cands) {
    if (!vis(el)) continue;
    // a pointer-cursor child inside a clickable parent is the same target (unless it has its own handler)
    let p = el.parentElement, dup = false;
    while (p && p !== root.parentElement) { if (cands.has(p) && !el.onclick && !native(el) && (native(p) || p.onclick)) { dup = true; break; } p = p.parentElement; }
    if (dup) continue;
    const r = el.getBoundingClientRect();
    const cx = Math.round(r.left + Math.min(r.width / 2, 40)), cy = Math.round(r.top + r.height / 2);
    const top = document.elementFromPoint(cx, cy);
    if (!top || !(top === el || el.contains(top) || top.contains(el) && top !== document.body)) continue;   // covered by something else
    const text = (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.value || el.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 70);
    const key = `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}.${[...el.classList].slice(0, 2).join('.')}|${text.replace(/\d+/g, '#')}`;
    if (seen.has(key)) continue; seen.add(key);
    const field = (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' && !['checkbox', 'radio', 'button', 'submit', 'range', 'color', 'file', 'hidden'].includes(el.type)) && !el.disabled && !el.readOnly;
    out.push({ key, text, tag: el.tagName, type: el.type || '', id: el.id || '', x: cx, y: cy, field, select: el.tagName === 'SELECT', disabled: !!el.disabled });
  }
  return out;
};
const STATE = () => ({
  tab: document.querySelector('.nav.on')?.dataset.tab || '', hud: !!document.getElementById('hud'), term: !!document.querySelector('#hudterm:not([hidden])'),
  overlay: document.querySelectorAll('.overlay').length, overlayText: [...document.querySelectorAll('.overlay')].map((o) => o.innerText.slice(0, 200)).join(' | '),
  wall: !!document.querySelector('#hudwall:not([hidden])'), box: !!document.querySelector('#hudbox:not([hidden])'),
  xss: window.__xss || 0, injected: document.querySelectorAll('#page img[src="x"], #hud img[src="x"], .overlay img[src="x"], #page script, #hud script, .overlay script').length,
  scroll: document.querySelector('#page')?.scrollTop || 0, scrollMax: (() => { const p = document.querySelector('#page'); return p ? p.scrollHeight - p.clientHeight : 0; })(),
});
const DANGER = /\b(delete|remove|erase|forget|wipe|reset|clear all|unlink|disconnect|sign out|log ?out|uninstall|revoke|stop all|discard|leave group|forget this)\b/i;
const SKIPTEXT = /^(Quit|Quit Atlas)$/i;

// ------------------------------------------------------------------ one launch of the app
async function launch(name, prefs, { fakeMedia = false } = {}) {
  run = name;
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-deep-'));
  execFileSync('python3', [path.join(ROOT, 'tools', 'deep', 'seed.py'), String(Date.now()), data]);
  fs.renameSync(path.join(data, 'atlas-state.json'), path.join(data, 'state.json'));
  fs.writeFileSync(path.join(data, 'prefs.json'), JSON.stringify({ speakReplies: false, onScreen: false, ...prefs }));
  const mainLog = path.join(OUT, `main-${name}.jsonl`);
  const args = ['--no-sandbox', ...(fakeMedia ? ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] : []), path.join(DESK, 'test', 'crawl-main.js')];
  const app = await _electron.launch({ executablePath: require('electron'), args, cwd: DESK, timeout: 90000,
    env: { ...process.env, ATLAS_DATA: data, DEEP_STUB: STUB, DEEP_LOG: mainLog, ELECTRON_ENABLE_LOGGING: '1' } });
  const errOut = fs.createWriteStream(path.join(OUT, `main-${name}-stderr.txt`));
  app.process().stderr.on('data', (d) => {
    errOut.write(d);
    for (const l of String(d).split('\n')) {
      // Chromium's own chatter on a headless Linux box (no GPU, no D-Bus, no tray) is not Atlas
      if (!l.trim() || /dbus|Gtk-|libva|gpu_|viz_main|ui\/gl|GPU process|sandbox|Fontconfig|ALSA|pulse|StatusNotifier|appindicator|zygote|platform_shared|ozone|egl|vaapi|DevTools listening|Debugger listening|For help, see|inspector|object_proxy|ssl_client_socket|cert_verify|bus\.cc|xdg/i.test(l)) continue;
      if (/error|exception|uncaught|unhandled|TypeError|ReferenceError|fatal/i.test(l)) ev('main-stderr', l.trim());
    }
  });
  const hook = (page) => {
    page.on('console', (m) => {
      const t = m.type();
      if (t === 'error' || t === 'warning') ev('console-' + t, m.text(), { at: m.location() && `${path.basename(m.location().url || '')}:${m.location().lineNumber}` });
    });
    page.on('pageerror', (e) => ev('pageerror', e.stack || e.message));
    page.on('crash', () => ev('renderer-crash', 'the page crashed'));
  };
  app.on('window', (p) => { hook(p); p.waitForLoadState('domcontentloaded').then(() => p.title()).then((t) => { if (t !== 'Atlas') ev('window', 'another window opened: ' + t); }).catch(() => {}); });
  const page = await app.firstWindow();
  hook(page);
  await page.waitForLoadState('domcontentloaded');
  await page.evaluate(() => {
    window.addEventListener('unhandledrejection', (e) => console.error('[unhandledrejection]', e.reason && (e.reason.stack || e.reason.message) || String(e.reason)));
    window.addEventListener('error', (e) => console.error('[window.error]', e.message, e.filename + ':' + e.lineno));
  });
  await sleep(3000);
  coverage[name] = { clicked: 0, typed: 0, screens: 0, overlays: 0 };
  return { app, page, data, mainLog };
}

async function shot(page, what) {
  shotN++;
  const name = `${String(shotN).padStart(4, '0')}-${slug(run, 10)}-${slug(what, 50)}.png`;
  try { await page.screenshot({ path: path.join(OUT, 'shots', name), timeout: 15000 }); } catch (e) { ev('rig', 'screenshot failed: ' + e.message); }
  coverage[run] && coverage[run].screens++;
  return name;
}
const state = (page) => page.evaluate(STATE).catch(() => ({}));
const scan = (page, scope) => page.evaluate(SCAN, scope).catch(() => []);
let lastXss = 0, lastInjected = 0;
async function checkPage(page) {
  const s = await state(page);
  if (s.xss > lastXss) { ev('xss', `a script from text ran in the page (window.__xss=${s.xss})`, { shot: await shot(page, 'xss') }); lastXss = s.xss; }
  if (s.injected > lastInjected) { ev('html-injection', `${s.injected} element(s) built from text that should have stayed text (img src=x / script)`, { shot: await shot(page, 'html-injection') }); }
  lastInjected = s.injected || 0;
  return s;
}

async function typeInto(page, t, value) {
  last = `type into ${t.text || t.id || t.tag} (${run}): ${JSON.stringify(value.slice(0, 40))}`;
  try {
    await page.mouse.click(t.x, t.y);
    await page.keyboard.press('Control+A'); await page.keyboard.press('Delete');
    if (value.length > 200) await page.evaluate((v) => { const el = document.activeElement; if (el && 'value' in el) { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); } }, value);
    else await page.keyboard.type(value, { delay: 2 });
    await page.keyboard.press('Enter');
    await sleep(500);
    coverage[run].typed++;
  } catch (e) { ev('rig', 'typing failed: ' + e.message); }
}

/** Close whatever overlay is open: Esc, then its Cancel / No, then any closing button; a page reload if nothing works. */
async function closeOverlays(page) {
  for (let i = 0; i < 4; i++) {
    let s = await state(page);
    if (!s.overlay && !s.box) return true;
    await page.keyboard.press('Escape'); await sleep(300);
    s = await state(page); if (!s.overlay && !s.box) return true;
    const closer = await page.evaluate(() => {
      const o = [...document.querySelectorAll('.overlay, #hudbox:not([hidden])')].pop(); if (!o) return null;
      const b = o.querySelector('[data-k=no],[data-k=cancel],[data-k=close]') || [...o.querySelectorAll('button')].find((x) => /^(Cancel|Close|Not now|No|Done|OK|End|Back|Keep|×|✕)$/i.test(x.innerText.trim()));
      if (!b) return null; const r = b.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, text: b.innerText.trim() };
    });
    if (closer) { await page.mouse.click(closer.x, closer.y); await sleep(400); continue; }
    // the backdrop (sheets close when it's clicked)
    await page.mouse.click(5, 300); await sleep(300);
  }
  const s = await state(page);
  if (s.overlay || s.box) { ev('overlay-stuck', 'an overlay could not be closed with Esc, Cancel or the backdrop: ' + s.overlayText, { shot: await shot(page, 'overlay-stuck') }); await page.reload(); await sleep(3000); }
  return false;
}

/** One overlay (a sheet): its fields get odd text, then its buttons are tried one at a time (re-opening it is the caller's job). */
async function exploreOverlay(page, depth) {
  coverage[run].overlays++;
  const ts = await scan(page, '.overlay:last-of-type');
  for (const t of ts.filter((x) => x.field)) await typeInto(page, t, INPUTS[inputN++ % INPUTS.length]);
  await checkPage(page);
  const s = await state(page);
  if (s.overlay) await shot(page, 'sheet-filled-' + (ts[0] && ts[0].text));
  // the sheet's main button with that odd text in it (Add, Save, OK…): it must refuse or cope, not break
  const primary = (await scan(page, '.overlay:last-of-type')).find((x) => !x.field && /primary|yes/.test(x.key) && !DANGER.test(x.text));
  if (primary && depth < 2) {
    last = `sheet primary "${primary.text}" (${run})`;
    await page.mouse.click(primary.x, primary.y); await sleep(700);
    const s2 = await checkPage(page);
    if (s2.overlay) await shot(page, 'sheet-after-primary-' + primary.text);
  }
}

async function goTab(page, tab) {
  const s = await state(page);
  if (s.hud) { await page.keyboard.press('Control+Alt+KeyH'); await sleep(800); }
  await closeOverlays(page);
  await page.click(`.nav[data-tab="${tab}"]`, { timeout: 5000 }).catch((e) => ev('rig', `tab ${tab}: ${e.message}`));
  await sleep(700);
}

/** Every target on one classic tab, scrolling down it. */
async function crawlTab(page, tab, visited, dangers) {
  await goTab(page, tab);
  await shot(page, `${tab}-top`);
  for (let round = 0; round < (QUICK ? 3 : 25); round++) {
    for (let guard = 0; guard < 200; guard++) {
      const ts = (await scan(page)).filter((t) => !t.disabled && !visited.has(tab + '|' + t.key) && !/\.nav\b|nav\.|side-foot|#titlebar/.test(t.key) && !(t.tag === 'DIV' && /\bnav\b/.test(t.key)));
      const t = ts[0];
      if (!t) break;
      visited.add(tab + '|' + t.key);
      if (SKIPTEXT.test(t.text)) continue;
      if (DANGER.test(t.text)) { dangers.push({ tab, round, key: t.key, text: t.text }); continue; }
      const before = await state(page);
      if (t.field) { await typeInto(page, t, INPUTS[inputN++ % INPUTS.length]); }
      else if (t.select) {
        last = `select ${t.text} (${run}/${tab})`;
        const n = await page.evaluate(({ x, y }) => { const el = document.elementFromPoint(x, y); const s = el && el.closest('select'); if (!s) return 0; s.selectedIndex = (s.selectedIndex + 1) % Math.max(1, s.options.length); s.dispatchEvent(new Event('change', { bubbles: true })); return s.options.length; }, t);
        await sleep(500);
        if (!n) continue;
      } else {
        last = `click "${t.text}" [${t.key.split('|')[0]}] on ${tab} (${run})`;
        await page.mouse.click(t.x, t.y).catch(() => {});
        coverage[run].clicked++;
        await sleep(650);
      }
      const after = await checkPage(page);
      if (after.overlay > before.overlay || after.box && !before.box) {
        await shot(page, `${tab}--${t.text}`);
        await exploreOverlay(page, 1);
        await closeOverlays(page);
      } else if (after.hud !== before.hud || after.tab !== tab) {
        await shot(page, `${tab}--${t.text}--went-${after.hud ? 'hud' : after.tab}`);
        await goTab(page, tab);
        for (let r = 0; r < round; r++) await page.evaluate(() => { const p = document.querySelector('#page'); p.scrollTop += p.clientHeight * 0.8; });
      } else if (!t.field) {
        // the same page, changed? (a section opened, a chip picked)
        const sig = await page.evaluate(() => document.querySelector('#page')?.innerText.length || 0);
        if (Math.abs(sig - (before.sigLen || sig)) > 200) await shot(page, `${tab}--${t.text}`);
      }
    }
    const moved = await page.evaluate(() => { const p = document.querySelector('#page'); if (!p) return false; const y = p.scrollTop; p.scrollTop += p.clientHeight * 0.8; return p.scrollTop !== y; });
    if (!moved) break;
    await sleep(350);
    if (round % 3 === 2) await shot(page, `${tab}-scrolled-${round + 1}`);
  }
}

async function crawlHud(page) {
  const s = await state(page);
  if (!s.hud) { await page.evaluate(() => document.querySelector('.nav[data-tab=me]')?.click()); }
  await shot(page, 'hud');
  const visited = new Set();
  for (let guard = 0; guard < 80; guard++) {
    const ts = (await scan(page, '#hud')).filter((t) => !visited.has(t.key) && !/classic/i.test(t.text) && !t.field);
    const t = ts[0]; if (!t) break;
    visited.add(t.key);
    if (t.select) continue;
    last = `HUD click "${t.text}" (${run})`;
    await page.mouse.click(t.x, t.y).catch(() => {}); coverage[run].clicked++;
    await sleep(900);
    const a = await checkPage(page);
    await shot(page, 'hud--' + t.text);
    if (a.box || a.overlay) await closeOverlays(page);
    if (!a.hud) { ev('note', 'the HUD closed after ' + t.text); await page.keyboard.press('Control+Alt+KeyH'); await sleep(800); }
  }
  // the terminal and every command
  if (!(await state(page)).term) { await page.keyboard.press('Control+Backquote'); await sleep(400); }
  const cmds = ['/help', '/status', '/bots', '/approve', '/approve zzz', '/deny', '/deny 9999', '/stop', '/history', '/history 1', '/history 99', '/history -1', '/wall', '/mute', '/mute',
    '/clear', '/cls', '/?', '/unknowncommand', '/', '//', 'hello from the terminal', INPUTS[3], INPUTS[2], LONG.slice(0, 3000), '/new', '/hud'];
  for (const c of cmds) {
    if (!(await state(page)).term) { await page.keyboard.press('Control+Backquote'); await sleep(400); }
    last = `terminal: ${JSON.stringify(c.slice(0, 40))} (${run})`;
    try {
      await page.fill('#termin', c, { timeout: 4000 });
      await page.press('#termin', 'Enter');
    } catch (e) { ev('rig', 'terminal input: ' + e.message); }
    await sleep(c.startsWith('/') ? 700 : 2500);
    const a = await checkPage(page);
    if (a.overlay || a.box) { await shot(page, 'term-overlay-' + c); await closeOverlays(page); }
  }
  await shot(page, 'terminal-after-commands');
  for (const k of ['Escape', 'Space', 'Enter', 'Control+Backquote', 'Control+Backquote', 'Tab', 'Tab', 'Space', 'Escape']) { last = `HUD key ${k} (${run})`; await page.keyboard.press(k); await sleep(400); await checkPage(page); }
  await shot(page, 'hud-after-keys');
  await closeOverlays(page);
}

async function resizeAll(page, app) {
  for (const [w, h] of [[760, 560], [400, 300], [1024, 700], [2560, 1400]]) {
    last = `resize ${w}x${h} (${run})`;
    await app.evaluate(({ BrowserWindow }, [w, h]) => { const win = BrowserWindow.getAllWindows().find((x) => x.getTitle() === 'Atlas'); if (win) { win.unmaximize(); win.setSize(w, h); } }, [w, h]);
    await sleep(700);
    for (const tab of ['atlas', 'log', 'bots', 'me']) { await goTab(page, tab); await shot(page, `size-${w}x${h}-${tab}`); }
    const hasHScroll = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2 || (document.querySelector('#page')?.scrollWidth || 0) > (document.querySelector('#page')?.clientWidth || 0) + 2);
    if (hasHScroll) ev('layout', `the page scrolls sideways at ${w}x${h} (something is wider than the window)`);
  }
  await app.evaluate(({ BrowserWindow }) => { const win = BrowserWindow.getAllWindows().find((x) => x.getTitle() === 'Atlas'); if (win) win.setSize(1280, 860); });
  await sleep(500);
}

async function keys(page) {
  for (const tab of ['atlas', 'log', 'bots', 'me']) {
    await goTab(page, tab);
    await page.mouse.click(600, 20).catch(() => {});
    for (const k of ['Escape', 'Enter', 'Space', 'Tab', 'Tab', 'Tab', 'Enter', 'Escape', 'Control+Alt+KeyH', 'Control+Alt+KeyH', 'PageDown', 'End', 'Home']) {
      last = `key ${k} on ${tab} (${run})`; await page.keyboard.press(k); await sleep(250);
    }
    await checkPage(page); await closeOverlays(page);
    const s = await state(page); if (s.hud) { await page.keyboard.press('Control+Alt+KeyH'); await sleep(500); }
  }
}

/** The stand-in answers each way in turn: what does the chat show? */
async function modes(page, list) {
  await goTab(page, 'atlas');
  for (const m of list) {
    await mode(m);
    last = `ask with the stand-in answering "${m}" (${run})`;
    try {
      await page.fill('#ask', `deep test ${m} please`, { timeout: 5000 });
      await page.press('#ask', 'Enter');
    } catch (e) { ev('rig', 'composer: ' + e.message); continue; }
    const wait = m === 'slow' ? 20000 : /429|500|503/.test(m) ? 16000 : 7000;
    await sleep(wait);
    const r = await page.evaluate(() => { const ms = [...document.querySelectorAll('.msg.atlas')]; const l = ms[ms.length - 1]; return { text: l ? l.innerText.slice(0, 1500) : '', busy: !!document.querySelector('.typing, .thinking, [data-busy]') }; });
    replies[`${run}:${m}`] = { ...r, shot: await shot(page, 'reply-' + m) };
    if (/\{\s*"error"|"status"\s*:|\bat [\w.<>]+ \(|\bundefined\b|\[object Object\]|\bNaN\b|TypeError|SyntaxError|Unexpected token|<html|<\/?center>/i.test(r.text)) ev('raw-reply', `the reply for "${m}" shows raw internals: ${r.text.slice(0, 400)}`, { shot: replies[`${run}:${m}`].shot });
    if (m !== 'ok' && m !== 'md' && m !== 'long' && /Stub reply: all good/.test(r.text)) ev('stale-reply', `for "${m}" the chat still shows an earlier reply`);
    await checkPage(page);
    await closeOverlays(page);
  }
  // Esc stops a slow answer; the chat says so
  await mode('hang');
  last = `ask, then Esc while waiting (${run})`;
  try { await page.fill('#ask', 'deep test then stop', { timeout: 5000 }); await page.press('#ask', 'Enter'); } catch {}
  await sleep(2500); await page.keyboard.press('Escape'); await sleep(1500);
  replies[`${run}:esc-stop`] = { text: await page.evaluate(() => [...document.querySelectorAll('.msg.atlas')].pop()?.innerText.slice(0, 500) || ''), shot: await shot(page, 'esc-stop') };
  await mode('ok');
}

async function dangerPass(page, dangers) {
  const res = [];
  const tried = new Set();
  for (const d of dangers) {
    if (tried.has(d.text)) continue; tried.add(d.text);
    await goTab(page, d.tab);
    for (let r = 0; r < d.round; r++) await page.evaluate(() => { const p = document.querySelector('#page'); p.scrollTop += p.clientHeight * 0.8; });
    await sleep(300);
    const t = (await scan(page)).find((x) => x.key === d.key);
    if (!t) { res.push({ ...d, found: false }); continue; }
    const before = await state(page);
    const beforeText = await page.evaluate(() => document.querySelector('#page')?.innerText || '');
    last = `destructive "${t.text}" on ${d.tab} (${run})`;
    await page.mouse.click(t.x, t.y); await sleep(800);
    const after = await checkPage(page);
    const asked = after.overlay > before.overlay;
    const name = await shot(page, 'danger-' + t.text);
    const afterText = await page.evaluate(() => document.querySelector('#page')?.innerText || '');
    res.push({ ...d, asked, shot: name, changed: beforeText !== afterText, sheet: after.overlayText });
    if (!asked && beforeText !== afterText) ev('danger-without-asking', `"${t.text}" on ${d.tab} changed the page without asking first`, { shot: name });
    await closeOverlays(page);
  }
  return res;
}

async function mainLogEvents(file) {
  try {
    for (const l of fs.readFileSync(file, 'utf8').split('\n').filter(Boolean)) {
      const e = JSON.parse(l);
      if (/^(main-uncaught|main-unhandled-rejection|main-console-error|render-process-gone|child-process-gone|error-box)$/.test(e.kind)) ev(e.kind, e.detail);
      if (e.kind === 'dialog' || e.kind === 'dialog-sync') ev('dialog', e.detail);
    }
  } catch {}
}

async function session(name, prefs, plan) {
  let ctx;
  try {
    ctx = await launch(name, prefs, plan);
  } catch (e) { ev('launch-failed', e.stack || e.message); return; }
  const { app, page, mainLog } = ctx;
  const out = { name };
  try {
    if (plan.hud) await crawlHud(page);
    await goTab(page, 'atlas'); await shot(page, 'classic-atlas');
    if (plan.modes) await modes(page, plan.modes);
    if (plan.crawl) {
      const visited = new Set(); const dangers = [];
      for (const tab of ['atlas', 'log', 'bots', 'me']) { try { await crawlTab(page, tab, visited, dangers); } catch (e) { ev('rig', `crawl ${tab}: ${e.stack || e.message}`); } }
      if (plan.keys) await keys(page);
      if (plan.resize) await resizeAll(page, app);
      out.dangers = await dangerPass(page, dangers);
    }
  } catch (e) { ev('rig', `${name}: ${e.stack || e.message}`); }
  finally {
    try { await shot(page, 'end'); } catch {}
    await app.close().catch(() => {});
    await mainLogEvents(mainLog);
  }
  return out;
}

const results = [];
const ALL = ['ok', 'md', 'long', '429', '500', '503', '400key', '403', 'html502', 'malformed', 'empty', 'maxtokens', 'slow'];
results.push(await session('nokey', {}, { hud: true, crawl: true, keys: true, resize: !QUICK }));
results.push(await session('gemini', { keys: { gemini: [{ key: 'AIzaDeepTestKey-not-real-0000000000000' }] }, provider: 'gemini' }, { fakeMedia: true, hud: true, modes: ALL, crawl: !QUICK, keys: false }));
results.push(await session('openai', { keys: { openai: [{ key: 'sk-deep-not-real-000000000000' }] }, provider: 'openai' }, { modes: ['ok', 'md', '429', '500', '400key', 'html502', 'malformed', 'empty', 'maxtokens'] }));
results.push(await session('claude', { keys: { anthropic: [{ key: 'sk-ant-deep-not-real-0000000000' }] }, provider: 'anthropic' }, { modes: ['ok', '429', '503', '400key', 'malformed', 'empty', 'maxtokens'] }));
// two keys for one service: the first runs out (429), Atlas moves to the second
results.push(await session('twokeys', { keys: { gemini: [{ key: 'AIzaDeepFirst-not-real-00000000000' }, { key: 'AIzaDeepSecond-not-real-0000000000' }] }, provider: 'gemini' }, { modes: ['429', 'ok', '400key', 'ok'] }));
stubProc.kill();

fs.writeFileSync(path.join(OUT, 'replies.json'), JSON.stringify(replies, null, 1));
fs.writeFileSync(path.join(OUT, 'sessions.json'), JSON.stringify({ results, coverage }, null, 1));
// the findings: errors grouped (numbers taken out so the same error counts once), with what came before
const groups = new Map();
for (const e of events) {
  if (/^(net|rig-note)$/.test(e.kind)) continue;
  const k = e.kind + ' ' + e.detail.replace(/\d+/g, '#').replace(/\s+/g, ' ').slice(0, 220);
  const g = groups.get(k) || { kind: e.kind, n: 0, sample: e, runs: new Set() };
  g.n++; g.runs.add(e.run); groups.set(k, g);
}
const order = ['xss', 'html-injection', 'pageerror', 'renderer-crash', 'main-uncaught', 'main-unhandled-rejection', 'error-box', 'launch-failed', 'danger-without-asking', 'raw-reply', 'stale-reply', 'overlay-stuck', 'console-error', 'main-console-error', 'main-stderr', 'layout', 'dialog', 'window', 'console-warning', 'note', 'rig'];
const lines = ['# Deep test — desktop', '', `Sessions: ${results.filter(Boolean).map((r) => r.name).join(', ')}; ${shotN} screenshots; ${Math.round((Date.now() - T0) / 60000)} min`, '', 'Coverage: ' + JSON.stringify(coverage), ''];
for (const kind of order) {
  const gs = [...groups.values()].filter((g) => g.kind === kind).sort((a, b) => b.n - a.n);
  if (!gs.length) continue;
  lines.push(`## ${kind} (${gs.reduce((s, g) => s + g.n, 0)})`);
  for (const g of gs.slice(0, 60)) lines.push(`- ×${g.n} [${[...g.runs].join(',')}] ${g.sample.detail.slice(0, 700).replace(/\n/g, '\n    ')}${g.sample.at ? ` (${g.sample.at})` : ''}\n    after: ${g.sample.after}${g.sample.shot ? `\n    shot: ${g.sample.shot}` : ''}`);
  lines.push('');
}
lines.push('## Replies for each stand-in answer', '');
for (const [k, v] of Object.entries(replies)) lines.push(`- **${k}** → ${JSON.stringify(v.text.slice(0, 300))} (${v.shot})`);
lines.push('', '## Destructive buttons', '');
for (const r of results.filter(Boolean)) for (const d of r.dangers || []) lines.push(`- ${r.name} ${d.tab}: "${d.text}" — ${d.found === false ? 'not found again' : d.asked ? 'asked first' : d.changed ? '**changed without asking**' : 'nothing visible happened'} (${d.shot || ''})`);
fs.writeFileSync(path.join(OUT, 'desktop-findings.md'), lines.join('\n') + '\n');
console.log(`done: ${events.length} events, ${shotN} screenshots → ${OUT}`);
process.exit(0);
