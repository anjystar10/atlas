# ESLint (eslint:recommended + bug checks) over desktop/

Totals: no-unmodified-loop-condition error ×10, no-control-regex error ×9, no-irregular-whitespace error ×3, no-useless-escape error ×1, no-use-before-define warning ×69, no-promise-executor-return warning ×18, no-unused-vars warning ×14

## error · no-control-regex (9)
- desktop/botbrowser.js:230 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 50).trim();`
- desktop/mail.js:210 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const name = String(a.filename || `attachment-${index}`).replace(/[\\/:*?"<>|\x00-\x1f]+/g, '_').replace(/^\.+/, '').slice(0, 120) || 'attachment';`
- desktop/projectrules.js:138 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `if (!s || s.length > 400 || escapes(s) || /[\x00-\x1f<>:"|?*]/.test(s)) return null;`
- desktop/projectrules.js:147 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 60).trim();`
- desktop/projects.js:122 — Unexpected control character(s) in regular expression: \x1b.
    `const stripAnsi = (s) => String(s).replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\r(?!\n)/g, '\n');`
- desktop/renderer/md.js:23 — Unexpected control character(s) in regular expression: \x01, \x01.
    `t = t.replace(/\u0001(\d+)\u0001/g, (_m, i) => links[+i]);`
- desktop/renderer/md.js:24 — Unexpected control character(s) in regular expression: \x00, \x00.
    `return t.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[+i]}</code>`).replace(/\u0002(\d+)\u0002/g, (_m, i) => lits[+i]);`
