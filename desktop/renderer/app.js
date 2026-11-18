// Atlas on the PC: the screens (Atlas, Log, Me), alarms, focus sessions, and everything joined up.
import * as store from './store.js';
import { db, pref, setPref, onChange } from './store.js';
import * as brain from './brain.js';
import * as sync from './sync.js';
import * as keyshare from './keyshare.js';
import * as botmirror from './botmirror.js';
import * as voice from './voice.js';
import { endsTalk } from './speech.js';
import { Orb } from './orb.js';
import { renderMarkdown } from './md.js';
import * as UI from './uishape.js';   // replies: prose plus tidy rows (same rules as the phone)
import { plainMemory } from './plain.js';
import { bots, extras } from './botwire.js';
import * as BX from './botsui.js';
import * as CREW from './crewui.js';   // the Bots page, built like Grok Bot's (conversations first)
import * as THREADS from './threads.js';
import * as BTPL from './bottemplates.js';
import { registerTemplate } from './crewface.js';
import * as aware from './aware.js';
import { renderLibrary, refreshLibrary } from './library.js';
import * as fitRules from './fit.js';
import * as conn from './connect.js';   // >>> connections (MCP servers, web APIs)
import { renderConnections } from './connui.js';
import { renderEmail } from './mailui.js';   // >>> email: Me → Email
import * as trade from './trade.js';   // >>> trading (Me → Trading; the tools register themselves)
import * as LW from './linkwire.js';   // >>> device link (control across devices)
import { THEMES, DEFAULT as THEME_DEFAULT, findTheme } from './themes.js';   // >>> themes (Me → Look)
import * as AR from './attachrules.js';   // >>> attachments: what a file is, how Atlas reads it
import { progressTo } from './attachsend.js';
import { renderDevices } from './linkui.js';
import { S as BOT, LABELS as BOT_LABELS, FINAL as BOT_FINAL, WAITING_ON_USER as BOT_WAITING, summary as botSummary } from './bots.js';
const host = window.atlasHost;

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
function h(html) { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; }
let botView = null, botDraft = '';
let tab = 'atlas', logFull = false, brainShown = null, attachments = [], listenCtl = null, homeOrb = null;

// ------------------------------------------------------------------ small pieces

function toast(msg, ms = 2600) {
  document.querySelectorAll('.toast').forEach((t) => t.remove());
  const t = h(`<div class="toast">${esc(msg)}</div>`); document.body.appendChild(t); setTimeout(() => t.remove(), ms);
}
function sheet({ title, body = '', html = '', primary = 'OK', danger = false, secondary = 'Cancel', onPrimary = () => true, onOpen }) {
  return new Promise((resolve) => {
    const o = h(`<div class="overlay"><div class="card sheet"><h2>${esc(title)}</h2>${body ? `<p class="muted">${esc(body)}</p>` : ''}${html}
      <div class="actions">${secondary ? `<button class="btn" data-k="no">${esc(secondary)}</button>` : ''}${primary ? `<button class="btn ${danger ? 'danger' : 'primary'}" data-k="yes">${esc(primary)}</button>` : ''}</div></div></div>`);
    document.body.appendChild(o);
    const close = (v) => { o.remove(); resolve(v); };
    o.addEventListener('click', async (e) => {
      const k = e.target.closest('[data-k]')?.dataset.k;
      if (k === 'no' || e.target === o) close(false);
      if (k === 'yes') { if ((await onPrimary(o)) !== false) close(true); }
    });
    onOpen && onOpen(o);
    setTimeout(() => (o.querySelector('input,textarea') || o.querySelector('[data-k=yes]'))?.focus(), 30);
  });
}
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
function logged(ms) { const d = new Date(ms), t = new Date(); return d.toDateString() === t.toDateString() ? 'Today ' + fmtTime(ms) : d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }) + ', ' + fmtTime(ms); }
function whenLabel(ms) { if (ms == null) return 'No alarm'; const d = brain.dayLabel(ms); return d[0].toUpperCase() + d.slice(1) + ' ' + fmtTime(ms); }
const ICON = {
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L19 7"/></svg>',
  compose: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4.5H6a2 2 0 0 0-2 2V18a2 2 0 0 0 2 2h11.5a2 2 0 0 0 2-2v-5"/><path d="M17.5 3.5l3 3L12 15l-3.5.5.5-3.5z"/></svg>',
  mic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2.5"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
};

// ------------------------------------------------------------------ the screens

/**
 * Draws the current tab. [keepInputs]: a redraw that comes from the background (a sync, a reminder changing) keeps
 * what you were typing (a link code, a key, something to remember) and where the cursor was.
 */
function render(keepScroll, keepInputs) {
  const page = $('#page'); const y = page.scrollTop;
  const kept = keepInputs ? [...page.querySelectorAll('input[id]:not([type=checkbox]),textarea[id]')]
    .map((el) => ({ id: el.id, v: el.value, f: el === document.activeElement, s: el.selectionStart, e: el.selectionEnd })).filter((x) => x.v || x.f) : [];
  document.querySelectorAll('.nav').forEach((n) => n.classList.toggle('on', n.dataset.tab === tab));
  page.innerHTML = '';
  if (tab === 'atlas') renderAtlas(page); else if (tab === 'log') renderLog(page); else if (tab === 'bots') renderBots(page); else renderMe(page);
  $('#composer').style.display = tab === 'atlas' ? '' : 'none';
  for (const k of kept) {
    const el = page.querySelector('#' + CSS.escape(k.id)); if (!el) continue;
    if (k.v && !el.value) el.value = k.v;
    if (k.f) { el.focus(); try { el.setSelectionRange(k.s, k.e); } catch {} }
  }
  if (keepScroll) page.scrollTop = y;
  else if (tab === 'atlas' && db.chat.length) toLatest();
  renderFoot(); latestChip();
}

// ---- the chat opens at your latest messages ----
let followUntil = 0, awaySince = 0;
/** Jump to the newest messages, and keep following for a second while long replies and pictures finish laying out (unless you scroll). */
function toLatest(smooth) {
  const page = $('#page'); followUntil = Date.now() + 1200;
  const go = () => { if (Date.now() <= followUntil) page.scrollTo({ top: page.scrollHeight, behavior: smooth ? 'smooth' : 'auto' }); };
  go(); requestAnimationFrame(go);
  const body = page.firstElementChild; if (!body || typeof ResizeObserver === 'undefined') return;
  const ro = new ResizeObserver(() => { if (Date.now() > followUntil || tab !== 'atlas') ro.disconnect(); else page.scrollTop = page.scrollHeight; });
  ro.observe(body); setTimeout(() => ro.disconnect(), 1300);
}
/** "Latest ↓" shows on the Atlas page once you've scrolled up more than about a screen. */
function latestChip() {
  const page = $('#page'); let chip = $('#latest');
  const far = tab === 'atlas' && db.chat.length && page.scrollHeight - (page.scrollTop + page.clientHeight) > page.clientHeight * 0.8;
  if (!chip) {
    if (!far) return;
    chip = document.createElement('button'); chip.id = 'latest'; chip.className = 'latest-chip'; chip.textContent = 'Latest ↓';
    chip.setAttribute('aria-label', 'Jump to the latest messages'); chip.onclick = () => toLatest(true);
    document.body.appendChild(chip);
  }
  chip.classList.toggle('on', !!far);
}
function renderFoot() {
  const s = sync.code() ? (sync.status.last || 'Syncing…') : 'Not linked to your phone';
  $('#sidefoot').innerHTML = `${esc(s)}${sync.status.at ? '<br>' + esc(logged(sync.status.at)) : ''}`;
}

// ---- Atlas: talk ----
function renderAtlas(page) {
  const col = h('<div class="column"></div>'); page.appendChild(col);
  const name = (db.state.settings.name || '').trim().split(' ')[0];
  const msgs = db.chat;
  if (!msgs.length) {
    const hello = h('<div class="hello"></div>'); col.appendChild(hello);
    homeOrb = new Orb(hello, { size: 210 }); homeOrb.el.style.cursor = 'pointer'; homeOrb.el.title = 'Talk to Atlas'; homeOrb.el.onclick = () => startListening();
    hello.appendChild(h(`<h1>Hey ${esc(name || 'there')},<br>What can I help with?</h1>`));
    const ideas = h('<div class="ideas"></div>');
    for (const idea of ['Remind me to call mum tomorrow at 6pm', 'Open Spotify and play something chill', 'What can you do on my PC?'])
      ideas.appendChild(Object.assign(h(`<div class="card idea">${esc(idea)}</div>`), { onclick: () => { const ta = $('#ask'); ta.value = idea; ta.focus(); autosize(); updateSend(); } }));
    hello.appendChild(ideas);
    botStrip(hello, true);
    if (!brain.active()) hello.appendChild(h('<p class="muted small" style="margin-top:20px">To think, Atlas needs an AI key (Gemini\'s is free) or a local model: Me → Brain.</p>'));
    return;
  }
  const head = h(`<div class="chathead"><div class="grow"><div style="font:500 20px var(--serif)">Atlas</div><div class="small muted" id="brainline"></div></div>
    <button class="round" id="newchat" title="New chat" aria-label="New chat">${ICON.compose}</button></div>`);
  const orbSlot = h('<div style="width:56px;height:56px"></div>'); head.prepend(orbSlot); col.appendChild(head);
  homeOrb = new Orb(orbSlot, { size: 56 }); homeOrb.el.style.cursor = 'pointer'; homeOrb.el.onclick = () => startListening();
  head.querySelector('#newchat').onclick = async () => { if (await sheet({ title: 'Start a New Chat?', body: 'This clears the conversation here' + (sync.code() ? ' and on your phone' : '') + '. Your Log stays as it is.', primary: 'New Chat' })) { sync.noteDeleted('chat', db.chat.map((m) => m.id)); db.chat = []; store.saveChat(); render(); } };
  botStrip(col);
  const chat = h('<div class="chat" id="chat"></div>'); col.appendChild(chat);
  fillChat();
}
function brainLine() { const p = brain.active(); return p ? `${p.name} · ${brain.modelOf(p)}` + (brain.dontSwitch() ? ' · stays on this service' : '') + (!brain.dontSwitch() && brain.fallbacks(p.id).length ? ` · ${brain.fallbacks(p.id).length} backup service${brain.fallbacks(p.id).length > 1 ? 's' : ''}` : '') : 'No brain set up yet · add an AI key in Me'; }
function fillChat() {
  const chat = $('#chat'); if (!chat) return;
  const bl = $('#brainline'); if (bl) bl.textContent = brain.convo.busy && brain.convo.status ? brain.convo.status + '…' : brainLine();
  chat.innerHTML = '';
  db.chat.forEach((m, i) => chat.appendChild(messageView(m, i === db.chat.length - 1)));
  if (homeOrb) homeOrb.energy = brain.convo.busy || listenCtl ? 1 : 0;
}
function messageView(m, last) {
  if (m.user) {
    const v = h('<div class="msg you"></div>');
    if (m.media && m.media.length) {
      const f = h('<div class="files"></div>');
      for (const a of m.media) f.appendChild(a.thumb ? h(`<img class="thumb" src="${a.thumb}" alt="">`) : h(`<div class="card filetile"><b>${esc(a.kind === 'pdf' ? 'PDF' : a.kind === 'video' ? 'VIDEO' : (a.name || '').split('.').pop().toUpperCase().slice(0, 4))}</b>${esc(a.name || '')}</div>`));
      v.appendChild(f);
    }
    v.appendChild(Object.assign(h('<div class="bubble"></div>'), { textContent: m.text }));
    v.appendChild(msgActions(m));   // >>> smooth
    return v;
  }
  const v = h('<div class="msg atlas"></div>');
  const working = last && brain.convo.busy && m.pending;
  if (!m.text) v.appendChild(h(`<div class="thinking">${esc(working ? (brain.convo.status || 'Thinking') : '')}</div>`));
  else {
    // prose stays prose; a list of times or "Label: thing" lines becomes a glass card of rows
    for (const seg of UI.split(m.text)) {
      if (seg.rows) {
        const card = h('<div class="rows"></div>');
        for (const r of seg.rows) card.appendChild(h(`<div class="row ${r.tone}"><span class="key">${esc(r.key)}</span><span class="val">${esc(r.value)}</span></div>`));
        v.appendChild(card);
      } else { const r = h(`<div class="reply ${m.source === 'error' ? 'bad' : ''}"></div>`); r.innerHTML = renderMarkdown(seg.text); v.appendChild(r); }
    }
  }
  if (m.steps && m.steps.length) {
    // what Atlas did: small glass receipts with a check (click for all of it)
    const all = () => sheet({ title: 'What Atlas Did', html: `<div class="list">${m.steps.map((x) => `<div class="item">${esc(x)}</div>`).join('')}</div>`, primary: 'Close', secondary: null });
    const box = h('<div class="receipts"></div>');
    for (const x of m.steps.slice(0, 3)) box.appendChild(Object.assign(h(`<button class="receipt"><span class="tick">${ICON.check}</span><span>${esc(x)}</span></button>`), { onclick: all }));
    if (m.steps.length > 3) box.appendChild(Object.assign(h(`<button class="more">and ${m.steps.length - 3} more ›</button>`), { onclick: all }));
    v.appendChild(box);
  }
  const tag = working && m.text ? (brain.convo.status || 'Working') + '…' : (m.source === 'error' || m.source === 'Atlas' ? '' : m.source);
  if (tag) v.appendChild(h(`<div class="source">${esc(tag)}</div>`));
  if (m.text && !working) v.appendChild(msgActions(m));   // >>> smooth
  return v;
}
// >>> smooth: on hover, a message offers Copy, Remember and Ask Again
function msgActions(m) {
  const row = h('<div class="msgacts"><button title="Copy">Copy</button><button title="Save it to Atlas\'s memory">Remember</button><button title="Send the question again">Ask Again</button></div>');
  const [copy, keep, again] = row.children;
  copy.onclick = () => { navigator.clipboard.writeText(m.text); toast('Copied'); };
  keep.onclick = () => {
    const t = plainMemory(String(m.text).slice(0, 600)); if (!t) return toast('Nothing to remember there.');
    if (db.state.memories.some((x) => x.text.trim().toLowerCase() === t.toLowerCase())) return toast('Already remembered.');
    store.remember(t); toast('Remembered.');
  };
  const i = db.chat.indexOf(m); const ask = m.user ? m : db.chat.slice(0, Math.max(i, 0)).reverse().find((x) => x.user);
  if (!ask || !ask.text) again.remove();
  // the question alone: whatever is waiting in the tray stays there for your next message
  else again.onclick = () => { if (brain.convo.busy) return toast('Atlas is still answering.'); const keepAtt = attachments; attachments = []; submit(false, ask.text); attachments = keepAtt; showTray(); updateSend(); };
  return row;
}
// <<< smooth

