// What's around Atlas right now: the time, battery, network, sound mode, what's on screen, load… (the PC side of
// core/Around.kt; both follow tools/around-fixtures.json word for word).
//
// - nowLine(): one short line that goes in front of every turn.
// - alerts(): the few things worth saying out loud ("battery at 9% and the trading bot is running"), each with a key
//   so Atlas mentions it once (markNew), not every turn.
// - detail(): everything, for the whats_around tool, so the brief stays short.
//
// Facts only come from what this device already lets Atlas see. They go to the AI service in the turn that uses
// them and nowhere else. Pure functions: the app gathers the facts and passes them in.

export const SECTION_MAX = 450;

const has = (v) => v !== null && v !== undefined && v !== '';
const pct = (n) => Math.round(Number(n));
const NET = { wifi: 'Wi-Fi', mobile: 'mobile data', ethernet: 'Ethernet', none: 'offline' };

/** f = { time, zone, battery, charging, network, dnd, ringer, audio, foreground, notifications, cpu, ram, gpu, thermal } */
export function nowLine(f = {}) {
  const p = [];
  if (has(f.time)) p.push(f.time + (has(f.zone) ? ` (${f.zone})` : ''));
  if (has(f.battery)) p.push(`battery ${pct(f.battery)}%${f.charging === true ? ' charging' : ''}`);
  if (has(f.network)) p.push(NET[f.network] || String(f.network));
  if (f.dnd === true) p.push('Do Not Disturb');
  else if (f.ringer === 'silent' || f.ringer === 'vibrate') p.push(f.ringer);
  if (f.audio === 'headphones' || f.audio === 'bluetooth') p.push(f.audio === 'bluetooth' ? 'Bluetooth audio' : 'headphones');
  if (has(f.foreground)) p.push('in ' + String(f.foreground).slice(0, 40));
  if (Number(f.notifications) > 0) p.push(`${pct(f.notifications)} notification${pct(f.notifications) === 1 ? '' : 's'}`);
  const load = [];
  if (Number(f.cpu) >= 70) load.push(`CPU ${pct(f.cpu)}%`);
  if (Number(f.ram) >= 70) load.push(`RAM ${pct(f.ram)}%`);
  if (Number(f.gpu) >= 70) load.push(`GPU ${pct(f.gpu)}%`);
  if (load.length) p.push(load.join(', '));
  if (f.thermal && !['cool', 'unknown', 'slightly warm'].includes(f.thermal)) p.push(f.thermal);
  return p.join(' · ');
}

/** c = { botsRunning, tradingRunning } → [{ key, text }] */
export function alerts(f = {}, c = {}) {
  const out = [];
  const bots = Number(c.botsRunning) || 0;
  const running = c.tradingRunning ? 'the trading bot is' : bots === 1 ? 'a bot is' : `${bots} bots are`;
  if (has(f.battery) && f.charging === false) {
    const b = pct(f.battery);
    if (b <= 15 && (bots > 0 || c.tradingRunning)) out.push({ key: 'battery-low', text: `Battery at ${b}% and not charging while ${running} running: it stops if the device dies.` });
    else if (b <= 10) out.push({ key: 'battery-low', text: `Battery at ${b}% and not charging.` });
  }
  if (f.network === 'none') out.push({ key: 'offline', text: bots > 0 || c.tradingRunning ? `No internet: ${running} running but can't reach anything until it's back.` : 'No internet right now: only the offline brain works.' });
  if (has(f.storageFreeGb) && Number(f.storageFreeGb) < 1) out.push({ key: 'storage', text: `Storage almost full: ${Number(f.storageFreeGb).toFixed(1)} GB free, so downloads and recordings may fail.` });
  if (['hot', 'very hot', 'dangerously hot'].includes(f.thermal)) out.push({ key: 'thermal', text: `The device is ${f.thermal}: heavy work is held back.` });
  if (Number(f.cpu) >= 90 || Number(f.ram) >= 90) out.push({ key: 'load', text: `The PC is under heavy load (CPU ${pct(f.cpu || 0)}%, RAM ${pct(f.ram || 0)}%).` });
  return out;
}

/**
 * Marks alerts not mentioned yet. [seen]: keys already told. Returns the lines and the new seen list: only keys still
 * true stay, so an alert that clears and comes back is new again.
 */
export function markNew(list, seen = []) {
  const s = new Set(seen);
  const lines = list.map((a) => a.text + (s.has(a.key) ? '' : ' (NEW: mention it briefly if it matters)'));
  return { lines, seen: list.map((a) => a.key) };
}

/** The brief's "Around you" part: the now line and the alerts. Kept under [SECTION_MAX]. */
export function section(f, c, seen) {
  const n = nowLine(f);
  const m = markNew(alerts(f, c), seen);
  const lines = [...(n ? ['Now: ' + n] : []), ...m.lines];
  const kept = [];
  let len = 0;
  for (const l of lines) { if (len + l.length + 1 > SECTION_MAX) break; kept.push(l); len += l.length + 1; }
  return { lines: kept, seen: m.seen };
}

/** Everything known, for the whats_around tool. */
export function detail(f = {}) {
  const L = [];
  const row = (label, v) => { if (has(v)) L.push(`${label}: ${v}`); };
  row('Time', has(f.time) ? f.time + (has(f.zone) ? ` (${f.zone})` : '') : '');
  row('Battery', has(f.battery) ? `${pct(f.battery)}%${f.charging === true ? ', charging' : f.charging === false ? ', not charging' : ''}` : '');
  row('Network', has(f.network) ? (NET[f.network] || f.network) + (f.metered === true ? ' (metered)' : '') : '');
  row('Sound', f.dnd === true ? 'Do Not Disturb on' + (has(f.ringer) ? `, ringer ${f.ringer}` : '') : has(f.ringer) ? `ringer ${f.ringer}` : '');
  row('Audio out', f.audio === 'bluetooth' ? 'Bluetooth' : f.audio === 'headphones' ? 'headphones' : f.audio === 'speaker' ? 'speaker' : '');
  row('Screen', f.screenOn === true ? 'on' : f.screenOn === false ? 'off' : '');
  row('In front', f.foreground);
  row('Active window', f.activeWindow);
  row('Notifications', Number(f.notifications) > 0 ? `${pct(f.notifications)}${Array.isArray(f.notifApps) && f.notifApps.length ? ' (' + f.notifApps.slice(0, 6).join(', ') + ')' : ''}` : f.notifications === 0 ? 'none' : '');
  row('Next alarm', f.nextAlarm);
  row('Next events', Array.isArray(f.nextEvents) ? f.nextEvents.slice(0, 3).join('; ') : f.nextEvents);
  row('Approximate location', f.location);
  row('Storage free', has(f.storageFreeGb) ? `${Number(f.storageFreeGb).toFixed(1)} GB` : '');
  row('Temperature', f.thermal);
  row('CPU', has(f.cpu) ? `${pct(f.cpu)}%` : '');
  row('Memory', has(f.ram) ? `${pct(f.ram)}% used${has(f.ramTotalGb) ? ` of ${Number(f.ramTotalGb).toFixed(0)} GB` : ''}` : '');
  row('GPU', has(f.gpu) ? `${pct(f.gpu)}%${has(f.gpuName) ? ' (' + f.gpuName + ')' : ''}` : '');
  row('Jobs running', f.jobs);
  row('Other device', f.other);
  return L.length ? L.join('\n') : 'Nothing known about the surroundings right now.';
}
