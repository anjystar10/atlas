// HUD mode on screen: starts the real app (main.js) with fresh data, and takes screenshots of the idle core, live bot
// cards (one waiting for an OK), a full results wall laid out through the real show_panels tool, and the terminal.
// Pictures on the wall come from test/fixtures/hud (served for the made-up host pictures.atlas-demo.example).
// Run under xvfb:  xvfb-run -s "-screen 0 1920x1080x24" npx electron --no-sandbox test/hud.shots.js [out-folder]
'use strict';
const path = require('path'), fs = require('fs'), os = require('os');
const { app, BrowserWindow } = require('electron');

const out = path.resolve(process.argv.find((a, i) => i > 1 && !a.startsWith('-') && !a.endsWith('.js')) || path.join(os.tmpdir(), 'atlas-hud-shots'));
fs.mkdirSync(out, { recursive: true });
const data = fs.mkdtempSync(path.join(os.tmpdir(), 'atlashud-'));
fs.writeFileSync(path.join(data, 'prefs.json'), JSON.stringify({ pcLook: 'hud', speakReplies: false, onScreen: false }));
process.env.ATLAS_DATA = data;
process.env.ATLAS_SHOT = path.join(out, 'hud.png');
process.env.ATLAS_WAIT = '2600';

// the wall's pictures: the main process fetches them (net:fetch); here a made-up host is answered from the fixtures
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  const m = String(url).match(/^https:\/\/pictures\.atlas-demo\.example\/(\w+\.jpg)$/);
  if (m) { const b = fs.readFileSync(path.join(__dirname, 'fixtures', 'hud', m[1])); return new Response(b, { status: 200, headers: { 'content-type': 'image/jpeg' } }); }
  if (/i\.ytimg\.com/.test(String(url))) { const b = fs.readFileSync(path.join(__dirname, 'fixtures', 'hud', 'room.jpg')); return new Response(b, { status: 200, headers: { 'content-type': 'image/jpeg' } }); }
  return realFetch(url, init);
};
app.on('browser-window-created', (_e, w) => { setTimeout(() => { if (!w.isDestroyed() && w.getTitle() === 'Atlas') w.setSize(1600, 960); }, 50); });

const now = Date.now();
const job = (id, goal, state, over = {}) => ({ id, goal, details: '', createdAt: now - 95000, device: 'pc', state, updatedAt: now - 2000, updated_ms: now, history: [{ at: now - 95000, state: 'queued' }, { at: now - 94000, state: 'running' }],
  plan: [], notes: [], steps: [], sources: [], recent: [], toolCalls: 0, workMs: 0, inbox: [], children: [], depth: 0, v: 2, bot: 'atlas', ...over });
