// Atlas Bot v2 on the PC: the Bots page (standing bots, missions, group chats), a bot's page (instructions, access,
// budget, triggers, memory, work log, costs, actions), creating a bot (from scratch or a template), the agent tree on a
// mission's page, and group chats. Everything goes through crewface.js (the engine's API) and threads.js.
// app.js passes its small helpers in [k]: h, esc, sheet, toast, render, go(view), md(text) → html.
import * as crew from './crewface.js';
import * as threads from './threads.js';
import { CAP_IDS, CAP_LABELS, DEFAULT_BUDGET, HOMES, planProgress, defaultAccess } from './crew.js';
import { LABELS as BOT_LABELS } from './bots.js';
import { USER, ATLAS, mentionsIn } from './threadcore.js';

let section = 'bots';          // the Bots page's tab: bots | missions | chats
const drafts = {};             // unsent text per box (survives re-renders)
const WAITING = new Set(['awaiting_approval', 'awaiting_answer', 'needs_review']);
const RUNNING = new Set(['running', 'queued', 'waiting_retry', 'waiting_helpers']);

const when = (ms) => (ms ? new Date(ms).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
const ago = (ms) => { if (!ms) return ''; const s = Math.max(0, (Date.now() - ms) / 1000); return s < 60 ? 'just now' : s < 3600 ? `${Math.floor(s / 60)} min ago` : s < 86400 ? `${Math.floor(s / 3600)} h ago` : when(ms); };
const iconOf = (b) => (b && b.icon) || (b && b.name ? b.name.trim()[0].toUpperCase() : '•');
const toneOf = (s) => (s === 'completed' ? 'ok' : s === 'failed' ? 'bad' : WAITING.has(s) ? 'warn' : s === 'cancelled' || s === 'paused' ? '' : 'accent');
const draftBox = (k, key, el) => { el.value = drafts[key] || ''; el.oninput = () => (drafts[key] = el.value); };

/** Where a bot stands right now: {label, tone} from its missions and state. */
function statusOf(b) {
  if (b.state === 'paused') return { label: 'Paused', tone: '' };
  const ms = crew.missions(b.id);
  if (ms.some((m) => WAITING.has(m.state))) return { label: 'Waiting on you', tone: 'warn' };
  if (ms.some((m) => RUNNING.has(m.state))) return { label: 'Working', tone: 'accent' };
  const u = crew.usage(b.id); if (!u.ok) return { label: 'Budget used today', tone: 'warn' };
  return { label: 'Ready', tone: 'ok' };
}
/** The next thing that will start it on its own (a trigger, or a routine that names it). */
function nextTrigger(b) {
  const routines = ((crew.ext && crew.ext()) || null);
  let next = null;
  for (const t of b.triggers || []) if (t.enabled && t.config && t.config.nextAt && (!next || t.config.nextAt < next.at)) next = { at: t.config.nextAt, what: t.config.name || t.type };
  try {
    for (const r of (window.__atlasRoutines || []).filter((x) => x.enabled && x.specialist === b.id && x.nextAt)) if (!next || r.nextAt < next.at) next = { at: r.nextAt, what: r.name };
  } catch {}
  void routines;
  if (next) return `Next: ${next.what} · ${when(next.at)}`;
  const kinds = (b.triggers || []).filter((t) => t.enabled).map((t) => t.type);
  return kinds.length ? `Starts on: ${[...new Set(kinds)].join(', ')}` : 'Starts when you ask';
}
function meter(k, pct, tone) {
  return `<div class="meter${tone ? ' ' + tone : ''}"><i style="width:${Math.round(Math.max(0.02, pct) * 100)}%"></i></div>`;
}

// =================================================================== the Bots page

/** Draws the page. Returns true when it handled [view] (null = the page itself; "bot:<id>", "thread:<id>", "newbot", "newthread"). */
export function render(col, view, k) {
  if (view && view.startsWith('bot:')) { botPage(col, view.slice(4), k); return true; }
  if (view && view.startsWith('thread:')) { threadPage(col, view.slice(7), k); return true; }
  if (view === 'newbot') { newBotPage(col, k); return true; }
  if (view === 'newthread') { newThreadPage(col, k); return true; }
  if (view) return false;
  home(col, k);
  return true;
}

function home(col, k) {
  col.appendChild(k.h('<h1 class="title">Atlas Bot</h1>'));
  col.appendChild(k.h('<p class="sub">Your standing bots, the missions they’re on, and group chats with them. They keep working while Atlas sits in the tray; what needs you shows here first.</p>'));
  sendBox(col, k);
  const waiting = crew.missions().filter((m) => WAITING.has(m.state));
  if (waiting.length) {
    col.appendChild(k.h('<div class="eyebrow warn">NEEDS YOU</div>'));
    for (const m of waiting.slice(0, 6)) col.appendChild(missionCard(m, k));
  }
  const tabs = k.h(`<div class="seg crewtabs" role="tablist">${[['bots', 'Bots'], ['missions', 'Missions'], ['chats', 'Group Chats']].map(([id, t]) => `<button class="chip${section === id ? ' on' : ''}" data-s="${id}" role="tab" aria-selected="${section === id}">${t}</button>`).join('')}</div>`);
  tabs.querySelectorAll('[data-s]').forEach((b) => (b.onclick = () => { section = b.dataset.s; k.render(true); }));
  col.appendChild(tabs);
  if (section === 'missions') missionsList(col, k);
  else if (section === 'chats') chatsList(col, k);
  else botsGrid(col, k);
}

/** "Send a bot to…" with who should do it. */
function sendBox(col, k) {
  const list = crew.bots();
  const pick = drafts.__who && list.some((b) => b.id === drafts.__who) ? drafts.__who : 'atlas';
  const box = k.h(`<div class="card"><textarea class="field" id="botask" rows="2" placeholder="Send a bot to… (e.g. find the three best budget laptops for coding, with sources)"></textarea>
    <div class="row" style="margin-top:10px;gap:8px;flex-wrap:wrap"><span class="small muted">Who:</span><div class="chips" id="who">${list.map((b) => `<button class="chip${b.id === pick ? ' on' : ''}" data-id="${k.esc(b.id)}">${k.esc(iconOf(b))} ${k.esc(b.name)}</button>`).join('')}</div>
    <div class="grow"></div><label class="small muted" title="Plan and say what it would do; nothing risky actually happens"><input type="checkbox" id="dry"> Dry run</label><button class="btn primary" id="botgo">Send</button></div></div>`);
  const ta = box.querySelector('#botask'); draftBox(k, '__send', ta);
  box.querySelectorAll('#who [data-id]').forEach((b) => (b.onclick = () => { drafts.__who = b.dataset.id; k.render(true); }));
  const go = () => {
    const g = ta.value.trim(); if (!g) return;
    drafts.__send = '';
    crew.startMission(pick, g, { source: { type: 'user', at: Date.now() }, dryRun: box.querySelector('#dry').checked });
    k.toast(`${(crew.bot(pick) || {}).name || 'Atlas'} is on it.`); k.render(true);
  };
  box.querySelector('#botgo').onclick = go;
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go(); } };
  col.appendChild(box);
}

