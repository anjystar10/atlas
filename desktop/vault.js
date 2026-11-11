// The vault on the PC: sign-ins Atlas Bot may use in its own browser (site, username, password, notes).
// Encrypted with Windows' own protection for this user (Electron safeStorage = DPAPI), kept in Atlas's data folder,
// never synced. Passwords stay in this (main) process: the app's page gets names, sites and usernames only, a password
// only when the user asks to see it and confirms; the model gets names and sites. Filling a sign-in into the bot's
// browser happens here, straight into the page, and only on the entry's own site.
'use strict';
const { app, safeStorage, dialog, BrowserWindow } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const FILE = () => path.join(app.getPath('userData'), 'vault.bin');

function siteOf(s) {
  const t = String(s || '').trim().toLowerCase();
  let host = t;
  if (t.includes('://')) { try { host = new URL(t).hostname; } catch { host = t; } } else host = t.split('/')[0];
  return host.replace(/^www\./, '').replace(/^\.+|\.+$/g, '');
}
/** The page's [host] belongs to [site]: the same, or a subdomain of it (never a look-alike like site.evil.com). */
function siteMatches(site, host) {
  const s = siteOf(site), h = siteOf(host);
  return !!s && !!h && (h === s || h.endsWith('.' + s));
}
function find(entries, ref) {
  const r = String(ref || '').trim(); if (!r) return null;
  const byId = entries.find((e) => e.id === r); if (byId) return byId;
  const byName = entries.filter((e) => e.name.toLowerCase() === r.toLowerCase()); if (byName.length === 1) return byName[0];
  const bySite = entries.filter((e) => siteMatches(e.site, r)); return bySite.length === 1 ? bySite[0] : null;
}
function forModel(entries) {
  if (!entries.length) return "The vault is empty: the user hasn't saved any sign-ins for Atlas (they can in Me → Vault).";
  return 'Saved sign-ins (use browser_fill_login with the name, on that site\'s sign-in page):\n' + entries.map((e) => `- ${e.name} — ${e.site}${e.trusted ? ' (allowed without asking)' : ''}`).join('\n');
}

function available() { try { return safeStorage.isEncryptionAvailable(); } catch { return false; } }

function all() {
  try {
    if (!fs.existsSync(FILE())) return [];
    if (!available()) return [];
    const a = JSON.parse(safeStorage.decryptString(fs.readFileSync(FILE())));
    return Array.isArray(a) ? a : [];
  } catch { return []; }
}
function write(entries) {
  if (!available()) throw new Error("Windows can't protect the vault on this PC, so nothing was saved.");
  const tmp = FILE() + '.tmp';
  fs.writeFileSync(tmp, safeStorage.encryptString(JSON.stringify(entries)));
  fs.renameSync(tmp, FILE());
}

/** For the app's page: no passwords. */
function list() { return all().map(({ password, ...rest }) => ({ ...rest, hasPassword: !!password })); }

function save(e) {
  const entries = all();
  const name = String(e.name || '').trim().slice(0, 60), site = siteOf(e.site);
  const old = e.id ? entries.find((x) => x.id === e.id) : null;
  const password = e.password ? String(e.password) : old ? old.password : '';
  if (!name || !site || !password) throw new Error('A name, a site and a password are needed.');
  const entry = { id: old ? old.id : crypto.randomUUID(), name, site, username: String(e.username || '').trim(), password, notes: String(e.notes || '').trim().slice(0, 500), trusted: !!e.trusted, updated: Date.now() };
  write(old ? entries.map((x) => (x.id === old.id ? entry : x)) : [...entries, entry]);
  return true;
}
function remove(id) { write(all().filter((x) => x.id !== id)); return true; }

/** Shows a password after the user confirms in a Windows dialog (the page can't open one by itself). */
async function reveal(id, parent) {
  const e = all().find((x) => x.id === id); if (!e) return null;
  const r = await dialog.showMessageBox(parent || BrowserWindow.getFocusedWindow(), { type: 'warning', buttons: ['Show It', 'Cancel'], defaultId: 1, cancelId: 1,
    title: 'Atlas Vault', message: `Show the password for ${e.name}?`, detail: 'Make sure nobody else can see your screen.' });
  return r.response === 0 ? e.password : null;
}

/** The page script that puts the username and password into the sign-in form; it reports only what it found. */
function fillScript(user, pass) {
  return `(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); if (r.width < 2 || r.height < 2) return false; const s = getComputedStyle(e); return s.visibility !== 'hidden' && s.display !== 'none'; };
  const put = (e, v) => { e.focus(); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(e, v); e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); e.blur(); };
  const u = ${JSON.stringify(user)}, p = ${JSON.stringify(pass)};
  const pw = [...document.querySelectorAll('input[type=password]')].filter(vis)[0] || null;
  const root = (pw && pw.form) || document;
  const cands = [...root.querySelectorAll('input[type=email],input[type=text],input[type=tel],input:not([type])')].filter(vis);
  let user = cands.find((c) => /user|email|login|phone|account/i.test((c.autocomplete || '') + ' ' + (c.name || '') + ' ' + (c.id || ''))) || null;
  if (!user && pw) user = cands.filter((c) => c.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING).pop() || null;
  if (!user && !pw && cands.length) user = cands[0];
  if (user && u) put(user, u);
  if (pw && p) put(pw, p);
  return { ok: !!(user || pw), user: !!(user && u), pass: !!(pw && p) };
})()`;
}

/** Fills the entry named [ref] into the page in [wc], if the page is on its site. Returns the result for the bot (no values). */
async function fillLogin(wc, ref) {
  const e = find(all(), ref);
  if (!e) return { out: `Error: there's no saved sign-in called “${String(ref).slice(0, 40)}” (or more than one fits). Use vault_list for the names, or hand the browser to the user to sign in.` };
  let host = ''; try { host = new URL(wc.getURL()).hostname; } catch {}
  if (!siteMatches(e.site, host)) return { out: `Error: the saved sign-in “${e.name}” is for ${e.site}, but this page is ${host || 'not a website'}. Open ${e.site}'s sign-in page first.` };
  let r; try { r = await wc.executeJavaScript(fillScript(e.username, e.password), true); } catch { r = null; }
  if (!r || !r.ok) return { out: "Error: couldn't find the sign-in fields on this page. Open the sign-in page (it may be a step further, e.g. after the username)." };
  return { out: `Filled the saved sign-in “${e.name}”: username ${r.user ? 'filled' : 'not found'}, password ${r.pass ? 'filled' : 'not found (it may be on the next step)'}. Now click the sign-in / next button.`, step: `Filled your saved sign-in for ${e.site}` };
}

/** Whether filling [ref] on [host] needs the user's OK (a sentence), or null when they allowed it on that site. */
function needsOk(ref, host) {
  const e = find(all(), ref);
  if (e && e.trusted && siteMatches(e.site, host)) return null;
  return `Sign in${host ? ' on ' + siteOf(host) : ''} with your saved sign-in “${String(e ? e.name : ref).slice(0, 40)}”`;
}

module.exports = { siteOf, siteMatches, find, forModel, fillScript, list, save, remove, reveal, fillLogin, needsOk, forModelNow: () => forModel(all()) };
