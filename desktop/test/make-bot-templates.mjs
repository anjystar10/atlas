// Writes app/src/main/assets/bot-templates.json from renderer/bottemplates.js, so the phone offers exactly the same
// ready-made bots as the PC. Run: node test/make-bot-templates.mjs (templates.test.mjs fails when they drift apart).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { TEMPLATES, TEMPLATES_V } from '../renderer/bottemplates.js';

const here = path.dirname(fileURLToPath(import.meta.url));
export const ASSET = path.join(here, '../../app/src/main/assets/bot-templates.json');
export function assetText() {
  return JSON.stringify({ note: 'Made by desktop/test/make-bot-templates.mjs from desktop/renderer/bottemplates.js. Do not edit by hand.', v: TEMPLATES_V, templates: TEMPLATES }, null, 1) + '\n';
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  fs.writeFileSync(ASSET, assetText());
  console.log('wrote', path.relative(process.cwd(), ASSET), TEMPLATES.length, 'templates');
}
