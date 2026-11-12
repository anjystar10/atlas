// Me → Email on the PC: connect an inbox with an app password (Gmail, Yahoo, iCloud, AOL, Zoho, Fastmail, most others),
// see that it's working, or remove it. Outlook.com and Proton are read in Atlas Bot's own browser instead.
import * as M from './mail.js';
const host = window.atlasHost;

/** Draws the Email section into [col]. [ui]: { h, esc, sheet, toast, render } from the app. */
export function renderEmail(col, ui) {
  const { h, esc, render } = ui;
  col.appendChild(h('<div class="eyebrow">Email</div>'));
  const card = h('<div class="card"></div>'); col.appendChild(card);
  const a = M.state.account;
  if (!a) {
    card.appendChild(h('<div style="font:400 22px var(--serif)">Not connected</div>'));
    card.appendChild(h('<p class="muted" style="margin:4px 0 12px">Connect your inbox so you can just ask: "what does my email say?", "reply to Anna that Friday works". Atlas reads freely; sending and deleting always ask you first. The password stays on this PC, encrypted by Windows, and never syncs.</p>'));
    const b = h('<button class="btn primary">Connect Email</button>'); b.onclick = () => connect(ui); card.appendChild(b);
    return;
  }
  if (a.webmail) {
    card.appendChild(h(`<div style="font:400 22px var(--serif)">${esc(a.email)}</div>`));
    card.appendChild(h(`<p class="muted" style="margin:4px 0 12px">${esc(a.providerName)} doesn't let apps sign in with a password any more, so Atlas reads it in Atlas Bot's own browser: ask "check my email" and sign in there the first time.</p>`));
  } else {
    card.appendChild(h(`<div style="font:400 22px var(--serif)">${esc(a.email)}</div>`));
    card.appendChild(h(`<p class="muted" style="margin:4px 0 12px">${esc(a.providerName || 'Email')} · ${M.state.unread === null ? 'connected' : `${M.state.unread} unread in the inbox`}. Ask Atlas about it any time; sending and deleting ask you first.</p>`));
  }
  const row = h('<div class="row"></div>');
  if (!a.webmail) { const c = h('<button class="btn">Check Again</button>'); c.onclick = async () => { c.disabled = true; await M.refresh(true).catch(() => {}); render(true); }; row.appendChild(c); }
  const ch = h('<button class="btn">Change</button>'); ch.onclick = () => connect(ui, a); row.appendChild(ch);
  const rm = h('<button class="btn danger">Remove</button>');
  rm.onclick = async () => { if (await ui.sheet({ title: 'Remove this email account?', body: 'Atlas stops reading it. The password is deleted from this PC.', primary: 'Remove', danger: true })) { await host.mail('forget'); await M.refresh(); render(true); } };
  row.appendChild(rm); card.appendChild(row);
}

function connect(ui, old) {
  const { esc } = ui;
  ui.sheet({ title: 'Connect Email', body: 'Use an app password, not your normal password. Gmail: turn on 2-Step Verification, then make one at myaccount.google.com/apppasswords. Yahoo, iCloud, AOL, Zoho and Fastmail have the same thing in their security settings.', primary: 'Connect',
    html: `<input class="field" id="m-email" type="email" placeholder="you@gmail.com" value="${esc(old ? old.email : '')}" style="margin-bottom:8px">
      <input class="field mono" id="m-pass" type="password" placeholder="App password" autocomplete="off" style="margin-bottom:8px">
      <input class="field" id="m-name" placeholder="Your name on sent emails (optional)" value="${esc(old && old.name ? old.name : '')}" style="margin-bottom:8px">
      <details><summary class="small muted" style="cursor:pointer">Other provider? Server settings</summary>
        <div class="row" style="margin-top:8px"><input class="field mono" id="m-ih" placeholder="IMAP server (imap.…)" value="${esc(old && old.imap ? old.imap.host : '')}"><input class="field mono" id="m-ip" placeholder="993" style="max-width:90px" value="${esc(old && old.imap ? String(old.imap.port) : '')}"></div>
        <div class="row" style="margin-top:8px"><input class="field mono" id="m-sh" placeholder="SMTP server (smtp.…)" value="${esc(old && old.smtp ? old.smtp.host : '')}"><input class="field mono" id="m-sp" placeholder="465" style="max-width:90px" value="${esc(old && old.smtp ? String(old.smtp.port) : '')}"></div>
        <input class="field mono" id="m-user" placeholder="Username, if not the email address" style="margin-top:8px" value="${esc(old && old.user && old.user !== old.email ? old.user : '')}">
      </details>`,
    onPrimary: async (o) => {
      const v = (id) => o.querySelector(id).value.trim();
      ui.toast('Connecting…', 15000);
      const r = await host.mail('setup', { email: v('#m-email'), password: o.querySelector('#m-pass').value, name: v('#m-name'), imapHost: v('#m-ih'), imapPort: v('#m-ip'), smtpHost: v('#m-sh'), smtpPort: v('#m-sp'), user: v('#m-user') });
      if (r.error) { ui.toast(r.error, 9000); return false; }
      await M.refresh();
      ui.toast(r.webmail ? `${r.account.providerName}: Atlas will read it in its own browser.` : `Connected. ${r.unread} unread in the inbox.`, 5000);
      ui.render(true); return true;
    } });
}
