// Atlas Bot v2 on the PC, built like Grok Bot: the Bots page is a list of conversations. Atlas (the chief of staff) is
// starred at the top, then the standing bots, then group chats. Tapping a bot opens ITS OWN CONVERSATION: you talk to
// it there, and its progress, questions, approval cards and reports arrive inline; your message always comes first and
// can redirect what it's doing; Stop is always there while it works. Everything else about a bot — watching its screen,
// its routines and triggers, skills, budget, memory, settings and its work (missions and agent trees) — is on its
// Details page. Group chats are the same conversation view with several bots. Everything goes through crewface.js (the
// engine) and threads.js (the conversations). app.js passes its small helpers in [k]: h, esc, sheet, toast, render,
// go(view), md(text) → html, after(col) (what app.js adds under the list).
import * as crew from './crewface.js';
import * as threads from './threads.js';
import * as TR from './trigrules.js';
import { CAP_IDS, CAP_LABELS, DEFAULT_BUDGET, HOMES, planProgress, defaultAccess } from './crew.js';
import { LABELS as BOT_LABELS } from './bots.js';
import { USER, ATLAS, preview } from './threadcore.js';
import { extras } from './botwire.js';
import { kindFor, tierOf } from './autonomy.js';   // >>> autonomy: wider OKs on the approval card
import * as BF from './botface.js';   // named bots: who each is, its face, and History
import * as botmirror from './botmirror.js';

const drafts = {};             // unsent text per box (survives re-renders)
const WAITING = new Set(['awaiting_approval', 'awaiting_answer', 'needs_review']);
const RUNNING = new Set(['running', 'queued', 'waiting_retry', 'waiting_helpers']);

