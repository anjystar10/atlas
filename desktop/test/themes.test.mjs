// The colour themes: every text colour clears WCAG AA in both looks (the same numbers as the phone's ThemesTest and
// tools/gen_themes.py --check), the CSS theme block and themes.js say exactly what tools/themes.json says, and
// "switch to the gold theme" finds the right one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { THEMES, DEFAULT, findTheme } from '../renderer/themes.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const data = JSON.parse(readFileSync(join(root, 'tools/themes.json'), 'utf8'));
const css = readFileSync(join(root, 'desktop/renderer/style.css'), 'utf8');

const lum = (hex) => {
  const n = parseInt(hex.slice(1), 16);
  const lin = (v) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
};
const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };

test('every text colour clears AA on every surface, in both looks', () => {
  const bad = [];
  assert.equal(data.themes.length, 4);
  for (const t of data.themes) for (const mode of ['dark', 'light']) {
    const p = t[mode];
    for (const k of ['fg', 'muted', 'faint', 'accentInk', 'blue', 'warm', 'done', 'danger'])
      for (const g of ['bg', 'surface', 'surface2']) if (ratio(p[k], p[g]) < 4.5) bad.push(`${t.id} ${mode} ${k} on ${g}`);
    for (const g of ['primaryTop', 'primaryBottom']) if (ratio(p.onPrimary, p[g]) < 4.5) bad.push(`${t.id} ${mode} onPrimary on ${g}`);
    if (ratio(p.accent, p.bg) < 3) bad.push(`${t.id} ${mode} accent on bg`);
  }
  assert.deepEqual(bad, []);
});

test('the CSS theme block and themes.js match tools/themes.json', () => {
  assert.equal(DEFAULT, 'midnight');
  assert.equal(data.default, 'midnight');
  for (const t of data.themes) for (const mode of ['dark', 'light']) {
    const sel = `:root[data-palette="${t.id}"]` + (mode === 'light' ? '[data-theme="light"]' : '') + ' {';
    const at = css.indexOf(sel.replace(' {', ''));
    assert.ok(at > 0, `${t.id} ${mode} block`);
    const block = css.slice(at, css.indexOf('}', at));
    const p = t[mode];
    for (const [k, v] of [['--bg', p.bg], ['--fg', p.fg], ['--accent', p.accent], ['--on-primary', p.onPrimary], ['--primary-a', p.primaryTop], ['--miss', p.danger]])
      assert.ok(block.includes(`${k}: ${v};`), `${t.id} ${mode} ${k} ${v}`);
    const m = THEMES.find((x) => x.id === t.id);
    assert.equal(m[mode].accent, p.accent);
  }
  // the default theme is also the plain :root, so a PC that never chose still opens in Midnight & Gold
  assert.match(css, /:root, :root\[data-palette="midnight"\] \{/);
});

test('Atlas finds a theme from how people say it', () => {
  const id = (w) => findTheme(w)?.id ?? null;
  assert.equal(id('gold'), 'midnight');
  assert.equal(id('switch to the cyan one'), 'graphite');
  assert.equal(id('Forest & Coral'), 'forest');
  assert.equal(id('purple'), 'indigo');
  assert.equal(id('golden retriever'), null);
  assert.equal(id(''), null);
});
