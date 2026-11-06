// Atlas on the PC: the screens (Atlas, Log, Me), alarms, focus sessions, and everything joined up.
import * as store from './store.js';
import { db, pref, setPref, onChange } from './store.js';
import * as brain from './brain.js';
import * as sync from './sync.js';
import * as voice from './voice.js';
import { Orb } from './orb.js';
import { renderMarkdown } from './md.js';
import { plainMemory } from './plain.js';
const host = window.atlasHost;

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
function h(html) { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; }
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
  mic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2.5"/></svg>',
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
  if (tab === 'atlas') renderAtlas(page); else if (tab === 'log') renderLog(page); else renderMe(page);
  $('#composer').style.display = tab === 'atlas' ? '' : 'none';
  for (const k of kept) {
    const el = page.querySelector('#' + CSS.escape(k.id)); if (!el) continue;
    if (k.v && !el.value) el.value = k.v;
    if (k.f) { el.focus(); try { el.setSelectionRange(k.s, k.e); } catch {} }
  }
  if (keepScroll) page.scrollTop = y;
  renderFoot();
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
    if (!brain.active()) hello.appendChild(h('<p class="muted small" style="margin-top:20px">To think, Atlas needs an AI key (Gemini\'s is free) or a local model: Me → Brain.</p>'));
    return;
  }
  const head = h(`<div class="chathead"><div class="grow"><div style="font:500 20px var(--serif)">Atlas</div><div class="small muted" id="brainline"></div></div>
    <button class="btn" id="newchat">New Chat</button></div>`);
  const orbSlot = h('<div style="width:56px;height:56px"></div>'); head.prepend(orbSlot); col.appendChild(head);
  homeOrb = new Orb(orbSlot, { size: 56 }); homeOrb.el.style.cursor = 'pointer'; homeOrb.el.onclick = () => startListening();
  head.querySelector('#newchat').onclick = async () => { if (await sheet({ title: 'Start a New Chat?', body: 'This clears the conversation on this PC. Your Log stays as it is.', primary: 'New Chat' })) { db.chat = []; store.saveChat(); render(); } };
  const chat = h('<div class="chat" id="chat"></div>'); col.appendChild(chat);
  fillChat();
}
function brainLine() { const p = brain.active(); return p ? `${p.name} · ${brain.modelOf(p)}` + (brain.fallbacks(p.id).length ? ` · ${brain.fallbacks(p.id).length} backup service${brain.fallbacks(p.id).length > 1 ? 's' : ''}` : '') : 'No brain set up yet · add an AI key in Me'; }
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
    return v;
  }
  const v = h('<div class="msg atlas"></div>');
  const working = last && brain.convo.busy && m.pending;
  if (!m.text) v.appendChild(h(`<div class="thinking">${esc(working ? (brain.convo.status || 'Thinking') : '')}</div>`));
  else { const r = h(`<div class="reply ${m.source === 'error' ? 'bad' : ''}"></div>`); r.innerHTML = renderMarkdown(m.text); v.appendChild(r); }
  if (m.steps && m.steps.length) {
    const s = m.steps.length <= 3 ? m.steps.join(' · ') : m.steps.slice(0, 2).join(' · ') + ` · and ${m.steps.length - 2} more`;
    const st = h(`<div class="steps">${esc(s)}</div>`); st.onclick = () => sheet({ title: 'What Atlas Did', html: `<div class="list">${m.steps.map((x) => `<div class="item">${esc(x)}</div>`).join('')}</div>`, primary: 'Close', secondary: null });
    v.appendChild(st);
  }
  const tag = working && m.text ? (brain.convo.status || 'Working') + '…' : (m.source === 'error' || m.source === 'Atlas' ? '' : m.source);
  if (tag) v.appendChild(h(`<div class="source">${esc(tag)}</div>`));
  return v;
}

