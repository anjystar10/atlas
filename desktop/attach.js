// Attachments on the PC (the twin of the phone's Attach.kt / AttachSend.kt): any file, any size.
// - take(): what a picked or dropped file is (renderer/attachrules.js decides how it's read). Anything but a picture
//   is copied, streamed, into Documents/Atlas Bot/Uploads (it shows in the Library and tools can use it), with
//   progress, cancellable, after a free-space check. Word, Excel and PowerPoint get their text read into a sidecar.
// - bytes(): a small file's bytes for an inline part (the renderer only asks for files within the inline limit).
// - geminiUpload(): Gemini's Files API, resumable protocol, streamed from disk, waiting until the file is ACTIVE.
// - read() / search(): file_read and file_search, part by part, for files too long to send whole.
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const https = require('https');
const crypto = require('crypto');
const { app } = require('electron');

const MB = 1024 * 1024;
const INLINE_MAX = 14 * MB;
const UPLOAD_MAX = 2048 * MB;
const RESERVE = 200 * MB;
const PART_MAX = 128 * MB;            // one XML part of an Office file, unpacked (a doctored zip can unpack to gigabytes)
const READ_MAX = 60000;

const base = () => path.join(app.getPath('documents'), 'Atlas Bot');
const uploads = () => path.join(base(), 'Uploads');
const sideDir = () => path.join(app.getPath('userData'), 'attach-text');
/** Where the text read out of an Office file is kept (by the file's path, so tools find it from the path alone). */
const sidecar = (p) => path.join(sideDir(), crypto.createHash('sha1').update(path.resolve(p)).digest('hex') + '.txt');
const inside = (p, dir) => { const d = path.resolve(dir) + path.sep; const f = path.resolve(String(p || '')); return f.startsWith(d) && f.length > d.length; };
/** Files the tools may read: the Atlas Bot folder (uploads, bot files) and the sidecars. */
const readable = (p) => (inside(p, base()) || inside(p, sideDir())) && fs.existsSync(p) && fs.statSync(p).isFile();

const copies = new Map();   // id → { cancel }

/** Copies [src] into Uploads, streamed. [onProgress](done, total). Resolves to the new path. */
function copyIn(id, src, name, onProgress) {
  return new Promise((resolve, reject) => {
    const total = fs.statSync(src).size;
    fs.mkdirSync(uploads(), { recursive: true });
    let free = Infinity; try { const s = fs.statfsSync(uploads()); free = s.bavail * s.bsize; } catch {}
    if (total + RESERVE > free) return reject(new Error(`Not enough room on this PC for “${name}” (${(total / MB).toFixed(1)} MB).`));
    const { freshPath, fileName } = require('./botfiles');
    const dest = freshPath(uploads(), fileName(name, ''));
    const rs = fs.createReadStream(src, { highWaterMark: 1 * MB }); const ws = fs.createWriteStream(dest);
    let done = 0, last = 0, stopped = false;
    const fail = (e) => { rs.destroy(); ws.destroy(); fs.rm(dest, { force: true }, () => {}); copies.delete(id); reject(e); };
    copies.set(id, { cancel: () => { stopped = true; fail(new Error('Stopped.')); } });
    rs.on('data', (c) => { done += c.length; const now = Date.now(); if (now - last > 200) { last = now; onProgress(done, total); } });
    rs.on('error', fail); ws.on('error', fail);
    ws.on('finish', () => { if (stopped) return; copies.delete(id); onProgress(total, total); resolve(dest); });
    rs.pipe(ws);
  });
}
function cancel(id) { const c = copies.get(id); if (c) c.cancel(); }

// ---------------------------------------------------------------- zips (Office files, archives), read from disk

