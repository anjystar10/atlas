// The Bots page's extras on the PC: routines, skills, the specialist team, and on Me the approval rules. Each piece
// draws into a column it's given; app.js passes its small helpers in [k] (h, esc, sheet, toast, render).
import { extras, bots } from './botwire.js';
import { CATEGORIES, describeSchedule, parseSchedule, TOOL_GROUPS } from './botx.js';
import { FINAL as BOT_FINAL, LABELS as BOT_LABELS } from './bots.js';

const when = (ms) => (ms ? new Date(ms).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');
const val = (o, sel) => (o.querySelector(sel).value || '').trim();
const spOptions = (k, chosen) => `<option value="">Atlas Bot (no specialist)</option>` +
  extras.data.specialists.map((s) => `<option value="${k.esc(s.id)}" ${s.id === chosen ? 'selected' : ''}>${k.esc(s.name)}</option>`).join('');

// ---------------------------------------------------------------- the job page

/** Allow once / Always allow this kind / Deny. */
export function approvalCard(col, j, k) {
  const kind = j.approval.category ? extras.label(j.approval.category) : null;
  const c = k.h(`<div class="card" style="margin-top:14px"><div class="small warn" style="font-weight:600">It wants to:</div><div style="font:400 19px var(--serif);margin-top:4px">${k.esc(j.approval.what)}</div>
    ${kind ? `<div class="small muted" style="margin-top:6px">Kind: ${k.esc(kind)}</div>` : ''}
    <div class="row" style="margin-top:12px;flex-wrap:wrap"><button class="btn grow" id="bdeny">Deny</button><button class="btn grow" id="balways" ${kind ? '' : 'hidden'}>Always Allow This Kind</button><button class="btn primary grow" id="bok">Allow Once</button></div></div>`);
  c.querySelector('#bdeny').onclick = () => bots.approve(j.id, false);
  c.querySelector('#bok').onclick = () => bots.approve(j.id, true);
  c.querySelector('#balways').onclick = async () => {
    if (await k.sheet({ title: 'Always Allow This Kind?', body: `Bots won't ask again before: ${kind.toLowerCase()}. You can change this in Me → Bot Permissions.`, primary: 'Always Allow' })) bots.approve(j.id, true, true);
  };
  col.appendChild(c);
}

/** Talk to a bot while it works, or ask it to wrap up. */
export function steerCard(col, j, k) {
  if (BOT_FINAL.has(j.state)) return;
  const c = k.h(`<div class="card" style="margin-top:14px"><textarea class="field" id="botsteer" rows="1" placeholder="Tell the bot something (it reads it at its next step)"></textarea>
    <div class="row" style="margin-top:10px"><button class="btn" id="bwrap" ${j.wrapUp ? 'disabled' : ''}>${j.wrapUp ? 'Wrapping Up…' : 'Wrap Up Now'}</button><div class="grow"></div><button class="btn primary" id="bsteer">Send</button></div></div>`);
  const ta = c.querySelector('#botsteer');
  const send = () => { const t = ta.value.trim(); if (!t) return; if (bots.steer(j.id, t)) { ta.value = ''; k.toast('Sent: the bot reads it at its next step.'); } };
  c.querySelector('#bsteer').onclick = send;
  ta.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } };
  c.querySelector('#bwrap').onclick = () => { bots.wrapUp(j.id); k.toast('It will finish with what it has.'); };
  col.appendChild(c);
}

