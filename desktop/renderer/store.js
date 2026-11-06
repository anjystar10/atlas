// Atlas's data on this PC, in exactly the same shape as on the phone (so the two sync record for record):
// state.json (settings, reminders "tasks", the log, memories), chat.json (the conversation), prefs.json (this PC's
// own settings: AI keys, voice, look). The reminder rules mirror the phone's Engine.kt.
const host = window.atlasHost;
const MIN = 60000;
export const OPEN = new Set(['planned', 'active', 'awaiting', 'blocked']);
const RECURRENCES = ['none', 'daily', 'weekdays', 'weekly'];

export const db = { state: null, chat: [], prefs: {} };
const listeners = new Set();
export function onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function changed(what) { for (const fn of [...listeners]) { try { fn(what); } catch (e) { console.error(e); } } }

export const uid = () => crypto.randomUUID();

function freshState() {
  return { schema: 3, deviceId: uid(), settings: { name: '', tone: 'firm', quietStart: '22:30', quietEnd: '06:30', graceMin: 15, followUps: 1,
    sound: true, avatarOn: true, speakReplies: false, theme: 'dark', wakeWord: false }, tasks: [], log: [], memories: [] };
}

export async function load() {
  const s = await host.read('state.json');
  try { db.state = s ? JSON.parse(s) : freshState(); } catch { db.state = freshState(); }
  for (const k of ['tasks', 'log', 'memories']) if (!Array.isArray(db.state[k])) db.state[k] = [];
  db.state.settings = Object.assign(freshState().settings, db.state.settings || {});
  if (!db.state.deviceId) db.state.deviceId = uid();
  try { db.chat = JSON.parse((await host.read('chat.json')) || '[]'); } catch { db.chat = []; }
  try { db.prefs = JSON.parse((await host.read('prefs.json')) || '{}'); } catch { db.prefs = {}; }
}

let tState = 0, tChat = 0, tPrefs = 0;
export function saveState(quiet) {
  clearTimeout(tState);
  tState = setTimeout(() => host.write('state.json', JSON.stringify(db.state)), 250);
  if (!quiet) changed('state');
}
export function saveChat(quiet) {
  clearTimeout(tChat);
  tChat = setTimeout(() => host.write('chat.json', JSON.stringify(db.chat.slice(-240))), 400);
  if (!quiet) changed('chat');
}
export function savePrefs() { clearTimeout(tPrefs); tPrefs = setTimeout(() => host.write('prefs.json', JSON.stringify(db.prefs)), 150); changed('prefs'); }
export const pref = (k, d) => (db.prefs[k] === undefined ? d : db.prefs[k]);
export function setPref(k, v) { db.prefs[k] = v; savePrefs(); }

// ------------------------------------------------------------------ the reminder rules (as on the phone)

const clampDur = (v) => (!v || v <= 0 ? 30 : Math.min(720, Math.max(5, v)));
function log(type, task, detail = '', now = Date.now()) {
  db.state.log.push({ id: uid(), at: now, type, taskId: task ? task.id : null, title: task ? task.title : detail, detail: task ? detail : '' });
  if (db.state.log.length > 2000) db.state.log.splice(0, db.state.log.length - 2000);
}
function touch(t, now) { t.updatedAt = now; t.version = (t.version || 1) + 1; t.deviceId = db.state.deviceId; }
function resetRem(t) { t.remindedAt = null; t.nextCheckAt = null; t.promptedAt = null; t.followUpsSent = 0; }
export const findTask = (id) => db.state.tasks.find((t) => t.id === id);

export function createTask(title, { startAt = null, durationMin = 30, notes = '', priority = 'normal', recurrence = 'none', seriesId = null } = {}, now = Date.now()) {
  const clean = String(title || '').trim();
  if (!clean) throw new Error('Give the reminder a title.');
  const rec = startAt != null && RECURRENCES.includes(recurrence) ? recurrence : 'none';
  const t = { id: uid(), title: clean, notes, priority, startAt, durationMin: clampDur(durationMin), recurrence: rec, status: 'planned',
    createdAt: now, updatedAt: now, version: 1, deviceId: db.state.deviceId, completedAt: null, blockedReason: '', skipReason: '',
    seriesId, seriesAt: rec !== 'none' ? startAt : null, remindedAt: null, nextCheckAt: null, promptedAt: null, followUpsSent: 0 };
  db.state.tasks.push(t); log('created', t, '', now); saveState();
  return t;
}