- desktop/renderer/md.js:24 — Unexpected control character(s) in regular expression: \x02, \x02.
    `return t.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[+i]}</code>`).replace(/\u0002(\d+)\u0002/g, (_m, i) => lits[+i]);`
- desktop/sheets.js:67 — Unexpected control character(s) in regular expression: \x00, \x08, \x0b, \x0c, \x0e, \x1f.
    `const x = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])).replace(/[\u0000-\u0008\u000B\u000C\u000E-\`

## error · no-irregular-whitespace (3)
- desktop/mailtext.js:74 — Irregular whitespace not allowed.
    `s = decodeEntities(s).replace(/ /g, ' ').replace(/\r/g, '');`
- desktop/renderer/brain.js:395 — Irregular whitespace not allowed.
    `function strip(s) { return String(s).replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(`
- desktop/sheets.js:8 — Irregular whitespace not allowed.
    `const s = String(text || '').replace(/^﻿/, '');`

## error · no-unmodified-loop-condition (10)
- desktop/projects.js:140 — 'active' is not modified in this loop.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/projects.js:140 — 'MAX_ACTIVE' is not modified in this loop.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/remote.js:96 — 'ready' is not modified in this loop.
    `function flush() { while (ready && proc && queue.length) proc.stdin.write(queue.shift() + '\n'); }`
- desktop/remote.js:96 — 'proc' is not modified in this loop.
    `function flush() { while (ready && proc && queue.length) proc.stdin.write(queue.shift() + '\n'); }`
- desktop/renderer/linkcore.js:121 — 'running' is not modified in this loop.
    `while (running) {`
- desktop/renderer/linkwire.js:255 — 'link' is not modified in this loop.
    `while (!s.stopped && link) {`
- desktop/renderer/linkwire.js:318 — 'ended' is not modified in this loop.
    `while (!ended && link) {`
- desktop/renderer/linkwire.js:318 — 'link' is not modified in this loop.
    `while (!ended && link) {`
- desktop/renderer/sync.js:324 — 'inflight' is not modified in this loop.
    `while (inflight) { try { await inflight; } catch {} }`
- desktop/renderer/sync.js:423 — 'blocked' is not modified in this loop.
    `while (!blocked) {`

## error · no-useless-escape (1)
- desktop/pc.js:65 — Unnecessary escape character: \[.
    `function sendKeysText(t) { return String(t).replace(/[+^%~(){}\[\]]/g, (c) => '{' + c + '}').replace(/\r?\n/g, '{ENTER}'); }`

## warning · no-promise-executor-return (18)
- desktop/atlasvoice.js:77 — Return values from promise executor functions cannot be read.
    `const run = (cmd, args, ms = 120_000) => new Promise((res) => execFile(cmd, args, { timeout: ms, windowsHide: true }, (err, out, errOut) => res({ ok: !err, out:`
- desktop/attach.js:40 — Return values from promise executor functions cannot be read.
    `if (total + RESERVE > free) return reject(new Error(`Not enough room on this PC for “${name}” (${(total / MB).toFixed(1)} MB).`));`
- desktop/attach.js:82 — Return values from promise executor functions cannot be read.
    `if (!e.csize) return resolve(Buffer.alloc(0));`
- desktop/attach.js:235 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, 3000));`
- desktop/botbrowser.js:14 — Return values from promise executor functions cannot be read.
    `const sleep = (ms) => new Promise((r) => setTimeout(r, ms));`
- desktop/botfiles.js:135 — Return values from promise executor functions cannot be read.
    `await Promise.race([win.webContents.executeJavaScript('document.fonts ? document.fonts.ready.then(() => 1) : 1', true).catch(() => {}), new Promise((r) => setTi`
- desktop/main.js:46 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, Number(process.env.ATLAS_WAIT || 2500)));`
- desktop/pc.js:17 — Return values from promise executor functions cannot be read.
    `if (!WIN) return resolve({ code: -1, out: '', err: 'This only works on Windows.' });`
- desktop/projects.js:140 — Return values from promise executor functions cannot be read.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/projects.js:312 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, 4000));`
- desktop/remote.js:99 — Return values from promise executor functions cannot be read.
    `if (!WIN) return resolve('This only works on Windows.');`
- desktop/renderer/app.js:507 — Return values from promise executor functions cannot be read.
    `if (!cancelled && !followUp) { o.querySelector('.heard').textContent = "I didn't catch that."; await new Promise((r) => setTimeout(r, 1200)); }`
- desktop/renderer/bots.js:811 — Return values from promise executor functions cannot be read.
    `idle: async () => { while (workers.size) await new Promise((r) => setTimeout(r, 5)); await saving; },`
- desktop/renderer/brain.js:639 — Return values from promise executor functions cannot be read.
    `const sleep = (ms) => new Promise((r) => setTimeout(r, ms));`
- desktop/renderer/linkcore.js:61 — Return values from promise executor functions cannot be read.
    `const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));`
- desktop/renderer/linkwire.js:261 — Return values from promise executor functions cannot be read.
    `try { await link.putFrame(++s.seq, { sid, w: f.w, h: f.h, realW: f.realW, realH: f.realH, jpg: f.jpg }); } catch { await new Promise((r) => setTimeout(r, 800));`
- desktop/renderer/linkwire.js:264 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, Math.max(120, 350 - (Date.now() - t0))));   // at most about 3 frames a second`
- desktop/renderer/linkwire.js:323 — Return values from promise executor functions cannot be read.
    `} catch { await new Promise((res) => setTimeout(res, 700)); }`

## warning · no-unused-vars (14)
- desktop/remote.js:9 — 'path' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const path = require('path');`
- desktop/renderer/app.js:400 — 'videoSeconds' is defined but never used. Allowed unused vars must match /^_/u.
    `function videoSeconds(a) {`
- desktop/renderer/bots.js:10 — 'planProgress' is defined but never used. Allowed unused vars must match /^_/u.
    `import { parsePlanTree, planText, planProgress, missionDefaults, allows as crewAllows, capabilityOf, narrowAccess, budgetCheck, usageToday, dayOf, agentSlice,`
- desktop/renderer/botwire.js:7 — 'MAX_TOOL_CALLS' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/botwire.js:7 — 'callsOf' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/botwire.js:7 — 'msOf' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/connui.js:10 — 'toast' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render, settingRow } = ui;`
- desktop/renderer/linkwire.js:309 — 'frame' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `let seq = -1, ended = false, frame = null;`
- desktop/renderer/selfwire.js:141 — 'now' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const st = state(); const now = Date.now();`
- desktop/renderer/store.js:5 — 'MIN' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const MIN = 60000;`
- desktop/renderer/trade.js:136 — 'h' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render } = ui;`
- desktop/renderer/trade.js:207 — 'h' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render } = ui;`
- desktop/renderer/when.js:91 — 'used' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `while ((x = re.exec(rest))) { const v = +x[1], u = x[2][0]; total += u === 'h' ? v * 60 : u === 'd' ? v * 1440 : v; used += x[0]; }`
- desktop/trading.js:6 — 'BrowserWindow' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { app, safeStorage, dialog, Notification, BrowserWindow } = require('electron');`

## warning · no-use-before-define (69)
- desktop/attach.js:132 — 'size' was used before it was defined.
    `return { count: all.length, lines: all.slice(0, 300).map((e) => `  ${e.name}  (${size(e.size)})`).join('\n') + (all.length > 300 ? `\n  …and ${all.length - 300}`
- desktop/pc.js:23 — 't' was used before it was defined.
    `const finish = (r) => { if (done) return; done = true; clearTimeout(t); clearTimeout(late); resolve(r); };`
- desktop/pc.js:23 — 'late' was used before it was defined.
    `const finish = (r) => { if (done) return; done = true; clearTimeout(t); clearTimeout(late); resolve(r); };`
- desktop/remote.js:88 — 'waiting' was used before it was defined.
    `const w = waiting.shift(); if (w) w(line.startsWith('err') ? line.slice(4) : null);`
- desktop/remote.js:91 — 'waiting' was used before it was defined.
    `proc.on('exit', () => { proc = null; ready = false; for (const w of waiting.splice(0)) w('input stopped'); });`
- desktop/renderer/app.js:377 — 'MAX_ITEMS' was used before it was defined.
    `if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);`
- desktop/renderer/app.js:377 — 'MAX_ITEMS' was used before it was defined.
    `if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);`
- desktop/renderer/app.js:1012 — 'iv' was used before it was defined.
    `const x = pref(F); if (!x) { clearInterval(iv); return; }`
- desktop/renderer/app.js:1014 — 'resume' was used before it was defined.
    `if (x.pausedAt && x.breakUntil && x.breakUntil <= now) { resume(); chime(); return; }`
- desktop/renderer/app.js:1033 — 'iv' was used before it was defined.
    `clearInterval(iv); o.remove(); host.fullscreen(false); toast(mins >= 1 ? `Session done: ${mins} min. Nice work.` : 'Session ended.'); if (tab === 'log') render(`
- desktop/renderer/autonomyui.js:38 — 'timer' was used before it was defined.
    `const done = (v) => { clearInterval(tick); clearTimeout(timer); el.remove(); resolve(v); };`
- desktop/renderer/bots.js:219 — 'job' was used before it was defined.
    `const rootOf = (j) => { let r = j, n = 0; while (r.parent && job(r.parent) && n++ < 50) r = job(r.parent); return r; };`
- desktop/renderer/bots.js:219 — 'job' was used before it was defined.
    `const rootOf = (j) => { let r = j, n = 0; while (r.parent && job(r.parent) && n++ < 50) r = job(r.parent); return r; };`
- desktop/renderer/bots.js:223 — 'job' was used before it was defined.
    `const treeSize = (j) => { const r = rootOf(j); const seen = new Set(); const walk = (x) => { for (const id of x.children || []) { const k = job(id); if (k && !s`
- desktop/renderer/botwire.js:78 — 'extras' was used before it was defined.
    `const b = extras.data.specialists.find((s) => s.id === id);`
- desktop/renderer/botwire.js:79 — 'usage' was used before it was defined.
    `const c = budgetCheck(b && b.budget, usageToday(usage.get(id), dayOf(Date.now())));`
- desktop/renderer/botwire.js:86 — 'extras' was used before it was defined.
    `const sk = skillsIndex(extras.data.skills); if (sk) out.push(sk);`
- desktop/renderer/botwire.js:87 — 'extras' was used before it was defined.
    `if ((j.depth || 0) < MAX_DEPTH) { const t = teamIndex(extras.data.specialists.filter((s) => s.id !== j.specialist)); if (t) out.push(t + '\nHand a part to one w`
- desktop/renderer/botwire.js:88 — 'extras' was used before it was defined.
    `const r = rulesPrompt(extras.data.policy); if (r) out.push(r);`
- desktop/renderer/botwire.js:89 — 'extras' was used before it was defined.
    `if (j.routine) { const rt = extras.data.routines.find((x) => x.id === j.routine); if (rt) out.push(`This job is the routine "${rt.name}" (${describeSchedule(rt.`
- desktop/renderer/botwire.js:90 — 'extras' was used before it was defined.
    `const sp = j.specialist ? extras.data.specialists.find((s) => s.id === j.specialist) : null;`
- desktop/renderer/botwire.js:109 — 'bots' was used before it was defined.
    `fire: (r) => bots.deploy(r.goal, `This is the routine "${r.name}", which runs ${describeSchedule(r.schedule).toLowerCase()}.`, { specialist: r.specialist || nul`
- desktop/renderer/botwire.js:239 — 'trigRun' was used before it was defined.
    `nextTriggerAt: (botId, trigId) => trigRun.nextAt(botId, trigId),`
- desktop/renderer/botwire.js:240 — 'trigRun' was used before it was defined.
    `triggerProblem: (botId, trigId) => trigRun.problem(botId, trigId),`
- desktop/renderer/brain.js:23 — 'modelOf' was used before it was defined.
    `const id = modelOf(p), base = baseOf(p);`
- desktop/renderer/brain.js:23 — 'baseOf' was used before it was defined.
    `const id = modelOf(p), base = baseOf(p);`
- desktop/renderer/brain.js:32 — 'dontSwitch' was used before it was defined.
    `const other = !dontSwitch() && PROVIDERS.find((x) => !x.local && readyKey(x.id));`
- desktop/renderer/brain.js:32 — 'PROVIDERS' was used before it was defined.
    `const other = !dontSwitch() && PROVIDERS.find((x) => !x.local && readyKey(x.id));`
- desktop/renderer/brain.js:33 — 'convo' was used before it was defined.
    `if (other) { convo.status = `${modelOf(p)} is too big for this PC right now: using ${other.name}`; changed('chat'); return other; }`
- desktop/renderer/brain.js:33 — 'modelOf' was used before it was defined.
    `if (other) { convo.status = `${modelOf(p)} is too big for this PC right now: using ${other.name}`; changed('chat'); return other; }`
- desktop/renderer/brain.js:34 — 'dontSwitch' was used before it was defined.
    `throw new Failure(v.reason + (dontSwitch() ? ' (Don\'t Switch Services is on, so I stayed with it.)' : ''));`
- desktop/renderer/brain.js:69 — 'byId' was used before it was defined.
    `let id = 'c_' + Date.now().toString(36); while (byId(id)) id += Math.floor(Math.random() * 36).toString(36);`
- desktop/renderer/brain.js:71 — 'byId' was used before it was defined.
    `saveCustoms([...mineOnly(), p]); return byId(p.id);`
- desktop/renderer/brain.js:141 — 'dontSwitch' was used before it was defined.
    `if (chosen && available(chosen)) return readyKey(chosen.id) || dontSwitch() ? chosen : fallbacks(chosen.id)[0] || chosen;`
- desktop/renderer/brain.js:180 — 'TOOLS' was used before it was defined.
    `export function session(p, key, history, user, media, tools = TOOLS) {`
- desktop/renderer/crewui.js:287 — 'PRESETS' was used before it was defined.
    `${b.builtIn ? '' : `<div class="chips" id="bud" style="margin-top:12px">${PRESETS.map(([n, p]) => `<button class="chip sm${b.budget.callsPerDay === p.callsPerDa`
- desktop/renderer/crewui.js:291 — 'PRESETS' was used before it was defined.
    `today.querySelectorAll('#bud [data-n]').forEach((x) => (x.onclick = () => { crew.updateBot(b.id, { budget: { ...b.budget, ...PRESETS.find(([n]) => n === x.datas`
- desktop/renderer/crewui.js:318 — 'HOME_LABELS' was used before it was defined.
    `const where = k.h(`<div class="card" style="margin-top:12px"><div class="small muted" style="font-weight:600;margin-bottom:8px">WHERE IT WORKS</div><div class="`
- desktop/renderer/crewui.js:412 — 'wireSrc' was used before it was defined.
    `o.querySelector('#tf').innerHTML = fields[type]; wireSrc(o);`
- desktop/renderer/crewui.js:414 — 'wireSrc' was used before it was defined.
    `wireSrc(o);`
- desktop/renderer/crewui.js:545 — 'flatten' was used before it was defined.
    `const total = (t ? flatten(t) : [j]).reduce((s, m) => ({ calls: s.calls + ((m.cost || {}).calls || 0), tin: s.tin + ((m.cost || {}).tokensIn || 0), tout: s.tout`
- desktop/renderer/crewui.js:546 — 'fmtTok' was used before it was defined.
    `col.appendChild(k.h(`<div class="small muted" style="margin-top:12px">Cost: ${total.calls} AI calls · ${fmtTok(total.tin)} in / ${fmtTok(total.tout)} out${t && `
- desktop/renderer/crewui.js:546 — 'fmtTok' was used before it was defined.
    `col.appendChild(k.h(`<div class="small muted" style="margin-top:12px">Cost: ${total.calls} AI calls · ${fmtTok(total.tin)} in / ${fmtTok(total.tout)} out${t && `
- desktop/renderer/hud.js:20 — 'root' was used before it was defined.
    `const $ = (sel) => root && root.querySelector(sel);`
- desktop/renderer/hud.js:20 — 'root' was used before it was defined.
    `const $ = (sel) => root && root.querySelector(sel);`
- desktop/renderer/hud.js:105 — 'offChange' was used before it was defined.
    `offChange = onChange((what) => {`
- desktop/renderer/hud.js:112 — 'ticker' was used before it was defined.
    `ticker = setInterval(tick, 100);`
- desktop/renderer/hud.js:113 — 'slowTicker' was used before it was defined.
    `slowTicker = setInterval(() => { refreshMeta(); refreshCards(); }, 1000);`
- desktop/renderer/hud.js:238 — 'current' was used before it was defined.
    `speakTap = { orig, get ctx() { return ctx; }, level: () => { if (!an || !current || current.paused) return null; an.getFloatTimeDomainData(buf); let s = 0; for `
- desktop/renderer/hud.js:238 — 'current' was used before it was defined.
    `speakTap = { orig, get ctx() { return ctx; }, level: () => { if (!an || !current || current.paused) return null; an.getFloatTimeDomainData(buf); let s = 0; for `
- desktop/renderer/hud.js:510 — 'COMMANDS_RUN' was used before it was defined.
    `const run = COMMANDS_RUN[c.cmd]; await run(c.arg);`
- desktop/renderer/linkwire.js:61 — 'peers' was used before it was defined.
    `for (const p of peers()) link.send(p.device, 'sync', {}).catch(() => {});`
- desktop/renderer/linkwire.js:77 — 'controlled' was used before it was defined.
    `case 'screen_keep': { const s = controlled.get(m.sid); if (s) s.lastKeep = Date.now(); return; }`
- desktop/renderer/linkwire.js:78 — 'controlled' was used before it was defined.
    `case 'screen_stop': { if (controlled.has(m.sid)) endControlled(m.sid, 'Your phone stopped watching.'); if (viewer && viewer.sid === m.sid) viewer.end('The phone`
- desktop/renderer/linkwire.js:78 — 'viewer' was used before it was defined.
    `case 'screen_stop': { if (controlled.has(m.sid)) endControlled(m.sid, 'Your phone stopped watching.'); if (viewer && viewer.sid === m.sid) viewer.end('The phone`
- desktop/renderer/linkwire.js:78 — 'viewer' was used before it was defined.
    `case 'screen_stop': { if (controlled.has(m.sid)) endControlled(m.sid, 'Your phone stopped watching.'); if (viewer && viewer.sid === m.sid) viewer.end('The phone`
- desktop/renderer/linkwire.js:78 — 'viewer' was used before it was defined.
    `case 'screen_stop': { if (controlled.has(m.sid)) endControlled(m.sid, 'Your phone stopped watching.'); if (viewer && viewer.sid === m.sid) viewer.end('The phone`
- desktop/renderer/linkwire.js:173 — 'ago' was used before it was defined.
    `return `Your phone isn't online right now${p ? ` (last seen ${ago(p.agoMs)})` : ''}. I'll send it as soon as it is (open Atlas on the phone).`;`
- desktop/renderer/linkwire.js:295 — 'ago' was used before it was defined.
    `if (!p.online) throw new Error(`Your phone isn't online (last seen ${ago(p.agoMs)}). Open Atlas on it, or turn on "Reachable from Your Laptop" there.`);`
- desktop/renderer/linkwire.js:311 — 'keep' was used before it was defined.
    `if (ended) return; ended = true; viewer = null; clearInterval(keep);`
- desktop/renderer/sync.js:35 — 'timer' was used before it was defined.
    `clearTimeout(timer);                       // no retry waiting to run against a link that's gone`
- desktop/renderer/threadcore.js:17 — 'clip' was used before it was defined.
    `export const directId = (botId) => DIRECT + clip(botId, 56);`
- desktop/renderer/voice.js:126 — 'free' was used before it was defined.
    `if (mine !== gen) { free(got.clip); return; }`
- desktop/renderer/voice.js:131 — 'free' was used before it was defined.
    `if (mine !== gen) { if (next) next.then((n) => free(n.clip)); return; }`
- desktop/renderer/voice.js:132 — 'free' was used before it was defined.
    `if (!ok) { if (next) next.then((n) => free(n.clip)); state.lastProblem = `${engineName(got.used)}: the audio couldn't be played`; noteFallback(); return systemS`
- desktop/renderer/voice.js:180 — 'lastHeard' was used before it was defined.
    `const r = await host.atlasVoice.speak(text, lastHeard && lastHeard.language ? languageName(lastHeard.language) : 'English');`
- desktop/renderer/voice.js:180 — 'lastHeard' was used before it was defined.
    `const r = await host.atlasVoice.speak(text, lastHeard && lastHeard.language ? languageName(lastHeard.language) : 'English');`
- desktop/renderer/voice.js:180 — 'lastHeard' was used before it was defined.
    `const r = await host.atlasVoice.speak(text, lastHeard && lastHeard.language ? languageName(lastHeard.language) : 'English');`
- desktop/renderer/voice.js:437 — 'language' was used before it was defined.
    `if (text) { learn(ep); Object.assign(lastHeard, { language: got.language || (language().slice(0, 2).toLowerCase() || null), unsure: got.unsure, at: Date.now() }`

