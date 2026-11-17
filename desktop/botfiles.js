// What Atlas Bot makes on the PC: its job folder (Documents/Atlas Bot/<job>, shared with its browser's downloads),
// documents (a styled PDF plus the Markdown), spreadsheets (a real .xlsx plus a .csv), interactive pages (one
// self-contained .html), and the Library that lists everything in those folders.
// Runs in the main process. Every name a bot gives is reduced to a plain file name inside its own job folder, so a
// bot can't read or write anywhere else. Pages open in Atlas's own viewer, which has no internet and no access to
// the PC: a page can run its own script (charts, filters) but can't reach anything.
'use strict';
const { app, BrowserWindow, session, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const sheets = require('./sheets');
const { safeName } = require('./botbrowser');

const MAX_READ = 20000;           // characters a bot reads from one file at a time
const MAX_WRITE = 1024 * 1024;    // a bot writes text files up to 1 MB
const MAX_PAGE = 2 * 1024 * 1024; // a page's own HTML up to 2 MB
const MAX_ROWS = 20000;
const TEXT_EXT = new Set(['.txt', '.md', '.csv', '.json', '.html', '.htm', '.xml', '.log', '.tsv', '.yaml', '.yml']);

const base = () => path.join(app.getPath('documents'), 'Atlas Bot');
/** The job's folder: the same one its browser downloads into (botbrowser.js). */
function folderFor(jobId, goal) { return path.join(base(), safeName(goal) + ' (' + String(jobId).slice(0, 6) + ')'); }

/** A bot's file name → a safe plain name (no folders), keeping a sensible extension. */
function fileName(name, fallbackExt = '.txt') {
  const raw = path.basename(String(name || '').replace(/\\/g, '/'));
  let ext = path.extname(raw).toLowerCase().replace(/[^\w.]/g, '').slice(0, 10);
  const stem = safeName(path.basename(raw, path.extname(raw)));
  if (!ext) ext = fallbackExt;
  return (stem === 'Job' && !raw.trim() ? 'file' : stem) + ext;
}
/** A new name that doesn't overwrite an existing file: "Report.pdf", "Report (2).pdf", ... */
function freshPath(dir, name) {
  let file = path.join(dir, name), k = 1;
  const ext = path.extname(name), stem = path.basename(name, ext);
  while (fs.existsSync(file)) file = path.join(dir, `${stem} (${++k})${ext}`);
  return file;
}
/** True if [file] is inside the Atlas Bot folder (and not the folder itself). */
function inside(file) {
  const b = path.resolve(base()) + path.sep;
  const f = path.resolve(String(file || ''));
  return f.startsWith(b) && f.length > b.length;
}
const fmtSize = (n) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${Math.round(n / 1024)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

// ---------------------------------------------------------------- the Library's index (what bots made, and for which job)

const indexFile = () => path.join(app.getPath('userData'), 'library.json');
function readIndex() { try { const a = JSON.parse(fs.readFileSync(indexFile(), 'utf8')); return Array.isArray(a) ? a : []; } catch { return []; } }
function writeIndex(list) {
  const f = indexFile(), tmp = f + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(list.slice(-2000)), 'utf8'); fs.renameSync(tmp, f);
}
function remember(entry) { const list = readIndex().filter((x) => x.file !== entry.file); list.push(entry); writeIndex(list); }

// ---------------------------------------------------------------- the job folder

function listFiles(dir) {
  try { return fs.readdirSync(dir, { withFileTypes: true }).filter((d) => d.isFile() && !d.name.startsWith('.')).map((d) => { const st = fs.statSync(path.join(dir, d.name)); return { name: d.name, size: st.size, at: st.mtimeMs }; }); }
  catch { return []; }
}

// ---------------------------------------------------------------- documents: Markdown → a styled PDF

const FONT_DIR = path.join(__dirname, 'renderer', 'fonts');
const fontUrl = (f) => 'file:///' + path.join(FONT_DIR, f).replace(/\\/g, '/').replace(/^\/+/, '');
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/** The printed page: Newsreader for reading, Geist for small labels, the Atlas palette on white paper. */
function documentHtml(title, bodyHtml, when) {
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: file:; style-src 'unsafe-inline'; font-src file:">
<style>
@font-face{font-family:Newsreader;src:url('${fontUrl('newsreader_regular.ttf')}');font-weight:400}
@font-face{font-family:Newsreader;src:url('${fontUrl('newsreader_medium.ttf')}');font-weight:500}
@font-face{font-family:Newsreader;src:url('${fontUrl('newsreader_semibold.ttf')}');font-weight:600 700}
@font-face{font-family:Newsreader;src:url('${fontUrl('newsreader_italic.ttf')}');font-style:italic}
@font-face{font-family:Geist;src:url('${fontUrl('geist_regular.ttf')}');font-weight:400}
@font-face{font-family:Geist;src:url('${fontUrl('geist_semibold.ttf')}');font-weight:600}
@font-face{font-family:GeistMono;src:url('${fontUrl('geist_mono.ttf')}')}
@page{size:A4;margin:22mm 20mm 22mm 20mm}
html{-webkit-print-color-adjust:exact;print-color-adjust:exact}
body{font:400 11.5pt/1.55 Newsreader,Georgia,serif;color:#14152E;margin:0}
.kicker{font:600 8pt Geist,sans-serif;letter-spacing:.14em;text-transform:uppercase;color:#4A32C8;margin:0 0 6pt}
h1.doc{font:500 25pt/1.12 Newsreader,serif;margin:0 0 4pt;color:#0A0B1F}
.meta{font:400 8.5pt Geist,sans-serif;color:#4B4F7A;margin:0 0 18pt;padding-bottom:10pt;border-bottom:1.2pt solid #4A32C8}
h1,h2,h3,h4{font-family:Newsreader,serif;font-weight:500;color:#0A0B1F;break-after:avoid;page-break-after:avoid}
h1{font-size:19pt;margin:18pt 0 6pt} h2{font-size:15.5pt;margin:16pt 0 5pt} h3{font-size:13pt;margin:13pt 0 4pt} h4{font-size:11.5pt;margin:11pt 0 3pt}
p{margin:0 0 8pt} ul,ol{margin:0 0 8pt 0;padding-left:18pt} li{margin:2pt 0}
a{color:#4A32C8;text-decoration:none} strong,b{font-weight:600}
blockquote{margin:8pt 0;padding:2pt 0 2pt 10pt;border-left:2.5pt solid #8B6CFF;color:#3E4170}
code{font:9.5pt GeistMono,monospace;background:#EEF0FB;padding:0 2pt;border-radius:2pt}
pre{font:9pt/1.45 GeistMono,monospace;background:#F3F4FC;border:0.6pt solid #D3D5F2;border-radius:4pt;padding:8pt;white-space:pre-wrap;break-inside:avoid}
pre code{background:none;padding:0}
table{border-collapse:collapse;width:100%;margin:6pt 0 10pt;font:9.5pt/1.4 Geist,sans-serif;break-inside:auto}
th{background:#ECEBFB;color:#0A0B1F;font-weight:600;text-align:left}
th,td{border:0.6pt solid #D3D5F2;padding:4pt 6pt;vertical-align:top} tr{break-inside:avoid}
hr{border:none;border-top:0.8pt solid #D3D5F2;margin:12pt 0}
img{max-width:100%}
</style></head><body>
<div class="kicker">Atlas Bot</div><h1 class="doc">${esc(title)}</h1><div class="meta">${esc(when)}</div>
${bodyHtml}
</body></html>`;
}

let docSession = null;
/** A private session for printing documents and showing pages: nothing from the internet, no permissions. */
function offlineSession(name) {
  const s = session.fromPartition(name);
  s.webRequest.onBeforeRequest((d, cb) => cb({ cancel: !/^(file|data|blob|devtools|chrome-extension):/i.test(d.url) || (/^file:/i.test(d.url) && !okFileUrl(d.url)) }));
  s.setPermissionRequestHandler((_wc, _p, cb) => cb(false));
  s.setPermissionCheckHandler(() => false);
  return s;
}
/** Pages and documents may load Atlas's own fonts and the file itself, nothing else on the PC. */
function okFileUrl(url) {
  try {
    const p = path.resolve(decodeURIComponent(new URL(url).pathname).replace(/^\/([A-Za-z]:)/, '$1'));
    return p.startsWith(path.resolve(FONT_DIR) + path.sep) || p.startsWith(path.resolve(base()) + path.sep) || p.startsWith(path.resolve(os.tmpdir()) + path.sep);
  } catch { return false; }
}

async function printPdf(html) {
  if (!docSession) docSession = offlineSession('atlas-docs');
  const tmp = path.join(os.tmpdir(), `atlas-doc-${process.pid}-${Date.now()}.html`);
  fs.writeFileSync(tmp, html, 'utf8');
  // the page itself can't run script (its CSP blocks it); Atlas only uses script to wait for the fonts
  const win = new BrowserWindow({ show: false, width: 900, height: 1200, webPreferences: { session: docSession, javascript: true, sandbox: true, contextIsolation: true, nodeIntegration: false } });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  try {
    await win.loadFile(tmp);
    await Promise.race([win.webContents.executeJavaScript('document.fonts ? document.fonts.ready.then(() => 1) : 1', true).catch(() => {}), new Promise((r) => setTimeout(r, 3000))]);
    return await win.webContents.printToPDF({ pageSize: 'A4', printBackground: true, preferCSSPageSize: true });
  } finally { win.destroy(); fs.rm(tmp, { force: true }, () => {}); }
}

// ---------------------------------------------------------------- interactive pages

const TEMPLATE = path.join(__dirname, 'renderer', 'templates', 'page.html');
/** A page from a plain description (title, text, tables, charts): Atlas's own template draws it, with sorting, a filter and charts. */
function pageFromSpec(spec) {
  const tpl = fs.readFileSync(TEMPLATE, 'utf8');
  const data = JSON.stringify(cleanSpec(spec)).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  return tpl.replace('/*ATLAS_PAGE_DATA*/null', () => data).replace('<title>Atlas Page</title>', () => `<title>${esc(spec.title || 'Atlas Page')}</title>`);
}
/** Only the parts the template knows, with sane sizes. */
function cleanSpec(s) {
  const str = (v, n) => String(v ?? '').slice(0, n);
  const out = { title: str(s.title, 200), intro: str(s.intro || s.text, 20000), sections: [] };
  for (const t of (Array.isArray(s.tables) ? s.tables : []).slice(0, 12)) {
    const columns = (Array.isArray(t.columns) ? t.columns : []).slice(0, 40).map((c) => str(c, 120));
    const rows = (Array.isArray(t.rows) ? t.rows : []).slice(0, 5000).map((r) => (Array.isArray(r) ? r : []).slice(0, 40).map((c) => (typeof c === 'number' ? c : str(c, 1000))));
    out.sections.push({ type: 'table', title: str(t.title, 200), columns, rows });
  }
  for (const c of (Array.isArray(s.charts) ? s.charts : []).slice(0, 12)) {
    const labels = (Array.isArray(c.labels) ? c.labels : []).slice(0, 200).map((x) => str(x, 80));
    const series = (Array.isArray(c.series) ? c.series : []).slice(0, 8).map((x) => ({ name: str(x && x.name, 80), values: (Array.isArray(x && x.values) ? x.values : []).slice(0, 200).map((v) => (Number.isFinite(+v) ? +v : 0)) }));
    out.sections.push({ type: 'chart', title: str(c.title, 200), kind: ['bar', 'line', 'pie'].includes(c.type || c.kind) ? (c.type || c.kind) : 'bar', labels, series });
  }
  return out;
}

let pageSession = null;
/** Opens a page in Atlas's own viewer: its script runs, but it has no internet, no Node, no permissions. */
function openPage(file) {
  if (!pageSession) pageSession = offlineSession('atlas-pages');
  const win = new BrowserWindow({ width: 1100, height: 820, title: path.basename(file), autoHideMenuBar: true, backgroundColor: '#ffffff',
    webPreferences: { session: pageSession, sandbox: true, contextIsolation: true, nodeIntegration: false, javascript: true } });
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); });
  win.loadFile(file);
  return win;
}

// ---------------------------------------------------------------- the tools

/**
 * Runs one of Atlas Bot's file tools for a job. Returns { out, step?, made? } (made: the new file's name, for the report).
 * makeHtml: the document's body as HTML (the renderer turns the bot's Markdown into HTML with Atlas's own renderer).
 */
async function run(jobId, goal, name, args = {}) {
  const dir = folderFor(jobId, goal);
  const when = new Date().toLocaleString(undefined, { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  switch (name) {
    case 'workspace_list': {
      const files = listFiles(dir);
      if (!files.length) return { out: "This job's folder is empty. Files you make, write or download go here." };
      return { out: "Files in this job's folder:\n" + files.map((f) => `- ${f.name} (${fmtSize(f.size)})`).join('\n'), step: 'Looked at its files' };
    }
    case 'workspace_read': {
      const n = fileName(args.name);
      const f = path.join(dir, n);
      if (!fs.existsSync(f)) return { out: `There's no file called "${n}" in this job's folder. Use workspace_list to see what's there.` };
      const ext = path.extname(n).toLowerCase();
      if (!TEXT_EXT.has(ext)) return { out: `"${n}" isn't a text file, so it can't be read here (you can still upload it on a website or mention it in the report).` };
      const text = fs.readFileSync(f, 'utf8');
      const from = Math.max(0, Number(args.from) || 0);
      const part = text.slice(from, from + MAX_READ);
      const more = text.length > from + MAX_READ ? `\n\n[${text.length - from - MAX_READ} more characters: read again with from=${from + MAX_READ}]` : '';
      return { out: part + more, step: 'Read ' + n };
    }
    case 'workspace_write': {
      const n = fileName(args.name);
      const ext = path.extname(n).toLowerCase();
      if (!TEXT_EXT.has(ext)) return { out: 'Only text files can be written this way (.txt, .md, .csv, .json, .html…). For a PDF use make_document; for Excel use make_spreadsheet.' };
      const text = String(args.text ?? '');
      if (Buffer.byteLength(text) > MAX_WRITE) return { out: 'That is too long for one file (1 MB at most). Split it.' };
      fs.mkdirSync(dir, { recursive: true });
      const f = path.join(dir, n);
      const existed = fs.existsSync(f);
      fs.writeFileSync(f, args.append && existed ? fs.readFileSync(f, 'utf8') + text : text, 'utf8');
      remember({ file: f, title: n, kind: 'file', job: jobId, goal: String(goal || ''), at: Date.now() });
      // a working file, not a deliverable: it shows in the Library, but the report's file list is for what make_* produced
      return { out: `${existed ? (args.append ? 'Added to' : 'Replaced') : 'Wrote'} ${n} in this job's folder.`, step: 'Wrote ' + n };
    }
    case 'make_document': {
      const title = String(args.title || 'Document').slice(0, 160);
      const md = String(args.markdown || args.text || '');
      if (!md.trim()) return { out: 'The document is empty: give it the text in Markdown.' };
      fs.mkdirSync(dir, { recursive: true });
      const pdf = await printPdf(documentHtml(title, String(args.html || ''), when));
      const pf = freshPath(dir, fileName(title, '.pdf').replace(/\.[^.]+$/, '') + '.pdf');
      fs.writeFileSync(pf, pdf);
      const mf = pf.replace(/\.pdf$/, '.md');
      fs.writeFileSync(mf, `# ${title}\n\n${md}\n`, 'utf8');
      remember({ file: pf, title, kind: 'document', job: jobId, goal: String(goal || ''), at: Date.now() });
      return { out: `Made the document "${path.basename(pf)}" (PDF, ${fmtSize(pdf.length)}), with the text also saved as ${path.basename(mf)}.`, step: 'Made a document: ' + title.slice(0, 60), made: path.basename(pf) };
    }
    case 'make_spreadsheet': {
      const title = String(args.title || 'Spreadsheet').slice(0, 160);
      const list = Array.isArray(args.sheets) && args.sheets.length ? args.sheets : [{ name: args.sheet || title, columns: args.columns, rows: args.rows }];
      const book = [];
      for (const s of list.slice(0, 20)) {
        const columns = (Array.isArray(s.columns) ? s.columns : []).map((c) => String(c ?? ''));
        let rows = Array.isArray(s.rows) ? s.rows : (typeof s.csv === 'string' ? sheets.parseCsv(s.csv) : []);
        if (!columns.length && typeof s.csv === 'string') rows = rows.slice(0); // the CSV's first row is the header
        rows = rows.slice(0, MAX_ROWS).map((r) => (Array.isArray(r) ? r : Object.values(r || {})).map((v) => (v == null ? '' : typeof v === 'number' ? String(v) : String(v))));
        book.push({ name: s.name || title, rows: columns.length ? [columns, ...rows] : rows });
      }
      if (!book.some((s) => s.rows.length)) return { out: 'The spreadsheet is empty: give it columns and rows.' };
      fs.mkdirSync(dir, { recursive: true });
      const xf = freshPath(dir, fileName(title, '.xlsx').replace(/\.[^.]+$/, '') + '.xlsx');
      fs.writeFileSync(xf, sheets.xlsx(book));
      const cf = xf.replace(/\.xlsx$/, '.csv');
      fs.writeFileSync(cf, '﻿' + sheets.toCsv(book[0].rows), 'utf8');
      remember({ file: xf, title, kind: 'spreadsheet', job: jobId, goal: String(goal || ''), at: Date.now() });
      const rowsN = book.reduce((n, s) => n + Math.max(0, s.rows.length - 1), 0);
      return { out: `Made the spreadsheet "${path.basename(xf)}" (Excel, ${book.length} sheet${book.length === 1 ? '' : 's'}, ${rowsN} rows), plus ${path.basename(cf)} for the first sheet.`, step: 'Made a spreadsheet: ' + title.slice(0, 60), made: path.basename(xf) };
    }
    case 'make_page': {
      const title = String(args.title || 'Page').slice(0, 160);
      let html;
      if (typeof args.html === 'string' && args.html.trim()) {
        if (Buffer.byteLength(args.html) > MAX_PAGE) return { out: 'That page is too big (2 MB at most).' };
        html = args.html;
      } else {
        const spec = { title, intro: args.intro || args.text || '', tables: args.tables, charts: args.charts };
        if (!spec.intro && !(spec.tables || []).length && !(spec.charts || []).length) return { out: 'The page is empty: give it some text, tables or charts.' };
        html = pageFromSpec(spec);
      }
      fs.mkdirSync(dir, { recursive: true });
      const f = freshPath(dir, fileName(title, '.html').replace(/\.[^.]+$/, '') + '.html');
      fs.writeFileSync(f, html, 'utf8');
      remember({ file: f, title, kind: 'page', job: jobId, goal: String(goal || ''), at: Date.now() });
      return { out: `Made the page "${path.basename(f)}". It opens in Atlas's viewer from the Library.`, step: 'Made a page: ' + title.slice(0, 60), made: path.basename(f) };
    }
    default: return { out: 'Error: there is no such file tool.' };
  }
}

// ---------------------------------------------------------------- the Library

const KIND_BY_EXT = { '.zip': 'project', '.pdf': 'document', '.xlsx': 'spreadsheet', '.csv': 'spreadsheet', '.html': 'page', '.htm': 'page', '.md': 'text', '.txt': 'text', '.json': 'text' };
/** Everything in the Atlas Bot folders, newest first, with what made it (index) where known. */
function library() {
  const idx = new Map(readIndex().map((x) => [path.resolve(x.file), x]));
  const out = [];
  let dirs = [];
  try { dirs = fs.readdirSync(base(), { withFileTypes: true }).filter((d) => d.isDirectory()); } catch { return []; }
  for (const d of dirs) {
    const dir = path.join(base(), d.name);
    for (const f of listFiles(dir)) {
      const file = path.join(dir, f.name);
      const i = idx.get(path.resolve(file));
      // a document's Markdown and a spreadsheet's CSV twin sit beside the main file: shown with it, not as their own rows
      const ext = path.extname(f.name).toLowerCase();
      const twin = (ext === '.md' && fs.existsSync(file.replace(/\.md$/i, '.pdf'))) || (ext === '.csv' && fs.existsSync(file.replace(/\.csv$/i, '.xlsx')));
      if (twin && !i) continue;
      out.push({ file, name: f.name, title: i ? i.title : f.name, kind: i ? i.kind : (KIND_BY_EXT[ext] || 'download'), made: !!i,
        job: i ? i.job : null, goal: i ? i.goal : d.name.replace(/\s*\([\w-]{1,6}\)$/, ''), at: i ? i.at : f.at, size: f.size });
    }
  }
  return out.sort((a, b) => b.at - a.at);
}
function open(file) {
  if (!inside(file) || !fs.existsSync(file)) return false;
  if (/\.html?$/i.test(file)) { openPage(file); return true; }
  shell.openPath(file); return true;
}
function showInFolder(file) { if (!inside(file) || !fs.existsSync(file)) return false; shell.showItemInFolder(file); return true; }
function remove(file) {
  if (!inside(file)) return false;
  try { fs.rmSync(file, { force: true }); } catch { return false; }
  writeIndex(readIndex().filter((x) => path.resolve(x.file) !== path.resolve(file)));
  return true;
}

// >>> attachments: files sent in the chat are kept in the Library (Documents/Atlas Bot/Uploads)
function keepUpload(file, title) { remember({ file, title, kind: 'file', job: null, goal: 'Sent in the chat', at: Date.now() }); }
// <<< attachments
// >>> projects (projects.js): a finished project's zip goes in the Library, under Documents/Atlas Bot/Projects
function saveProjectZip(projectName, buf) {
  const dir = path.join(base(), 'Projects');
  fs.mkdirSync(dir, { recursive: true });
  const f = freshPath(dir, fileName(projectName, '.zip').replace(/\.[^.]+$/, '') + '.zip');
  fs.writeFileSync(f, buf);
  remember({ file: f, title: projectName, kind: 'project', job: null, goal: 'Projects', at: Date.now() });
  return { file: f, name: path.basename(f) };
}
// <<< projects

module.exports = { keepUpload, saveProjectZip, run, library, open, showInFolder, remove, folderFor, fileName, freshPath, inside, cleanSpec, documentHtml, pageFromSpec, NAMES: ['workspace_list', 'workspace_read', 'workspace_write', 'make_document', 'make_spreadsheet', 'make_page'] };
