// What Atlas may do inside one of the user's projects without asking (desktop/projects.js uses this).
// Building inside the project's own folder is the point: writing its files and running its build, test and
// package-manager commands go ahead. Everything that reaches outside it asks first: other folders, global installs,
// publishing or pushing, system changes, deleting the project, and any command this file can't read with
// confidence (chains, redirects, variables, unknown programs). Pure: no Electron, no file system (testable anywhere).
'use strict';

/** Characters that can chain, redirect, substitute or hide a second command in cmd.exe, PowerShell or sh. */
const CHAINS = /[\r\n`$;|&<>^%!(){}]/;

/** Splits a command line into words the way a shell would for plain words and simple quotes (no expansion). */
function words(cmd) {
  const out = []; let cur = '', q = null, any = false;
  for (const ch of String(cmd || '')) {
    if (q) { if (ch === q) q = null; else cur += ch; continue; }
    if (ch === '"' || ch === "'") { q = ch; any = true; continue; }
    if (/\s/.test(ch)) { if (cur || any) out.push(cur); cur = ''; any = false; continue; }
    cur += ch;
  }
  if (q) return null;                 // an unclosed quote: can't be read with confidence
  if (cur || any) out.push(cur);
  return out;
}

/** A word that names a place outside the project: an absolute path, a drive, a UNC share, home (~), or any ".." step. */
function escapes(w) {
  if (/^\/[a-z?]{1,2}(:\S*)?$/i.test(String(w))) return false;   // a Windows switch like /s, /q, /b (not a path)
  const s = String(w).replace(/^--?[\w-]+=/, '');   // --out=../x → ../x
  if (/^[a-z]:/i.test(s) || s.startsWith('\\\\') || s.startsWith('/') || s.startsWith('\\') || s.startsWith('~')) return true;
  return s.split(/[\\/]+/).some((p) => p === '..');
}

/** The program a word names, without folders or .exe/.cmd/.bat (".venv/Scripts/python.exe" → "python"). */
const progOf = (w) => String(w || '').split(/[\\/]/).pop().toLowerCase().replace(/\.(exe|cmd|bat|ps1|sh)$/, '');
const viaVenv = (w) => /^(\.venv|venv|env)[\\/]+(scripts|bin)[\\/]+/i.test(String(w || ''));

const GLOBAL_FLAGS = new Set(['-g', '--global', '--location=global', '--user', '--system', '--break-system-packages', '--prefix', '--root']);
const SIMPLE = new Set(['node', 'tsc', 'eslint', 'prettier', 'jest', 'vitest', 'mocha', 'pytest', 'ruff', 'black', 'mypy', 'flake8', 'javac', 'java', 'deno', 'echo',
  'dir', 'ls', 'type', 'cat', 'mkdir', 'md', 'tree', 'where', 'which', 'gradle', 'gradlew', 'mvnw', 'make', 'cmake', 'ninja', 'rustc', 'gcc', 'g++', 'clang', 'vite', 'next', 'tsx', 'ts-node']);
const DELETE = new Set(['rm', 'del', 'erase', 'rmdir', 'rd', 'remove-item']);

/**
 * Should running [command] in the project folder ask first?
 * Returns { ask: false } or { ask: true, why, category } (category is one of the approval kinds in botx.js).
 * [hasVenv]: the project has its own Python environment (.venv / venv), so pip there stays inside the project.
 */
function checkCommand(command, { hasVenv = false } = {}) {
  const raw = String(command || '').trim();
  const ask = (why, category = 'command') => ({ ask: true, why, category });
  if (!raw) return ask('an empty command');
  if (raw.length > 600) return ask('a very long command');
  if (CHAINS.test(raw)) return ask('it chains, redirects or substitutes something');
  const w = words(raw);
  if (!w || !w.length) return ask("it couldn't be read clearly");
  if (w.some(escapes)) return ask('it points outside the project folder');
  const prog = progOf(w[0]), sub = (w[1] || '').toLowerCase(), rest = w.slice(1).map((x) => x.toLowerCase());
  const hasGlobal = rest.some((x) => GLOBAL_FLAGS.has(x) || x.startsWith('--prefix=') || x.startsWith('--root='));
  if (['sudo', 'runas', 'su', 'doas'].includes(prog)) return ask('it asks for administrator rights', 'account');
  if (['winget', 'choco', 'scoop', 'apt', 'apt-get', 'brew', 'msiexec', 'reg', 'regedit', 'sc', 'setx', 'net', 'netsh', 'bcdedit', 'icacls', 'takeown', 'format', 'diskpart', 'shutdown', 'powershell', 'pwsh', 'cmd', 'bash', 'sh', 'wsl', 'start', 'curl', 'wget', 'invoke-webrequest', 'iwr', 'ssh', 'scp'].includes(prog))
    return ask('it reaches beyond this project (system, other shells or the network)');
  if (DELETE.has(prog)) {
    const targets = w.slice(1).filter((x) => !x.startsWith('-') && !x.startsWith('/'));
    if (!targets.length || targets.some((t) => ['.', './', '.\\', '*', '*.*', '/'].includes(t) || /^[.\\/*]+$/.test(t))) return ask('it would delete the whole project', 'delete');
    return { ask: false };   // deleting named files inside the project is editing it
  }
  switch (prog) {
    case 'npm': case 'pnpm': case 'yarn': case 'bun': {
      if (hasGlobal || sub === 'global') return ask('a global install changes the whole PC');
      if (['publish', 'unpublish', 'deprecate', 'owner', 'access', 'adduser', 'login', 'logout', 'token', 'team', 'org', 'dist-tag', 'star', 'link', 'config', 'set'].includes(sub)) return ask(sub === 'publish' ? 'it publishes the package' : 'it changes your account or global settings', sub === 'publish' ? 'publish' : 'account');
      return { ask: false };
    }
    case 'npx': case 'pnpx': case 'bunx': {
      if (hasGlobal) return ask('a global install changes the whole PC');
      const tool = progOf(w.find((x, i) => i > 0 && !x.startsWith('-')) || '');
      if (/^(vercel|netlify|firebase|wrangler|surge|gh-pages|np|release-it|semantic-release|eas)$/.test(tool)) return ask('it deploys or publishes', 'publish');
      return { ask: false };
    }
    case 'python': case 'python3': case 'py': {
      const m = rest.indexOf('-m'), mod = m >= 0 ? rest[m + 1] : null;
      if (mod === 'pip') {
        if (!['install', 'uninstall'].includes(rest[m + 2])) return { ask: false };   // pip list / show / freeze
        if (hasGlobal) return ask('it installs for the whole PC');
        return viaVenv(w[0]) ? { ask: false } : ask(hasVenv ? "it installs into the PC's Python, not the project's own environment (use .venv\\Scripts\\python -m pip)" : 'it installs into the PC-wide Python (make a project environment first: python -m venv .venv)');
      }
      if (mod === 'twine' || mod === 'webbrowser' || mod === 'http.server') return ask(mod === 'twine' ? 'it publishes the package' : 'it opens or serves on the network', mod === 'twine' ? 'publish' : 'command');
      return { ask: false };
    }
    case 'pip': case 'pip3': {
      if (!['install', 'uninstall'].includes(sub)) return { ask: false };
      if (hasGlobal) return ask('it installs for the whole PC');
      return viaVenv(w[0]) ? { ask: false } : ask("it installs into the PC-wide Python, not the project's own environment");
    }
    case 'git': {
      const gsub = (rest.find((x) => !x.startsWith('-')) || '').toLowerCase();
      if (rest.includes('--global') || rest.includes('--system')) return ask('it changes your global git settings', 'account');
      if (['push', 'send-email', 'request-pull'].includes(gsub)) return ask('it sends your code out (push)', 'publish');
      if (['init', 'add', 'commit', 'status', 'log', 'diff', 'show', 'branch', 'checkout', 'switch', 'restore', 'stash', 'tag', 'rm', 'mv', 'clone', 'pull', 'fetch', 'merge', 'rebase', 'reset', 'remote', 'config', 'rev-parse', 'ls-files', 'describe', 'blame', 'shortlog', 'grep'].includes(gsub)) return { ask: false };
      return ask("it's a git command Atlas doesn't know is safe");
    }
    case 'cargo': {
      if (['publish', 'install', 'login', 'owner', 'yank'].includes(sub)) return ask(sub === 'publish' ? 'it publishes the crate' : 'it installs for the whole PC or changes your account', sub === 'publish' ? 'publish' : 'command');
      return { ask: false };
    }
    case 'dotnet': {
      if (sub === 'tool' && (rest.includes('install') || rest.includes('update')) && hasGlobal) return ask('a global tool install changes the whole PC');
      if (sub === 'nuget' && rest.includes('push')) return ask('it publishes the package', 'publish');
      if (sub === 'workload' || sub === 'dev-certs') return ask('it changes the PC-wide .NET setup');
      return { ask: false };
    }
    case 'go': {
      if (sub === 'install') return ask('it installs for the whole PC');
      return { ask: false };
    }
    case 'flutter': case 'dart': {
      if (rest.includes('publish')) return ask('it publishes the package', 'publish');
      if (sub === 'upgrade' || sub === 'channel' || sub === 'config' || (sub === 'pub' && rest.includes('global'))) return ask('it changes the PC-wide Flutter setup');
      return { ask: false };
    }
    case 'mvn': {
      if (rest.includes('deploy') || rest.includes('release:perform')) return ask('it deploys or publishes', 'publish');
      return { ask: false };
    }
    case 'code': case 'explorer': return { ask: false };   // opening the project in an editor / the folder
    default:
      if (SIMPLE.has(prog)) return { ask: false };
      if (viaVenv(w[0])) return { ask: false };              // the project's own environment's tools
      if (/^\.[\\/]/.test(w[0]) || /^(node_modules[\\/]\.bin|bin|scripts?|gradlew)\b/i.test(w[0])) return { ask: false };   // the project's own scripts
      return ask(`Atlas doesn't know "${prog}" is safe`);
  }
}