const when = (ms) => (ms ? new Date(ms).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
const ago = (ms) => { if (!ms) return ''; const s = Math.max(0, (Date.now() - ms) / 1000); return s < 60 ? 'now' : s < 3600 ? `${Math.floor(s / 60)}m` : s < 86400 ? `${Math.floor(s / 3600)}h` : new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short' }); };
const iconOf = (b) => (b && b.icon) || (b && b.name ? b.name.trim()[0].toUpperCase() : '•');
const toneOf = (s) => (s === 'completed' ? 'ok' : s === 'failed' ? 'bad' : WAITING.has(s) ? 'warn' : s === 'cancelled' || s === 'paused' ? '' : 'accent');
const draftBox = (key, el) => { el.value = drafts[key] || ''; el.oninput = () => (drafts[key] = el.value); };

/** Where a bot stands right now: {label, tone, dot}. */
function statusOf(b) {
  if (b.state === 'paused') return { label: 'Paused', tone: '', dot: 'off' };
  const ms = crew.missions(b.id);
  if (ms.some((m) => WAITING.has(m.state))) return { label: 'Waiting on you', tone: 'warn', dot: 'warn' };
  if (ms.some((m) => RUNNING.has(m.state))) return { label: 'Working', tone: 'accent', dot: 'live' };
  const u = crew.usage(b.id); if (!u.ok) return { label: 'Budget used today', tone: 'warn', dot: 'warn' };
  return { label: 'Idle', tone: '', dot: 'idle' };
}
/** The mission a conversation's bot is on now (what Stop stops). */
const busyIn = (t) => (t ? threads.activeMission(t.id) : null);

function avatar(k, b, size = '', dot = '') {
  if (!b || b.id === ATLAS) return `<div class="bav orb ${size}" aria-hidden="true"></div>`;
  return faceHtml(b, size, dot);
}

// ------------------------------------------------------------------- a bot's face (botface.js): drawn, never a letter or emoji

let faceSeq = 0;
const P = (n) => Math.round(n * 100) / 100;
function polygon(r, n, start, round) {
  const pts = []; for (let i = 0; i < n; i++) { const a = start + (i * 2 * Math.PI) / n; pts.push([Math.cos(a) * r, Math.sin(a) * r]); }
  let d = '';
  for (let i = 0; i < n; i++) {
    const [x, y] = pts[i], [px, py] = pts[(i + n - 1) % n], [nx, ny] = pts[(i + 1) % n];
    const to = (bx, by) => { const dx = bx - x, dy = by - y, l = Math.hypot(dx, dy); return [x + (dx / l) * round, y + (dy / l) * round]; };
    const [ax, ay] = to(px, py), [cx, cy] = to(nx, ny);
    d += `${i ? 'L' : 'M'}${P(ax)} ${P(ay)}Q${P(x)} ${P(y)} ${P(cx)} ${P(cy)}`;
  }
  return `<path d="${d}Z"/>`;
}
function shapeMarkup(shape) {
  const r = 40;
  if (shape === 'hex') return polygon(r * 1.06, 6, -Math.PI / 2, r * 0.18);
  if (shape === 'diamond') return polygon(r * 1.12, 4, -Math.PI / 2, r * 0.22);
  if (shape === 'squircle') return `<rect x="${-r * 0.92}" y="${-r * 0.92}" width="${r * 1.84}" height="${r * 1.84}" rx="${r * 0.42}"/>`;
  if (shape === 'petal') { const k = r * 0.52; let m = `<circle r="${r * 0.8}"/>`; for (let i = 0; i < 4; i++) { const a = Math.PI / 4 + (i * Math.PI) / 2; m += `<circle cx="${P(Math.cos(a) * k * 0.62)}" cy="${P(Math.sin(a) * k * 0.62)}" r="${P(k * 1.05)}"/>`; } return m; }
  return `<circle r="${r}"/>`;
}
function glyphPath(motif) {
  const g = 21; const p = (x, y) => `${P(x * g)} ${P(y * g)}`;
  switch (motif) {
    case 'code': return `M${p(-0.35, -0.55)}L${p(-1, 0)}L${p(-0.35, 0.55)}M${p(0.35, -0.55)}L${p(1, 0)}L${p(0.35, 0.55)}M${p(0.15, -0.7)}L${p(-0.15, 0.7)}`;
    case 'build': return `M${p(0, -0.85)}L${p(0.8, -0.42)}L${p(0.8, 0.48)}L${p(0, 0.9)}L${p(-0.8, 0.48)}L${p(-0.8, -0.42)}ZM${p(-0.8, -0.42)}L${p(0, 0)}L${p(0.8, -0.42)}M${p(0, 0)}L${p(0, 0.9)}`;
    case 'chart': return `M${p(-0.9, 0.55)}L${p(-0.3, -0.05)}L${p(0.1, 0.3)}L${p(0.85, -0.5)}M${p(0.4, -0.55)}L${p(0.88, -0.55)}L${p(0.88, -0.08)}`;
    case 'wave': { let d = `M${p(-0.95, 0)}`; for (let i = 1; i <= 24; i++) { const x = -0.95 + (i * 1.9) / 24; d += `L${p(x, -Math.sin(x * Math.PI * 1.6) * 0.45)}`; } return d; }
    case 'mail': return `M${p(-0.74, -0.6)}H${P(0.74 * g)}Q${p(0.9, -0.6)} ${p(0.9, -0.44)}V${P(0.44 * g)}Q${p(0.9, 0.6)} ${p(0.74, 0.6)}H${P(-0.74 * g)}Q${p(-0.9, 0.6)} ${p(-0.9, 0.44)}V${P(-0.44 * g)}Q${p(-0.9, -0.6)} ${p(-0.74, -0.6)}ZM${p(-0.8, -0.48)}L${p(0, 0.1)}L${p(0.8, -0.48)}`;
    case 'play': return `M${p(-0.42, -0.7)}L${p(0.75, 0)}L${p(-0.42, 0.7)}Z`;
    case 'search': return `M${p(0.37, -0.18)}A${P(0.55 * g)} ${P(0.55 * g)} 0 1 1 ${p(-0.73, -0.18)}A${P(0.55 * g)} ${P(0.55 * g)} 0 1 1 ${p(0.37, -0.18)}ZM${p(0.22, 0.22)}L${p(0.8, 0.8)}`;
    case 'coins': return `M${p(0.78, 0)}A${P(0.78 * g)} ${P(0.78 * g)} 0 1 1 ${p(-0.78, 0)}A${P(0.78 * g)} ${P(0.78 * g)} 0 1 1 ${p(0.78, 0)}ZM${p(0, -0.42)}L${p(0, 0.42)}M${p(0.28, -0.22)}L${p(-0.12, -0.22)}Q${p(-0.32, -0.22)} ${p(-0.32, -0.05)}Q${p(-0.32, 0.08)} ${p(0, 0.08)}Q${p(0.3, 0.08)} ${p(0.3, 0.26)}L${p(-0.28, 0.26)}`;
    case 'calendar': return `M${p(-0.66, -0.62)}H${P(0.66 * g)}Q${p(0.8, -0.62)} ${p(0.8, -0.48)}V${P(0.61 * g)}Q${p(0.8, 0.75)} ${p(0.66, 0.75)}H${P(-0.66 * g)}Q${p(-0.8, 0.75)} ${p(-0.8, 0.61)}V${P(-0.48 * g)}Q${p(-0.8, -0.62)} ${p(-0.66, -0.62)}ZM${p(-0.8, -0.2)}L${p(0.8, -0.2)}M${p(-0.4, -0.85)}L${p(-0.4, -0.45)}M${p(0.4, -0.85)}L${p(0.4, -0.45)}`;
    default: return `M${p(0, -1)}Q${p(0.12, -0.12)} ${p(1, 0)}Q${p(0.12, 0.12)} ${p(0, 1)}Q${p(-0.12, 0.12)} ${p(-1, 0)}Q${p(-0.12, -0.12)} ${p(0, -1)}Z`;
  }
}
/** A bot's face as inline SVG: its shape lit like glass in its colours, a white glyph for its job, its state drawn on it. */
export function faceHtml(b, size = '', dot = '') {
  const f = BF.faceOf(b);
  const [light, deep] = BF.COLORS[f.color];
  const id = `bf${++faceSeq}`;
  const filled = f.motif === 'play' || f.motif === 'spark';
  return `<div class="bav bface ${size} ${dot}" aria-hidden="true"><svg viewBox="-50 -50 100 100" width="100%" height="100%">
    <defs><radialGradient id="${id}g" gradientUnits="userSpaceOnUse" cx="-13" cy="-15" r="62"><stop offset="0" stop-color="${light}"/><stop offset=".62" stop-color="${deep}"/><stop offset="1" stop-color="${deep}" stop-opacity=".85"/></radialGradient>
    <radialGradient id="${id}s" gradientUnits="userSpaceOnUse" cx="-14" cy="-18" r="30"><stop offset="0" stop-color="#fff" stop-opacity=".5"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
    <clipPath id="${id}c">${shapeMarkup(f.shape)}</clipPath></defs>
    ${dot === 'warn' ? '<circle class="fring" r="47" fill="none" stroke-width="4.5"/>' : ''}
    <g class="fbody" clip-path="url(#${id}c)"><rect x="-50" y="-50" width="100" height="100" fill="url(#${id}g)"/><rect x="-50" y="-50" width="100" height="100" fill="url(#${id}s)"/></g>
    <path d="${glyphPath(f.motif)}" fill="${filled ? '#fff' : 'none'}" stroke="#fff" stroke-width="${filled ? 0 : 6.2}" stroke-linecap="round" stroke-linejoin="round"/>
    ${dot === 'live' ? `<g class="forbit"><circle cx="0" cy="-45" r="5.5" fill="#fff" style="filter:drop-shadow(0 0 4px ${light})"/></g>` : ''}
  </svg></div>`;
}
/** The five shapes and eight colours, each shown as the bot's real face. */
function lookPicker(k, base, shape, color, onPick) {
  const el = k.h(`<div><div class="lookrow" role="radiogroup" aria-label="Shape">${BF.SHAPES.map((sh) => `<button class="lookbtn${sh === shape ? ' on' : ''}" data-sh="${sh}" role="radio" aria-checked="${sh === shape}" aria-label="Shape: ${sh}">${faceHtml({ ...base, face: { shape: sh, color } }, '')}</button>`).join('')}</div>
    <div class="lookrow" role="radiogroup" aria-label="Colour" style="margin-top:8px">${BF.COLORS.map(([a, z], i) => `<button class="swatch${i === color ? ' on' : ''}" data-co="${i}" role="radio" aria-checked="${i === color}" aria-label="Colour ${i + 1}" style="background:linear-gradient(135deg,${a},${z})"></button>`).join('')}</div></div>`);
  el.querySelectorAll('[data-sh]').forEach((x) => (x.onclick = () => onPick(x.dataset.sh, color)));
  el.querySelectorAll('[data-co]').forEach((x) => (x.onclick = () => onPick(shape, Number(x.dataset.co))));
  return el;
}
function meter(pct, tone) { return `<div class="meter${tone ? ' ' + tone : ''}"><i style="width:${Math.round(Math.max(0.02, pct) * 100)}%"></i></div>`; }

// =================================================================== routing

/** Draws the page. Returns true when it handled [view] (null = the list; "bot:<id>", "info:<id>", "thread:<id>", "newbot", "newbot:<id>", "newthread"). */
export function render(col, view, k) {
  if (view && view.startsWith('bot:')) { conversation(col, threads.direct(view.slice(4), (crew.bot(view.slice(4)) || {}).name).id, k); return true; }
  if (view && view.startsWith('info:')) { details(col, view.slice(5), k); return true; }
  if (view && view.startsWith('thread:')) { conversation(col, view.slice(7), k); return true; }
  if (view && view.startsWith('newbot:')) { newBotPage(col, k, view.slice(7)); return true; }
  if (view === 'newbot') { newBotPage(col, k); return true; }
  if (view === 'newthread') { newThreadPage(col, k); return true; }
  if (view === 'history') { historyPage(col, k); return true; }
  if (view) return false;
  home(col, k);
  return true;
}
export function renderEdit(col, id, k) { newBotPage(col, k, id); }

// =================================================================== the list (like a messaging app)

function home(col, k) {
  const head = k.h(`<div class="crewhead"><div class="grow"><h1 class="title">Bots</h1><p class="sub" style="margin:0">Your team. Each one has a name, a job and its own conversation: tell it what you need, and it works in the background and reports back there.</p></div>
    <div class="row" style="gap:8px;flex:none"><button class="btn" id="chist" title="Every job your bots ran, here and on your phone">History</button><button class="btn" id="cnewg">New Group</button><button class="btn primary" id="cnewb">New Bot</button></div></div>`);
  head.querySelector('#chist').onclick = () => k.go('history');
  head.querySelector('#cnewb').onclick = () => k.go('newbot');
  head.querySelector('#cnewg').onclick = () => k.go('newthread');
  col.appendChild(head);

  const roster = crew.bots();
  const lastAt = (b) => { const t = threads.directOf(b.id); return Math.max((t && t.updatedAt) || 0, b.updatedAt || 0); };
  const [atlas, ...rest] = roster;
  const list = k.h('<div class="card blist" role="list"></div>');
  list.appendChild(botRow(k, atlas, true));
  for (const b of rest.slice().sort((a, c) => lastAt(c) - lastAt(a))) list.appendChild(botRow(k, b, false));
  col.appendChild(list);
  if (!rest.length) col.appendChild(k.h('<p class="small muted" style="margin:10px 6px 0">No other bots yet. <b>New Bot</b> makes one in a tap: a trader, an inbox checker, a reel poster, a researcher… or describe your own.</p>'));

  const gs = threads.groups();
  col.appendChild(k.h('<div class="eyebrow">GROUP CHATS</div>'));
  if (!gs.length) col.appendChild(k.h('<div class="card blist"><div class="brow empty"><div class="grow muted">You and several bots in one conversation: @mention one to give it the job, and they hand work to each other. <b>New Group</b> starts one.</div></div></div>'));
  else { const gl = k.h('<div class="card blist" role="list"></div>'); for (const t of gs) gl.appendChild(groupRow(k, t, roster)); col.appendChild(gl); }

  if (k.after) k.after(col);
}

function nameOf(id, roster) { return id === USER ? 'You' : id === ATLAS ? 'Atlas' : ((roster || crew.bots()).find((b) => b.id === id) || {}).name || 'A bot'; }

function badges(k, t, extraWaiting = 0) {
  const ap = (t ? threads.approvalsWaiting(t.id) : 0) + extraWaiting;
  const un = t ? threads.unread(t.id) : 0;
  if (ap) return `<span class="bbadge warn" title="${ap} waiting for your OK">${ap}</span>`;
  if (un) return `<span class="bbadge" title="${un} unread">${un > 99 ? '99+' : un}</span>`;
  return '';
}

function botRow(k, b, starred) {
  const t = threads.directOf(b.id);
  const st = statusOf(b);
  const p = preview(t);
  const waitingElsewhere = (t ? 0 : crew.missions(b.id).filter((m) => WAITING.has(m.state)).length)
    + (b.id === ATLAS ? botmirror.others().filter((v) => WAITING.has(v.state)).length : 0);   // the other device's jobs waiting on you count on Atlas's row
  const last = p ? `${p.from === USER ? 'You: ' : ''}${p.text.replace(/\s+/g, ' ').slice(0, 140)}` : 'No messages yet. Say what you need.';
  const r = k.h(`<div class="brow" role="listitem button" tabindex="0" aria-label="${k.esc(b.name)}, ${k.esc(st.label)}">
    <div class="bavwrap">${avatar(k, b, '', st.dot)}${b.id === ATLAS ? `<span class="sdot ${st.dot}" aria-hidden="true"></span>` : ''}</div>
    <div class="grow" style="min-width:0"><div class="row" style="gap:8px"><span class="bname">${k.esc(b.name)}${starred ? ' <span class="star" title="Your chief of staff">★</span>' : ''}</span><span class="grow"></span><span class="small faint">${k.esc(ago(p ? p.at : 0))}</span></div>
      <div class="small muted clamp1">${k.esc(b.id === ATLAS ? 'Your chief of staff' : BF.subtitle(b))} · <span class="${st.tone}">${k.esc(st.label)}</span></div>
      <div class="small faint clamp1" style="margin-top:2px">${k.esc(last)}</div></div>
    ${badges(k, t, waitingElsewhere)}</div>`);
  r.onclick = () => k.go('bot:' + b.id);
  r.onkeydown = (e) => { if (e.key === 'Enter') k.go('bot:' + b.id); };
  return r;
}

function groupRow(k, t, roster) {
  const who = t.participants.filter((p) => p !== USER).map((p) => roster.find((b) => b.id === p)).filter(Boolean);
  const p = preview(t);
  const working = who.some((b) => statusOf(b).dot === 'live');
  const r = k.h(`<div class="brow" role="listitem button" tabindex="0">
    <div class="bavwrap"><div class="bstack">${who.slice(0, 3).map((b) => avatar(k, b, 'sm')).join('')}</div>${working ? '<span class="sdot live" aria-hidden="true"></span>' : ''}</div>
    <div class="grow" style="min-width:0"><div class="row" style="gap:8px"><span class="bname">${k.esc(t.title)}</span><span class="grow"></span><span class="small faint">${k.esc(ago(p ? p.at : t.updatedAt))}</span></div>
      <div class="small muted clamp1">${k.esc(who.map((b) => b.name).join(', ') || 'Just you')} + Atlas</div>
      <div class="small faint clamp1" style="margin-top:2px">${p ? `${k.esc(nameOf(p.from, roster))}: ${k.esc(p.text.replace(/\s+/g, ' ').slice(0, 140))}` : 'No messages yet.'}</div></div>
    ${badges(k, t)}</div>`);
  r.onclick = () => k.go('thread:' + t.id);
  r.onkeydown = (e) => { if (e.key === 'Enter') k.go('thread:' + t.id); };
  return r;
}

// =================================================================== a conversation (a bot's own, or a group chat)

function conversation(col, id, k) {
  const t = threads.get(id);
  const back = Object.assign(k.h('<button class="btn ghost" aria-label="Back to Bots">‹ Bots</button>'), { onclick: () => k.go(null) });
  if (!t) { col.appendChild(back); col.appendChild(k.h('<div class="card" style="margin-top:12px">This conversation was deleted.</div>')); return; }
  const roster = crew.bots();
  const direct = t.kind === 'direct';
  const botId = direct ? t.participants[1] : null;
  const b = direct ? (crew.bot(botId) || (botId === ATLAS ? crew.ATLAS_BOT : null)) : null;
  if (direct && !b) { col.appendChild(back); col.appendChild(k.h('<div class="card" style="margin-top:12px">This bot was removed.</div>')); return; }
  const inIt = direct ? [b] : t.participants.filter((p) => p !== USER).map((p) => roster.find((x) => x.id === p)).filter(Boolean);
  const busy = busyIn(t);

  // the header: who, where it stands, Stop while it works, Details
  const st = direct ? statusOf(b) : null;
  const head = k.h(`<div class="convhead card"><div class="row" style="gap:12px;align-items:center"></div></div>`);
  const hr = head.querySelector('.row');
  hr.appendChild(back);
  hr.appendChild(k.h(direct ? `<div class="bavwrap">${avatar(k, b, '', st.dot)}${botId === ATLAS ? `<span class="sdot ${st.dot}"></span>` : ''}</div>` : `<div class="bstack">${inIt.slice(0, 3).map((x) => avatar(k, x, 'sm')).join('')}</div>`));
  hr.appendChild(k.h(`<div class="grow" style="min-width:0"><div class="bname lg">${k.esc(direct ? b.name : t.title)}${direct && botId === ATLAS ? ' <span class="star">★</span>' : ''}</div>
    <div class="small muted clamp1">${direct ? `${botId === ATLAS ? '' : k.esc(BF.subtitle(b)) + ' · '}<span class="${st.tone}">${k.esc(st.label)}</span>${busy && busy.state === 'running' && busy.current ? ' · ' + k.esc(busy.current) : ''}` : k.esc(inIt.map((x) => x.name).join(', ') + ' + Atlas')}</div></div>`));
  if (busy) hr.appendChild(Object.assign(k.h('<button class="btn danger" title="Stop what it is doing now (what is already done stays done)">Stop</button>'), { onclick: () => { crew.stop(busy.id); k.toast('Stopped.'); k.render(true); } }));
  if (busy) hr.appendChild(Object.assign(k.h('<button class="btn" title="Its plan, steps, agents and browser">Work</button>'), { onclick: () => k.go(busy.id) }));
  if (direct) hr.appendChild(Object.assign(k.h('<button class="btn">Details</button>'), { onclick: () => k.go('info:' + botId) }));
  else {
    hr.appendChild(Object.assign(k.h('<button class="btn" title="Atlas sums up what was decided, done and still open">Sum Up</button>'), { onclick: () => { threads.summarize(t.id); k.toast('Atlas is summing it up.'); k.render(true); } }));
    hr.appendChild(Object.assign(k.h('<button class="btn" aria-label="Group chat settings">⋯</button>'), { onclick: () => groupSheet(t, k) }));
  }
  col.appendChild(head);

  // the messages
  const feed = k.h('<div class="thread"></div>');
  if (!t.messages.length) {
    feed.appendChild(k.h(direct
      ? `<div class="tempty">${avatar(k, b, 'lg')}<div class="serif" style="font-size:22px;margin-top:12px">${k.esc(b.name)}</div><div class="muted" style="max-width:46ch;text-align:center">${k.esc(b.role || '')}</div><div class="small faint" style="margin-top:8px">Tell it what you need. It works in the background and reports back here.</div></div>`
      : `<div class="tempty"><div class="muted">Say what you need. @mention a bot to give it the job${inIt.length ? `, e.g. “@${k.esc(inIt[0].name)} …”` : ''}. Without a mention, Atlas takes it and hands parts on.</div></div>`));
  }
  for (const m of t.messages) feed.appendChild(messageView(m, roster, k));
  col.appendChild(feed);
  threads.markSeen(t.id);

  // the composer
  const key = 'th:' + t.id;
  const box = k.h(`<div class="card convcompose">${direct ? '' : `<div class="chips" id="men">${inIt.map((x) => `<button class="chip sm" data-n="${k.esc(x.name)}">@${k.esc(x.name)}</button>`).join('')}<button class="chip sm" data-n="Atlas">@Atlas</button></div>`}
    <div class="row" style="gap:8px;align-items:flex-end;${direct ? '' : 'margin-top:10px'}"><textarea class="field grow" id="tmsg" rows="1" placeholder="${direct ? `Message ${k.esc(b.name)}` : 'Message the group'}"></textarea><button class="btn primary" id="tsend">Send</button></div>
    ${direct && busy ? '<label class="small muted" style="display:block;margin-top:8px"><input type="checkbox" id="tfresh"> Start this as a separate task (otherwise it redirects what it’s doing now)</label>' : ''}</div>`);
  const ta = box.querySelector('#tmsg'); draftBox(key, ta);
  const grow = () => { ta.style.height = 'auto'; ta.style.height = Math.min(220, ta.scrollHeight) + 'px'; };
  ta.addEventListener('input', grow); setTimeout(grow, 0);
  box.querySelectorAll('[data-n]').forEach((c) => (c.onclick = () => { ta.value = (ta.value ? ta.value.replace(/\s*$/, ' ') : '') + '@' + c.dataset.n + ' '; drafts[key] = ta.value; ta.focus(); }));
  const send = () => {
    const text = ta.value.trim(); if (!text) return;
    drafts[key] = '';
    const fresh = !!(box.querySelector('#tfresh') && box.querySelector('#tfresh').checked);
    threads.post(t.id, USER, text, { fresh });
    k.render(true);
  };
  box.querySelector('#tsend').onclick = send;
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } };
  col.appendChild(box);
  setTimeout(() => { feed.lastElementChild && feed.lastElementChild.scrollIntoView({ block: 'end' }); }, 0);
}

