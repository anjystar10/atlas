// Atlas knows itself: which version it is, what changed in each update, whether a newer one is out (the PC side of
// core/Updates.kt; the two follow the same cases in tools/updates-fixtures.json).
//
// - CHANGELOG.json (repo root, bundled into both apps) is the one source: per version, notes for the user and
//   "abilities" written for Atlas itself.
// - After an update, Atlas says so once in the chat and keeps a short "Recent updates" note in its brief for a few days.
// - latest.json (published by CI on every release build) says what the newest release is; Atlas compares it with
//   what's installed and tells the user. Installing always needs the user's own click.
//
// Pure functions only (no imports): the app passes in what they need, so they run the same in tests.

export const RECENT_DAYS = 5;
export const CHECK_EVERY_MS = 12 * 3600_000;
export const BRIEF_MAX = 700;

/** "0.10.2" → [0, 10, 2]; anything else → null. A "-beta" style suffix is ignored. */
export function parseVersion(v) {
  const m = /^\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(String(v ?? ''));
  return m ? [Number(m[1]), Number(m[2] || 0), Number(m[3] || 0)] : null;
}
/** -1, 0 or 1; an unreadable version is older than any readable one. */
export function compareVersions(a, b) {
  const x = parseVersion(a), y = parseVersion(b);
  if (!x || !y) return x ? 1 : y ? -1 : 0;
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}

/** The changelog's versions, newest first, each cleaned up. Accepts the parsed JSON or its text. */
export function parseChangelog(src) {
  let o = src;
  if (typeof src === 'string') { try { o = JSON.parse(src); } catch { return []; } }
  const list = Array.isArray(o) ? o : (o && Array.isArray(o.versions) ? o.versions : []);
  return list.filter((e) => e && parseVersion(e.version)).map((e) => ({
    version: String(e.version).trim(),
    versionCode: Number.isFinite(Number(e.versionCode)) ? Number(e.versionCode) : 0,
    date: String(e.date || ''),
    released: e.released !== false,
    notes: (Array.isArray(e.notes) ? e.notes : []).map(String).filter(Boolean),
    abilities: (Array.isArray(e.abilities) ? e.abilities : []).map(String).filter(Boolean),
  })).sort((a, b) => compareVersions(b.version, a.version));
}

export const entryFor = (cl, version) => cl.find((e) => compareVersions(e.version, version) === 0) || null;

/** The entries after [from] up to and including [to], newest first (from null: just [to]'s own entry). */
export function between(cl, from, to) {
  if (!from) { const e = entryFor(cl, to); return e ? [e] : []; }
  return cl.filter((e) => compareVersions(e.version, from) > 0 && compareVersions(e.version, to) <= 0);
}

/**
 * What to do at launch. [last]: the version this device last ran (null: never recorded). [hadData]: Atlas was already
 * in use here (a chat or a Log), so a missing [last] means it was updated from a version before this feature.
 * → { kind: 'fresh' | 'same' | 'updated' | 'downgraded', from }
 */
export function launchState(last, current, hadData) {
  if (!last) return hadData ? { kind: 'updated', from: null } : { kind: 'fresh', from: null };
  const c = compareVersions(current, last);
  return c === 0 ? { kind: 'same', from: last } : c > 0 ? { kind: 'updated', from: last } : { kind: 'downgraded', from: last };
}

/** The one chat message after an update, in Atlas's voice. [where]: "on your phone" / "on your PC". */
export function updatedMessage(cl, from, to, where = '') {
  const notes = between(cl, from, to).flatMap((e) => e.notes);
  const head = `I've been updated to ${to}${where ? ' ' + where : ''}` + (from ? ` (from ${from})` : '') + '.';
  if (!notes.length) return head;
  const shown = notes.slice(0, 5);
  return head + ' New:\n' + shown.map((n) => '- ' + n).join('\n') + (notes.length > shown.length ? `\n- …and ${notes.length - shown.length} more (ask me what's new)` : '');
}

/** "You are Atlas 0.11.0 on the phone." Always in the system context. */
export const identityLine = (version, device) => `You are Atlas ${version || '(unknown version)'} on ${device}.`;

/**
 * The brief's lines about Atlas itself: recent updates (for [RECENT_DAYS] after one), the other device's version,
 * an available update. Short: they go in front of every turn.
 * s = { version, device, updatedAt, from, now, others: [{ name, platform, app }], latest, checkedAt }
 */
