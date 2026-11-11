// Atlas Bot's own browser on the PC: a real Chromium window per running bot job, driven by the bot's browser_* tools.
// It keeps its own sign-ins (a separate, persistent session: never the user's Chrome), saves downloads into the job's
// folder (Documents/Atlas Bot/<job>), is hidden unless the user wants to watch or take over, and refuses the things
// the user must do themselves (passwords, CAPTCHAs). Sending, posting, buying, deleting and similar wait for the user's
// OK: `check()` says which actions need it, and the bot's approval gate (bots.js) asks before running them.
'use strict';
const { BrowserWindow, session, app } = require('electron');
const path = require('path');
const fs = require('fs');

const PARTITION = 'persist:atlas-bot';
const MAX_WINDOWS = 2;
const TEXT_MAX = 6000, ITEMS_MAX = 150;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const jobs = new Map();   // job id → { win, title, folder, downloads: [{name, file, state}], created }
let ses = null, quitting = false;
app.on('before-quit', () => { quitting = true; });

// ---------------------------------------------------------------- what counts as risky (the user says yes first)

const RISKY_WORDS = /\b(send|post|publish|share|tweet|reply|comment|buy|purchase|pay|payment|checkout|check out|place (your )?order|order now|complete (your )?order|submit order|confirm|delete|unsubscribe|subscribe|sign ?up|register|create (an |your )?account|join|accept|agree|transfer|donate|book|reserve|deactivate|close (my |your )?account|cancel (my |your )?(account|subscription|order|plan)|change (my |your )?password)\b/i;
const RISKY_PATHS = /(checkout|payment|\/pay\b|purchase|\/order|place-?order|delete|remove|subscribe|signup|sign-up|register|\/post\b|compose|\/send\b|message|transfer|donate|booking)/i;

/** Pure: a sentence saying why a browser action needs the user's OK, or null. [d] describes the element (see DESCRIBE). */
function riskOf(name, args, d) {
  if (name === 'browser_upload') return `Upload ${path.basename(String(args.file || 'a file'))} to ${d && d.host ? d.host : 'this site'}`;
  if (!d || !d.found) return null;
  const site = d.host ? ` on ${d.host}` : '';
  const said = (s) => `“${String(s).slice(0, 60)}”`;
  if (name === 'browser_click') {
    if (RISKY_WORDS.test(d.label || '')) return `Click ${said(d.label)}${site}`;
    if (d.submits && (RISKY_WORDS.test(d.formSubmit || '') || RISKY_PATHS.test(d.formAction || ''))) return `Submit the form${d.formSubmit ? ' ' + said(d.formSubmit) : ''}${site}`;
    if (d.href && RISKY_PATHS.test(d.href) && /delete|remove|unsubscribe|transfer|pay/i.test(d.href)) return `Open ${said(d.label || d.href)}${site}`;
    return null;
  }
  if ((name === 'browser_type' && args.submit) || name === 'browser_press') {
    if (name === 'browser_press' && !/^(enter|return)$/i.test(String(args.key || '').trim())) return null;
    if (d.inForm && (RISKY_WORDS.test(d.formSubmit || '') || RISKY_PATHS.test(d.formAction || ''))) return `Submit the form${d.formSubmit ? ' ' + said(d.formSubmit) : ''}${site}`;
    return null;
  }
  return null;
}

// ---------------------------------------------------------------- scripts run inside the page