async function groupSheet(t, k) {
  const roster = crew.bots().filter((x) => !x.builtIn);
  const picked = new Set(t.participants.filter((p) => p !== USER));
  let title = t.title, del = false;
  const html = `<input class="field" id="gt" maxlength="80" aria-label="Title"><div class="small muted" style="margin:14px 0 8px;font-weight:600">WHO'S IN IT (UP TO SIX BOTS)</div>
    <div class="chips" id="gb">${roster.map((b) => `<button class="chip${picked.has(b.id) ? ' on' : ''}" data-id="${k.esc(b.id)}">${k.esc(b.name)}</button>`).join('')}</div>
    <button class="btn danger" id="gdel" style="margin-top:18px">Delete This Group Chat</button>`;
  const ok = await k.sheet({ title: 'Group Chat', html, primary: 'Save',
    onOpen: (o) => {
      o.querySelector('#gt').value = t.title;
      o.querySelectorAll('#gb [data-id]').forEach((x) => (x.onclick = () => { const id = x.dataset.id; if (picked.has(id)) picked.delete(id); else if (picked.size < 6) picked.add(id); x.classList.toggle('on', picked.has(id)); }));
      o.querySelector('#gdel').onclick = () => { del = true; o.querySelector('[data-k=yes]').click(); };
    },
    onPrimary: (o) => { title = o.querySelector('#gt').value.trim() || t.title; return true; } });
  if (!ok) return;
  if (del) {
    if (await k.sheet({ title: 'Delete This Group Chat?', body: 'On both your devices. Missions it started carry on.', primary: 'Delete', danger: true })) { threads.remove(t.id); k.go(null); }
    return;
  }
  threads.rename(t.id, title);
  threads.setParticipants(t.id, [...picked]);
  k.render(true);
}

