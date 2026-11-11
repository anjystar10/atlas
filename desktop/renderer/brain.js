// Atlas's mind on the PC: any AI service (or a local model), a pool of keys per service that switches by itself when
// one runs out, and the tools: the Log, the web, its own settings, and the PC itself.
import { db, pref, setPref, saveChat, changed, OPEN, findTask, createTask, updateTask, respond, cancelTask, deleteTask, remember, forget } from './store.js';
import { plainReply, plainMemory } from './plain.js';
import * as fitRules from './fit.js';
import * as conn from './connect.js';   // connections: MCP servers and web APIs
const host = window.atlasHost;

// ---- this PC, measured: a local model is checked against free memory before it's used (see fit.js) ----
export const device = fitRules.profileCache(() => (host.device ? host.device() : null), 30000);
/** The local model in use and where it would run now, or null when it isn't a local model. */
export async function localVerdict(p, fresh = true) {
  if (!p || !p.local) return null;
  const prof = await device.get(fresh);
  if (!prof) return null;
  const id = modelOf(p), base = baseOf(p);
  const rt = (prof.runtimes || []).find((r) => r.base === base);
  const m = (rt && rt.models.find((x) => x.id === id)) || { id, sizeMb: -1 };
  return fitRules.fit(prof, m);
}
/** Before a local model answers: if it would freeze this PC right now, use a cloud service instead (unless Don't Switch), or say why not. */
async function guardLocal(p) {
  const v = await localVerdict(p).catch(() => null);
  if (!v || v.ok) return p;
  const other = !dontSwitch() && PROVIDERS.find((x) => !x.local && readyKey(x.id));
  if (other) { convo.status = `${modelOf(p)} is too big for this PC right now: using ${other.name}`; changed('chat'); return other; }
  throw new Failure(v.reason + (dontSwitch() ? ' (Don\'t Switch Services is on, so I stayed with it.)' : ''));
}
// ---- end hardware ----

// Every base address and default model below was checked against the service's own docs (November 2026), and each
// default model supports tool calling. Same ids, addresses and order as the phone (Providers.kt); a test compares them.
export const BUILT_IN = [
  { id: 'gemini', name: 'Gemini', style: 'gemini', base: 'https://generativelanguage.googleapis.com/v1beta/models/', model: 'gemini-3.8-flash', keyUrl: 'https://aistudio.google.com/apikey', hint: 'AIza…', note: 'Google. Free tier with limits; the best all-rounder for Atlas' },
  { id: 'openai', name: 'OpenAI', style: 'openai', base: 'https://api.openai.com/v1', model: 'gpt-6-sol', keyUrl: 'https://platform.openai.com/api-keys', hint: 'sk-…', note: 'Paid' },
  { id: 'anthropic', name: 'Claude', style: 'anthropic', base: 'https://api.anthropic.com/v1', model: 'claude-sonnet-5-5', keyUrl: 'https://console.anthropic.com/settings/keys', hint: 'sk-ant-…', note: 'Paid' },
  { id: 'openrouter', name: 'OpenRouter', style: 'openai', base: 'https://openrouter.ai/api/v1', model: 'openrouter/auto', keyUrl: 'https://openrouter.ai/keys', hint: 'sk-or-…', note: 'Many models behind one key; some free' },
  { id: 'groq', name: 'Groq', style: 'openai', base: 'https://api.groq.com/openai/v1', model: 'openai/gpt-oss-120b', keyUrl: 'https://console.groq.com/keys', hint: 'gsk_…', note: 'Very fast; free tier with limits' },
  { id: 'cerebras', name: 'Cerebras', style: 'openai', base: 'https://api.cerebras.ai/v1', model: 'gpt-oss-120b', keyUrl: 'https://cloud.cerebras.ai', hint: 'your key', note: 'Very fast; $5 trial credit for 30 days, then paid' },
  { id: 'sambanova', name: 'SambaNova', style: 'openai', base: 'https://api.sambanova.ai/v1', model: 'gpt-oss-120b', keyUrl: 'https://cloud.sambanova.ai', hint: 'your key', note: 'Fast; free tier of 20 requests a day' },
  { id: 'cohere', name: 'Cohere', style: 'openai', base: 'https://api.cohere.ai/compatibility/v1', model: 'command-a-plus-05-2026', keyUrl: 'https://dashboard.cohere.com/api-keys', hint: 'your key', note: 'Free trial key: 1,000 calls a month' },
  { id: 'mistral', name: 'Mistral', style: 'openai', base: 'https://api.mistral.ai/v1', model: 'mistral-large-latest', keyUrl: 'https://console.mistral.ai', hint: 'your key', note: 'Paid (the API needs billing turned on)' },
  { id: 'zai', name: 'Z.AI (GLM)', style: 'openai', base: 'https://api.z.ai/api/paas/v4', model: 'glm-5.3', keyUrl: 'https://z.ai/model-api', hint: 'your key', note: 'GLM models; paid' },
  { id: 'fireworks', name: 'Fireworks', style: 'openai', base: 'https://api.fireworks.ai/inference/v1', model: 'accounts/fireworks/models/kimi-k2-instruct-0905', keyUrl: 'https://fireworks.ai', hint: 'your key', note: 'Open models; paid' },
  { id: 'ovh', name: 'OVHcloud AI', style: 'openai', base: 'https://oai.endpoints.kepler.ai.cloud.ovh.net/v1', model: 'Qwen3.5-397B-A17B', keyUrl: 'https://endpoints.ai.cloud.ovh.net', hint: 'your key', note: 'European hosting; paid' },
  { id: 'deepseek', name: 'DeepSeek', style: 'openai', base: 'https://api.deepseek.com/v1', model: 'deepseek-chat', keyUrl: 'https://platform.deepseek.com/api_keys', hint: 'sk-…', note: 'Cheap; paid' },
  { id: 'xai', name: 'xAI Grok', style: 'openai', base: 'https://api.x.ai/v1', model: 'grok-4.7', keyUrl: 'https://console.x.ai', hint: 'xai-…', note: 'Paid' },
  { id: 'local', name: 'Local model', style: 'openai', base: 'http://localhost:11434/v1', model: '', keyUrl: '', hint: '', local: true, note: 'A model running on this PC (Ollama or LM Studio)' },
  { id: 'custom', name: 'Other (OpenAI-compatible)', style: 'openai', base: '', model: '', keyUrl: '', hint: 'your key', custom: true, note: 'Any service that speaks the OpenAI chat format' },
];
// Every service: built in, then the user's own (as many as they like). Kept as one array so callers see both.
export const PROVIDERS = [...BUILT_IN];
const customFrom = (o) => ({ id: o.id, name: o.name, style: 'openai', base: (o.base || '').trim().replace(/\/+$/, ''), model: (o.model || '').trim(), keyUrl: '', hint: 'your key', custom: true, note: 'Added by you' });
/** Re-reads the services you added (call after changing them). */
export function loadCustoms() {
  const list = (pref('custom_services', []) || []).filter((o) => o && typeof o.id === 'string' && o.id.startsWith('c_') && (o.name || '').trim());
  PROVIDERS.splice(0, PROVIDERS.length, ...BUILT_IN, ...list.map(customFrom));
}
function saveCustoms(list) { setPref('custom_services', list.map((p) => ({ id: p.id, name: p.name, base: p.base, model: p.model }))); loadCustoms(); }
const mineOnly = () => PROVIDERS.filter((p) => p.custom && p.id.startsWith('c_'));
export function addCustom(name, base, model) {
  let id = 'c_' + Date.now().toString(36); while (byId(id)) id += Math.floor(Math.random() * 36).toString(36);
  const p = customFrom({ id, name: String(name).trim().slice(0, 40), base, model });
  saveCustoms([...mineOnly(), p]); return byId(p.id);
}
export function updateCustom(id, name, base, model) {
  saveCustoms(mineOnly().map((p) => (p.id === id ? customFrom({ id, name: String(name).trim().slice(0, 40) || p.name, base, model }) : p)));
}
export function removeCustom(id) {
  if (!String(id).startsWith('c_')) return;
  saveCustoms(mineOnly().filter((p) => p.id !== id));
  const all = { ...pref('keys', {}) }; delete all[id]; setPref('keys', all);
  if (pref('provider', '') === id) setPref('provider', '');
}
export const byId = (id) => PROVIDERS.find((p) => p.id === id);
// Only prefixes that belong to one service; many services' keys have no telling shape (or look like OpenAI's).
export function guess(k) {
  k = k.trim();
  if (k.startsWith('AIza')) return 'gemini'; if (k.startsWith('sk-ant-')) return 'anthropic'; if (k.startsWith('sk-or-')) return 'openrouter';
  if (k.startsWith('gsk_')) return 'groq'; if (k.startsWith('xai-')) return 'xai'; if (k.startsWith('sk-')) return 'openai'; return null;
}
/** Where a pasted key goes: its own service, unless it only looks like OpenAI's while another OpenAI-style service is shown. */
export function keyTarget(k, shownId) {
  const g = guess(k); if (!g) return shownId;
  const shown = byId(shownId);
  return g === 'openai' && shown && shown.id !== 'openai' && shown.style === 'openai' && !shown.local ? shownId : g;
}
export const modelOf = (p) => (pref('model_' + p.id, '') || p.model || '').trim();
export const baseOf = (p) => (p.id === 'custom' || p.id === 'local' ? (pref('base_' + p.id, '') || p.base) : p.base).replace(/\/+$/, '');