// Shared helpers: what an element is called, whether it can be seen, finding one by number or by its words.
const PRELUDE = `
const clean = (s) => String(s || '').replace(/\\s+/g, ' ').trim();
const shown = (el) => { const r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2) return false; const s = getComputedStyle(el); return s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05; };
const labelOf = (el) => {
  let t = el.getAttribute('aria-label') || '';
  if (!t && el.labels && el.labels.length) t = [...el.labels].map((l) => l.innerText).join(' ');
  if (!t && el.getAttribute('aria-labelledby')) t = el.getAttribute('aria-labelledby').split(/\\s+/).map((id) => (document.getElementById(id) || {}).innerText || '').join(' ');
  if (!t && !['INPUT', 'SELECT', 'TEXTAREA'].includes(el.tagName)) t = el.innerText || el.textContent || '';
  if (!t && el.tagName === 'INPUT' && ['submit', 'button', 'reset'].includes(el.type)) t = el.value;
  if (!t) t = el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') || ((el.querySelector && el.querySelector('img[alt]')) || {}).alt || el.getAttribute('name') || '';
  return clean(t).slice(0, 80);
};
const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[role=textbox],[contenteditable=""],[contenteditable=true]';
const tagAll = () => {
  document.querySelectorAll('[data-atlas-id]').forEach((e) => e.removeAttribute('data-atlas-id'));
  let n = 0; const list = [];
  for (const el of document.querySelectorAll(SEL)) {
    if (n >= ${ITEMS_MAX} || !shown(el) || el.closest('[aria-hidden=true]') || el.disabled) continue;
    el.setAttribute('data-atlas-id', String(++n)); list.push(el);
  }
  return list;
};
const find = (t) => {
  let all = [...document.querySelectorAll('[data-atlas-id]')];
  if (!all.length) all = tagAll();
  const s = String(t == null ? '' : t).trim().replace(/^\\[|\\]$/g, '');
  if (/^\\d+$/.test(s)) return document.querySelector('[data-atlas-id="' + Number(s) + '"]');
  const want = clean(s).toLowerCase(); if (!want) return null;
  return all.find((e) => labelOf(e).toLowerCase() === want) || all.find((e) => labelOf(e).toLowerCase().includes(want)) || null;
};
const kindOf = (el) => {
  const tag = el.tagName, role = el.getAttribute('role'), type = (el.getAttribute('type') || '').toLowerCase();
  if (tag === 'A' || role === 'link') return 'link';
  if (tag === 'SELECT' || role === 'combobox') return 'dropdown';
  if (tag === 'TEXTAREA' || el.isContentEditable || role === 'textbox') return 'text box';
  if (tag === 'INPUT') {
    if (type === 'password') return 'password field';
    if (['checkbox', 'radio'].includes(type)) return type;
    if (type === 'file') return 'file upload';
    if (['submit', 'button', 'reset', 'image'].includes(type)) return 'button';
    return (type && type !== 'text' ? type + ' ' : '') + 'field';
  }
  if (role === 'checkbox' || role === 'radio' || role === 'switch') return role;
  return 'button';
};
`;

const READ = (max) => `(() => { ${PRELUDE}
  const list = tagAll();
  const items = list.map((el, i) => {
    const k = kindOf(el); let s = '[' + (i + 1) + '] ' + k + ' "' + labelOf(el) + '"';
    if (el.tagName === 'INPUT' && !['password', 'checkbox', 'radio', 'file', 'submit', 'button'].includes(el.type) && el.value) s += ' (filled: "' + clean(el.value).slice(0, 60) + '")';
    if (el.tagName === 'TEXTAREA' && el.value) s += ' (filled: "' + clean(el.value).slice(0, 60) + '")';
    if (['checkbox', 'radio'].includes(el.type)) s += el.checked ? ' (ticked)' : ' (not ticked)';
    if (el.tagName === 'SELECT') { const o = [...el.options]; s += ' (chosen: "' + clean((o[el.selectedIndex] || {}).text) + '"; options: ' + o.slice(0, 12).map((x) => clean(x.text)).filter(Boolean).join(' | ') + (o.length > 12 ? ' | …' : '') + ')'; }
    if (el.tagName === 'A') { const h = el.getAttribute('href') || ''; if (h && !h.startsWith('javascript')) s += ' → ' + h.slice(0, 80); }
    if (el.type === 'file' && el.files && el.files.length) s += ' (attached: ' + [...el.files].map((f) => f.name).join(', ') + ')';
    return s;
  });
  const text = clean((document.body && document.body.innerText) || '').slice(0, ${max});
  const captcha = !!document.querySelector('iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="challenges.cloudflare"],iframe[title*="challenge" i],.g-recaptcha,.h-captcha,#cf-challenge-running,[data-sitekey]');
  const password = [...document.querySelectorAll('input[type=password]')].some(shown);
  return { title: document.title, url: location.href, text, items, password, captcha,
    more: Math.max(0, document.documentElement.scrollHeight - window.innerHeight - window.scrollY) > 40, at: window.scrollY };
})()`;