/** >>> autonomy: the wider OKs this approval may get (never money or the always-ask kinds; autonomy.js decides). */
function wider(j) {
  const a = j && j.approval; if (!a || a.mustAsk) return [];
  let kind = a.kind; try { kind = kind || kindFor(a.tool, a.args || {}, a.category, { what: a.what, money: !!a.mustAsk }); } catch { return []; }
  const t = tierOf(kind); if (t !== 'low' && t !== 'medium') return [];
  const out = [['today', 'For Today']];
  out.push(['bot', 'For This Bot']);   // bots.js grants it to the mission's standing bot (or Atlas)
  if ((j.plan && j.plan.length) || j.parent) out.push(['plan', 'Whole Plan']);
  return out;
}

function messageView(m, roster, k) {
  if (m.from === USER) return k.h(`<div class="msg you"><div class="bubble">${k.esc(m.text)}</div><div class="tmeta">${k.esc(ago(m.at))}</div></div>`);
  if (m.kind === 'note') return k.h(`<div class="tnote small muted">${k.esc(m.text)}</div>`);
  const b = m.from === ATLAS ? crew.ATLAS_BOT : roster.find((x) => x.id === m.from) || { name: 'A bot', icon: '•' };
  const label = m.kind === 'question' ? 'asks you' : m.kind === 'approval' ? 'needs your OK' : m.kind === 'report' ? 'reported' : '';
  const v = k.h(`<div class="tmsg">${avatar(k, b, 'sm')}<div class="grow" style="min-width:0"><div class="small muted"><b class="fg">${k.esc(b.name)}</b>${label ? ' · ' + label : ''} · ${k.esc(ago(m.at))}</div><div class="tbody"></div></div></div>`);
  const body = v.querySelector('.tbody');
  const j = m.mission && crew.mission(m.mission);
  if (m.kind === 'approval') {
    const open = j && j.state === 'awaiting_approval' && j.approval && j.approval.what === m.text;
    const c = k.h(`<div class="card approve"><div class="serif" style="font-size:18px;line-height:1.4">${k.esc(m.text)}</div>${open
      ? `<div class="row" style="margin-top:12px;gap:8px;flex-wrap:wrap"><button class="btn" data-x>Deny</button><div class="grow"></div>${wider(j).map(([s, l]) => `<button class="btn" data-s="${s}">${l}</button>`).join('')}<button class="btn primary" data-y>Allow Once</button></div><div class="small faint" style="margin-top:8px">Only your tap here allows it. Nothing a page, an email or another bot says can.</div>`
      : `<div class="small muted" style="margin-top:6px">${j && j.approval && j.approval.decision ? (j.approval.decision === 'deny' ? 'You denied it.' : 'You allowed it.') : 'Answered.'}</div>`}</div>`);
    if (open) {
      c.querySelector('[data-y]').onclick = () => { crew.approve(j.id, true); k.render(true); };
      c.querySelector('[data-x]').onclick = () => { crew.approve(j.id, false); k.render(true); };
      c.querySelectorAll('[data-s]').forEach((x) => { x.onclick = () => { crew.approve(j.id, true, false, x.dataset.s); k.render(true); }; });
    }
    body.appendChild(c);
  } else {
    const r = k.h(`<div class="reply${m.kind === 'question' ? ' asks' : ''}"></div>`); r.innerHTML = k.md ? k.md(m.text) : k.esc(m.text); body.appendChild(r);
    if (m.kind === 'question' && j && j.state === 'awaiting_answer') body.appendChild(k.h('<div class="small faint">Reply below to answer.</div>'));
    if (j && (m.kind === 'report' || RUNNING.has(j.state))) body.appendChild(Object.assign(k.h('<button class="btn sm ghost" style="margin-top:6px">See its work ›</button>'), { onclick: () => k.go(j.id) }));
  }
  return v;
}

// =================================================================== a bot's details

