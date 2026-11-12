// Me → Your Devices, the Device Link part: which devices are online, controlling the phone from here, and who may
// control this PC. Plus the phone viewer (a full-window overlay).
import * as LW from './linkwire.js';
import { pref, setPref } from './store.js';

export function renderDevices(col, { h, esc, toast, render, sheet }) {
  if (!LW.linked()) return;
  const card = h('<div class="card"></div>'); col.appendChild(card);
  card.appendChild(h('<div style="font:400 20px var(--serif)">Control Across Devices</div>'));
  card.appendChild(h('<p class="muted" style="margin:4px 0 10px">Drive your phone from here, let your phone drive this PC, or just ask Atlas: “on my phone, …”. Everything goes encrypted with your link code.</p>'));
  const peers = LW.peers();
  if (!peers.length) card.appendChild(h('<p class="small muted">Waiting to hear from your phone… (open Atlas there once, after updating it).</p>'));
  for (const p of peers) {
    const row = h(`<div class="setting"><div class="grow">${esc(p.name)} <span class="small ${p.online ? 'ok' : 'muted'}">● ${p.online ? 'online' : 'seen ' + esc(LW.ago(p.agoMs))}</span>
      <div class="small muted">${p.platform === 'android' ? 'Phone' : 'Computer'}${p.online && p.platform === 'android' && p.info && p.info.capture === false ? ' · screen sharing needs Phone Control on' : ''}</div></div></div>`);
    if (p.platform === 'android') {
      const b = h(`<button class="btn primary" ${p.online ? '' : 'disabled'}>Control Phone</button>`);
      b.onclick = () => openPhoneViewer({ h, esc, toast });
      row.appendChild(b);
    }
    card.appendChild(row);
    const allow = h(`<div class="setting"><div class="grow">Let ${esc(p.name)} Control This PC<div class="small muted">See this screen, click and type. A bar with Stop shows while it does.</div></div><div class="switch ${LW.controlAllowed(p.device) ? 'on' : ''}"></div></div>`);
    allow.querySelector('.switch').onclick = (e) => { const on = !e.currentTarget.classList.contains('on'); LW.allowControl(p.device, on); e.currentTarget.classList.toggle('on', on); toast(on ? 'Allowed' : 'Not allowed any more'); };
    card.appendChild(allow);
  }
  const live = LW.beingControlled();
  if (live.length) card.appendChild(h(`<p class="small" style="color:var(--warm)">${esc(live.join(', '))} is controlling this PC right now (Stop is in the bar at the top of the screen).</p>`));
  const out = LW.outgoing();
  for (const t of out) card.appendChild(h(`<p class="small muted">On your phone: ${esc((t.title || t.text).slice(0, 90))} — ${esc(t.sent ? (t.state === 'awaiting_approval' ? 'waiting for your OK' : t.line || t.state || 'working') : 'waiting for the phone to come online')}</p>`));
  const nm = h(`<div class="setting"><div class="grow">This PC's Name<div class="small muted">${esc(pref('deviceName', '') || 'Laptop')}</div></div><button class="btn">Change</button></div>`);
  nm.querySelector('button').onclick = async () => {
    await sheet({ title: "This PC's Name", body: 'How your phone shows this PC.', primary: 'Save', html: `<input class="field" id="v" value="${esc(pref('deviceName', '') || 'Laptop')}">`,
      onPrimary: (o) => { setPref('deviceName', o.querySelector('#v').value.trim().slice(0, 40)); render(true, true); return true; } });
  };
  card.appendChild(nm);
  const st = LW.stats();
  if (st && st.lastError) card.appendChild(h(`<p class="small muted">Link: ${esc(st.lastError)} (it keeps trying)</p>`));
}

export async function openPhoneViewer({ h, esc, toast }) {
  const o = h(`<div class="overlay remote"><div class="remote-box">
    <div class="remote-top"><b>Your Phone</b><span class="small muted" id="rs">Connecting…</span><span class="grow"></span><button class="btn" data-k="x">Done</button></div>
    <div class="remote-stage" id="stage"></div>
    <div class="remote-keys">
      <button class="btn" data-a="back">Back</button><button class="btn" data-a="home">Home</button><button class="btn" data-a="recents">Apps</button><button class="btn" data-a="notifications">Notifications</button>
      <input class="field grow" id="txt" placeholder="Type on the phone, then Enter"><button class="btn" data-k="bs">⌫</button>
    </div>
    <p class="small muted" style="margin:6px 2px 0">Click to tap, hold to long-press, drag to swipe, scroll to scroll. Click the screen and type to type.</p>
  </div></div>`);
  document.body.appendChild(o);
  const status = (t) => { const s = o.querySelector('#rs'); if (s) s.textContent = t; };
  let v = null;
  const close = (why) => { if (v) v.end(); o.remove(); if (why) toast(why); };
  o.querySelector('[data-k=x]').onclick = () => close();
  try {
    v = await LW.viewPhone(o.querySelector('#stage'), { onStatus: status, onEnd: (why) => { if (document.body.contains(o)) { o.remove(); if (why) toast(why); } } });
    status('Live');
  } catch (e) { close(e.message); return; }
  o.querySelectorAll('[data-a]').forEach((b) => (b.onclick = () => v.act(b.dataset.a)));
  o.querySelector('[data-k=bs]').onclick = () => v.key('backspace');
  const txt = o.querySelector('#txt');
  txt.addEventListener('keydown', (e) => { if (e.key === 'Enter') { if (txt.value) v.type(txt.value); else v.key('enter'); txt.value = ''; e.preventDefault(); } });
}