// ------------------------------------------------------------------ the key pool

export function keys(id) { const all = pref('keys', {}); return Array.isArray(all[id]) ? all[id] : []; }
function saveKeys(id, list) { const all = { ...pref('keys', {}) }; all[id] = list; setPref('keys', all); }
const usable = (e, now = Date.now()) => !e.bad && (e.restUntil || 0) <= now;
export function addKey(id, key) {
  const k = key.trim(); if (!k) return;
  const list = keys(id).map((e) => ({ ...e })); const have = list.find((e) => e.key === k);
  if (have) Object.assign(have, { bad: false, restUntil: 0, fails: 0, problem: '' }); else list.push({ key: k, restUntil: 0, bad: false, fails: 0, problem: '' });
  saveKeys(id, list);
}
export function removeKey(id, key) { saveKeys(id, keys(id).filter((e) => e.key !== key)); }
export function wakeKey(id, key) { saveKeys(id, keys(id).map((e) => (e.key === key ? { ...e, bad: false, restUntil: 0, fails: 0, problem: '' } : e))); }
export function keyFor(id) {
  if (byId(id)?.local) return 'local';
  const l = keys(id).filter((e) => !e.bad); if (!l.length) return null;
  return (l.find((e) => usable(e)) || l.reduce((a, b) => ((a.restUntil || 0) <= (b.restUntil || 0) ? a : b))).key;
}
// a local model needs no key: it's ready once one has been chosen in Me
export function readyKey(id, except) { if (byId(id)?.local) return modelOf(byId(id)) && except !== 'local' ? 'local' : null; return keys(id).find((e) => usable(e) && e.key !== except)?.key || null; }
const hasKeys = (id) => (byId(id)?.local ? !!modelOf(byId(id)) : keys(id).some((e) => !e.bad));
function rest(id, key, code, problem) {
  const now = Date.now();
  saveKeys(id, keys(id).map((e) => {
    if (e.key !== key) return e;
    const n = { ...e, problem: String(problem || '').slice(0, 160) };
    if ([400, 401, 403].includes(code)) n.bad = true;
    else if (code === 402) n.restUntil = now + 6 * 3600000;
    else { n.fails = (e.fails || 0) + 1; n.restUntil = now + Math.min(24 * 3600000, 120000 * 2 ** Math.min(n.fails - 1, 10)); }
    return n;
  }));
}
function worked(id, key) { if (keys(id).some((e) => e.key === key && (e.fails || e.problem))) saveKeys(id, keys(id).map((e) => (e.key === key ? { ...e, fails: 0, problem: '' } : e))); }
export const keyExhausted = (f) => [429, 402, 401, 403].includes(f.code) || (f.code === 400 && /key/i.test(f.message || ''));
const available = (p) => hasKeys(p.id) && (!p.custom || baseOf(p));
export function fallbacks(except) { loadCustoms(); return PROVIDERS.filter((p) => p.id !== except && available(p) && readyKey(p.id)); }
export function active() {
  loadCustoms(); // cheap: a short list from settings, so services you added are always current
  const chosen = byId(pref('provider', ''));
  if (chosen && available(chosen)) return readyKey(chosen.id) || dontSwitch() ? chosen : fallbacks(chosen.id)[0] || chosen;
  return PROVIDERS.find((p) => available(p) && readyKey(p.id)) || PROVIDERS.find(available) || null;
}

/** Finds models running on this PC (Ollama, LM Studio). */
export async function detectLocal() {
  const found = [];
  const tries = [{ name: 'Ollama', base: 'http://localhost:11434/v1' }, { name: 'LM Studio', base: 'http://localhost:1234/v1' }];
  for (const t of tries) {
    const r = await host.fetch(t.base + '/models', { timeout: 2500 });
    if (r.ok) { try { for (const m of JSON.parse(r.text).data || []) found.push({ server: t.name, base: t.base, model: m.id }); } catch {} }
  }
  return found;
}

// ------------------------------------------------------------------ talking to a service

export class Failure extends Error { constructor(msg, code = 0, offline = false) { super(msg); this.code = code; this.offline = offline; } }

function explain(service, code, body) {
  let msg = ''; try { const o = JSON.parse(body); msg = (o.error && (o.error.message || o.error)) || o.message || ''; if (typeof msg !== 'string') msg = JSON.stringify(msg); } catch {}
  if (code === 401 || code === 403) return `${service} turned the key down: ${(msg || 'not authorised').slice(0, 160)}`;
  if (code === 400) return /key/i.test(msg) && /invalid|not valid/i.test(msg) ? `That ${service} key isn't valid.` : `${service} didn't accept the request: ${(msg || 'bad request').slice(0, 180)}`;
  if (code === 404) return `${service} doesn't know that model: ${msg.slice(0, 160)}`;
  if (code === 402) return `${service} says the account has no credit left.`;
  if (code === 429) return `The ${service} limit is used up for now.`;
  if (code >= 500) return `${service} is having trouble right now (${code}).`;
  return `${service} error ${code}: ${(msg || body || '').slice(0, 160)}`;
}
async function post(url, headers, body, service, timeout = 180000) {
  const r = await host.fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body), timeout });
  if (r.status === 0) throw new Failure(r.error === 'timed out' ? `${service} took too long to answer.` : `Couldn't reach ${service}${r.error ? ' (' + r.error + ')' : ''}.`, 0, true);
  if (!r.ok) { const f = new Failure(explain(service, r.status, r.text || ''), r.status); f.retryAfterMs = retryHint(r.text, r.retryAfter); throw f; }
  try { return JSON.parse(r.text); } catch { throw new Failure(`${service} sent back something unreadable.`); }
}

