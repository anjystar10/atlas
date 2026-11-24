// HUD mode: the PC's cinematic, voice-first face of Atlas. A full-window dark screen with the holographic core in the
// middle, live cards for whatever Atlas and its bots are doing, a results wall Atlas lays out with show_panels, and a
// terminal for typing. It's a layer over the classic window (same store, brain, voice, bots and sync), so nothing is
// duplicated: app.js plugs its submit/listen/new-chat in with init() and hands over to it while the HUD is on.
import { db, pref, setPref, onChange } from './store.js';
import * as brain from './brain.js';
import * as voice from './voice.js';
import * as sync from './sync.js';
import * as aware from './aware.js';
import { bots } from './botwire.js';
import { FINAL as BOT_FINAL, summary as botSummary } from './bots.js';
import { Core } from './hudring.js';
import * as H from './hudcore.js';
import { renderMarkdown } from './md.js';
import { endsTalk } from './speech.js';

const host = window.atlasHost;
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
function h(html) { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; }
const $ = (sel) => root && root.querySelector(sel);

let deps = null, root = null, core = null, listenCtl = null, walls = [], wall = null, termOpen = false, termFull = false;
let micLevel = 0, outLevel = 0, heard = '', prevTheme = null, camStream = null, lastStatus = '', speakTap = null;
const reduceMotion = () => pref('reduceMotion', false) || window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export const active = () => pref('pcLook', 'hud') === 'hud';
export const isOn = () => !!root;

/** app.js calls this once at start. deps: { submit(spoken, text, attachments), newChat(), sheet, toast, classicListen() } */
export function init(d) {
  deps = d;
  registerTool();
  loadWalls();
  document.addEventListener('keydown', onKey, true);
  if (active()) enter();
}

// ------------------------------------------------------------------ the show_panels tool (only while the HUD is on)

function registerTool() {
  aware.register({
    id: 'hud', forChat: true, forBots: false,
    tools: () => (isOn() ? [H.SHOW_PANELS] : []),
    brief: () => (isOn() ? 'The PC is in HUD mode: for research, comparisons, numbers, pictures, videos or links, also lay the findings out with show_panels (only addresses your tools found).' : null),
    run: async (call) => {
      if (call.name !== 'show_panels') return null;
      const r = H.cleanPanels(call.args || {});
      if (!r.panels.length) return { out: `Error: nothing could be shown (${r.dropped.join('; ') || 'no panels'}).` };
      const shown = await showWall({ id: crypto.randomUUID(), at: Date.now(), title: r.title, panels: r.panels });
      const note = [...r.dropped, ...shown.failed].join('; ');
      return { out: `Shown on the screen: ${shown.count} panel${shown.count === 1 ? '' : 's'} titled "${r.title}".${note ? ' Not shown: ' + note + '.' : ''}`, step: `Showed ${shown.count} panels` };
    },
  });
}

// ------------------------------------------------------------------ on and off

export function enter() {
  if (root) return;
  setPref('pcLook', 'hud');
  prevTheme = document.documentElement.dataset.theme; document.documentElement.dataset.theme = 'dark';
  document.body.classList.add('hud-on');
  root = h(`<div id="hud" class="hud" role="application" aria-label="Atlas HUD">
    <div class="hud-bg" aria-hidden="true"></div>
    <header class="hud-top">
      <div class="hud-id"><div class="hud-name">ATLAS <span class="hud-dim">//</span> <span id="hudlink">ONLINE</span></div><div class="hud-meta" id="hudmeta"></div></div>
      <nav class="hud-tools" aria-label="HUD controls">
        <button class="hud-btn" data-k="history" title="Earlier result walls">HISTORY</button>
        <button class="hud-btn" data-k="text" title="Type to Atlas (Ctrl+\`)">TEXT</button>
        <button class="hud-btn" data-k="clear" title="Clear the results wall">CLEAR VIEW</button>
        <button class="hud-btn" data-k="camera" title="Let Atlas see through the webcam">CAMERA</button>
        <select class="hud-btn hud-select" id="hudcam" aria-label="Which camera"><option value="">DEFAULT CAMERA</option></select>
        <button class="hud-btn" data-k="screens" title="What each bot is looking at">SCREENS</button>
        <button class="hud-btn" data-k="classic" title="The classic window (Ctrl+Alt+H)">CLASSIC</button>
      </nav>
    </header>
    <section class="hud-cards" id="hudcards" aria-label="Work in progress" aria-live="polite"></section>
    <div class="hud-stage" id="hudstage">
      <button class="hud-corebtn" id="hudcore" aria-label="Talk to Atlas (Space)"></button>
      <div class="hud-state mono" id="hudstate">STANDING BY</div>
      <div class="hud-line" id="hudline"></div>
    </div>
    <section class="hud-wall" id="hudwall" aria-label="Results" hidden></section>
    <footer class="hud-bottom">
      <div class="hud-meter" aria-label="Microphone level"><span class="hud-lbl">MIC</span><span class="hud-bars" id="micbars"></span><span class="hud-db" id="micdb">STANDBY</span></div>
      <div class="hud-heard" id="hudheard" aria-live="polite"></div>
      <div class="hud-meter" aria-label="Voice level"><span class="hud-lbl">OUT</span><span class="hud-bars" id="outbars"></span><span class="hud-db" id="outdb">-∞ dB</span></div>
      <button class="hud-btn" data-k="mute" id="hudmute"></button>
      <button class="hud-btn hud-talk" data-k="talk">TALK <span class="hud-dim">SPACE</span></button>
    </footer>
    <div class="hud-panelbox" id="hudbox" hidden></div>
    <div class="hud-cam" id="hudcamview" hidden><video autoplay playsinline muted></video><div class="hud-rec"><i></i>CAMERA ON</div>
      <div class="hud-camacts"><button class="hud-btn" data-k="look">ASK ABOUT THIS</button><button class="hud-btn" data-k="camoff">TURN OFF</button></div></div>
    <section class="hud-term" id="hudterm" hidden aria-label="Terminal"></section>
  </div>`);
  document.body.appendChild(root);
  core = new Core($('#hudcore'), { reduceMotion: reduceMotion() });
  for (const id of ['micbars', 'outbars']) $('#' + id).innerHTML = '<i></i>'.repeat(24);
  root.addEventListener('click', onClick);
  $('#hudcam').onchange = () => { if (camStream) camera(true); };
  buildTerm();
  tapVoice(true);
  refreshAll();
  if (wall) drawWall(wall, false);
  offChange = onChange((what) => {
    if (!root) return;
    if (what === 'chat') { refreshLine(); termSync(); }
    if (what === 'bots') refreshCards();
    if (what === 'prefs') { refreshMeta(); refreshMute(); if (document.documentElement.dataset.theme !== 'dark') document.documentElement.dataset.theme = 'dark'; }
    if (what === 'sync') refreshMeta();
  });
  ticker = setInterval(tick, 100);
  slowTicker = setInterval(() => { refreshMeta(); refreshCards(); }, 1000);
  setTimeout(() => $('#hudcore')?.focus(), 50);
}
let offChange = null, ticker = null, slowTicker = null;

