// The file tools Atlas Bot gets on the PC (the work is done by desktop/botfiles.js): its job folder, and real files to
// hand over: a document (PDF), a spreadsheet (Excel), an interactive page. Everything it makes shows in the Library.
import { renderMarkdown } from './md.js';

const str = (description) => ({ type: 'string', description });
const fn = (name, description, props, required = []) => ({ name, description, ...(props ? { parameters: { type: 'object', properties: props, ...(required.length ? { required } : {}) } } : {}) });
const rowsOf = { type: 'array', description: 'The rows: each row is a list of cells, in the same order as the columns. A cell starting with = is a formula, e.g. "=B2*C2".', items: { type: 'array', items: { type: 'string' } } };
const colsOf = { type: 'array', description: 'The column headings', items: { type: 'string' } };

export const FILE_TOOLS = [
  fn('workspace_list', "List the files in this job's folder (what you made, wrote or downloaded)."),
  fn('workspace_read', "Read a text file from this job's folder (txt, md, csv, json, html). Long files come in parts.",
    { name: str("The file's name"), from: { type: 'number', description: 'Start at this character (for the next part of a long file)' } }, ['name']),
  fn('workspace_write', "Write a text file in this job's folder (notes, a CSV, JSON, a draft). Set append to add to the end instead of replacing.",
    { name: str('The file name, e.g. notes.md'), text: str('What to write'), append: { type: 'boolean', description: 'Add to the end of the file' } }, ['name', 'text']),
  fn('make_document', 'Make a finished document for the user: a neatly typeset PDF (with the Markdown saved beside it). Use it for reports, briefs, letters, plans, summaries.',
    { title: str('The title'), markdown: str('The whole document in Markdown: headings, paragraphs, lists, tables, links') }, ['title', 'markdown']),
  fn('make_spreadsheet', 'Make a real Excel spreadsheet (.xlsx, with a .csv beside it): bold frozen header, sized columns; numbers stay numbers and cells starting with = are formulas. One sheet with columns + rows, or several sheets.',
    { title: str('The file title'), columns: colsOf, rows: rowsOf,
      sheets: { type: 'array', description: 'For several sheets instead: each with a name, columns and rows', items: { type: 'object', properties: { name: str('The sheet name'), columns: colsOf, rows: rowsOf } } } }, ['title']),
  fn('make_page', 'Make an interactive page the user opens in Atlas: text, tables they can sort and filter, and bar, line or pie charts. Give the content; Atlas draws the page.',
    { title: str('The title'), intro: str('Text at the top, in simple Markdown (headings, bullets, **bold**, links)'),
      tables: { type: 'array', description: 'Tables', items: { type: 'object', properties: { title: str('Its heading'), columns: colsOf, rows: rowsOf } } },
      charts: { type: 'array', description: 'Charts', items: { type: 'object', properties: { title: str('Its heading'), type: { type: 'string', enum: ['bar', 'line', 'pie'] },
        labels: { type: 'array', description: 'The labels along the bottom (or the pie slices)', items: { type: 'string' } },
        series: { type: 'array', description: 'One or more series of numbers, one per label', items: { type: 'object', properties: { name: str('Its name'), values: { type: 'array', items: { type: 'number' } } } } } } } } }, ['title']),
];
export const FILES = new Set(FILE_TOOLS.map((t) => t.name));

const LABELS = {
  workspace_list: () => 'Looking at its files', workspace_read: (a) => 'Reading ' + String(a.name || 'a file').slice(0, 40), workspace_write: (a) => 'Writing ' + String(a.name || 'a file').slice(0, 40),
  make_document: (a) => 'Making a document: ' + String(a.title || '').slice(0, 50), make_spreadsheet: (a) => 'Making a spreadsheet: ' + String(a.title || '').slice(0, 50), make_page: (a) => 'Making a page: ' + String(a.title || '').slice(0, 50),
};
export const filesLabel = (c) => (LABELS[c.name] ? LABELS[c.name](c.args || {}) : null);

/** Runs a file tool for [job]. A document's Markdown becomes HTML here, with the same renderer the chat uses (it escapes everything). */
export async function runFiles(call, job) {
  const host = window.atlasHost;
  if (!host.botFiles || !job) return { out: "Error: files aren't available here." };
  const args = { ...(call.args || {}) };
  if (call.name === 'make_document') args.html = renderMarkdown(String(args.markdown || args.text || ''));
  const r = await host.botFiles.run(job.id, job.goal, call.name, args);
  if (r && r.made) { job.made = Array.isArray(job.made) ? job.made : []; if (!job.made.includes(r.made)) job.made.push(r.made); }
  return r;
}

/** The line added to a report when the bot made files: where to find them. */
export function filesFooter(job) {
  const made = (job && Array.isArray(job.made) ? job.made : []).filter(Boolean);
  if (!made.length) return '';
  return `\n\n**Files:** ${made.slice(0, 12).join(', ')}${made.length > 12 ? ` and ${made.length - 12} more` : ''} (open them in Atlas Bot › Library).`;
}
