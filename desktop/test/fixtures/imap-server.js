// A small IMAP4rev1 server for tests: plain TCP, one user, folders with SPECIAL-USE, and the commands Atlas's email
// (imapflow) uses: LOGIN, CAPABILITY, LIST/LSUB, SELECT/EXAMINE, STATUS, UID SEARCH, UID FETCH (ENVELOPE, FLAGS,
// INTERNALDATE, BODY[]), UID STORE, UID MOVE, CREATE, NOOP, LOGOUT. Not a real mail server: just enough to prove the
// client end to end. It records every command so a test can check what was sent.
'use strict';
const net = require('net');

function tokenize(s) {
  // IMAP arguments → nested arrays of strings ({ q: '…' } for quoted strings, so "NIL" and NIL differ)
  let i = 0;
  function list(endCh) {
    const out = [];
    while (i < s.length) {
      const c = s[i];
      if (c === ' ') { i++; continue; }
      if (c === endCh) { i++; return out; }
      if (c === '(') { i++; out.push(list(')')); continue; }
      if (c === '"') { let v = ''; i++; while (i < s.length && s[i] !== '"') { if (s[i] === '\\') i++; v += s[i++]; } i++; out.push({ q: v }); continue; }
      if (c === '{' && s.indexOf('}', i) > 0) { const j = s.indexOf('}', i); const n = Number(s.slice(i + 1, j).replace('+', '')); const v = s.slice(j + 1, j + 1 + n); i = j + 1 + n; out.push({ q: v }); continue; }
      let v = ''; while (i < s.length && s[i] !== ' ' && s[i] !== endCh && s[i] !== '(') { if (s[i] === '[') { const j = s.indexOf(']', i); v += s.slice(i, j + 1); i = j + 1; continue; } v += s[i++]; }
      out.push(v);
    }
    return out;
  }
  return list(null);
}
const val = (t) => (t && typeof t === 'object' && 'q' in t ? t.q : t);
const quote = (s) => (s == null ? 'NIL' : '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"');
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function imapDate(d) { const p = (n) => String(n).padStart(2, '0'); return `${p(d.getUTCDate())}-${MON[d.getUTCMonth()]}-${d.getUTCFullYear()} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())} +0000`; }
function parseImapDate(s) { const m = String(s).match(/^(\d{1,2})-(\w{3})-(\d{4})/); return m ? Date.UTC(+m[3], MON.indexOf(m[2]), +m[1]) : NaN; }

function headers(raw) {
  const head = raw.split(/\r?\n\r?\n/)[0].replace(/\r?\n[ \t]+/g, ' ');
  const h = {}; for (const line of head.split(/\r?\n/)) { const m = line.match(/^([^:]+):\s*(.*)$/); if (m) h[m[1].toLowerCase()] = m[2]; }
  return h;
}
function addrList(s) {
  if (!s) return 'NIL';
  const parts = s.split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/).map((x) => x.trim()).filter(Boolean);
  return '(' + parts.map((p) => {
    const m = p.match(/^(.*?)\s*<([^>]+)>$/); const name = m ? m[1].replace(/^"|"$/g, '') || null : null; const a = (m ? m[2] : p).split('@');
    return `(${quote(name)} NIL ${quote(a[0])} ${quote(a[1] || '')})`;
  }).join('') + ')';
}
function envelope(raw) {
  const h = headers(raw);
  return `(${quote(h.date || null)} ${quote(h.subject || null)} ${addrList(h.from)} ${addrList(h.from)} ${addrList(h['reply-to'] || h.from)} ${addrList(h.to)} ${addrList(h.cc)} NIL ${quote(h['in-reply-to'] || null)} ${quote(h['message-id'] || null)})`;
}