export function exit() {
  if (!root) return;
  setPref('pcLook', 'classic');
  if (listenCtl) listenCtl.stop();
  camera(false); tapVoice(false);
  clearInterval(ticker); clearInterval(slowTicker); if (offChange) offChange();
  core && core.destroy(); core = null;
  root.remove(); root = null;
  document.body.classList.remove('hud-on');
  if (prevTheme) document.documentElement.dataset.theme = prevTheme;
  deps.restoreTheme && deps.restoreTheme();
  deps.afterExit && deps.afterExit();
}
export const toggle = () => (root ? exit() : enter());

// ------------------------------------------------------------------ keys and clicks

function typing(e) { const t = e.target; return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable); }
function onKey(e) {
  if (e.ctrlKey && e.altKey && (e.key === 'h' || e.key === 'H')) { e.preventDefault(); toggle(); return; }
  if (!root) return;
  if (e.ctrlKey && (e.key === '`' || e.code === 'Backquote')) { e.preventDefault(); term(!termOpen); return; }
  if (e.key === 'F11') { e.preventDefault(); host.fullscreen(!(window.innerHeight >= screen.height - 2)); return; }
  if (document.querySelector('.overlay')) return;   // a sheet is open: its keys
  if (e.key === 'Escape') {
    if (!$('#hudbox').hidden) { e.preventDefault(); closeBox(); return; }
    if (termOpen) { e.preventDefault(); term(false); return; }
    if (listenCtl) { e.preventDefault(); listenCtl.cancelled = true; listenCtl.stop(); return; }
    if (brain.convo.busy) { e.preventDefault(); brain.stop(); return; }
    if (voice.state.speaking) { e.preventDefault(); voice.hush(); return; }
    if (wall && !$('#hudwall').hidden) { e.preventDefault(); clearWall(); return; }
  }
  if (e.code === 'Space' && !typing(e) && !e.repeat && !(e.target && e.target.tagName === 'BUTTON' && e.target.id !== 'hudcore')) { e.preventDefault(); listen(); }
}
function onClick(e) {
  const b = e.target.closest('[data-k]'); if (!b || !root.contains(b)) { if (e.target.closest('#hudcore')) listen(); return; }
  const k = b.dataset.k;
  if (k === 'talk') listen();
  else if (k === 'text') term(!termOpen);
  else if (k === 'clear') clearWall();
  else if (k === 'history') historyBox();
  else if (k === 'screens') screensBox();
  else if (k === 'camera') camera(!camStream);
  else if (k === 'camoff') camera(false);
  else if (k === 'look') lookNow();
  else if (k === 'classic') exit();
  else if (k === 'mute') toggleMute();
  else if (k === 'allow' || k === 'deny') { const id = b.closest('[data-job]')?.dataset.job; if (id) bots.approve(id, k === 'allow'); }
  else if (k === 'answer') { const card = b.closest('[data-job]'); const inp = card && card.querySelector('input'); const t = inp && inp.value.trim(); if (t) { bots.answer(card.dataset.job, t); inp.value = ''; } }
  else if (k === 'boxclose') closeBox();
  else if (k === 'wallclose') clearWall();
}

// ------------------------------------------------------------------ the live parts

