// Email, end to end: Atlas's mail engine (mail.js, the real imapflow / mailparser / nodemailer) against a local IMAP
// test server and a real SMTP server (smtp-server), and the tools the model uses (renderer/mail.js) on top, including
// "sending asks first". Run: npm run test:mail
'use strict';
const os = require('os'); const fs = require('fs'); const path = require('path');
const { createImapServer } = require('./fixtures/imap-server');
const { SMTPServer } = require('smtp-server');
const { createMail } = require('../mail');

let passed = 0, failed = 0;
function check(name, ok, info) { if (ok) { passed++; console.log('ok   ' + name); } else { failed++; console.log('FAIL ' + name + (info !== undefined ? ' — ' + (typeof info === 'string' ? info : JSON.stringify(info)).slice(0, 400) : '')); } }

const D = (s) => new Date(s);
const MSGS = {
  plain: `From: Anna Smith <anna@example.org>\nTo: King <king@example.com>\nSubject: Lunch on Friday?\nDate: Mon, 09 Nov 2026 10:00:00 +0000\nMessage-ID: <lunch-1@example.org>\nContent-Type: text/plain; charset=utf-8\n\nHey, are you free for lunch on Friday at 1pm?\nLet me know.\n\nOn Sun, 8 Nov 2026 at 09:00, King <king@example.com> wrote:\n> We should catch up soon.\n> Cheers\n`,
  html: `From: =?UTF-8?B?QmFuayDDgGxlcnRz?= <alerts@bank.example>\nTo: king@example.com\nSubject: =?UTF-8?B?WW91ciBzdGF0ZW1lbnQg4oCUIE5vdmVtYmVy?=\nDate: Tue, 10 Nov 2026 08:30:00 +0000\nMessage-ID: <stmt-2@bank.example>\nMIME-Version: 1.0\nContent-Type: text/html; charset=iso-8859-1\nContent-Transfer-Encoding: quoted-printable\n\n<html><head><style>p{color:red}</style></head><body><p>Caf=E9 &amp; more: your balance is ready.</p><p><a href=3D"https://bank.example/s">View statement</a></p><script>alert(1)</script></body></html>\n`,
  attach: `From: Tolu <tolu@example.net>\nTo: king@example.com\nSubject: The report\nDate: Wed, 11 Nov 2026 07:00:00 +0000\nMessage-ID: <rep-3@example.net>\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary="XX"\n\n--XX\nContent-Type: text/plain; charset=utf-8\n\nReport attached.\n--XX\nContent-Type: application/pdf; name="report.pdf"\nContent-Disposition: attachment; filename="report.pdf"\nContent-Transfer-Encoding: base64\n\nJVBERi0xLjQKJcOkw7zDtsOfCg==\n--XX--\n`,
};