// ---- the ask bar ----
function buildComposer() {
  const c = $('#composer');
  c.innerHTML = `<div class="column"><div id="tray"></div><div class="askbar">
    <div class="round" id="attach" title="Add pictures, a video or files">${ICON.plus}</div>
    <textarea id="ask" rows="1" placeholder="Ask anything…"></textarea>
    <div class="round go" id="go" title="Speak">${ICON.mic}</div></div>
    <div class="hint">Enter to send · Shift+Enter for a new line · Ctrl+Shift+Space to talk from anywhere</div></div>`;
  const ta = $('#ask');
  ta.addEventListener('input', () => { autosize(); updateSend(); });
  ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } });
  ta.addEventListener('paste', (e) => { const files = [...(e.clipboardData?.files || [])]; if (files.length) { e.preventDefault(); files.forEach(addFileObject); } });
  $('#attach').onclick = async () => { for (const p of await host.pickFiles()) await addPath(p); };
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
    if (attachments.some((a) => a.kind === 'video') || attachments.length >= 4) return toast('Up to 4 per message (a video goes on its own).');
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
async function addPath(p) {
  if (attachments.some((a) => a.kind === 'video') || attachments.length >= 4) return toast('Up to 4 per message (a video goes on its own).');
  try {
    const a = await host.loadFile(p);
    if (a.kind === 'video' && attachments.length) return toast('A video goes on its own. Send the others first.');
    if (a.kind === 'video') { const secs = await videoSeconds(a); if (secs > 61) return toast(`That video is ${Math.round(secs)} seconds; videos can be up to a minute.`); }
    if (a.kind === 'image') a.thumb = 'data:image/jpeg;base64,' + a.base64;
    attachments.push(a); showTray(); updateSend();
  } catch (e) { toast(String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '')); }
}
function showTray() {
  const t = $('#tray'); t.innerHTML = '';
  attachments.forEach((a, i) => {
    const cell = h(`<div class="att">${a.thumb ? `<img class="thumb" src="${a.thumb}" alt="">` : `<div class="card filetile"><b>${esc(a.kind === 'pdf' ? 'PDF' : a.kind === 'video' ? 'VIDEO' : a.name.split('.').pop().toUpperCase().slice(0, 4))}</b>${esc(a.name)}</div>`}<button class="x" title="Remove">✕</button></div>`);
    cell.querySelector('.x').onclick = () => { attachments.splice(i, 1); showTray(); updateSend(); };
    t.appendChild(cell);
  });
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
  listenCtl = voice.listen({ onLevel: (l) => { orb.level = l; host.orbState({ energy: 1, level: l, listening: true }); } });
  o.querySelector('[data-k=done]').onclick = () => listenCtl && listenCtl.stop();
  o.querySelector('[data-k=cancel]').onclick = () => { cancelled = true; listenCtl && listenCtl.stop(); };
  try {
    o.querySelector('.heard').textContent = 'Listening…';
    const p = listenCtl.done;
    const words = await p.then((w) => w, (e) => { throw e; });
    o.querySelector('.heard').textContent = words ? words : '';
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
function settingRow(label, on, onToggle, note) {
  const r = h(`<div class="setting"><div class="grow"><div>${esc(label)}</div>${note ? `<div class="small muted">${esc(note)}</div>` : ''}</div><div class="switch ${on ? 'on' : ''}"></div></div>`);
  r.querySelector('.switch').onclick = (e) => { e.currentTarget.classList.toggle('on'); onToggle(e.currentTarget.classList.contains('on')); };
  return r;
}
function renderMe(page) {
  const col = h('<div class="column"></div>'); page.appendChild(col);
  col.appendChild(h('<h1 class="title">Me</h1>'));
  col.appendChild(h('<p class="sub">How Atlas thinks, sounds and connects on this PC.</p>'));

  // Brain
  col.appendChild(h('<div class="eyebrow">Brain</div>'));
  const act = brain.active(); const shown = brain.byId(brainShown) || act || brain.PROVIDERS[0]; brainShown = shown.id;
  const bc = h('<div class="card"></div>'); col.appendChild(bc);
  bc.appendChild(h(`<div style="font:400 24px var(--serif)">${esc(act ? `${act.name} · ${brain.modelOf(act)}` : 'Not set up')}</div>`));
  bc.appendChild(h(`<p class="${act ? 'ok' : 'muted'}" style="margin:4px 0 14px">${act ? `Atlas thinks with ${esc(act.name)}. Add as many keys as you like: when one runs out, Atlas switches to the next and carries on, then to another service.` : 'Add a key from any AI service, or use a model running on this PC.'}</p>`));
  const chips = h('<div class="chips"></div>');
  for (const p of brain.PROVIDERS) { const c = h(`<span class="chip ${p.id === shown.id ? 'on' : ''}">${esc(p.name)}</span>`); c.onclick = () => { brainShown = p.id; render(true); }; chips.appendChild(c); }
  bc.appendChild(chips);
  if (shown.local) {
    bc.appendChild(h(`<p class="muted small" style="margin-top:14px">Models running on this PC with Ollama or LM Studio. They work offline; when you're offline, Atlas uses one automatically if it's set here.</p>`));
    const found = h('<div class="list" style="margin-top:6px"><div class="item muted">Looking for models on this PC…</div></div>'); bc.appendChild(found);
    brain.detectLocal().then((ms) => {
      found.innerHTML = '';
      if (!ms.length) found.appendChild(h('<div class="item muted">None found. Start Ollama (ollama.com) or LM Studio\'s server, then come back here.</div>'));
      for (const m of ms) {
        const on = brain.modelOf(shown) === m.model && brain.baseOf(shown) === m.base;
        const r = h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${esc(m.model)}</div><div class="m">${esc(m.server)}</div></div><span class="tag ${on ? 'ok' : ''}">${on ? 'In use' : 'Use'}</span></div>`);
        r.onclick = () => { setPref('model_local', m.model); setPref('base_local', m.base); setPref('provider', 'local'); toast('Using ' + m.model); render(true); };
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
      const g = brain.guess(k); const id = g && !(g === 'openai' && ['deepseek', 'custom', 'openrouter', 'groq', 'xai'].includes(shown.id)) ? g : shown.id;
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
  }
  if (!shown.local) { const mr = h(`<div class="setting"><div class="grow">Model<div class="small muted">${esc(brain.modelOf(shown) || 'Not set')}</div></div><button class="btn">Change</button></div>`);
    mr.querySelector('button').onclick = () => askText(`${shown.name} Model`, shown.model ? `Leave it as ${shown.model} unless you want another of ${shown.name}'s models.` : "The model's name, as your service writes it.", brain.modelOf(shown), (v) => setPref('model_' + shown.id, v.trim())); bc.appendChild(mr); }
  bc.appendChild(h('<p class="small muted" style="margin-top:10px">Keys stay on this PC (they don\'t sync). When Atlas thinks online, what you say goes to that service.</p>'));

  // Voice
  col.appendChild(h('<div class="eyebrow">Voice</div>'));
  const vc = h('<div class="card"></div>'); col.appendChild(vc);
  vc.appendChild(settingRow('Speak Replies', pref('speakReplies', false), (on) => setPref('speakReplies', on), 'Spoken questions always get a spoken answer.'));
  vc.appendChild(settingRow('Natural Voice', pref('voiceNatural', true), (on) => { setPref('voiceNatural', on); render(true); }));
  const eng = voice.engine();
  vc.appendChild(h(`<p class="small muted">${!pref('voiceNatural', true) ? "Using Windows' own voice." : eng === 'gemini' ? 'A human-sounding voice from Gemini.' : eng === 'openai' ? 'A human-sounding voice from OpenAI.' : 'Natural voices come from Gemini or OpenAI: add one of their keys. Until then, the best Windows voice.'}${voice.state.lastSpoke ? ' Last reply: ' + esc(voice.state.lastSpoke) + '.' : ''}${voice.state.lastProblem ? ' The natural voice failed last time (' + esc(voice.state.lastProblem) + ').' : ''}</p>`));
  if (eng) {
    const l = h('<div class="list"></div>'); const cur = voice.current(eng).id;
    for (const v of voice.voices(eng)) {
      const r = h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${esc(v.label)}</div><div class="m">${esc(v.about)}</div></div><span class="tag ${v.id === cur ? 'accent' : ''}">${v.id === cur ? 'Chosen · Hear It' : 'Hear It'}</span></div>`);
      r.onclick = () => { voice.preview(eng, v.id); render(true); };
      l.appendChild(r);
    }
    vc.appendChild(l);
  }

  // Devices
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
    dc.appendChild(h(`<div style="font:400 22px var(--serif)">Linked</div><p class="muted" style="margin:4px 0 10px">${esc(sync.status.last || 'Syncing…')}${sync.status.at ? ' · ' + esc(logged(sync.status.at)) : ''}</p>`));
    dc.appendChild(h(`<div class="small muted">Your link code (keep it private):</div>`));
    dc.appendChild(h(`<div class="field mono" style="margin:6px 0 10px;text-align:center;font-size:17px;user-select:all">${esc(code)}</div>`));
    const r = h('<div class="row"><button class="btn">Copy Code</button><button class="btn">Sync Now</button><button class="btn danger">Unlink This PC</button></div>'); dc.appendChild(r);
    r.children[0].onclick = () => { navigator.clipboard.writeText(code); toast('Copied'); };
    r.children[1].onclick = async () => { toast('Syncing…'); toast(await sync.runOnce()); render(true); };
    r.children[2].onclick = async () => { if (await sheet({ title: 'Unlink This PC?', body: 'It stops syncing. Everything stays on this PC; the phone keeps its copy.', primary: 'Unlink', danger: true })) { await sync.unlink(); render(true); } };
  }

  // This PC
  col.appendChild(h('<div class="eyebrow">On This PC</div>'));
  const pc = h('<div class="card"></div>'); col.appendChild(pc);
  pc.appendChild(settingRow('Atlas On Screen', pref('onScreen', true), (on) => { setPref('onScreen', on); host.showOrb(on); }, 'A small orb in the corner, over everything. Click it to talk.'));
  pc.appendChild(settingRow('Reminders Ring Here Too', pref('ringHere', true), (on) => setPref('ringHere', on), 'Alarms and notifications on this PC as well as the phone.'));
  pc.appendChild(settingRow('Alarm Sound', db.state.settings.sound !== false, (on) => { db.state.settings.sound = on; store.saveState(); }));
  const auto = settingRow('Start With Windows', false, (on) => host.autostart(on), 'Quietly, in the tray: reminders and sync keep going.'); pc.appendChild(auto);
  host.autostart().then((on) => auto.querySelector('.switch').classList.toggle('on', !!on));
  pc.appendChild(settingRow('Light Look', document.documentElement.dataset.theme === 'light', (on) => { setPref('theme', on ? 'light' : 'dark'); applyTheme(); }));
  const nm = h(`<div class="setting"><div class="grow">Your Name<div class="small muted">${esc(db.state.settings.name || 'Not set')}</div></div><button class="btn">Change</button></div>`);
  nm.querySelector('button').onclick = () => askText('Your Name', 'What Atlas calls you.', db.state.settings.name || '', (v) => { db.state.settings.name = v.trim().slice(0, 40); store.saveState(); });
  pc.appendChild(nm);
  host.info().then((i) => col.appendChild(h(`<p class="small muted" style="text-align:center;margin-top:24px">Atlas ${esc(i.version)} for Windows</p>`)));
}
function askText(title, body, value, save) {
  sheet({ title, body, primary: 'Save', html: `<input class="field" id="v" value="${esc(value)}">`, onPrimary: (o) => { save(o.querySelector('#v').value); render(true); return true; } });
}
function applyTheme() { document.documentElement.dataset.theme = pref('theme', 'dark'); }

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
brain.ui.startFocus = startFocus;
brain.ui.settingsText = () => {
  const on = (b) => (b ? 'on' : 'off'); const eng = voice.engine();
  return [`- speak_replies (on/off) — now ${on(pref('speakReplies', false))}`,
    `- voice: now ${eng ? voice.current(eng).label : "Windows' voice"}; choices: ${voice.voices(eng).map((v) => v.label).join(', ')}`,
    `- theme (dark/light) — now ${pref('theme', 'dark')}`, `- on_screen (on/off): the orb in the corner of the PC — now ${on(pref('onScreen', true))}`,
    `- your_name — now ${db.state.settings.name || 'not set'}`, `- alarm_sound (on/off) — now ${on(db.state.settings.sound !== false)}`, '- start_with_windows (on/off)'].join('\n');
};
brain.ui.setting = async (name, value) => {
  const flag = { on: true, true: true, yes: true, off: false, false: false, no: false }[String(value).toLowerCase().trim()];
  const need = () => { if (flag === undefined) throw new Error(`Say on or off for ${name}.`); return flag; };
  try {
    switch (name) {
      case 'speak_replies': setPref('speakReplies', need()); return `Replies will ${flag ? '' : 'no longer '}be read aloud.`;
      case 'voice': { const eng = voice.engine(); if (!eng) return 'Natural voices need a Gemini or OpenAI key (Me → Brain).'; const n = value.toLowerCase(); const v = voice.voices(eng).find((x) => x.id === n || x.label.toLowerCase() === n || n.includes(x.id)) || voice.voices(eng).find((x) => x.about.split(/[ ,]+/).some((w) => w.length > 3 && n.includes(w))); if (!v) return `Error: no voice called "${value}".`; setPref('voice_' + eng, v.id); return `Voice set to ${v.label} (${v.about}).`; }
      case 'theme': { const t = /light/i.test(value) ? 'light' : /dark/i.test(value) ? 'dark' : null; if (!t) return 'Error: theme is dark or light.'; setPref('theme', t); applyTheme(); return `Switched to ${t} mode.`; }
      case 'on_screen': setPref('onScreen', need()); host.showOrb(flag); return flag ? "I'm in the corner of your screen now." : "I'm off your screen.";
      case 'your_name': if (!value.trim()) return 'Error: give the name.'; db.state.settings.name = value.trim().slice(0, 40); store.saveState(); return `I'll call you ${value.trim()}.`;
      case 'alarm_sound': db.state.settings.sound = need(); store.saveState(); return `Alarm sound ${flag ? 'on' : 'off'}.`;
      case 'start_with_windows': await host.autostart(need()); return flag ? 'I\'ll start with Windows (quietly, in the tray).' : "I won't start with Windows.";
    }
    return `Error: I can't change "${name}".`;
  } catch (e) { return 'Error: ' + e.message; }
};

// ------------------------------------------------------------------ start

async function main() {
  await store.load();
  applyTheme();
  document.querySelectorAll('.nav').forEach((n) => (n.onclick = () => { tab = n.dataset.tab; logFull = false; render(); }));
  buildComposer();
  render();
  // the page scrolls to the newest message as the conversation moves
  onChange((what) => {
    if (what === 'chat') {
      updateSend();
      if (tab === 'atlas') { if (!$('#chat') && db.chat.length) render(); else if (!db.chat.length && $('#chat')) render(); else { const p = $('#page'); const atBottom = p.scrollTop + p.clientHeight >= p.scrollHeight - 260; fillChat(); if (atBottom || brain.convo.busy) p.scrollTop = p.scrollHeight; } }
      if (!brain.convo.busy) sync.syncSoon();
    }
    if (what === 'state') { sync.syncSoon(); if (tab === 'log') render(true, true); }
    if (what === 'sync') { renderFoot(); if (tab === 'me') render(true, true); }
    if (what === 'prefs' && tab === 'atlas') { const bl = $('#brainline'); if (bl && !brain.convo.busy) bl.textContent = brainLine(); }
  });
  document.addEventListener('click', (e) => { const c = e.target.closest('.copy'); if (c) { navigator.clipboard.writeText(c.dataset.copy); toast('Copied'); } });
  host.on('orb-clicked', () => startListening());
  host.on('notification-click', (id) => { if (id && store.findTask(id)) showAlarm([id], true); });
  host.showOrb(pref('onScreen', true));
  if (pref(F)) showFocus();
  checkDue(); setInterval(checkDue, 15000);
  sync.syncSoon(800); setInterval(() => sync.syncSoon(0), 5 * 60000);
  window.addEventListener('focus', () => sync.syncSoon(500));
}
main();