// ---- Atlas Bot ----
/** A small glass strip while bots are working (or recently done): click to see them. */
function botStrip(parent, center) {
  const line = botSummary(bots.jobs, Date.now());
  if (!line) return;
  const b = h(`<div class="botstrip${center ? ' center' : ''}" id="botstrip" role="button" tabindex="0">Atlas Bot · <span>${esc(line)}</span> ›</div>`);
  b.onclick = () => { tab = 'bots'; botView = null; render(); };
  parent.appendChild(b);
}
/** The small helpers botsui.js draws with. */
const kit = () => ({ h, esc, sheet, toast, render });
/** …and what the Bots page (crewui.js) needs on top: moving between its views, Markdown, and what goes under the list. */
const crewKit = () => ({
  ...kit(), md: renderMarkdown,
  go: (v) => { botView = v; botDraft = ''; render(); },
  after: (col) => {
    const lib = h('<div class="card blist" style="margin-top:12px"><div class="brow" role="button" tabindex="0"><div class="bav">▤</div><div class="grow"><div class="bname">Library</div><div class="small muted">Documents, spreadsheets, pages and downloads your bots made</div></div><span class="small faint">›</span></div></div>');
    lib.onclick = () => { botView = 'library'; refreshLibrary(); render(); };
    col.appendChild(lib);
    botsElsewhere(col);
    BX.moreSections(col, kit());
  },
});
function botColor(s) { return s === BOT.COMPLETED ? 'ok' : s === BOT.FAILED ? 'bad' : BOT_WAITING.has(s) ? 'warn' : s === BOT.CANCELLED || s === BOT.PAUSED ? 'muted' : 'accent'; }
function renderBots(page) {
  const col = h('<div class="column"></div>'); page.appendChild(col);
  // >>> library: everything bots made (library.js)
  if (botView === 'library') { renderLibrary(col, kit(), () => { botView = null; render(); }); return; }
  // <<< library
  const j = botView && bots.job(botView);
  if (!j) {
    // the Bots page (crewui.js): a list of conversations, each bot's own conversation, its details, group chats
    if (CREW.render(col, botView, crewKit())) return;
    botView = null; CREW.render(col, null, crewKit());
    return;
  }
  const up = j.parent && bots.job(j.parent);
  const ownBot = j.bot || j.specialist || 'atlas';
  col.appendChild(Object.assign(h(`<button class="btn ghost" style="margin-bottom:14px">‹ ${up ? 'Back to Its Bot' : 'Conversation'}</button>`), { onclick: () => { botView = up ? up.id : 'bot:' + ownBot; render(); } }));
  col.appendChild(h(`<h1 class="title" style="font-size:30px">${esc(j.goal)}</h1>`));
  if (j.details) col.appendChild(h(`<p class="muted" style="margin:0 0 8px;white-space:pre-wrap">${esc(j.details.slice(0, 600))}</p>`));
  const now = j.state === BOT.RUNNING ? `${BOT_LABELS[j.state]}: ${j.current || 'Thinking'}` : j.state === BOT.WAITING_RETRY ? `${BOT_LABELS[j.state]} at ${fmtTime(j.retryAt)}${j.error ? ` (${j.error})` : ''}`
    : j.state === BOT.FAILED ? `${BOT_LABELS[j.state]}: ${j.error || ''}` : BOT_LABELS[j.state];
  col.appendChild(h(`<div class="${botColor(j.state)}" style="font-weight:500">${esc(now)}</div>`));
  col.appendChild(h(`<div class="small muted" style="margin-top:2px">${j.toolCalls} steps · ${Math.floor(j.workMs / 60000)} min${j.provider ? ' · ' + esc(j.provider) : ''}</div>`));
  if (j.state === BOT.AWAITING_APPROVAL && j.approval) BX.approvalCard(col, j, kit());
  const bb = window.atlasHost.botBrowser;
  if (j.state === BOT.AWAITING_ANSWER && j.handover) {
    const c = h(`<div class="card" style="margin-top:14px"><div class="small warn" style="font-weight:600">It needs you in its browser:</div><div style="font:400 19px var(--serif);margin-top:4px">${esc(j.question || '')}</div>
      <div class="row" style="margin-top:12px"><button class="btn grow" id="bshow">Open the Browser</button><button class="btn primary grow" id="bback">Hand Back</button></div></div>`);
    c.querySelector('#bshow').onclick = () => bb && bb.show(j.id, j.goal);
    c.querySelector('#bback').onclick = () => bots.handBack(j.id);
    col.appendChild(c);
  } else if (j.state === BOT.AWAITING_ANSWER) {
    const c = h(`<div class="card" style="margin-top:14px"><div class="small warn" style="font-weight:600">It asks:</div><div style="font:400 19px var(--serif);margin-top:4px">${esc(j.question || '')}</div>
      <textarea class="field" id="botask" rows="2" placeholder="Your answer" style="margin-top:12px"></textarea><div class="row" style="margin-top:10px"><div class="grow"></div><button class="btn primary" id="bans">Send</button></div></div>`);
    const ta = c.querySelector('#botask'); ta.value = botDraft; ta.oninput = () => (botDraft = ta.value);
    const send = () => { const t = ta.value.trim(); if (!t) return; botDraft = ''; bots.answer(j.id, t); };
    c.querySelector('#bans').onclick = send; ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } };
    col.appendChild(c);
  }
  if (j.state === BOT.NEEDS_REVIEW) {
    const c = h(`<div class="card" style="margin-top:14px"><div>Atlas was closed while the bot was doing: ${esc(j.pending || 'a step')}. It may or may not have happened. Check, then carry on or stop.</div>
      <div class="row" style="margin-top:12px"><button class="btn grow" id="bstop2">Stop</button><button class="btn primary grow" id="bgo">Carry On</button></div></div>`);
    c.querySelector('#bstop2').onclick = () => bots.stop(j.id); c.querySelector('#bgo').onclick = () => bots.carryOn(j.id);
    col.appendChild(c);
  }
  const keys = h('<div class="row" style="margin-top:14px"></div>');
  const key = (label, f, primary) => keys.appendChild(Object.assign(h(`<button class="btn ${primary ? 'primary ' : ''}grow">${label}</button>`), { onclick: f }));
  if (j.state === BOT.PAUSED) { key('Stop', () => bots.stop(j.id)); key('Resume', () => bots.resume(j.id), true); }
  else if (!BOT_FINAL.has(j.state) && j.state !== BOT.NEEDS_REVIEW) { key('Pause', () => bots.pause(j.id)); key('Stop', () => bots.stop(j.id)); }
  if (keys.children.length) col.appendChild(keys);
  // its browser: watch it work, take over (the bot pauses), hand it back
  if (bb && j.browser && !BOT_FINAL.has(j.state) && !(j.state === BOT.AWAITING_ANSWER && j.handover)) {
    const wk = h('<div class="row" style="margin-top:10px"></div>');
    const k2 = (label, f, primary) => wk.appendChild(Object.assign(h(`<button class="btn ${primary ? 'primary ' : ''}grow">${label}</button>`), { onclick: f }));
    k2('Watch', () => bb.show(j.id, j.goal));
    if (j.state === BOT.PAUSED) k2('Hand Back', () => bots.handBack(j.id), true);
    else k2('Take Over', () => { bots.pause(j.id); bb.show(j.id, j.goal); toast('The bot is paused: the browser is yours. Tap Hand Back when you’re done.'); });
    col.appendChild(wk);
    col.appendChild(h('<div class="small muted" style="margin-top:6px">Its browser keeps its own sign-ins. Files it downloads go to Documents › Atlas Bot.</div>'));
  }
  BX.steerCard(col, j, kit());
  BX.helpersList(col, j, kit(), (id) => { botView = id; render(); });
  CREW.missionExtras(col, j, crewKit());
  // >>> library: the files this job made
  if (Array.isArray(j.made) && j.made.length) {
    const c = h(`<div class="card" style="margin-top:14px"><div class="row"><div class="grow"><div class="small muted" style="font-weight:600">FILES IT MADE</div><div style="margin-top:4px">${esc(j.made.slice(0, 8).join(', '))}${j.made.length > 8 ? ` and ${j.made.length - 8} more` : ''}</div></div><button class="btn primary" id="blib">Open the Library</button></div></div>`);
    c.querySelector('#blib').onclick = () => { botView = 'library'; refreshLibrary(); render(); };
    col.appendChild(c);
  }
  // <<< library
  if (j.report) {
    col.appendChild(h('<div class="eyebrow">REPORT</div>'));
    const r = h('<div class="reply"></div>'); r.innerHTML = renderMarkdown(j.report); col.appendChild(r);
  }
  if (j.plan.length) {
    col.appendChild(h('<div class="eyebrow">PLAN</div>'));
    const l = h('<div class="botplan"></div>');
    for (const p of j.plan) l.appendChild(h(`<div class="${p.done ? 'done' : ''}"><span>${p.done ? '✓' : '○'}</span>${esc(p.text)}</div>`));
    col.appendChild(l);
  }
  if (j.steps.length) {
    col.appendChild(h('<div class="eyebrow">WHAT IT DID</div>'));
    const shown = j.steps.slice(-15);
    const l = h('<div class="botsteps"></div>');
    if (j.steps.length > shown.length) l.appendChild(h(`<div class="muted small">and ${j.steps.length - shown.length} earlier steps</div>`));
    for (const x of shown) l.appendChild(h(`<div>· ${esc(x)}</div>`));
    col.appendChild(l);
  }
  BX.saveAsSkillButton(col, j, kit());
  if (BOT_FINAL.has(j.state)) col.appendChild(Object.assign(h('<button class="btn" style="margin-top:22px">Remove From List</button>'), { onclick: () => { bots.remove(j.id); botView = null; render(); } }));
}