function params(f) { return f.parameters || { type: 'object', properties: {} }; }

/** One conversation with a service for one request. call() → {text, calls:[{name,args,id}]}; results(list) answers the calls. */
export function session(p, key, history, user, media, tools = TOOLS) {
  const model = modelOf(p), base = baseOf(p);
  if (p.style === 'gemini') {
    const contents = history.map(([u, t]) => ({ role: u ? 'user' : 'model', parts: [{ text: t }] }));
    contents.push({ role: 'user', parts: [...media.map((m) => ({ inline_data: { mime_type: m.mime, data: m.base64 } })), { text: user }] });
    return {
      key, contents,
      async call(system) {
        const req = { systemInstruction: { parts: [{ text: system }] }, contents: trimScreens(contents), tools: [{ functionDeclarations: tools }] };
        if (model.startsWith('gemini-3')) req.generationConfig = { thinkingConfig: { thinkingLevel: 'low' } };
        // Google's adjustable content filters at their most open setting (the user's choice); the model's own limits still apply
        req.safetySettings = ['HARM_CATEGORY_HARASSMENT', 'HARM_CATEGORY_HATE_SPEECH', 'HARM_CATEGORY_SEXUALLY_EXPLICIT', 'HARM_CATEGORY_DANGEROUS_CONTENT'].map((category) => ({ category, threshold: 'BLOCK_NONE' }));
        const r = await post(`${p.base}${model}:generateContent`, { 'x-goog-api-key': this.key }, req, 'Gemini');
        if (r.promptFeedback && r.promptFeedback.blockReason) throw new Failure(`Gemini refused that message (${r.promptFeedback.blockReason}).`);
        const cand = (r.candidates || [])[0]; if (!cand) throw new Failure('Gemini sent back an empty answer.');
        const content = cand.content || { role: 'model', parts: [] }; content.role = 'model'; content.parts = content.parts || [];
        let text = ''; const calls = [];
        for (const part of content.parts) { if (part.thought) continue; if (part.functionCall) calls.push({ name: part.functionCall.name, args: part.functionCall.args || {}, id: part.functionCall.id }); if (part.text) text += part.text; }
        contents.push(content);   // as sent, thought signatures and all (Gemini needs them back)
        if (!calls.length && !text.trim()) throw new Failure(cand.finishReason === 'MALFORMED_FUNCTION_CALL' ? 'Gemini got muddled calling a tool. Try again.' : 'Gemini sent back an empty answer.');
        return { text: text.trim(), calls };
      },
      results(list) {
        const parts = list.map(({ call, out }) => ({ functionResponse: { name: call.name, ...(call.id ? { id: call.id } : {}), response: { result: out } } }));
        for (const { image } of list) if (image) parts.push({ inline_data: { mime_type: image.mime, data: image.base64 } });
        contents.push({ role: 'user', parts });
      }
    };
  }
  if (p.style === 'anthropic') {
    const messages = history.map(([u, t]) => ({ role: u ? 'user' : 'assistant', content: t }));
    messages.push({ role: 'user', content: media.length ? [...media.map((m) => ({ type: m.mime === 'application/pdf' ? 'document' : 'image', source: { type: 'base64', media_type: m.mime, data: m.base64 } })), { type: 'text', text: user }] : user });
    while (messages.length && messages[0].role !== 'user') messages.shift();
    return {
      key, messages,
      async call(system) {
        const r = await post(`${base}/messages`, { 'x-api-key': this.key, 'anthropic-version': '2023-06-01' },
          { model, max_tokens: 4096, system, messages: trimScreens(messages), tools: tools.map((f) => ({ name: f.name, description: f.description, input_schema: params(f) })) }, 'Claude');
        let text = ''; const calls = [];
        for (const b of r.content || []) { if (b.type === 'text') text += b.text; if (b.type === 'tool_use') calls.push({ name: b.name, args: b.input || {}, id: b.id }); }
        messages.push({ role: 'assistant', content: r.content || [] });
        if (!calls.length && !text.trim()) throw new Failure(`Claude sent back an empty answer (${r.stop_reason}).`);
        return { text: text.trim(), calls };
      },
      results(list) {
        messages.push({ role: 'user', content: list.map(({ call, out, image }) => ({ type: 'tool_result', tool_use_id: call.id,
          content: image ? [{ type: 'text', text: out }, { type: 'image', source: { type: 'base64', media_type: image.mime, data: image.base64 } }] : out })) });
      }
    };
  }
  // OpenAI-style: OpenAI, OpenRouter, Groq, DeepSeek, xAI, a local model, any compatible service
  const messages = history.map(([u, t]) => ({ role: u ? 'user' : 'assistant', content: t }));
  messages.push({ role: 'user', content: media.length ? [{ type: 'text', text: user }, ...media.map((m) => (m.mime === 'application/pdf'
    ? { type: 'file', file: { filename: m.name || 'document.pdf', file_data: `data:application/pdf;base64,${m.base64}` } }
    : { type: 'image_url', image_url: { url: `data:${m.mime};base64,${m.base64}` } }))] : user });
  return {
    key, messages,
    async call(system) {
      if (!base) throw new Failure(`Set the address of ${p.name} in Me.`);
      if (!model) throw new Failure(`Choose a model for ${p.name} in Me.`);
      const body = { model, messages: [{ role: 'system', content: system }, ...trimScreens(messages)], tools: tools.map((f) => ({ type: 'function', function: { name: f.name, description: f.description, parameters: params(f) } })) };
      if (p.id === 'openai' && model.startsWith('gpt-6')) body.reasoning_effort = 'none';
      const headers = p.local ? {} : { Authorization: `Bearer ${this.key}` };
      if (p.id === 'openrouter') { headers['HTTP-Referer'] = 'https://github.com/anjystar10/atlas-app'; headers['X-Title'] = 'Atlas'; }
      const r = await post(`${base}/chat/completions`, headers, body, p.name, p.local ? 600000 : 180000);
      const choice = (r.choices || [])[0]; const m = choice && choice.message;
      if (!m) throw new Failure(`${p.name} sent back an empty answer.`);
      const text = typeof m.content === 'string' ? m.content : '';
      const calls = (m.tool_calls || []).map((tc, i) => { let args = {}; try { args = JSON.parse(tc.function.arguments || '{}'); } catch {} return { name: tc.function.name, args, id: tc.id || 'call_' + i }; });
      const keep = { role: 'assistant', content: !text && calls.length ? null : text }; if (m.tool_calls) keep.tool_calls = m.tool_calls;
      messages.push(keep);
      if (!calls.length && !text.trim()) throw new Failure(`${p.name} sent back an empty answer.`);
      return { text: text.trim(), calls };
    },
    results(list) {
      for (const { call, out } of list) messages.push({ role: 'tool', tool_call_id: call.id, content: out });
      const imgs = list.filter((x) => x.image);
      if (imgs.length) messages.push({ role: 'user', content: [{ type: 'text', text: '(the screen, as just captured)' }, ...imgs.map((x) => ({ type: 'image_url', image_url: { url: `data:${x.image.mime};base64,${x.image.base64}` } }))] });
    }
  };
}

