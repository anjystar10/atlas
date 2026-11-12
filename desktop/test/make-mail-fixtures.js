// Writes tools/mail-fixtures.json from mailtext.js: the email cases the phone's MailText.kt must answer the same way.
// Run after changing mailtext.js on purpose: node test/make-mail-fixtures.js
'use strict';
const T = require('../mailtext');
const html = [
  '<p>Hello&nbsp;<b>there</b></p><p>Line two</p>',
  '<html><head><style>p{x}</style><title>t</title></head><body><div>Caf&eacute;? &amp; &#8212; &#x2603;</div><script>bad()</script></body></html>',
  '<ul><li>One</li><li>Two</li></ul><a href="https://x.org/a?b=1&amp;c=2">Open</a> <a href="mailto:a@b.c">mail me</a>',
  'Text<br>next<br/>third<table><tr><td>a</td><td>b</td></tr></table><!-- hidden -->',
  '<div>  lots   of\n\n\n spaces </div><div></div><div>end</div>',
];
const strip = [
  'Sounds good, see you then.\n\nOn Mon, 9 Nov 2026 at 10:00, Anna <a@x.org> wrote:\n> Lunch?\n> Friday',
  'Thanks!\n\nOn Mon, 9 Nov 2026 at 10:00, Anna Smith\n<a@x.org> wrote:\n> Lunch?',
  'Will do.\n\n-----Original Message-----\nFrom: Bob\nSent: Monday\nSubject: x\n\nold text',
  'Done.\n\n________________________________\nFrom: Bob <b@y.com>\nSent: Monday, 9 November 2026 10:00\nTo: King\nSubject: Re: x\n\nold',
  'From: the start, this line is mine\nand more',
  '> only quotes\n> here',
  'Inline answer below\n> question one\nmy answer one\n> question two\nmy answer two',
  'Hi\n\nFrom my phone',
  'Ok\r\n\r\nOn Tue, Anna wrote:\r\n> hi\r\n',
];
const orig = { date: '2026-11-09T10:00:00Z', from: 'Anna <a@x.org>', text: 'Lunch?\nFriday at 1' };
const out = {
  htmlToText: html.map((h) => ({ in: h, out: T.htmlToText(h) })),
  stripQuoted: strip.map((t) => ({ in: t, out: T.stripQuoted(t) })),
  quoteForReply: [{ body: 'Yes, Friday works.', original: orig, out: T.quoteForReply('Yes, Friday works.', orig) }, { body: 'ok', original: { from: '', text: 'x' }, out: T.quoteForReply('ok', { from: '', text: 'x' }) }],
  replySubject: ['Lunch', 'Re: Lunch', 'RE:Lunch', 'AW: x', '', 'Fwd: x'].map((s) => ({ in: s, out: T.replySubject(s) })),
  parseQuery: ['from:anna subject:"lunch plans" is:unread after:2026-11-01 pizza', 'is:starred before:2026/10/05', 'hello world', 'to:bob@x.com is:read', 'label:work "exact phrase"'].map((s) => ({ in: s, out: T.parseQuery(s) })),
  presetFor: ['king@gmail.com', 'x@Yahoo.com', 'me@icloud.com', 'me@outlook.com', 'me@hotmail.com', 'me@proton.me', 'me@mycompany.co.uk', 'bad'].map((e) => ({ in: e, out: T.presetFor(e) })),
  parseId: ['INBOX#12', '[Gmail]/All Mail#5', 'Work#Stuff#7', 'INBOX#0', 'INBOX', '#3'].map((s) => ({ in: s, out: T.parseId(s) })),
  splitAddresses: ['Anna <a@x.org>, b@y.com', '"Smith, Anna" <a@x.org>', 'Anna', 'a@x.org; c@d.ee', ''].map((s) => ({ in: s, out: T.splitAddresses(s) })),
  addr: [{ name: 'Anna Smith', address: 'a@x.org' }, { name: 'Smith, Anna', address: 'a@x.org' }, { name: '', address: 'b@y.com' }].map((a) => ({ in: a, out: T.addr(a) })),
};
require('fs').writeFileSync(require('path').join(__dirname, '../../tools/mail-fixtures.json'), JSON.stringify(out, null, 1) + '\n');
console.log('wrote tools/mail-fixtures.json');
