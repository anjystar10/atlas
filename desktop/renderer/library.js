// The Library on the PC: everything Atlas Bot made or downloaded, from its folders in Documents/Atlas Bot.
// Open (pages in Atlas's own viewer, the rest in their usual app), Show in Folder, Delete. Files stay on this PC.
const KIND = { document: 'Document', spreadsheet: 'Spreadsheet', page: 'Page', file: 'File', text: 'Text', download: 'Download' };
const ICON = { document: '▤', spreadsheet: '▦', page: '◫', file: '▢', text: '▢', download: '↓' };
const size = (n) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${Math.round(n / 1024)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);
const when = (ms) => new Date(ms).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

let cache = null, loading = false, filter = 'all';

/** Draws the Library into [col]. k: app.js helpers (h, esc, sheet, toast, render). back(): return to the Bots page. */
export function renderLibrary(col, k, back) {
  const host = window.atlasHost;
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-bottom:14px">‹ All Bots</button>'), { onclick: () => { cache = null; back(); } }));
  col.appendChild(k.h('<h1 class="title">Library</h1>'));
  col.appendChild(k.h('<p class="sub">Documents, spreadsheets, pages and downloads your bots made. They live on this PC in Documents › Atlas Bot and don’t sync to your phone.</p>'));
  if (!host.botFiles) { col.appendChild(k.h('<div class="card muted">The Library isn’t available here.</div>')); return; }
  if (!cache) {
    col.appendChild(k.h('<div class="card muted">Loading…</div>'));
    if (!loading) { loading = true; host.botFiles.library().then((l) => { cache = Array.isArray(l) ? l : []; }).catch(() => { cache = []; }).finally(() => { loading = false; k.render(true); }); }
    return;
  }
  const kinds = ['all', ...['document', 'spreadsheet', 'page', 'download', 'file', 'text'].filter((x) => cache.some((f) => f.kind === x))];
  if (kinds.length > 2) {
    const chips = k.h('<div class="row" style="flex-wrap:wrap;gap:8px;margin-bottom:12px"></div>');
    for (const x of kinds) chips.appendChild(Object.assign(k.h(`<button class="btn${filter === x ? ' primary' : ''}">${x === 'all' ? 'Everything' : KIND[x] + 's'}</button>`), { onclick: () => { filter = x; k.render(true); } }));
    col.appendChild(chips);
  }
  const shown = cache.filter((f) => filter === 'all' || f.kind === filter);
  if (!shown.length) { col.appendChild(k.h('<div class="card"><div style="font:400 19px var(--serif)">Nothing here yet</div><p class="muted" style="margin:6px 0 0">Ask a bot for a report, a spreadsheet or a page, like “compare the five best budget phones and make me a spreadsheet”.</p></div>')); return; }
  const list = k.h('<div class="card list"></div>'); col.appendChild(list);
  for (const f of shown) {
    const r = k.h(`<div class="item"><div class="libicon" aria-hidden="true">${ICON[f.kind] || '▢'}</div><div class="grow" style="min-width:0">
      <div class="t" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${k.esc(f.title)}</div>
      <div class="m">${k.esc([KIND[f.kind] || 'File', f.name !== f.title ? f.name : null, size(f.size), when(f.at)].filter(Boolean).join(' · '))}</div>
      <div class="m" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${k.esc(f.goal || '')}</div></div>
      <div class="row" style="gap:6px;flex:none"><button class="btn" data-a="open">Open</button><button class="btn" data-a="show">Show in Folder</button><button class="btn danger" data-a="del" aria-label="Delete">Delete</button></div></div>`);
    r.querySelector('[data-a=open]').onclick = async () => { if (!(await host.botFiles.open(f.file))) k.toast('That file is gone.'); };
    r.querySelector('[data-a=show]').onclick = () => host.botFiles.show(f.file);
    r.querySelector('[data-a=del]').onclick = async () => {
      if (!(await k.sheet({ title: 'Delete This File?', body: `“${f.name}” will be deleted from this PC.`, primary: 'Delete', danger: true }))) return;
      if (await host.botFiles.remove(f.file)) { cache = cache.filter((x) => x.file !== f.file); k.toast('Deleted.'); k.render(true); }
    };
    list.appendChild(r);
  }
}
/** Forget the listing so it's read again (e.g. when a bot finishes). */
export function refreshLibrary() { cache = null; }