/** The central directory: [{ name, method, csize, size, local }]. Only the end of the file is read. */
function zipEntries(p) {
  const fd = fs.openSync(p, 'r');
  try {
    const size = fs.fstatSync(fd).size; const tail = Math.min(size, 65557 + 22);
    const end = Buffer.alloc(tail); fs.readSync(fd, end, 0, tail, size - tail);
    let eocd = -1; for (let i = tail - 22; i >= 0; i--) if (end.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw new Error('not a zip');
    const count = end.readUInt16LE(eocd + 10), cdSize = end.readUInt32LE(eocd + 12), cdOff = end.readUInt32LE(eocd + 16);
    const cd = Buffer.alloc(cdSize); fs.readSync(fd, cd, 0, cdSize, cdOff);
    const out = []; let o = 0;
    for (let n = 0; n < count && o + 46 <= cd.length; n++) {
      const nlen = cd.readUInt16LE(o + 28), xlen = cd.readUInt16LE(o + 30), clen = cd.readUInt16LE(o + 32);
      out.push({ name: cd.toString('utf8', o + 46, o + 46 + nlen), method: cd.readUInt16LE(o + 10), csize: cd.readUInt32LE(o + 20), size: cd.readUInt32LE(o + 24), local: cd.readUInt32LE(o + 42) });
      o += 46 + nlen + xlen + clen;
    }
    return out;
  } finally { fs.closeSync(fd); }
}
/** One entry's bytes, unpacked, stopping at [max]: streamed from disk, so a huge member never fills memory. */
function zipRead(p, e, max = PART_MAX) {
  return new Promise((resolve, reject) => {
    const fd = fs.openSync(p, 'r'); const h = Buffer.alloc(30);
    try { fs.readSync(fd, h, 0, 30, e.local); } finally { fs.closeSync(fd); }
    const start = e.local + 30 + h.readUInt16LE(26) + h.readUInt16LE(28);
    if (!e.csize) return resolve(Buffer.alloc(0));
    const rs = fs.createReadStream(p, { start, end: start + e.csize - 1 });
    const src = e.method === 0 ? rs : rs.pipe(zlib.createInflateRaw());
    const parts = []; let n = 0, over = false;
    const finish = () => resolve(Buffer.concat(parts).subarray(0, max));
    src.on('data', (d) => { if (over) return; parts.push(d); n += d.length; if (n >= max) { over = true; rs.destroy(); if (src !== rs) src.destroy(); finish(); } });
    src.on('end', () => { if (!over) finish(); });
    src.on('error', (err) => { if (!over) reject(err); });
    rs.on('error', (err) => { if (!over) reject(err); });
  });
}
const unxml = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_m, d) => String.fromCodePoint(+d)).replace(/&amp;/g, '&');
const num = (re) => (e) => +(re.exec(e.name) || [])[1];

/** Writes the text inside a Word, Excel or PowerPoint file to its sidecar; returns the sidecar's path. */
async function officeText(p, kind) {
  const entries = zipEntries(p); const out = [];
  const get = (name) => entries.find((e) => e.name === name);
  if (kind === 'docx') {
    const e = get('word/document.xml');
    if (e) out.push(unxml((await zipRead(p, e)).toString('utf8').replace(/<w:tab\/>/g, '\t').replace(/<w:br\/>/g, '\n').replace(/<\/w:p>/g, '\n').replace(/<[^>]+>/g, '')));
  } else if (kind === 'pptx') {
    const re = /^ppt\/slides\/slide(\d+)\.xml$/;
    const slides = entries.filter((e) => re.test(e.name)).sort((a, b) => num(re)(a) - num(re)(b));
    for (const [i, e] of slides.entries()) out.push(`## Slide ${i + 1}\n` + unxml((await zipRead(p, e)).toString('utf8').replace(/<\/a:p>/g, '\n').replace(/<[^>]+>/g, '')));
  } else if (kind === 'xlsx') {
    const ss = get('xl/sharedStrings.xml');
    const shared = ss ? [...(await zipRead(p, ss)).toString('utf8').matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) => unxml(m[1].replace(/<[^>]+>/g, ''))) : [];
    const wb = get('xl/workbook.xml');
    const names = wb ? [...(await zipRead(p, wb)).toString('utf8').matchAll(/<sheet [^>]*name="([^"]*)"/g)].map((m) => unxml(m[1])) : [];
    const re = /^xl\/worksheets\/sheet(\d+)\.xml$/;
    const sheets = entries.filter((e) => re.test(e.name)).sort((a, b) => num(re)(a) - num(re)(b));
    for (const [i, e] of sheets.entries()) {
      const rows = [...(await zipRead(p, e)).toString('utf8').matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)].map((r) =>
        [...r[1].matchAll(/<c([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)].map((c) => {
          const t = (/ t="(\w+)"/.exec(c[1]) || [])[1]; const inner = c[2] || '';
          const v = unxml(((/<v>([\s\S]*?)<\/v>/.exec(inner) || [])[1]) ?? (inner.replace(/<[^>]+>/g, '')));
          return (t === 's' ? shared[+v] ?? '' : v).replace(/[\t\n]/g, ' ');
        }).join('\t'));
      out.push(`## Sheet: ${names[i] || 'Sheet ' + (i + 1)}\n` + rows.join('\n'));
    }
  }
  fs.mkdirSync(sideDir(), { recursive: true });
  const sc = sidecar(p); fs.writeFileSync(sc, out.join('\n\n').replace(/\n{3,}/g, '\n\n'), 'utf8');
  return sc;
}

/** An archive's contents, for the message. */
function listing(p) {
  const all = zipEntries(p).filter((e) => !e.name.endsWith('/'));
  return { count: all.length, lines: all.slice(0, 300).map((e) => `  ${e.name}  (${size(e.size)})`).join('\n') + (all.length > 300 ? `\n  …and ${all.length - 300} more` : '') };
}

