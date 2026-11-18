// Atlas aware of itself and of what's around it, on the PC (the phone's SelfAware.kt + Updater.kt).
//
// - Around it: time, battery (laptops), network, the window in front, CPU / memory / GPU load, free disk, bots
//   running, the phone. Only what this PC already shows Atlas; nothing is stored or sent anywhere except to the AI
//   service in the turn that uses it.
// - Inside it: version and changelog, abilities (the capability registry), AI keys and their health, bots, sync and
//   the link, the device, settings, recent errors.
// - In the brief: a "Now:" line and alerts that matter (each mentioned once), and "This Atlas" lines; details come
//   from the tools (whats_around, self_status, about_atlas).
// - Updates: after an update, one chat line; a newer release (latest.json on the "desktop" release) is announced
//   once; update_atlas downloads it, checks its SHA-256 and runs it only after the user clicks Install.
import { db, saveChat, changed, uid, pref, setPref } from './store.js';
import * as aware from './aware.js';
import * as brain from './brain.js';
import * as sync from './sync.js';
import { plainReply } from './plain.js';
import { bots, extras } from './botwire.js';
import { FINAL as BOT_FINAL } from './bots.js';
import * as U from './updates.js';
import * as A from './around.js';

const host = window.atlasHost;
export const LATEST_URL = 'https://github.com/anjystar10/atlas/releases/download/desktop/latest.json';

let version = '';
let cl = [];
let seenAlerts = [];
let battery = null;

// ------------------------------------------------------------------ around

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const two = (n) => String(n).padStart(2, '0');
export const timeLabel = (d) => `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]} ${two(d.getHours())}:${two(d.getMinutes())}`;

function liveBots() {
  try { return bots.visible().filter((j) => !BOT_FINAL.has(j.state)); } catch { return []; }
}
function tradingRunning(live) {
  const specs = (extras && extras.data && extras.data.specialists) || [];
  return live.some((j) => /trad/i.test(j.goal || '') || (j.specialist && specs.some((s) => s.id === j.specialist && /trad/i.test((s.name || '') + (s.role || '')))));
}

