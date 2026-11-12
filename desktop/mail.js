// Email on the PC: Atlas reads, searches, drafts and sends the user's email over IMAP and SMTP, signed in with an app
// password (the way Gmail, Yahoo, iCloud, AOL, Zoho and Fastmail let a personal app in without a registered sign-in
// app). Outlook.com and Proton don't allow that any more, so for them Atlas reads mail in its bot browser instead.
//
// - The password is encrypted with Windows' own protection for this user (safeStorage), kept here, never synced and
//   never given to the page or the model.
// - Lists fetch headers only; a message's body is fetched when it's read. One connection is kept open for a minute or
//   so between uses, then closed. Everything has a time limit.
// - Sending and deleting are decided by the caller (renderer/mail.js asks the user first); drafts live here until sent.
'use strict';
const T = require('./mailtext');

const OP_MS = 45_000;            // one operation, all in
const IDLE_MS = 90_000;          // close the connection after this long unused
const LIST_MAX = 50;
const BODY_MAX = 12_000;         // characters of a message given to the model
const ATTACH_MAX = 25 * 1024 * 1024;

function timeout(p, ms, what) {
  let t; return Promise.race([p, new Promise((_, no) => { t = setTimeout(() => no(Object.assign(new Error(`${what} took too long`), { code: 'ETIMEDOUT' })), ms); })]).finally(() => clearTimeout(t));
}

/**
 * deps: { load(): text|null, save(text), encrypt(text): string, decrypt(string): text, attachmentsDir(): path,
 *         imapOptions?: extra ImapFlow options (tests), smtpOptions?: extra nodemailer options (tests), now?() }
 */