/** Old screenshots and screen readings in a long task are dropped (only the latest two stay), so tasks stay within limits. */
function trimScreens(list) {
  const isImg = (part) => part && (part.inline_data || part.type === 'image_url' || part.type === 'image');
  const idx = []; list.forEach((m, i) => { const c = m.parts || (Array.isArray(m.content) ? m.content : []); if (c.some((x) => isImg(x) || (x.type === 'tool_result' && Array.isArray(x.content) && x.content.some(isImg)))) idx.push(i); });
  const old = new Set(idx.slice(0, -2).filter((i) => i !== 0 && i !== list.length - 1));
  if (!old.size) return list;
  return list.map((m, i) => {
    if (!old.has(i)) return m;
    const c = JSON.parse(JSON.stringify(m));
    if (c.parts) c.parts = c.parts.filter((x) => !x.inline_data);
    if (Array.isArray(c.content)) c.content = c.content.map((x) => (x.type === 'tool_result' && Array.isArray(x.content) ? { ...x, content: x.content.filter((y) => !isImg(y)) } : x)).filter((x) => !isImg(x));
    if (Array.isArray(c.content) && !c.content.length) c.content = '(an earlier screenshot, left out)';
    if (c.parts && !c.parts.length) c.parts = [{ text: '(an earlier screenshot, left out)' }];
    return c;
  });
}

// ------------------------------------------------------------------ tools

const str = (description, e) => ({ type: 'string', description, ...(e ? { enum: e } : {}) });
const int = (description) => ({ type: 'integer', description });
const bool = (description) => ({ type: 'boolean', description });
const fn = (name, description, props, required = []) => ({ name, description, ...(props ? { parameters: { type: 'object', properties: props, ...(required.length ? { required } : {}) } } : {}) });
export const SETTINGS = ['speak_replies', 'voice', 'theme', 'on_screen', 'your_name', 'alarm_sound', 'start_with_windows', 'dont_switch', 'speaking_speed', 'keep_listening', 'listen_language'];

export const TOOLS = [
  fn('set_reminder', 'Log a reminder. At its time Atlas rings an alarm (important things) or sends a notification (lighter things), on this PC and on the phone.', {
    title: str("Short title, e.g. 'Pick up my sister'"), when: str("Local date and time 'YYYY-MM-DD HH:MM' (24h). Leave out only if no time was given."),
    alert: str('alarm: important or time-critical. notification: lighter things. If the user says which, do that.', ['alarm', 'notification']),
    duration_min: int('How long it takes, minutes (default 30)'), repeat: str('How it repeats', ['none', 'daily', 'weekdays', 'weekly']), notes: str('Optional details') }, ['title']),
  fn('update_reminder', 'Change a logged reminder: done, cancel, delete, move, rename, or make it an alarm / a notification.', {
    id: str("The reminder's id from your instructions"), action: str('What to do', ['done', 'cancel', 'delete', 'move', 'rename', 'make_alarm', 'make_notification']),
    when: str("For move: 'YYYY-MM-DD HH:MM'"), title: str('For rename') }, ['id', 'action']),
  fn('remember', 'Save a lasting fact or preference the user wants kept (shown in the Log, on both devices).', { text: str('The fact, in a short sentence') }, ['text']),
  fn('forget', 'Delete a saved memory.', { id: str("The memory's id from your instructions") }, ['id']),
  fn('read_log', "Look things up in the user's Log: reminders and what happened to them, memories, history. Newest first.", { query: str('Words to search for; leave out for the latest'), limit: int('How many at most (default 30)') }),
  fn('web_search', "Search the internet. Use it for anything current or that you're unsure of. Returns results with sources.", { query: str('What to search for') }, ['query']),
  fn('read_page', 'Read a web page and get its text.', { url: str("The page's address") }, ['url']),
  fn('change_setting', "Change one of Atlas's own settings when the user asks. Current values are in your instructions.", { setting: str('Which', SETTINGS), value: str('on/off, a voice name, dark/light, or the name') }, ['setting', 'value']),
  fn('start_focus', "Start a focus session when the user says they're doing something right now. Full screen, a timer, no distractions.", { title: str("What they're doing"), minutes: int('Planned length, if known'), id: str("The reminder's id, if from the Log") }, ['title']),
  fn('open_app', 'Open an app on this PC by name (Start menu name, e.g. "Spotify", "Visual Studio Code", "Notepad").', { name: str("The app's name") }, ['name']),
  fn('list_apps', 'List the apps installed on this PC.'),
  fn('open_link', 'Open a web link in the default browser.', { url: str('The link') }, ['url']),
  fn('open_path', 'Open a file or folder on this PC with its usual app. Paths may use ~ (home), Desktop, Documents, Downloads.', { path: str('The path') }, ['path']),
  fn('list_files', 'List what is in a folder on this PC.', { path: str('The folder (e.g. ~/Documents, Downloads)') }, ['path']),
  fn('read_file', 'Read a text or Word file on this PC.', { path: str('The file') }, ['path']),
  fn('write_file', 'Create or overwrite a text file on this PC. The user is asked first.', { path: str('The file'), text: str('Its full contents') }, ['path', 'text']),
  fn('run_command', 'Run a PowerShell command on this PC and get its output. The user is asked first. Prefer the other tools when one fits.', { command: str('The PowerShell command'), timeout_s: int('Seconds to allow (default 60)') }, ['command']),
  fn('look_at_screen', "See the PC's screen (a screenshot). Use before clicking; coordinates for click come from it."),
  fn('click', 'Click at a point on the screen, in the coordinates of the latest look_at_screen.', { x: int('x'), y: int('y'), button: str('Which button', ['left', 'right']), double: bool('Double-click') }, ['x', 'y']),
  fn('type_text', 'Type text into whatever has focus on the PC.', { text: str('What to type') }, ['text']),
  fn('press_keys', "Press keys or a shortcut, e.g. 'enter', 'ctrl+s', 'alt+tab', 'ctrl+shift+t'.", { keys: str('The keys') }, ['keys']),
  fn('scroll', 'Scroll whatever is under the mouse.', { direction: str('Which way', ['up', 'down']), amount: int('How far, 1-20 (default 5)') }, ['direction']),
  fn('deploy_bot', "Send Atlas Bot to do a bigger job in the background: anything that needs many steps, like deep research with sources, comparing several options, writing a long piece, checking many pages, or a long chain of PC actions. The bot plans, works on its own, and its report lands in this chat when it's done; the chat stays free meanwhile. Don't use it for quick things you can do in a step or two.", {
    goal: str("The job in one clear sentence, e.g. 'Find the best budget laptop under $800 for coding, with sources'"),
    details: str('Everything the bot should know: what the user wants, preferences, limits, what to include in the report'),
    specialist: str("Optional: one of the user's specialist bots to send (by name), when the job fits its speciality") }, ['goal']),
  // ---- Atlas Bot extras (botx.js): skills taught by telling, and routines (bots on a schedule)
  fn('save_skill', 'Save a skill: a reusable way of doing a kind of job that the user teaches you ("when I ask for X, do it like this…"). Bots load it when they do that kind of job. Saving with an existing name updates it.', {
    name: str('A short name, e.g. "Weekly money check"'), when: str('When to use it'), steps: str('The steps, one per line'),
    inputs: str('What is needed first (optional)'), checks: str('What to check before finishing (optional)'), output: str('What the result should look like (optional)'),
    permissions: str('Actions in it that need the user\'s OK (optional)') }, ['name', 'when', 'steps']),
  fn('set_routine', 'Set up a routine: a bot sent on a schedule, like "every weekday at 8am, summarise the news on AI". Runs every 5 minutes at most.', {
    goal: str('What the bot should do each time'), schedule: str('When, in plain words: "every weekday at 8am", "every 2 hours", "every Sunday evening", "every month on the 1st at 9am"'),
    name: str('A short name (optional)'), specialist: str('A specialist bot to send (optional)') }, ['goal', 'schedule']),
];

