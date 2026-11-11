// A small local website for testing Atlas Bot's browser: search, a two-step form with a dropdown and a file upload,
// a download, a shop with a "Place order" button, and a sign-in page. It records what it receives in `got`.
'use strict';
const http = require('http');

function page(title, body) {
  return `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>
  <style>body{font:16px system-ui;margin:40px;max-width:700px} input,select,button{font:inherit;margin:6px 0;display:block}</style></head><body>${body}</body></html>`;
}
function readBody(req) { return new Promise((r) => { const c = []; req.on('data', (d) => c.push(d)); req.on('end', () => r(Buffer.concat(c))); }); }

function start(port = 0) {
  const got = { searches: [], step1: null, upload: null, orders: 0, done: false };
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const send = (html, code = 200, headers = {}) => { res.writeHead(code, { 'content-type': 'text/html; charset=utf-8', ...headers }); res.end(html); };
    if (u.pathname === '/') return send(page('Test Shop Home', `<h1>Test Shop</h1>
      <form action="/search" method="get"><label for="q">Search the shop</label><input id="q" name="q" placeholder="What are you looking for?"><button type="submit">Search</button></form>
      <p><a href="/form">Join the newsletter form</a></p><p><a href="/files/report.csv">Download the price list</a></p><p><a href="/shop">Go to the shop</a></p><p><a href="/login">Sign in</a></p>`));
    if (u.pathname === '/search') { const q = u.searchParams.get('q') || ''; got.searches.push(q);
      return send(page('Results', `<h1>Results for ${q.replace(/</g, '&lt;')}</h1><ul><li><a href="/shop">Blue Kettle — $25</a></li><li><a href="/shop">Red Kettle — $30</a></li></ul>`)); }
    if (u.pathname === '/form' && req.method === 'GET') return send(page('Step 1', `<h1>Step 1 of 2</h1><form action="/form/step2" method="post">
      <label for="name">Your name</label><input id="name" name="name"><label for="country">Country</label>
      <select id="country" name="country"><option value="">Choose…</option><option value="ng">Nigeria</option><option value="uk">United Kingdom</option><option value="us">United States</option></select>
      <button type="submit">Next</button></form>`));
    if (u.pathname === '/form/step2' && req.method === 'POST') {
      const b = new URLSearchParams((await readBody(req)).toString()); got.step1 = { name: b.get('name'), country: b.get('country') };
      return send(page('Step 2', `<h1>Step 2 of 2</h1><form action="/form/done" method="post" enctype="multipart/form-data">
        <label for="cv">Attach a file</label><input id="cv" type="file" name="cv"><button type="submit">Finish</button></form>`));
    }
    if (u.pathname === '/form/done' && req.method === 'POST') {
      const raw = (await readBody(req)).toString('latin1');
      const m = raw.match(/filename="([^"]*)"[\s\S]*?\r\n\r\n([\s\S]*?)\r\n--/);
      got.upload = m ? { filename: m[1], content: m[2] } : { filename: null }; got.done = true;
      return send(page('Done', '<h1>Thanks, all done!</h1><p>Your form was received.</p>'));
    }
    if (u.pathname === '/files/report.csv') { res.writeHead(200, { 'content-type': 'text/csv', 'content-disposition': 'attachment; filename="price list.csv"' }); return res.end('item,price\nBlue Kettle,25\nRed Kettle,30\n'); }
    if (u.pathname === '/shop') return send(page('Shop', `<h1>Blue Kettle</h1><p>$25</p><form action="/order" method="post"><label for="qty">Quantity</label><input id="qty" name="qty" value="1"><button type="submit">Place order</button></form>`));
    if (u.pathname === '/order' && req.method === 'POST') { got.orders++; return send(page('Ordered', '<h1>Order placed</h1><p>Order number 1001.</p>')); }
    if (u.pathname === '/login') return send(page('Sign in', `<h1>Sign in</h1><form action="/" method="post"><label for="email">Email</label><input id="email" name="email"><label for="pw">Password</label><input id="pw" name="pw" type="password"><button type="submit">Sign in</button></form>`));
    send(page('Not found', '<h1>Not found</h1>'), 404);
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve({ server, got, base: `http://127.0.0.1:${server.address().port}` })));
}
module.exports = { start };