/** The bot's helpers (specialists it handed parts to), each opening its own page. */
export function helpersList(col, j, k, open) {
  const kids = bots.helpers(j.id); if (!kids.length) return;
  col.appendChild(k.h('<div class="eyebrow">HELPERS</div>'));
  const l = k.h('<div class="card list"></div>');
  for (const x of kids) {
    const sp = extras.data.specialists.find((s) => s.id === x.specialist);
    const r = k.h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${k.esc(x.goal)}</div><div class="m">${k.esc((sp ? sp.name + ' · ' : '') + BOT_LABELS[x.state])}</div></div><span class="tag">Open</span></div>`);
    r.onclick = () => open(x.id);
    l.appendChild(r);
  }
  col.appendChild(l);
}

/** A finished job can become a skill: a first draft from what it did, for the user to edit. */
export function saveAsSkillButton(col, j, k) {
  if (j.state !== 'completed' || j.parent) return;
  col.appendChild(Object.assign(k.h('<button class="btn" style="margin-top:22px;margin-right:8px">Save as a Skill</button>'), { onclick: () => editSkill(k, extras.draftSkill(j)) }));
}

// ---------------------------------------------------------------- the list page: routines, skills, team

/** Under the Bots list: the older routines and the skills (the team is the list itself now). */
export function moreSections(col, k) { routines(col, k); skills(col, k); }
export function extrasSections(col, k) {
  routines(col, k); skills(col, k); team(col, k);
}

function routines(col, k) {
  col.appendChild(k.h('<div class="eyebrow">Routines</div>'));
  const c = k.h('<div class="card"></div>'); col.appendChild(c);
  c.appendChild(k.h('<p class="muted small" style="margin:0 0 8px">Bots on a schedule, like “every weekday at 8am, summarise what\'s new in AI”. Only one of your devices runs each one.</p>'));
  const list = k.h('<div class="list"></div>'); c.appendChild(list);
  for (const r of extras.data.routines) {
    const mine = extras.mine(r);
    const row = k.h(`<div class="item"><div class="grow"><div class="t">${k.esc(r.name)}</div><div class="m">${k.esc(describeSchedule(r.schedule))}${r.enabled ? ' · next ' + k.esc(when(r.nextAt)) : ' · paused'}${r.lastAt ? ' · last ' + k.esc(when(r.lastAt)) : ''}${mine ? '' : ' · made on your other device'}</div></div>
      <button class="btn" data-a="test">Test Run</button><button class="btn" data-a="toggle">${r.enabled ? 'Pause' : 'Resume'}</button><button class="btn" data-a="edit">Edit</button></div>`);
    row.querySelector('[data-a=test]').onclick = () => { extras.runRoutine(r.id, true); k.toast('Sent a bot to try it now.'); };
    row.querySelector('[data-a=toggle]').onclick = () => extras.enableRoutine(r.id, !r.enabled);
    row.querySelector('[data-a=edit]').onclick = () => editRoutine(k, r);
    list.appendChild(row);
  }
  if (!extras.data.routines.length) list.appendChild(k.h('<div class="item muted">None yet. Add one here, or tell Atlas “every Sunday evening, plan my week”.</div>'));
  c.appendChild(Object.assign(k.h('<button class="btn primary" style="margin-top:10px">Add a Routine</button>'), { onclick: () => editRoutine(k, null) }));
}

async function editRoutine(k, r) {
  let err = '';
  const html = `<label class="small muted">What the bot should do each time</label><textarea class="field" id="rg" rows="2">${k.esc(r ? r.goal : '')}</textarea>
    <label class="small muted" style="display:block;margin-top:10px">When, in plain words</label><input class="field" id="rs" placeholder="every weekday at 8am · every 2 hours · every Sunday evening" value="${k.esc(r ? describeSchedule(r.schedule) : '')}">
    <div class="small" id="rp" style="margin-top:6px"></div>
    <label class="small muted" style="display:block;margin-top:10px">Name</label><input class="field" id="rn" value="${k.esc(r ? r.name : '')}" placeholder="(from what it does)">
    <label class="small muted" style="display:block;margin-top:10px">Send</label><select class="field" id="rsp">${spOptions(k, r && r.specialist)}</select>
    ${r ? '<button class="btn danger" id="rdel" style="margin-top:14px">Delete Routine</button>' : ''}`;
  await k.sheet({
    title: r ? 'Edit Routine' : 'New Routine', html, primary: 'Save',
    onOpen: (o) => {
      const show = () => { const p = parseSchedule(val(o, '#rs')); o.querySelector('#rp').textContent = !val(o, '#rs') ? '' : !p ? "Couldn't read that yet." : p.error ? p.error : '→ ' + describeSchedule(p); o.querySelector('#rp').className = 'small ' + (p && !p.error ? 'ok' : 'warn'); };
      o.querySelector('#rs').oninput = show; show();
      const del = o.querySelector('#rdel');
      if (del) del.onclick = async () => { if (await k.sheet({ title: 'Delete This Routine?', body: r.name, primary: 'Delete', danger: true })) { extras.removeRoutine(r.id); o.remove(); k.render(true); } };
    },
    onPrimary: (o) => {
      const res = extras.saveRoutine({ id: r ? r.id : undefined, goal: val(o, '#rg'), scheduleText: val(o, '#rs'), name: val(o, '#rn') || undefined, specialist: val(o, '#rsp') || null });
      if (res.error) { err = res.error; k.toast(err, 5000); return false; }
      k.toast(`Next run: ${when(res.routine.nextAt)}`); return true;
    },
  });
}

function skills(col, k) {
  col.appendChild(k.h('<div class="eyebrow">Skills</div>'));
  const c = k.h('<div class="card"></div>'); col.appendChild(c);
  c.appendChild(k.h('<p class="muted small" style="margin:0 0 8px">How you like a kind of job done. Bots load the right one when they need it. Teach one here, tell Atlas “when I ask for X, do it like this…”, or save one from a finished bot.</p>'));
  const list = k.h('<div class="list"></div>'); c.appendChild(list);
  for (const s of extras.data.skills) {
    const row = k.h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${k.esc(s.name)}</div><div class="m">${k.esc(s.when || '')}</div></div><span class="tag">Edit</span></div>`);
    row.onclick = () => editSkill(k, s);
    list.appendChild(row);
  }
  if (!extras.data.skills.length) list.appendChild(k.h('<div class="item muted">None yet.</div>'));
  c.appendChild(Object.assign(k.h('<button class="btn primary" style="margin-top:10px">Teach a Skill</button>'), { onclick: () => editSkill(k, null) }));
}