export function updateTask(id, { title, startAt, notes, priority, recurrence }, now = Date.now()) {
  const t = findTask(id); if (!t) throw new Error('Reminder not found.');
  const timeChanged = startAt !== undefined && startAt !== t.startAt;
  if (title !== undefined) { const c = String(title).trim(); if (!c) throw new Error('Give the reminder a title.'); t.title = c; }
  if (notes !== undefined) t.notes = notes;
  if (priority !== undefined) t.priority = priority;
  if (startAt !== undefined) t.startAt = startAt;
  if (recurrence !== undefined || startAt !== undefined) {
    const r = recurrence !== undefined ? recurrence : t.recurrence;
    t.recurrence = t.startAt != null && RECURRENCES.includes(r) ? r : 'none';
    t.seriesAt = t.recurrence !== 'none' ? t.startAt : null;
  }
  if (timeChanged && ['planned', 'active', 'awaiting'].includes(t.status)) { t.status = 'planned'; resetRem(t); }
  touch(t, now); log('edited', t, '', now); saveState();
  return t;
}

export function nextOcc(ms, rec) {
  const d = new Date(ms);
  if (rec === 'daily') d.setDate(d.getDate() + 1);
  else if (rec === 'weekly') d.setDate(d.getDate() + 7);
  else if (rec === 'weekdays') { d.setDate(d.getDate() + 1); while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1); }
  else return null;
  return d.getTime();
}
function spawnNext(t, now) {
  const start = t.seriesAt ?? t.startAt;
  if (start == null || t.recurrence === 'none') return null;
  let at = nextOcc(start, t.recurrence), missed = 0;
  while (at != null && at <= now && missed < 1000) { at = nextOcc(at, t.recurrence); missed++; }
  if (at == null) return null;
  return createTask(t.title, { startAt: at, durationMin: t.durationMin, notes: t.notes, priority: t.priority, recurrence: t.recurrence, seriesId: t.seriesId || t.id }, now);
}

/** done | delay (startAt) | skipped */
export function respond(id, kind, { startAt = null, reason = '' } = {}, now = Date.now()) {
  const t = findTask(id); if (!t) throw new Error('Reminder not found.');
  if (kind === 'done') { t.status = 'done'; t.completedAt = now; touch(t, now); log('done', t, '', now); spawnNext(t, now); }
  else if (kind === 'delay') { t.startAt = startAt; t.status = 'planned'; t.blockedReason = ''; resetRem(t); touch(t, now); log('delayed', t, String(startAt), now); }
  else if (kind === 'skipped') { t.status = 'skipped'; t.skipReason = reason; touch(t, now); log('skipped', t, reason, now); spawnNext(t, now); }
  else throw new Error('Unknown response.');
  saveState();
  return t;
}
export function cancelTask(id, now = Date.now()) { const t = findTask(id); if (!t) return; t.status = 'cancelled'; touch(t, now); log('cancelled', t, '', now); saveState(); }
export function deleteTask(id, now = Date.now()) { const i = db.state.tasks.findIndex((t) => t.id === id); if (i < 0) return; const [t] = db.state.tasks.splice(i, 1); log('deleted', t, '', now); saveState(); }

export function remember(text, now = Date.now()) {
  const clean = String(text || '').trim(); if (!clean) throw new Error('Nothing to save.');
  const m = { id: uid(), text: clean, createdAt: now }; db.state.memories.push(m); log('remembered', null, clean, now); saveState();
  return m;
}
export function forget(id, now = Date.now()) {
  const i = db.state.memories.findIndex((m) => m.id === id); if (i < 0) throw new Error('Memory not found.');
  const [m] = db.state.memories.splice(i, 1); log('forgot', null, m.text, now); saveState();
}
export function logFocus(title, taskId, mins, now = Date.now()) {
  db.state.log.push({ id: uid(), at: now, type: 'focus', taskId: taskId || null, title, detail: `focused for ${mins} min` }); saveState();
}

/** Due reminders: marks them reminded and returns them (the caller rings or notifies). */
export function takeDue(now = Date.now()) {
  const due = [];
  for (const t of db.state.tasks) {
    if (t.status === 'planned' && t.remindedAt == null && t.startAt != null && t.startAt <= now + 30000) {
      t.remindedAt = now;
      if (now - t.startAt <= 6 * 3600000) due.push(t);
      else log('note', t, 'missed: the PC was off at its time', now);
    }
  }
  if (due.length) saveState();
  return due;
}
