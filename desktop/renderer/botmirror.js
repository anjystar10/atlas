// Atlas Bot's jobs, shown on the other device too (the same as BotMirror.kt on the phone): what each bot is doing,
// how far its plan is, and its report. Read-only there: each job runs only on the device that started it, so nothing
// runs twice. Synced as kind "botview" (plain JSON, no secrets).
import { db, changed } from './store.js';
import * as sync from './sync.js';

export const KIND = 'botview';
const host = window.atlasHost;
let mirror = { others: {}, mine: {} }, loaded = false;

async function load() {
  if (loaded) return; loaded = true;
  try { const m = JSON.parse((await host.read('bot-mirror.json')) || '{}'); mirror = { others: m.others || {}, mine: m.mine || {} }; } catch {}
}
const save = () => host.write('bot-mirror.json', JSON.stringify(mirror));

/** A job as the other device shows it. */
export function view(j, me, label) {
  return { id: j.id, device: me, platform: 'windows', goal: String(j.goal || '').slice(0, 300), state: j.state, label: label || '',
    current: String(j.current || '').slice(0, 200), done: (j.plan || []).filter((p) => p.done).length, total: (j.plan || []).length,
    report: String(j.report || '').slice(0, 20000), createdAt: j.createdAt || 0, updatedAt: j.updatedAt || 0,
    bot: j.bot || j.specialist || 'atlas' };   // whose job it is (History groups by bot)
}

/** The other devices' jobs, newest first. */
export const others = () => Object.values(mirror.others).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

export function register({ visible, labels }) {
  load();
  sync.registerKind(KIND, {
    list: async () => {
      await load();
      const me = db.state.deviceId, out = {}, mine = {};
      for (const j of visible()) if (!j.device || j.device === me) { const v = view(j, me, labels[j.state]); out[j.id] = v; mine[j.id] = v; }
      mirror.mine = mine;
      // the other device's jobs stay as they came (so their absence here never reads as "deleted")
      for (const [id, v] of Object.entries(mirror.others)) if (!(id in out)) out[id] = v;
      await save();
      return out;
    },
    apply: async (id, rec) => {
      await load();
      if (!rec) delete mirror.others[id]; else if (rec.device !== db.state.deviceId) mirror.others[id] = rec;
      await save(); changed('bots');
    },
  });
}