(async () => {
  const imap = createImapServer({ user: 'king@example.com', pass: 'abcd efgh ijkl mnop'.replace(/ /g, ''), folders: [
    { path: 'INBOX', messages: [{ raw: MSGS.plain, date: D('2026-11-09T10:00:00Z') }, { raw: MSGS.html, date: D('2026-11-10T08:30:00Z') }, { raw: MSGS.attach, flags: ['\\Seen'], date: D('2026-11-11T07:00:00Z') }] },
    { path: 'Sent', special: '\\Sent' }, { path: 'Trash', special: '\\Trash' }, { path: 'Archive', special: '\\Archive' },
  ] });
  const imapPort = await imap.listen();
  const sent = [];
  const smtp = new SMTPServer({ secure: false, authOptional: false, allowInsecureAuth: true, disabledCommands: ['STARTTLS'], logger: false,
    onAuth(auth, s, cb) { if (auth.username === 'king@example.com' && auth.password === 'abcdefghijklmnop') cb(null, { user: 'king' }); else cb(new Error('Invalid login: 535 5.7.8 Username and Password not accepted')); },
    onData(stream, s, cb) { let raw = ''; stream.on('data', (d) => (raw += d)); stream.on('end', () => { sent.push({ raw, rcpt: s.envelope.rcptTo.map((r) => r.address) }); cb(); }); } });
  const smtpPort = await new Promise((r) => smtp.listen(0, '127.0.0.1', () => r(smtp.server.address().port)));

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'atlas-mail-'));
  const store = path.join(dir, 'email.json');
  const deps = {
    load: () => (fs.existsSync(store) ? fs.readFileSync(store, 'utf8') : null), save: (t) => fs.writeFileSync(store, t),
    encrypt: (t) => 'enc:' + Buffer.from(t).toString('base64').split('').reverse().join(''), decrypt: (v) => Buffer.from(String(v).slice(4).split('').reverse().join(''), 'base64').toString(),
    attachmentsDir: () => path.join(dir, 'Email attachments'), forceSecure: false, smtpOptions: { ignoreTLS: true },
  };
  const mail = createMail(deps);
  const server = { imapHost: '127.0.0.1', imapPort, smtpHost: '127.0.0.1', smtpPort };

  try {
    // ---- signing in
    const bad = await mail.op('setup', { email: 'king@gmail.com', password: 'wrong-password', ...server, user: 'king@example.com' });
    check('a wrong password is turned down in plain words, with how to make an app password', bad.error && /turned down the password/.test(bad.error) && /apppasswords/.test(bad.error), bad);
    check('a failed sign-in saves nothing', !fs.existsSync(store) || fs.readFileSync(store, 'utf8') === 'null');
    const ol = await mail.op('setup', { email: 'king@outlook.com', password: 'x' });
    check('Outlook.com goes to the bot browser (no password sign-in any more)', ol.ok && ol.webmail === 'https://outlook.live.com/mail/', ol);
    const good = await mail.op('setup', { email: 'king@example.com', password: ' abcdefghijklmnop ', ...server });
    check('signing in with the app password works and counts unread mail', good.ok && good.unread === 2, good);
    check('the password is stored encrypted, never as given', !fs.readFileSync(store, 'utf8').includes('abcdefghijklmnop') && /"pass":"enc:/.test(fs.readFileSync(store, 'utf8')));
    check('the app never sees the password', !JSON.stringify((await mail.op('status')).account).includes('abcd'));

    // ---- the tools, as the model uses them (renderer/mail.js over a stand-in for the app's bridge)
    let confirms = []; let answer = false;
    global.window = { atlasHost: { mail: (op, args) => mail.op(op, args) } };
    const M = await import('../renderer/mail.js');
    const confirm = async (title, detail) => { confirms.push({ title, detail }); return answer; };
    const run = (name, args, opts = {}) => M.run({ name, args }, { confirm, ...opts });
    await M.refresh();
    check("Atlas's instructions say the inbox is connected, with the unread count", /connected to king@example\.com \(2 unread/.test(M.promptLine()), M.promptLine());

    const l = await run('email_list', {});
    check('email_list: newest first, with ids, senders, subjects and unread marks', /showing 3 of 3/.test(l.out) && l.out.indexOf('The report') < l.out.indexOf('Lunch on Friday') && /INBOX#1 .*Anna Smith <anna@example\.org> · Lunch on Friday\? \[unread\]/.test(l.out), l.out);
    check('encoded sender names and subjects come out readable', /Bank Àlerts/.test(l.out) && /Your statement — November/.test(l.out), l.out);
    const listFetches = imap.log.filter((x) => /^UID FETCH/i.test(x));
    check('the list fetch asked for envelopes and flags, not BODY[]', listFetches.length && listFetches.every((x) => !/BODY/i.test(x)), listFetches);
    const u = await run('email_list', { unread_only: true });
    check('unread only', /showing 2 of 2/.test(u.out) && !/The report/.test(u.out), u.out);
    const s = await run('email_search', { query: 'from:anna' });
    check('search from:anna finds Anna\'s email only', /showing 1 of 1/.test(s.out) && /Lunch/.test(s.out), s.out);
    const s2 = await run('email_search', { query: 'statement' });
    check('a plain word searches subject, sender and text', /showing 1 of 1/.test(s2.out) && /statement/.test(s2.out), s2.out);

    const r1 = await run('email_read', { id: 'INBOX#1' });
    check('email_read: the new part, without the quoted history', /free for lunch on Friday at 1pm/.test(r1.out) && !/catch up soon/.test(r1.out) && /Earlier quoted messages left out/.test(r1.out), r1.out);
    check('reading leaves it unread (the user decides)', (await mail.op('status')) && /\[unread\]/.test((await run('email_list', { query: 'from:anna' })).out));
    const r1f = await run('email_read', { id: 'INBOX#1', full: true });
    check('full=true includes the quoted history', /catch up soon/.test(r1f.out));
    const r2 = await run('email_read', { id: 'INBOX#2' });
    check('an HTML-only email becomes readable text: charset decoded, styles and scripts gone, links kept', /Café & more: your balance is ready\./.test(r2.out) && /View statement [([]https:\/\/bank\.example\/s[)\]]/.test(r2.out) && !/alert\(1\)|color:red/.test(r2.out), r2.out);
    check('the email is marked as information, not instructions', /not instructions to you/.test(r2.out));
    const r3 = await run('email_read', { id: 'INBOX#3' });
    check('attachments are listed with their number', /1\. report\.pdf \(application\/pdf/.test(r3.out), r3.out);
    const sa = await run('email_save_attachment', { id: 'INBOX#3', index: 1 });
    const saved = path.join(dir, 'Email attachments', 'report.pdf');
    check('an attachment saves to the Library folder, byte for byte', /Saved report\.pdf/.test(sa.out) && fs.existsSync(saved) && fs.readFileSync(saved).slice(0, 8).toString() === '%PDF-1.4', sa.out);
    const bad2 = await run('email_read', { id: 'INBOX#99' });
    check("a missing email says so plainly", /isn't there any more/.test(bad2.out), bad2.out);
    const bad3 = await run('email_read', { id: 'nonsense' });
    check('a wrong id says what ids look like', /isn't an email id/.test(bad3.out), bad3.out);

    // ---- drafting and sending
    const d = await run('email_draft', { reply_to: 'INBOX#1', body: 'Friday at 1 works. See you then!' });
    check('a reply draft goes to the sender, keeps "Re:", quotes the original, and sends nothing', /Draft d1 is ready \(not sent\)/.test(d.out) && /To: Anna Smith <anna@example\.org>/.test(d.out) && /Subject: Re: Lunch on Friday\?/.test(d.out) && /wrote:\n> Hey, are you free/.test(d.out) && sent.length === 0, d.out);
    check("the bots' approval text names who it goes to", M.approvalFor('email_send', { draft_id: 'd1' }) === 'Send the email “Re: Lunch on Friday?” to Anna Smith <anna@example.org>', M.approvalFor('email_send', { draft_id: 'd1' }));
    answer = false;
    const no = await run('email_send', { draft_id: 'd1' });
    check('sending asks first; a no sends nothing and keeps the draft', /said no/.test(no.out) && sent.length === 0 && confirms.length === 1 && /To: Anna Smith/.test(confirms[0].detail) && /Friday at 1 works/.test(confirms[0].detail), { out: no.out, confirms });
    answer = true;
    const yes = await run('email_send', { draft_id: 'd1' });
    check('a yes sends it over SMTP', /Sent “Re: Lunch on Friday\?” to Anna Smith/.test(yes.out) && sent.length === 1 && sent[0].rcpt[0] === 'anna@example.org', yes.out);
    check('the sent reply threads with the original (In-Reply-To and References)', /In-Reply-To: <lunch-1@example\.org>/i.test(sent[0].raw) && /References: <lunch-1@example\.org>/i.test(sent[0].raw) && /From: king@example\.com/i.test(sent[0].raw), sent[0].raw.slice(0, 600));
    await new Promise((r) => setTimeout(r, 300));
    check('the original is marked answered on the server', imap.boxes.get('INBOX').msgs.find((m) => m.uid === 1).flags.has('\\Answered'));
    const again = await run('email_send', { draft_id: 'd1' });
    check('a sent draft is gone (no double sending)', /There's no draft "d1"/.test(again.out), again.out);
    // the chat's way: the OK is asked in the conversation (aware.addAsk) and answer_approval runs it
    const asks = new Map(); let askN = 0;
    const ask = (what, runIt) => { const ref = 'chat' + (++askN); asks.set(ref, { what, runIt }); return ref; };
    await run('email_draft', { to: 'tolu@example.net', subject: 'In chat', body: 'Asked in the chat.' });
    const pend = await M.run({ name: 'email_send', args: { draft_id: 'd2' } }, { ask });
    check('in the chat, sending waits for a yes in the conversation and shows the email', /Needs the user's OK first \[chat1\]: Send the email “In chat” to tolu@example\.net/.test(pend.out) && /Asked in the chat\./.test(pend.out) && sent.length === 1, pend.out);
    const later = await asks.get('chat1').runIt();
    check('their yes sends it', /Sent “In chat”/.test(later.out) && sent.length === 2, later.out);
    const bot = await run('email_draft', { to: 'tolu@example.net', subject: 'Thanks', body: 'Got it, thanks.' });
    confirms = [];
    const bs = await run('email_send', { draft_id: 'd3' }, { approved: true });
    check("a bot's already-approved send goes without asking again", /Sent “Thanks” to tolu@example\.net/.test(bs.out) && confirms.length === 0 && sent.length === 3, bs.out);
    const badTo = await run('email_draft', { to: 'Anna', body: 'hi' });
    check('a name that isn\'t an address is refused, with what to do', /isn't a valid email address/.test(badTo.out) && /find_contact/.test(badTo.out), badTo.out);

    // ---- tidying
    const mk = await run('email_mark', { ids: 'INBOX#1, INBOX#2', read: true });
    await M.refresh(true);
    check('mark read: unread count goes to 0', /Done \(2 emails\)/.test(mk.out) && M.state.unread === 0, { out: mk.out, unread: M.state.unread });
    const ar = await run('email_archive', { ids: 'INBOX#2' });
    check('archive moves it to the Archive folder', /Archived 1 email/.test(ar.out) && imap.boxes.get('Archive').msgs.length === 1 && imap.boxes.get('INBOX').msgs.length === 2, ar.out);
    answer = false; confirms = [];
    const delNo = await run('email_delete', { ids: 'INBOX#3' });
    check('delete asks first; a no deletes nothing', /said no/.test(delNo.out) && confirms.length === 1 && imap.boxes.get('INBOX').msgs.length === 2, delNo.out);
    answer = true;
    const del = await run('email_delete', { ids: 'INBOX#3' });
    check('delete moves it to the trash (not gone for good)', /Deleted 1 email \(they're in Trash\)/.test(del.out) && imap.boxes.get('Trash').msgs.length === 1, del.out);
    const mv = await run('email_move', { ids: 'INBOX#1', folder: 'nowhere' });
    check('moving to a folder that doesn\'t exist says which folders there are', /no folder called "nowhere"/.test(mv.out) && /Archive/.test(mv.out), mv.out);
    const sentList = await run('email_list', { folder: 'sent' });
    check('"sent", "trash", "archive" find the special folders', /\(Sent\)/.test(sentList.out), sentList.out);

    // ---- the connection
    await mail.close();
    const after = await run('email_list', { folder: 'archive' });
    check('after the connection closes, the next use connects again', /showing 1 of 1/.test(after.out), after.out);
    const restored = createMail(deps); const st = await restored.op('status');
    check('the account comes back after a restart', st.account && st.account.email === 'king@example.com' && st.account.hasPassword, st);
    await restored.close();
    const gone = await mail.op('forget'); await M.refresh();
    check('forgetting the account removes it', gone.ok && !(await mail.op('status')).account && /no account is connected/.test(M.promptLine()));
    const none = await run('email_list', {});
    check('with no account, the tool says how to connect or send a bot to the inbox', /Me → Email/.test(none.out) && /deploy_bot/.test(none.out), none.out);
  } catch (e) { failed++; console.log('FAIL crashed — ' + (e.stack || e)); }
  finally {
    await mail.close().catch(() => {});
    await imap.close(); await new Promise((r) => smtp.close(r));
    console.log(`\n${passed}/${passed + failed} passed`);
    process.exit(failed ? 1 : 0);
  }
})();