/** What's around right now. [full]: also the window's title and the phone (for the tool). */
export async function facts(full = false) {
  const f = {};
  const now = new Date();
  f.time = timeLabel(now);
  try { f.zone = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch {}
  try { Object.assign(f, (host.self && (await host.self.facts())) || {}); } catch {}
  if (!full) delete f.activeWindow;
  if (typeof navigator !== 'undefined' && navigator.onLine === false) f.network = 'none';
  try {
    if (!battery && navigator.getBattery) battery = await navigator.getBattery();
    // a desktop with no battery reports full and charging with nothing to charge: leave it out
    if (battery && !(battery.level === 1 && battery.charging && battery.chargingTime === 0)) { f.battery = Math.round(battery.level * 100); f.charging = !!battery.charging; }
  } catch {}
  const live = liveBots();
  if (live.length) f.jobs = `${live.length} bot${live.length === 1 ? '' : 's'} working`;
  if (full) {
    try {
      const l = aware.src.link;
      if (l && l.linked()) { const p = l.phone(); f.other = p ? `phone ${p.name || ''}: ${p.online ? 'online' : 'offline, last seen ' + l.ago(p.agoMs)}`.replace('  ', ' ') : 'phone linked, not checked in yet'; }
    } catch {}
  }
  return f;
}

export async function aroundLines(markSeen = true) {
  const f = await facts(false);
  const live = liveBots();
  const s = A.section(f, { botsRunning: live.length, tradingRunning: tradingRunning(live) }, seenAlerts);
  if (markSeen) seenAlerts = s.seen;
  return s.lines;
}

// ------------------------------------------------------------------ this Atlas

function state() {
  return {
    version, versionCode: 0, device: 'the PC',
    from: pref('selfFrom', null), updatedAt: pref('selfUpdatedAt', 0), now: Date.now(),
    others: (sync.status.others || []).map((o) => ({ platform: o.platform, name: o.name, app: o.app })),
    latest: U.parseLatest(pref('selfLatest', null)),
  };
}
export const identity = () => U.identityLine(version, 'their Windows PC');
export const briefLines = () => { try { return U.briefLines(cl, state()); } catch { return []; } };
export const about = () => U.aboutText(cl, state());

function post(text) {
  const m = { id: uid(), user: false, text: plainReply(text), source: 'Atlas', at: Date.now(), steps: [] };
  const busy = db.chat.findIndex((x) => x.pending);
  if (busy > 0) db.chat.splice(busy - 1, 0, m); else db.chat.push(m);
  saveChat(); changed('chat');
}

export async function check(force = false) {
  const now = Date.now();
  const cached = U.parseLatest(pref('selfLatest', null));
  if (!force && !U.checkDue(pref('selfCheckedAt', 0), now)) return cached ? describe(cached) : 'Checked recently.';
  const r = await host.fetch(LATEST_URL, { timeout: 20000 }).catch((e) => ({ ok: false, error: e.message }));
  if (!r || !r.ok || !r.text) return `Couldn't check for updates: ${(r && (r.error || 'the server said ' + r.status)) || 'no connection'}.`;
  const l = U.parseLatest(r.text);
  if (!l) return "Couldn't read the update information.";
  setPref('selfCheckedAt', now); setPref('selfLatest', r.text);
  const installed = { version, versionCode: 0 };
  if (U.shouldAnnounce(l, installed, pref('selfAnnounced', ''))) {
    setPref('selfAnnounced', l.version);
    post(U.availableMessage(l, installed));
    try { host.notify(`Atlas ${l.version} is ready`, 'Say "update Atlas" when you want it.', 'atlas-update'); } catch {}
  }
  return describe(l);
}
function describe(l) {
  return U.isNewer(l, { version, versionCode: 0 })
    ? `Atlas ${l.version} is available (this PC has ${version}).` + (l.notes.length ? ` New: ${l.notes.slice(0, 4).join('; ')}.` : '')
    : `Atlas is up to date (${version}; newest release ${l.version}).`;
}

export async function install() {
  await check(true);
  const l = U.parseLatest(pref('selfLatest', null));
  if (!l) return "I couldn't find the update information (no connection?).";
  if (!U.isNewer(l, { version, versionCode: 0 })) return `Atlas is already up to date (${version}).`;
  if (!host.self) return 'Updating needs the installed Atlas app.';
  const d = await host.self.download(l.url, l.sha256, l.version);
  if (!d || !d.ok) return (d && d.error) || 'The download failed.';
  const r = await host.self.install(l.version);
  if (r && r.ok) return `Installing Atlas ${l.version}: Atlas closes now and opens again on the new version.`;
  if (r && r.said === 'not now') return `Atlas ${l.version} is downloaded and checked. Say "update Atlas" again when you're ready to install it.`;
  return (r && r.error) || "The installer didn't start.";
}

// ------------------------------------------------------------------ inside Atlas: a full check

async function status() {
  const out = [];
  const part = (title, lines) => { const l = lines.filter(Boolean); if (l.length) { out.push(title + ':'); for (const x of l) out.push('- ' + x); } };
  const st = state(); const now = Date.now();
  out.push(identity() + (st.from ? ` Updated from ${st.from}.` : ''));
  if (st.latest) out.push(U.isNewer(st.latest, { version, versionCode: 0 }) ? `A newer release is out: ${st.latest.version}.` : `Up to date (newest release ${st.latest.version}).`);
  part('Abilities (tool groups)', aware.all().map((c) => { let t = []; try { t = (c.tools() || []).map((d) => d.name); } catch {} return `${c.id}: ${t.join(', ')}`; }));
  const perms = [];
  try { perms.push(`notifications: ${typeof Notification !== 'undefined' ? Notification.permission : '?'}`); } catch {}
  try { const m = await navigator.permissions.query({ name: 'microphone' }); perms.push(`microphone: ${m.state}`); } catch {}
  try { perms.push(`starts with Windows: ${(await host.autostart()) ? 'on' : 'off'}`); } catch {}
  part('Permissions', perms);
  return out;
}

export async function selfStatus() {
  const out = await status();
  const now = Date.now();
  const add = (title, lines) => { const l = lines.filter(Boolean); if (l.length) { out.push(title + ':'); for (const x of l) out.push('- ' + x); } };
  const keyLines = brain.PROVIDERS.map((p) => {
    const ks = brain.keys(p.id); if (!ks.length) return null;
    const ready = ks.filter((e) => !e.bad && (e.restUntil || 0) <= now).length, bad = ks.filter((e) => e.bad);
    const resting = ks.filter((e) => !e.bad && (e.restUntil || 0) > now);
    return `${p.name}: ${ks.length} key${ks.length === 1 ? '' : 's'}, ${ready} ready` +
      (resting.length ? `, ${resting.length} resting (out of quota or rate-limited) until ${timeLabel(new Date(Math.min(...resting.map((e) => e.restUntil))))}` : '') +
      (bad.length ? `, ${bad.length} turned down (${String(bad[0].problem || '').slice(0, 60)})` : '');
  }).filter(Boolean);
  add('AI services and keys', [...(keyLines.length ? keyLines : ['no keys (only a local model, if one is set up)']), brain.dontSwitch() ? "Don't Switch is on" : null]);
  try {
    const all = bots.visible(); const live = all.filter((j) => !BOT_FINAL.has(j.state));
    add('Bots', [`${live.length} working, ${all.length - live.length} finished in the last week`, ...live.slice(0, 6).map((j) => `“${String(j.goal).slice(0, 60)}”: ${j.state}`)]);
  } catch {}
  try {
    const s = sync.status;
    add('Sync and link', [sync.code() ? `linked; last sync ${s.at ? Math.round((now - s.at) / 60000) + ' min ago' : 'not yet'}, ${s.pending || 0} change${s.pending === 1 ? '' : 's'} waiting to send${s.problem ? '; problem: ' + s.problem.text : ''}` : 'not linked to another device',
      ...(s.others || []).map((o) => `${o.name || o.platform} (${o.platform}): Atlas ${o.app || '?'}${o.outdated ? ' (needs an update to sync everything)' : ''}`)]);
  } catch {}
  try { const d = await brain.device.get(); if (d) add('Device and models', [`${d.cpu || 'PC'}${d.totalRamMb ? `, ${Math.round(d.totalRamMb / 1024)} GB memory` : ''}${(d.gpus || []).length ? ', ' + d.gpus.map((g) => g.name + (g.vramTotalMb > 0 ? ` (${Math.round(g.vramTotalMb / 1024)} GB)` : '')).join(', ') : ''}`, ...(brain.PROVIDERS.filter((p) => p.local).map((p) => `${p.name}: ${brain.modelOf(p) || 'no model set'}`))]); } catch {}
  try { const t = brain.ui.settingsText(); if (t) add('Settings', String(t).split('\n').map((x) => x.replace(/^- /, '')).filter((x) => x.trim())); } catch {}
  const errors = db.chat.filter((m) => m.source === 'error').slice(-3).map((m) => `${Math.round((now - (m.at || now)) / 60000)} min ago: ${String(m.text).slice(0, 140)}`);
  try { for (const j of bots.visible().filter((j) => BOT_FINAL.has(j.state) && j.error && now - j.updatedAt < 86400000).slice(0, 3)) errors.push(`bot “${String(j.goal).slice(0, 40)}”: ${String(j.error).slice(0, 120)}`); } catch {}
  add('Recent errors', errors.length ? errors : ['none']);
  return out.join('\n');
}

// ------------------------------------------------------------------ the tools

const fn = (name, description) => ({ name, description, parameters: { type: 'object', properties: {} } });
export const TOOLS = [
  fn('about_atlas', 'Your own version, what changed in each update (for the user and what you can now do), when you were updated, and which version the user\'s other device runs. Use it for "what version are you", "what\'s new", "what can you do now".'),
  fn('self_status', 'A full check of yourself: abilities (tool groups), permissions, AI services and keys (ready, resting, turned down), bots, sync and the link, the device and models, your settings, recent errors.'),
  fn('whats_around', 'Everything you can see around you on this PC right now: time, battery, network, the window in front, CPU / memory / GPU load, free disk, jobs running, the phone.'),
  fn('check_updates', 'Look for a newer Atlas release now.'),
  fn('update_atlas', 'Download the newest Atlas for Windows, check it, and ask the user to click Install (Atlas restarts). Only when the user asks for the update.'),
];

aware.register({
  id: 'self', forChat: true, forBots: false,
  tools: () => TOOLS,
  run: async (call) => {
    switch (call.name) {
      case 'about_atlas': return { out: about(), step: "Checked what's new in Atlas" };
      case 'self_status': return { out: await selfStatus(), step: 'Checked myself' };
      case 'whats_around': return { out: A.detail(await facts(true)), step: 'Looked around' };
      case 'check_updates': return { out: await check(true), step: 'Checked for updates' };
      case 'update_atlas': return { out: await install(), step: 'Got the update ready' };
      default: return null;
    }
  },
});
aware.use({ self: { aroundLines, briefLines, identity } });

/** Once, after the app's data is loaded: the "I've been updated" line, then a background check now and every 12 h. */
export async function start() {
  try { version = (await host.info()).version || ''; } catch {}
  try { cl = U.parseChangelog((host.self && (await host.self.changelog())) || ''); } catch { cl = []; }
  if (version) {
    const st = U.launchState(pref('selfLast', null), version, db.chat.length > 0 || (db.state && db.state.tasks && db.state.tasks.length > 0));
    if (st.kind === 'updated' || st.kind === 'downgraded') {
      setPref('selfUpdatedAt', Date.now()); setPref('selfFrom', st.from);
      if (st.kind === 'updated') post(U.updatedMessage(cl, st.from, version, 'on your PC'));
    }
    setPref('selfLast', version);
  }
  const tick = () => check(false).catch(() => {});
  setTimeout(tick, 20000);
  const t = setInterval(tick, 60 * 60000); if (t.unref) t.unref();
}