function refreshAll() { refreshMeta(); refreshMute(); refreshLine(); refreshCards(); }
function refreshMeta() {
  if (!root) return;
  const p = brain.active();
  const now = new Date();
  const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const day = now.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }).toUpperCase();
  const parts = [`${day} · ${time}`, p ? `${p.name} · ${brain.modelOf(p)}`.toUpperCase() : 'NO BRAIN: ADD A KEY IN CLASSIC → ME'];
  const bs = botSummary(bots.jobs, Date.now()); if (bs) parts.push(bs.toUpperCase());
  parts.push(sync.code() ? 'PHONE LINKED' : 'PHONE NOT LINKED');
  $('#hudmeta').textContent = parts.join('  ·  ');
  $('#hudlink').textContent = navigator.onLine ? 'ONLINE' : 'OFFLINE';
  $('#hudlink').className = navigator.onLine ? '' : 'hud-warn';
}
/** MUTE: Atlas stops talking (replies still appear); the classic window's Speak Replies decides the rest. */
function refreshMute() { if (root) { const m = pref('voiceMuted', false); const b = $('#hudmute'); b.textContent = m ? 'MUTED' : 'MUTE'; b.setAttribute('aria-pressed', String(m)); b.classList.toggle('hud-muted', m); b.title = m ? 'Atlas is silent: replies only appear. Click to hear it again.' : 'Silence Atlas (replies still appear)'; } }
function toggleMute() { const m = !pref('voiceMuted', false); setPref('voiceMuted', m); if (m) voice.hush(); refreshMute(); return m; }
function lastAtlas() { for (let i = db.chat.length - 1; i >= 0; i--) if (!db.chat[i].user) return db.chat[i]; return null; }
function refreshLine() {
  if (!root) return;
  const m = lastAtlas();
  const line = $('#hudline');
  const text = m && m.text ? H.spokenLine(m.text, 220) : '';
  if (line.dataset.v !== text) { line.dataset.v = text; line.textContent = text; line.classList.remove('in'); void line.offsetWidth; line.classList.add('in'); }
}
function stateNow() {
  if (listenCtl) return 'listening';
  if (voice.state.speaking) return 'speaking';
  if (brain.convo.busy) return 'thinking';
  if (bots.jobs.some((j) => !j.parent && j.state === 'running')) return 'working';
  return 'idle';
}
const STATE_WORDS = { idle: 'STANDING BY', listening: 'LISTENING', thinking: 'THINKING', speaking: 'SPEAKING', working: 'BOTS WORKING' };
function tick() {
  if (!root) return;
  const st = stateNow();
  core.setState(st);
  let words = STATE_WORDS[st];
  if (st === 'thinking' && brain.convo.status) words += ' · ' + String(brain.convo.status).toUpperCase().slice(0, 60);
  if (st === 'listening' && heard) words = 'HEARD';
  $('#hudstate').textContent = words;
  // the voice's level: measured when the audio can be tapped, otherwise a speech-like envelope while it talks
  if (voice.state.speaking) {
    const measured = speakTap && speakTap.level();
    outLevel = measured != null ? measured : 0.18 + 0.14 * Math.abs(Math.sin(performance.now() / 95)) * Math.abs(Math.sin(performance.now() / 410));
  } else outLevel *= 0.6;
  if (!listenCtl) micLevel *= 0.6;
  core.setLevel(listenCtl ? Math.min(1, micLevel * 4) : voice.state.speaking ? Math.min(1, outLevel * 2.5) : 0);
  meter('micbars', 'micdb', micLevel, listenCtl ? null : 'STANDBY');
  meter('outbars', 'outdb', outLevel);
}
function meter(barsId, dbId, level, word) {
  const lit = H.meterLit(level, 24);
  const bars = $('#' + barsId).children;
  for (let i = 0; i < bars.length; i++) bars[i].className = i < lit ? (i > 20 ? 'hot' : 'on') : '';
  $('#' + dbId).textContent = word || H.dbOf(level);
}

// ---- the voice's level: the spoken replies play through <audio>; while the HUD is on, a quiet tap measures them
function tapVoice(on) {
  const proto = HTMLMediaElement.prototype;
  if (!on) { if (speakTap) { proto.play = speakTap.orig; try { speakTap.ctx.close(); } catch {} speakTap = null; } return; }
  if (speakTap) return;
  const orig = proto.play; let ctx = null, an = null; const tapped = new WeakSet(); const buf = new Float32Array(1024);
  speakTap = { orig, get ctx() { return ctx; }, level: () => { if (!an || !current || current.paused) return null; an.getFloatTimeDomainData(buf); let s = 0; for (const v of buf) s += v * v; return Math.sqrt(s / buf.length); } };
  let current = null;
  proto.play = function play(...a) {
    try {
      if (!(this instanceof HTMLVideoElement) && !tapped.has(this) && /^(blob|data):/.test(this.currentSrc || this.src || '')) {
        if (!ctx) { ctx = new AudioContext(); an = ctx.createAnalyser(); an.fftSize = 1024; an.connect(ctx.destination); }
        if (ctx.state === 'running') { const src = ctx.createMediaElementSource(this); src.connect(an); tapped.add(this); }
        else ctx.resume().catch(() => {});
      }
      if (tapped.has(this)) current = this;
    } catch {}
    return orig.apply(this, a);
  };
}

// ------------------------------------------------------------------ listening