function botsGrid(col, k) {
  const grid = k.h('<div class="botgrid"></div>');
  for (const b of crew.bots()) {
    const st = statusOf(b); const u = crew.usage(b.id);
    const c = k.h(`<div class="card bot2" role="button" tabindex="0">
      <div class="row" style="gap:12px"><div class="boticon">${k.esc(iconOf(b))}</div><div class="grow" style="min-width:0"><div class="bname">${k.esc(b.name)}</div><div class="small muted clamp2">${k.esc(b.role || 'A bot of yours')}</div></div><span class="tag ${st.tone}">${k.esc(st.label)}</span></div>
      <div class="small muted" style="margin-top:14px;display:flex;justify-content:space-between"><span>Today</span><span>${u.calls}/${u.callsPerDay} calls · ${Math.round(u.ms / 60000)}/${u.minutesPerDay} min</span></div>
      ${meter(k, u.pct, u.pct >= 1 ? 'warn' : '')}
      <div class="small faint" style="margin-top:10px">${k.esc(nextTrigger(b))}</div></div>`);
    c.onclick = () => k.go('bot:' + b.id);
    grid.appendChild(c);
  }
  const add = k.h('<div class="card bot2 add" role="button" tabindex="0"><div class="boticon">＋</div><div class="bname">New Bot</div><div class="small muted">From scratch or a template</div></div>');
  add.onclick = () => k.go('newbot');
  grid.appendChild(add);
  col.appendChild(grid);
}

