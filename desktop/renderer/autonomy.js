// Atlas autonomy: when Atlas acts on its own and when it asks. One rule set for the PC; the phone's copy
// (AutonomyRules.kt) is held to the same answers by tools/autonomy-fixtures.json (desktop/test/make-autonomy-fixtures.mjs).
//
// Pure: no app imports, no clock, no storage. The state it reads is a plain object kept inside the bot policy
// (botx.js / BotExtras.kt), so it syncs between the devices with it:
//   policy.level         'careful' | 'balanced' | 'autopilot'   (missing: balanced)
//   policy.botLevels     { botId: level }                        (a bot can only be the same or more careful)
//   policy.grants        [{ kind, scope: 'always'|'today'|'bot'|'mission', botId?, missionId?, day?, at }]
//   policy.recipients    ['someone@x.com', '+2348012345678', ...]  people Atlas has sent to with the user's OK
//   policy.suggested     ['message_send', ...]                    kinds already offered "always allow?" once
//   policy.modes         the older per-category ask / allow / deny choices (still honoured: allow = an always grant, deny = never)
//
// The safety rules (never weakened here): only the user's own tap or own message makes a grant; the high tier always
// asks and no grant or level covers it; money (mustAsk) always asks; content from pages, emails or bots never approves.

export const LEVELS = ['careful', 'balanced', 'autopilot'];
export const DEFAULT_LEVEL = 'balanced';
export const LEVEL_LABELS = {
  careful: 'Careful: asks before anything that changes things (like before)',
  balanced: 'Balanced: small things just happen (with Undo); asks once per kind of bigger thing; always asks for money, deleting, new people and security',
  autopilot: 'Autopilot: only asks for money, deleting, new people and security',
};
export const TIERS = ['free', 'low', 'medium', 'high'];
export const UNDO_MS = 10_000;
/** How long an approval raised while the user is away stays answerable. */
export const ASK_TTL_MS = { chat: 30 * 60_000, bot: 12 * 3600_000, laptop: 12 * 3600_000 };
export const SUGGEST_AFTER = 3;

/** Every kind of action that could need an OK: its tier and how it's described. The one table both devices follow. */
export const KINDS = {
  // free: reading, searching, drafting, organising, own notes and reminders, demo trading
  read: { tier: 'free', label: 'Read or look things up' },
  draft: { tier: 'free', label: 'Write a draft' },
  organize: { tier: 'free', label: 'Organise (mark read, move, label)' },
  open_app: { tier: 'free', label: 'Open an app or page' },
  note: { tier: 'free', label: 'Save a note or reminder' },
  demo_trade: { tier: 'free', label: 'Trade on a demo or paper account' },
  // low: Atlas just does it, with 10 seconds to Undo
  screen: { tier: 'low', label: 'Use your screen to work in an app' },
  trash_email: { tier: 'low', label: 'Move emails to the trash' },
  reminder_change: { tier: 'low', label: 'Cancel or delete one of your reminders' },
  // medium: asks once per kind; after "always allow" it just happens (with Undo)
  message_send: { tier: 'medium', label: 'Send a message or email to someone you have messaged before' },
  message_reply: { tier: 'medium', label: 'Reply in a conversation you already have' },
  publish: { tier: 'medium', label: 'Post or publish something' },
  terms: { tier: 'medium', label: 'Accept terms or sign up' },
  sign_in: { tier: 'medium', label: 'Sign in with a saved password' },
  command: { tier: 'medium', label: 'Run a command that changes things' },
  write_file: { tier: 'medium', label: 'Write or overwrite a file' },
  calendar_change: { tier: 'medium', label: 'Change or delete a calendar event' },
  forget_memory: { tier: 'medium', label: 'Delete a saved memory' },
  other: { tier: 'medium', label: 'Anything else that changes things' },
  // high: always asks, whatever the level or grants
  message_new: { tier: 'high', label: 'Message someone Atlas has never sent to' },
  purchase: { tier: 'high', label: 'Buy or pay' },
  live_trade: { tier: 'high', label: 'A LIVE (real money) trade outside your pre-approved limits' },
  delete: { tier: 'high', label: 'Delete or overwrite something for good' },
  account: { tier: 'high', label: 'Change a password, permission or security setting' },
  install: { tier: 'high', label: 'Install an app or software' },
};
export const KIND_IDS = Object.keys(KINDS);
export const tierOf = (kind) => (KINDS[kind] || KINDS.other).tier;
export const kindLabel = (kind) => (KINDS[kind] || KINDS.other).label;

