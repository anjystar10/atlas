// Keeps the machinery out of sight (the same rules as Plain.kt on the phone): no ids, tool calls, JSON or model
// tokens in what Atlas shows, says or saves; real code only when you asked for code.
const TOOLS = ['set_reminder', 'update_reminder', 'remember', 'forget', 'read_log', 'open_app', 'open_link', 'read_screen', 'tap', 'long_press', 'type_text', 'scroll',
  'press_key', 'press_keys', 'wait', 'web_search', 'read_page', 'change_setting', 'start_focus', 'look_at_screen', 'click', 'run_command', 'write_file', 'read_file',
  'list_files', 'open_path', 'list_apps', 'default_api', 'tool_code', 'tool_call', 'function_call'];
const UUID = /\[?\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b\]?/g;
const TOKENS = /<\|[^|>]{1,40}\|>|<\/?s>|<think>[\s\S]*?<\/think>|<\/?(start_of_turn|end_of_turn)>/g;
const WHAT_I_DID = /\(What I did:[^)]*\)/g;
const FENCE = /```([a-zA-Z_]*)\n?([\s\S]*?)```/g;
const TOOL_LINE = new RegExp(`^\\s*(print\\()?\\s*(default_api\\.)?(${TOOLS.join('|')})\\s*\\(.*$`);
const looksLikeToolCall = (code) => { const c = code.trim(); return TOOLS.some((t) => c.includes(t + '(') || c.includes(`"${t}"`)) || (c.startsWith('{') && /"(name|args|arguments)"/.test(c)); };

export function plainReply(s) {
  let t = String(s || '').replace(TOKENS, '').replace(WHAT_I_DID, '');
  t = t.replace(FENCE, (m, lang, body) => (lang.toLowerCase() === 'tool_code' || lang.toLowerCase() === 'tool_call' || looksLikeToolCall(body) ? '' : m));
  t = t.split('\n').filter((l) => !TOOL_LINE.test(l)).join('\n').replace(UUID, '');
  return t.replace(/[ \t]+([.,;:!?])/g, '$1').replace(/[ \t]{2,}/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
}
export function plainMemory(s) {
  let t = plainReply(s);
  if (t.trimStart().startsWith('{')) t = [...t.matchAll(/"[^"]*"\s*:\s*"([^"]*)"/g)].map((m) => m[1]).join('. ');
  t = t.replace(FENCE, (m, l, b) => b).replace(/`/g, '').replace(/[{}[\]<>]/g, '').replace(/\s+/g, ' ').trim().replace(/^["']|["']$/g, '');
  return t ? t[0].toUpperCase() + t.slice(1) : t;
}
export function plainSpoken(s) {
  let t = plainReply(s).replace(FENCE, ' ');
  t = t.split('\n').map((l) => l.trim().replace(/^#+\s*/, '').replace(/^([-*•]|\d+[.)])\s+/, '')).join('. ');
  t = t.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/[*_#`>|]/g, '').replace(/\.\s*\.+/g, '.').replace(/\s+/g, ' ').trim().replace(/^\.+|\.+$/g, '');
  return t ? t + '.' : t;
}
