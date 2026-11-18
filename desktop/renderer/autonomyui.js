// Autonomy on the PC screen: the 10-second Undo bar, the "several things need your OK" bar with Approve All, the
// one-time "Always allow this?" offer, and the Me section (level, standing permissions, done without asking).
// Every change made here is the user's own click. The rules are autonomy.js; the state lives in botx.js (extras.autonomy).
import { LEVELS, LEVEL_LABELS, DEFAULT_LEVEL, UNDO_MS, kindLabel, grantText } from './autonomy.js';

const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
let styled = false;
function style() {
  if (styled || typeof document === 'undefined') return; styled = true;
  const st = document.createElement('style');
  st.textContent = `.auto-bar{position:fixed;left:50%;transform:translateX(-50%);z-index:61;display:flex;align-items:center;gap:12px;max-width:min(640px,92vw);
    padding:10px 10px 10px 18px;border-radius:999px;background:color-mix(in srgb,var(--night-bg) 92%,transparent);color:var(--night-fg);border:1px solid rgba(255,255,255,.14);
    box-shadow:var(--shadow);font:500 14px var(--sans);animation:fade .2s}
  .auto-bar .t{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .auto-bar .n{font:500 13px var(--mono);opacity:.8}
  .auto-bar button{min-height:36px;padding:0 16px;border-radius:999px;border:1px solid rgba(255,255,255,.2);background:rgba(255,255,255,.08);color:inherit;font:600 14px var(--sans);cursor:pointer}
  .auto-bar button.p{background:linear-gradient(180deg,var(--primary-a),var(--primary-b));color:var(--on-primary,#fff);border-color:transparent}
  .auto-bar.undo{bottom:176px}.auto-bar.batch{bottom:232px}.auto-bar.offer{bottom:288px}`;
  document.head.appendChild(st);
}
function bar(cls, html) {
  style();
  document.querySelectorAll('.auto-bar.' + cls).forEach((x) => x.remove());
  const t = document.createElement('template'); t.innerHTML = `<div class="auto-bar ${cls}" role="status">${html}</div>`.trim();
  const el = t.content.firstElementChild; document.body.appendChild(el); return el;
}

/**
 * "About to: … · 10 · Undo". true: go ahead; false: Undo was clicked; null: Atlas isn't on screen, so there's no way to
 * offer Undo (the caller asks instead).
 */
export function countdown(what) {
  if (typeof document === 'undefined' || document.visibilityState !== 'visible') return Promise.resolve(null);
  return new Promise((resolve) => {
    const el = bar('undo', `<span class="t">About to: ${esc(String(what).slice(0, 140))}</span><span class="n">10</span><button class="p" aria-label="Undo">Undo</button>`);
    const n = el.querySelector('.n'); const start = Date.now();
    const tick = setInterval(() => { const left = Math.max(0, Math.ceil((UNDO_MS - (Date.now() - start)) / 1000)); n.textContent = String(left); }, 250);
    const done = (v) => { clearInterval(tick); clearTimeout(timer); el.remove(); resolve(v); };
    const timer = setTimeout(() => done(true), UNDO_MS);
    el.querySelector('button').onclick = () => done(false);
  });
}

/** Several approvals waiting: one bar with Approve All (only the ones that may be bulk-approved). */
export function batchBar(b, approveAll, open) {
  if (typeof document === 'undefined') return;
  if (!b || b.bulk.length + b.single.length < 2) { document.querySelectorAll('.auto-bar.batch').forEach((x) => x.remove()); return; }
  const el = bar('batch', `<span class="t">${esc(b.title)}${b.single.length ? ` · ${b.single.length} need their own OK` : ''}</span>
    <button data-k="open">Look</button>${b.bulk.length >= 2 ? `<button class="p" data-k="all">Approve All ${b.bulk.length}</button>` : ''}`);
  el.onclick = (e) => { const k = e.target.closest('[data-k]')?.dataset.k; if (k === 'all') { el.remove(); approveAll(); } if (k === 'open') { el.remove(); open && open(); } };
}

/** After the third yes to the same kind: offer to stop asking, once. */
export function offerAlways(kind, n, grant) {
  if (typeof document === 'undefined') return;
  const el = bar('offer', `<span class="t">You've said yes to “${esc(kindLabel(kind))}” ${n} times. Always allow it?</span><button data-k="no">Keep Asking</button><button class="p" data-k="yes">Always Allow</button>`);
  el.onclick = (e) => { const k = e.target.closest('[data-k]')?.dataset.k; if (k === 'yes') { grant(kind); el.remove(); } if (k === 'no') el.remove(); };
  setTimeout(() => el.remove(), 30000);
}

/** Me: how freely Atlas acts. k: { h, esc, render }; au: extras.autonomy; names(id) → a bot's name. */
export function autonomyCard(col, k, au, names = (id) => id) {
  col.appendChild(k.h('<div class="eyebrow">How Freely Atlas Acts</div>'));
  const c = k.h('<div class="card"></div>'); col.appendChild(c);
  c.appendChild(k.h('<p class="muted small" style="margin:0 0 10px">Money, deleting, messaging someone new and security changes always ask, whatever you pick.</p>'));
  const now = au.level();
  const list = k.h('<div class="list"></div>'); c.appendChild(list);
  for (const lv of LEVELS) {
    const row = k.h(`<div class="item" role="button" tabindex="0" style="cursor:pointer;min-height:44px"><div class="grow"><div style="font-weight:600">${lv === now ? '● ' : '○ '}${esc(lv[0].toUpperCase() + lv.slice(1))}${lv === DEFAULT_LEVEL ? ' <span class="muted small">(recommended)</span>' : ''}</div>
      <div class="small muted">${esc(LEVEL_LABELS[lv].split(': ').slice(1).join(': '))}</div></div></div>`);
    row.onclick = () => { au.setLevel(lv, { byTap: true }); k.render(true, true); };
    list.appendChild(row);
  }
  const grants = au.grants();
  if (grants.length) {
    c.appendChild(k.h('<div class="small muted" style="margin-top:14px">Allowed without asking</div>'));
    const gl = k.h('<div class="list"></div>'); c.appendChild(gl);
    grants.forEach((g, i) => {
      const row = k.h(`<div class="item"><div class="grow">${esc(grantText(g, names))}</div><button class="btn">Ask Again</button></div>`);
      row.querySelector('button').onclick = () => { au.revoke(i); k.render(true, true); };
      gl.appendChild(row);
    });
  }
  const recent = au.recent().slice(-8).reverse();
  if (recent.length) {
    c.appendChild(k.h('<div class="small muted" style="margin-top:14px">Done without asking</div>'));
    c.appendChild(k.h(`<div class="small">${recent.map((r) => `${esc(new Date(r.at).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' }))} · ${esc(r.what)}`).join('<br>')}</div>`));
  }
}