const jobs = [
  job('demo-research', 'Research the state of VR in 2026', 'running', { plan: [{ text: 'Search news', done: true }, { text: 'Read reviews', done: true }, { text: 'Sales numbers', done: true }, { text: 'Compare headsets', done: false }, { text: 'Write it up', done: false }],
    steps: ['Searched the web: Quest 3S sales 2026', 'Read uploadvr.com: Quest sales estimates'], sources: ['uploadvr.com', 'theverge.com', 'meta.com', 'apple.com', 'idc.com', 'roadtovr.com'], toolCalls: 14 }),
  job('demo-mail', 'Check my inbox and draft replies', 'awaiting_approval', { approval: { what: 'Send the reply to Ada about Thursday’s meeting' }, plan: [{ text: 'Read new mail', done: true }, { text: 'Draft replies', done: true }, { text: 'Send', done: false }], steps: ['Drafted 3 replies'], toolCalls: 9 }),
];
const panels = {
  title: 'State of VR',
  panels: [
    { type: 'image', url: 'https://pictures.atlas-demo.example/headset.jpg', title: 'Quest 3S', source: 'https://www.meta.com/quest/quest-3s/' },
    { type: 'text', kicker: 'HARDWARE', title: 'Meta Quest 3S / Quest 3 — shipping', body: 'Quest 3S brings mixed reality to $299 with the Quest 3\'s chip; Quest 3 stays the premium standalone. Both run the same store and get the same updates.', source: 'https://www.theverge.com/' },
    { type: 'text', kicker: 'CONTENT', title: 'Hits: social, sandbox, fitness and rhythm', body: 'The top-earning titles are social sandboxes, fitness and rhythm games. Batman: Arkham Shadow showed premium single-player can sell, but bundled copies inflate the numbers.', source: 'https://www.uploadvr.com/' },
    { type: 'video', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', title: 'Hands-on: mixed reality in a living room' },
    { type: 'chart', kicker: 'MARKET', title: 'Headsets shipped, 2025 (m)', unit: 'millions', series: [{ label: 'Meta', value: 6.8 }, { label: 'Sony', value: 1.1 }, { label: 'Apple', value: 0.4 }, { label: 'Pico', value: 0.5 }, { label: 'Other', value: 0.3 }], source: 'https://www.idc.com/' },
    { type: 'stat', kicker: 'FACT', value: '≈20m', label: 'Quest headsets sold by early 2023', body: 'Internal Meta figure reported by The Verge; lifetime sales, not active users.', source: 'https://www.theverge.com/' },
    { type: 'image', url: 'https://pictures.atlas-demo.example/city.jpg', title: 'A city scene in mixed reality', source: 'https://www.roadtovr.com/' },
    { type: 'table', kicker: 'COMPARE', title: 'Headsets today', columns: ['Headset', 'Price', 'Standalone'], rows: [['Quest 3S', '$299', 'Yes'], ['Quest 3', '$499', 'Yes'], ['Vision Pro', '$3,499', 'Yes'], ['PS VR2', '$399', 'No (PS5)']], source: 'https://www.roadtovr.com/' },
    { type: 'link', title: 'UploadVR: the year in VR', url: 'https://www.uploadvr.com/', body: 'Release calendar, sales estimates and reviews.' },
  ],
};
const chat = [
  { id: 'c1', user: true, text: 'what is the state of VR right now', source: 'You', at: now - 60000, steps: [], media: [] },
  { id: 'c2', user: false, text: 'Meta still dominates: the **Quest 3S** at $299 is the cheapest way in, and the Quest line has sold about 20 million. Apple\'s Vision Pro is the premium outlier. I\'ve put the details on the wall.', source: 'Gemini', at: now - 50000, steps: ['Searched the web: state of VR 2026', 'Read theverge.com', 'Showed 9 panels'] },
];
process.env.ATLAS_STEPS = JSON.stringify([
  // 1: bots at work, one waiting for an OK
  `(async () => { const { bots } = await import('./botwire.js'); const st = await import('./store.js'); bots.jobs.push(...${JSON.stringify(jobs)}); st.changed('bots'); })()`,
  // 2: Atlas's reply, under the core
  `(async () => { const st = await import('./store.js'); st.db.chat.push(...${JSON.stringify(chat)}); st.changed('chat'); })()`,
  // 3: a results wall through the real tool
  `(async () => { const aware = await import('./aware.js'); const r = await aware.run({ name: 'show_panels', args: ${JSON.stringify(panels)} }); console.warn('[shots] ' + r.out); })()`,
  // 4: the terminal, with /status
  `(async () => { document.dispatchEvent(new KeyboardEvent('keydown', { key: '\`', code: 'Backquote', ctrlKey: true, bubbles: true })); await new Promise((r) => setTimeout(r, 300)); const i = document.querySelector('#termin'); i.value = '/status'; i.form.requestSubmit(); await new Promise((r) => setTimeout(r, 200)); i.value = '/bots'; i.form.requestSubmit(); })()`,
]);
console.log('screenshots →', out);
require('../main.js');