const PC = new Set(['open_app', 'list_apps', 'open_link', 'open_path', 'list_files', 'read_file', 'write_file', 'run_command', 'look_at_screen', 'click', 'type_text', 'press_keys', 'scroll']);

function parseWhen(s) {
  const m = String(s || '').trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})/);
  if (!m) return null;
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  return isNaN(d) ? null : d.getTime();
}
const pad = (n) => String(n).padStart(2, '0');
export const fmtLocal = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };
export const hhmm = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
export function dayLabel(ms) {
  const d = new Date(ms), t = new Date(); t.setHours(0, 0, 0, 0); const dd = new Date(d); dd.setHours(0, 0, 0, 0);
  const diff = Math.round((dd - t) / 86400000);
  if (diff === 0) return 'today'; if (diff === 1) return 'tomorrow'; if (diff === -1) return 'yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
}

export function logContext() {
  const s = db.state, sb = [];
  const open = s.tasks.filter((t) => OPEN.has(t.status)).sort((a, b) => (a.startAt == null) - (b.startAt == null) || (a.startAt || 0) - (b.startAt || 0));
  sb.push('Open reminders:' + (open.length ? '' : ' none'));
  for (const t of open.slice(0, 40)) sb.push(`- [${t.id}] ${t.title} — ${t.startAt ? fmtLocal(t.startAt) : 'no time'}${t.recurrence !== 'none' ? ', repeats ' + t.recurrence : ''}${t.priority === 'high' ? ' (alarm)' : ''}`);
  sb.push('Saved memories:' + (s.memories.length ? '' : ' none'));
  for (const m of s.memories.slice(-40)) sb.push(`- [${m.id}] ${m.text}`);
  return sb.join('\n');
}
function readLog(query, limit) {
  const q = String(query || '').toLowerCase().trim(); const s = db.state, lines = [];
  for (const t of s.tasks) lines.push([t.updatedAt || t.createdAt, `[${t.id}] Reminder "${t.title}"${t.startAt ? ' at ' + fmtLocal(t.startAt) : ''}: ${t.status}${t.priority === 'high' ? ', alarm' : ''}`]);
  for (const m of s.memories) lines.push([m.createdAt, `[${m.id}] Remembered: ${m.text} (saved ${fmtLocal(m.createdAt)})`]);
  for (const l of s.log.slice(-500)) lines.push([l.at, `${fmtLocal(l.at)} ${l.type}: ${l.title}${l.detail ? ' (' + l.detail + ')' : ''}`]);
  const hits = lines.filter(([, t]) => !q || t.toLowerCase().includes(q)).sort((a, b) => b[0] - a[0]).slice(0, limit || 30);
  return hits.length ? hits.map(([, t]) => t).join('\n') : 'Nothing in the Log matches.';
}

/** Hooks the screens provide: asking the user to allow something, starting a focus session, changing settings. */
export const ui = { confirm: async () => false, startFocus: () => {}, setting: () => 'Error: settings unavailable.', settingsText: () => '', deployBot: () => null,
  saveSkill: () => null, saveRoutine: () => ({ error: 'Routines are unavailable.' }), botTeam: () => '' };