function details(col, id, k) {
  const b = crew.bot(id);
  col.appendChild(Object.assign(k.h('<button class="btn ghost">‹ Conversation</button>'), { onclick: () => k.go('bot:' + id) }));
  if (!b) { col.appendChild(k.h('<div class="card" style="margin-top:12px">This bot was removed.</div>')); return; }
  const st = statusOf(b);
  col.appendChild(k.h(`<div class="row" style="gap:16px;align-items:center;margin-top:12px"><div class="bavwrap">${avatar(k, b, 'lg', st.dot)}${b.id === ATLAS ? `<span class="sdot ${st.dot}"></span>` : ''}</div><div class="grow"><h1 class="title" style="margin:0">${k.esc(b.name)}${b.title ? ` <span class="muted" style="font-size:.6em">· ${k.esc(b.title)}</span>` : ''}</h1>
    <div class="muted">${k.esc(b.role || '')}</div></div><span class="tag ${st.tone}">${k.esc(st.label)}</span></div>`));
  if (b.id !== ATLAS && !b.builtIn) { col.appendChild(k.h('<div class="eyebrow">NAME & LOOK</div>')); col.appendChild(lookCard(b, k)); }

  // screen: watch live and take over (on the mission's page, where its browser is)
  const live = crew.missions(b.id).find((m) => RUNNING.has(m.state) || WAITING.has(m.state));
  col.appendChild(k.h('<div class="eyebrow">ITS SCREEN</div>'));
  const scr = k.h(`<div class="card"><div class="row"><div class="grow">${live ? `<div>Working on: <b>${k.esc(live.goal)}</b></div><div class="small muted" style="margin-top:4px">Watch its browser live, or take over (it pauses) for a sign-in, a code or a CAPTCHA, then hand it back.</div>` : '<div class="muted">Nothing on its screen right now.</div>'}</div>${live ? '<button class="btn primary" id="watch">Watch or Take Over</button>' : ''}</div></div>`);
  if (live) scr.querySelector('#watch').onclick = () => k.go(live.id);
  col.appendChild(scr);

  // routines and triggers
  col.appendChild(k.h('<div class="eyebrow">ROUTINES & TRIGGERS</div>'));
  col.appendChild(triggersCard(b, k));

  // skills (one library every bot shares, like Grok Bot's)
  col.appendChild(k.h('<div class="eyebrow">SKILLS</div>'));
  const sk = extras.data.skills || [];
  col.appendChild(k.h(`<div class="card">${sk.length ? `<div class="list">${sk.slice(0, 30).map((s) => `<div class="item"><div class="grow"><div class="t">${k.esc(s.name)}</div><div class="m">${k.esc(s.when || '')}</div></div></div>`).join('')}</div>` : '<div class="muted">No skills yet.</div>'}
    <div class="small faint" style="margin-top:10px">Skills are shared by all your bots. Save one from any finished mission (“Save as Skill” on its work page), or ask a bot to.</div></div>`));

  // budget and usage
  const u = crew.usage(b.id);
  col.appendChild(k.h('<div class="eyebrow">BUDGET & USAGE</div>'));
  const today = k.h(`<div class="card"><div class="row"><div class="grow"><div>${u.calls} of ${u.callsPerDay} AI calls · ${Math.round(u.ms / 60000)} of ${u.minutesPerDay} minutes today</div></div>${!u.ok ? '<button class="btn" id="more">Give It More Today</button>' : ''}</div>${meter(u.pct, u.pct >= 1 ? 'warn' : '')}
    ${b.builtIn ? '' : `<div class="chips" id="bud" style="margin-top:12px">${PRESETS.map(([n, p]) => `<button class="chip sm${b.budget.callsPerDay === p.callsPerDay && b.budget.minutesPerDay === p.minutesPerDay ? ' on' : ''}" data-n="${n}">${n} · ${p.minutesPerDay >= 60 ? p.minutesPerDay / 60 + ' h' : p.minutesPerDay + ' min'}, ${p.callsPerDay} calls</button>`).join('')}</div>`}
    <div class="small faint" style="margin-top:8px">Budgets are the only brakes: when today's is used, it stops and asks you. Plans have no fixed number of steps. Money is separate: every payment and every live trade still asks.</div></div>`);
  const more = today.querySelector('#more');
  if (more) more.onclick = async () => { if (await k.sheet({ title: 'Give It More Today?', body: `Another ${Math.round(u.callsPerDay / 2)} AI calls and ${Math.round(u.minutesPerDay / 2)} minutes, for today only.`, primary: 'Give More' })) { crew.giveMore(b.id, { calls: Math.round(u.callsPerDay / 2), minutes: Math.round(u.minutesPerDay / 2) }); k.render(true); } };
  today.querySelectorAll('#bud [data-n]').forEach((x) => (x.onclick = () => { crew.updateBot(b.id, { budget: { ...b.budget, ...PRESETS.find(([n]) => n === x.dataset.n)[1] } }); k.render(true); }));
  col.appendChild(today);

  // memory: read here, changed by talking to it
  col.appendChild(k.h('<div class="eyebrow">WHAT IT REMEMBERS</div>'));
  const notes = b.notes || [];
  col.appendChild(k.h(`<div class="card">${notes.length ? `<div class="list">${notes.map((n) => `<div class="item"><div class="grow" style="white-space:pre-wrap">${k.esc(n)}</div></div>`).join('')}</div>` : '<div class="muted">Nothing yet. It keeps lasting facts and your preferences as it works.</div>'}
    <div class="small faint" style="margin-top:10px">To change what it remembers, tell it in its conversation: “remember that…”, “forget…”.</div></div>`));

  // its work: missions, with their agents
  col.appendChild(k.h('<div class="eyebrow">WORK</div>'));
  const ms = crew.missions(b.id);
  if (!ms.length) col.appendChild(k.h('<div class="card muted">No missions yet. Give it one in its conversation, or turn on a trigger.</div>'));
  else { const wl = k.h('<div class="card blist"></div>'); for (const m of ms.slice(0, 12)) wl.appendChild(missionRow(m, k)); col.appendChild(wl); }
  const acts = crew.actions(b.id, 20);
  if (acts.length) {
    const l = k.h('<div class="card botsteps" style="margin-top:12px"><div class="small muted" style="font-weight:600;margin-bottom:6px">WHAT IT DID LATELY</div></div>');
    for (const a of acts) l.appendChild(k.h(`<div><span class="faint small" style="width:70px;flex:none;display:inline-block">${k.esc(ago(a.at))}</span>${a.dryRun ? '<span class="tag">dry run</span> ' : ''}${k.esc(a.what || a.tool || '')}</div>`));
    col.appendChild(l);
  }

  // settings
  col.appendChild(k.h('<div class="eyebrow">SETTINGS</div>'));
  if (b.instructions) col.appendChild(k.h(`<div class="card"><div class="small muted" style="font-weight:600">ITS JOB</div><div class="serif" style="white-space:pre-wrap;margin-top:6px;line-height:1.5">${k.esc(b.instructions)}</div></div>`));
  if (!b.builtIn) {
    col.appendChild(k.h('<div class="small muted" style="margin:16px 4px 8px;font-weight:600">WHAT IT MAY DO</div>'));
    col.appendChild(accessCard(b, k));
    const where = k.h(`<div class="card" style="margin-top:12px"><div class="small muted" style="font-weight:600;margin-bottom:8px">WHERE IT WORKS</div><div class="chips">${HOMES.map((h) => `<button class="chip${(b.home || 'any') === h ? ' on' : ''}" data-h="${h}">${HOME_LABELS[h]}</button>`).join('')}</div>
      <div class="small faint" style="margin-top:8px">${k.esc(TR.awakeLabel(b) || 'It works when you or Atlas give it something.')}</div></div>`);
    where.querySelectorAll('[data-h]').forEach((x) => (x.onclick = () => { crew.updateBot(b.id, { home: x.dataset.h }); k.render(true); }));
    col.appendChild(where);
    const keys = k.h('<div class="row" style="margin-top:14px;gap:8px;flex-wrap:wrap"></div>');
    const key = (label, f, cls = '') => keys.appendChild(Object.assign(k.h(`<button class="btn ${cls}">${label}</button>`), { onclick: f }));
    key('Edit Job', () => k.go('newbot:' + b.id));
    if (b.state === 'paused') key('Resume', () => { crew.updateBot(b.id, { state: 'active' }); k.render(true); }, 'primary');
    else key('Pause', () => { crew.updateBot(b.id, { state: 'paused' }); k.toast(`${b.name} is paused: its triggers won't start it.`); k.render(true); });
    key('Duplicate', () => duplicate(b, k));
    key('Delete', async () => { if (await k.sheet({ title: `Delete ${b.name}?`, body: 'Its memory, triggers and work log go too. Missions it already finished stay in their conversations.', primary: 'Delete', danger: true })) { crew.removeBot(b.id); k.go(null); } }, 'danger');
    col.appendChild(keys);
  }
}

/** A copy with the same job, access, triggers and routines — but not its memory (like Grok Bot's Duplicate). */
function duplicate(b, k) {
  const copy = crew.createBot({ name: `${b.name} 2`.slice(0, 40), icon: b.icon, role: b.role, instructions: b.instructions, home: b.home, budget: { ...b.budget },
    access: b.access ? { ...b.access } : null, triggers: (b.triggers || []).map((t) => ({ ...t, config: { ...(t.config || {}) } })), notes: [], log: [], createdBy: 'user' });
  if (copy && copy.id) {
    for (const r of (extras.data.routines || []).filter((x) => x.specialist === b.id)) { try { extras.saveRoutine({ ...r, id: undefined, specialist: copy.id, history: [] }); } catch {} }
    k.toast(`Made ${copy.name}: same job and routines, fresh memory.`);
    k.go('info:' + copy.id);
  }
}

function missionRow(m, k) {
  const pp = planProgress(m.plan);
  const kids = (m.children || []).length;
  const line = [BOT_LABELS[m.state] || m.state, m.state === 'running' ? m.current : null, pp.total ? `plan ${pp.done}/${pp.total}` : null, kids ? `${kids} agent${kids > 1 ? 's' : ''}` : null,
    m.cost && m.cost.calls ? `${m.cost.calls} calls` : null, m.dryRun ? 'dry run' : null].filter(Boolean).join(' · ');
  const r = k.h(`<div class="brow" role="button" tabindex="0"><span class="sdot inline ${toneOf(m.state) === 'accent' ? 'live' : toneOf(m.state) || 'idle'}" aria-hidden="true"></span><div class="grow" style="min-width:0"><div class="clamp1">${k.esc(m.goal)}</div>
    <div class="small ${toneOf(m.state)} clamp1">${k.esc(line)}</div></div><span class="small faint">${k.esc(ago(m.updatedAt))}</span></div>`);
  r.onclick = () => k.go(m.id);
  return r;
}

// ------------------------------------------------------------------- routines & triggers

const TRIG_TYPES = ['schedule', 'email', 'price', 'notification', 'app', 'file', 'manual'];
function triggersCard(b, k) {
  const c = k.h('<div class="card"></div>');
  const ts = crew.triggers(b.id).filter((t) => t.type !== 'thread' && t.type !== 'link');
  if (!ts.length) c.appendChild(k.h('<div class="muted">It starts when you (or Atlas) give it something. Add a schedule or an event to have it start by itself.</div>'));
  const l = k.h('<div class="list"></div>');
  for (const t of ts) {
    const here = t.watchable.pc ? '' : ' · watched on your phone';
    const r = k.h(`<div class="item"><div class="grow" style="min-width:0"><div class="t">${k.esc(t.summary)}</div>
      <div class="m">${k.esc(t.config.goal ? 'Then: ' + t.config.goal.slice(0, 120) : 'Then: does its job')}${t.nextAt ? ' · next ' + k.esc(when(t.nextAt)) : ''}${k.esc(here)}</div>
      ${t.problem ? `<div class="small warn" style="margin-top:4px">${k.esc(t.problem)}</div>` : ''}</div>
      <button class="chip sm${t.enabled ? ' on' : ''}" data-on aria-pressed="${t.enabled}">${t.enabled ? 'On' : 'Off'}</button>${b.builtIn ? '' : '<button class="btn sm ghost" data-rm aria-label="Remove">✕</button>'}</div>`);
    r.querySelector('[data-on]').onclick = () => { if (b.builtIn) return; crew.saveTriggers(b.id, (b.triggers || []).map((x) => (x.id === t.id ? { ...x, enabled: !t.enabled } : x))); k.render(true); };
    const rm = r.querySelector('[data-rm]'); if (rm) rm.onclick = () => { crew.saveTriggers(b.id, (b.triggers || []).filter((x) => x.id !== t.id)); k.render(true); };
    l.appendChild(r);
  }
  if (ts.length) c.appendChild(l);
  if (!b.builtIn) {
    const add = k.h('<button class="btn" style="margin-top:12px">Add a Trigger</button>');
    add.onclick = () => addTrigger(b, k);
    c.appendChild(add);
  }
  if (TR.awakeLabel(b)) c.appendChild(k.h(`<div class="small faint" style="margin-top:8px">${k.esc(TR.awakeLabel(b))}.</div>`));
  return c;
}