const size = (b) => (b >= 1024 * MB ? `${(b / (1024 * MB)).toFixed(1)} GB` : b >= MB ? `${(b / MB).toFixed(1)} MB` : b >= 1024 ? `${Math.floor(b / 1024)} KB` : `${b} bytes`);
function strings(buf, min = 5, max = 4000) {
  let out = '', run = '';
  const flush = () => { if (run.length >= min && out.length < max) out += (out ? ' ' : '') + run.slice(0, max - out.length); run = ''; };
  for (const b of buf) { if ((b >= 32 && b <= 126) || b === 9) run += String.fromCharCode(b); else flush(); }
  flush(); return out;
}
const hex = (buf, max = 256) => [...buf.subarray(0, max)].map((b) => b.toString(16).padStart(2, '0')).reduce((rows, h, i) => { if (i % 16 === 0) rows.push([]); rows[rows.length - 1].push(h); return rows; }, []).map((r) => r.join(' ')).join('\n');
function head(p, n = 16384) { const fd = fs.openSync(p, 'r'); try { const b = Buffer.alloc(Math.min(n, fs.fstatSync(fd).size)); fs.readSync(fd, b, 0, b.length, 0); return b; } finally { fs.closeSync(fd); } }

/** Characters of text, exact when small; Infinity once surely past the inline limit. */
function chars(p) {
  const sz = fs.statSync(p).size;
  if (sz > 120000 * 4) return Infinity;
  return fs.readFileSync(p, 'utf8').length;
}

const textOf = (p) => (fs.existsSync(sidecar(p)) ? sidecar(p) : p);

// ---------------------------------------------------------------- reading part by part (file_read / file_search)

