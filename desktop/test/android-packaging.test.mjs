// The phone's half of "passes the tests but breaks in the installed app": what the APK must carry for the code to work.
// The Kotlin compile can't see a missing asset (assets.open throws at run time) or a component missing from the
// manifest (ActivityNotFoundException / the service never starts), so this reads the sources and checks both.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const main = path.join(repo, 'app', 'src', 'main');
const javaDir = path.join(main, 'java');
// assets made at build time (app/build.gradle.kts copies them in), not kept in src/main/assets
const GENERATED = { 'CHANGELOG.json': path.join(repo, 'CHANGELOG.json') };

function kotlinFiles(dir, out = []) {
  for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, d.name);
    if (d.isDirectory()) kotlinFiles(p, out); else if (d.name.endsWith('.kt')) out.push(p);
  }
  return out;
}
const sources = kotlinFiles(javaDir).map((f) => fs.readFileSync(f, 'utf8'));

test('android: every asset the code opens is in the APK', () => {
  const opened = new Set();
  for (const s of sources) for (const m of s.matchAll(/assets\.open\("([^"]+)"/g)) opened.add(m[1]);
  assert.ok(opened.size >= 3, 'found the assets.open calls');
  const missing = [...opened].filter((a) => !fs.existsSync(path.join(main, 'assets', a)) && !(GENERATED[a] && fs.existsSync(GENERATED[a])));
  assert.deepEqual(missing, [], `opened but not packaged: ${missing.join(', ')}`);
  const gradle = fs.readFileSync(path.join(repo, 'app', 'build.gradle.kts'), 'utf8');
  for (const a of Object.keys(GENERATED)) if (opened.has(a)) assert.match(gradle, new RegExp(a.replace('.', '\\.')), `${a} is copied into the assets by the build`);
});

test('android: every activity, service, receiver and provider is declared in the manifest', () => {
  const manifest = fs.readFileSync(path.join(main, 'AndroidManifest.xml'), 'utf8');
  const declared = new Set([...manifest.matchAll(/android:name="\.([A-Za-z0-9_]+)"/g)].map((m) => m[1]));
  const KIND = /^(?:open |abstract )?class ([A-Za-z0-9_]+)[^{\n]*:\s*[A-Za-z.]*(Activity|Service|BroadcastReceiver|ContentProvider)\b/gm;
  const components = new Set();
  for (const s of sources) for (const m of s.matchAll(KIND)) if (!/^(open|abstract) /.test(m[0])) components.add(m[1]);
  assert.ok(components.size >= 10, 'found the components');
  const missing = [...components].filter((c) => !declared.has(c));
  assert.deepEqual(missing, [], `not in AndroidManifest.xml: ${missing.join(', ')}`);
});
