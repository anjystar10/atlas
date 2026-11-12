// Email, the plain part: which servers a mail address uses, turning HTML into readable text, hiding the quoted history
// under a reply, quoting for a reply, reading a search like "from:anna is:unread", and saying what went wrong in plain
// words. No network here, so all of it is tested in Node (test/logic.test.mjs) and the phone's MailText.kt follows the
// same rules (tools/mail-fixtures.json holds the shared cases).
'use strict';

// ---------------------------------------------------------------- which servers

/**
 * Known providers by address domain. `webmail`: the provider no longer lets apps sign in with a password over IMAP
 * (Outlook.com turned it off in September 2024; Proton needs its Bridge app), so Atlas reads it in its bot browser.
 */
const PROVIDERS = [
  { id: 'gmail', name: 'Gmail', domains: ['gmail.com', 'googlemail.com'], imap: ['imap.gmail.com', 993, true], smtp: ['smtp.gmail.com', 465, true],
    help: 'Gmail needs an app password, not your normal password: turn on 2-Step Verification, then make one at https://myaccount.google.com/apppasswords and paste its 16 letters here.' },
  { id: 'yahoo', name: 'Yahoo Mail', domains: ['yahoo.com', 'ymail.com', 'rocketmail.com', 'yahoo.co.uk', 'yahoo.fr'], imap: ['imap.mail.yahoo.com', 993, true], smtp: ['smtp.mail.yahoo.com', 465, true],
    help: 'Yahoo needs an app password: Yahoo Account Security → Generate app password, then paste it here.' },
  { id: 'icloud', name: 'iCloud Mail', domains: ['icloud.com', 'me.com', 'mac.com'], imap: ['imap.mail.me.com', 993, true], smtp: ['smtp.mail.me.com', 587, false],
    help: 'iCloud needs an app-specific password: account.apple.com → Sign-In and Security → App-Specific Passwords.' },
  { id: 'aol', name: 'AOL Mail', domains: ['aol.com'], imap: ['imap.aol.com', 993, true], smtp: ['smtp.aol.com', 465, true],
    help: 'AOL needs an app password: Account Security → Generate app password.' },
  { id: 'zoho', name: 'Zoho Mail', domains: ['zoho.com', 'zohomail.com'], imap: ['imap.zoho.com', 993, true], smtp: ['smtp.zoho.com', 465, true],
    help: 'With two-factor sign-in on, Zoho needs an app-specific password (Zoho Accounts → Security → App Passwords).' },
  { id: 'fastmail', name: 'Fastmail', domains: ['fastmail.com', 'fastmail.fm'], imap: ['imap.fastmail.com', 993, true], smtp: ['smtp.fastmail.com', 465, true],
    help: 'Fastmail needs an app password: Settings → Privacy & Security → App passwords.' },
  { id: 'gmx', name: 'GMX', domains: ['gmx.com', 'gmx.net', 'gmx.de'], imap: ['imap.gmx.com', 993, true], smtp: ['mail.gmx.com', 587, false],
    help: 'In GMX settings, allow access through POP3 and IMAP first.' },
  { id: 'outlook', name: 'Outlook.com', domains: ['outlook.com', 'hotmail.com', 'live.com', 'msn.com', 'outlook.co.uk', 'hotmail.co.uk'],
    webmail: 'https://outlook.live.com/mail/', help: 'Outlook.com stopped letting apps sign in with a password (September 2024), so Atlas reads it in its own browser instead: you sign in there once.' },
  { id: 'proton', name: 'Proton Mail', domains: ['proton.me', 'protonmail.com', 'pm.me'],
    webmail: 'https://mail.proton.me/', help: 'Proton Mail only allows other apps through its paid Bridge app, so Atlas reads it in its own browser instead: you sign in there once.' },
];

function domainOf(email) { const m = String(email || '').trim().toLowerCase().match(/@([^@\s]+)$/); return m ? m[1] : ''; }