const DESCRIBE = (target, scroll) => `(() => { ${PRELUDE}
  const el = find(${JSON.stringify(target)}); if (!el) return { found: false };
  if (${scroll ? 'true' : 'false'}) el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  const r = el.getBoundingClientRect(); const form = el.form || el.closest('form');
  let formSubmit = '';
  if (form) { const b = form.querySelector('button[type=submit],input[type=submit],button:not([type])'); if (b) formSubmit = labelOf(b); }
  const type = (el.getAttribute('type') || '').toLowerCase();
  const submits = !!form && ((el.tagName === 'BUTTON' && (!type || type === 'submit')) || (el.tagName === 'INPUT' && ['submit', 'image'].includes(type)));
  const x = r.left + r.width / 2, y = r.top + r.height / 2;
  const top = document.elementFromPoint(x, y);
  return { found: true, id: el.getAttribute('data-atlas-id'), label: labelOf(el), kind: kindOf(el), tag: el.tagName, type,
    password: type === 'password', file: type === 'file', href: el.tagName === 'A' ? el.href : '', host: location.host,
    inForm: !!form, submits, formAction: form ? (form.getAttribute('action') || location.pathname) : '', formSubmit,
    x, y, hit: !!top && (top === el || el.contains(top)), inView: y >= 0 && y <= window.innerHeight && x >= 0 && x <= window.innerWidth };
})()`;

const ARM_JS = (target) => `(() => { ${PRELUDE} window.__atlasClicked = false; const el = find(${JSON.stringify(target)}); if (el) el.addEventListener('click', () => { window.__atlasClicked = true; }, { once: true, capture: true }); })()`;
const CLICK_JS = (target) => `(() => { ${PRELUDE} const el = find(${JSON.stringify(target)}); if (!el) return false; el.click(); return true; })()`;
const FOCUS_JS = (target) => `(() => { ${PRELUDE}
  const el = find(${JSON.stringify(target)}); if (!el) return { ok: false };
  el.scrollIntoView({ block: 'center', behavior: 'instant' }); el.focus();
  if (el.isContentEditable) { const r = document.createRange(); r.selectNodeContents(el); const s = getSelection(); s.removeAllRanges(); s.addRange(r); }
  else if (typeof el.select === 'function') el.select();
  return { ok: document.activeElement === el || el.contains(document.activeElement) };
})()`;
// For sites that don't take typed text (some React fields): set the value the way the page's own code would see it.
const SETVALUE_JS = (target, text) => `(() => { ${PRELUDE}
  const el = find(${JSON.stringify(target)}); if (!el) return false;
  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  if (el.isContentEditable) el.textContent = ${JSON.stringify(text)};
  else Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(text)});
  el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`;
const VALUE_JS = (target) => `(() => { ${PRELUDE} const el = find(${JSON.stringify(target)}); return el ? (el.isContentEditable ? el.textContent : el.value) : null; })()`;
const CHANGE_JS = (target) => `(() => { ${PRELUDE} const el = find(${JSON.stringify(target)}); if (el) el.dispatchEvent(new Event('change', { bubbles: true })); })()`;
const SELECT_JS = (target, option) => `(() => { ${PRELUDE}
  const el = find(${JSON.stringify(target)}); if (!el) return { ok: false, why: 'not found' };
  if (el.tagName !== 'SELECT') return { ok: false, why: 'not a dropdown' };
  const want = clean(${JSON.stringify(option)}).toLowerCase(); const o = [...el.options];
  const pick = o.find((x) => clean(x.text).toLowerCase() === want || x.value.toLowerCase() === want) || o.find((x) => clean(x.text).toLowerCase().includes(want));
  if (!pick) return { ok: false, why: 'no option like that', options: o.map((x) => clean(x.text)).slice(0, 30) };
  el.value = pick.value; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
  return { ok: true, chose: clean(pick.text) };
})()`;
const FOCUSED_JS = `(() => { ${PRELUDE}
  const el = document.activeElement; if (!el || el === document.body) return { found: false };
  if (!el.getAttribute('data-atlas-id')) el.setAttribute('data-atlas-id', 'f');
  const form = el.form || el.closest('form'); let formSubmit = '';
  if (form) { const b = form.querySelector('button[type=submit],input[type=submit],button:not([type])'); if (b) formSubmit = labelOf(b); }
  return { found: true, label: labelOf(el), inForm: !!form, formAction: form ? (form.getAttribute('action') || location.pathname) : '', formSubmit, host: location.host };
})()`;