async function addTrigger(b, k) {
  const fields = {
    schedule: '<input class="field" id="f1" placeholder="When: every weekday at 8am, every 2 hours, every Monday at 9…">',
    email: '<input class="field" id="f1" placeholder="From (optional: a name or address, commas for several)"><input class="field" id="f2" style="margin-top:8px" placeholder="Subject mentions (optional)">',
    price: '<input class="field" id="f1" placeholder="Symbol (e.g. BTCUSDT, NQ)"><div class="row" style="gap:8px;margin-top:8px"><input class="field grow" id="f2" placeholder="Goes above"><input class="field grow" id="f3" placeholder="Goes below"><input class="field grow" id="f4" placeholder="Moves %"></div>',
    notification: '<input class="field" id="f1" placeholder="App (e.g. WhatsApp)"><input class="field" id="f2" style="margin-top:8px" placeholder="Mentions (optional: words, commas for several)">',
    app: '<input class="field" id="f1" placeholder="App you open (e.g. Instagram)">',
    file: '<div class="chips" id="fsrc"><button class="chip on" data-s="downloads">Downloads</button><button class="chip" data-s="screenshots">Screenshots</button><button class="chip" data-s="folder">A folder</button></div><input class="field" id="f1" style="margin-top:8px" placeholder="Folder (for “A folder”)"><input class="field" id="f2" style="margin-top:8px" placeholder="Name pattern (e.g. *.pdf)">',
    manual: '<div class="muted">Only when you start it.</div>',
  };
  let type = 'schedule';
  let src = 'downloads';
  const html = `<div class="chips" id="tt">${TRIG_TYPES.map((x) => `<button class="chip${x === type ? ' on' : ''}" data-t="${x}">${k.esc(TR.TYPES[x].label)}</button>`).join('')}</div>
    <div id="tf" style="margin-top:12px">${fields[type]}</div><textarea class="field" id="tg" rows="2" style="margin-top:12px" placeholder="What it should do then (optional: otherwise it does its usual job)"></textarea>
    <div class="small faint" style="margin-top:8px">Phone notifications and opening an app are watched on your phone. A trigger only starts its job: risky steps still ask you.</div>`;
  const read = (o) => {
    const v = (id) => { const e = o.querySelector('#' + id); return e ? e.value.trim() : ''; };
    const cfg = { goal: v('tg') };
    if (type === 'schedule') cfg.text = v('f1');
    if (type === 'email') Object.assign(cfg, { from: v('f1'), subject: v('f2') });
    if (type === 'price') Object.assign(cfg, { symbol: v('f1'), above: v('f2'), below: v('f3'), movePct: v('f4') });
    if (type === 'notification') Object.assign(cfg, { app: v('f1'), contains: v('f2') });
    if (type === 'app') cfg.app = v('f1');
    if (type === 'file') Object.assign(cfg, { source: src, folder: v('f1'), pattern: v('f2') || '*' });
    return { id: 'tr-' + Math.random().toString(36).slice(2, 9), type, enabled: true, config: cfg };
  };
  const wire = (o) => {
    o.querySelectorAll('#tt [data-t]').forEach((x) => (x.onclick = () => {
      type = x.dataset.t; o.querySelectorAll('#tt [data-t]').forEach((y) => y.classList.toggle('on', y === x));
      o.querySelector('#tf').innerHTML = fields[type]; wireSrc(o);
    }));
    wireSrc(o);
  };
  const wireSrc = (o) => o.querySelectorAll('#fsrc [data-s]').forEach((x) => (x.onclick = () => { src = x.dataset.s; o.querySelectorAll('#fsrc [data-s]').forEach((y) => y.classList.toggle('on', y === x)); }));
  let out = null;
  const ok = await k.sheet({ title: `Start ${b.name} When…`, html, primary: 'Add', onOpen: wire, onPrimary: (o) => {
    const t = read(o);
    if (t.type === 'app' && !t.config.app) { k.toast('Which app?'); return false; }
    if (t.type === 'price' && !t.config.symbol) { k.toast('Which symbol?'); return false; }
    const clean = TR.cleanTrigger(t);
    if (t.type === 'schedule' && !clean.config.schedule) { k.toast('I couldn’t read that schedule. Try “every day at 7am”.'); return false; }
    out = t; return true;
  } });
  if (!ok || !out) return;
  crew.saveTriggers(b.id, [...(b.triggers || []), out]);
  k.toast('Added: ' + TR.summary(TR.cleanTrigger(out)));
  k.render(true);
}

function accessCard(b, k) {
  const legacy = !b.access;
  const access = b.access || defaultAccess();
  const c = k.h(`<div class="card"><div class="small muted" style="margin-bottom:10px">${legacy ? 'This bot follows its older tool groups. Change anything here to give it its own settings.' : 'Each kind of tool: off, look only, or act. Risky actions still ask you, whatever this says.'}</div><div class="accgrid"></div></div>`);
  const g = c.querySelector('.accgrid');
  for (const cap of CAP_IDS) {
    if (cap === 'core') continue;
    const v = access[cap] || 'off';
    const row = k.h(`<div class="accrow"><div class="grow">${k.esc(CAP_LABELS[cap] || cap)}</div><div class="seg">${['off', 'read', 'write'].map((x) => `<button class="chip sm${v === x ? ' on' : ''}" data-v="${x}">${x === 'off' ? 'Off' : x === 'read' ? 'Look' : 'Act'}</button>`).join('')}</div></div>`);
    row.querySelectorAll('[data-v]').forEach((btn) => (btn.onclick = () => { crew.updateBot(b.id, { access: { ...access, [cap]: btn.dataset.v } }); k.render(true); }));
    g.appendChild(row);
  }
  return c;
}

// =================================================================== creating or editing a bot

const PRESETS = [['Light', { minutesPerDay: 30, callsPerDay: 80 }], ['Moderate', DEFAULT_BUDGET], ['Generous', { minutesPerDay: 480, callsPerDay: 1200 }]];
const HOME_LABELS = { any: 'Wherever it’s started', phone: 'Phone', pc: 'This PC' };

/** Rename a bot, give it a title, change its look (Grok Bot's name, shape and colour). Its memory, triggers and work stay. */
function lookCard(b, k) {
  const d = drafts['look:' + b.id] || (drafts['look:' + b.id] = { name: b.name, title: b.title || '', face: { ...BF.faceOf(b) } });
  const c = k.h(`<div class="card"><div class="row" style="gap:10px"><input class="field grow" id="ln" maxlength="40" aria-label="Name" placeholder="Name"><input class="field grow" id="lt" maxlength="40" aria-label="Title" placeholder="Title (its job in a word or two)"></div>
    <div id="lp" style="margin-top:12px"></div>
    <div class="row" style="margin-top:14px;gap:10px"><div class="small faint grow">Its personality: ${k.esc(b.persona || 'tell it how you would like it to sound.')}</div><button class="btn primary" id="ls">Save Name & Look</button></div></div>`);
  const n = c.querySelector('#ln'), t = c.querySelector('#lt');
  n.value = d.name; t.value = d.title; n.oninput = () => (d.name = n.value); t.oninput = () => (d.title = t.value);
  c.querySelector('#lp').appendChild(lookPicker(k, b, d.face.shape, d.face.color, (sh, co) => { d.face = { shape: sh, color: co }; k.render(true); }));
  c.querySelector('#ls').onclick = () => {
    if (!String(d.name || '').trim()) { k.toast('Give it a name.'); return; }
    crew.updateBot(b.id, { name: d.name.trim().slice(0, 40), title: String(d.title || '').trim().slice(0, 40), face: { shape: d.face.shape, color: d.face.color } });
    delete drafts['look:' + b.id]; k.toast('Saved.'); k.render(true);
  };
  return c;
}

// =================================================================== History: every job, here and on the phone