export function briefLines(cl, s) {
  const out = [];
  const now = s.now || Date.now();
  if (s.updatedAt && now - s.updatedAt < RECENT_DAYS * 86400000) {
    const abil = between(cl, s.from || null, s.version).flatMap((e) => e.abilities);
    const days = Math.floor((now - s.updatedAt) / 86400000);
    out.push(`Updated to ${s.version}${s.from ? ` from ${s.from}` : ''} ${days <= 0 ? 'today' : days === 1 ? 'yesterday' : days + ' days ago'}. New since then (use these; about_atlas has the full list):`);
    for (const a of abil.slice(0, 4)) out.push('  ' + a.slice(0, 160));
  }
  for (const o of s.others || []) {
    if (!o || !o.app) continue;
    const c = compareVersions(o.app, s.version);
    const who = o.platform === 'android' ? 'phone' : o.platform === 'windows' ? 'PC' : (o.name || 'other device');
    if (c !== 0) out.push(`The ${who} runs Atlas ${o.app} (${c < 0 ? 'older than this one: tell the user if it matters' : 'newer than this one'}).`);
  }
  if (s.latest && isNewer(s.latest, { version: s.version, versionCode: s.versionCode })) {
    out.push(`Update available: Atlas ${s.latest.version} (this is ${s.version}). Mention it once if it fits; update_atlas when the user says to.`);
  }
  let text = out.join('\n');
  if (text.length > BRIEF_MAX) text = text.slice(0, text.lastIndexOf('\n', BRIEF_MAX - 2) > 0 ? text.lastIndexOf('\n', BRIEF_MAX - 2) : BRIEF_MAX - 2) + '\n…';
  return text ? text.split('\n') : [];
}

/** The whole story for "what's new" / "what can you do now": newest first, at most [max] versions. */
export function aboutText(cl, s, max = 4) {
  const lines = [identityLine(s.version, s.device)];
  const cur = entryFor(cl, s.version);
  if (cur && cur.date) lines.push(`This version: ${s.version}, released ${cur.date}.`);
  if (s.from) lines.push(`Updated from ${s.from}${s.updatedAt ? ' on ' + new Date(s.updatedAt).toISOString().slice(0, 10) : ''}.`);
  for (const o of s.others || []) if (o && o.app) lines.push(`${o.platform === 'android' ? 'Phone' : o.platform === 'windows' ? 'PC' : o.name || 'Other device'}: Atlas ${o.app}.`);
  if (s.latest) lines.push(isNewer(s.latest, { version: s.version, versionCode: s.versionCode }) ? `Newer release available: ${s.latest.version}.` : `Up to date (newest release: ${s.latest.version}).`);
  for (const e of cl.filter((x) => compareVersions(x.version, s.version) <= 0).slice(0, max)) {
    lines.push('', `${e.version}${e.date ? ' (' + e.date + ')' : ''}:`);
    for (const n of e.notes) lines.push('- ' + n);
    if (e.abilities.length) { lines.push('What you can do because of it:'); for (const a of e.abilities) lines.push('- ' + a); }
  }
  return lines.join('\n');
}

/** latest.json from CI → { version, versionCode, url, sha256, notes, size } or null when it isn't usable. */
export function parseLatest(src) {
  let o = src;
  if (typeof src === 'string') { try { o = JSON.parse(src); } catch { return null; } }
  if (!o || !parseVersion(o.version) || typeof o.url !== 'string' || !/^https:\/\//.test(o.url)) return null;
  const sha = String(o.sha256 || '').toLowerCase();
  return {
    version: String(o.version).trim(),
    versionCode: Number.isFinite(Number(o.versionCode)) ? Number(o.versionCode) : 0,
    url: o.url,
    sha256: /^[0-9a-f]{64}$/.test(sha) ? sha : '',
    size: Number(o.size) || 0,
    notes: (Array.isArray(o.notes) ? o.notes : []).map(String).filter(Boolean),
    builtFrom: String(o.builtFrom || ''),
  };
}

/** Is [latest] newer than what's installed? versionCode decides when both have one (the phone); else the version. */
export function isNewer(latest, installed) {
  if (!latest || !installed) return false;
  if (latest.versionCode > 0 && installed.versionCode > 0 && latest.versionCode !== installed.versionCode) return latest.versionCode > installed.versionCode;
  return compareVersions(latest.version, installed.version) > 0;
}

/** Time for a background check? (Not more than every [CHECK_EVERY_MS]; a clock that went backwards counts as due.) */
export const checkDue = (lastCheck, now) => !lastCheck || now - lastCheck >= CHECK_EVERY_MS || now < lastCheck;

/** Should the user hear about [latest] now? Once per version. */
export const shouldAnnounce = (latest, installed, announcedVersion) => isNewer(latest, installed) && announcedVersion !== latest.version;

/** The one chat line when an update is out. */
export function availableMessage(latest, installed, where = '') {
  const notes = latest.notes.slice(0, 3);
  return `Atlas ${latest.version} is out${where ? ' for ' + where : ''} (you have ${installed.version}).` + (notes.length ? ' New: ' + notes.join('; ') + '.' : '') + ' Say "update Atlas" when you want it.';
}