function createMail(deps) {
  const { ImapFlow } = require('imapflow');
  const { simpleParser } = require('mailparser');
  const nodemailer = require('nodemailer');
  const fs = require('fs'); const path = require('path');
  const now = deps.now || (() => Date.now());

  let account = null;           // { email, name, imap, smtp, user, pass (encrypted), provider, webmail }
  try { const a = JSON.parse(deps.load() || 'null'); if (a && a.email) account = a; } catch {}
  let client = null, clientReady = null, idleTimer = null, folders = null;
  let chain = Promise.resolve();          // one operation at a time on the connection
  const drafts = new Map(); let draftN = 0;
  const parsed = new Map();               // the last few messages read, by id (for attachments and replies)
  let unread = { n: null, at: 0 };

  const preset = () => (account ? { ...T.presetFor(account.email), ...(account.provider ? { name: account.providerName || T.presetFor(account.email).name } : {}) } : null);
  function publicAccount() {
    if (!account) return null;
    const { pass, ...rest } = account; return { ...rest, hasPassword: !!pass };
  }

  // ---------------------------------------------------------------- the connection

  async function close() {
    clearTimeout(idleTimer); idleTimer = null; folders = null;
    const c = client; client = null; clientReady = null;
    if (c) { try { await timeout(c.logout(), 5000, 'Signing out'); } catch { try { c.close(); } catch {} } }
  }
  function touch() { clearTimeout(idleTimer); idleTimer = setTimeout(() => { close(); }, IDLE_MS); if (idleTimer.unref) idleTimer.unref(); }

  async function connect() {
    if (!account || account.webmail) throw new Error('No email account is set up for Atlas.');
    if (client && client.usable) return client;
    if (clientReady) return clientReady;
    const c = new ImapFlow({
      host: account.imap.host, port: account.imap.port, secure: account.imap.secure,
      auth: { user: account.user || account.email, pass: deps.decrypt(account.pass) },
      logger: false, connectionTimeout: 15_000, greetingTimeout: 15_000, socketTimeout: 90_000, disableAutoIdle: true,
      clientInfo: { name: 'Atlas' }, ...(deps.imapOptions || {}),
    });
    c.on('error', () => {});            // a dropped connection: the next use connects again
    c.on('close', () => { if (client === c) { client = null; folders = null; } });
    clientReady = (async () => {
      try { await timeout(c.connect(), 30_000, 'Connecting to the mail server'); client = c; return c; }
      finally { clientReady = null; }
    })();
    return clientReady;
  }

  /** Runs [fn] with the connection, one at a time, with a time limit; reconnects once if the connection had died. */
  function withClient(fn) {
    const run = async () => {
      let lastErr;
      for (let attempt = 0; attempt < 2; attempt++) {
        try { const c = await connect(); const r = await timeout(fn(c), OP_MS, 'The mail server'); touch(); return r; }
        catch (e) {
          lastErr = e;
          const dead = !client || /closed|not available|ECONNRESET|EPIPE|socket/i.test(String(e && (e.message || e.code)));
          if (e && (e.authenticationFailed || /AUTHENTICATIONFAILED|Invalid credentials/i.test(String(e.responseText || e.message)))) break;
          if (!dead || attempt) break;
          await close();
        }
      }
      throw lastErr;
    };
    const p = chain.then(run, run); chain = p.catch(() => {}); return p;
  }

  // ---------------------------------------------------------------- folders

  const ALIASES = { inbox: null, sent: '\\Sent', trash: '\\Trash', bin: '\\Trash', deleted: '\\Trash', archive: '\\Archive', all: '\\All', 'all mail': '\\All', drafts: '\\Drafts', spam: '\\Junk', junk: '\\Junk', starred: '\\Flagged', flagged: '\\Flagged', important: '\\Important' };
  async function folderList(c) {
    if (folders) return folders;
    const list = await c.list();
    folders = list.filter((f) => !(f.flags && (f.flags.has ? f.flags.has('\\Noselect') : [...(f.flags || [])].includes('\\Noselect')))).map((f) => ({ path: f.path, special: f.specialUse || null }));
    return folders;
  }
  /** "inbox", "sent", "trash", "archive", "spam", a folder's own name: its path on the server. */
  async function resolveFolder(c, name) {
    const n = String(name || 'INBOX').trim(); if (!n || /^inbox$/i.test(n)) return 'INBOX';
    const list = await folderList(c);
    const exact = list.find((f) => f.path === n) || list.find((f) => f.path.toLowerCase() === n.toLowerCase());
    if (exact) return exact.path;
    const sp = ALIASES[n.toLowerCase()];
    if (sp) { const f = list.find((x) => x.special === sp); if (f) return f.path; }
    const tail = list.find((f) => f.path.toLowerCase().split(/[/.]/).pop() === n.toLowerCase()); if (tail) return tail.path;
    throw Object.assign(new Error(`There's no folder called "${n}". Folders: ${list.map((f) => f.path).join(', ')}`), { plain: true });
  }
  async function specialFolder(c, special, fallback) {
    const list = await folderList(c); const f = list.find((x) => x.special === special);
    if (f) return f.path;
    if (fallback) { const g = list.find((x) => x.path.toLowerCase() === fallback.toLowerCase()); if (g) return g.path; try { await c.mailboxCreate(fallback); folders = null; return fallback; } catch {} }
    return null;
  }
  async function inFolder(c, folder, fn) {
    const lock = await c.getMailboxLock(folder);
    try { return await fn(); } finally { lock.release(); }
  }

  // ---------------------------------------------------------------- reading

  function summary(folder, m) {
    const e = m.envelope || {};
    const flags = m.flags || new Set();
    return {
      id: T.makeId(folder, m.uid), uid: m.uid, date: e.date || m.internalDate || null,
      from: (e.from || []).map(T.addr).join(', '), to: (e.to || []).map(T.addr).join(', '), subject: e.subject || '',
      unread: !flags.has('\\Seen'), starred: flags.has('\\Flagged'), messageId: e.messageId || null,
    };
  }
  function criteria(c, q, unreadOnly) {
    const out = {};
    if (q && c.capabilities && c.capabilities.has('X-GM-EXT-1')) out.gmraw = q + (unreadOnly && !/is:unread/i.test(q) ? ' is:unread' : '');
    else if (q) {
      const p = T.parseQuery(q);
      if (p.from) out.from = p.from; if (p.to) out.to = p.to; if (p.cc) out.cc = p.cc; if (p.subject) out.subject = p.subject;
      if (p.unread === true) out.seen = false; if (p.unread === false) out.seen = true; if (p.starred) out.flagged = true;
      if (p.since) out.since = new Date(p.since + 'T00:00:00Z'); if (p.before) out.before = new Date(p.before + 'T00:00:00Z');
      if (p.text) out.or = [{ subject: p.text }, { from: p.text }, { body: p.text }];
    }
    if (unreadOnly && !out.gmraw) out.seen = false;
    return Object.keys(out).length ? out : { all: true };
  }

  async function list({ folder, unread_only, query, limit } = {}) {
    const n = Math.max(1, Math.min(LIST_MAX, Number(limit) || 15));
    return withClient(async (c) => {
      const path = await resolveFolder(c, folder);
      return inFolder(c, path, async () => {
        const uids = await c.search(criteria(c, query, !!unread_only), { uid: true }) || [];
        const pick = uids.sort((a, b) => a - b).slice(-n);
        const out = [];
        if (pick.length) for await (const m of c.fetch(pick.join(','), { uid: true, envelope: true, flags: true, internalDate: true }, { uid: true })) out.push(summary(path, m));
        out.sort((a, b) => b.uid - a.uid);
        if (path === 'INBOX' && !query && !unread_only) unread = { n: null, at: 0 };   // read again next time
        return { folder: path, total: uids.length, messages: out };
      });
    });
  }

  async function fetchParsed(id) {
    const p = T.parseId(id); if (!p) throw Object.assign(new Error(`"${id}" isn't an email id (they look like INBOX#1234; get them from email_list).`), { plain: true });
    const key = T.makeId(p.folder, p.uid);
    if (parsed.has(key)) return parsed.get(key);
    const got = await withClient(async (c) => inFolder(c, p.folder, async () => {
      const m = await c.fetchOne(String(p.uid), { uid: true, source: true, flags: true, envelope: true }, { uid: true });
      if (!m) return null;
      return { m, mail: await simpleParser(m.source, { skipImageLinks: true, skipTextToHtml: true }) };
    }));
    if (!got) throw Object.assign(new Error(`That email (${id}) isn't there any more: it may have been moved or deleted.`), { plain: true });
    const entry = { id: key, folder: p.folder, uid: p.uid, flags: got.m.flags || new Set(), mail: got.mail };
    parsed.set(key, entry); while (parsed.size > 8) parsed.delete(parsed.keys().next().value);
    return entry;
  }
  /** "Anna Smith <anna@x.org>, b@y.com" from a parsed address header (no quotes around names). */
  const people = (h) => (h ? [].concat(h).flatMap((x) => x.value || []).map(T.addr).join(', ') : '');
  function bodyText(mail) {
    const t = mail.text && mail.text.trim() ? mail.text : mail.html ? T.htmlToText(mail.html) : '';
    return t.replace(/\r/g, '');
  }

  async function read({ id, full } = {}) {
    const e = await fetchParsed(id); const mail = e.mail;
    const raw = bodyText(mail);
    const s = full ? { text: raw.trim(), hidden: false } : T.stripQuoted(raw);
    let body = s.text; let cut = false;
    if (body.length > BODY_MAX) { body = body.slice(0, BODY_MAX); cut = true; }
    const att = (mail.attachments || []).filter((a) => !a.related);
    return {
      id: e.id, from: people(mail.from), to: people(mail.to), cc: people(mail.cc),
      date: mail.date ? mail.date.toISOString() : null, subject: mail.subject || '', unread: !e.flags.has('\\Seen'),
      body, quotedHidden: s.hidden, cut,
      attachments: att.map((a, i) => ({ index: i + 1, name: a.filename || `attachment-${i + 1}`, type: a.contentType, size: a.size })),
    };
  }

  async function saveAttachment({ id, index } = {}) {
    const e = await fetchParsed(id);
    const att = (e.mail.attachments || []).filter((a) => !a.related);
    const a = att[(Number(index) || 1) - 1];
    if (!a) throw Object.assign(new Error(`That email has ${att.length} attachment${att.length === 1 ? '' : 's'}; there's no number ${index}.`), { plain: true });
    if (a.size > ATTACH_MAX) throw Object.assign(new Error(`That attachment is ${Math.round(a.size / 1048576)} MB, too big to save from here (limit 25 MB).`), { plain: true });
    const dir = deps.attachmentsDir(); fs.mkdirSync(dir, { recursive: true });
    const name = String(a.filename || `attachment-${index}`).replace(/[\\/:*?"<>|\x00-\x1f]+/g, '_').replace(/^\.+/, '').slice(0, 120) || 'attachment';
    let file = path.join(dir, name); const ext = path.extname(name), base = name.slice(0, name.length - ext.length);
    for (let i = 2; fs.existsSync(file) && i < 1000; i++) file = path.join(dir, `${base} (${i})${ext}`);
    if (path.dirname(path.resolve(file)) !== path.resolve(dir)) throw new Error('bad name');
    fs.writeFileSync(file, a.content);
    return { file, name: path.basename(file), size: a.size };
  }

  // ---------------------------------------------------------------- changing things

  async function setFlags({ ids, read: isRead, starred } = {}) {
    const list = T.parseIds(ids); if (!list.length) throw Object.assign(new Error('Give the email ids (from email_list).'), { plain: true });
    return withClient(async (c) => {
      for (const [folder, uids] of group(list)) await inFolder(c, folder, async () => {
        const set = uids.join(',');
        if (isRead === true) await c.messageFlagsAdd(set, ['\\Seen'], { uid: true });
        if (isRead === false) await c.messageFlagsRemove(set, ['\\Seen'], { uid: true });
        if (starred === true) await c.messageFlagsAdd(set, ['\\Flagged'], { uid: true });
        if (starred === false) await c.messageFlagsRemove(set, ['\\Flagged'], { uid: true });
      });
      for (const p of list) parsed.delete(T.makeId(p.folder, p.uid));
      unread = { n: null, at: 0 };
      return list.length;
    });
  }
  function group(list) { const m = new Map(); for (const p of list) { if (!m.has(p.folder)) m.set(p.folder, []); m.get(p.folder).push(p.uid); } return m; }

  async function moveTo({ ids, folder, special, fallback } = {}) {
    const list = T.parseIds(ids); if (!list.length) throw Object.assign(new Error('Give the email ids (from email_list).'), { plain: true });
    return withClient(async (c) => {
      const dest = special ? await specialFolder(c, special, fallback) : await resolveFolder(c, folder);
      if (!dest) return { moved: 0, dest: null };
      let moved = 0;
      for (const [from, uids] of group(list)) {
        if (from === dest) continue;
        await inFolder(c, from, async () => { await c.messageMove(uids.join(','), dest, { uid: true }); });
        moved += uids.length;
      }
      for (const p of list) parsed.delete(T.makeId(p.folder, p.uid));
      unread = { n: null, at: 0 };
      return { moved, dest };
    });
  }
  async function archive({ ids } = {}) {
    // Gmail: archiving is taking it out of the inbox, into All Mail; elsewhere the Archive folder (made if missing)
    const r = await withClient(async (c) => (await folderList(c)).some((f) => f.special === '\\All'));
    return r ? moveTo({ ids, special: '\\All' }) : moveTo({ ids, special: '\\Archive', fallback: 'Archive' });
  }
  async function remove({ ids } = {}) {
    const r = await moveTo({ ids, special: '\\Trash', fallback: 'Trash' });
    return r;
  }

  // ---------------------------------------------------------------- drafts and sending

  async function draft({ to, cc, subject, body, reply_to } = {}) {
    let orig = null;
    if (reply_to) { const e = await fetchParsed(reply_to); orig = e; }
    const toList = T.splitAddresses(to || (orig ? people(orig.mail.replyTo || orig.mail.from) : ''));
    if (!toList || !toList.length) throw Object.assign(new Error(`"${to}" isn't a valid email address. If you only have a name, look it up first (find_contact, or ask the user).`), { plain: true });
    const ccList = cc ? T.splitAddresses(cc) : [];
    if (cc && !ccList) throw Object.assign(new Error(`"${cc}" isn't a valid email address.`), { plain: true });
    const d = {
      id: 'd' + (++draftN), to: toList.join(', '), cc: (ccList || []).join(', '),
      subject: orig ? T.replySubject(subject || orig.mail.subject) : String(subject || '').trim() || '(no subject)',
      body: orig ? T.quoteForReply(body, { date: orig.mail.date, from: people(orig.mail.from), text: T.stripQuoted(bodyText(orig.mail)).text.slice(0, 4000) }) : String(body || '').trim() + '\n',
      inReplyTo: orig ? orig.mail.messageId || null : null,
      references: orig ? [].concat(orig.mail.references || [], orig.mail.messageId ? [orig.mail.messageId] : []).slice(-10) : [],
      replyOf: orig ? orig.id : null, at: now(),
    };
    drafts.set(d.id, d); while (drafts.size > 20) drafts.delete(drafts.keys().next().value);
    return d;
  }
  function getDraft(id) { const d = drafts.get(String(id || '').trim()); return d ? { ...d } : null; }

  async function send({ draft_id } = {}) {
    const d = drafts.get(String(draft_id || '').trim());
    if (!d) throw Object.assign(new Error(`There's no draft "${draft_id}" (drafts are kept until Atlas restarts). Make it again with email_draft.`), { plain: true });
    const t = nodemailer.createTransport({
      host: account.smtp.host, port: account.smtp.port, secure: account.smtp.secure, requireTLS: !account.smtp.secure && !(deps.smtpOptions && deps.smtpOptions.ignoreTLS),
      auth: { user: account.user || account.email, pass: deps.decrypt(account.pass) },
      connectionTimeout: 15_000, greetingTimeout: 15_000, socketTimeout: 60_000, ...(deps.smtpOptions || {}),
    });
    try {
      const info = await timeout(t.sendMail({
        from: account.name ? { name: account.name, address: account.email } : account.email, to: d.to, cc: d.cc || undefined, subject: d.subject, text: d.body,
        inReplyTo: d.inReplyTo || undefined, references: d.references.length ? d.references : undefined,
      }), OP_MS, 'Sending');
      drafts.delete(d.id);
      if (d.replyOf) { const p = T.parseId(d.replyOf); if (p) withClient((c) => inFolder(c, p.folder, () => c.messageFlagsAdd(String(p.uid), ['\\Answered'], { uid: true }))).catch(() => {}); }
      return { to: d.to, subject: d.subject, messageId: info.messageId || null, accepted: (info.accepted || []).length };
    } finally { try { t.close(); } catch {} }
  }

  async function unreadCount({ fresh } = {}) {
    if (!account || account.webmail) return null;
    if (!fresh && unread.n !== null && now() - unread.at < 5 * 60_000) return unread.n;
    const n = await withClient(async (c) => { const s = await c.status('INBOX', { unseen: true }); return s.unseen; });
    unread = { n, at: now() }; return n;
  }

  // ---------------------------------------------------------------- the account

  async function setup({ email, password, name, imapHost, imapPort, smtpHost, smtpPort, user } = {}) {
    const addr = String(email || '').trim();
    if (!T.validEmail(addr)) return { error: "That isn't an email address." };
    const p = T.presetFor(addr);
    await close(); drafts.clear(); parsed.clear(); unread = { n: null, at: 0 };
    if (p.webmail) {
      account = { email: addr, name: String(name || '').trim().slice(0, 60), provider: p.id, providerName: p.name, webmail: p.webmail };
      deps.save(JSON.stringify(account));
      return { ok: true, webmail: p.webmail, note: p.help, account: publicAccount() };
    }
    if (!password) return { error: 'Paste the app password.' };
    const imap = { host: String(imapHost || p.imap.host).trim(), port: Number(imapPort) || p.imap.port, secure: (Number(imapPort) || p.imap.port) === 993 };
    const smtp = { host: String(smtpHost || p.smtp.host).trim(), port: Number(smtpPort) || p.smtp.port, secure: (Number(smtpPort) || p.smtp.port) === 465 };
    if (deps.forceSecure !== undefined) { imap.secure = deps.forceSecure; smtp.secure = deps.forceSecure; }
    const next = { email: addr, name: String(name || '').trim().slice(0, 60), user: String(user || '').trim() || addr, provider: p.id, providerName: p.name,
      imap, smtp, pass: deps.encrypt(p.id === 'gmail' ? String(password).replace(/\s+/g, '') : String(password).trim()) };   // Google shows its app passwords in groups of four
    const old = account; account = next;
    try {
      const n = await withClient(async (c) => { const s = await c.status('INBOX', { unseen: true }); return s.unseen; });
      unread = { n, at: now() };
      deps.save(JSON.stringify(account));
      return { ok: true, unread: n, account: publicAccount() };
    } catch (e) {
      await close(); account = old;
      return { error: e.plain ? e.message : T.friendlyError(e, p) };
    }
  }
  async function forget() { await close(); account = null; drafts.clear(); parsed.clear(); unread = { n: null, at: 0 }; deps.save('null'); return true; }

  /** For the app: run an operation, errors turned into plain words. */
  async function op(name, args = {}) {
    try {
      switch (name) {
        case 'status': return { account: publicAccount(), unread: unread.n };
        case 'setup': return await setup(args);
        case 'forget': return { ok: await forget() };
        case 'list': return { ok: await list(args) };
        case 'read': return { ok: await read(args) };
        case 'saveAttachment': return { ok: await saveAttachment(args) };
        case 'flags': return { ok: await setFlags(args) };
        case 'archive': return { ok: await archive(args) };
        case 'move': return { ok: await moveTo(args) };
        case 'delete': return { ok: await remove(args) };
        case 'draft': return { ok: await draft(args) };
        case 'getDraft': return { ok: getDraft(args.id) };
        case 'send': return { ok: await send(args) };
        case 'unread': return { ok: await unreadCount(args) };
        default: return { error: 'unknown email operation' };
      }
    } catch (e) {
      return { error: e && e.plain ? e.message : T.friendlyError(e, preset()) };
    }
  }

  return { op, close, account: publicAccount };
}

module.exports = { createMail };