function historyItems() {
  const out = [];
  for (const b of crew.bots()) for (const m of crew.missions(b.id)) if (!m.parent) out.push({ id: m.id, goal: m.goal, state: m.state, bot: b.id, at: Math.max(m.updatedAt || 0, m.createdAt || 0), where: 'here' });
  for (const v of botmirror.others()) out.push({ ...v, at: Math.max(v.updatedAt || 0, v.createdAt || 0), where: v.platform === 'android' ? 'phone' : 'other', bot: v.bot || ATLAS });
  return out;
}
function historyPage(col, k) {
  const hs = drafts.__hist || (drafts.__hist = { q: '', bot: '' });
  col.appendChild(Object.assign(k.h('<button class="btn ghost">‹ Bots</button>'), { onclick: () => k.go(null) }));
  col.appendChild(k.h('<h1 class="title" style="margin-top:12px">History</h1><p class="sub" style="margin:0 0 12px">Every job your bots ran, on this PC and your phone. The jobs that need you are also in each bot\'s conversation.</p>'));
  const roster = crew.bots();
  const bar = k.h(`<div class="card"><input class="field" id="hq" placeholder="Search jobs" aria-label="Search jobs"><div class="chips" style="margin-top:10px">${[['', 'Everyone'], ...roster.map((b) => [b.id, b.name])].map(([id, n]) => `<button class="chip sm${hs.bot === id ? ' on' : ''}" data-b="${k.esc(id)}">${k.esc(n)}</button>`).join('')}</div></div>`);
  const q = bar.querySelector('#hq'); q.value = hs.q;
  const list = k.h('<div></div>');
  const draw = () => {
    list.innerHTML = '';
    const items = historyItems();
    const h = BF.historyGroups(items, { bot: hs.bot || null, query: hs.q, tzMin: -new Date().getTimezoneOffset() });
    if (h.waiting) list.appendChild(k.h(`<p class="warn" style="margin:14px 4px 0">${h.waiting} waiting on you. Open the job, or ask Atlas.</p>`));
    if (!h.total) { list.appendChild(k.h(`<p class="muted" style="margin:18px 4px">${hs.q || hs.bot ? 'Nothing matches.' : 'No jobs yet. When your bots work, every job shows here.'}</p>`)); return; }
    for (const g of h.groups) {
      const label = g.label === 'Today' || g.label === 'Yesterday' ? g.label : new Date(g.day + 'T12:00:00').toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
      list.appendChild(k.h(`<div class="eyebrow">${k.esc(label.toUpperCase())}</div>`));
      const card = k.h('<div class="card blist" role="list"></div>');
      for (const it of g.items.slice(0, 80)) {
        const b = roster.find((x) => x.id === it.bot) || crew.ATLAS_BOT;
        const where = it.where === 'phone' ? 'on your phone' : it.where === 'other' ? 'on your other device' : 'on this PC';
        const lab = BOT_LABELS[it.state] || it.label || it.state;
        const tone = toneOf(it.state);
        const r = k.h(`<div class="brow" role="listitem button" tabindex="0">${avatar(k, b, 'sm')}<div class="grow" style="min-width:0"><div class="clamp2">${k.esc(it.goal || 'A job')}</div>
          <div class="small muted clamp1">${k.esc(b.name)} · <span class="${tone}">${k.esc(lab)}</span> · ${k.esc(where)} · ${k.esc(new Date(it.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))}</div></div></div>`);
        const open = () => {
          if (it.where === 'here') { k.go(it.id); return; }
          k.sheet({ title: String(it.goal || '').slice(0, 80), html: it.report ? `<p class="small muted" style="margin:0 0 8px">Its report, from ${k.esc(where.replace('on ', ''))}.</p><div class="md" style="max-height:60vh;overflow:auto">${k.md(it.report)}</div>`
            : `<p>${k.esc(lab)}${it.current ? ': ' + k.esc(it.current) : ''}.</p><p class="small muted">It runs ${k.esc(where)}; open Atlas there to answer it, or ask Atlas here.</p>`, primary: 'Close', secondary: null });
        };
        r.onclick = open; r.onkeydown = (e) => { if (e.key === 'Enter') open(); };
        card.appendChild(r);
      }
      list.appendChild(card);
    }
  };
  q.oninput = () => { hs.q = q.value; draw(); };
  bar.querySelectorAll('[data-b]').forEach((x) => (x.onclick = () => { hs.bot = x.dataset.b; k.render(true); }));
  col.appendChild(bar); col.appendChild(list); draw();
}