// ---- the ask bar ----
function buildComposer() {
  const c = $('#composer');
  c.innerHTML = `<div class="column"><div id="tray"></div><div class="askbar">
    <div class="round" id="attach" title="Add pictures, videos or any file">${ICON.plus}</div>
    <div class="round" id="snap" title="Take a photo with the webcam">${ICON.camera}</div>
    <textarea id="ask" rows="1" placeholder="Ask anything…"></textarea>
    <div class="round go" id="go" title="Speak">${ICON.mic}</div></div>
    <div class="hint">Enter to send · Shift+Enter for a new line · Esc to stop · Ctrl+Shift+Space to talk from anywhere</div></div>`;
  const ta = $('#ask');
  ta.addEventListener('input', () => { autosize(); updateSend(); });
  ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } });
  ta.addEventListener('paste', (e) => { const files = [...(e.clipboardData?.files || [])]; if (files.length) { e.preventDefault(); files.forEach(addFileObject); } });
  $('#attach').onclick = async () => { for (const p of await host.pickFiles()) await addPath(p); };
  $('#snap').onclick = () => webcamSnap();
  $('#go').onclick = () => { const k = $('#go').dataset.k; if (k === 'stop') brain.stop(); else if (k === 'send') submit(); else startListening(); };
  // drag and drop anywhere on the window
  const drop = $('#drop'); let depth = 0;
  window.addEventListener('dragenter', (e) => { if (e.dataTransfer?.types?.includes('Files')) { depth++; drop.classList.add('on'); } });
  window.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; drop.classList.remove('on'); } });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => { e.preventDefault(); depth = 0; drop.classList.remove('on'); [...(e.dataTransfer?.files || [])].forEach(addFileObject); if (tab !== 'atlas') { tab = 'atlas'; render(); } });
  updateSend();
}
function autosize() { const ta = $('#ask'); ta.style.height = 'auto'; ta.style.height = Math.min(200, ta.scrollHeight) + 'px'; }
function updateSend() {
  const go = $('#go'); if (!go) return;
  const k = brain.convo.busy ? 'stop' : ($('#ask').value.trim() || attachments.length) ? 'send' : 'mic';
  if (go.dataset.k === k) return;
  go.dataset.k = k; go.innerHTML = k === 'stop' ? ICON.stop : k === 'send' ? ICON.up : ICON.mic; go.title = k === 'stop' ? 'Stop' : k === 'send' ? 'Send' : 'Speak';
}
async function addFileObject(file) {
  const p = host.pathOf(file); if (p) return addPath(p);
  // a pasted screenshot (or a picture dragged from a browser) has no file on disk: it's read here instead
  if (file && /^image\//.test(file.type)) {
    if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);
    try { const a = await pictureFrom(file); attachments.push(a); showTray(); updateSend(); } catch { toast("Couldn't add that picture."); }
    return;
  }
  toast("Couldn't add that.");
}
/** A picture as an attachment: at most 1600 px on its longest side, as JPEG (the same as files picked from disk). */
function pictureFrom(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file); const img = new Image();
    img.onload = () => {
      const k = Math.min(1, 1600 / Math.max(img.naturalWidth, img.naturalHeight));
      const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(img.naturalWidth * k)); c.height = Math.max(1, Math.round(img.naturalHeight * k));
      const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); g.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      const data = c.toDataURL('image/jpeg', 0.85);
      resolve({ kind: 'image', mime: 'image/jpeg', name: file.name || 'Pasted picture.jpg', base64: data.split(',')[1], thumb: data });
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('bad image')); };
    img.src = url;
  });
}
/** How long a video is, in seconds (NaN if it can't tell). */
function videoSeconds(a) {
  return new Promise((resolve) => {
    const v = document.createElement('video'); let done = false;
    const end = (x) => { if (done) return; done = true; v.removeAttribute('src'); try { v.load(); } catch {} resolve(x); };
    v.preload = 'metadata'; v.onloadedmetadata = () => end(v.duration); v.onerror = () => end(NaN); setTimeout(() => end(NaN), 6000);
    v.src = `data:${a.mime};base64,${a.base64}`;
  });
}
// >>> attachments: any file, any size; copied (streamed) into the Library with progress and Stop
const MAX_ITEMS = 10;
let adding = null;   // { id, name, line }
let addQueue = Promise.resolve();
function addPath(p) { addQueue = addQueue.then(() => addOne(p)); return addQueue; }
async function addOne(p) {
  if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);
  const id = 'add-' + Math.random().toString(36).slice(2), name = String(p).split(/[\\/]/).pop();
  adding = { id, name, line: `Adding ${name}…` }; showTray();
  const off = progressTo(id, (d, t) => { if (!adding || adding.id !== id) return; adding.line = `Adding ${name} · ${t ? Math.floor(d * 100 / t) : 0}% of ${AR.sizeText(t)} · click to stop`; const el = $('#tray .adding'); if (el) el.textContent = adding.line; });
  try {
    const a = await host.attach.take(p, id);
    if (a.kind === 'image' && a.base64) a.thumb = 'data:image/jpeg;base64,' + a.base64;
    const pr = brain.active();
    a.note = pr ? AR.note(a.kind, AR.route(a.kind, a.size || 0, pr.style, a.chars || 0), pr.name) : '';
    attachments.push(a); updateSend();
  } catch (e) { const m = String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''); if (m !== 'Stopped.') toast(m, 4000); }
  finally { off(); if (adding && adding.id === id) adding = null; showTray(); }
}
/** Snap on the PC: the webcam, a live preview, one click for a photo. */
async function webcamSnap() {
  if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1600 } }, audio: false }); }
  catch { return toast("Couldn't open a camera on this PC.", 3500); }
  const o = h(`<div class="overlay"><div class="card snapbox"><video autoplay playsinline muted></video>
    <div class="row" style="justify-content:center;margin-top:12px"><button class="btn" data-k="cancel">Cancel</button><button class="btn primary" data-k="take">Take Photo</button></div></div></div>`);
  document.body.appendChild(o);
  const v = o.querySelector('video'); v.srcObject = stream;
  const close = () => { stream.getTracks().forEach((t) => t.stop()); o.remove(); };
  o.querySelector('[data-k=cancel]').onclick = close;
  o.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); }); o.tabIndex = -1; o.focus();
  o.querySelector('[data-k=take]').onclick = () => {
    const k = Math.min(1, 1600 / Math.max(v.videoWidth || 1, v.videoHeight || 1));
    const c = document.createElement('canvas'); c.width = Math.round(v.videoWidth * k) || 1; c.height = Math.round(v.videoHeight * k) || 1;
    c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
    const data = c.toDataURL('image/jpeg', 0.85);
    const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ').replace(':', '.');
    attachments.push({ kind: 'image', mime: 'image/jpeg', name: `Photo ${stamp}.jpg`, base64: data.split(',')[1], thumb: data, size: Math.floor(data.length * 0.75) });
    close(); showTray(); updateSend();
  };
}
// <<< attachments
function showTray() {
  const t = $('#tray'); if (!t) return; t.innerHTML = '';
  const row = h('<div class="atts"></div>'); t.appendChild(row);
  attachments.forEach((a, i) => {
    const label = a.kind === 'pdf' ? 'PDF' : a.kind === 'video' ? 'VIDEO' : (AR.ext(a.name) || 'file').toUpperCase().slice(0, 4);
    const cell = h(`<div class="att">${a.thumb ? `<img class="thumb" src="${a.thumb}" alt="">` : `<div class="card filetile"><b>${esc(label)}</b>${esc(a.name)}${a.size ? `<span class="small muted">${esc(AR.sizeText(a.size))}</span>` : ''}</div>`}<button class="x" title="Remove">✕</button></div>`);
    cell.querySelector('.x').onclick = () => { attachments.splice(i, 1); showTray(); updateSend(); };
    row.appendChild(cell);
  });
  // honest about what Atlas will read of a big or unusual file
  [...new Set(attachments.filter((a) => a.note).map((a) => `${a.name}: ${a.note}`))].slice(0, 3).forEach((n) => t.appendChild(h(`<div class="small muted attnote">${esc(n)}</div>`)));
  if (adding) { const el = h(`<div class="small adding" title="Stop adding" role="button">${esc(adding.line)}</div>`); el.onclick = () => { host.attach.cancel(adding.id); }; t.appendChild(el); }
}
async function submit(spoken = false, textOverride) {
  const ta = $('#ask');
  const text = textOverride ?? ta.value.trim();
  if (!text && !attachments.length) return;
  const att = attachments; attachments = []; showTray(); if (!textOverride) { ta.value = ''; autosize(); }
  if (tab !== 'atlas') { tab = 'atlas'; render(); }
  host.orbState({ energy: 1 });
  const reply = await brain.send(text, { spoken, attachments: att });
  host.orbState({ energy: 0 });
  if (reply && reply.source !== 'error' && reply.source !== 'Stopped' && (spoken || pref('speakReplies', false))) voice.speak(reply.text, () => { if (spoken && pref('keepTalking', true) && document.hasFocus()) startListening(true); });
}

// ---- listening ----
async function startListening(followUp = false) {
  if (listenCtl) { listenCtl.stop(); return; }
  voice.hush(); host.show();
  if (tab !== 'atlas') { tab = 'atlas'; render(); }
  const o = h(`<div class="overlay"><div class="listening"><div id="lorb"></div><div class="heard">${followUp ? 'Go on…' : 'Listening…'}</div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn" data-k="cancel">Cancel</button><button class="btn primary" data-k="done">Done</button></div></div></div>`);
  document.body.appendChild(o);
  const orb = new Orb(o.querySelector('#lorb'), { size: 220 }); orb.energy = 1;
  host.orbState({ energy: 1, listening: true });
  let cancelled = false;
  listenCtl = voice.listen({ onLevel: (l) => { orb.level = l; host.orbState({ energy: 1, level: l, listening: true }); }, onState: () => { const el = o.querySelector('.heard'); if (el) el.textContent = 'One moment…'; }, onPause: (p) => { const el = o.querySelector('.heard'); if (el && el.textContent !== 'One moment…') el.textContent = p ? 'Still listening…' : 'Listening…'; } });
  o.querySelector('[data-k=done]').onclick = () => listenCtl && listenCtl.stop();
  o.querySelector('[data-k=cancel]').onclick = () => { cancelled = true; listenCtl && listenCtl.stop(); };
  try {
    o.querySelector('.heard').textContent = 'Listening…';
    const p = listenCtl.done;
    const words = await p.then((w) => w, (e) => { throw e; });
    o.querySelector('.heard').textContent = words ? words : '';
    // "that's all", "never mind", "goodbye" after a reply: the conversation is over, nothing is sent
    if (!cancelled && words && followUp && endsTalk(words)) { o.remove(); listenCtl = null; host.orbState({ energy: 0 }); return; }
    if (!cancelled && words) { o.remove(); listenCtl = null; host.orbState({ energy: 0 }); await submit(true, words); return; }
    if (!cancelled && !followUp) { o.querySelector('.heard').textContent = "I didn't catch that."; await new Promise((r) => setTimeout(r, 1200)); }
  } catch (e) { toast(e.message || String(e), 4000); }
  o.remove(); listenCtl = null; host.orbState({ energy: 0 });
}

