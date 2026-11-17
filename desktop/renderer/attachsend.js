// Turning a message's attachments into what the chosen AI service gets (the PC twin of the phone's AttachSend.kt):
// inline parts, Gemini uploads, stills from a video, text in the message, or a short note telling Atlas how to read
// the rest with file_read / file_search. Nothing is refused: the worst case is a note saying what the file is.
import * as R from './attachrules.js';
import * as aware from './aware.js';

const host = window.atlasHost;

/** For a service of [p] (with [key]); [status](text) shows progress. Returns { media, text }. */
export async function prepare(atts, p, key, status = () => {}) {
  const media = []; let text = '';
  for (const a of atts) {
    const size = a.size ?? (a.base64 ? Math.floor(a.base64.length * 3 / 4) : 0);
    const r = R.route(a.kind, size, p.style, a.chars || (a.text ? a.text.length : 0));
    const name = a.name || 'file';
    try {
      if (r === 'native') media.push({ mime: a.mime, base64: a.base64 || await host.attach.bytes(a.path), name });
      else if (r === 'upload') {
        try {
          const id = 'up-' + Math.random().toString(36).slice(2);
          const off = progressTo(id, (d, t) => status(`Uploading ${name} · ${t ? Math.floor(d * 100 / t) : 0}%`));
          const uri = await host.attach.upload(key, a.path, a.mime, name, id).finally(off);
          media.push({ mime: a.mime, fileUri: uri, name });
        } catch (e) {
          const why = String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
          if (a.kind === 'video') text += await addFrames(a, name, media, `The upload to Gemini didn't work (${why}), so you see`, status);
          else text += describe(a, name, `It couldn't be uploaded to Gemini (${why}).`);
        }
      } else if (r === 'frames') text += await addFrames(a, name, media, p.style === 'gemini' ? "It's over Gemini's 2 GB limit, so you see" : `${p.name} can't watch video, so you see`, status);
      else if (r === 'text') text += `\n\n[File: ${name}]\n${a.text}\n[End of ${name}]`;
      else if (r === 'chunks') text += `\n\n[File: ${name} (${R.sizeText(size)}) is too long to include. Read it with file_search(file, query) and file_read(file, offset, length); start with file_read(file, 0) for an overview. file = "${a.path}"]`;
      else if (r === 'listing') text += `\n\n[Archive: ${name} (${R.sizeText(size)}), ${a.listing.count} files:\n${a.listing.lines}\nOpen one with file_read(file, member = its path); file = "${a.path}"]`;
      else text += describe(a, name, R.note(a.kind, r, p.name));
    } catch (e) { text += describe(a, name, `It couldn't be read (${e.message || e}).`); }
  }
  return { media, text };
}

function describe(a, name, why) {
  return `\n\n[File: ${name} (${a.mime}, ${R.sizeText(a.size || 0)}). ${why}${a.head ? ` Readable text near its start: "${a.head}".` : ''}${a.path ? ` You can look further with file_read(file, offset, length); file = "${a.path}"` : ''}]`;
}

const listeners = new Map();
let wired = false;
/** Calls [fn](done, total) for progress events of [id]; returns the unsubscribe. */
export function progressTo(id, fn) {
  if (!wired) { wired = true; host.attach.onProgress((i, d, t) => listeners.get(i)?.(d, t)); }
  listeners.set(id, fn); return () => listeners.delete(id);
}

const clock = (s) => { s = Math.floor(s); return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

async function addFrames(a, name, media, why, status) {
  status(`Looking through ${name}`);
  const shots = await frames(a.path).catch(() => []);
  if (!shots.length) return describe(a, name, "The video couldn't be opened to take stills from it.");
  for (const s of shots) media.push({ mime: 'image/jpeg', base64: s.base64, name: '' });
  return `\n\n[Video: ${name} (${R.sizeText(a.size || 0)}). ${why} ${shots.length} stills from it, at ${shots.map((s) => clock(s.t)).join(', ')}: not the motion or sound.]`;
}

/** [R.FRAMES] stills spread through a video on disk, at most 768 px, as JPEG. The video streams from disk (atlasfile:). */
export function frames(path) {
  return new Promise((resolve, reject) => {
    const v = document.createElement('video'); v.muted = true; v.preload = 'auto';
    const out = []; let i = 0, dur = 0, done = false;
    const end = (err) => { if (done) return; done = true; v.removeAttribute('src'); try { v.load(); } catch {} err && !out.length ? reject(err) : resolve(out); };
    const grab = () => {
      const k = Math.min(1, 768 / Math.max(v.videoWidth || 1, v.videoHeight || 1));
      const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(v.videoWidth * k)); c.height = Math.max(1, Math.round(v.videoHeight * k));
      c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
      out.push({ t: v.currentTime, base64: c.toDataURL('image/jpeg', 0.8).split(',')[1] });
    };
    const next = () => { if (i >= R.FRAMES || !isFinite(dur) || dur <= 0) return end(); v.currentTime = dur * (2 * i + 1) / (2 * R.FRAMES); i++; };
    v.onloadedmetadata = () => { dur = v.duration; if (!isFinite(dur) || dur <= 0) { v.currentTime = 0; } next(); };
    v.onseeked = () => { try { grab(); } catch (e) { return end(e); } next(); };
    v.onerror = () => end(new Error('unplayable'));
    setTimeout(() => end(new Error('timed out')), 60000);
    v.src = 'atlasfile://f/?p=' + encodeURIComponent(path);
  });
}

// file_read / file_search: reading attached and Library files part by part (chat and bots)
const fn = (name, description, properties, required) => ({ name, description, parameters: { type: 'object', properties, required } });
const TOOLS = [
  fn('file_read', 'Read part of a file the user attached (or a Library file): text from a byte offset, the text inside Word/Excel/PowerPoint files, a member of a zip, or for other files the readable text and hex at that offset. Use for files too long to have been included whole.',
    { file: { type: 'string', description: 'the file path given with the attachment' }, offset: { type: 'integer', description: 'byte offset to start at (0 = the start)' },
      length: { type: 'integer', description: 'bytes to read, up to 60000 (default 20000)' }, member: { type: 'string', description: 'for a zip: the path of the file inside to read' } }, ['file']),
  fn('file_search', 'Find the lines of an attached (or Library) file that contain all the given words, with the byte offset of each, to then read around them with file_read.',
    { file: { type: 'string', description: 'the file path given with the attachment' }, query: { type: 'string', description: 'words to look for' }, max: { type: 'integer', description: 'most lines to return (default 30)' } }, ['file', 'query']),
];
aware.register({ id: 'attachments', forBots: true, tools: () => TOOLS, run: async (call) => {
  const a = call.args || {}; const base = String(a.file || '').split(/[\\/]/).pop();
  if (call.name === 'file_read') return { out: await host.attach.read(a.file, a.offset, a.length, a.member), step: 'Read ' + base };
  if (call.name === 'file_search') return { out: await host.attach.search(a.file, a.query, a.max), step: 'Searched ' + base };
  return null;
} });
