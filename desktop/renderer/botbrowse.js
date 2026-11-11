// The browser tools Atlas Bot gets on the PC: a real Chromium window of its own (desktop/botbrowser.js) that it
// drives like a person: open a page, read it, click, type, choose, download, upload, and hand it to the user for
// sign-ins and "are you human" checks.
const str = (description) => ({ type: 'string', description });
const fn = (name, description, props, required = []) => ({ name, description, ...(props ? { parameters: { type: 'object', properties: props, ...(required.length ? { required } : {}) } } : {}) });
const target = str('The number of the thing on the page (from browser_read), or its exact visible words');

export const BROWSER_TOOLS = [
  fn('browser_open', 'Open a web page in your browser (your own window, with its own sign-ins). Returns the page: its text and a numbered list of the things you can use.', { url: str('The address, e.g. https://www.example.com') }, ['url']),
  fn('browser_read', 'Read the page your browser is on now: its text and the numbered list of links, buttons and fields. Read again after anything changes the page.'),
  fn('browser_click', 'Click a link, button, checkbox or tab on the page. Returns the page after the click.', { target }, ['target']),
  fn('browser_type', "Type into a field on the page (replaces what's there). Set submit to true to press Enter afterwards (e.g. to search).",
    { target, text: str('What to type'), submit: { type: 'boolean', description: 'Press Enter after typing' } }, ['target', 'text']),
  fn('browser_select', 'Choose an option in a dropdown on the page.', { target, option: str('The option, as it reads') }, ['target', 'option']),
  fn('browser_press', 'Press a key in the browser: Enter, Tab, Escape, Backspace, Up, Down, Left, Right, PageUp, PageDown, Home or End.', { key: str('The key') }, ['key']),
  fn('browser_scroll', 'Scroll the page: down, up, top or bottom.', { direction: str('down, up, top or bottom') }, ['direction']),
  fn('browser_back', 'Go back to the previous page.'),
  fn('browser_screenshot', 'See the page as a picture, for layouts, images or charts the text misses.'),
  fn('browser_wait', 'Wait for the page: a few seconds (up to 10), or until some words appear.', { seconds: { type: 'number', description: 'How long, up to 10' }, text: str('Words to wait for') }),
  fn('browser_download', "Download a file (a link's number, or its address) into this job's folder on the PC.", { target, url: str('Or the file address') }),
  fn('browser_upload', "Attach a file from this job's folder to an upload field on the page.", { target, file: str("The file's name in the job folder") }, ['target', 'file']),
  fn('browser_handover', 'Give the browser window to the user when they must do something themselves: sign in, a password, a code, a CAPTCHA or "are you human" check, a passkey, or a payment detail. The job waits until they tap Hand Back.',
    { reason: str('What they need to do, in one sentence') }, ['reason']),
];
export const BROWSER = new Set(BROWSER_TOOLS.map((t) => t.name));
const short = (u) => String(u || '').replace(/^https?:\/\/(www\.)?/, '').slice(0, 50);
const LABELS = {
  browser_open: (a) => 'Opening ' + short(a.url), browser_read: () => 'Reading the page', browser_click: (a) => `Clicking ${/^\d+$/.test(String(a.target)) ? 'on the page' : '“' + String(a.target).slice(0, 40) + '”'}`,
  browser_type: () => 'Typing on the page', browser_select: (a) => 'Choosing ' + String(a.option || '').slice(0, 40), browser_press: (a) => 'Pressing ' + (a.key || ''),
  browser_scroll: () => 'Scrolling the page', browser_back: () => 'Going back', browser_screenshot: () => 'Looking at the page', browser_wait: () => 'Waiting for the page',
  browser_download: () => 'Downloading a file', browser_upload: () => 'Attaching a file', browser_handover: () => 'Handing the browser to you',
};
export const browserLabel = (c) => (LABELS[c.name] ? LABELS[c.name](c.args || {}) : null);