/** The older approval categories (botx.js CATEGORIES) as kinds. */
export const CATEGORY_KIND = { message: 'message_send', publish: 'publish', purchase: 'purchase', delete: 'delete', account: 'account', terms: 'terms', command: 'command', screen: 'screen', other: 'other' };
/** The category a kind's "always / never" choice is stored under in the older modes, so both stay in step. */
export const KIND_CATEGORY = { message_send: 'message', message_reply: 'message', message_new: 'message', publish: 'publish', purchase: 'purchase', live_trade: 'purchase',
  delete: 'delete', forget_memory: 'delete', calendar_change: 'delete', write_file: 'delete', trash_email: 'delete', reminder_change: 'delete', account: 'account', sign_in: 'account',
  install: 'command', terms: 'terms', command: 'command', screen: 'screen', other: 'other' };

const RISKY_SCHEMES = ['tel:', 'sms:', 'smsto:', 'mailto:', 'mms:', 'whatsapp:'];

/** Normalises an email address or phone number so "Bob@X.com" and "bob@x.com", "+234 801…" and "+234801…" match. */
export function normRecipient(r) {
  const s = String(r || '').trim().toLowerCase();
  if (!s) return '';
  const email = s.match(/[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/);
  if (email) return email[0];
  const digits = s.replace(/[^\d+]/g, '');
  return digits.replace(/\D/g, '').length >= 6 ? digits.replace(/(?!^)\+/g, '') : s.replace(/\s+/g, ' ');
}
/** Every recipient named in a "to" field (commas, semicolons, "Name <a@b.c>"). */
export function recipientsIn(to) {
  return String(to || '').split(/[,;]+/).map((p) => { const m = p.match(/<([^>]+)>/); return normRecipient(m ? m[1] : p); }).filter(Boolean);
}
/** All of them already known (sent to before with the user's OK)? An empty list is never "known". */
export function allKnown(recipients, known) {
  const set = new Set((known || []).map(normRecipient));
  return recipients.length > 0 && recipients.every((r) => set.has(normRecipient(r)));
}

/**
 * The kind of an action from the tool, its arguments and what it says (category: the older guess, if already made).
 * ctx: { known: [recipients], replyThread: bool, live: bool, money: bool }
 */
export function kindFor(tool, args = {}, category = null, ctx = {}) {
  const a = args || {};
  switch (tool) {
    case 'email_send': return ctx.replyThread ? 'message_reply' : (allKnown(recipientsIn(ctx.to || a.to), ctx.known) ? 'message_send' : 'message_new');
    case 'send_sms': return allKnown(recipientsIn(a.to), ctx.known) ? 'message_send' : 'message_new';
    case 'reply_message': return 'message_reply';
    case 'email_delete': return 'trash_email';
    case 'email_archive': case 'email_mark': case 'email_move': case 'mark_read': return 'organize';
    case 'email_draft': return 'draft';
    case 'forget': return 'forget_memory';
    case 'update_reminder': return ['delete', 'cancel'].includes(a.action) ? 'reminder_change' : 'note';
    case 'calendar_change': return 'calendar_change';
    case 'run_command': return 'command';
    case 'write_file': return 'write_file';
    case 'browser_fill_login': return ctx.money ? 'purchase' : 'sign_in';
    case 'open_link': return RISKY_SCHEMES.some((s) => String(a.url || '').trim().toLowerCase().startsWith(s)) ? 'message_send' : 'open_app';
    // only LIVE orders the user hasn't pre-approved ever reach here as needing an OK; money always means LIVE
    case 'trade_order': case 'trade_close': case 'trade_plan': return ctx.live || ctx.money ? 'live_trade' : 'demo_trade';
    default: break;
  }
  if (ctx.money) return 'purchase';
  if (category && CATEGORY_KIND[category]) {
    // "install" said in a command-ish action is the high kind, not just a command
    if (category === 'command' && /\binstall\b/i.test(String(ctx.what || ''))) return 'install';
    return CATEGORY_KIND[category];
  }
  return 'other';
}

const rank = (lv) => Math.max(0, LEVELS.indexOf(lv));
export const levelOf = (policy) => (LEVELS.includes(policy && policy.level) ? policy.level : DEFAULT_LEVEL);
/** The level a bot works at: its own choice, but never bolder than the global level. */
export function effectiveLevel(policy, botId) {
  const g = levelOf(policy);
  const b = botId && policy && policy.botLevels ? policy.botLevels[botId] : null;
  return LEVELS.includes(b) && rank(b) < rank(g) ? b : g;
}

/** Does a grant cover this action? (day: 'YYYY-MM-DD' local, for "today" grants) */
export function grantCovers(g, kind, { botId = null, missionId = null, day = null } = {}) {
  if (!g || g.kind !== kind) return false;
  switch (g.scope) {
    case 'always': return true;
    case 'today': return !!day && g.day === day;
    case 'bot': return !!botId && g.botId === botId;
    case 'mission': return !!missionId && g.missionId === missionId;
    default: return false;
  }
}
/** The grant that covers it, if any. */
export function coveringGrant(policy, kind, where = {}) {
  return ((policy && policy.grants) || []).find((g) => grantCovers(g, kind, where)) || null;
}

/**
 * What to do with one action.
 * in:  { kind, mustAsk, botId, missionId, day, planKinds }   (planKinds: kinds the user approved with the whole plan)
 * out: { act: 'go' | 'undo' | 'ask' | 'deny', tier, kind, why }
 *   go: just do it (noted in Recent); undo: do it after UNDO_MS unless the user taps Undo; ask: wait for the user; deny: the user never allows it.
 */
export function decide(policy, { kind, mustAsk = false, botId = null, missionId = null, day = null, planKinds = null } = {}) {
  const k = KINDS[kind] ? kind : 'other';
  const tier = mustAsk ? 'high' : tierOf(k);
  const cat = KIND_CATEGORY[k];
  const mode = policy && policy.modes && cat ? policy.modes[cat] : null;
  if (mode === 'deny' && tier !== 'free') return { act: 'deny', tier, kind: k, why: 'you never allow this kind' };
  if (tier === 'free') return { act: 'go', tier, kind: k, why: 'nothing to approve' };
  if (tier === 'high') return { act: 'ask', tier, kind: k, why: mustAsk ? 'money is involved' : 'it always needs your OK' };
  const level = effectiveLevel(policy, botId);
  const granted = !!coveringGrant(policy, k, { botId, missionId, day }) || mode === 'allow' || (Array.isArray(planKinds) && planKinds.includes(k));
  if (level === 'careful') return granted ? { act: 'go', tier, kind: k, why: 'you allowed this kind' } : { act: 'ask', tier, kind: k, why: 'Careful mode asks' };
  if (tier === 'low') return { act: level === 'autopilot' ? 'go' : 'undo', tier, kind: k, why: 'a small, private action' };
  // medium
  if (granted) return { act: 'undo', tier, kind: k, why: 'you allowed this kind' };
  if (level === 'autopilot') return { act: 'undo', tier, kind: k, why: 'Autopilot' };
  return { act: 'ask', tier, kind: k, why: 'first time for this kind' };
}

/** The kinds a plan's steps need (approving the plan approves these for that mission; high kinds never). */
export function planKindsOf(steps, guess) {
  const out = [];
  for (const s of steps || []) {
    const text = typeof s === 'string' ? s : (s && s.text) || '';
    const k = kindFor(null, {}, guess ? guess(text) : null, { what: text });
    // only named kinds: a step that says nothing specific ('other') never pre-approves anything
    if (k !== 'other' && (tierOf(k) === 'medium' || tierOf(k) === 'low') && !out.includes(k)) out.push(k);
  }
  return out;
}

/** A new grant ({grant} or {error}); scope 'always' | 'today' | 'bot' | 'mission'. High kinds can't be granted. */
export function makeGrant(kind, scope, { botId = null, missionId = null, day = null, at = 0 } = {}) {
  if (!KINDS[kind]) return { error: 'unknown kind' };
  if (tierOf(kind) === 'high') return { error: `“${kindLabel(kind)}” always asks; it can't be allowed in advance.` };
  if (tierOf(kind) === 'free') return { error: 'That never needs an OK.' };
  if (scope === 'today' && !day) return { error: 'no day' };
  if (scope === 'bot' && !botId) return { error: 'no bot' };
  if (scope === 'mission' && !missionId) return { error: 'no mission' };
  if (!['always', 'today', 'bot', 'mission'].includes(scope)) return { error: 'unknown scope' };
  const g = { kind, scope, at };
  if (scope === 'today') g.day = day; if (scope === 'bot') g.botId = botId; if (scope === 'mission') g.missionId = missionId;
  return { grant: g };
}
/** The grants with this one added (replacing the same kind + scope + target), old "today" grants dropped, capped at 100. */
export function addGrant(grants, g, today = null) {
  const same = (x) => x.kind === g.kind && x.scope === g.scope && (x.botId || null) === (g.botId || null) && (x.missionId || null) === (g.missionId || null);
  const out = (grants || []).filter((x) => !same(x) && !(x.scope === 'today' && today && x.day !== today));
  out.push(g);
  return out.slice(-100);
}
export function revokeGrant(grants, i) { return (grants || []).filter((_, j) => j !== i); }
export function grantText(g, botName = (id) => id) {
  const what = kindLabel(g.kind);
  switch (g.scope) {
    case 'always': return `${what}: always allowed`;
    case 'today': return `${what}: allowed today (${g.day})`;
    case 'bot': return `${what}: allowed for ${botName(g.botId)}`;
    case 'mission': return `${what}: allowed for one mission`;
    default: return what;
  }
}

/**
 * After the user approves a kind (a tap, or their own "yes"): the new count, and whether to offer "Always allow this?"
 * now (once per kind, after SUGGEST_AFTER approvals, never for high or free kinds).
 */
export function afterApproval(counts, suggested, kind) {
  const n = ((counts && counts[kind]) || 0) + 1;
  const offer = n >= SUGGEST_AFTER && tierOf(kind) === 'medium' && !(suggested || []).includes(kind);
  return { count: n, offer };
}

/** Pending approvals into one batch: those "Approve all" may cover, and those that still need their own tap. */
export function batch(pending) {
  const all = (pending || []).filter(Boolean);
  const bulk = all.filter((p) => !p.question && !p.mustAsk && tierOf(p.kind || 'other') !== 'high');
  const single = all.filter((p) => !bulk.includes(p));
  return { bulk, single, title: all.length === 1 ? '1 thing needs your OK' : `${all.length} things need your OK` };
}

/** Is a waiting approval too old to act on? (where: 'chat' | 'bot' | 'laptop') */
export function expired(askedAt, now, where = 'bot') {
  const ttl = ASK_TTL_MS[where] || ASK_TTL_MS.bot;
  return !!askedAt && now - askedAt > ttl;
}

/**
 * Who may change autonomy: only the user's tap in Me, or their own message (consent.yes) for a more careful level.
 * Making Atlas BOLDER from chat needs the user's own message to say so (consent.yes); content never counts.
 */
export function canChangeLevel(from, to, { byTap = false, consentYes = false } = {}) {
  if (!LEVELS.includes(to)) return { ok: false, why: 'unknown level' };
  if (byTap) return { ok: true };
  if (rank(to) <= rank(from)) return { ok: true };
  return consentYes ? { ok: true } : { ok: false, why: "only the user's own words can make Atlas ask less" };
}

/** One short line for the brief, so Atlas knows how freely it may act. */
export function briefLine(policy) {
  const lv = levelOf(policy);
  const n = ((policy && policy.grants) || []).length;
  return `Autonomy: ${lv}${n ? ` (${n} standing permission${n === 1 ? '' : 's'})` : ''}. Small private actions just happen with Undo; money, deleting, new people and security always ask.`;
}
