// The project tools (the work is done by desktop/projects.js): Atlas builds software for the user in
// Documents/Atlas Projects, from the chat or as a bot (usually the Developer specialist). Inside the project folder it
// just works; anything reaching outside it (other folders, global installs, publishing, the system) asks first.

const str = (description, e) => ({ type: 'string', description, ...(e ? { enum: e } : {}) });
const fn = (name, description, props, required = []) => ({ name, description, ...(props ? { parameters: { type: 'object', properties: props, ...(required.length ? { required } : {}) } } : {}) });
const P = str("The project's name (as given to project_new)");

export const PROJECT_TOOLS = [
  fn('project_list', "List the user's software projects (in Documents\\Atlas Projects) and the developer tools installed on this PC (node, python, git, VS Code, .NET, Java, Rust, Go, Flutter)."),
  fn('project_new', 'Start a software project (or reopen one by name): makes its folder and notes what it is for. Tells you what is installed so you can pick a stack that will run here.',
    { name: str('A short name, e.g. "Expense Tracker"'), goal: str('What the user asked for, in full: features, who it is for, any must-haves'), stack: str('The language/framework you chose, e.g. "Node.js CLI", "Python + Flask", "HTML/CSS/JS site"') }, ['name']),
  fn('project_files', "List the project's files (folders like node_modules and .git are summarised).", { project: P, path: str('A folder inside the project (optional)') }, ['project']),
  fn('project_read', 'Read a text file in the project. Long files come in parts.', { project: P, path: str('The file, relative to the project, e.g. "src/index.js"'), from: { type: 'number', description: 'Start at this character, for the next part' } }, ['project', 'path']),
  fn('project_write', 'Create or replace a file in the project with its full contents (folders are made as needed).', { project: P, path: str('The file, relative to the project'), text: str('The whole file') }, ['project', 'path', 'text']),
  fn('project_patch', 'Change part of a file: replace an exact piece of text (copied exactly from project_read) with new text. Use it for small fixes instead of rewriting the file.',
    { project: P, path: str('The file'), find: str('The exact text to replace (unique in the file)'), replace: str('The new text'), all: { type: 'boolean', description: 'Replace every occurrence' } }, ['project', 'path', 'find', 'replace']),
  fn('project_run', 'Run a command in the project folder and get its output and exit code (e.g. "npm install", "npm test", "python main.py", "git init"). One command at a time, no chaining. Building, testing and installing the project\'s own packages just run; anything that reaches outside the project asks the user first.',
    { project: P, command: str('The command'), timeout_s: { type: 'number', description: 'Seconds before it is stopped (default 180, up to 1800)' } }, ['project', 'command']),
  fn('project_check', "Check the project: run its tests (or build) and say whether they pass. Atlas works out the command (npm test, pytest, cargo test…) or you give it once and it's remembered.",
    { project: P, command: str('The check command, when Atlas can\'t work it out (remembered)'), timeout_s: { type: 'number', description: 'Seconds (default 600)' } }, ['project']),
  fn('project_open', 'Open the project for the user: its folder, in VS Code, or start the app itself in the background (a server, a script with a window); "stop" ends a running app.',
    { project: P, how: str('How', ['folder', 'editor', 'app', 'stop']), command: str('For app: how to start it, e.g. "npm start" (remembered)') }, ['project', 'how']),
  fn('project_zip', 'Zip the project (without rebuildable folders like node_modules) and put the zip in the Library, ready to share.', { project: P }, ['project']),
  fn('project_delete', 'Delete a whole project. Always asks the user first.', { project: P }, ['project']),
];
export const PROJECTS = new Set(PROJECT_TOOLS.map((t) => t.name));

const short = (s, n = 40) => String(s || '').slice(0, n);
const LABELS = {
  project_list: () => 'Looking at your projects', project_new: (a) => 'Starting the project ' + short(a.name), project_files: (a) => 'Looking at ' + short(a.project) + "'s files",
  project_read: (a) => 'Reading ' + short(a.path), project_write: (a) => 'Writing ' + short(a.path), project_patch: (a) => 'Editing ' + short(a.path),
  project_run: (a) => 'Running ' + short(a.command, 50), project_check: (a) => 'Checking ' + short(a.project), project_open: (a) => (a.how === 'app' ? 'Starting ' : 'Opening ') + short(a.project),
  project_zip: (a) => 'Zipping ' + short(a.project), project_delete: (a) => 'Deleting ' + short(a.project),
};
export const projectLabel = (c) => (LABELS[c.name] ? LABELS[c.name](c.args || {}) : null);