function missionCard(m, k) {
  const b = crew.bot(m.bot) || crew.ATLAS_BOT;
  const pp = planProgress(m.plan);
  const kids = (m.children || []).length;
  const line = [BOT_LABELS[m.state] || m.state, m.state === 'running' ? m.current : null, pp.total ? `plan ${pp.done}/${pp.total}` : null, kids ? `${kids} agent${kids > 1 ? 's' : ''}` : null,
    m.cost && m.cost.calls ? `${m.cost.calls} calls` : null, m.dryRun ? 'dry run' : null].filter(Boolean).join(' · ');
  const c = k.h(`<div class="card botcard"><div class="row" style="gap:10px"><div class="boticon sm">${k.esc(iconOf(b))}</div><div class="grow" style="min-width:0"><div class="goal">${k.esc(m.goal)}</div>
    <div class="small ${toneOf(m.state)}">${k.esc(line)}</div></div><div class="small faint">${k.esc(ago(m.updatedAt))}</div></div></div>`);
  c.onclick = () => k.go(m.id);
  return c;
}
function missionsList(col, k) {
  const ms = crew.missions();
  if (!ms.length) { col.appendChild(k.h('<div class="card"><div style="font:400 19px var(--serif)">No missions yet</div><p class="muted" style="margin:6px 0 0">Send a bot above, ask Atlas in the chat, or give a bot a trigger.</p></div>')); return; }
  for (const m of ms.slice(0, 60)) col.appendChild(missionCard(m, k));
}

function chatsList(col, k) {
  const add = k.h('<button class="btn primary">New Group Chat</button>');
  add.onclick = () => k.go('newthread');
  col.appendChild(k.h('<div class="row" style="margin-bottom:12px"><p class="muted grow" style="margin:0">You and several bots in one conversation. @mention a bot to give it the job; bots can hand work to each other here, and you see everything.</p></div>')).appendChild(add);
  const ts = threads.all();
  if (!ts.length) { col.appendChild(k.h('<div class="card"><div style="font:400 19px var(--serif)">No group chats yet</div><p class="muted" style="margin:6px 0 0">Start one with, say, your Research and Reel Poster bots for a launch week.</p></div>')); return; }
  const roster = crew.bots();
  for (const t of ts) {
    const last = t.messages[t.messages.length - 1];
    const who = t.participants.filter((p) => p !== USER).map((p) => (roster.find((b) => b.id === p) || {}).name).filter(Boolean).join(', ');
    const c = k.h(`<div class="card botcard"><div class="row"><div class="grow" style="min-width:0"><div class="goal">${k.esc(t.title)}</div><div class="small muted">${k.esc(who || 'Just you')}</div>
      ${last ? `<div class="small faint clamp2" style="margin-top:6px">${k.esc(nameOf(last.from, roster))}: ${k.esc(last.text.slice(0, 160))}</div>` : ''}</div><div class="small faint">${k.esc(ago(t.updatedAt))}</div></div></div>`);
    c.onclick = () => k.go('thread:' + t.id);
    col.appendChild(c);
  }
}
function nameOf(id, roster) { return id === USER ? 'You' : id === ATLAS ? 'Atlas' : ((roster || crew.bots()).find((b) => b.id === id) || {}).name || 'A bot'; }

// =================================================================== a bot's page