/** The servers for [email]: a known provider, or a guess (imap.<domain> / smtp.<domain>) the user can change. */
function presetFor(email) {
  const d = domainOf(email);
  const p = PROVIDERS.find((x) => x.domains.includes(d));
  if (p && p.webmail) return { id: p.id, name: p.name, webmail: p.webmail, help: p.help, known: true };
  if (p) return { id: p.id, name: p.name, imap: { host: p.imap[0], port: p.imap[1], secure: p.imap[2] }, smtp: { host: p.smtp[0], port: p.smtp[1], secure: p.smtp[2] }, help: p.help, known: true };
  return { id: 'other', name: d || 'Email', imap: { host: d ? 'imap.' + d : '', port: 993, secure: true }, smtp: { host: d ? 'smtp.' + d : '', port: 465, secure: true },
    help: 'If your provider uses two-step sign-in, it probably needs an app password rather than your normal one.', known: false };
}

function validEmail(s) { return /^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:"]+$/.test(String(s || '').trim()); }
/** "Anna <a@x.com>, b@y.com" → ['Anna <a@x.com>', 'b@y.com']; null when one of them isn't an address. */
function splitAddresses(s) {
  const parts = String(s || '').split(/[,;]\s*(?=(?:[^"]*"[^"]*")*[^"]*$)/).map((x) => x.trim()).filter(Boolean);
  for (const p of parts) { const m = p.match(/<([^>]+)>\s*$/); if (!validEmail(m ? m[1] : p)) return null; }
  return parts;
}

// ---------------------------------------------------------------- HTML → text

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…', rsquo: '’', lsquo: '‘', rdquo: '”', ldquo: '“', copy: '©', reg: '®', trade: '™', bull: '•', middot: '·', euro: '€', pound: '£',
  eacute: 'é', egrave: 'è', ecirc: 'ê', euml: 'ë', aacute: 'á', agrave: 'à', acirc: 'â', auml: 'ä', aring: 'å', oacute: 'ó', ograve: 'ò', ocirc: 'ô', ouml: 'ö', oslash: 'ø', iacute: 'í', igrave: 'ì', iuml: 'ï', uacute: 'ú', ugrave: 'ù', uuml: 'ü', ntilde: 'ñ', ccedil: 'ç', szlig: 'ß', Eacute: 'É', Agrave: 'À', Auml: 'Ä', Ouml: 'Ö', Uuml: 'Ü' };
function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === '#') { const n = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10); return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : ''; }
    const v = ENT[e] !== undefined ? ENT[e] : ENT[e.toLowerCase()]; return v === undefined ? m : v;
  });
}
/** Readable text from an email's HTML: no styles or scripts, paragraphs and lists kept, links as "text (url)". */
function htmlToText(html) {
  let s = String(html || '');
  s = s.replace(/<!--[\s\S]*?-->/g, '').replace(/<(script|style|head|title)\b[\s\S]*?<\/\1\s*>/gi, '');
  s = s.replace(/<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)<\/a\s*>/gi, (m, href, inner) => {
    const t = inner.replace(/<[^>]+>/g, '').trim(); const u = decodeEntities(href.trim());
    if (!t) return ''; if (/^mailto:/i.test(u) || t === u || !/^https?:/i.test(u)) return t; return `${t} (${u})`;
  });
  s = s.replace(/<br\s*\/?>/gi, '\n').replace(/<li\b[^>]*>/gi, '\n• ').replace(/<\/(p|div|h[1-6]|tr|table|ul|ol|blockquote)\s*>/gi, '\n').replace(/<(p|div|h[1-6]|tr|table|ul|ol|blockquote)\b[^>]*>/gi, '\n').replace(/<\/t[dh]\s*>/gi, '\t');
  s = s.replace(/<[^>]+>/g, '');
  s = decodeEntities(s).replace(/ /g, ' ').replace(/\r/g, '');
  s = s.split('\n').map((l) => l.replace(/[ \t]+/g, ' ').trim()).join('\n');
  return s.replace(/\n{3,}/g, '\n\n').trim();
}