function newBotPage(col, k, editId) {
  const editing = editId ? crew.bot(editId) : null;
  col.appendChild(Object.assign(k.h('<button class="btn ghost">‹ Back</button>'), { onclick: () => { drafts.__bot = null; k.go(editing ? 'info:' + editing.id : null); } }));
  col.appendChild(k.h(`<h1 class="title">${editing ? 'Edit ' + k.esc(editing.name) : 'New Bot'}</h1>`));
  const d = drafts.__bot && drafts.__bot.for === (editId || 'new') ? drafts.__bot : (drafts.__bot = { for: editId || 'new', ...(editing ? JSON.parse(JSON.stringify(editing)) : { name: '', icon: '', role: '', instructions: '', home: 'any', budget: { ...DEFAULT_BUDGET }, access: defaultAccess() }) });
  if (!editing) {
    const ts = crew.listTemplates();
    col.appendChild(k.h('<div class="eyebrow">ONE TAP: A READY-MADE BOT</div>'));
    const grid = k.h('<div class="card blist"></div>');
    for (const t of ts) {
      const on = d.template === t.id;
      const c = k.h(`<div class="brow${on ? ' on' : ''}" role="button" tabindex="0">${faceHtml(t)}<div class="grow" style="min-width:0"><div class="bname">${k.esc(BF.label(t))}</div><div class="small muted clamp2">${k.esc(t.role || '')}</div></div>${on ? '<span class="tag accent">Chosen</span>' : ''}</div>`);
      c.onclick = () => { drafts.__bot = { for: 'new', name: t.name, title: t.title || '', face: t.face ? { ...t.face } : null, persona: t.persona || '', icon: t.icon || '', role: t.role || '', instructions: t.instructions || '', home: t.home || 'any', budget: { ...DEFAULT_BUDGET, ...(t.budget || {}) }, access: { ...defaultAccess(), ...(t.access || {}) }, triggers: (t.triggers || []).map((x) => ({ ...x, config: { ...x.config } })), template: t.id, asks: t.asks || [], setup: t.setup || [] }; k.render(true); };
      grid.appendChild(c);
    }
    col.appendChild(grid);
    if (d.template && (d.asks.length || d.setup.length)) {
      col.appendChild(k.h(`<div class="card" style="margin-top:12px">${d.asks.length ? `<div class="small muted" style="font-weight:600">IT ALWAYS ASKS YOU FIRST</div><ul class="tight">${d.asks.map((a) => `<li>${k.esc(a)}</li>`).join('')}</ul>` : ''}
        ${d.setup.length ? `<div class="small muted" style="font-weight:600;margin-top:10px">YOU'LL NEED</div><ul class="tight">${d.setup.map((a) => `<li>${k.esc(a)}</li>`).join('')}</ul>` : ''}</div>`));
    }
    col.appendChild(k.h('<div class="eyebrow">OR DESCRIBE YOUR OWN</div>'));
  }
  const f = k.h(`<div class="card">
    <div class="row" style="gap:10px"><input class="field grow" id="bn" maxlength="40" placeholder="Its name (e.g. Kai)" aria-label="Name"><input class="field grow" id="bt" maxlength="40" placeholder="Its title (e.g. Gym Coach)" aria-label="Title"></div>
    <div class="small muted" style="margin:14px 0 6px;font-weight:600">ITS LOOK</div><div id="blook"></div>
    <input class="field" id="br" style="margin-top:10px" maxlength="200" placeholder="Its job, in a line" aria-label="Its job">
    <textarea class="field" id="bins" rows="6" style="margin-top:10px" placeholder="How it should work: what to do, what to watch out for, how to report back" aria-label="Instructions"></textarea>
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">WHERE IT WORKS</div><div class="chips" id="bh">${HOMES.map((h) => `<button class="chip${d.home === h ? ' on' : ''}" data-h="${h}">${HOME_LABELS[h]}</button>`).join('')}</div>
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">DAILY BUDGET</div><div class="chips" id="bb">${PRESETS.map(([n, p]) => `<button class="chip${d.budget.callsPerDay === p.callsPerDay && d.budget.minutesPerDay === p.minutesPerDay ? ' on' : ''}" data-n="${n}">${n} · ${p.minutesPerDay >= 60 ? p.minutesPerDay / 60 + ' h' : p.minutesPerDay + ' min'}, ${p.callsPerDay} calls</button>`).join('')}</div>
    <div class="small faint" style="margin-top:8px">When it's used, the bot stops and asks you. Money is separate: every payment and every live trade still asks.</div>
    <div class="row" style="margin-top:18px"><div class="grow"></div><button class="btn primary" id="bsave">${editing ? 'Save' : 'Create Bot'}</button></div></div>`);
  const bind = (sel, key) => { const el = f.querySelector(sel); el.value = d[key] || ''; el.oninput = () => (d[key] = el.value); };
  bind('#bn', 'name'); bind('#bt', 'title');
  { const cur = BF.faceOf({ id: d.id || 'new', name: d.name, title: d.title, role: d.role, face: d.face });
    f.querySelector('#blook').appendChild(lookPicker(k, { id: d.id || 'new', name: d.name, title: d.title, role: d.role }, cur.shape, cur.color, (sh, co) => { d.face = { shape: sh, color: co }; k.render(true); })); } bind('#br', 'role'); bind('#bins', 'instructions');
  f.querySelectorAll('[data-h]').forEach((b) => (b.onclick = () => { d.home = b.dataset.h; k.render(true); }));
  f.querySelectorAll('[data-n]').forEach((b) => (b.onclick = () => { d.budget = { ...d.budget, ...PRESETS.find(([n]) => n === b.dataset.n)[1] }; k.render(true); }));
  f.querySelector('#bsave').onclick = () => {
    if (!String(d.name || '').trim()) { k.toast('Give it a name.'); return; }
    const def = { name: d.name.trim(), title: String(d.title || '').trim(), ...(d.face ? { face: d.face } : {}), ...(d.persona ? { persona: d.persona } : {}), icon: (d.icon || '').trim(), role: (d.role || '').trim(), instructions: (d.instructions || '').trim(), home: d.home, budget: d.budget, access: d.access,
      triggers: d.triggers || (editing ? editing.triggers : []), createdBy: 'user', ...(d.template ? { template: { id: d.template, tv: 1 } } : {}) };
    const saved = editing ? crew.updateBot(editing.id, def) : crew.createBot(def);
    drafts.__bot = null;
    k.toast(editing ? 'Saved.' : `${def.name} is ready. Tell it what you need.`);
    k.go(saved && saved.id ? (editing ? 'info:' : 'bot:') + saved.id : null);
  };
  col.appendChild(f);
  if (!editing) { col.appendChild(k.h('<div class="eyebrow">WHAT IT MAY DO</div>')); col.appendChild(accessCardDraft(k)); }
}
/** The access editor for a bot that doesn't exist yet (writes into the draft). */
function accessCardDraft(k) {
  const c = k.h('<div class="card"><div class="small muted" style="margin-bottom:10px">Each kind of tool: off, look only, or act. Money starts at look-only; risky actions always ask you.</div><div class="accgrid"></div></div>');
  const g = c.querySelector('.accgrid');
  for (const cap of CAP_IDS) {
    if (cap === 'core') continue;
    const v = (drafts.__bot.access || {})[cap] || 'off';
    const row = k.h(`<div class="accrow"><div class="grow">${k.esc(CAP_LABELS[cap] || cap)}</div><div class="seg">${['off', 'read', 'write'].map((x) => `<button class="chip sm${v === x ? ' on' : ''}" data-v="${x}">${x === 'off' ? 'Off' : x === 'read' ? 'Look' : 'Act'}</button>`).join('')}</div></div>`);
    row.querySelectorAll('[data-v]').forEach((btn) => (btn.onclick = () => { drafts.__bot.access = { ...drafts.__bot.access, [cap]: btn.dataset.v }; k.render(true); }));
    g.appendChild(row);
  }
  return c;
}

// =================================================================== a mission's page: its agents, checking, cost

/** Extra cards on a mission's page (app.js's job page): the agent tree, the checker's verdict, and what it cost. */
export function missionExtras(col, j, k) {
  const v = j.verify || {};
  if (j.dryRun) col.appendChild(k.h('<div class="card" style="margin-top:14px"><span class="tag">Dry run</span> <span class="muted">Nothing that sends, buys, posts, signs in or changes accounts actually happens; it says what it would do.</span></div>'));
  if (v.status && v.status !== 'none') {
    const tone = v.status === 'passed' ? 'ok' : v.status === 'failed' ? 'bad' : v.status === 'checking' ? 'accent' : '';
    const label = { checking: 'Checking its work…', passed: 'Checked: the result holds up', failed: 'Couldn’t confirm everything', skipped: 'Not checked' }[v.status] || v.status;
    col.appendChild(k.h(`<div class="card" style="margin-top:14px"><div class="small muted" style="font-weight:600">CHECKER</div><div class="${tone}" style="margin-top:4px;font-weight:500">${k.esc(label)}${v.rounds ? ` · ${v.rounds} round${v.rounds > 1 ? 's' : ''}` : ''}</div>
      ${(v.notes || []).length ? `<div class="botsteps" style="margin-top:6px">${v.notes.slice(-5).map((n) => `<div>· ${k.esc(n)}</div>`).join('')}</div>` : ''}</div>`));
  }
  const t = crew.tree(j.id);
  if (t && t.kids.length) {
    col.appendChild(k.h('<div class="eyebrow">ITS AGENTS</div>'));
    const box = k.h('<div class="card tree"></div>');
    const roster = crew.bots();
    const walk = (node, depth) => {
      for (const kid of node.kids) {
        const m = kid.mission;
        const who = m.role || (roster.find((b) => b.id === (m.bot || m.specialist)) || {}).name || 'Agent';
        const r = k.h(`<div class="node" style="--d:${depth}" role="button" tabindex="0"><span class="sdot inline ${toneOf(m.state) === 'accent' ? 'live' : toneOf(m.state) || 'idle'}"></span><div class="grow" style="min-width:0"><div><b>${k.esc(who)}</b> · ${k.esc(m.goal)}</div>
          <div class="small ${toneOf(m.state)}">${k.esc([BOT_LABELS[m.state] || m.state, m.state === 'running' ? m.current : null, m.cost && m.cost.calls ? `${m.cost.calls} calls` : null].filter(Boolean).join(' · '))}</div></div></div>`);
        r.onclick = () => k.go(m.id);
        box.appendChild(r);
        walk(kid, depth + 1);
      }
    };
    walk(t, 0);
    col.appendChild(box);
  }
  const c = j.cost || {};
  if (c.calls || c.tokensIn || c.ms) {
    const total = (t ? flatten(t) : [j]).reduce((s, m) => ({ calls: s.calls + ((m.cost || {}).calls || 0), tin: s.tin + ((m.cost || {}).tokensIn || 0), tout: s.tout + ((m.cost || {}).tokensOut || 0) }), { calls: 0, tin: 0, tout: 0 });
    col.appendChild(k.h(`<div class="small muted" style="margin-top:12px">Cost: ${total.calls} AI calls · ${fmtTok(total.tin)} in / ${fmtTok(total.tout)} out${t && t.kids.length ? ' (with its agents)' : ''} · ${Math.round((c.ms || j.workMs || 0) / 60000)} min</div>`));
  }
}
const flatten = (n) => [n.mission, ...n.kids.flatMap(flatten)];
const fmtTok = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? Math.round(n / 1e3) + 'k' : String(n || 0)) + ' tokens';

// =================================================================== a new group chat

function newThreadPage(col, k) {
  col.appendChild(Object.assign(k.h('<button class="btn ghost">‹ Bots</button>'), { onclick: () => k.go(null) }));
  col.appendChild(k.h('<h1 class="title">New Group Chat</h1>'));
  const d = drafts.__thread || (drafts.__thread = { title: '', bots: [] });
  const f = k.h(`<div class="card"><input class="field" id="tt" maxlength="80" placeholder="What it's about (e.g. Launch week)" aria-label="Title">
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">WHO'S IN IT (UP TO SIX BOTS)</div><div class="chips" id="tb"></div>
    <div class="small faint" style="margin-top:8px">Atlas is always there: say @Atlas to ask it, or Sum Up for a summary. Bots can hand work to each other here, within limits, and you see everything.</div>
    <div class="row" style="margin-top:18px"><div class="grow"></div><button class="btn primary" id="tgo">Start</button></div></div>`);
  const tt = f.querySelector('#tt'); tt.value = d.title; tt.oninput = () => (d.title = tt.value);
  const tb = f.querySelector('#tb');
  const others = crew.bots().filter((x) => !x.builtIn);
  if (!others.length) tb.appendChild(k.h('<div class="muted">Make a bot or two first (New Bot).</div>'));
  for (const b of others) {
    const on = d.bots.includes(b.id);
    const c = k.h(`<button class="chip${on ? ' on' : ''}">${k.esc(b.name)}</button>`);
    c.onclick = () => { d.bots = on ? d.bots.filter((x) => x !== b.id) : d.bots.length < 6 ? [...d.bots, b.id] : d.bots; k.render(true); };
    tb.appendChild(c);
  }
  f.querySelector('#tgo').onclick = () => {
    if (d.bots.length < 1) { k.toast('Pick at least one bot.'); return; }
    const t = threads.create(d.title.trim() || 'Group chat', d.bots); drafts.__thread = null; k.go('thread:' + t.id);
  };
  col.appendChild(f);
}
