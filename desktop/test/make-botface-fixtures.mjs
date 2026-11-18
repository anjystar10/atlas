// Writes tools/botface-fixtures.json from renderer/botface.js, so BotFaceTest.kt checks the phone gives the same
// answers (names, faces, migration, History). Run: node test/make-botface-fixtures.mjs (botface.test.mjs fails when stale).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as F from '../renderer/botface.js';

const here = path.dirname(fileURLToPath(import.meta.url));
export const FIX = path.join(here, '../../tools/botface-fixtures.json');

const bots = [
  { id: 'sp-research', name: 'Research Bot', role: 'Finds things out and backs them with sources' },
  { id: 'sp-developer', name: 'Developer Bot', role: 'Programming, debugging and technical work' },
  { id: 'sp-finance', name: 'Finance Bot', role: 'Numbers, budgets, prices and comparisons' },
  { id: 'sp-planner', name: 'Planner Bot', role: 'Turns goals into schedules and step-by-step plans' },
  { id: 'bot-x1', name: 'Trader', role: 'Trades your connected accounts inside your limits' },
  { id: 'bot-x2', name: 'Inbox', role: 'Keeps your email under control' },
  { id: 'bot-x3', name: 'Reel Poster', role: 'Prepares and posts your Instagram reels' },
  { id: 'bot-x4', name: 'Builder', role: 'Builds software projects on your laptop' },
  { id: 'bot-x5', name: 'Chief of Staff', role: 'Plans your day each morning' },
  { id: 'bot-x6', name: 'Market Watcher', role: 'Watches the markets and news you care about' },
  { id: 'bot-x7', name: 'Researcher', role: 'Deep research with sources' },
  { id: 'bot-mine', name: 'Developer bot', role: 'my own one' },
  { id: 'bot-kept', name: 'Ted', title: 'Coder', role: 'x', face: { shape: 'petal', color: 3 } },
  { id: 'bot-face', name: 'Zed', role: 'Walks the dog', face: { shape: 'nope', color: 99 } },
  { id: 'bot-face2', name: 'Lola', role: 'Sings', face: { shape: 'hex', color: 7 } },
  { id: '', name: 'Nameless helper', role: '' },
  { id: 'bot-ü', name: 'Zoë 🎧', role: 'Finds music' },
  { id: 'bot-renamed', name: 'Inbox', title: 'Mail', persona: 'Mine.', face: { shape: 'orb', color: 0 } },
];
const strings = ['', 'a', 'sp-research', 'bot-ü', 'Zoë 🎧', 'x'.repeat(100), 'Ted · Developer'];

const DAY = 86400000;
const now = Date.UTC(2026, 10, 18, 9, 30);
const jobs = [
  { id: 'j1', goal: 'Summarize what is new in AI', state: 'completed', bot: 'sp-research', at: now - 3600000, where: 'other' },
  { id: 'j2', goal: 'Install FFmpeg on this PC', state: 'awaiting_approval', bot: '', at: now - 7200000, where: 'other' },
  { id: 'j3', goal: 'Type the exact text into Notepad', state: 'awaiting_approval', at: now - DAY - 60000, where: 'here' },
  { id: 'j4', goal: 'Weekly budget check', state: 'running', bot: 'sp-finance', at: now - 3 * DAY, where: 'here' },
  { id: 'j5', goal: 'Post the reel', state: 'failed', bot: 'bot-x3', at: now - 3 * DAY, where: 'here' },
  { id: 'j1', goal: 'duplicate id is dropped', state: 'completed', at: now },
  { id: '', goal: 'no id is dropped', state: 'completed', at: now },
  { id: 'j6', goal: 'Late night job', state: 'needs_review', bot: 'atlas', at: Date.UTC(2026, 10, 17, 23, 30), where: 'here' },
];
const historyCases = [
  { opts: { now } },
  { opts: { now, tzMin: 60 } },
  { opts: { now, tzMin: -600 } },
  { opts: { now, bot: 'atlas' } },
  { opts: { now, bot: 'sp-research' } },
  { opts: { now, query: 'the  NOTEPAD' } },
  { opts: { now, query: 'reel budget' } },
  { opts: { now, bot: 'nobody' } },
];

export function fixtures() {
  return {
    note: 'Made by desktop/test/make-botface-fixtures.mjs from desktop/renderer/botface.js. Do not edit by hand.',
    hash: strings.map((s) => ({ s, out: F.hash(s) })),
    bots: bots.map((b) => {
      const m = F.migrate(b);
      return { in: b, motif: F.motifOf(b), face: F.faceOf(b), label: F.label(b), subtitle: F.subtitle(b), changed: m !== b, migrated: m,
        migratedFace: F.faceOf(m), migratedLabel: F.label(m), persona: F.personaPrompt(m) };
    }),
    jobs,
    history: historyCases.map((c) => ({ ...c, out: F.historyGroups(jobs, c.opts) })),
  };
}
export const fixtureText = () => JSON.stringify(fixtures(), null, 1) + '\n';
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) { fs.writeFileSync(FIX, fixtureText()); console.log('wrote', path.relative(process.cwd(), FIX)); }