// ---- the Log ----
function entries() {
  return [...db.state.tasks.map((t) => ({ at: t.createdAt, task: t })), ...db.state.memories.map((m) => ({ at: m.createdAt, memory: m }))].sort((a, b) => b.at - a.at);
}
function renderLog(page) {
  const col = h('<div class="column"></div>'); page.appendChild(col);
  const all = entries();
  if (logFull) {
    col.appendChild(h(`<div class="row"><h1 class="title grow">Full History</h1><button class="btn" id="back">Back to the Log</button></div>`));
    col.querySelector('#back').onclick = () => { logFull = false; render(); };
    col.appendChild(h(`<p class="sub">Everything Atlas has logged, newest first (${all.length}).</p>`));
  } else {
    col.appendChild(h(`<div class="row"><h1 class="title grow">Log</h1><button class="btn" id="newrem">${ICON.plus.replace('<svg', '<svg width="16" height="16"')} New Reminder</button></div>`));
    col.querySelector('#newrem').onclick = () => editTask(null);
    col.appendChild(h('<p class="sub">What Atlas keeps for you, on this PC and your phone. Important reminders ring an alarm; lighter ones send a notification.</p>'));
    // Remember Something first
    col.appendChild(h('<div class="eyebrow">Remember Something</div>'));
    const rc = h('<div class="card"><textarea class="field" id="rem" rows="2" placeholder="Something to remember"></textarea><div class="row" style="margin-top:10px;justify-content:flex-end"><button class="btn primary" id="saverem">Save to Memory</button></div></div>');
    col.appendChild(rc);
    rc.querySelector('#saverem').onclick = () => { const t = plainMemory(rc.querySelector('#rem').value); if (!t) return; store.remember(t); toast('Saved'); render(true); };
    col.appendChild(h('<div class="eyebrow">Latest</div>'));
  }
  if (!all.length) { col.appendChild(h('<div class="card"><div style="font:400 22px var(--serif)">Nothing logged yet</div><p class="muted">Tell Atlas “remind me to pick up my sister Thursday at 4” or “remember my locker code is on the fridge”, and it lands here.</p></div>')); return; }
  const shown = logFull ? all : all.slice(0, 4);
  const list = h('<div class="card list"></div>'); col.appendChild(list);
  for (const e of shown) list.appendChild(entryRow(e));
  if (!logFull) { const b = h(`<button class="btn block" style="margin-top:12px">Full History${all.length > 4 ? ` (${all.length})` : ''}</button>`); b.onclick = () => { logFull = true; render(); }; col.appendChild(b); }
}
function entryRow(e) {
  const t = e.task, m = e.memory;
  let label = 'Note', cls = 'muted';
  if (m) { label = 'Memory'; cls = 'accent'; }
  else if (t.status === 'done') { label = 'Done'; cls = 'ok'; } else if (t.status === 'skipped') label = 'Not doing'; else if (t.status === 'cancelled') label = 'Cancelled';
  else if (t.status === 'planned' && t.startAt != null && t.remindedAt == null) { label = t.priority === 'high' ? 'Alarm' : 'Upcoming'; cls = 'accent'; }
  else if (t.status === 'planned' && t.startAt != null) label = 'Rang';
  const title = m ? plainMemory(m.text) : plainMemory(t.title);
  const meta = m ? `Remembered · ${logged(m.createdAt)}` : [t.startAt != null ? '⏰ ' + whenLabel(t.startAt) : null, t.recurrence !== 'none' ? t.recurrence[0].toUpperCase() + t.recurrence.slice(1) : null, t.notes ? plainMemory(t.notes) : null, 'Logged ' + logged(t.createdAt)].filter(Boolean).join(' · ');
  const row = h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t ${t && ['done', 'skipped', 'cancelled'].includes(t.status) ? 'muted' : ''}">${esc(title)}</div><div class="m">${esc(meta)}</div></div><span class="tag ${cls}">${label}</span></div>`);
  row.onclick = () => (t ? taskActions(t) : forgetSheet(m));
  return row;
}
async function forgetSheet(m) { if (await sheet({ title: 'Forget This?', body: m.text, primary: 'Forget', danger: true, secondary: 'Keep' })) { store.forget(m.id); render(true); } }
async function taskActions(t) {
  const o = h(`<div class="overlay"><div class="card sheet"><h2>${esc(plainMemory(t.title))}</h2><div style="display:grid;gap:8px;margin-top:12px">
    <button class="btn" data-a="edit">Edit</button>${t.status === 'planned' ? '<button class="btn" data-a="now">Doing It Now</button><button class="btn" data-a="cancel">Cancel It</button>' : ''}
    <button class="btn danger" data-a="delete">Delete</button><button class="btn" data-a="close">Close</button></div></div></div>`);
  document.body.appendChild(o);
  o.onclick = async (e) => {
    const a = e.target.closest('[data-a]')?.dataset.a; if (!a && e.target !== o) return; o.remove();
    if (a === 'edit') editTask(t);
    if (a === 'now') startFocus(plainMemory(t.title), null, t.id);
    if (a === 'cancel') { store.cancelTask(t.id); render(true); }
    if (a === 'delete' && await sheet({ title: 'Delete This?', body: t.title, primary: 'Delete', danger: true, secondary: 'Keep' })) { store.deleteTask(t.id); render(true); }
  };
}
function toLocalInput(ms) { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); }
async function editTask(t) {
  const start = t?.startAt ?? Date.now() + 3600000;
  await sheet({ title: t ? 'Edit Reminder' : 'New Reminder', primary: t ? 'Save' : 'Add',
    html: `<div style="display:grid;gap:12px">
      <input class="field" id="et" placeholder="What" value="${esc(t ? t.title : '')}">
      <div class="row"><input class="field grow" id="ew" type="datetime-local" value="${t && t.startAt == null ? '' : toLocalInput(start)}"><label class="row small muted"><input type="checkbox" id="en" ${t && t.startAt == null ? 'checked' : ''}> No alarm</label></div>
      <div class="chips" id="er">${['none:Never', 'daily:Daily', 'weekdays:Weekdays', 'weekly:Weekly'].map((x) => { const [k, l] = x.split(':'); return `<span class="chip ${(t?.recurrence || 'none') === k ? 'on' : ''}" data-r="${k}">${l}</span>`; }).join('')}</div>
      <div class="chips" id="ep"><span class="chip ${t?.priority === 'high' ? 'on' : ''}" data-p="high">Alarm</span><span class="chip ${t?.priority !== 'high' ? 'on' : ''}" data-p="normal">Notification</span></div>
      <textarea class="field" id="eo" placeholder="Notes (optional)">${esc(t?.notes || '')}</textarea></div>`,
    onOpen: (o) => {
      o.querySelectorAll('#er .chip').forEach((c) => (c.onclick = () => { o.querySelectorAll('#er .chip').forEach((x) => x.classList.remove('on')); c.classList.add('on'); }));
      o.querySelectorAll('#ep .chip').forEach((c) => (c.onclick = () => { o.querySelectorAll('#ep .chip').forEach((x) => x.classList.remove('on')); c.classList.add('on'); }));
    },
    onPrimary: (o) => {
      const title = o.querySelector('#et').value.trim(); if (!title) { toast('Give it a title.'); return false; }
      const noAlarm = o.querySelector('#en').checked; const v = o.querySelector('#ew').value;
      const startAt = noAlarm || !v ? null : new Date(v).getTime();
      const recurrence = o.querySelector('#er .on')?.dataset.r || 'none'; const priority = o.querySelector('#ep .on')?.dataset.p || 'normal';
      if (recurrence !== 'none' && startAt == null) { toast('A repeating reminder needs a day and time.'); return false; }
      const notes = o.querySelector('#eo').value.trim();
      try { if (t) store.updateTask(t.id, { title, startAt, notes, priority, recurrence }); else store.createTask(title, { startAt, notes, priority, recurrence }); }
      catch (e) { toast(e.message); return false; }
      render(true); return true;
    } });
}

// ---- Me ----
/** Bots working on the phone (read-only here: each job runs where it was started). Click one for its report. */
function botsElsewhere(col) {
  const list = botmirror.others(); if (!list.length) return;
  col.appendChild(h(`<div class="eyebrow" style="margin-top:22px">${list.some((v) => v.platform === 'android') ? 'On Your Phone' : 'On Your Other Device'}</div>`));
  for (const v of list.slice(0, 20)) {
    const line = [v.label, v.state === 'running' && v.current ? v.current : null, v.total ? `plan ${v.done}/${v.total}` : null].filter(Boolean).join(' · ');
    const c = h(`<div class="card botcard"${v.report ? ' role="button" tabindex="0"' : ''}><div class="goal">${esc(v.goal)}</div><div class="small muted" style="margin-top:6px">${esc(line)}</div></div>`);
    if (v.report) c.onclick = () => sheet({ title: v.goal.slice(0, 80), html: `<p class="small muted" style="margin:0 0 8px">Its report, from your phone.</p><div class="md" style="max-height:60vh;overflow:auto">${renderMarkdown(v.report)}</div>`, primary: 'Close', secondary: null });
    col.appendChild(c);
  }
}

function settingRow(label, on, onToggle, note) {
  const r = h(`<div class="setting"><div class="grow"><div>${esc(label)}</div>${note ? `<div class="small muted">${esc(note)}</div>` : ''}</div><div class="switch ${on ? 'on' : ''}"></div></div>`);
  r.querySelector('.switch').onclick = (e) => { e.currentTarget.classList.toggle('on'); onToggle(e.currentTarget.classList.contains('on')); };
  return r;
}
/** What this PC has right now, and what Atlas will (and won't) run on it because of that. Measured each time Me opens. */
function deviceCard(col) {
  col.appendChild(h('<div class="eyebrow">This Device</div>'));
  const c = h('<div class="card"><p class="muted">Measuring this PC…</p></div>'); col.appendChild(c);
  const gb = (mb) => (mb == null || mb < 0 ? '?' : (mb / 1024).toFixed(1) + ' GB');
  brain.device.get(true).then(async (p) => {
    if (!c.isConnected) return;
    if (!p) { c.innerHTML = '<p class="muted">Couldn\'t read this PC\'s hardware.</p>'; return; }
    const cards = (p.gpus || []).map((g) => `${esc(g.name)}: ${g.vramTotalMb > 0 ? gb(g.vramTotalMb) + ' of its own memory' : 'its memory size isn\'t reported'}${g.vramFreeMb >= 0 ? `, ${gb(g.vramFreeMb)} free` : ''}`);
    const cores = p.physicalCores > 0 ? `${p.physicalCores} cores, ${p.logicalCores} threads` : `${p.logicalCores} threads`;
    const lines = [`Memory: ${gb(p.freeRamMb)} free of ${gb(p.totalRamMb)}`, `Processor: ${esc(p.cpu || 'unknown')} (${cores})`,
      ...(cards.length ? cards.map((x) => 'Graphics: ' + x) : ['Graphics: no separate graphics memory found']),
      ...(p.diskFreeMb >= 0 ? [`Disk: ${gb(p.diskFreeMb)} free`] : [])];
    c.innerHTML = `<div style="font:400 22px var(--serif)">This PC</div><p class="muted small" style="margin-top:6px;line-height:1.7">${lines.join('<br>')}</p>`;
    const hold = fitRules.holdingBack(p); if (hold) c.appendChild(h(`<p class="warn" style="margin-top:10px">${esc(hold)}</p>`));
    const act = brain.byId('local'); const v = act && brain.modelOf(act) ? await brain.localVerdict(act, false) : null;
    if (v) c.appendChild(h(`<p style="margin-top:12px"><b>Local model ${esc(brain.modelOf(act))}</b><br><span class="${v.ok ? 'muted' : 'warn'} small">${esc(v.reason)}</span></p>`));
    const any = (p.runtimes || []).flatMap((r) => r.models);
    if (any.length) {
      const best = fitRules.rank(p, any).find((m) => m.fit.place === 'gpu' || m.fit.place === 'split');
      if (best) c.appendChild(h(`<p class="muted small" style="margin-top:8px">Best local model for this PC right now: ${esc(best.id)} (${esc(best.fit.reason)})</p>`));
    } else c.appendChild(h('<p class="muted small" style="margin-top:8px">No local model server found (Ollama or LM Studio). Atlas uses your online services.</p>'));
    c.appendChild(h('<p class="muted small" style="margin-top:10px">Before every local answer Atlas checks the model fits what\'s free, so it never makes the PC freeze; bots run one at a time when memory is short. These checks stay on this PC.</p>'));
  }).catch(() => { c.innerHTML = '<p class="muted">Couldn\'t read this PC\'s hardware.</p>'; });
}

