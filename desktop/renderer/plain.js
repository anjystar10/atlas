// Keeps the machinery out of sight (the same rules as Plain.kt on the phone): no ids, tool calls, JSON or model
// tokens in what Atlas shows, says or saves; real code only when you asked for code.
const TOOLS = ['set_reminder', 'update_reminder', 'remember', 'forget', 'read_log', 'open_app', 'open_link', 'read_screen', 'tap', 'long_press', 'type_text', 'scroll',
  'press_key', 'press_keys', 'wait', 'web_search', 'read_page', 'change_setting', 'start_focus', 'look_at_screen', 'click', 'run_command', 'write_file', 'read_file',
  'list_files', 'open_path', 'list_apps', 'default_api', 'tool_code', 'tool_call', 'function_call'];
// Atlas's own ids, as tools print them: "[3f2a…]", "(3f2a…)", "(id 3f2a…)", "#3f2a…". A bare one in a sentence is kept:
// it may be the user's own code or key, and dropping it would lose what they asked to keep.
const UUID = /\s*(?:\[\s*|\(\s*(?:id[:\s]+)?|#)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b(?:\s*[\])])?/g;
const TOKENS = /<\|[^|>]{1,40}\|>|<\/?s>|<think>[\s\S]*?<\/think>|<\/?(start_of_turn|end_of_turn)>/g;
const WHAT_I_DID = /\(What I did:[^)]*\)/g;
const FENCE = /```([a-zA-Z_]*)\n?([\s\S]*?)```/g;
// a tool call written out on its own line: name(...) with no space, ending in ")" — prose like "wait (just a sec)" stays
const TOOL_LINE = new RegExp(`^\\s*(print\\()?\\s*(default_api\\.)?(${TOOLS.join('|')})\\((.*=.*|\\s*|\\s*["'{].*)\\)\\)?\\s*;?\\s*$`);
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
  // a capital to start, unless the first word is written its own way (iPhone, eBay, gRPC)
  return t && !/[A-Z]/.test(t.split(/\s/)[0]) ? t[0].toUpperCase() + t.slice(1) : t;
}
export function plainSpoken(s) {
  let t = plainReply(s).replace(FENCE, " There's some code on screen. ");
  // links: say the words, not the address; a bare address becomes "a link"
  t = t.replace(/!?\[([^\]]*)\]\((?:[^()\s]|\([^()\s]*\))*\)/g, '$1').replace(/(https?:\/\/|www\.)\S+/g, 'a link');
  // tables: the divider row goes; cells are read with pauses. Each line is its own sentence.
  const lines = t.split('\n').filter((l) => !/^\|?[\s:|-]*-{2,}[\s:|-]*\|?$/.test(l.trim()))
    .map((l) => l.trim().replace(/^[#> ]+/, '').replace(/^[| ]+|[| ]+$/g, '').replace(/\s*\|\s*/g, ', ').replace(/^([-*•]|\d+[.)])\s+/, '')).filter(Boolean);
  t = lines.map((l) => (/[.!?…:;,]$/.test(l) ? (l.endsWith(':') ? l.slice(0, -1) + '.' : l) : l + '.')).join(' ');
  t = t.replace(/[*_#`~^\\]/g, '').replace(/\s*[–—]\s*/g, ', ').replace(/&/g, ' and ').replace(/(\d)\s*%/g, '$1 percent')
    .replace(/\(\s*\)/g, '').replace(/\.\s*\.+/g, '.').replace(/,\s*([.,!?])/g, '$1').replace(/\s+/g, ' ');
  return t.trim();
}

