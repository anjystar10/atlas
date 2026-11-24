// Every local file the main process require()s must be packed into the installer.
// 0.11.0 shipped without attach.js and crashed on launch ("Cannot find module './attach'"); this stops that recurring.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const files = pkg.build.files.filter(f => !f.startsWith('!'));

function packed(rel) {
  return files.some(f => {
    if (f === rel) return true;
    if (f.endsWith('/**/*')) return rel.startsWith(f.slice(0, -4));
    return false;
  });
}

// walk require('./x') from main.js through every local file it reaches
function localRequires(file) {
  const src = fs.readFileSync(path.join(root, file), 'utf8');
  const out = [];
  for (const m of src.matchAll(/require\(\s*['"](\.{1,2}\/[^'"]+)['"]\s*\)/g)) {
    let rel = path.posix.normalize(path.posix.join(path.posix.dirname(file), m[1]));
    if (!/\.(js|json|node)$/.test(rel)) rel += fs.existsSync(path.join(root, rel + '.js')) ? '.js' : (fs.existsSync(path.join(root, rel, 'index.js')) ? '/index.js' : '.js');
    out.push(rel);
  }
  return out;
}

test('everything main.js loads is inside the installer', () => {
  const seen = new Set();
  const queue = [pkg.main || 'main.js', 'preload.js'];
  const missing = [];
  while (queue.length) {
    const f = queue.shift();
    if (seen.has(f)) continue;
    seen.add(f);
    assert.ok(fs.existsSync(path.join(root, f)), `${f} is required but does not exist`);
    if (!packed(f)) missing.push(f);
    if (f.endsWith('.js')) queue.push(...localRequires(f));
  }
  assert.deepEqual(missing, [], `not in package.json build.files: ${missing.join(', ')}`);
  assert.ok(seen.size > 10, 'walked the require graph');
});

// The app's own pages forbid inline scripts (script-src 'self'): an inline <script> there never runs.
// The floating orb shipped like that and was a dead, invisible box on top of the screen.
test("the app's pages keep their scripts in files their CSP allows", () => {
  for (const f of fs.readdirSync(path.join(root, 'renderer')).filter((n) => n.endsWith('.html'))) {
    const html = fs.readFileSync(path.join(root, 'renderer', f), 'utf8');
    const csp = (html.match(/Content-Security-Policy" content="([^"]*)"/) || [])[1] || '';
    if (!/script-src[^;]*'self'/.test(csp) || /script-src[^;]*'unsafe-inline'/.test(csp)) continue;
    const inline = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)].filter((m) => !/\bsrc=/.test(m[1]) && m[2].trim());
    assert.equal(inline.length, 0, `${f} has an inline <script> its CSP blocks`);
    for (const m of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) assert.ok(fs.existsSync(path.join(root, 'renderer', m[1])), `${f} loads ${m[1]}, which is missing`);
  }
});