// >>> vault
async function vaultEdit(old) {
  const v = (x) => esc(x || '');
  const html = `<div class="col" style="gap:8px;margin-top:6px">
    <input class="field" id="vn" placeholder="Name, e.g. My Gmail" value="${v(old && old.name)}">
    <input class="field" id="vs" placeholder="Site, e.g. accounts.google.com" value="${v(old && old.site)}">
    <input class="field" id="vu" placeholder="Username or email" value="${v(old && old.username)}" autocomplete="off">
    <input class="field" id="vp" type="password" placeholder="${old ? 'Password (leave empty to keep it)' : 'Password'}" autocomplete="new-password">
    <input class="field" id="vo" placeholder="Notes (optional)" value="${v(old && old.notes)}">
    <label class="row small" style="gap:8px"><input type="checkbox" id="vt" ${old && old.trusted ? 'checked' : ''}> Allow without asking on this site</label>
    ${old ? '<div class="row"><button class="btn" id="vshow">Show Password</button><button class="btn danger" id="vdel">Delete</button></div>' : ''}</div>`;
  await sheet({ title: old ? 'Edit ' + old.name : 'Add a Sign-in', html, primary: 'Save', onOpen: (o) => {
    const show = o.querySelector('#vshow'); if (show) show.onclick = async () => { const pw = await host.vault.reveal(old.id); if (pw != null) { const f = o.querySelector('#vp'); f.type = 'text'; f.value = pw; } };
    const del = o.querySelector('#vdel'); if (del) del.onclick = async () => { await host.vault.remove(old.id); o.remove(); toast('Deleted ' + old.name); render(true); };
  }, onPrimary: async (o) => {
    const q = (id) => o.querySelector(id);
    const r = await host.vault.save({ id: old ? old.id : undefined, name: q('#vn').value, site: q('#vs').value, username: q('#vu').value, password: q('#vp').value, notes: q('#vo').value, trusted: q('#vt').checked });
    if (r && r.error) { toast(r.error); return false; }
    toast('Saved'); render(true); return true;
  } });
}
// <<< vault