async function editSkill(k, s) {
  const f = (id, label, v, rows = 1, ph = '') => `<label class="small muted" style="display:block;margin-top:10px">${label}</label>${rows > 1 ? `<textarea class="field" id="${id}" rows="${rows}" placeholder="${k.esc(ph)}">${k.esc(v || '')}</textarea>` : `<input class="field" id="${id}" value="${k.esc(v || '')}" placeholder="${k.esc(ph)}">`}`;
  const existing = s && s.id && extras.data.skills.some((x) => x.id === s.id);
  const html = f('kn', 'Name', s && s.name, 1, 'Weekly money check') + f('kw', 'When to use it', s && s.when, 2, 'When I ask how my spending went this week') +
    f('ks', 'Steps', s && s.steps, 5, '1. …\n2. …') + f('ki', 'What it needs first (optional)', s && s.inputs, 2) + f('kc', 'Checks before finishing (optional)', s && s.checks, 2) +
    f('ko', 'What the result should look like (optional)', s && s.output, 2) + f('kp', 'Actions that need your OK (optional)', s && s.permissions, 2) +
    (existing ? '<button class="btn danger" id="kdel" style="margin-top:14px">Delete Skill</button>' : '');
  await k.sheet({
    title: existing ? 'Edit Skill' : 'New Skill', html, primary: 'Save',
    onOpen: (o) => { const d = o.querySelector('#kdel'); if (d) d.onclick = async () => { if (await k.sheet({ title: 'Delete This Skill?', body: s.name, primary: 'Delete', danger: true })) { extras.removeSkill(s.id); o.remove(); k.render(true); } }; },
    onPrimary: (o) => {
      const x = { id: existing ? s.id : undefined, name: val(o, '#kn'), when: val(o, '#kw'), steps: val(o, '#ks'), inputs: val(o, '#ki'), checks: val(o, '#kc'), output: val(o, '#ko'), permissions: val(o, '#kp') };
      if (!x.name || !x.steps) { k.toast('A skill needs a name and its steps.'); return false; }
      extras.saveSkill(x); k.toast('Skill saved'); k.render(true); return true;
    },
  });
}

function team(col, k) {
  col.appendChild(k.h('<div class="eyebrow">Your Team</div>'));
  const c = k.h('<div class="card"></div>'); col.appendChild(c);
  c.appendChild(k.h('<p class="muted small" style="margin:0 0 8px">Specialist bots. Atlas and your bots hand them the parts that fit, and each keeps its own notes on how you like things.</p>'));
  const list = k.h('<div class="list"></div>'); c.appendChild(list);
  for (const s of extras.data.specialists) {
    const row = k.h(`<div class="item" style="cursor:pointer"><div class="grow"><div class="t">${k.esc(s.name)}</div><div class="m">${k.esc(s.role || '')}${s.notes.length ? ` · ${s.notes.length} note${s.notes.length === 1 ? '' : 's'}` : ''}</div></div><span class="tag">Edit</span></div>`);
    row.onclick = () => editSpecialist(k, s);
    list.appendChild(row);
  }
  c.appendChild(Object.assign(k.h('<button class="btn primary" style="margin-top:10px">Add a Specialist</button>'), { onclick: () => editSpecialist(k, null) }));
}