// ---------------------------------------------------------------- windows, one per job

function safeName(s) {
  const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 50).trim();
  return t || 'Job';
}
function folderFor(jobId, title) {
  const dir = path.join(app.getPath('documents'), 'Atlas Bot', safeName(title) + ' (' + String(jobId).slice(0, 6) + ')');
  return dir;
}
function botSession() {
  if (ses) return ses;
  ses = session.fromPartition(PARTITION);
  // look like the Chromium it is, without naming Atlas or Electron (some sites turn those away)
  ses.setUserAgent(ses.getUserAgent().replace(/\s(atlas|Atlas)\/[\d.]+/g, '').replace(/\sElectron\/[\d.]+/g, ''));
  // the bot never gets the camera, microphone, location or notifications
  ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));
  ses.setPermissionCheckHandler(() => false);
  ses.on('will-download', (_e, item, wc) => {
    const entry = [...jobs.values()].find((j) => j.win && !j.win.isDestroyed() && j.win.webContents === wc);
    if (!entry) { item.cancel(); return; }
    fs.mkdirSync(entry.folder, { recursive: true });
    let name = safeName(item.getFilename()).slice(0, 80) || 'download';
    let file = path.join(entry.folder, name), k = 1;
    while (fs.existsSync(file)) { const ext = path.extname(name); file = path.join(entry.folder, `${path.basename(name, ext)} (${++k})${ext}`); }
    item.setSavePath(file);
    const d = { name: path.basename(file), file, state: 'downloading', bytes: 0 };
    entry.downloads.push(d);
    item.on('updated', () => { d.bytes = item.getReceivedBytes(); });
    item.once('done', (_ev, state) => { d.state = state; d.bytes = item.getReceivedBytes(); });
  });
  return ses;
}

function ensure(jobId, title) {
  let e = jobs.get(jobId);
  if (e && e.win && !e.win.isDestroyed()) return e;
  // at most two bot windows: the oldest one nobody is watching goes first
  const open = [...jobs.entries()].filter(([, v]) => v.win && !v.win.isDestroyed());
  if (open.length >= MAX_WINDOWS) {
    const [oldId] = open.sort((a, b) => a[1].created - b[1].created).find(([, v]) => !v.win.isVisible()) || open[0];
    close(oldId);
  }
  const win = new BrowserWindow({
    width: 1280, height: 900, show: false, title: 'Atlas Bot',
    autoHideMenuBar: true, backgroundColor: '#ffffff',
    webPreferences: { session: botSession(), contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false, spellcheck: false },
  });
  const t = String(title || '').slice(0, 60);
  win.on('page-title-updated', (ev, pt) => { ev.preventDefault(); win.setTitle(`Atlas Bot · ${t}${pt ? ' · ' + pt : ''}`); });
  win.setTitle(`Atlas Bot · ${t}`);
  // closing the window while watching only hides it: the bot keeps its page
  win.on('close', (ev) => { const x = jobs.get(jobId); if (x && !x.closing && !quitting) { ev.preventDefault(); win.hide(); } });
  // new tabs/pop-ups open in the same window, so the bot never loses its place
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) setImmediate(() => { if (!win.isDestroyed()) win.loadURL(url).catch(() => {}); }); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (ev, url) => { if (!/^(https?:|about:blank|data:)/i.test(url)) ev.preventDefault(); });
  win.webContents.on('will-redirect', (ev, url) => { if (!/^(https?:|about:blank)/i.test(url)) ev.preventDefault(); });
  e = { win, title: t, folder: folderFor(jobId, t), downloads: [], created: Date.now(), closing: false };
  jobs.set(jobId, e);
  win.loadURL('about:blank').catch(() => {});
  return e;
}