function renderMe(page) {
  const col = h('<div class="column"></div>'); page.appendChild(col);
  col.appendChild(h('<h1 class="title">Me</h1>'));
  col.appendChild(h('<p class="sub">How Atlas thinks, sounds and connects on this PC.</p>'));

  // Brain
  col.appendChild(h('<div class="eyebrow">Brain</div>'));
  const act = brain.active(); const shown = brain.byId(brainShown) || act || brain.PROVIDERS[0]; brainShown = shown.id;
  const bc = h('<div class="card"></div>'); col.appendChild(bc);
  bc.appendChild(h(`<div style="font:400 24px var(--serif)">${esc(act ? `${act.name} · ${brain.modelOf(act)}` : 'Not set up')}</div>`));
  bc.appendChild(h(`<p class="${act ? 'ok' : 'muted'}" style="margin:4px 0 14px">${act ? `Atlas thinks with ${esc(act.name)}. Add as many keys as you like: when one runs out, Atlas switches to the next and carries on${brain.dontSwitch() ? '.' : ', then to another service.'}` : 'Add a key from any AI service, or use a model running on this PC.'}</p>`));
  // the services you use (with keys) stay in view; the rest are one click away under More Services
  const chips = h('<div class="chips"></div>');
  const mine = brain.PROVIDERS.filter((p) => p.id === shown.id || p.id === act?.id || (p.local ? !!brain.modelOf(p) : brain.keys(p.id).some((e) => !e.bad)));
  for (const p of mine) { const c = h(`<span class="chip ${p.id === shown.id ? 'on' : ''}">${esc(p.name)}</span>`); c.onclick = () => { brainShown = p.id; render(true); }; chips.appendChild(c); }
  const more = h('<span class="chip">More Services…</span>'); more.onclick = () => moreServices(new Set(mine.map((p) => p.id))); chips.appendChild(more);
  bc.appendChild(chips);
  if (shown.note) bc.appendChild(h(`<p class="small muted" style="margin:8px 0 0">${esc(shown.note)}</p>`));
  if (shown.local) {
    bc.appendChild(h(`<p class="muted small" style="margin-top:14px">Models running on this PC with Ollama or LM Studio. They work offline; when you're offline, Atlas uses one automatically if it's set here.</p>`));
    const found = h('<div class="list" style="margin-top:6px"><div class="item muted">Looking for models on this PC…</div></div>'); bc.appendChild(found);
    Promise.all([brain.detectLocal(), brain.device.get(true).catch(() => null)]).then(([ms, prof]) => {
      found.innerHTML = '';
      // what each model needs against what this PC has free now, best-first
      const sized = new Map(); for (const rt of (prof && prof.runtimes) || []) for (const x of rt.models) sized.set(rt.base + '|' + x.id, x);
      if (prof) ms = fitRules.rank(prof, ms.map((m) => ({ ...m, ...(sized.get(m.base + '|' + m.model) || { sizeMb: -1 }), id: m.model })));
      if (!ms.length) found.appendChild(h('<div class="item muted">None found. Start Ollama (ollama.com) or LM Studio\'s server, then come back here.</div>'));
      for (const m of ms) {
        const on = brain.modelOf(shown) === m.model && brain.baseOf(shown) === m.base;
        const where = m.fit ? { gpu: 'Runs on the graphics card', split: 'Graphics card + memory (slower)', cpu: 'Processor only (slow)', no: 'Too big for this PC right now', unknown: 'Size unknown' }[m.fit.place] : '';
        const r = h(`<div class="item" style="cursor:pointer" title="${esc(m.fit ? m.fit.reason : '')}"><div class="grow"><div class="t">${esc(m.model)}</div><div class="m">${esc(m.server)}${where ? ' · ' + esc(where) : ''}</div></div><span class="tag ${on ? 'ok' : m.fit && !m.fit.ok ? 'warn' : ''}">${on ? 'In use' : 'Use'}</span></div>`);
        r.onclick = () => { setPref('model_local', m.model); setPref('base_local', m.base); setPref('provider', 'local'); toast(m.fit && !m.fit.ok ? m.fit.reason : 'Using ' + m.model, m.fit && !m.fit.ok ? 6000 : 2500); render(true); };
        found.appendChild(r);
      }
    });
    if (brain.modelOf(shown)) { const t = h('<div class="row" style="margin-top:12px"><button class="btn">Test</button>' + (act?.id !== 'local' ? '<button class="btn">Use Local Model</button>' : '') + '</div>'); bc.appendChild(t);
      t.children[0].onclick = async () => { toast('Testing…'); const r = await brain.testKey(shown); toast(r.msg, 4000); };
      if (t.children[1]) t.children[1].onclick = () => { setPref('provider', 'local'); render(true); }; }
  } else {
    const kr = h(`<div class="row" style="margin-top:14px"><input class="field grow" id="newkey" type="password" placeholder="Paste a ${esc(shown.name)} key (${esc(shown.hint)})"><button class="btn primary" id="addkey">Add Key</button>${shown.keyUrl ? '<button class="btn" id="getkey">Get a Key</button>' : ''}</div>`);
    bc.appendChild(kr);
    kr.querySelector('#addkey').onclick = async () => {
      const k = kr.querySelector('#newkey').value.trim(); if (k.length < 16) return toast("That doesn't look like a whole key.");
      const id = brain.keyTarget(k, shown.id);
      brain.addKey(id, k); setPref('provider', id); brainShown = id; render(true);
      toast('Key added. Testing it…'); const r = await brain.testKey(brain.byId(id), k); toast(r.msg, 4000); render(true);
    };
    if (shown.keyUrl) kr.querySelector('#getkey').onclick = () => host.openExternal(shown.keyUrl);
    const pool = brain.keys(shown.id);
    if (pool.length) {
      bc.appendChild(h(`<div class="eyebrow" style="margin-left:0">${esc(shown.name)} Keys (${pool.length})</div>`));
      const l = h('<div class="list"></div>'); const now = Date.now(); const inUse = pool.find((e) => !e.bad && (e.restUntil || 0) <= now);
      for (const e of pool) {
        const masked = e.key.length > 10 ? e.key.slice(0, 4) + '••••' + e.key.slice(-4) : '••••';
        const st = e.bad ? ['Turned down' + (e.problem ? ': ' + e.problem.slice(0, 70) : ''), 'bad'] : (e.restUntil || 0) > now ? ['Ran out · back at ' + fmtTime(e.restUntil), 'muted'] : [inUse === e && act?.id === shown.id ? 'In use' : 'Ready', 'ok'];
        const r = h(`<div class="item keyrow"><div class="grow"><div class="mono">${esc(masked)}</div><div class="small ${st[1]}">${esc(st[0])}</div></div><button class="btn">Try Again</button><button class="btn danger">Remove</button></div>`);
        r.querySelectorAll('button')[0].onclick = async () => { brain.wakeKey(shown.id, e.key); const res = await brain.testKey(shown, e.key); toast(res.msg, 4000); render(true); };
        r.querySelectorAll('button')[1].onclick = async () => { if (await sheet({ title: 'Remove This Key?', body: masked, primary: 'Remove', danger: true })) { brain.removeKey(shown.id, e.key); render(true); } };
        l.appendChild(r);
      }
      bc.appendChild(l);
      if (act?.id !== shown.id) { const u = h(`<button class="btn" style="margin-top:10px">Use ${esc(shown.name)}</button>`); u.onclick = () => { setPref('provider', shown.id); render(true); }; bc.appendChild(u); }
    }
    if (shown.id === 'custom') { const a = h(`<div class="setting"><div class="grow">Address<div class="small muted">${esc(brain.baseOf(shown) || 'Not set')}</div></div><button class="btn">Change</button></div>`); a.querySelector('button').onclick = () => askText('Service Address', 'The OpenAI-compatible address, ending in /v1.', brain.baseOf(shown), (v) => setPref('base_custom', v.trim())); bc.appendChild(a); }
    if (shown.custom && shown.id !== 'custom') {
      const a = h(`<div class="setting"><div class="grow">Address<div class="small muted">${esc(brain.baseOf(shown) || 'Not set')}</div></div><button class="btn">Change</button><button class="btn danger">Remove</button></div>`);
      a.querySelectorAll('button')[0].onclick = () => customService(shown);
      a.querySelectorAll('button')[1].onclick = async () => { if (await sheet({ title: `Remove ${shown.name}?`, body: 'Its keys go too.', primary: 'Remove', danger: true })) { brain.removeCustom(shown.id); brainShown = null; render(true); } };
      bc.appendChild(a);
    }
  }
  if (!shown.local) { const mr = h(`<div class="setting"><div class="grow">Model<div class="small muted">${esc(brain.modelOf(shown) || 'Not set')}</div></div><button class="btn">Change</button></div>`);
    mr.querySelector('button').onclick = () => askText(`${shown.name} Model`, shown.model ? `Leave it as ${shown.model} unless you want another of ${shown.name}'s models.` : "The model's name, as your service writes it.", brain.modelOf(shown), (v) => setPref('model_' + shown.id, v.trim())); bc.appendChild(mr); }
  const stayOn = brain.dontSwitch();
  bc.appendChild(settingRow("Don't Switch Services", stayOn, (on) => { setPref('dont_switch', on); render(true); },
    stayOn ? `Atlas stays on ${act ? act.name : 'the service you chose'}, even when it runs out or you're offline (no local model either). Your other keys for it can still take over.`
      : 'Off: when a service keeps failing (Atlas tries it 3 times first) or all its keys run out, Atlas moves to another service, or a local model when offline, and carries on.'));
  bc.appendChild(h('<p class="small muted" style="margin-top:10px">Keys stay on this PC (they don\'t sync). When Atlas thinks online, what you say goes to that service.</p>'));

  // Voice
  deviceCard(col);

  col.appendChild(h('<div class="eyebrow">Voice</div>'));
  const vc = h('<div class="card"></div>'); col.appendChild(vc);
  vc.appendChild(settingRow('Speak Replies', pref('speakReplies', false), (on) => setPref('speakReplies', on), 'Spoken questions always get a spoken answer.'));
  vc.appendChild(settingRow('Natural Voice', pref('voiceNatural', true), (on) => { setPref('voiceNatural', on); render(true); }));
  const eng = voice.engine();
  vc.appendChild(h(`<p class="small muted">${!pref('voiceNatural', true) ? "Using Windows' own voice." : eng === 'elevenlabs' ? "Atlas's own voice, designed for it on ElevenLabs from a description (made once, then kept)." : eng === 'gemini' ? 'A human-sounding voice from Gemini.' : eng === 'openai' ? 'A human-sounding voice from OpenAI.' : 'Natural voices come from ElevenLabs, Gemini or OpenAI: add one of their keys. Until then, the best Windows voice.'}${voice.state.lastSpoke ? ' Last reply: ' + esc(voice.state.lastSpoke) + '.' : ''}${voice.state.lastProblem ? ' The natural voice failed last time (' + esc(voice.state.lastProblem) + ').' : ''}</p>`));
  const engs = voice.engines();
  if (engs.length > 1) {
    vc.appendChild(h('<div style="margin-top:16px">Voice Service</div>'));
    const cs = h('<div class="chips" style="margin-top:8px"></div>');
    for (const e of engs) { const c = h(`<span class="chip ${e === eng ? 'on' : ''}">${esc(voice.engineName(e))}</span>`); c.onclick = () => { voice.setEngine(e); render(true); }; cs.appendChild(c); }
    vc.appendChild(cs);
    vc.appendChild(h('<p class="small muted" style="margin-top:6px">If it fails, the next one says the reply, so a reply is never lost.</p>'));
  }
  if (eng) {
    const l = h('<div class="list"></div>'); const cur = voice.current(eng).id;
    for (const v of voice.voices(eng)) {
      const r = h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${esc(v.label)}</div><div class="m">${esc(v.about)}</div></div><span class="tag ${v.id === cur ? 'accent' : ''}">${v.id === cur ? 'Chosen · Hear It' : 'Hear It'}</span></div>`);
      r.onclick = () => { voice.preview(eng, v.id); render(true); };
      l.appendChild(r);
    }
    vc.appendChild(l);
  }
  // the "AI system" sound on every reply, "sir", and the ElevenLabs key for Atlas's designed voice
  vc.appendChild(settingRow('AI System Finish', voice.finishOn(), (on) => voice.setFinish(on), 'A light finish on every natural-voice reply: a little presence, a faint digital sheen and a small room. Words stay clear first. (Windows\' own voice plays as Windows makes it.)'));
  vc.appendChild(settingRow('Call Me “Sir”', voice.sir(), (on) => voice.setSir(on), 'Spoken replies now and then address you as “sir”, the way a butler would.'));
  const elPool = brain.keys('elevenlabs');
  const er = h(`<div class="row" style="margin-top:14px"><input class="field grow" type="password" placeholder="ElevenLabs key (optional)"><button class="btn primary">Add Key</button><button class="btn">${elPool.length ? 'Remove Keys' : 'Get a Key'}</button></div>`);
  vc.appendChild(er);
  er.children[1].onclick = () => { const k = er.children[0].value.trim(); if (k.length < 20) return toast("That doesn't look like a whole key."); brain.addKey('elevenlabs', k); voice.setEngine('elevenlabs'); toast('ElevenLabs key added. Atlas designs its voice the first time it speaks.', 4000); render(true); };
  er.children[2].onclick = () => { if (!elPool.length) return host.openExternal('https://elevenlabs.io/app/settings/api-keys'); for (const e of elPool) brain.removeKey('elevenlabs', e.key); render(true); };
  vc.appendChild(h(`<p class="small muted" style="margin-top:6px">${elPool.length ? `${elPool.length} ElevenLabs key${elPool.length === 1 ? '' : 's'} added.` : 'With an ElevenLabs key Atlas designs its own voice there (refined, calm, British) and speaks with it. Voice Design may need a paid ElevenLabs plan.'}</p>`));
  // how fast Atlas talks, whether a spoken chat keeps going, and the language it listens for
  const chipRow = (title, options, current, pick, note) => {
    vc.appendChild(h(`<div style="margin-top:16px">${esc(title)}</div>`));
    const cs = h('<div class="chips" style="margin-top:8px"></div>');
    for (const [k, label] of options) { const c = h(`<span class="chip ${k === current ? 'on' : ''}">${esc(label)}</span>`); c.onclick = () => { pick(k); render(true); }; cs.appendChild(c); }
    vc.appendChild(cs);
    if (note) vc.appendChild(h(`<p class="small muted" style="margin-top:6px">${esc(note)}</p>`));
  };
  chipRow('Speaking Speed', [['slow', 'Slow'], ['normal', 'Normal'], ['fast', 'Fast']], voice.speed(), (k) => voice.setSpeed(k));
  vc.appendChild(settingRow('Keep Listening', pref('keepTalking', true), (on) => setPref('keepTalking', on), "After Atlas answers a spoken question it listens again, so you can just keep talking. Say “that's all” or stay quiet to stop."));
  chipRow('Language You Speak', voice.LANGUAGES, voice.language() || 'auto', (k) => voice.setLanguage(k), 'Telling Atlas the language makes it hear names and accents better. “Any” lets it work it out (Atlas then answers in the language you speak).');
  vc.appendChild(settingRow('Give Me Time to Finish', voice.patient(), (on) => { voice.setPatient(on); render(true); }, `Atlas waits much longer through pauses before it takes it you've finished: for a stammer, a slow or careful way of speaking, or thinking mid-sentence. It also learns your pace by itself (now ${(voice.endSilenceMs() / 1000).toFixed(1)} s).`));
  { const wr = h(`<div style="margin-top:14px">Words Atlas Should Know</div>`); vc.appendChild(wr);
    const ta = h(`<textarea class="field" rows="3" style="width:100%;margin-top:8px" placeholder="One per line: names, places, words from your work"></textarea>`); ta.value = voice.taughtWords().join('\n');
    ta.onchange = () => voice.setTaughtWords(ta.value.split('\n')); vc.appendChild(ta);
    vc.appendChild(h('<p class="small muted" style="margin-top:6px">The speech model is told to expect them, so they\'re written down right. They stay on this PC.</p>')); }

  // >>> vault: sign-ins Atlas Bot may use in its browser (desktop/vault.js; passwords never reach this page unless shown)
  if (host.vault) {
    col.appendChild(h('<div class="eyebrow">Vault</div>'));
    const vc = h('<div class="card"><p class="muted">Loading…</p></div>'); col.appendChild(vc);
    host.vault.list().then((entries) => {
      vc.innerHTML = '';
      vc.appendChild(h(`<div style="font:400 22px var(--serif)">${entries.length ? entries.length + ' saved sign-in' + (entries.length === 1 ? '' : 's') : 'No saved sign-ins'}</div>`));
      vc.appendChild(h('<p class="muted">Sign-ins Atlas Bot can use in its browser. It never sees them: they go straight into the page, after your OK unless you allow a site. Kept on this PC only, encrypted by Windows, never synced.</p>'));
      entries.slice().sort((x, y) => x.name.localeCompare(y.name)).forEach((e) => {
        const r = h(`<div class="vault-row" style="padding:10px 0;cursor:pointer;border-top:1px solid var(--line)"><div style="font-weight:500">${esc(e.name)}</div><div class="small muted">${esc(e.site)}${e.trusted ? ' · allowed without asking' : ''}</div></div>`);
        r.onclick = () => vaultEdit(e); vc.appendChild(r);
      });
      const add = h('<button class="btn" style="margin-top:12px">Add a Sign-in</button>'); add.onclick = () => vaultEdit(null); vc.appendChild(add);
    }).catch(() => { vc.innerHTML = '<p class="muted">The vault couldn\'t be opened.</p>'; });
  }
  // <<< vault

  // >>> email (mailui.js)
  renderEmail(col, { h, esc, sheet, toast, render });
  // <<< email

  // >>> connections: MCP servers and web APIs (connui.js)
  renderConnections(col, { h, esc, sheet, toast, render, settingRow });
  // <<< connections
  trade.renderTrading(col, { h, esc, sheet, toast, render });   // >>> trading <<<

  // Devices
  BX.permissionsCard(col, kit());
  col.appendChild(h('<div class="eyebrow">Your Devices</div>'));
  const dc = h('<div class="card"></div>'); col.appendChild(dc);
  const code = sync.code();
  if (!code) {
    dc.appendChild(h('<div style="font:400 22px var(--serif)">Not linked</div>'));
    dc.appendChild(h('<p class="muted">Link Atlas on your phone and they share everything: reminders, memories, the Log and your conversation. It\'s encrypted with a private code before it leaves either device. On the phone: Atlas → Me → Your Devices.</p>'));
    const r = h('<div class="row"><input class="field grow mono" id="code" placeholder="ATLS-XXXX-XXXX-XXXX-XXXX-XXXX"><button class="btn primary" id="link">Link</button></div>'); dc.appendChild(r);
    r.querySelector('#link').onclick = async () => { if (await sync.link(r.querySelector('#code').value)) { toast('Linked. Syncing…'); render(true); } else toast("That code doesn't look right. Check it and try again."); };
    const n = h('<button class="btn" style="margin-top:10px">Start a New Link Here Instead</button>'); n.onclick = async () => { await sync.link(sync.newCode()); render(true); }; dc.appendChild(n);
  } else {
    dc.appendChild(h(`<div style="font:400 22px var(--serif)">Linked</div>`));
    // how syncing stands, in plain words: when it last worked, what's waiting, the phone, any problem and its fix
    const ss = sync.status, p = ss.problem;
    const whenSaid = (ms) => { const t = logged(ms); return t.startsWith('Today ') ? 'today ' + t.slice(6) : 'on ' + t; };
    const headline = p ? p.text : ss.running ? 'Syncing…' : ss.at ? `Synced ${whenSaid(ss.at)}${ss.pending ? ` · ${ss.pending} change${ss.pending === 1 ? '' : 's'} waiting to go up` : ''}` : (ss.last || 'Syncing…');
    const lines = [`<p style="margin:4px 0 2px;color:${p && p.kind !== 'offline' ? 'var(--warm)' : 'var(--muted)'}">${esc(headline)}</p>`];
    if (p && p.fix) lines.push(`<p class="small muted" style="margin:2px 0">${esc(p.fix)}</p>`);
    const cloudNow = Date.now() + (ss.skew || 0);
    if (!ss.others.length) lines.push('<p class="small muted" style="margin:6px 0 0">Your phone hasn\'t synced yet. Enter the code below in Atlas there.</p>');
    for (const o of ss.others) {
      const who = o.platform === 'android' ? 'Your phone' : o.platform === 'windows' ? 'Another PC' : 'Your other device';
      const ago = cloudNow - o.seenMs;
      const seen = o.seenMs <= 0 ? 'not seen yet' : ago < 11 * 60000 ? 'active now' : 'last seen ' + whenSaid(Date.now() - ago);
      lines.push(`<p class="small muted" style="margin:6px 0 0">${esc(who)} · ${esc(seen)}${o.app ? ' · Atlas ' + esc(o.app) : ''}</p>`);
      if (o.outdated) lines.push('<p class="small" style="margin:2px 0 0;color:var(--warm)">Update Atlas there so everything syncs both ways.</p>');
    }
    if (ss.tooBig) lines.push(`<p class="small muted" style="margin:4px 0 0">${ss.tooBig} item${ss.tooBig === 1 ? ' is' : 's are'} too big to sync and stay${ss.tooBig === 1 ? 's' : ''} on this PC only.</p>`);
    if (ss.waiting) lines.push(`<p class="small" style="margin:4px 0 0;color:var(--warm)">${ss.waiting} item${ss.waiting === 1 ? '' : 's'} from your phone need${ss.waiting === 1 ? 's' : ''} a newer Atlas here. Update Atlas to bring ${ss.waiting === 1 ? 'it' : 'them'} in.</p>`);
    dc.appendChild(h(`<div style="margin-bottom:10px">${lines.join('')}</div>`));
    dc.appendChild(h(`<div class="small muted">Your link code (keep it private):</div>`));
    dc.appendChild(h(`<div class="field mono" style="margin:6px 0 10px;text-align:center;font-size:17px;user-select:all">${esc(code)}</div>`));
    dc.appendChild(settingRow('Share My AI Keys', keyshare.on(), (on) => keyshare.set(on),
      'Your AI keys go to your phone too, inside the encrypted sync (scrambled with your link code; the cloud can’t read them). Turn it on on both devices.'));
    const r = h('<div class="row" style="margin-top:10px"><button class="btn">Copy Code</button><button class="btn">Sync Now</button><button class="btn danger">Unlink This PC</button></div>'); dc.appendChild(r);
    r.children[0].onclick = () => { navigator.clipboard.writeText(code); toast('Copied'); };
    r.children[1].onclick = async () => { toast('Syncing…'); toast(await sync.runOnce()); render(true); };
    r.children[2].onclick = async () => { if (await sheet({ title: 'Unlink This PC?', body: 'It stops syncing. Everything stays on this PC; the phone keeps its copy.', primary: 'Unlink', danger: true })) { await sync.unlink(); render(true); } };
  }

  renderDevices(col, kit());   // >>> device link: online devices, Control Phone, who may control this PC

  // This PC
  col.appendChild(h('<div class="eyebrow">On This PC</div>'));
  const pc = h('<div class="card"></div>'); col.appendChild(pc);
  pc.appendChild(settingRow('Atlas On Screen', pref('onScreen', true), (on) => { setPref('onScreen', on); host.showOrb(on); }, 'A small orb in the corner, over everything. Click it to talk.'));
  pc.appendChild(settingRow('Reminders Ring Here Too', pref('ringHere', true), (on) => setPref('ringHere', on), 'Alarms and notifications on this PC as well as the phone.'));
  pc.appendChild(settingRow('Alarm Sound', db.state.settings.sound !== false, (on) => { db.state.settings.sound = on; store.saveState(); }));
  const auto = settingRow('Start With Windows', false, (on) => host.autostart(on), 'Quietly, in the tray: reminders and sync keep going.'); pc.appendChild(auto);
  host.autostart().then((on) => auto.querySelector('.switch').classList.toggle('on', !!on));
  pc.appendChild(settingRow('Light Look', document.documentElement.dataset.theme === 'light', (on) => { setPref('theme', on ? 'light' : 'dark'); applyTheme(); render(true); }));
  pc.appendChild(themePicker());   // >>> themes
  const nm = h(`<div class="setting"><div class="grow">Your Name<div class="small muted">${esc(db.state.settings.name || 'Not set')}</div></div><button class="btn">Change</button></div>`);
  nm.querySelector('button').onclick = () => askText('Your Name', 'What Atlas calls you.', db.state.settings.name || '', (v) => { db.state.settings.name = v.trim().slice(0, 40); store.saveState(); });
  pc.appendChild(nm);
  host.info().then((i) => col.appendChild(h(`<p class="small muted" style="text-align:center;margin-top:24px">Atlas ${esc(i.version)} for Windows</p>`)));
}
// >>> themes
/** Me → Look: the four colour themes side by side, each a small picture of itself in the current light/dark look. */
function themePicker() {
  const light = document.documentElement.dataset.theme === 'light';
  const cur = document.documentElement.dataset.palette || THEME_DEFAULT;
  const wrap = h(`<div class="setting look"><div class="grow">Colours<div class="small muted">${esc((THEMES.find((t) => t.id === cur) || THEMES[0]).name)} · on this PC only</div><div class="swatches" role="radiogroup" aria-label="Colour theme"></div></div></div>`);
  const row = wrap.querySelector('.swatches');
  for (const t of THEMES) {
    const p = light ? t.light : t.dark;
    const b = h(`<button class="swatch${t.id === cur ? ' on' : ''}" role="radio" aria-checked="${t.id === cur}" title="${esc(t.note)}" style="--s-bg:${p.bg};--s-surface:${p.surface};--s-fg:${p.fg};--s-muted:${p.muted};--s-accent:${p.accent};--s-blue:${p.blue}">
      <span class="sw-panel"><i></i><i></i></span><span class="sw-dots"><b></b><b></b></span><span class="sw-name">${esc(t.name)}</span></button>`);
    b.onclick = () => { if (t.id === cur) return; setPref('palette', t.id); applyTheme(); render(true); };
    row.appendChild(b);
  }
  return wrap;
}
// <<< themes
/** Every other service with its one-line note, plus adding one of your own. */
function moreServices(shownAlready) {
  const rest = brain.PROVIDERS.filter((p) => !shownAlready.has(p.id));
  const rows = rest.map((p) => `<div class="item" data-id="${esc(p.id)}" style="cursor:pointer"><div class="grow"><div class="t">${esc(p.name)}</div><div class="m">${esc(p.note || '')}</div></div></div>`).join('');
  sheet({ title: 'More Services', primary: null, secondary: 'Close', html: `<div class="list" style="max-height:60vh;overflow:auto">${rows}<div class="item" data-id="_add" style="cursor:pointer"><div class="grow"><div class="t">Add Your Own Service…</div><div class="m">Any service that speaks the OpenAI chat format</div></div></div></div>`,
    onOpen: (o) => o.querySelectorAll('[data-id]').forEach((r) => { r.onclick = () => { o.remove(); if (r.dataset.id === '_add') customService(null); else { brainShown = r.dataset.id; render(true); } }; }) });
}
/** Add (or edit) a service of your own: any OpenAI-compatible address. */
function customService(editing) {
  const v = editing ? { name: editing.name, base: brain.baseOf(editing), model: brain.modelOf(editing) } : { name: '', base: '', model: '' };
  sheet({ title: editing ? editing.name : 'Add a Service', body: 'Any service that speaks the OpenAI chat format, with tool calling. Its keys work like the others: Atlas switches between them when one runs out.', primary: 'Save',
    html: `<input class="field" id="n" placeholder="Name (e.g. My Server)" value="${esc(v.name)}"><input class="field" id="b" placeholder="Address ending in /v1" value="${esc(v.base)}" style="margin-top:8px"><input class="field" id="m" placeholder="Model name" value="${esc(v.model)}" style="margin-top:8px">`,
    onPrimary: (o) => {
      const name = o.querySelector('#n').value.trim(), base = o.querySelector('#b').value.trim(), model = o.querySelector('#m').value.trim();
      if (!name || !/^https?:\/\//.test(base)) { toast('It needs a name and an address starting with https://'); return false; }
      if (editing) brain.updateCustom(editing.id, name, base, model); else brainShown = brain.addCustom(name, base, model).id;
      render(true); return true;
    } });
}
function askText(title, body, value, save) {
  sheet({ title, body, primary: 'Save', html: `<input class="field" id="v" value="${esc(value)}">`, onPrimary: (o) => { save(o.querySelector('#v').value); render(true); return true; } });
}
function applyTheme() {
  document.documentElement.dataset.theme = pref('theme', 'dark');
  // >>> themes: the colour theme, kept on this PC only (prefs.json is never synced)
  const id = pref('palette', THEME_DEFAULT);
  document.documentElement.dataset.palette = THEMES.some((t) => t.id === id) ? id : THEME_DEFAULT;
}

// ------------------------------------------------------------------ alarms and notifications

let alarmQueue = [], alarmEl = null, ringer = null;
function ring() {
  // a soft repeating chime made here (no sound files needed)
  if (ringer || db.state.settings.sound === false) return;
  const ctx = new AudioContext(); let stopAt = Date.now() + 120000;
  const beep = () => {
    if (!ringer || Date.now() > stopAt) return stopRing();
    const t = ctx.currentTime;
    for (const [f, d] of [[880, 0], [1175, 0.18], [1568, 0.36]]) {
      const o = ctx.createOscillator(), g = ctx.createGain(); o.type = 'sine'; o.frequency.value = f; o.connect(g); g.connect(ctx.destination);
      g.gain.setValueAtTime(0.0001, t + d); g.gain.exponentialRampToValueAtTime(0.35, t + d + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + d + 0.5); o.start(t + d); o.stop(t + d + 0.55);
    }
  };
  ringer = { ctx, timer: setInterval(beep, 1600) }; beep();
}
function stopRing() { if (ringer) { clearInterval(ringer.timer); try { ringer.ctx.close(); } catch {} ringer = null; } }
function chime() { try { const ctx = new AudioContext(); const o = ctx.createOscillator(), g = ctx.createGain(); o.frequency.value = 1046; o.connect(g); g.connect(ctx.destination); g.gain.setValueAtTime(0.25, ctx.currentTime); g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6); o.start(); o.stop(ctx.currentTime + 0.6); } catch {} }

function showAlarm(ids, quiet = false) {
  for (const id of ids) if (!alarmQueue.includes(id)) alarmQueue.push(id);
  host.attention(true);
  if (!quiet) ring();
  nextAlarm();
}
function nextAlarm() {
  while (alarmQueue.length && !(store.findTask(alarmQueue[0]) && store.OPEN.has(store.findTask(alarmQueue[0]).status))) alarmQueue.shift();
  if (alarmEl) { alarmEl.remove(); alarmEl = null; }
  if (!alarmQueue.length) { stopRing(); host.attention(false); return; }
  const t = store.findTask(alarmQueue[0]);
  alarmEl = h(`<div class="overlay night"><div class="alarm"><div id="aorb"></div><div class="card pane">
    <div class="when">${esc(t.startAt ? whenLabel(t.startAt) : '')}</div><div class="what">${esc(plainMemory(t.title))}</div><div class="q">Will you do it?</div>
    ${alarmQueue.length > 1 ? `<div class="small muted" style="margin-top:4px">${alarmQueue.length - 1} more after this</div>` : ''}</div>
    <div class="buttons"><button class="btn primary" data-a="now" style="padding:16px;font-size:17px">Doing It Now</button>
    <div class="three"><button class="btn" data-a="done">I'll Do It</button><button class="btn" data-a="snooze">Snooze 10m</button><button class="btn" data-a="skip">Not Doing It</button></div></div></div></div>`);
  document.body.appendChild(alarmEl);
  const o = new Orb(alarmEl.querySelector('#aorb'), { size: 170 }); o.energy = 1;
  alarmEl.onclick = (e) => {
    const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return;
    const id = alarmQueue.shift(); const now = Date.now();
    if (a === 'now') { stopRing(); startFocus(plainMemory(t.title), null, id); }
    if (a === 'done') store.respond(id, 'done');
    if (a === 'snooze') store.respond(id, 'delay', { startAt: now + 600000 });
    if (a === 'skip') store.respond(id, 'skipped', { reason: 'Said not doing it' });
    nextAlarm(); if (tab === 'log') render(true);
  };
}
function checkDue() {
  if (!pref('ringHere', true)) return;
  const due = store.takeDue();
  const important = due.filter((t) => t.priority === 'high');
  if (important.length) showAlarm(important.map((t) => t.id));
  for (const t of due.filter((x) => x.priority !== 'high')) { host.notify(plainMemory(t.title), "It's time. Will you do it?", t.id); chime(); }
  // an alarm answered on the phone closes here too
  if (alarmEl && alarmQueue.length && !store.OPEN.has(store.findTask(alarmQueue[0])?.status)) nextAlarm();
}

// ------------------------------------------------------------------ focus sessions

const F = 'focus';
function startFocus(title, minutes, taskId) {
  if (!pref(F)) setPref(F, { title, taskId: taskId || null, startedAt: Date.now(), planned: minutes || (taskId && store.findTask(taskId)?.durationMin) || null, pausedAt: 0, pausedTotal: 0, breakUntil: 0 });
  showFocus();
}
function showFocus() {
  const s = pref(F); if (!s) return;
  document.querySelectorAll('.focus-ov').forEach((x) => x.remove());
  host.fullscreen(true); host.show();
  const o = h(`<div class="overlay night focus-ov"><div class="focus"><div class="kicker">DOING IT NOW</div><div class="what">${esc(s.title)}</div><div class="muted">No distractions. Just this.</div>
    <div id="forb" style="margin-top:22px"></div><div class="timer" id="ft">00:00</div><div class="muted" id="fs"></div>
    <div class="row" style="margin-top:22px"><button class="btn grow" id="fp">Pause Session</button><button class="btn grow" id="fb">Take a Break</button></div>
    <button class="btn primary end" id="fe">End</button></div></div>`);
  document.body.appendChild(o);
  const orb = new Orb(o.querySelector('#forb'), { size: 130 }); orb.energy = 0.25;
  let chimed = false;
  const worked = (x, now) => Math.max(0, ((x.pausedAt || now) - x.startedAt - x.pausedTotal));
  const fmt = (ms) => { const t = Math.floor(ms / 1000), hh = Math.floor(t / 3600), mm = Math.floor((t % 3600) / 60), ss = t % 60; return hh ? `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`; };
  const tick = () => {
    const x = pref(F); if (!x) { clearInterval(iv); return; }
    const now = Date.now();
    if (x.pausedAt && x.breakUntil && x.breakUntil <= now) { resume(); chime(); return; }
    const onBreak = x.pausedAt && x.breakUntil > now;
    o.querySelector('#ft').textContent = onBreak ? fmt(x.breakUntil - now) : fmt(worked(x, now));
    o.querySelector('#ft').style.opacity = x.pausedAt && !onBreak ? 0.55 : 1;
    const planned = x.planned ? x.planned * 60000 : null;
    o.querySelector('#fs').textContent = onBreak ? 'On a break. It ends by itself.' : x.pausedAt ? 'Paused' : planned && worked(x, now) >= planned ? `Planned ${x.planned} min done. Keep going, or End.` : planned ? `of ${x.planned} min` : 'focused';
    if (planned && worked(x, now) >= planned && !chimed) { chimed = true; chime(); }
    o.querySelector('#fp').textContent = onBreak ? 'End Break' : x.pausedAt ? 'Resume' : 'Pause Session';
    o.querySelector('#fb').disabled = !!x.pausedAt; o.querySelector('#fb').style.opacity = x.pausedAt ? 0.5 : 1;
    orb.energy = x.pausedAt ? 0 : 0.25;
  };
  const resume = () => { const x = pref(F); const now = Date.now(); if (x.pausedAt) { x.pausedTotal += now - x.pausedAt; x.pausedAt = 0; } x.breakUntil = 0; setPref(F, { ...x }); };
  o.querySelector('#fp').onclick = () => { const x = pref(F); if (x.pausedAt) resume(); else setPref(F, { ...x, pausedAt: Date.now() }); tick(); };
  o.querySelector('#fb').onclick = () => sheet({ title: 'Take a Break', html: '<div class="chips" style="justify-content:center"><span class="chip" data-m="5">5 minutes</span><span class="chip" data-m="10">10 minutes</span><span class="chip" data-m="15">15 minutes</span></div>', primary: null,
    onOpen: (so) => so.querySelectorAll('[data-m]').forEach((c) => (c.onclick = () => { const x = pref(F); setPref(F, { ...x, pausedAt: Date.now(), breakUntil: Date.now() + (+c.dataset.m) * 60000 }); so.remove(); tick(); })) });
  o.querySelector('#fe').onclick = () => {
    const x = pref(F); const mins = Math.floor(worked(x, Date.now()) / 60000);
    if (x.taskId && store.findTask(x.taskId) && store.OPEN.has(store.findTask(x.taskId).status)) store.respond(x.taskId, 'done');
    store.logFocus(x.title, x.taskId, mins); setPref(F, null);
    clearInterval(iv); o.remove(); host.fullscreen(false); toast(mins >= 1 ? `Session done: ${mins} min. Nice work.` : 'Session ended.'); if (tab === 'log') render(true);
  };
  // Escape doesn't end it: only End does
  o.tabIndex = -1; o.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); o.querySelector('#fs').textContent = 'Press End when you\'re finished.'; } });
  const iv = setInterval(tick, 500); tick();
}

