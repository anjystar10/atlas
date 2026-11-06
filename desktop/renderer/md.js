// Atlas's replies as neat HTML: headings, lists, bold, italics, quotes, links, tables and code blocks.
// Everything is escaped first, so nothing in a reply can inject markup.
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function inline(s) {
  let t = esc(s);
  const codes = [];
  t = t.replace(/`([^`]+)`/g, (_m, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
  t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, (_m, txt, url) => `<a href="${url}" target="_blank" rel="noreferrer">${txt}</a>`);
  t = t.replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, (_m, pre, url) => `${pre}<a href="${url}" target="_blank" rel="noreferrer">${url}</a>`);
  t = t.replace(/\*\*(.+?)\*\*|__(.+?)__/g, (_m, a, b) => `<strong>${a || b}</strong>`);
  t = t.replace(/(^|[^\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)/g, '$1<em>$2</em>').replace(/(^|[^\w_])_(?!\s)(.+?)(?<!\s)_(?!\w)/g, '$1<em>$2</em>');
  return t.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[+i]}</code>`);
}

export function renderMarkdown(src) {
  const lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
  const out = []; let i = 0; let list = null; let para = [];
  const flushPara = () => { if (para.length) { out.push(`<p>${para.map(inline).join('<br>')}</p>`); para = []; } };
  const closeList = () => { if (list) { out.push(`</${list}>`); list = null; } };
  while (i < lines.length) {
    const line = lines[i];
    const fence = line.match(/^\s*```\s*([\w+-]*)\s*$/);
    if (fence) {
      flushPara(); closeList();
      const code = []; i++;
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) code.push(lines[i++]);
      i++;
      out.push(`<div class="codebox"><div class="codehead"><span>${esc(fence[1] || 'code')}</span><button class="copy" data-copy="${esc(code.join('\n'))}">Copy</button></div><pre><code>${esc(code.join('\n'))}</code></pre></div>`);
      continue;
    }
    if (!line.trim()) { flushPara(); closeList(); i++; continue; }
    let m;
    if ((m = line.match(/^\s{0,3}(#{1,6})\s+(.*)$/))) { flushPara(); closeList(); const lv = Math.min(4, m[1].length + 1); out.push(`<h${lv}>${inline(m[2].replace(/#+\s*$/, ''))}</h${lv}>`); i++; continue; }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flushPara(); closeList(); out.push('<hr>'); i++; continue; }
    if ((m = line.match(/^\s*>\s?(.*)$/))) { flushPara(); closeList(); out.push(`<blockquote>${inline(m[1])}</blockquote>`); i++; continue; }
    if (line.trim().startsWith('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      flushPara(); closeList();
      const row = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
      const head = row(line); i += 2; const body = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) body.push(row(lines[i++]));
      out.push(`<div class="tablewrap"><table><thead><tr>${head.map((h) => `<th>${inline(h)}</th>`).join('')}</tr></thead><tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);
      continue;
    }
    if ((m = line.match(/^(\s*)[-*•+]\s+(.*)$/))) { flushPara(); if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; } out.push(`<li${m[1].length >= 2 ? ' class="sub"' : ''}>${inline(m[2])}</li>`); i++; continue; }
    if ((m = line.match(/^(\s*)(\d{1,3})[.)]\s+(.*)$/))) { flushPara(); if (list !== 'ol') { closeList(); out.push(`<ol start="${m[2]}">`); list = 'ol'; } out.push(`<li>${inline(m[3])}</li>`); i++; continue; }
    closeList(); para.push(line.trim()); i++;
  }
  flushPara(); closeList();
  return out.join('');
}