const TEXTY = /\.(txt|md|markdown|csv|tsv|json|jsonl|xml|ya?ml|html?|log|ini|toml|cfg|conf|env|properties|gradle|kts?|java|py|m?js|cjs|tsx?|jsx|c|h|cpp|hpp|cs|go|rs|rb|php|swift|sh|bash|zsh|ps1|bat|sql|lua|dart|s?css|svg|tex|srt|vtt|rtf|r|m|scala|pl|vue|svelte|ipynb)$/i;
function chunkCut(text, size) {   // the same rule as attachrules.js chunkStarts: end at a line break in the last eighth
  if (text.length <= size) return text.length;
  const nl = text.lastIndexOf('\n', size - 1);
  return nl > size - Math.floor(size / 8) ? nl + 1 : size;
}
async function read(p, offset = 0, length = 20000, member = '') {
  if (!readable(p)) return 'Error: that isn\'t an attached or Library file Atlas can read.';
  length = Math.max(1, Math.min(READ_MAX, length | 0)); offset = Math.max(0, offset | 0);
  if (member) {
    const e = zipEntries(p).find((x) => x.name === member); if (!e) return `There's no "${member}" in ${path.basename(p)}.`;
    const b = (await zipRead(p, e, offset + length)).subarray(offset, offset + length);
    return TEXTY.test(member) || strings(b).length > b.length / 2 ? `[${member}, bytes ${offset}–${offset + b.length} of ${e.size}]\n${b.toString('utf8')}`
      : `[${member}, bytes ${offset}–${offset + b.length} of ${e.size}: not text]\nReadable text: ${strings(b)}\nHex:\n${hex(b)}`;
  }
  const src = textOf(p); const asText = src !== p || TEXTY.test(p) || path.basename(p).toLowerCase().match(/^(dockerfile|makefile|readme|license)$/);
  const total = fs.statSync(src).size;
  if (offset >= total) return `That's past the end: the ${asText ? 'text' : 'file'} is ${total} bytes.`;
  const want = asText ? length + Math.floor(length / 8) : length;
  const buf = Buffer.alloc(Math.min(want, total - offset));
  const fd = fs.openSync(src, 'r'); try { fs.readSync(fd, buf, 0, buf.length, offset); } finally { fs.closeSync(fd); }
  const name = path.basename(p);
  if (!asText) return `[${name}, bytes ${offset}–${offset + buf.length} of ${total}]\nReadable text: ${strings(buf)}\nHex:\n${hex(buf)}` + (offset + buf.length < total ? `\n(next: offset=${offset + buf.length})` : '');
  let s = 0; while (s < buf.length && s < 3 && (buf[s] & 0xc0) === 0x80) s++;
  const win = buf.subarray(s).toString('utf8');
  const part = offset + buf.length >= total ? win : win.slice(0, chunkCut(win, Math.min(win.length, length)));
  const end = offset + s + Buffer.byteLength(part, 'utf8');
  return `[${name}, bytes ${offset}–${end} of ${total}]\n${part}` + (end < total ? `\n(next: offset=${end})` : '\n(end of file)');
}
function search(p, query, max = 30) {
  if (!readable(p)) return Promise.resolve('Error: that isn\'t an attached or Library file Atlas can read.');
  const words = String(query || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return Promise.resolve('Say what to look for.');
  max = Math.max(1, Math.min(100, max | 0));
  const src = textOf(p); const name = path.basename(p);
  return new Promise((resolve, reject) => {
    let at = 0, lineNo = 0, found = 0, rest = Buffer.alloc(0); const hits = [];
    const rs = fs.createReadStream(src, { highWaterMark: 1 * MB });
    const line = (b) => { lineNo++; const t = b.subarray(0, 65536).toString('utf8');
      if (found < max && words.every((w) => t.toLowerCase().includes(w))) { found++; hits.push(`line ${lineNo} @offset ${at}: ${t.trim().slice(0, 300)}`); }
      at += b.length + 1; };
    const done = () => resolve(found ? `Lines with "${query}" in ${name}:\n${hits.join('\n')}\n` + (found >= max ? `(stopped at ${max}; search again with more words, or read on with file_read)` : '') : `No line of ${name} has all of: ${query}.`);
    rs.on('data', (chunk) => {
      let buf = rest.length ? Buffer.concat([rest, chunk]) : chunk; let i;
      while ((i = buf.indexOf(10)) >= 0) { line(buf.subarray(0, i)); buf = buf.subarray(i + 1); if (found >= max) { rs.destroy(); return done(); } }
      rest = Buffer.from(buf);
    });
    rs.on('end', () => { if (rest.length) line(rest); done(); });
    rs.on('error', reject);
  });
}

// ---------------------------------------------------------------- Gemini Files API (resumable upload, streamed)

const uploaded = new Map();
function req(url, opts, body) {
  return new Promise((resolve, reject) => {
    const r = https.request(url, opts, (res) => { const parts = []; res.on('data', (d) => parts.push(d)); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(parts).toString('utf8') })); });
    r.on('error', reject);
    if (body && typeof body.pipe === 'function') body.pipe(r); else r.end(body);
  });
}
async function geminiUpload(key, p, mime, name, onProgress = () => {}) {
  const st = fs.statSync(p); const id = `${crypto.createHash('sha1').update(key).digest('hex')}|${p}|${st.size}|${st.mtimeMs}`;
  const was = uploaded.get(id); if (was && Date.now() - was.at < 24 * 3600e3) return was.uri;
  if (st.size > UPLOAD_MAX) throw new Error("over Gemini's 2 GB limit");
  const G = 'https://generativelanguage.googleapis.com';
  const s = await req(`${G}/upload/v1beta/files`, { method: 'POST', headers: { 'x-goog-api-key': key, 'X-Goog-Upload-Protocol': 'resumable', 'X-Goog-Upload-Command': 'start',
    'X-Goog-Upload-Header-Content-Length': String(st.size), 'X-Goog-Upload-Header-Content-Type': mime, 'Content-Type': 'application/json' } }, JSON.stringify({ file: { display_name: String(name).slice(0, 120) } }));
  const to = s.headers['x-goog-upload-url'];
  if (s.status >= 300 || !to) throw new Error(`Gemini wouldn't take the upload (${s.status}) ${s.text.slice(0, 200)}`);
  const rs = fs.createReadStream(p, { highWaterMark: 1 * MB }); let done = 0;
  rs.on('data', (c) => { done += c.length; onProgress(done, st.size); });
  const u = await req(to, { method: 'POST', headers: { 'Content-Length': String(st.size), 'X-Goog-Upload-Offset': '0', 'X-Goog-Upload-Command': 'upload, finalize' } }, rs);
  if (u.status >= 300) throw new Error(`the upload to Gemini failed (${u.status}) ${u.text.slice(0, 200)}`);
  let file = JSON.parse(u.text).file || {};
  const until = Date.now() + 10 * 60e3;
  while (file.state === 'PROCESSING') {
    if (Date.now() > until) throw new Error('Gemini is still processing it after 10 minutes');
    await new Promise((r) => setTimeout(r, 3000));
    const g = await req(`${G}/v1beta/${file.name}`, { method: 'GET', headers: { 'x-goog-api-key': key } });
    if (g.status < 300) file = JSON.parse(g.text);
  }
  if (file.state === 'FAILED' || !file.uri) throw new Error("Gemini couldn't process it");
  uploaded.set(id, { uri: file.uri, at: Date.now() });
  return file.uri;
}

function bytes(p) {
  if (!readable(p)) throw new Error('not an attached file');
  if (fs.statSync(p).size > INLINE_MAX) throw new Error('too big to send inline');
  return fs.readFileSync(p).toString('base64');
}
function describeHead(p) { try { return strings(head(p), 5, 1500); } catch { return ''; } }

module.exports = { copyIn, cancel, officeText, listing, chars, read, search, geminiUpload, bytes, describeHead, readable, sidecar, uploads, zipEntries, chunkCut };