// ------------------------------------------------------------------ Atlas's own settings (change_setting)

brain.ui.confirm = (title, detail, yes) => sheet({ title, html: `<pre>${esc(detail)}</pre>`, primary: yes, secondary: 'No' });
trade.setConfirm((title, detail, yes) => { try { host.show && host.show(); } catch {} return sheet({ title, html: `<pre style="white-space:pre-wrap">${esc(detail)}</pre>`, primary: yes, secondary: "Don't" }); });   // trading: a LIVE order waits for this tap
brain.ui.startFocus = startFocus;
brain.ui.settingsText = () => {
  const on = (b) => (b ? 'on' : 'off'); const eng = voice.engine();
  return [`- speak_replies (on/off) — now ${on(pref('speakReplies', false))}`,
    `- voice: now ${eng ? voice.current(eng).label : "Windows' voice"}; choices: ${voice.voices(eng).map((v) => v.label).join(', ')}`,
    `- theme (dark/light) — now ${pref('theme', 'dark')}`, `- color_theme (${THEMES.map((t) => t.name).join(' / ')}): the app's colours on this PC — now ${(THEMES.find((t) => t.id === document.documentElement.dataset.palette) || THEMES[0]).name}`, `- on_screen (on/off): the orb in the corner of the PC — now ${on(pref('onScreen', true))}`,
    `- your_name — now ${db.state.settings.name || 'not set'}`, `- alarm_sound (on/off) — now ${on(db.state.settings.sound !== false)}`, '- start_with_windows (on/off)', `- speaking_speed (slow/normal/fast): how fast Atlas talks — now ${voice.speed()}`, `- keep_listening (on/off): after a spoken answer, listen again — now ${on(pref('keepTalking', true))}`, `- listen_language (a code like en, en-GB, fr, yo, or auto) — now ${voice.language() || 'auto'}`, `- dont_switch (on/off): stay on the chosen AI service instead of moving to another when it fails or runs out (other keys of the same service still take over) — now ${on(brain.dontSwitch())}`].join('\n');
};
brain.ui.setting = async (name, value) => {
  const flag = { on: true, true: true, yes: true, off: false, false: false, no: false }[String(value).toLowerCase().trim()];
  const need = () => { if (flag === undefined) throw new Error(`Say on or off for ${name}.`); return flag; };
  try {
    switch (name) {
      case 'speak_replies': setPref('speakReplies', need()); return `Replies will ${flag ? '' : 'no longer '}be read aloud.`;
      case 'voice': { const eng = voice.engine(); if (!eng) return 'Natural voices need an ElevenLabs, Gemini or OpenAI key (Me).'; const n = value.toLowerCase(); const v = voice.voices(eng).find((x) => x.id === n || x.label.toLowerCase() === n || n.includes(x.id)) || voice.voices(eng).find((x) => x.about.split(/[ ,]+/).some((w) => w.length > 3 && n.includes(w))); if (!v) return `Error: no voice called "${value}".`; setPref('voice_' + eng, v.id); return `Voice set to ${v.label} (${v.about}).`; }
      case 'color_theme': { const t = findTheme(value); if (!t) return `Error: the colour themes are ${THEMES.map((x) => x.name).join(', ')}.`; setPref('palette', t.id); applyTheme(); render(true); return `Switched to ${t.name}.`; }
      case 'theme': { if (!/light|dark/i.test(value)) { const ct = findTheme(String(value).replace(/theme|the /gi, '').trim()); if (ct) { setPref('palette', ct.id); applyTheme(); render(true); return `Switched to ${ct.name}.`; } } const t = /light/i.test(value) ? 'light' : /dark/i.test(value) ? 'dark' : null; if (!t) return 'Error: theme is dark or light.'; setPref('theme', t); applyTheme(); return `Switched to ${t} mode.`; }
      case 'on_screen': setPref('onScreen', need()); host.showOrb(flag); return flag ? "I'm in the corner of your screen now." : "I'm off your screen.";
      case 'your_name': if (!value.trim()) return 'Error: give the name.'; db.state.settings.name = value.trim().slice(0, 40); store.saveState(); return `I'll call you ${value.trim()}.`;
      case 'alarm_sound': db.state.settings.sound = need(); store.saveState(); return `Alarm sound ${flag ? 'on' : 'off'}.`;
      case 'speaking_speed': return `I'll speak at ${voice.setSpeed(value)} speed.`;
      case 'keep_listening': { const b = /^(on|true|yes|1|enable)/i.test(String(value).trim()); setPref('keepTalking', b); return b ? "I'll keep listening after I answer." : "I'll listen only when you ask me to."; }
      case 'listen_language': { const l = String(value).trim(); if (!/^auto$/i.test(l) && !/^[A-Za-z]{2,3}(-[A-Za-z]{2})?$/.test(l)) return 'Error: give a language code like en, en-GB, fr or yo (or auto).'; voice.setLanguage(l); return /^auto$/i.test(l) ? "I'll work out the language myself." : `I'll listen for ${l}.`; }
      case 'dont_switch': setPref('dont_switch', need()); return flag ? "I'll stay on the service you chose and won't switch to another one (your other keys for it can still take over)." : "I'll switch to another service again when one keeps failing or runs out.";
      case 'start_with_windows': await host.autostart(need()); return flag ? 'I\'ll start with Windows (quietly, in the tray).' : "I won't start with Windows.";
    }
    return `Error: I can't change "${name}".`;
  } catch (e) { return 'Error: ' + e.message; }
};