function close(jobId) {
  const e = jobs.get(jobId); if (!e) return;
  e.closing = true;
  try { if (e.win && !e.win.isDestroyed()) e.win.destroy(); } catch {}
  jobs.delete(jobId);
}

/** Show the window to the user (Watch / Take Over). */
function show(jobId, title) {
  const e = ensure(jobId, title);
  e.win.show(); e.win.focus();
  return true;
}

// ---------------------------------------------------------------- waiting for pages

async function settle(wc, quiet = 500, timeout = 15000) {
  await sleep(120);
  const t0 = Date.now();
  while (!wc.isDestroyed() && wc.isLoading() && Date.now() - t0 < timeout) await sleep(120);
  await sleep(quiet);
}

async function js(wc, code) { return wc.executeJavaScript(code, true); }

async function summary(e, note = '') {
  const wc = e.win.webContents;
  let r;
  try { r = await js(wc, READ(TEXT_MAX)); } catch (err) { return { out: `${note ? note + '\n' : ''}The page couldn't be read yet (${err.message}). Wait a moment and read it again.` }; }
  if (!r.url || r.url === 'about:blank') return { out: (note ? note + '\n' : '') + 'The browser is on a blank page. Open a page with browser_open.', url: '' };
  const lines = [];
  if (note) lines.push(note);
  lines.push(`Page: ${r.title || '(no title)'}`, `Address: ${r.url}`);
  const dl = e.downloads.slice(-3).map((d) => `${d.name} (${d.state === 'completed' ? 'saved' : d.state})`);
  if (dl.length) lines.push(`Downloads in the job folder: ${dl.join(', ')}`);
  if (r.captcha) lines.push('THIS PAGE HAS A CAPTCHA / "are you human" check. Never try to solve it: call browser_handover so the user does it.');
  if (r.password) lines.push('This page asks for a password. Never type passwords: if the user saved a sign-in for this site (vault_list), use browser_fill_login; otherwise call browser_handover so they sign in themselves.');
  // the things to use come first, so a short excerpt of this result (as a bot keeps between rounds) still has them
  lines.push('', `Things you can use (${r.items.length}${r.items.length >= ITEMS_MAX ? ', the first ' + ITEMS_MAX : ''}; use the number as target):`, ...(r.items.length ? r.items : ['(none)']));
  lines.push('', 'Text on the page:', r.text || '(none)');
  if (r.more) lines.push('', '(There is more below: browser_scroll down to see it.)');
  return { out: lines.join('\n'), url: r.url, title: r.title };
}

// ---------------------------------------------------------------- real clicks and keys

async function realClick(wc, x, y) {
  const p = { x: Math.round(x), y: Math.round(y) };
  wc.sendInputEvent({ type: 'mouseMove', ...p });
  wc.sendInputEvent({ type: 'mouseDown', ...p, button: 'left', clickCount: 1 });
  await sleep(40);
  wc.sendInputEvent({ type: 'mouseUp', ...p, button: 'left', clickCount: 1 });
}
const KEYS = { enter: 'Enter', return: 'Enter', tab: 'Tab', escape: 'Escape', esc: 'Escape', backspace: 'Backspace', delete: 'Delete', space: 'Space',
  up: 'Up', down: 'Down', left: 'Left', right: 'Right', arrowup: 'Up', arrowdown: 'Down', arrowleft: 'Left', arrowright: 'Right', pageup: 'PageUp', pagedown: 'PageDown', home: 'Home', end: 'End' };
async function pressKey(wc, key) {
  const k = KEYS[String(key || '').toLowerCase().replace(/\s+/g, '')];
  if (!k) return false;
  wc.sendInputEvent({ type: 'keyDown', keyCode: k });
  if (k === 'Enter') wc.sendInputEvent({ type: 'char', keyCode: '\r' });
  if (k === 'Space') wc.sendInputEvent({ type: 'char', keyCode: ' ' });
  wc.sendInputEvent({ type: 'keyUp', keyCode: k });
  return true;
}