async function webSearch(q) {
  const gk = keyFor('gemini');
  if (gk && gk !== 'local') {
    try {
      const r = await post(`https://generativelanguage.googleapis.com/v1beta/models/${modelOf(byId('gemini'))}:generateContent`, { 'x-goog-api-key': gk },
        { contents: [{ role: 'user', parts: [{ text: 'Search the web and report what answers this, with dates where they matter. Facts only, concise: ' + q }] }], tools: [{ google_search: {} }] }, 'Web search');
      const cand = (r.candidates || [])[0]; const text = ((cand && cand.content && cand.content.parts) || []).map((x) => x.text || '').join('');
      const src = ((cand && cand.groundingMetadata && cand.groundingMetadata.groundingChunks) || []).slice(0, 8).map((c) => c.web && `- ${c.web.title} — ${c.web.uri}`).filter(Boolean);
      if (text.trim()) return `Search results for "${q}" (Google):\n${text}` + (src.length ? '\nSources:\n' + src.join('\n') : '');
    } catch {}
  }
  const r = await host.fetch('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(q), { headers: { 'User-Agent': 'Mozilla/5.0 Atlas' }, timeout: 20000 });
  if (!r.ok) return "Couldn't search right now.";
  const html = r.text || ''; const out = [];
  const re = /<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g; let m; const snips = [...html.matchAll(/class="result__snippet"[^>]*>([\s\S]*?)<\/a>/g)].map((x) => strip(x[1]));
  let i = 0;
  while ((m = re.exec(html)) && i < 6) {
    let href = m[1].replace(/&amp;/g, '&'); const u = href.match(/uddg=([^&]+)/); if (u) href = decodeURIComponent(u[1]); if (href.startsWith('//')) href = 'https:' + href;
    out.push(`${i + 1}. ${strip(m[2])} — ${href}` + (snips[i] ? `\n   ${snips[i]}` : '')); i++;
  }
  return out.length ? `Search results for "${q}":\n${out.join('\n')}\nUse read_page on a link to read it.` : `No results found for "${q}".`;
}
function strip(s) { return String(s).replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/[ \t ]+/g, ' ').trim(); }
async function readPage(url) {
  const u = /^https?:\/\//.test(url) ? url : 'https://' + url;
  const r = await host.fetch(u, { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Atlas', Accept: 'text/html,text/plain;q=0.9,*/*;q=0.5' }, timeout: 25000 });
  if (!r.ok) return `Couldn't read that page (${r.status || r.error}).`;
  if (r.text == null) return `That link is a ${r.type} file, not a page.`;
  const doc = new DOMParser().parseFromString(r.text, 'text/html');
  doc.querySelectorAll('script,style,noscript,svg,nav,footer,header,form,iframe').forEach((n) => n.remove());
  const main = doc.querySelector('main,article') || doc.body;
  let text = (main ? main.innerText || main.textContent : r.text).replace(/\n{3,}/g, '\n\n').trim();
  if (text.length > 12000) text = text.slice(0, 12000) + '\n(page continues)';
  return `Title: ${doc.title || ''}\nURL: ${u}\n\n${text || '(no readable text)'}`;
}

/** Runs one tool. Returns {out, step?, image?}. [approved]: the user already said yes (Atlas Bot asks first itself). */
export async function runTool(call, { approved = false } = {}) {
  const a = call.args || {}; const now = Date.now();
  try {
    switch (call.name) {
      case 'set_reminder': {
        const title = plainMemory(a.title || ''); if (!title) return { out: 'Error: a title is needed.' };
        const at = parseWhen(a.when); if (a.when && at == null) return { out: "Error: couldn't read the time. Use 'YYYY-MM-DD HH:MM'." };
        const alarm = a.alert === 'alarm';
        const t = createTask(title, { startAt: at, durationMin: a.duration_min || 30, notes: plainMemory(a.notes || ''), priority: alarm ? 'high' : 'normal', recurrence: a.repeat || 'none' });
        const w = t.startAt ? `${dayLabel(t.startAt)} at ${hhmm(t.startAt)}` : 'with no time yet';
        return { out: `Logged reminder [${t.id}] '${t.title}' ${w}; at its time: ${alarm ? 'an alarm' : 'a notification'}.`, step: `Logged “${t.title}”, ${w}` };
      }
      case 'update_reminder': {
        const t = findTask(a.id); if (!t) return { out: 'Error: no reminder with that id.' };
        const title = t.title;
        if (a.action === 'done') respond(a.id, 'done'); else if (a.action === 'cancel') cancelTask(a.id); else if (a.action === 'delete') deleteTask(a.id);
        else if (a.action === 'move') { const at = parseWhen(a.when); if (at == null) return { out: "Error: give the new time as 'YYYY-MM-DD HH:MM'." }; updateTask(a.id, { startAt: at }); }
        else if (a.action === 'rename') updateTask(a.id, { title: plainMemory(a.title || '') });
        else if (a.action === 'make_alarm' || a.action === 'make_notification') updateTask(a.id, { priority: a.action === 'make_alarm' ? 'high' : 'normal' });
        else return { out: 'Error: unknown action.' };
        const word = { done: 'Marked “%” done', cancel: 'Cancelled “%”', delete: 'Deleted “%”', move: 'Moved “%”', rename: 'Renamed “%”', make_alarm: '“%” will ring as an alarm', make_notification: '“%” will come as a notification' }[a.action];
        return { out: `Done: ${a.action} on '${title}'.`, step: word.replace('%', title) };
      }
      case 'remember': { const txt = plainMemory(a.text || ''); if (!txt) return { out: 'Error: nothing to remember.' }; const m = remember(txt); return { out: `Saved memory [${m.id}].`, step: `Remembered “${txt}”` }; }
      case 'forget': { const m = db.state.memories.find((x) => x.id === a.id); if (!m) return { out: 'Error: no memory with that id.' }; forget(a.id); return { out: 'Forgotten.', step: `Forgot “${m.text}”` }; }
      case 'read_log': return { out: readLog(a.query, a.limit) };
      case 'web_search': return { out: await webSearch(String(a.query || '')), step: `Searched the web for “${String(a.query || '').slice(0, 60)}”` };
      case 'read_page': return { out: await readPage(String(a.url || '')), step: 'Read ' + String(a.url || '').replace(/^https?:\/\//, '').slice(0, 50) };
      case 'change_setting': { const out = await ui.setting(a.setting, String(a.value ?? '')); return { out, step: out.startsWith('Error') ? null : out }; }
      case 'deploy_bot': {
        const goal = String(a.goal || '').trim(); if (!goal) return { out: 'Error: give the bot a goal.' };
        const j = ui.deployBot(goal, String(a.details || ''), a.specialist ? String(a.specialist) : null); if (!j) return { out: "Error: Atlas Bot isn't available right now." };
        return { out: `Atlas Bot is on it: '${j.goal}'. It works in the background; its report will appear in this chat when it's done, and the user can follow it on the Bots page. Tell them briefly, in your own words.`, step: 'Sent Atlas Bot: ' + j.goal };
      }
      case 'save_skill': {
        const name = plainMemory(a.name || ''); if (!name || !String(a.steps || '').trim()) return { out: 'Error: a skill needs a name and steps.' };
        const sk = ui.saveSkill({ name, when: a.when, steps: a.steps, inputs: a.inputs, checks: a.checks, output: a.output, permissions: a.permissions });
        return sk ? { out: `Saved the skill "${sk.name}". Bots will use it for that kind of job; the user can edit it on the Bots page.`, step: 'Saved a skill: ' + sk.name } : { out: "Error: skills aren't available right now." };
      }
      case 'set_routine': {
        const r = ui.saveRoutine({ goal: String(a.goal || ''), schedule: String(a.schedule || ''), name: a.name ? String(a.name) : undefined, specialist: a.specialist ? String(a.specialist) : null });
        if (r.error) return { out: 'Error: ' + r.error };
        return { out: `Routine set: "${r.routine.name}", next run ${new Date(r.routine.nextAt).toLocaleString()}. The user can pause, test or delete it on the Bots page.`, step: 'Set a routine: ' + r.routine.name };
      }
      case 'start_focus': { const title = plainMemory(a.title || '') || 'Focus'; ui.startFocus(title, a.minutes || null, a.id || null); return { out: `Focus session started for '${title}'.`, step: 'Started a focus session: ' + title }; }
    }
    if (PC.has(call.name)) {
      if (call.name === 'run_command' && !approved && !(await ui.confirm('Run this on your PC?', String(a.command || ''), 'Run It'))) return { out: 'The user said no to running that command.' };
      if (call.name === 'write_file' && !approved && !(await ui.confirm('Save this file?', `${a.path}\n\n${String(a.text || '').slice(0, 600)}${String(a.text || '').length > 600 ? '\n…' : ''}`, 'Save It'))) return { out: 'The user said no to saving that file.' };
      const r = await host.pc(call.name, a);
      if (r.error) return { out: 'Error: ' + r.error };
      return { out: r.out || 'Done.', step: r.step || null, image: r.image || null, pc: true };
    }
    if (conn.owns(call.name)) return await conn.run(call, { approved, confirm: ui.confirm });   // connections
    return { out: `Error: there's no tool called ${call.name}.` };
  } catch (e) { return { out: 'Error: ' + (e.message || e) }; }
}

// ------------------------------------------------------------------ the conversation

export const convo = { busy: false, status: null, stopFlag: 0 };
const KEEP_TURNS = 24, MAX_STEPS = 40;
const LABEL = { open_app: (a) => 'Opening ' + (a.name || ''), look_at_screen: () => 'Looking at the screen', click: () => 'Clicking', type_text: () => 'Typing', press_keys: (a) => 'Pressing ' + (a.keys || ''),
  scroll: () => 'Scrolling', run_command: () => 'Running a command', web_search: () => 'Searching the web', read_page: () => 'Reading a page', set_reminder: () => 'Logging a reminder', remember: () => 'Saving to memory', read_file: () => 'Reading a file', list_files: () => 'Looking in a folder' };

function history() {
  const msgs = db.chat.filter((m) => m.source !== 'error' && !m.pending).slice(-KEEP_TURNS * 2);
  const h = msgs.map((m) => [m.user, m.user ? m.text : m.text + (m.steps && m.steps.length ? `\n(What I did: ${m.steps.join('; ')})` : '')]);
  while (h.length && !h[0][0]) h.shift();
  return h;
}

export function systemPrompt(spoken) {
  const name = (db.state.settings.name || '').trim() || 'the user';
  const now = new Date();
  return `You are Atlas, ${name}'s personal AI assistant. Right now you're on their Windows PC (you also live on their phone, and the two share one memory: the Log and this conversation sync). On the PC you can open apps, files, folders and links, look at the screen, click, type and press keys, and run PowerShell commands. You can search the internet and read pages directly. You keep their Log: reminders and things they want remembered.

How you talk:
- Like a sharp, warm friend. Natural and relaxed, short by default, longer when they want detail. Understand casual writing and slang; match their energy.
- Be straight with them: they're an adult. Answer what was asked, fully and plainly. Don't lecture, moralise, pile on warnings or disclaimers, or back away from a topic because it's blunt, dark or uncomfortable; give your real opinion when asked. If something is truly dangerous, say so once, in a sentence, and carry on.
- Be honest. Only say you did something if a tool result shows it worked. If you don't know, say so; if it might have changed recently, search the web.
- Never show code, ids, function or tool names, JSON, or how you work inside, unless they asked you for code. Say what you did in plain words.
${spoken ? '- This came by voice and your answer will be read aloud: one to three short spoken sentences. No lists, no formatting, no links. Spoken, you are composed and courteous, the unflappable aide: precise, quietly warm, a touch of dry wit when it fits; never gushing.' : '- Write neatly, clean and organized, every time. Short answers: a few clear sentences. Anything with several parts: a short heading where it helps, bullet or numbered lists, and **bold** for the key words, using Markdown. Keep paragraphs short. No filler.'}

The Log (only what's worth keeping):
- set_reminder when they want reminding or have something to do at a time. Work out the exact date from today. If they gave a day but no time, ask what time unless it's obvious.
- Choose the alert: an alarm for important or time-critical things, a notification for lighter ones; follow them if they say which.
- When they say they're doing something right now, use start_focus.
- remember for lasting facts they want kept; read_log to look things up. Don't log ordinary chat or PC actions.

Using the PC:
- open_app by name; open_path for files and folders; list_files / read_file to look around.
- For anything on screen: look_at_screen first, then click using its coordinates, then look again to check. Type with type_text, shortcuts with press_keys.
- run_command and write_file ask the user first; explain briefly why you need them. Prefer simple, safe commands. Never delete files or change system settings unless they clearly asked.
- Before anything that sends a message, posts, buys, deletes, or changes an account, say exactly what you're about to do and wait for their go-ahead, unless they already asked for that exact action.
- Never type passwords or payment details unless they give them to you in this chat for that purpose.

Where you're running (so you can say why something is held back; don't bring it up otherwise):
- ${fitRules.line(device.now())}

Atlas Bot (deploy_bot):
- For bigger jobs that need many steps (deep research with sources, comparing several options, a long piece of writing, checking many pages, a long chain of PC actions), or when they say "send a bot to…", deploy a bot instead of doing it here. Give it a clear goal and all the details it needs. Tell them it's on it; its report comes into this chat when it's done.
- Quick things (one search, one reminder, a short answer) you do yourself.
- When they teach you how they want a kind of job done, save it with save_skill. When they want a bot on a schedule ("every morning…"), use set_routine (for one-off reminders use set_reminder).
${ui.botTeam()}

${conn.promptLine() ? conn.promptLine() + '\n\n' : ''}Your own settings (change_setting, when they ask):
${ui.settingsText()}

Now: ${now.toLocaleString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' })} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).
${logContext()}`;
}

/**
 * Sends [text] (with [attachments]: {kind, mime, name, base64|text}) and runs the conversation until Atlas answers.
 * [onUpdate] is called as the reply and status change.
 */
export async function send(text, { spoken = false, attachments = [] } = {}) {
  const q = (text || '').trim() || (attachments.length ? (attachments.some((x) => x.kind === 'video') ? "What's in this video?" : attachments.some((x) => x.kind === 'image') ? "What's in this picture?" : 'Have a look at this.') : '');
  if (!q) return null;
  const hist = history();
  const user = { id: crypto.randomUUID(), user: true, text: q, source: 'You', at: Date.now(), steps: [], media: attachments.map((x) => ({ kind: x.kind, name: x.name, thumb: x.thumb || null })) };
  const reply = { id: crypto.randomUUID(), user: false, text: '', source: 'Atlas', at: Date.now() + 1, steps: [], pending: true };
  db.chat.push(user, reply); saveChat();
  convo.busy = true; convo.status = 'Thinking'; const mine = ++convo.stopFlag; changed('chat');
  try {
    let p = active();
    if (!p) { reply.text = 'Add an AI key in **Me → Brain** (Gemini\'s is free), or start a local model, so I can think.'; reply.source = 'Atlas'; return reply; }
    const media = attachments.filter((x) => x.kind !== 'text').map((x) => ({ mime: x.mime, base64: x.base64, name: x.name }));
    if (media.some((m) => m.mime.startsWith('video/')) && p.style !== 'gemini') {
      const g = byId('gemini'); if (readyKey('gemini')) p = g; else throw new Failure(`${p.name} can't watch videos. Gemini can: add a Gemini key, or send a picture instead.`);
    }
    const full = q + attachments.filter((x) => x.kind === 'text').map((x) => `\n\n[File: ${x.name}]\n${x.text}\n[End of ${x.name}]`).join('');
    p = await guardLocal(p);
    reply.source = p.name;
    const tools = [...TOOLS, ...conn.declarations('chat')];   // connections
    let sess = session(p, keyFor(p.id), hist, full, media, tools);
    const tried = new Set([p.id]);
    let final = null, steps = 0;
    while (steps < MAX_STEPS) {
      steps++;
      if (mine !== convo.stopFlag) throw new Failure('Stopped.');
      let turn;
      try { turn = await callWithKeys(p, sess, () => sess.call(systemPrompt(spoken)), mine); }
      catch (e) {
        if (!(e instanceof Switch)) throw e;
        if (tried.has(e.to.id)) throw new Failure(e.message);
        tried.add(e.to.id); p = await guardLocal(e.to); tried.add(p.id); reply.source = p.name; convo.status = 'Switching to ' + p.name; changed('chat');
        const done = reply.steps.length ? `\n\n(Note for you, Atlas: you already did these steps for this request before the service changed, don't repeat them: ${reply.steps.join('; ')}. Continue from there.)` : '';
        sess = session(p, keyFor(p.id), hist, full + done, media, tools); steps--; continue;
      }
      if (mine !== convo.stopFlag) throw new Failure('Stopped.');
      if (!turn.calls.length) { final = turn.text; break; }
      if (turn.text) { reply.text = plainReply(turn.text); changed('chat'); }
      const results = [];
      for (const c of turn.calls) {
        convo.status = conn.label(c.name, c.args || {}) || (LABEL[c.name] || (() => 'Working'))(c.args || {}); changed('chat');
        const r = await runTool(c);
        if (r.step) reply.steps.push(r.step);
        results.push({ call: c, out: r.out, image: r.image });
        changed('chat');
      }
      sess.results(results);
    }
    reply.text = plainReply(final ?? `I stopped after ${MAX_STEPS} steps without finishing. Tell me how you'd like me to continue.`) || 'Done.';
  } catch (e) {
    reply.text = e.message === 'Stopped.' ? (reply.text || 'Stopped.') : (e.message || String(e));
    reply.source = e.message === 'Stopped.' ? 'Stopped' : 'error';
  } finally {
    delete reply.pending;
    if (mine === convo.stopFlag) { convo.busy = false; convo.status = null; }
    saveChat(); changed('chat');
  }
  return reply;
}
export function stop() { convo.stopFlag++; convo.busy = false; convo.status = null; changed('chat'); }

export class Switch extends Error { constructor(to, msg) { super(msg); this.to = to; } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// The key pool's rules (the same as the phone's KeyRouter):
// - Persistent first: a failure that can pass (429 limit, busy server 500/502/503/504, timeout or dropped connection)
//   is retried on the SAME key up to RETRIES times before anything switches. A limit waits as long as the service
//   asked (Gemini's retryDelay, "try again in 20s", a Retry-After header) when that's a minute or less; with no hint,
//   5, 15 and 40 s, which covers a per-minute window. Busy servers and the network: 2, 5, 10 s.
// - A failure retrying can't fix switches at once: the key was turned down (401/403, or a 400 about the key) or the
//   account has no credit (402). Waiting won't change those answers.
// - Then the key is rested (limit, credit) or set aside (turned down) and the next ready key of the service takes over;
//   when the service has none left, Switch moves to the next service with one, unless "Don't Switch Services" is on:
//   then Atlas stays put, waits out a used-up limit (30 s, 60 s) and, if it still can't, says so plainly.
// - No internet after the retries: the local model takes over if there is one (not with Don't Switch on).
export const RETRIES = 3;
export const RETRY_WAITS = [2000, 5000, 10000];
// a used-up limit (429) with no hint from the service: together they cover a per-minute window
export const LIMIT_WAITS = [5000, 15000, 40000];
// the longest wait a service may ask for that Atlas honours; a longer one moves on to the next key
export const MAX_HINT_MS = 60000;
const HINT = /(?:try again|retry)(?: after| in)?\s+(?:(\d+)\s*m(?!s)(?:in(?:ute)?s?)?\s*)?([0-9]+(?:\.[0-9]+)?)?\s*(ms|milliseconds?|s|secs?|seconds?)?\b/gi;
/**
 * How long the service asked us to wait, in ms (0 if it didn't say): a Retry-After header in seconds, Gemini's
 * google.rpc.RetryInfo "retryDelay": "37s", or words like "try again in 20s", "retry after 20 seconds", "in 500ms".
 */
export function retryHint(body, header) {
  const hs = Number(String(header ?? '').trim()); if (header != null && String(header).trim() && hs > 0) return Math.round(hs * 1000);
  const b = String(body || ''); if (!b) return 0;
  const d = /"retryDelay"\s*:\s*"([0-9.]+)s"/.exec(b); if (d && Number(d[1]) > 0) return Math.round(Number(d[1]) * 1000);
  for (const m of b.matchAll(HINT)) {
    const mins = Number(m[1] || 0), n = m[2] != null ? Number(m[2]) : null, unit = (m[3] || '').toLowerCase();
    let part;
    if (n == null) part = 0; else if (unit.startsWith('ms') || unit.startsWith('milli')) part = n; else if (unit || mins > 0) part = n * 1000; else continue;
    const ms = mins * 60000 + Math.round(part); if (ms > 0) return ms;
  }
  return 0;
}
/** How long to wait before retry number [n] (1-based), and whether that's the service's own ask. */
export function waitFor(f, n) {
  const hint = f.retryAfterMs || 0;
  if (hint > 0 && hint <= MAX_HINT_MS && (f.code === 429 || f.code === 503)) return [Math.max(hint, 500), true];
  return [(f.code === 429 ? LIMIT_WAITS : RETRY_WAITS)[n - 1], false];
}
export const WAIT_OUT = [30000, 60000];
export const dontSwitch = () => !!pref('dont_switch', false);
export function cause(f) {
  if ([401, 403, 402].includes(f.code) || (f.code === 400 && /key/i.test(f.message || ''))) return 'key';
  if ([429, 500, 502, 503, 504].includes(f.code) || f.offline) return 'passing';
  return 'other';
}

/**
 * One model call with the key pool behind it (used by the chat and anything else that talks to a model).
 * [onStatus] shows a short line while waiting or switching (null when that's over); [cancelled] () => true stops the waiting.
 */
export async function callWithKeys(p, sess, call, mine, { onStatus = (st) => { convo.status = st; changed('chat'); }, cancelled = () => mine !== convo.stopFlag, wait = sleep } = {}) {
  let retries = 0, waitedOut = 0; const rested = new Set();
  const nap = async (ms) => { await wait(ms); if (cancelled()) throw new Failure('Stopped.'); };
  for (;;) {
    let f;
    try { const t = await call(); if (!p.local) worked(p.id, sess.key); return t; }
    catch (e) { f = e; }
    if (!(f instanceof Failure)) throw f;
    if (cancelled()) throw f;
    const c = cause(f);
    if (c === 'other') throw f;
    // asked to wait more than a minute: not worth sitting through, move on to the next key
    const tooLong = (f.retryAfterMs || 0) > MAX_HINT_MS;
    if (c === 'passing' && retries < RETRIES && !tooLong) {
      retries++;
      const [ms, asked] = waitFor(f, retries); const secs = Math.ceil(ms / 1000);
      onStatus(asked ? `${p.name} asked me to wait ${secs} s (${retries} of ${RETRIES})` : f.code === 429 ? `Waiting for ${p.name}'s limit, ${secs} s (${retries} of ${RETRIES})` : f.offline ? `Can't reach ${p.name}, trying again (${retries} of ${RETRIES})` : `${p.name} is busy, trying again (${retries} of ${RETRIES})`);
      await nap(ms); onStatus(null);
      continue;
    }
    const stay = dontSwitch();
    if (f.offline) {
      // no internet: a local model, if there is one, carries on
      if (!p.local && !stay && hasKeys('local')) throw new Switch(byId('local'), 'Offline.');
      throw f;
    }
    if (!p.local && (c === 'key' || f.code === 429)) {
      if (!rested.has(sess.key)) { rested.add(sess.key); rest(p.id, sess.key, f.code, f.message); }
      const next = readyKey(p.id, sess.key);
      if (next && !rested.has(next)) { sess.key = next; retries = 0; onStatus(`Switching to another ${p.name} key`); await nap(300); onStatus(null); continue; }
    }
    const other = fallbacks(p.id)[0];
    if (other && !stay) throw new Switch(other, c === 'key' || f.code === 429 ? `All your ${p.name} keys are used up for now.` : `${p.name} kept failing (tried ${RETRIES} times).`);
    if (f.code === 429 && waitedOut < WAIT_OUT.length) { onStatus(`Waiting for ${p.name}'s limit to reset`); await nap(WAIT_OUT[waitedOut++]); onStatus(null); continue; }
    const stayed = stay && !!other;
    throw new Failure(
      stayed && f.code === 429 ? `${p.name}'s limit is used up for now. Don't Switch Services is on, so I stayed on ${p.name}: try again in a few minutes, or add another ${p.name} key in Me → Brain.`
        : stayed && c === 'key' ? `${f.message} Don't Switch Services is on, so I didn't move to another service. Add another ${p.name} key in Me → Brain.`
        : stayed ? `${f.message} I tried ${RETRIES} times. Don't Switch Services is on, so I didn't move to another service.`
        : c === 'key' || f.code === 429 ? `${f.message} (No other key is ready: add more in Me → Brain.)`
        : `${f.message} I tried ${RETRIES} times.`, f.code);
  }
}

/** A tiny question to check a key works. */
export async function testKey(p, key) {
  const sess = session(p, key || keyFor(p.id), [], 'Are you there? Answer in three words.', []);
  try { const t = await sess.call('You are Atlas, a friendly assistant. Reply briefly.'); if (!p.local) worked(p.id, sess.key); return { ok: true, msg: `${p.name} works: “${(t.text || '(used a tool)').slice(0, 40)}”` }; }
  catch (e) { if (!p.local && e instanceof Failure && keyExhausted(e)) rest(p.id, sess.key, e.code, e.message); return { ok: false, msg: e.message || String(e) }; }
}