/** Listen once (or stop listening if already). Replies spoken back, and the follow-up handled, as in the classic window. */
export async function listen(followUp = false) {
  if (!root) return deps.classicListen(followUp);
  if (listenCtl) { listenCtl.stop(); return; }
  voice.hush(); host.show();
  heard = ''; $('#hudheard').textContent = followUp ? 'GO ON…' : 'LISTENING…';
  let ctl;
  try {
    ctl = voice.listen({
      onLevel: (l) => { micLevel = l; },
      onState: () => { $('#hudheard').textContent = 'ONE MOMENT…'; },
      onPause: (p) => { if (!heard) $('#hudheard').textContent = p ? 'STILL LISTENING…' : 'LISTENING…'; },
    });
  } catch (e) { deps.toast(e.message || String(e), 4000); return; }
  listenCtl = ctl; host.orbState({ energy: 1, listening: true });
  try {
    const words = await ctl.done;
    heard = words || '';
    $('#hudheard').textContent = heard ? '“' + heard + '”' : '';
    listenCtl = null; host.orbState({ energy: 0 });
    if (ctl.cancelled) { $('#hudheard').textContent = ''; return; }
    if (heard && followUp && endsTalk(heard)) { $('#hudheard').textContent = ''; return; }
    if (heard) { termEcho('you', heard, true); await deps.submit(true, heard); return; }
    if (!followUp) { $('#hudheard').textContent = "I DIDN'T CATCH THAT."; setTimeout(() => { if (!listenCtl && root) $('#hudheard').textContent = ''; }, 1600); }
  } catch (e) { listenCtl = null; host.orbState({ energy: 0 }); $('#hudheard').textContent = ''; deps.toast(e.message || String(e), 4000); }
}

// ------------------------------------------------------------------ cards: what Atlas and its bots are doing

function refreshCards() {
  if (!root) return;
  const box = $('#hudcards'); const now = Date.now();
  const cards = H.cardsFor(bots.jobs, now).map((j) => H.cardFor(j, now, bots.helpers(j.id).filter((k) => !BOT_FINAL.has(k.state)).length));
  // the chat's own work shows as a card too while it's busy
  if (brain.convo.busy) cards.unshift({ id: 'chat', label: 'ATLAS // ACTIVE', title: (H.spokenLine((db.chat.filter((m) => m.user).pop() || {}).text || '', 60) || 'YOUR REQUEST').toUpperCase(), step: brain.convo.status || 'Thinking', lit: (Math.floor(now / 180) % H.SEGMENTS), frac: 0, counts: [`${(lastAtlas() || { steps: [] }).steps.length} STEPS`], time: H.elapsed(now - ((lastAtlas() || {}).at || now)), chat: true });
  const keep = new Set(cards.map((c) => c.id));
  for (const el of [...box.children]) if (!keep.has(el.dataset.job)) { el.classList.add('out'); setTimeout(() => el.remove(), 260); }
  cards.forEach((c, i) => {
    let el = box.querySelector(`[data-job="${CSS.escape(c.id)}"]`);
    const sig = JSON.stringify([c.label, c.title, c.step, c.lit, c.counts, c.time, !!c.approval, c.question]);
    if (el && el.dataset.sig === sig) return;
    const html = cardHtml(c);
    if (!el) { el = h(html); el.classList.add('in'); box.appendChild(el); }
    else { const keepInput = el.querySelector('input')?.value; el.innerHTML = h(html).innerHTML; el.className = h(html).className; if (keepInput) el.querySelector('input').value = keepInput; }
    el.dataset.sig = sig; el.style.order = i;
  });
}
function cardHtml(c) {
  const segs = Array.from({ length: H.SEGMENTS }, (_, i) => `<i class="${c.chat ? (i === c.lit ? 'on' : '') : i < c.lit ? 'on' : ''}"></i>`).join('');
  return `<article class="hud-card ${c.waiting ? 'wait' : ''} ${c.state === 'failed' ? 'bad' : ''} ${c.state === 'completed' ? 'done' : ''}" data-job="${esc(c.id)}">
    <div class="hud-card-top"><span>${esc(c.label)}</span><span>${esc(c.time)}</span></div>
    <h3>${esc(c.title)}</h3>
    <div class="hud-card-step">${esc(c.step)}</div>
    <div class="hud-segs" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round((c.frac || 0) * 100)}">${segs}</div>
    <div class="hud-card-foot">${c.counts.map((x) => `<span>${esc(x)}</span>`).join('')}</div>
    ${c.approval ? `<div class="hud-card-acts"><button class="hud-btn" data-k="deny">DENY</button><button class="hud-btn hud-alert" data-k="allow">ALLOW</button></div>` : ''}
    ${c.question ? `<div class="hud-card-ask"><div>${esc(c.question)}</div><div class="hud-card-acts"><input class="hud-input" aria-label="Your answer" placeholder="Your answer"><button class="hud-btn hud-alert" data-k="answer">SEND</button></div></div>` : ''}
  </article>`;
}

// ------------------------------------------------------------------ the results wall