// ---------------------------------------------------------------- the tools

function inFolder(e, file) {
  const f = path.resolve(path.isAbsolute(String(file)) ? String(file) : path.join(e.folder, String(file)));
  const root = path.resolve(e.folder) + path.sep;
  return f.startsWith(root) && fs.existsSync(f) && fs.statSync(f).isFile() ? f : null;
}

/** Runs one browser tool for a job. Returns {out, step?, image?, url?, handover?}. */
async function run(jobId, title, name, args = {}) {
  const e = ensure(jobId, title);
  const wc = e.win.webContents;
  const target = args.target;
  const notFound = () => ({ out: `There's nothing numbered or labelled "${target}" on the page now. Read the page again (browser_read) and use a number from the list.` });
  switch (name) {
    case 'browser_open': {
      let url = String(args.url || '').trim();
      if (!url) return { out: 'Error: give the address to open.' };
      if (!/^[a-z][\w+.-]*:/i.test(url)) url = 'https://' + url;
      if (!/^https?:\/\//i.test(url)) return { out: 'Error: only web addresses (http or https) can be opened.' };
      try { await wc.loadURL(url); } catch (err) { if (!/ERR_ABORTED/.test(err.message)) { await settle(wc, 200, 3000); const s = await summary(e); return { ...s, out: `The page didn't load cleanly (${err.code || err.message}).\n` + s.out }; } }
      await settle(wc);
      const s = await summary(e);
      return { ...s, step: 'Opened ' + url.replace(/^https?:\/\//, '').slice(0, 60) };
    }
    case 'browser_read': return { ...(await summary(e)), step: null };
    // >>> vault: a saved sign-in goes straight into the page (vault.js); the bot never sees it
    case 'browser_fill_login': {
      const r = await require('./vault').fillLogin(wc, args.entry);
      if (/^Error/.test(r.out)) return r;
      await sleep(250);
      const s = await summary(e);
      return { ...s, out: r.out + '\n\n' + s.out, step: r.step };
    }
    // <<< vault
    case 'browser_click': {
      const d = await js(wc, DESCRIBE(target, true)); if (!d.found) return notFound();
      if (d.file) return { out: `[${d.id}] is a file upload: use browser_upload with a file from the job folder.` };
      const before = wc.getURL();
      await sleep(80);
      const again = await js(wc, DESCRIBE(target, false));
      // a real mouse click where the element is, the way a person clicks; if something covers it, click it directly
      let clicked = false;
      if (again.found && again.hit && again.inView) {
        // a real mouse click where the element is, the way a person clicks; then make sure the page actually got it
        await js(wc, ARM_JS(target)).catch(() => {});
        await realClick(wc, again.x, again.y);
        await sleep(150);
        clicked = wc.getURL() !== before || wc.isLoading() || (await js(wc, 'window.__atlasClicked === true').catch(() => true));
      }
      // covered by something, off screen, or the click didn't land (a hidden window can miss the first one): click it directly
      if (!clicked) await js(wc, CLICK_JS(target));
      await settle(wc);
      const moved = wc.getURL() !== before;
      const s = await summary(e, `Clicked [${d.id}] ${d.kind} "${d.label}".${moved ? ' The page changed.' : ''}`);
      return { ...s, step: `Clicked “${(d.label || d.kind).slice(0, 50)}”` };
    }
    case 'browser_type': {
      const d = await js(wc, DESCRIBE(target, true)); if (!d.found) return notFound();
      if (d.password) return { out: 'Passwords are never typed by Atlas Bot. Call browser_handover so the user signs in themselves, then carry on.' };
      const text = String(args.text ?? '');
      const f = await js(wc, FOCUS_JS(target));
      if (!f.ok) return { out: `Couldn't put the cursor in [${d.id}] "${d.label}". Read the page again and try another field.` };
      if (text) wc.insertText(text); else wc.sendInputEvent({ type: 'keyDown', keyCode: 'Backspace' }), wc.sendInputEvent({ type: 'keyUp', keyCode: 'Backspace' });
      await sleep(120);
      let val = await js(wc, VALUE_JS(target));
      if (val !== text && val != null) { await js(wc, SETVALUE_JS(target, text)); val = await js(wc, VALUE_JS(target)); }
      await js(wc, CHANGE_JS(target));
      let note = `Typed into [${d.id}] ${d.kind} "${d.label}".` + (val === text ? '' : ` (The field now shows: "${String(val).slice(0, 80)}".)`);
      if (args.submit) { await js(wc, FOCUS_JS(target)).catch(() => {}); await pressKey(wc, 'enter'); note += ' Pressed Enter.'; }
      await settle(wc, args.submit ? 600 : 150);
      const s = await summary(e, note);
      return { ...s, step: `Typed into “${(d.label || d.kind).slice(0, 40)}”${args.submit ? ' and pressed Enter' : ''}` };
    }
    case 'browser_select': {
      const r = await js(wc, SELECT_JS(target, String(args.option ?? '')));
      if (!r.ok) return { out: r.why === 'not found' ? notFound().out : r.why === 'not a dropdown' ? `[${target}] isn't a dropdown: click it instead.` : `There's no option like "${args.option}". The options are: ${(r.options || []).join(' | ')}` };
      await settle(wc, 300);
      const s = await summary(e, `Chose "${r.chose}".`);
      return { ...s, step: `Chose “${r.chose.slice(0, 50)}”` };
    }
    case 'browser_press': {
      if (!(await pressKey(wc, args.key))) return { out: `Error: unknown key "${args.key}". Use Enter, Tab, Escape, Backspace, Up, Down, Left, Right, PageUp, PageDown, Home or End.` };
      await settle(wc, 400);
      const s = await summary(e, `Pressed ${args.key}.`);
      return { ...s, step: 'Pressed ' + args.key };
    }
    case 'browser_scroll': {
      const dir = String(args.direction || 'down').toLowerCase();
      const code = dir === 'top' ? 'window.scrollTo(0,0)' : dir === 'bottom' ? 'window.scrollTo(0,document.documentElement.scrollHeight)' : `window.scrollBy(0, ${dir === 'up' ? -1 : 1} * Math.round(window.innerHeight * 0.85))`;
      await js(wc, code); await sleep(400);
      return { ...(await summary(e, `Scrolled ${dir}.`)), step: null };
    }
    case 'browser_back': {
      if (!wc.navigationHistory.canGoBack()) return { out: "There's no page to go back to." };
      wc.navigationHistory.goBack(); await settle(wc);
      return { ...(await summary(e, 'Went back.')), step: 'Went back a page' };
    }
    case 'browser_screenshot': {
      const img = await e.win.webContents.capturePage();
      if (img.isEmpty()) return { out: "Couldn't take a picture of the page. Use browser_read instead." };
      const sz = img.getSize();
      const small = sz.width > 1280 ? img.resize({ width: 1280 }) : img;
      return { out: `Here is the page as it looks now (${wc.getURL()}).`, image: { mime: 'image/jpeg', base64: small.toJPEG(75).toString('base64') }, step: 'Looked at the page' };
    }
    case 'browser_wait': {
      const want = String(args.text || '').trim().toLowerCase();
      const secs = Math.min(10, Math.max(0.5, Number(args.seconds) || (want ? 10 : 2)));
      const t0 = Date.now();
      let seen = !want;
      while (Date.now() - t0 < secs * 1000) {
        await sleep(300);
        if (want) { const t = await js(wc, '(document.body && document.body.innerText || "").toLowerCase()').catch(() => ''); if (t.includes(want)) { seen = true; break; } }
      }
      await settle(wc, 200, 5000);
      return { ...(await summary(e, want ? (seen ? `“${args.text}” is on the page now.` : `“${args.text}” didn't appear within ${secs} s.`) : `Waited ${secs} s.`)), step: null };
    }
    case 'browser_download': {
      let url = String(args.url || '').trim();
      if (!url && target != null) { const d = await js(wc, DESCRIBE(target, false)); if (!d.found) return notFound(); url = d.href; if (!url) return { out: `[${d.id}] isn't a link: click it instead, and the download (if it starts) lands in the job folder.` }; }
      if (!/^https?:\/\//i.test(url)) return { out: 'Error: give a web address or the number of a download link.' };
      const before = e.downloads.length;
      wc.downloadURL(url);
      const t0 = Date.now();
      while (Date.now() - t0 < 120000) {
        await sleep(250);
        const d = e.downloads[before];
        if (d && d.state !== 'downloading') {
          if (d.state !== 'completed') return { out: `The download stopped (${d.state}).` };
          return { out: `Downloaded ${d.name} (${Math.round(d.bytes / 1024)} KB) into the job folder: ${d.file}`, step: 'Downloaded ' + d.name, file: d.file };
        }
      }
      return { out: 'The download is still going after 2 minutes; it will keep saving into the job folder.' };
    }
    case 'browser_upload': {
      const d = await js(wc, DESCRIBE(target, true)); if (!d.found) return notFound();
      if (!d.file) return { out: `[${d.id}] isn't a file upload.` };
      const f = inFolder(e, args.file);
      if (!f) return { out: `That file isn't in the job folder (${e.folder}). Only files there can be uploaded.` };
      const dbg = wc.debugger;
      try {
        if (!dbg.isAttached()) dbg.attach('1.3');
        const { root } = await dbg.sendCommand('DOM.getDocument', { depth: 0 });
        const sel = d.id === 'f' ? '[data-atlas-id="f"]' : `[data-atlas-id="${d.id}"]`;
        const { nodeId } = await dbg.sendCommand('DOM.querySelector', { nodeId: root.nodeId, selector: sel });
        if (!nodeId) return notFound();
        await dbg.sendCommand('DOM.setFileInputFiles', { files: [f], nodeId });
      } finally { try { dbg.detach(); } catch {} }
      await settle(wc, 300);
      return { ...(await summary(e, `Attached ${path.basename(f)} to [${d.id}].`)), step: 'Attached ' + path.basename(f) };
    }
    case 'browser_handover': {
      show(jobId, title);
      const why = String(args.reason || 'Something here needs you.').trim().slice(0, 300);
      return { out: 'The user has the browser window now.', handover: `${why} Please finish this in the Atlas Bot browser window, then tap Hand Back.`, step: 'Handed the browser to you: ' + why.slice(0, 80) };
    }
  }
  return { out: `Error: there's no browser tool called ${name}.` };
}

/** Whether [name] with [args] needs the user's OK first (a sentence), or null. Looks at the actual element on the page. */
async function check(jobId, title, name, args = {}) {
  if (name === 'browser_fill_login') {   // vault: asks unless the user allowed this sign-in on this site
    const e = jobs.get(jobId); let host = '';
    try { host = new URL(e && e.win && !e.win.isDestroyed() ? e.win.webContents.getURL() : '').hostname; } catch {}
    return require('./vault').needsOk(args.entry, host);
  }
  if (!['browser_click', 'browser_type', 'browser_press', 'browser_upload'].includes(name)) return null;
  if (name === 'browser_type' && !args.submit) return null;
  if (name === 'browser_press' && !/^(enter|return)$/i.test(String(args.key || '').trim())) return null;
  const e = jobs.get(jobId); if (!e || !e.win || e.win.isDestroyed()) return name === 'browser_upload' ? riskOf(name, args, null) : null;
  let d;
  try { d = await js(e.win.webContents, name === 'browser_press' ? FOCUSED_JS : DESCRIBE(args.target, false)); } catch { return null; }
  return riskOf(name, args, d);
}

function info(jobId) {
  const e = jobs.get(jobId);
  return e && e.win && !e.win.isDestroyed() ? { open: true, visible: e.win.isVisible(), url: e.win.webContents.getURL(), folder: e.folder } : { open: false };
}

module.exports = { run, check, show, close, info, riskOf, safeName, PARTITION, jobs };