function botPage(col, id, k) {
  const b = crew.bot(id);
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-bottom:14px">‹ All Bots</button>'), { onclick: () => k.go(null) }));
  if (!b) { col.appendChild(k.h('<div class="card">This bot was removed.</div>')); return; }
  const st = statusOf(b);
  col.appendChild(k.h(`<div class="row" style="gap:16px;align-items:center"><div class="boticon lg">${k.esc(iconOf(b))}</div><div class="grow"><h1 class="title" style="margin:0">${k.esc(b.name)}</h1>
    <div class="muted">${k.esc(b.role || '')}</div></div><span class="tag ${st.tone}">${k.esc(st.label)}</span></div>`));
  // actions
  const keys = k.h('<div class="row" style="margin-top:16px;gap:8px;flex-wrap:wrap"></div>');
  const key = (label, f, primary) => keys.appendChild(Object.assign(k.h(`<button class="btn${primary ? ' primary' : ''}">${label}</button>`), { onclick: f }));
  key('Run Now', () => runSheet(b, k, false), true);
  key('Dry Run', () => runSheet(b, k, true));
  if (!b.builtIn) {
    if (b.state === 'paused') key('Resume', () => { crew.updateBot(b.id, { state: 'active' }); k.render(true); }); else key('Pause', () => { crew.updateBot(b.id, { state: 'paused' }); k.render(true); });
    key('Edit', () => k.go('newbot:' + b.id));
  }
  col.appendChild(keys);

  // today
  const u = crew.usage(b.id);
  const today = k.h(`<div class="card" style="margin-top:16px"><div class="row"><div class="grow"><div class="small muted" style="font-weight:600">TODAY</div>
    <div style="margin-top:6px">${u.calls} of ${u.callsPerDay} AI calls · ${Math.round(u.ms / 60000)} of ${u.minutesPerDay} minutes</div></div>${!u.ok ? '<button class="btn" id="more">Give It More Today</button>' : ''}</div>${meter(k, u.pct, u.pct >= 1 ? 'warn' : '')}
    <div class="small faint" style="margin-top:8px">Budgets are the only brakes: when today's is used, the bot stops and asks you. Plans have no fixed number of steps.</div></div>`);
  const more = today.querySelector('#more');
  if (more) more.onclick = async () => { if (await k.sheet({ title: 'Give It More Today?', body: `Another ${Math.round(u.callsPerDay / 2)} AI calls and ${Math.round(u.minutesPerDay / 2)} minutes, for today only.`, primary: 'Give More' })) { crew.giveMore(b.id, { calls: Math.round(u.callsPerDay / 2), minutes: Math.round(u.minutesPerDay / 2) }); k.render(true); } };
  col.appendChild(today);

  // missions
  const ms = crew.missions(b.id);
  col.appendChild(k.h('<div class="eyebrow">MISSIONS</div>'));
  if (!ms.length) col.appendChild(k.h('<div class="card muted">None yet. Run it now, or give it a trigger.</div>'));
  for (const m of ms.slice(0, 8)) col.appendChild(missionCard(m, k));

  // instructions and access
  if (b.instructions) { col.appendChild(k.h('<div class="eyebrow">HOW IT WORKS</div>')); col.appendChild(k.h(`<div class="card" style="white-space:pre-wrap;font:400 var(--t-read)/1.5 var(--serif)">${k.esc(b.instructions)}</div>`)); }
  col.appendChild(k.h('<div class="eyebrow">WHAT IT MAY DO</div>'));
  col.appendChild(accessCard(b, k));

  // triggers (the Triggers work adds its own sections; until then, what we know)
  col.appendChild(k.h('<div class="eyebrow">WHAT STARTS IT</div>'));
  const tcol = k.h('<div></div>'); col.appendChild(tcol);
  const sections = crew.triggerSectionsFor();
  if (sections.length) for (const f of sections) { try { f(tcol, b, k); } catch (e) { console.error(e); } }
  else {
    const ts = (b.triggers || []);
    tcol.appendChild(k.h(`<div class="card">${ts.length ? `<div class="list">${ts.map((t) => `<div class="item"><div class="grow"><div class="t">${k.esc(t.config.name || t.type)}</div><div class="m">${k.esc(t.type)}${t.enabled ? '' : ' · off'}</div></div></div>`).join('')}</div>` : '<div class="muted">You, Atlas, or a routine that names it. More triggers (new email, price moves, phone notifications, files) arrive with the next update.</div>'}</div>`));
  }

  // memory
  col.appendChild(k.h('<div class="eyebrow">WHAT IT REMEMBERS</div>'));
  col.appendChild(memoryCard(b, k));

  // work log
  if ((b.log || []).length) {
    col.appendChild(k.h('<div class="eyebrow">WORK LOG</div>'));
    const l = k.h('<div class="card list"></div>');
    for (const e of b.log.slice().reverse().slice(0, 30)) {
      const r = k.h(`<div class="item"><div class="grow"><div class="t">${k.esc(e.text)}</div><div class="m">${k.esc(when(e.at))}</div></div>${e.mission && crew.mission(e.mission) ? '<span class="tag">Open</span>' : ''}</div>`);
      if (e.mission && crew.mission(e.mission)) { r.style.cursor = 'pointer'; r.onclick = () => k.go(e.mission); }
      l.appendChild(r);
    }
    col.appendChild(l);
  }

  // actions (audit trail)
  const acts = crew.actions(b.id, 25);
  if (acts.length) {
    col.appendChild(k.h('<div class="eyebrow">WHAT IT DID LATELY</div>'));
    const l = k.h('<div class="card botsteps"></div>');
    for (const a of acts) l.appendChild(k.h(`<div><span class="faint small" style="width:110px;flex:none">${k.esc(ago(a.at))}</span><span>${a.dryRun ? '<span class="tag">dry run</span> ' : ''}${k.esc(a.what || a.tool || '')}</span></div>`));
    col.appendChild(l);
  }
  if (!b.builtIn) col.appendChild(Object.assign(k.h('<button class="btn danger" style="margin-top:22px">Delete This Bot</button>'), {
    onclick: async () => { if (await k.sheet({ title: `Delete ${b.name}?`, body: 'Its memory and work log go too. Missions it already finished stay in the list.', primary: 'Delete' })) { crew.removeBot(b.id); k.go(null); } },
  }));
}

