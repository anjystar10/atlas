// Will a local model run well on this PC right now? Pure rules over a measured profile (from desktop/device.js), so
// they're tested off the PC. Atlas uses them before every local answer and to rank the models it finds, so a model too
// big for the moment is turned away with a reason instead of making the PC swap and freeze.
//
// A model's peak memory = its weights (the file) + the conversation cache + the runtime's working space.
// Where it runs:
//   - all on the graphics card when the card's free memory holds the peak (fast);
//   - split between the card and main memory when the two together hold it (works, slower);
//   - on the processor alone when main memory holds it (slow, but safe);
//   - not at all when nothing holds it.
// Main memory always keeps a reserve for Windows and the user's other apps.

export const CTX = 8192;            // the window Atlas plans for
export const WORKSPACE_MB = 500;    // runtime buffers and the server process itself
export const CACHE_SHARE = 0.45;    // with no layout known: conversation cache ≈ this share of the weights at CTX
export const VRAM_SPARE_MB = 300;   // left free on the card for the screen and other apps

/** Kept free in main memory for the rest of the PC: 2 GB, or 12% of memory if that is more. */
export const reserveMb = (p) => Math.max(2048, Math.round((p.totalRamMb || 0) * 0.12));

/** Model size from its name, for servers that don't say (e.g. "qwen2.5-7b-instruct-q4_k_m"): billions of parameters × bytes per weight. */
export function sizeFromName(id) {
  const s = String(id || '').toLowerCase();
  // mixture-of-experts names ("mixtral-8x7b", "8x22b") hold all the experts: 8 × 7 billion, not 7
  const moe = s.match(/(\d+)\s*x\s*(\d+(?:\.\d+)?)\s*b(?![a-z])/);
  const b = moe || s.match(/(\d+(?:\.\d+)?)\s*b(?![a-z])/);
  if (!b) return -1;
  const params = moe ? parseFloat(moe[1]) * parseFloat(moe[2]) : parseFloat(b[1]);
  const bits = /q8|8bit|int8/.test(s) ? 8.5 : /q6/.test(s) ? 6.6 : /q5/.test(s) ? 5.7 : /q3/.test(s) ? 3.9 : /q2/.test(s) ? 3 : /f16|fp16|bf16/.test(s) ? 16 : 4.8;
  return Math.round(params * 1e9 * bits / 8 / (1024 * 1024));
}

export const peakMb = (sizeMb, ctx = CTX) => Math.round(sizeMb + sizeMb * CACHE_SHARE * ctx / CTX + WORKSPACE_MB);

const biggestCard = (p) => (p.gpus || []).filter((g) => g.vramTotalMb > 0).sort((a, b) => b.vramTotalMb - a.vramTotalMb)[0] || null;
/** Free memory on the best card: measured when the driver says (NVIDIA), else its size less what the screen uses. */
export function cardFreeMb(p) {
  const g = biggestCard(p); if (!g) return 0;
  return Math.max(0, (g.vramFreeMb >= 0 ? g.vramFreeMb : Math.round(g.vramTotalMb * 0.85)) - VRAM_SPARE_MB);
}

/**
 * Where [model] ({ id, sizeMb, loaded, vramMb }) would run on [p] now: { place: 'gpu' | 'split' | 'cpu' | 'no' | 'unknown', ok, reason, peakMb }.
 * A model the server already holds counts its own memory as free (asking it again doesn't load it twice).
 */
export function fit(p, model) {
  const size = model.sizeMb > 0 ? model.sizeMb : sizeFromName(model.id);
  const name = model.id || 'This model';
  if (!(size > 0)) return { place: 'unknown', ok: true, peakMb: -1, reason: `I can't tell how big ${name} is, so I can't check it fits.` };
  const peak = peakMb(size);
  const gb = (mb) => (mb / 1024).toFixed(1) + ' GB';
  const held = model.loaded ? peak : 0;
  const card = cardFreeMb(p) + (model.loaded ? (model.vramMb || 0) : 0);
  const ram = Math.max(0, (p.freeRamMb || 0) + (model.loaded ? Math.max(0, held - (model.vramMb || 0)) : 0) - reserveMb(p));
  if (card >= peak) return { place: 'gpu', ok: true, peakMb: peak, reason: `Runs on the graphics card: needs about ${gb(peak)}, the card has ${gb(card)} free.` };
  if (card > 0 && card + ram >= peak) return { place: 'split', ok: true, peakMb: peak, reason: `Partly on the graphics card (${gb(card)} free) and partly in memory: works, but slower.` };
  if (ram >= peak) return { place: 'cpu', ok: true, peakMb: peak, reason: `Runs on the processor (${gb(ram)} of memory safely free): works, but slowly.` };
  return { place: 'no', ok: false, peakMb: peak, reason: `${name} needs about ${gb(peak)} and this PC has ${gb(card + ram)} safely free right now, so it would freeze the PC. Close some apps or pick a smaller model.` };
}

const RANK = { gpu: 3, split: 2, cpu: 1, unknown: 0, no: -1 };
/**
 * Installed models ordered best-first for this PC now: ones that run on the card, then split, then processor;
 * within each, the larger (usually more capable) first. Models that can't fit come last, marked.
 */
export function rank(p, models) {
  return models.map((m) => ({ ...m, fit: fit(p, m) }))
    .sort((a, b) => (RANK[b.fit.place] - RANK[a.fit.place]) || ((b.sizeMb > 0 ? b.sizeMb : sizeFromName(b.id)) - (a.sizeMb > 0 ? a.sizeMb : sizeFromName(a.id))));
}

/** How many bots may work at once: one when memory is short or the processor small, two otherwise. */
export function botSlots(p) {
  if (!p) return 2;
  const cores = p.physicalCores > 0 ? p.physicalCores : Math.ceil((p.logicalCores || 4) / 2);
  return (p.freeRamMb >= 0 && p.freeRamMb < 2500) || cores < 4 ? 1 : 2;
}

/** Why the PC is being careful right now, or null. */
export function holdingBack(p) {
  if (!p) return null;
  if (p.freeRamMb >= 0 && p.freeRamMb < 2500) return 'Memory is short: one bot at a time, and big local models wait.';
  return null;
}

/** A short line for Atlas's own notes. */
export function line(p) {
  if (!p) return 'this PC';
  const gb = (mb) => (mb < 0 ? '?' : Math.round(mb / 1024) + ' GB');
  const g = biggestCard(p);
  return `This PC: ${p.cpu || 'processor unknown'}, ${gb(p.totalRamMb)} memory (${gb(p.freeRamMb)} free)` +
    (g ? `, ${g.name} with ${gb(g.vramTotalMb)}${g.vramFreeMb >= 0 ? ` (${gb(g.vramFreeMb)} free)` : ''}` : ', no separate graphics memory found') +
    (holdingBack(p) ? `. ${holdingBack(p)}` : '');
}

/** Keeps the last profile for [maxAgeMs]; [read] fetches a new one. */
export function profileCache(read, maxAgeMs = 30000) {
  let last = null, at = 0, pending = null;
  return {
    now: () => last,
    async get(fresh = false) {
      if (!fresh && last && Date.now() - at < maxAgeMs) return last;
      if (!pending) pending = Promise.resolve().then(read).then((p) => { if (p) { last = p; at = Date.now(); } return last; }).catch(() => last).finally(() => { pending = null; });
      return pending;
    },
  };
}