/** For Atlas Bot's approval step: a sentence when this call needs the user's OK first, else null. */
export async function projectApproval(call) {
  const host = window.atlasHost;
  if (!host.projects) return null;
  return host.projects.check(call.name, call.args || {});
}

/**
 * Runs a project tool. In the chat ([confirm] given), something risky is asked about right there; for a bot the
 * approval step has already asked ([approved]), and anything it missed comes back as "needs the OK".
 */
export async function runProject(call, { approved = false, confirm = null } = {}) {
  const host = window.atlasHost;
  if (!host.projects) return { out: "Error: projects aren't available here." };
  let r = await host.projects.run(call.name, call.args || {}, approved);
  if (r && r.needs) {
    if (!confirm) return { out: `This needs the user's OK first: ${r.needs}. Ask with request_approval, or do it another way that stays inside the project.` };
    const yes = await confirm('Allow this?', r.needs, 'Allow');
    if (!yes) return { out: 'The user said no to that.' };
    r = await host.projects.run(call.name, call.args || {}, true);
  }
  return r || { out: 'Error: no answer.' };
}

/** What Atlas (and its bots) should know about building software here. */
export const PROJECTS_PROMPT = `Building software (projects): when the user asks you to build, make or code something (an app, a website, a script, a tool, a bot), build it for real in a project on this PC:
1. project_list (what exists, what's installed) → project_new with the full goal and a stack that's installed (prefer what's there: Node.js, Python, plain HTML/CSS/JS…).
2. Write the code with project_write (small, clear files; a README.md saying how to run it), and a few real tests.
3. Install what it needs inside the project (npm install; for Python make .venv first: python -m venv .venv, then .venv\\Scripts\\python -m pip install …).
4. Run it and its tests (project_run / project_check), read the errors, fix with project_patch, and repeat until project_check passes. Don't stop at code that was never run.
5. Hand it over: project_zip to the Library; say what it does, how to run it, and what you checked. Offer project_open (folder, VS Code, or start the app).
Inside the project folder everything just runs. Anything outside it (other folders, global installs, git push, publishing, deploying, system settings) needs the user's OK and is asked automatically.`;

// ------------------------------------------------------------------ the capability registry (aware.js)

let briefCache = '', briefAt = 0;
function refreshBrief() {
  const host = window.atlasHost;
  if (!host.projects || Date.now() - briefAt < 20000) return;
  briefAt = Date.now();
  host.projects.list().then((l) => {
    const list = Array.isArray(l) ? l.slice(0, 5) : [];
    briefCache = list.length ? 'Projects on this PC (project_* tools): ' + list.map((p) => `${p.name}${p.status ? ` (${p.status})` : ''}`).join(', ') : '';
  }).catch(() => {});
}

/**
 * Offers the project tools through the capability registry: in the chat (a risky step waits for the user's yes in
 * the chat, through answer_approval, or follows their standing choice) and to bots (whose own approval step asks).
 * The main Atlas's brief names the latest projects.
 */
export function registerProjects(aware) {
  aware.register({
    id: 'projects', forChat: true, forBots: true,
    tools: () => (window.atlasHost.projects ? PROJECT_TOOLS : []),
    brief: () => { refreshBrief(); return briefCache || null; },
    run: async (call) => {
      if (!PROJECTS.has(call.name)) return null;
      const host = window.atlasHost;
      if (!host.projects) return { out: "Error: projects aren't available here." };
      const r = await host.projects.run(call.name, call.args || {}, false);
      briefAt = 0;
      if (!r || !r.needs) return r || { out: 'Error: no answer.' };
      const mode = aware.src.mode ? aware.src.mode(r.needs, call.name, call.args || {}) : 'ask';
      if (mode === 'deny') return { out: `Error: the user's standing choice is never to allow this kind of action (${r.needs.slice(0, 100)}). Tell them; they can change it in Me → Bot Permissions.` };
      if (mode === 'allow') return host.projects.run(call.name, call.args || {}, true);
      const ref = aware.addAsk(r.needs, () => host.projects.run(call.name, call.args || {}, true));
      return { out: `Needs the user's OK first [${ref}]: ${r.needs}. Ask them in one short line; when they say yes, call answer_approval with ref ${ref}.`, step: 'Waiting for your OK: ' + r.needs.slice(0, 60) };
    },
  });
}