async function runSheet(b, k, dry) {
  const html = `<textarea class="field" id="rg" rows="3" placeholder="What should ${k.esc(b.name)} do?"></textarea>${dry ? '<p class="small muted" style="margin:8px 0 0">Dry run: it plans and tells you exactly what it would do. Nothing that sends, buys, posts, signs in or changes accounts actually happens.</p>' : ''}`;
  const ok = await k.sheet({ title: dry ? `Dry Run: ${b.name}` : `Run ${b.name}`, html, primary: dry ? 'Start Dry Run' : 'Start', read: (el) => el.querySelector('#rg').value.trim() });
  const goal = typeof ok === 'string' ? ok : ok && ok.value;
  if (!goal) return;
  const j = crew.startMission(b.id, goal, { source: { type: 'user', at: Date.now() }, dryRun: dry });
  k.toast(dry ? 'Dry run started.' : `${b.name} is on it.`);
  if (j && j.id) k.go(j.id); else k.render(true);
}

function accessCard(b, k) {
  const legacy = !b.access;
  const access = b.access || defaultAccess();
  const c = k.h(`<div class="card"><div class="small muted" style="margin-bottom:10px">${legacy ? 'This bot follows its older tool groups. Change anything here to give it its own settings.' : 'Each kind of tool: off, look only, or act. Risky actions still ask you, whatever this says.'}</div><div class="accgrid"></div></div>`);
  const g = c.querySelector('.accgrid');
  for (const cap of CAP_IDS) {
    if (cap === 'core') continue;
    const v = access[cap] || 'off';
    const row = k.h(`<div class="accrow"><div class="grow">${k.esc(CAP_LABELS[cap] || cap)}</div><div class="seg">${['off', 'read', 'write'].map((x) => `<button class="chip sm${v === x ? ' on' : ''}" data-v="${x}" ${b.builtIn ? 'disabled' : ''}>${x === 'off' ? 'Off' : x === 'read' ? 'Look' : 'Act'}</button>`).join('')}</div></div>`);
    row.querySelectorAll('[data-v]').forEach((btn) => (btn.onclick = () => { if (b.builtIn) return; const next = { ...access, [cap]: btn.dataset.v }; crew.updateBot(b.id, { access: next }); k.render(true); }));
    g.appendChild(row);
  }
  return c;
}

function memoryCard(b, k) {
  const c = k.h('<div class="card"></div>');
  const notes = b.notes || [];
  if (!notes.length) c.appendChild(k.h('<div class="muted">Nothing yet. It keeps lasting facts and your preferences here as it works; you can add, change or remove any of them.</div>'));
  const l = k.h('<div class="list"></div>');
  notes.forEach((n, i) => {
    const r = k.h(`<div class="item"><div class="grow" style="white-space:pre-wrap">${k.esc(n)}</div>${b.builtIn ? '' : '<button class="btn sm" data-e>Edit</button><button class="btn sm danger" data-f>Forget</button>'}</div>`);
    if (!b.builtIn) {
      r.querySelector('[data-f]').onclick = () => { crew.setNotes(b.id, notes.filter((_, j) => j !== i)); k.render(true); };
      r.querySelector('[data-e]').onclick = async () => {
        const v = await k.sheet({ title: 'Change What It Remembers', html: `<textarea class="field" id="nv" rows="3">${k.esc(n)}</textarea>`, primary: 'Save', read: (el) => el.querySelector('#nv').value.trim() });
        const t = typeof v === 'string' ? v : v && v.value; if (t) { crew.setNotes(b.id, notes.map((x, j) => (j === i ? t : x))); k.render(true); }
      };
    }
    l.appendChild(r);
  });
  if (notes.length) c.appendChild(l);
  if (!b.builtIn) {
    const add = k.h('<div class="row" style="margin-top:12px;gap:8px"><input class="field" id="nn" placeholder="Something it should remember"><button class="btn">Add</button></div>');
    const inp = add.querySelector('#nn'); draftBox(k, 'note:' + b.id, inp);
    const save = () => { const t = inp.value.trim(); if (!t) return; drafts['note:' + b.id] = ''; crew.setNotes(b.id, [...notes, t]); k.render(true); };
    add.querySelector('button').onclick = save; inp.onkeydown = (e) => { if (e.key === 'Enter') save(); };
    c.appendChild(add);
  }
  return c;
}

// =================================================================== creating or editing a bot

const PRESETS = [['Light', { minutesPerDay: 30, callsPerDay: 80 }], ['Moderate', DEFAULT_BUDGET], ['Generous', { minutesPerDay: 480, callsPerDay: 1200 }]];
const HOME_LABELS = { any: 'Wherever it’s started', phone: 'Phone', pc: 'This PC' };