async function editSpecialist(k, s) {
  const groups = TOOL_GROUPS.map((g) => `<label class="row small" style="gap:8px;margin-top:6px"><input type="checkbox" value="${g.id}" ${s && s.groups.includes(g.id) ? 'checked' : ''}>${k.esc(g.label)}</label>`).join('');
  const html = `<label class="small muted">Name</label><input class="field" id="sn" value="${k.esc(s ? s.name : '')}" placeholder="Travel Bot">
    <label class="small muted" style="display:block;margin-top:10px">What it's for</label><input class="field" id="sr" value="${k.esc(s ? s.role : '')}" placeholder="Finds trips, compares routes and prices">
    <label class="small muted" style="display:block;margin-top:10px">Standing instructions</label><textarea class="field" id="si" rows="3">${k.esc(s ? s.instructions : '')}</textarea>
    <div class="small muted" style="margin-top:10px">What it may use (none ticked: everything)</div>${groups}
    ${s && s.notes.length ? `<div class="small muted" style="margin-top:10px">Its notes</div><div class="small">${s.notes.map((n) => '· ' + k.esc(n)).join('<br>')}</div><button class="btn" id="sclear" style="margin-top:8px">Clear Its Notes</button>` : ''}
    ${s ? '<button class="btn danger" id="sdel" style="margin-top:14px">Remove From Team</button>' : ''}`;
  await k.sheet({
    title: s ? s.name : 'New Specialist', html, primary: 'Save',
    onOpen: (o) => {
      const d = o.querySelector('#sdel'); if (d) d.onclick = async () => { if (await k.sheet({ title: 'Remove This Specialist?', body: s.name, primary: 'Remove', danger: true })) { extras.removeSpecialist(s.id); o.remove(); k.render(true); } };
      const cl = o.querySelector('#sclear'); if (cl) cl.onclick = () => { extras.saveSpecialist({ id: s.id, notes: [] }); o.remove(); k.render(true); };
    },
    onPrimary: (o) => {
      const x = { id: s ? s.id : undefined, name: val(o, '#sn'), role: val(o, '#sr'), instructions: val(o, '#si'), groups: [...o.querySelectorAll('input[type=checkbox]:checked')].map((b) => b.value) };
      if (!x.name) { k.toast('Give it a name.'); return false; }
      extras.saveSpecialist(x); k.render(true); return true;
    },
  });
}

// ---------------------------------------------------------------- Me: what bots may do without asking

export function permissionsCard(col, k) {
  col.appendChild(k.h('<div class="eyebrow">Bot Permissions</div>'));
  const c = k.h('<div class="card"></div>'); col.appendChild(c);
  c.appendChild(k.h('<p class="muted small" style="margin:0 0 6px">What bots must ask you before doing. “Always” lets them go ahead; “Never” stops them trying.</p>'));
  const list = k.h('<div class="list"></div>'); c.appendChild(list);
  for (const cat of CATEGORIES) {
    const m = extras.mode(cat.id);
    const row = k.h(`<div class="item"><div class="grow"><div>${k.esc(cat.label)}</div></div><div class="chips">${['ask', 'allow', 'deny'].map((x) => `<span class="chip ${m === x ? 'on' : ''}" data-m="${x}">${{ ask: 'Ask', allow: 'Always', deny: 'Never' }[x]}</span>`).join('')}</div></div>`);
    row.querySelectorAll('[data-m]').forEach((ch) => (ch.onclick = () => { extras.setMode(cat.id, ch.dataset.m); k.render(true, true); }));
    list.appendChild(row);
  }
  c.appendChild(k.h('<div class="small muted" style="margin-top:14px">Standing rules, in your words (every bot follows them)</div>'));
  const rl = k.h('<div class="list"></div>'); c.appendChild(rl);
  for (const r of extras.data.policy.rules) {
    const row = k.h(`<div class="item"><div class="grow">${k.esc(r)}</div><button class="btn">Remove</button></div>`);
    row.querySelector('button').onclick = () => { extras.removeRule(r); k.render(true, true); };
    rl.appendChild(row);
  }
  const add = k.h('<div class="row" style="margin-top:8px"><input class="field grow" id="newrule" placeholder="e.g. Never book anything on a Sunday"><button class="btn primary">Add Rule</button></div>');
  const go = () => { const t = add.querySelector('input').value.trim(); if (t) { extras.addRule(t); k.render(true); } };
  add.querySelector('button').onclick = go; add.querySelector('input').onkeydown = (e) => { if (e.key === 'Enter') go(); };
  c.appendChild(add);
}