async function showWall(w) {
  if (!root) return { count: 0, failed: ['the HUD is off'] };
  const failed = [];
  // pictures come through the app (the page itself may not load other sites); one that doesn't load is left out
  await Promise.all(w.panels.map(async (p) => {
    if (p.type === 'image') { p.blob = await picture(p.url); if (!p.blob) { p.skip = true; failed.push(`the picture at ${H.hostOf(p.url)} didn't load`); } }
    if (p.type === 'video' && p.youtube) p.blob = await picture(`https://i.ytimg.com/vi/${p.youtube}/hqdefault.jpg`);
  }));
  const shown = { ...w, panels: w.panels.filter((p) => !p.skip) };
  wall = shown; walls = H.pushWall(walls, { ...shown, panels: shown.panels.map(({ blob, ...p }) => p) }); saveWalls();
  drawWall(shown, true);
  return { count: shown.panels.length, failed };
}
const pictureCache = new Map();
async function picture(url) {
  if (pictureCache.has(url)) return pictureCache.get(url);
  try {
    const r = await host.fetch(url, { binary: true, timeout: 15000 });
    if (!r || !r.ok || !r.base64 || !/^image\//.test(r.type || '') || r.base64.length > 11_000_000) return null;
    const bytes = Uint8Array.from(atob(r.base64), (c) => c.charCodeAt(0));
    const u = URL.createObjectURL(new Blob([bytes], { type: r.type }));
    pictureCache.set(url, u); return u;
  } catch { return null; }
}
function drawWall(w, animate) {
  const el = $('#hudwall');
  el.hidden = false; root.classList.add('docked');
  el.innerHTML = `<div class="hud-wall-head"><span class="hud-lbl">RESULTS //</span> <span class="hud-wall-title">${esc(w.title.toUpperCase())}</span>
    <span class="hud-dim mono">${w.panels.length} PANELS · ${new Date(w.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
    <button class="hud-btn" data-k="wallclose" aria-label="Clear the results wall">CLOSE</button></div><div class="hud-grid"></div>`;
  const grid = el.querySelector('.hud-grid');
  const places = H.layout(w.panels);
  w.panels.forEach((p, i) => {
    const s = places[i];
    const card = h(`<article class="hud-panel t-${p.type}" style="grid-column: ${s.col} / span ${s.cols}; grid-row: ${s.row} / span ${s.rows}"></article>`);
    card.innerHTML = panelHtml(p, i);
    if (animate && !reduceMotion()) { card.classList.add('enter'); card.style.animationDelay = `${120 + i * 110}ms`; }
    grid.appendChild(card);
    card.querySelectorAll('[data-open]').forEach((a) => (a.onclick = (e) => { e.preventDefault(); host.openExternal(a.dataset.open); }));
  });
}
function srcFoot(p, i) {
  const s = p.source || (p.type === 'link' ? p.url : null);
  return `<div class="hud-panel-foot"><span>${String(i + 1).padStart(2, '0')} // ${esc(p.type.toUpperCase())}</span>${s ? `<a href="#" data-open="${esc(s)}">${esc(H.hostOf(s))} ↗</a>` : '<span>ATLAS</span>'}</div>`;
}
function panelHtml(p, i) {
  const head = `<div class="hud-panel-head">${p.kicker ? `<span class="hud-kick">${esc(p.kicker.toUpperCase())}</span>` : ''}${p.title ? `<h4>${esc(p.title)}</h4>` : ''}</div>`;
  if (p.type === 'text') return `${head}<div class="hud-panel-body md">${renderMarkdown(p.body)}</div>${srcFoot(p, i)}`;
  if (p.type === 'stat') return `${head}<div class="hud-stat">${esc(p.value)}</div><div class="hud-stat-label">${esc(p.label)}</div>${p.body ? `<p class="hud-panel-body">${esc(p.body)}</p>` : ''}${srcFoot(p, i)}`;
  if (p.type === 'image') return `<div class="hud-media"><img src="${esc(p.blob)}" alt="${esc(p.caption || p.title || 'Picture found by Atlas')}"></div>${p.title || p.caption ? `<div class="hud-cap">${esc(p.title || p.caption)}</div>` : ''}${srcFoot({ ...p, source: p.source || p.url }, i)}`;
  if (p.type === 'video') return `<a href="#" class="hud-media hud-video" data-open="${esc(p.url)}" aria-label="Play ${esc(p.title || 'the video')} in your browser">${p.blob ? `<img src="${esc(p.blob)}" alt="">` : '<div class="hud-novid"></div>'}<span class="hud-play" aria-hidden="true"></span></a>
    <div class="hud-cap">${esc(p.title || p.caption || H.hostOf(p.url))}</div>${srcFoot({ ...p, source: p.source || p.url }, i)}`;
  if (p.type === 'link') return `${head}<a href="#" class="hud-link" data-open="${esc(p.url)}">${esc(p.host)} ↗</a>${p.body ? `<p class="hud-panel-body">${esc(p.body)}</p>` : ''}${srcFoot(p, i)}`;
  if (p.type === 'chart') return `${head}${chartSvg(p)}${p.body ? `<p class="hud-panel-body small">${esc(p.body)}</p>` : ''}${srcFoot(p, i)}`;
  return `${head}<div class="hud-table-wrap"><table class="hud-table"><thead><tr>${p.columns.map((c) => `<th scope="col">${esc(c)}</th>`).join('')}</tr></thead>
    <tbody>${p.rows.map((r) => `<tr>${p.columns.map((_, k) => `<td>${esc(r[k] ?? '')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>${srcFoot(p, i)}`;
}
function chartSvg(p) {
  const W = 320, Hh = 150, pad = 18, n = p.series.length;
  const max = Math.max(...p.series.map((s) => s.value), 0), min = Math.min(...p.series.map((s) => s.value), 0);
  const span = max - min || 1, bw = (W - pad * 2) / n;
  const y0 = Hh - pad - ((0 - min) / span) * (Hh - pad * 2);
  const fmt = (v) => (Math.abs(v) >= 1e9 ? (v / 1e9).toFixed(1) + 'B' : Math.abs(v) >= 1e6 ? (v / 1e6).toFixed(1) + 'M' : Math.abs(v) >= 1e4 ? (v / 1e3).toFixed(0) + 'K' : String(Math.round(v * 100) / 100));
  const bars = p.series.map((s, i) => {
    const y = Hh - pad - ((s.value - min) / span) * (Hh - pad * 2);
    const top = Math.min(y, y0), hgt = Math.max(1, Math.abs(y0 - y));
    return `<rect x="${(pad + i * bw + bw * 0.18).toFixed(1)}" y="${top.toFixed(1)}" width="${(bw * 0.64).toFixed(1)}" height="${hgt.toFixed(1)}" class="${i === 0 ? 'lead' : ''}"></rect>
      <text x="${(pad + i * bw + bw / 2).toFixed(1)}" y="${(top - 4).toFixed(1)}" text-anchor="middle" class="v">${esc(fmt(s.value))}</text>
      <text x="${(pad + i * bw + bw / 2).toFixed(1)}" y="${Hh - 3}" text-anchor="middle" class="l">${esc(s.label.slice(0, 10))}</text>`;
  }).join('');
  const desc = p.series.map((s) => `${s.label}: ${s.value}${p.unit ? ' ' + p.unit : ''}`).join(', ');
  return `<svg class="hud-chart" viewBox="0 0 ${W} ${Hh}" role="img" aria-label="${esc((p.title || 'Chart') + ': ' + desc)}"><line x1="${pad}" x2="${W - pad}" y1="${y0}" y2="${y0}" class="axis"></line>${bars}</svg>${p.unit ? `<div class="hud-unit">${esc(p.unit.toUpperCase())}</div>` : ''}`;
}
function clearWall() { if (!root) return; wall = null; const el = $('#hudwall'); el.hidden = true; el.innerHTML = ''; root.classList.remove('docked'); }
export function openWall(i = 0) { const w = walls[i]; if (!w || !root) return false; showWall({ ...w, panels: w.panels.map((p) => ({ ...p })) }); return true; }
async function loadWalls() { try { const t = await host.read('hudwalls.json'); const v = t ? JSON.parse(t) : []; walls = Array.isArray(v) ? v : []; } catch { walls = []; } }
function saveWalls() { host.write('hudwalls.json', JSON.stringify(walls)).catch(() => {}); }

// ------------------------------------------------------------------ boxes: history, screens

function box(title, inner) {
  const b = $('#hudbox'); b.hidden = false;
  b.innerHTML = `<div class="hud-box" role="dialog" aria-label="${esc(title)}"><div class="hud-wall-head"><span class="hud-lbl">${esc(title)}</span><span class="hud-grow"></span><button class="hud-btn" data-k="boxclose">CLOSE</button></div>${inner}</div>`;
  setTimeout(() => b.querySelector('button')?.focus(), 30);
  return b;
}
function closeBox() { const b = $('#hudbox'); b.hidden = true; b.innerHTML = ''; }
function historyBox() {
  const b = box('HISTORY // RESULT WALLS', walls.length ? `<div class="hud-list">${walls.map((w, i) => `<button class="hud-row" data-wall="${i}"><span>${esc(w.title.toUpperCase())}</span><span class="hud-dim">${w.panels.length} PANELS · ${esc(new Date(w.at).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' }))}</span></button>`).join('')}</div>` : '<p class="hud-empty">No result walls yet. Ask Atlas to research something.</p>');
  b.querySelectorAll('[data-wall]').forEach((r) => (r.onclick = () => { closeBox(); openWall(Number(r.dataset.wall)); }));
}
async function screensBox() {
  const list = bots.jobs.filter((j) => !BOT_FINAL.has(j.state));
  const infos = await Promise.all(list.map(async (j) => ({ j, info: host.botBrowser ? await host.botBrowser.info(j.id).catch(() => ({ open: false })) : { open: false } })));
  const open = infos.filter((x) => x.info && x.info.open);
  const b = box('SCREENS // WHAT THE BOTS SEE', open.length ? `<div class="hud-list">${open.map(({ j, info }) => `<button class="hud-row" data-show="${esc(j.id)}"><span>${esc(j.goal.slice(0, 70).toUpperCase())}</span><span class="hud-dim">${esc(info.url ? H.hostOf(info.url) : 'BROWSER')} · WATCH ↗</span></button>`).join('')}</div>`
    : `<p class="hud-empty">${list.length ? 'No bot has a browser open right now.' : 'No bots are working right now.'}</p>`);
  b.querySelectorAll('[data-show]').forEach((r) => (r.onclick = () => { const j = bots.job(r.dataset.show); host.botBrowser.show(r.dataset.show, j ? j.goal : ''); }));
}

// ------------------------------------------------------------------ camera: only while you turn it on

async function camera(on) {
  const view = root && $('#hudcamview');
  if (camStream) { camStream.getTracks().forEach((t) => t.stop()); camStream = null; }
  if (!on || !view) { if (view) view.hidden = true; root && root.querySelector('[data-k=camera]')?.classList.remove('lit'); return; }
  try {
    const id = $('#hudcam').value;
    camStream = await navigator.mediaDevices.getUserMedia({ video: id ? { deviceId: { exact: id } } : { width: { ideal: 1600 } }, audio: false });
  } catch { deps.toast("Couldn't open a camera on this PC.", 3500); return; }
  view.hidden = false; view.querySelector('video').srcObject = camStream;
  root.querySelector('[data-k=camera]').classList.add('lit');
  // the camera list (names show once a camera has been allowed)
  try {
    const sel = $('#hudcam'); const cur = sel.value;
    const cams = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'videoinput');
    sel.innerHTML = '<option value="">DEFAULT CAMERA</option>' + cams.map((c, i) => `<option value="${esc(c.deviceId)}">${esc((c.label || 'Camera ' + (i + 1)).toUpperCase().slice(0, 30))}</option>`).join('');
    sel.value = cur;
  } catch {}
}
/** One still from the camera goes with the next question (spoken, or "What am I looking at?"). */
async function lookNow() {
  const v = root && $('#hudcamview video'); if (!v || !camStream) return;
  const k = Math.min(1, 1600 / Math.max(v.videoWidth || 1, v.videoHeight || 1));
  const c = document.createElement('canvas'); c.width = Math.round(v.videoWidth * k) || 1; c.height = Math.round(v.videoHeight * k) || 1;
  c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
  const data = c.toDataURL('image/jpeg', 0.85);
  const att = [{ kind: 'image', mime: 'image/jpeg', name: 'Camera.jpg', base64: data.split(',')[1], thumb: data, size: Math.floor(data.length * 0.75) }];
  termEcho('you', '[camera] What am I looking at?', true);
  await deps.submit(true, 'What am I looking at?', att);
}

// ------------------------------------------------------------------ the terminal

let termShown = 0;   // how many chat messages the terminal has printed
function buildTerm() {
  const t = $('#hudterm');
  t.innerHTML = `<div class="hud-term-head"><span class="hud-lbl">TERMINAL // ATLAS</span><span class="hud-dim">/help for commands · Esc to close</span><span class="hud-grow"></span>
    <button class="hud-btn" id="termsize" aria-label="Full or half screen">FULL</button><button class="hud-btn" data-k="text">CLOSE</button></div>
    <div class="hud-term-out" id="termout" role="log" aria-live="polite"></div>
    <form class="hud-term-in" id="termform"><label for="termin" class="hud-prompt">atlas ▸</label><input id="termin" autocomplete="off" spellcheck="false" aria-label="Type to Atlas"></form>`;
  t.querySelector('#termsize').onclick = () => { termFull = !termFull; t.classList.toggle('full', termFull); t.querySelector('#termsize').textContent = termFull ? 'HALF' : 'FULL'; };
  t.querySelector('#termform').onsubmit = (e) => { e.preventDefault(); const inp = $('#termin'); const v = inp.value; inp.value = ''; termInput(v); };
  const hist = []; let hi = 0;
  t.querySelector('#termin').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { const v = e.target.value.trim(); if (v) { hist.push(v); hi = hist.length; } }
    if (e.key === 'ArrowUp' && hist.length) { e.preventDefault(); hi = Math.max(0, hi - 1); e.target.value = hist[hi]; }
    if (e.key === 'ArrowDown' && hist.length) { e.preventDefault(); hi = Math.min(hist.length, hi + 1); e.target.value = hist[hi] || ''; }
  });
  termPrint('sys', `ATLAS TERMINAL — same conversation as your voice, this window and your phone. Type to talk, /help for commands.`);
  const recent = db.chat.slice(-8);
  for (const m of recent) termMsg(m);
  termShown = db.chat.length;
}
function term(open) {
  if (!root) return;
  termOpen = !!open; const t = $('#hudterm'); t.hidden = !termOpen;
  root.querySelector('[data-k=text]')?.classList.toggle('lit', termOpen);
  if (termOpen) { setTimeout(() => $('#termin')?.focus(), 30); const o = $('#termout'); o.scrollTop = o.scrollHeight; }
  else $('#hudcore')?.focus();
}
function termPrint(kind, text, html) {
  const o = $('#termout'); if (!o) return;
  const line = document.createElement('div'); line.className = 'tl tl-' + kind;
  if (html) line.innerHTML = html; else line.textContent = text;
  o.appendChild(line);
  while (o.children.length > 600) o.firstChild.remove();
  o.scrollTop = o.scrollHeight;
}
function termMsg(m) {
  if (m.user) termPrint('you', '', `<span class="tp">you ▸</span> ${esc(m.text)}`);
  else if (m.text) { termPrint('atlas', '', `<span class="tp">atlas ▸</span><div class="md">${renderMarkdown(m.text)}</div>`); for (const s of (m.steps || []).slice(-6)) termPrint('step', '  · ' + s); }
}
function termEcho() {}   // what was said by voice prints when it reaches the chat (termSync), so nothing doubles
function termSync() {
  if (!$('#termout')) return;
  if (db.chat.length < termShown) termShown = 0;   // a new chat
  // print messages that are complete; the one still being written waits (its status shows as it changes)
  while (termShown < db.chat.length) {
    const m = db.chat[termShown];
    if (!m.user && m.pending && brain.convo.busy) break;
    termMsg(m); termShown++;
  }
  const st = brain.convo.busy && brain.convo.status ? String(brain.convo.status) : '';
  if (st && st !== lastStatus) termPrint('step', '  … ' + st);
  lastStatus = st;
}
async function termInput(raw) {
  const text = String(raw || '').trim(); if (!text) return;
  const c = H.parseCommand(text);
  if (!c) { await deps.submit(false, text); return; }
  termPrint('cmd', '› ' + text);
  if (!c.cmd) return termPrint('err', `Unknown command /${c.word}. Try /help.`);
  const run = COMMANDS_RUN[c.cmd]; await run(c.arg);
}
const COMMANDS_RUN = {
  help: () => termPrint('sys', H.helpText()),
  status: () => {
    const p = brain.active();
    termPrint('sys', [
      `brain    ${p ? `${p.name} · ${brain.modelOf(p)}` : 'none (add a key in classic → Me)'}${brain.convo.busy ? ' · busy: ' + (brain.convo.status || 'thinking') : ''}`,
      `voice    ${voice.engine() ? voice.engineName(voice.engine()) : 'Windows'} · ${pref('voiceMuted', false) ? 'MUTED' : pref('speakReplies', false) ? 'every reply spoken' : 'spoken when you speak'}`,
      `bots     ${botSummary(bots.jobs, Date.now()) || 'nothing running'}`,
      `waiting  ${aware.pending().length} for you`,
      `phone    ${sync.code() ? (sync.status.last || 'linked') : 'not linked'}`,
      `screen   HUD${wall ? ' · wall: ' + wall.title : ''}`,
    ].join('\n'));
  },
  bots: () => {
    const list = bots.visible().slice(0, 15);
    if (!list.length) return termPrint('sys', 'No bot jobs.');
    termPrint('sys', list.map((j) => { const c = H.cardFor(j, Date.now()); return `${j.id.slice(0, 6)}  ${c.label.padEnd(26)} ${c.time.padStart(8)}  ${j.goal.slice(0, 60)}`; }).join('\n'));
  },
  approve: (arg) => decide(arg, true),
  deny: (arg) => decide(arg, false),
  stop: () => { if (brain.convo.busy) { brain.stop(); termPrint('sys', 'Stopped.'); } else termPrint('sys', 'Atlas isn\'t answering anything.'); voice.hush(); },
  new: async () => { if (await deps.newChat()) { $('#termout').innerHTML = ''; termShown = 0; termPrint('sys', 'New chat.'); } },
  clear: () => { $('#termout').innerHTML = ''; },
  history: (arg) => {
    if (arg) { const n = Number(arg) - 1; return openWall(n) ? termPrint('sys', `Opened wall ${arg}.`) : termPrint('err', `There's no wall ${arg}.`); }
    termPrint('sys', walls.length ? walls.map((w, i) => `${String(i + 1).padStart(2)}  ${w.title}  (${w.panels.length} panels, ${new Date(w.at).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })})`).join('\n') : 'No result walls yet.');
  },
  wall: () => (openWall(0) ? termPrint('sys', 'Opened the last wall.') : termPrint('err', 'No result walls yet.')),
  hud: () => term(false),
  classic: () => exit(),
  mute: () => termPrint('sys', toggleMute() ? 'Muted: replies only appear.' : 'Unmuted: spoken questions get spoken answers again.'),
};
/** /approve and /deny: the user's own command, so it counts like tapping Allow or Deny. Bots' asks are answered here; others are named. */
function decide(arg, yes) {
  const list = aware.pending().filter((p) => !p.question);
  const p = aware.pick(list, arg);
  if (!p) return termPrint('err', list.length ? `More than one thing is waiting; say which:\n${list.map((x) => `  ${x.ref}  ${x.what} (${x.from})`).join('\n')}` : 'Nothing is waiting for you.');
  const [kind, id] = p.ref.includes(':') ? p.ref.split(':') : [p.ref, ''];
  if (kind === 'bot') { bots.approve(id, yes); return termPrint('sys', `${yes ? 'Allowed' : 'Denied'}: ${p.what}`); }
  if (kind === 'phone' && aware.src.link) {
    Promise.resolve(aware.src.link.answerTask(id, yes)).then((ok) => termPrint('sys', ok ? `${yes ? 'Allowed' : 'Denied'} on your phone: ${p.what}` : 'It was already answered on the phone.')).catch((e) => termPrint('err', e.message));
    return;
  }
  // something Atlas asked in the chat: answer it in the chat, in your own words, so it goes through the usual check
  // what's waiting goes in quotes so its own words ("Cancel the order", "reply no to Bob") aren't read as the user's yes or no
  const what = '“' + String(p.what).replace(/[“”"\n]/g, ' ') + '”';
  deps.submit(false, yes ? `Yes, go ahead: ${what}` : `No, don't: ${what}`);
}