function newBotPage(col, k, editId) {
  const editing = editId ? crew.bot(editId) : null;
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-bottom:14px">‹ Back</button>'), { onclick: () => k.go(editing ? 'bot:' + editing.id : null) }));
  col.appendChild(k.h(`<h1 class="title">${editing ? 'Edit ' + k.esc(editing.name) : 'New Bot'}</h1>`));
  const d = drafts.__bot && drafts.__bot.for === (editId || 'new') ? drafts.__bot : (drafts.__bot = { for: editId || 'new', ...(editing ? JSON.parse(JSON.stringify(editing)) : { name: '', icon: '', role: '', instructions: '', home: 'any', budget: { ...DEFAULT_BUDGET }, access: defaultAccess() }) });
  if (!editing) {
    const ts = crew.listTemplates();
    col.appendChild(k.h('<div class="eyebrow">START FROM A TEMPLATE</div>'));
    if (!ts.length) col.appendChild(k.h('<div class="card muted">Ready-made bots (Trader, Inbox, Reel Poster, Researcher, Builder) arrive with the next update. For now, describe yours below.</div>'));
    else {
      const grid = k.h('<div class="botgrid"></div>');
      for (const t of ts) {
        const c = k.h(`<div class="card bot2" role="button" tabindex="0"><div class="row" style="gap:12px"><div class="boticon">${k.esc(t.icon || '•')}</div><div class="grow"><div class="bname">${k.esc(t.name)}</div><div class="small muted clamp2">${k.esc(t.about || t.role || '')}</div></div></div></div>`);
        c.onclick = () => { drafts.__bot = { for: 'new', name: t.name, icon: t.icon || '', role: t.role || '', instructions: t.instructions || '', home: t.home || 'any', budget: { ...DEFAULT_BUDGET, ...(t.budget || {}) }, access: { ...defaultAccess(), ...(t.access || {}) }, triggers: t.triggers || [], template: t.id }; k.render(true); };
        grid.appendChild(c);
      }
      col.appendChild(grid);
    }
    col.appendChild(k.h('<div class="eyebrow">OR DESCRIBE IT</div>'));
  }
  const f = k.h(`<div class="card">
    <div class="row" style="gap:10px"><input class="field" id="bi" style="width:70px;text-align:center" maxlength="4" placeholder="🤖"><input class="field grow" id="bn" maxlength="40" placeholder="Name (e.g. Reel Poster)"></div>
    <input class="field" id="br" style="margin-top:10px" maxlength="200" placeholder="What it's for, in a line">
    <textarea class="field" id="bins" rows="6" style="margin-top:10px" placeholder="How it should work: what to do, what to watch out for, how to report back"></textarea>
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">WHERE IT WORKS</div><div class="chips" id="bh">${HOMES.map((h) => `<button class="chip${d.home === h ? ' on' : ''}" data-h="${h}">${HOME_LABELS[h]}</button>`).join('')}</div>
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">DAILY BUDGET</div><div class="chips" id="bb">${PRESETS.map(([n, p]) => `<button class="chip${d.budget.callsPerDay === p.callsPerDay && d.budget.minutesPerDay === p.minutesPerDay ? ' on' : ''}" data-n="${n}">${n} · ${p.minutesPerDay >= 60 ? p.minutesPerDay / 60 + ' h' : p.minutesPerDay + ' min'}, ${p.callsPerDay} calls</button>`).join('')}</div>
    <div class="small faint" style="margin-top:8px">When it's used, the bot stops and asks you. Money is separate: every payment and every live trade still asks.</div>
    <div class="row" style="margin-top:18px"><div class="grow"></div><button class="btn primary" id="bsave">${editing ? 'Save' : 'Create Bot'}</button></div></div>`);
  const bind = (sel, key) => { const el = f.querySelector(sel); el.value = d[key] || ''; el.oninput = () => (d[key] = el.value); };
  bind('#bi', 'icon'); bind('#bn', 'name'); bind('#br', 'role'); bind('#bins', 'instructions');
  f.querySelectorAll('[data-h]').forEach((b) => (b.onclick = () => { d.home = b.dataset.h; k.render(true); }));
  f.querySelectorAll('[data-n]').forEach((b) => (b.onclick = () => { d.budget = { ...d.budget, ...PRESETS.find(([n]) => n === b.dataset.n)[1] }; k.render(true); }));
  f.querySelector('#bsave').onclick = () => {
    if (!String(d.name || '').trim()) { k.toast('Give it a name.'); return; }
    const def = { name: d.name.trim(), icon: (d.icon || '').trim(), role: (d.role || '').trim(), instructions: (d.instructions || '').trim(), home: d.home, budget: d.budget, access: d.access, triggers: d.triggers || (editing ? editing.triggers : []), createdBy: 'user' };
    const saved = editing ? crew.updateBot(editing.id, def) : crew.createBot(def);
    drafts.__bot = null;
    k.toast(editing ? 'Saved.' : `${def.name} is ready.`);
    k.go(saved && saved.id ? 'bot:' + saved.id : null);
  };
  col.appendChild(f);
  if (!editing) { col.appendChild(k.h('<div class="eyebrow">WHAT IT MAY DO</div>')); const tmp = { ...d, id: '__new', access: d.access }; const ac = accessCardDraft(tmp, k); col.appendChild(ac); }
}
/** The access editor for a bot that doesn't exist yet (writes into the draft). */
function accessCardDraft(d, k) {
  const c = k.h('<div class="card"><div class="small muted" style="margin-bottom:10px">Each kind of tool: off, look only, or act. Money starts at look-only; risky actions always ask you.</div><div class="accgrid"></div></div>');
  const g = c.querySelector('.accgrid');
  for (const cap of CAP_IDS) {
    if (cap === 'core') continue;
    const v = (drafts.__bot.access || {})[cap] || 'off';
    const row = k.h(`<div class="accrow"><div class="grow">${k.esc(CAP_LABELS[cap] || cap)}</div><div class="seg">${['off', 'read', 'write'].map((x) => `<button class="chip sm${v === x ? ' on' : ''}" data-v="${x}">${x === 'off' ? 'Off' : x === 'read' ? 'Look' : 'Act'}</button>`).join('')}</div></div>`);
    row.querySelectorAll('[data-v]').forEach((btn) => (btn.onclick = () => { drafts.__bot.access = { ...drafts.__bot.access, [cap]: btn.dataset.v }; k.render(true); }));
    g.appendChild(row);
  }
  void d;
  return c;
}
export function renderEdit(col, id, k) { newBotPage(col, k, id); }

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
        const r = k.h(`<div class="node" style="--d:${depth}" role="button" tabindex="0"><span class="dot ${toneOf(m.state)}"></span><div class="grow" style="min-width:0"><div><b>${k.esc(who)}</b> · ${k.esc(m.goal)}</div>
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

// =================================================================== group chats

function newThreadPage(col, k) {
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-bottom:14px">‹ Back</button>'), { onclick: () => { section = 'chats'; k.go(null); } }));
  col.appendChild(k.h('<h1 class="title">New Group Chat</h1>'));
  const d = drafts.__thread || (drafts.__thread = { title: '', bots: [] });
  const f = k.h(`<div class="card"><input class="field" id="tt" maxlength="80" placeholder="What it's about (e.g. Launch week)">
    <div class="small muted" style="margin:16px 0 8px;font-weight:600">WHO'S IN IT (UP TO SIX BOTS)</div><div class="chips" id="tb"></div>
    <div class="small faint" style="margin-top:8px">Atlas is always there: say @Atlas to ask it, or “sum up” for a summary.</div>
    <div class="row" style="margin-top:18px"><div class="grow"></div><button class="btn primary" id="tgo">Start</button></div></div>`);
  const tt = f.querySelector('#tt'); tt.value = d.title; tt.oninput = () => (d.title = tt.value);
  const tb = f.querySelector('#tb');
  for (const b of crew.bots().filter((x) => !x.builtIn)) {
    const on = d.bots.includes(b.id);
    const c = k.h(`<button class="chip${on ? ' on' : ''}">${k.esc(iconOf(b))} ${k.esc(b.name)}</button>`);
    c.onclick = () => { d.bots = on ? d.bots.filter((x) => x !== b.id) : d.bots.length < 6 ? [...d.bots, b.id] : d.bots; k.render(true); };
    tb.appendChild(c);
  }
  f.querySelector('#tgo').onclick = () => { const t = threads.create(d.title.trim() || 'Group chat', d.bots); drafts.__thread = null; k.go('thread:' + t.id); };
  col.appendChild(f);
}

function threadPage(col, id, k) {
  const t = threads.get(id);
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-bottom:14px">‹ Group Chats</button>'), { onclick: () => { section = 'chats'; k.go(null); } }));
  if (!t) { col.appendChild(k.h('<div class="card">This group chat was deleted.</div>')); return; }
  const roster = crew.bots();
  const inIt = t.participants.filter((p) => p !== USER).map((p) => roster.find((b) => b.id === p)).filter(Boolean);
  const head = k.h(`<div class="row" style="gap:12px;align-items:center"><div class="grow"><h1 class="title" style="margin:0">${k.esc(t.title)}</h1><div class="chips" style="margin-top:8px">
    ${inIt.map((b) => `<span class="chip sm" data-b="${k.esc(b.id)}">${k.esc(iconOf(b))} ${k.esc(b.name)}</span>`).join('')}<span class="chip sm">✦ Atlas</span></div></div>
    <button class="btn" id="tsum">Sum Up</button><button class="btn" id="tdel">Delete</button></div>`);
  head.querySelectorAll('[data-b]').forEach((c) => (c.onclick = () => k.go('bot:' + c.dataset.b)));
  head.querySelector('#tsum').onclick = () => { threads.summarize(t.id); k.toast('Atlas is summing it up.'); };
  head.querySelector('#tdel').onclick = async () => { if (await k.sheet({ title: 'Delete This Group Chat?', body: 'On both your devices. Missions it started carry on.', primary: 'Delete' })) { threads.remove(t.id); section = 'chats'; k.go(null); } };
  col.appendChild(head);

  const feed = k.h('<div class="thread"></div>');
  if (!t.messages.length) feed.appendChild(k.h(`<div class="muted" style="text-align:center;padding:30px 0">Say what you need. @mention a bot to give it the job${inIt.length ? `, e.g. “@${k.esc(inIt[0].name)} …”` : ''}.</div>`));
  for (const m of t.messages) feed.appendChild(messageView(m, roster, k));
  col.appendChild(feed);

  // the composer, with @mention chips
  const box = k.h(`<div class="card" style="margin-top:14px"><div class="chips" id="men">${inIt.map((b) => `<button class="chip sm" data-n="${k.esc(b.name)}">@${k.esc(b.name)}</button>`).join('')}<button class="chip sm" data-n="Atlas">@Atlas</button></div>
    <div class="row" style="margin-top:10px;gap:8px"><textarea class="field grow" id="tmsg" rows="2" placeholder="Message the group"></textarea><button class="btn primary" id="tsend">Send</button></div></div>`);
  const ta = box.querySelector('#tmsg'); draftBox(k, 'th:' + t.id, ta);
  box.querySelectorAll('[data-n]').forEach((c) => (c.onclick = () => { ta.value = (ta.value ? ta.value.replace(/\s*$/, ' ') : '') + '@' + c.dataset.n + ' '; drafts['th:' + t.id] = ta.value; ta.focus(); }));
  const send = () => { const text = ta.value.trim(); if (!text) return; drafts['th:' + t.id] = ''; threads.post(t.id, USER, text); k.render(true); };
  box.querySelector('#tsend').onclick = send;
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } };
  col.appendChild(box);
}

function messageView(m, roster, k) {
  if (m.from === USER) return k.h(`<div class="msg you"><div class="bubble">${k.esc(m.text)}</div></div>`);
  const b = m.from === ATLAS ? crew.ATLAS_BOT : roster.find((x) => x.id === m.from) || { name: 'A bot', icon: '•' };
  if (m.kind === 'note') return k.h(`<div class="tnote small muted">${k.esc(m.text)}</div>`);
  const v = k.h(`<div class="tmsg"><div class="boticon sm">${k.esc(iconOf(b))}</div><div class="grow" style="min-width:0"><div class="small muted"><b>${k.esc(b.name)}</b>${m.kind === 'question' ? ' · asks you' : m.kind === 'approval' ? ' · wants to' : ''} · ${k.esc(ago(m.at))}</div><div class="tbody"></div></div></div>`);
  const body = v.querySelector('.tbody');
  if (m.kind === 'approval') {
    const j = m.mission && crew.mission(m.mission);
    const open = j && j.state === 'awaiting_approval' && j.approval && j.approval.what === m.text;
    body.appendChild(k.h(`<div class="card" style="margin-top:6px;padding:14px 16px"><div style="font:400 var(--t-read)/1.4 var(--serif)">${k.esc(m.text)}</div>${open ? '<div class="row" style="margin-top:10px;gap:8px"><button class="btn" data-x>Deny</button><button class="btn primary" data-y>Allow Once</button></div>' : `<div class="small muted" style="margin-top:6px">${j ? 'Answered' : ''}</div>`}</div>`));
    if (open) { body.querySelector('[data-y]').onclick = () => { crew.approve(j.id, true); k.render(true); }; body.querySelector('[data-x]').onclick = () => { crew.approve(j.id, false); k.render(true); }; }
  } else {
    const r = k.h('<div class="reply" style="margin-top:4px"></div>'); r.innerHTML = k.md ? k.md(m.text) : k.esc(m.text); body.appendChild(r);
    if (m.mission && crew.mission(m.mission)) body.appendChild(Object.assign(k.h('<button class="btn sm" style="margin-top:6px">Open Its Mission</button>'), { onclick: () => k.go(m.mission) }));
  }
  void mentionsIn;
  return v;
}