function createImapServer({ user, pass, folders }) {
  // folders: [{ path, special?, messages: [{ raw, flags: [], date }] }]
  const boxes = new Map(); let nextUid = 1;
  for (const f of folders) boxes.set(f.path, { path: f.path, special: f.special || null, msgs: (f.messages || []).map((m) => ({ uid: nextUid++, raw: m.raw.replace(/\r?\n/g, '\r\n'), flags: new Set(m.flags || []), date: m.date || new Date() })) });
  const log = [];
  const CAPS = 'IMAP4rev1 MOVE UIDPLUS SPECIAL-USE';

  function matches(m, toks, i) {
    // returns [ok, nextIndex]
    const k = String(val(toks[i]) || '').toUpperCase();
    const text = () => m.raw.toLowerCase();
    const h = headers(m.raw);
    const has = (field, v) => String(h[field] || '').toLowerCase().includes(String(v).toLowerCase());
    switch (k) {
      case 'ALL': return [true, i + 1];
      case 'SEEN': return [m.flags.has('\\Seen'), i + 1];
      case 'UNSEEN': return [!m.flags.has('\\Seen'), i + 1];
      case 'FLAGGED': return [m.flags.has('\\Flagged'), i + 1];
      case 'UNFLAGGED': return [!m.flags.has('\\Flagged'), i + 1];
      case 'ANSWERED': return [m.flags.has('\\Answered'), i + 1];
      case 'DELETED': return [m.flags.has('\\Deleted'), i + 1];
      case 'UNDELETED': return [!m.flags.has('\\Deleted'), i + 1];
      case 'FROM': case 'TO': case 'CC': case 'SUBJECT': return [has(k.toLowerCase(), val(toks[i + 1])), i + 2];
      case 'BODY': case 'TEXT': return [text().includes(String(val(toks[i + 1])).toLowerCase()), i + 2];
      case 'SINCE': return [m.date.getTime() >= parseImapDate(val(toks[i + 1])), i + 2];
      case 'BEFORE': return [m.date.getTime() < parseImapDate(val(toks[i + 1])), i + 2];
      case 'CHARSET': return [true, i + 2];
      case 'UID': { const set = String(val(toks[i + 1])).split(',').map(Number); return [set.includes(m.uid), i + 2]; }
      case 'NOT': { const [ok, j] = matches(m, toks, i + 1); return [!ok, j]; }
      case 'OR': { const [a, j] = matches(m, toks, i + 1); const [b, k2] = matches(m, toks, j); return [a || b, k2]; }
      default:
        if (Array.isArray(toks[i])) { let ok = true, j = 0; while (j < toks[i].length) { const [r, n] = matches(m, toks[i], j); ok = ok && r; j = n; } return [ok, i + 1]; }
        return [true, i + 1];
    }
  }
  function search(box, toks) {
    return box.msgs.filter((m) => { let ok = true, i = 0; while (i < toks.length) { const [r, n] = matches(m, toks, i); ok = ok && r; i = n; } return ok; }).map((m) => m.uid);
  }
  function uidSet(box, spec) {
    const out = new Set();
    for (const part of String(spec).split(',')) {
      const [a, b] = part.split(':'); const max = box.msgs.length ? Math.max(...box.msgs.map((m) => m.uid)) : 0;
      const lo = a === '*' ? max : Number(a), hi = b === undefined ? lo : b === '*' ? max : Number(b);
      for (const m of box.msgs) if (m.uid >= Math.min(lo, hi) && m.uid <= Math.max(lo, hi)) out.add(m.uid);
    }
    return out;
  }
  const flagsOf = (m) => '(' + [...m.flags].join(' ') + ')';

  const server = net.createServer((sock) => {
    let authed = false, selected = null, buf = Buffer.alloc(0), pending = null;
    const send = (s) => sock.write(s + '\r\n');
    send(`* OK [CAPABILITY ${CAPS}] test server ready`);

    function handle(line) {
      const sp = line.indexOf(' '); const tag = line.slice(0, sp); let rest = line.slice(sp + 1);
      let uid = false; if (/^UID /i.test(rest)) { uid = true; rest = rest.slice(4); }
      const sp2 = rest.indexOf(' '); const cmd = (sp2 < 0 ? rest : rest.slice(0, sp2)).toUpperCase(); const args = sp2 < 0 ? [] : tokenize(rest.slice(sp2 + 1));
      log.push((uid ? 'UID ' : '') + cmd + ' ' + (sp2 < 0 ? '' : rest.slice(sp2 + 1)));
      const ok = (t = `${cmd} completed`) => send(`${tag} OK ${t}`);
      try {
        if (cmd === 'CAPABILITY') { send(`* CAPABILITY ${CAPS}`); return ok(); }
        if (cmd === 'NOOP' || cmd === 'CHECK') return ok();
        if (cmd === 'LOGOUT') { send('* BYE bye'); ok(); return sock.end(); }
        if (cmd === 'LOGIN') { if (val(args[0]) === user && val(args[1]) === pass) { authed = true; return send(`${tag} OK [CAPABILITY ${CAPS}] logged in`); } return send(`${tag} NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)`); }
        if (!authed) return send(`${tag} BAD not logged in`);
        if (cmd === 'LIST' || cmd === 'LSUB') {
          const a = args.filter((x) => !Array.isArray(x) || x.length === 0 ? true : false).map(val);
          const pattern = val(args[args.length - 1]);
          if (pattern === '' || (a.length >= 2 && val(args[1]) === '')) { send(`* ${cmd} (\\Noselect) "/" ""`); return ok(); }
          for (const b of boxes.values()) send(`* ${cmd} (\\HasNoChildren${b.special ? ' ' + b.special : ''}) "/" ${quote(b.path)}`);
          return ok();
        }
        if (cmd === 'CREATE') { const p = val(args[0]); if (!boxes.has(p)) boxes.set(p, { path: p, special: null, msgs: [] }); return ok(); }
        if (cmd === 'STATUS') {
          const b = boxes.get(val(args[0])); if (!b) return send(`${tag} NO [NONEXISTENT] no such mailbox`);
          const items = (args[1] || []).map((x) => String(val(x)).toUpperCase());
          const parts = items.map((it) => `${it} ${it === 'UNSEEN' ? b.msgs.filter((m) => !m.flags.has('\\Seen')).length : it === 'MESSAGES' ? b.msgs.length : it === 'UIDNEXT' ? nextUid : it === 'UIDVALIDITY' ? 1 : 0}`);
          send(`* STATUS ${quote(b.path)} (${parts.join(' ')})`); return ok();
        }
        if (cmd === 'SELECT' || cmd === 'EXAMINE') {
          const b = boxes.get(val(args[0])); if (!b) return send(`${tag} NO [NONEXISTENT] no such mailbox`);
          selected = b;
          send('* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)'); send('* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] ok');
          send(`* ${b.msgs.length} EXISTS`); send('* 0 RECENT'); send('* OK [UIDVALIDITY 1] ok'); send(`* OK [UIDNEXT ${nextUid}] ok`);
          return send(`${tag} OK [${cmd === 'SELECT' ? 'READ-WRITE' : 'READ-ONLY'}] ${cmd} completed`);
        }
        if (cmd === 'CLOSE' || cmd === 'UNSELECT') { selected = null; return ok(); }
        if (!selected) return send(`${tag} BAD no mailbox selected`);
        const b = selected;
        if (cmd === 'SEARCH') { const r = uid ? search(b, args) : search(b, args).map((u) => b.msgs.findIndex((m) => m.uid === u) + 1); send(`* SEARCH${r.length ? ' ' + r.join(' ') : ''}`); return ok(); }
        if (cmd === 'FETCH') {
          const set = uid ? uidSet(b, val(args[0])) : new Set(String(val(args[0])).split(',').map((n) => b.msgs[Number(n) - 1] && b.msgs[Number(n) - 1].uid));
          const items = (Array.isArray(args[1]) ? args[1] : [args[1]]).map((x) => String(val(x)).toUpperCase());
          b.msgs.forEach((m, i) => {
            if (!set.has(m.uid)) return;
            const parts = [`UID ${m.uid}`];
            if (items.some((x) => x === 'FLAGS')) parts.push(`FLAGS ${flagsOf(m)}`);
            if (items.includes('INTERNALDATE')) parts.push(`INTERNALDATE "${imapDate(m.date)}"`);
            if (items.includes('RFC822.SIZE')) parts.push(`RFC822.SIZE ${Buffer.byteLength(m.raw)}`);
            if (items.includes('ENVELOPE')) parts.push(`ENVELOPE ${envelope(m.raw)}`);
            const bodyItem = items.find((x) => /^BODY(\.PEEK)?\[\]$/.test(x) || x === 'RFC822');
            if (bodyItem) { if (!/PEEK/.test(bodyItem)) m.flags.add('\\Seen'); const bytes = Buffer.from(m.raw, 'utf8'); sock.write(`* ${i + 1} FETCH (${parts.join(' ')} BODY[] {${bytes.length}}\r\n`); sock.write(bytes); sock.write(')\r\n'); return; }
            send(`* ${i + 1} FETCH (${parts.join(' ')})`);
          });
          return ok();
        }
        if (cmd === 'STORE') {
          const set = uidSet(b, val(args[0])); const op = String(val(args[1])).toUpperCase(); const fl = (Array.isArray(args[2]) ? args[2] : [args[2]]).map(val);
          b.msgs.forEach((m, i) => {
            if (!set.has(m.uid)) return;
            if (op.startsWith('+')) fl.forEach((f) => m.flags.add(f)); else if (op.startsWith('-')) fl.forEach((f) => m.flags.delete(f)); else m.flags = new Set(fl);
            if (!op.includes('SILENT')) send(`* ${i + 1} FETCH (UID ${m.uid} FLAGS ${flagsOf(m)})`);
          });
          return ok();
        }
        if (cmd === 'MOVE' || cmd === 'COPY') {
          const set = uidSet(b, val(args[0])); const dest = boxes.get(val(args[1])); if (!dest) return send(`${tag} NO [TRYCREATE] no such mailbox`);
          const from = [], to = [];
          for (const m of b.msgs.filter((x) => set.has(x.uid))) { const copy = { ...m, uid: nextUid++, flags: new Set(m.flags) }; dest.msgs.push(copy); from.push(m.uid); to.push(copy.uid); }
          if (cmd === 'MOVE') {
            send(`* OK [COPYUID 1 ${from.join(',')} ${to.join(',')}] moved`);
            for (let i = b.msgs.length - 1; i >= 0; i--) if (set.has(b.msgs[i].uid)) { b.msgs.splice(i, 1); send(`* ${i + 1} EXPUNGE`); }
            return ok();
          }
          return send(`${tag} OK [COPYUID 1 ${from.join(',')} ${to.join(',')}] COPY completed`);
        }
        if (cmd === 'EXPUNGE') { for (let i = b.msgs.length - 1; i >= 0; i--) if (b.msgs[i].flags.has('\\Deleted')) { b.msgs.splice(i, 1); send(`* ${i + 1} EXPUNGE`); } return ok(); }
        return send(`${tag} BAD unknown command ${cmd}`);
      } catch (e) { send(`${tag} BAD ${e.message}`); }
    }

    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      for (;;) {
        if (pending) {   // a literal the client is sending
          if (buf.length < pending.n) return;
          pending.line += buf.slice(0, pending.n).toString('utf8'); buf = buf.slice(pending.n); const p = pending; pending = null;
          const nl = buf.indexOf('\r\n'); if (nl < 0) { pending = { n: 0, line: p.line }; return; }
          const more = buf.slice(0, nl).toString('utf8'); buf = buf.slice(nl + 2); const line = p.line + more;
          const lit = line.match(/\{(\d+)\+?\}$/); if (lit) { pending = { n: Number(lit[1]), line: line }; if (!/\+\}$/.test(line)) send('+ go ahead'); continue; }
          handle(line.replace(/\{(\d+)\+?\}(?=.)/g, (m, n) => `{${n}}`)); continue;
        }
        const nl = buf.indexOf('\r\n'); if (nl < 0) return;
        const line = buf.slice(0, nl).toString('utf8'); buf = buf.slice(nl + 2);
        const lit = line.match(/\{(\d+)\+?\}$/);
        if (lit) { pending = { n: Number(lit[1]), line }; if (!/\+\}$/.test(line)) send('+ go ahead'); continue; }
        handle(line);
      }
    });
    sock.on('error', () => {});
  });
  return {
    log, boxes,
    listen: () => new Promise((r) => server.listen(0, '127.0.0.1', () => r(server.address().port))),
    close: () => new Promise((r) => server.close(() => r())),
  };
}

module.exports = { createImapServer, tokenize };