// ---------------------------------------------------------------- the quoted history under a reply

const QUOTE_HEADS = [
  /^On .{4,200}(wrote|écrit|schrieb|escribió):\s*$/i,
  /^-{2,}\s*(Original Message|Forwarded message|Mensaje original|Message d'origine)\s*-{2,}\s*$/i,
  /^_{5,}\s*$/,
  /^From:\s.+$/i,
];
/**
 * The new part of a message, without the quoted history under it ("On … wrote:", "> …", "-----Original Message-----",
 * an Outlook "From:" block). Returns { text, hidden } — hidden: some history was left out. A message that is only
 * quotes (a forward) is kept whole.
 */
function stripQuoted(text) {
  const lines = String(text || '').replace(/\r/g, '').split('\n');
  let cut = -1;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].trim();
    if (QUOTE_HEADS.some((r) => r.test(l))) {
      // "From:" counts only as the start of an Outlook block (Sent:/To:/Subject: in the next lines)
      if (/^From:/i.test(l) && !lines.slice(i + 1, i + 5).some((x) => /^(Sent|Date|To|Subject):/i.test(x.trim()))) continue;
      // "On … wrote:" may be wrapped over two lines
      cut = i; break;
    }
    if (/^On .{4,120}$/i.test(l) && i + 1 < lines.length && /(wrote|écrit|schrieb|escribió):\s*$/i.test(lines[i + 1].trim())) { cut = i; break; }
    if (l.startsWith('>')) {
      let j = i; while (j < lines.length && (lines[j].trim().startsWith('>') || !lines[j].trim())) j++;
      if (j >= lines.length || lines.slice(j).every((x) => !x.trim())) { cut = i; break; }
    }
  }
  if (cut < 0) return { text: String(text || '').trim(), hidden: false };
  const head = lines.slice(0, cut).join('\n').trim();
  if (!head) return { text: String(text || '').trim(), hidden: false };
  return { text: head, hidden: true };
}

/** The body of a reply: what they write, then "On <date>, <name> wrote:" and the original, each line with "> ". */
function quoteForReply(body, original) {
  const when = original.date ? new Date(original.date) : null;
  const d = when && !isNaN(when) ? when.toUTCString().replace(/:\d\d GMT$/, ' GMT') : 'an earlier date';
  const who = original.from || 'they';
  const quoted = String(original.text || '').replace(/\r/g, '').trim().split('\n').map((l) => '> ' + l).join('\n');
  return `${String(body || '').trim()}\n\nOn ${d}, ${who} wrote:\n${quoted}\n`;
}
function replySubject(s) { const t = String(s || '').trim(); return /^(re|aw|sv|antw)\s*:/i.test(t) ? t : `Re: ${t || '(no subject)'}`; }

// ---------------------------------------------------------------- searches

/**
 * "from:anna subject:invoice is:unread after:2026-11-01 lunch" → { from, to, subject, unread, starred, since, before,
 * text }. Gmail gets the words as they are (it understands its own search); other servers get these parts.
 */