/**
 * A path inside the project, given as the bot wrote it ("src/app.js"): the parts, or null when it would leave the
 * project (.., absolute, a drive, a share, home) or names something Atlas keeps out of (.git internals, the manifest).
 */
function relPath(p) {
  const s = String(p == null ? '' : p).trim().replace(/\\/g, '/');
  if (!s || s.length > 400 || escapes(s) || /[\x00-\x1f<>:"|?*]/.test(s)) return null;
  const parts = s.split('/').filter((x) => x && x !== '.');
  if (!parts.length || parts.some((x) => /^[\s.]+$/.test(x) || /[.\s]$/.test(x))) return null;
  if (parts[0].toLowerCase() === '.git' && parts.length > 1) return null;
  return parts;
}

/** A project's folder name from what the user or bot called it. */
function projectName(s) {
  const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 60).trim();
  if (/^(con|prn|aux|nul|com\d|lpt\d)$/i.test(t)) return t + ' project';
  return t;
}

/** How to check a project, from the files it has (the manifest's own check command wins). [has]: a name → exists test. */
function checkPlan(has, manifest = {}) {
  if (manifest && manifest.check) return manifest.check;
  if (has('package.json')) return 'npm test';
  if (has('Cargo.toml')) return 'cargo test';
  if (has('go.mod')) return 'go test ./...';
  if (has('gradlew') || has('gradlew.bat')) return 'gradlew test';
  if (has('pyproject.toml') || has('requirements.txt') || has('setup.py') || has('tests') || has('test')) return null;   // python: decided by the caller (venv, pytest)
  return null;
}

/** The last [max] characters of output, with a note when the start was dropped. */
function tail(text, max = 12000) {
  const s = String(text || '');
  return s.length > max ? `[${s.length - max} earlier characters not shown]\n` + s.slice(-max) : s;
}

module.exports = { checkCommand, relPath, projectName, checkPlan, tail, words, escapes };