// ------------------------------------------------------------------ start

async function main() {
  await store.load();
  await bots.init();
  await extras.init();
  // Atlas Bot v2: the ready-made bots, and the conversations with bots (each bot's own, and group chats)
  for (const t of BTPL.templates()) registerTemplate(t);
  try { await THREADS.init(); } catch (e) { console.error('threads', e); }   // its changes redraw the Bots page through changed('bots')
  aware.use({ threads: () => THREADS.briefLines(6) });
  // >>> connections: the main process reconnects them at start; redraw Me when their status changes
  conn.init(); conn.state.listeners.add(() => { if (tab === 'me') render(true, true); });
  // <<< connections
  applyTheme();
  // >>> smooth: Esc stops Atlas while it's answering (unless a sheet or overlay is open: Esc is theirs)
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && brain.convo.busy && !document.querySelector('.overlay')) { e.preventDefault(); brain.stop(); toast('Stopped.'); } });
  // and a quiet note while the PC is offline: replies and sync wait for the connection
  const offline = () => { let b = $('#offline'); if (navigator.onLine) { b?.remove(); return; } if (!b) { b = h('<div id="offline" role="status">Offline. Atlas answers again when you\'re back online.</div>'); document.body.appendChild(b); } };
  window.addEventListener('online', offline); window.addEventListener('offline', offline); offline();
  // <<< smooth
  document.querySelectorAll('.nav').forEach((n) => (n.onclick = () => { tab = n.dataset.tab; logFull = false; botView = null; render(); }));
  buildComposer();
  render();
  // the chat follows the latest messages: scrolling shows "Latest ↓"; touching the page stops the follow; back after a while → latest
  const pg = $('#page');
  pg.addEventListener('scroll', latestChip, { passive: true });
  for (const ev of ['wheel', 'touchstart', 'mousedown', 'keydown']) pg.addEventListener(ev, () => { followUntil = 0; }, { passive: true });
  window.addEventListener('blur', () => { awaySince = Date.now(); });
  window.addEventListener('focus', () => { if (tab === 'atlas' && db.chat.length && awaySince && Date.now() - awaySince > 20000) toLatest(); });
  // the page scrolls to the newest message as the conversation moves
  onChange((what) => {
    if (what === 'chat') {
      updateSend();
      if (tab === 'atlas') { if (!$('#chat') && db.chat.length) render(); else if (!db.chat.length && $('#chat')) render(); else { const p = $('#page'); const atBottom = p.scrollTop + p.clientHeight >= p.scrollHeight - 260; fillChat(); if (atBottom || brain.convo.busy) toLatest(); else latestChip(); } }
      if (!brain.convo.busy) sync.syncSoon();
    }
    if (what === 'state') { sync.syncSoon(); if (tab === 'log') render(true, true); }
    if (what === 'sync') { renderFoot(); if (tab === 'me') render(true, true); }
    if (what === 'bots') {
      // the Bots page redraws (keeping what's being typed); the strip on the Atlas tab follows along
      if (tab === 'bots') {
        // keep whatever box is being typed in (a bot's conversation, an answer) focused through the redraw
        const a = document.activeElement; const id = a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT') && a.id ? a.id : null; const pos = id ? a.selectionStart : 0;
        render(true, true);
        if (id) { const t = $('#' + id); if (t) { t.focus(); try { t.selectionStart = t.selectionEnd = pos; } catch {} } }
      }
      if (tab === 'atlas') { const line = botSummary(bots.jobs, Date.now()); const st = $('#botstrip'); if (!!st !== !!line) render(true); else if (st) st.querySelector('span').textContent = line; }
    }
    if (what === 'prefs' && tab === 'atlas') { const bl = $('#brainline'); if (bl && !brain.convo.busy) bl.textContent = brainLine(); }
  });
  document.addEventListener('click', (e) => { const c = e.target.closest('.copy'); if (c) { navigator.clipboard.writeText(c.dataset.copy); toast('Copied'); } });
  host.on('orb-clicked', () => startListening());
  host.on('notification-click', (id) => {
    if (id && String(id).startsWith('bot:')) { tab = 'bots'; botView = String(id).slice(4); render(); return; }
    if (id && store.findTask(id)) showAlarm([id], true);
  });
  host.showOrb(pref('onScreen', true));
  if (pref(F)) showFocus();
  checkDue(); setInterval(checkDue, 15000);
  keyshare.register(); botmirror.register({ visible: () => bots.visible(), labels: BOT_LABELS });
  sync.loadStatus(); sync.syncSoon(800); sync.keepFresh();
  // >>> device link: the live line to the phone (tasks both ways, remote control)
  LW.start({ sheet, toast }); brain.ui.onPhone = (task) => LW.sendTask(task);
  LW.onChange(() => { if (tab === 'me') render(true, true); });
  // <<< device link
}
main();