function parseQuery(q) {
  const out = {}; const rest = [];
  const re = /(\w+):("([^"]*)"|\S+)|"([^"]*)"|(\S+)/g; let m;
  while ((m = re.exec(String(q || '')))) {
    if (m[1]) {
      const k = m[1].toLowerCase(), v = m[3] !== undefined ? m[3] : m[2];
      if (k === 'from' || k === 'to' || k === 'subject' || k === 'cc') out[k] = v;
      else if (k === 'is' && /^unread$/i.test(v)) out.unread = true;
      else if (k === 'is' && /^read$/i.test(v)) out.unread = false;
      else if (k === 'is' && /^(starred|flagged)$/i.test(v)) out.starred = true;
      else if ((k === 'after' || k === 'since' || k === 'newer') && /^\d{4}[-/]\d{1,2}[-/]\d{1,2}$/.test(v)) out.since = v.replace(/\//g, '-');
      else if ((k === 'before' || k === 'older') && /^\d{4}[-/]\d{1,2}[-/]\d{1,2}$/.test(v)) out.before = v.replace(/\//g, '-');
      else rest.push(m[0]);
    } else rest.push(m[4] !== undefined ? m[4] : m[5]);
  }
  if (rest.length) out.text = rest.join(' ');
  return out;
}

// ---------------------------------------------------------------- ids: "<folder>#<uid>"

function makeId(folder, uid) { return `${folder}#${uid}`; }
function parseId(id) {
  const s = String(id || '').trim(); const i = s.lastIndexOf('#');
  if (i <= 0) return null; const uid = Number(s.slice(i + 1));
  return Number.isInteger(uid) && uid > 0 ? { folder: s.slice(0, i), uid } : null;
}
function parseIds(v) { const list = Array.isArray(v) ? v : String(v || '').split(/[\s,]+/); return list.map(parseId).filter(Boolean); }

// ---------------------------------------------------------------- what went wrong

function friendlyError(err, preset) {
  const msg = String((err && (err.responseText || err.response || err.message)) || err || '');
  const code = err && (err.code || err.serverResponseCode);
  if ((err && err.authenticationFailed) || /AUTHENTICATIONFAILED|Invalid credentials|authentication failed|LOGIN failed|Application-specific password|535|Username and Password not accepted|AUTHENTICATE failed/i.test(msg) || code === 'EAUTH') {
    return `${preset && preset.name ? preset.name : 'The mail server'} turned down the password. ${preset && preset.help ? preset.help : 'Check the address and password.'}`;
  }
  if (code === 'ENOTFOUND' || code === 'EAI_AGAIN' || /getaddrinfo/i.test(msg)) return "Couldn't find the mail server. Check the server name, or your internet connection.";
  if (code === 'ECONNREFUSED' || code === 'ETIMEDOUT' || code === 'ECONNRESET' || /timed? ?out|ETIMEDOUT|socket hang up/i.test(msg)) return "Couldn't reach the mail server. Check your internet connection (and the server name and port).";
  if (/certificate|self.signed|CERT_/i.test(msg)) return "The mail server's security certificate didn't check out, so Atlas didn't connect.";
  if (/Mailbox doesn't exist|NONEXISTENT|Unknown Mailbox|no such mailbox/i.test(msg)) return "That folder doesn't exist on the mail server.";
  return msg ? `The mail server said: ${msg.slice(0, 200)}` : 'Something went wrong with the mail server.';
}

// ---------------------------------------------------------------- lines for the model

function shortDate(d, now = Date.now()) {
  const t = d ? new Date(d) : null; if (!t || isNaN(t)) return '';
  const same = new Date(now).toDateString() === t.toDateString();
  const iso = t.toISOString();
  return same ? `today ${iso.slice(11, 16)} UTC` : iso.slice(0, 10);
}
function addr(a) {
  if (!a) return ''; const n = (a.name || '').trim(); if (!n) return a.address || '';
  return /[,;<>"@()]/.test(n) ? `"${n.replace(/["\\]/g, '')}" <${a.address || ''}>` : `${n} <${a.address || ''}>`;   // "Smith, Anna" stays one address
}
/** One line per message for email_list: id · date · from · subject [unread] [starred]. */
function listLine(m, now) {
  const flags = [m.unread ? 'unread' : '', m.starred ? 'starred' : '', m.attachments ? 'attachment' : ''].filter(Boolean);
  return `- ${m.id} · ${shortDate(m.date, now)} · ${m.from || '(unknown sender)'} · ${m.subject || '(no subject)'}${flags.length ? ' [' + flags.join(', ') + ']' : ''}`;
}

module.exports = { PROVIDERS, presetFor, domainOf, validEmail, splitAddresses, htmlToText, decodeEntities, stripQuoted, quoteForReply, replySubject, parseQuery, makeId, parseId, parseIds, friendlyError, shortDate, addr, listLine };
