// What an attachment is and how Atlas reads it: the same rules as the phone's AttachRules.kt, both checked against
// tools/attach-fixtures.json. Every file can be attached; see AttachRules.kt for what each route means.
export const MB = 1024 * 1024;
export const INLINE_MAX = 14 * MB;          // sent inline as base64 (Gemini's own limit is 100 MB, PDFs 50 MB)
export const UPLOAD_MAX = 2048 * MB;        // Gemini Files API: 2 GB a file
export const TEXT_INLINE_CHARS = 120000;    // longer text is read in parts (file_search / file_read)
export const FRAMES = 8;

const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif', 'heic', 'heif', 'bmp', 'avif']);
const VIDEO_EXT = new Set(['mp4', 'webm', 'mov', 'm4v', '3gp', 'mkv', 'avi']);
const AUDIO_EXT = new Set(['mp3', 'wav', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'flac', 'amr', 'weba']);
export const TEXT_EXT = new Set(['txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'jsonl', 'xml', 'yaml', 'yml', 'html', 'htm', 'log', 'ini', 'toml',
  'cfg', 'conf', 'env', 'properties', 'gradle', 'kt', 'kts', 'java', 'py', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'c', 'h', 'cpp', 'hpp',
  'cs', 'go', 'rs', 'rb', 'php', 'swift', 'sh', 'bash', 'zsh', 'ps1', 'bat', 'sql', 'lua', 'dart', 'css', 'scss', 'svg', 'tex', 'srt',
  'vtt', 'rtf', 'r', 'm', 'scala', 'pl', 'vue', 'svelte', 'ipynb']);
const TEXT_NAMES = new Set(['dockerfile', 'makefile', 'readme', 'license', 'gemfile', 'procfile']);
const TEXT_MIME = new Set(['application/json', 'application/xml', 'application/javascript', 'application/x-yaml', 'application/csv',
  'application/x-sh', 'application/sql', 'application/rtf', 'image/svg+xml']);

export function ext(name) { const b = String(name || '').split(/[\\/]/).pop(); return b.includes('.') ? b.split('.').pop().toLowerCase() : ''; }

/** image, video, audio, pdf, text, docx, xlsx, pptx, zip or binary. */
export function kind(name, mime) {
  const e = ext(name), m = String(mime || '').toLowerCase(), base = String(name || '').split(/[\\/]/).pop().toLowerCase();
  if (e === 'svg' || m === 'image/svg+xml') return 'text';
  if (IMAGE_EXT.has(e) || (m.startsWith('image/') && !TEXT_EXT.has(e))) return 'image';
  if (VIDEO_EXT.has(e) || m.startsWith('video/')) return 'video';
  if (AUDIO_EXT.has(e) || m.startsWith('audio/')) return 'audio';
  if (e === 'pdf' || m === 'application/pdf') return 'pdf';
  if (e === 'docx' || m.includes('wordprocessingml')) return 'docx';
  if (e === 'xlsx' || m.includes('spreadsheetml')) return 'xlsx';
  if (e === 'pptx' || m.includes('presentationml')) return 'pptx';
  if (e === 'zip' || m === 'application/zip' || m === 'application/x-zip-compressed') return 'zip';
  if (TEXT_EXT.has(e) || m.startsWith('text/') || TEXT_MIME.has(m)) return 'text';
  if (!e && TEXT_NAMES.has(base)) return 'text';
  return 'binary';
}
export const readsAsText = (k) => k === 'text' || k === 'docx' || k === 'xlsx' || k === 'pptx';

/** How a [k] file of [size] bytes reaches a service of [style] (gemini, anthropic, openai). */
export function route(k, size, style, chars = 0) {
  const gemini = style === 'gemini';
  switch (k) {
    case 'image': return 'native';
    case 'video': return gemini && size <= INLINE_MAX ? 'native' : gemini && size <= UPLOAD_MAX ? 'upload' : 'frames';
    case 'audio': return !gemini ? 'describe' : size <= INLINE_MAX ? 'native' : size <= UPLOAD_MAX ? 'upload' : 'describe';
    case 'pdf': return size <= INLINE_MAX ? 'native' : gemini && size <= UPLOAD_MAX ? 'upload' : 'describe';
    case 'text': case 'docx': case 'xlsx': case 'pptx': return chars <= TEXT_INLINE_CHARS ? 'text' : 'chunks';
    case 'zip': return 'listing';
    default: return 'describe';
  }
}

/** One honest line about what Atlas will actually read ('' when it reads the whole thing). */
export function note(k, r, service) {
  switch (r) {
    case 'upload': return 'Large: it goes up to Gemini for this message (Gemini keeps it 48 hours).';
    case 'frames': return service === 'Gemini' ? `Over 2 GB: Atlas sees ${FRAMES} stills from it, not the motion or sound.`
      : `${service} can't watch video: Atlas sees ${FRAMES} stills from it, not the motion or sound. Gemini can watch it whole.`;
    case 'chunks': return 'Too long to read at once: Atlas searches it and reads the parts it needs.';
    case 'listing': return 'Atlas sees the list of files inside and can open the readable ones.';
    case 'describe':
      if (k === 'audio') return service === 'Gemini' ? 'Over 2 GB: Atlas sees only its name, size and type.' : `Only Gemini can listen to audio: with ${service}, Atlas sees only its name, size and type.`;
      if (k === 'pdf') return `Too big for ${service} to read whole: Atlas sees its name and size.${service !== 'Gemini' ? ' Gemini can read PDFs up to 2 GB.' : ''}`;
      return "Atlas can't open this kind of file: it sees its name, size, type and any readable text inside.";
    default: return '';
  }
}

export function sizeText(b) {
  return b >= 1024 * MB ? `${(b / (1024 * MB)).toFixed(1)} GB` : b >= MB ? `${(b / MB).toFixed(1)} MB` : b >= 1024 ? `${Math.floor(b / 1024)} KB` : `${b} bytes`;
}

/** Readable runs of text inside any bytes (like the "strings" tool). */
export function strings(bytes, min = 5, max = 4000) {
  let out = '', run = '';
  const flush = () => { if (run.length >= min && out.length < max) out += (out ? ' ' : '') + run.slice(0, max - out.length); run = ''; };
  for (const b of bytes) { if ((b >= 32 && b <= 126) || b === 9) run += String.fromCharCode(b); else flush(); }
  flush();
  return out;
}

/** Where a long text is cut into [size]-character parts (nudged back to a line break in the last eighth). */
export function chunkStarts(text, size) {
  const out = [0]; let at = 0;
  while (at + size < text.length) {
    let next = at + size;
    const nl = text.lastIndexOf('\n', next - 1);
    if (nl > at + size - Math.floor(size / 8)) next = nl + 1;
    out.push(next); at = next;
  }
  return out;
}
