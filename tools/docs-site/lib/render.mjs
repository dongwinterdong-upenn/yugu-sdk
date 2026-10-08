// Markdown to HTML. One markdown-it instance with the site's rules: heading anchors and the page outline,
// highlighted code blocks with a copy button, wrapped tables, GitHub style callouts, link resolution,
// and fenced components (tabs, cards, endpoint, params, diagrams and so on) dispatched to components.mjs.
import MarkdownIt from 'markdown-it';
import { slugify } from './sources.mjs';
import { highlightLines, langLabel, normalizeLang } from './highlight.mjs';
import { icon } from './ui.mjs';

export const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const CALLOUTS = {
  NOTE: { cls: 'note', label: '说明', icon: 'info' },
  TIP: { cls: 'tip', label: '提示', icon: 'lightbulb' },
  IMPORTANT: { cls: 'important', label: '重要', icon: 'error' },
  WARNING: { cls: 'warning', label: '注意', icon: 'warning' },
  CAUTION: { cls: 'caution', label: '警告', icon: 'warning' },
};

const LABEL_KIND = { 说明: 'NOTE', 提示: 'TIP', 重要: 'IMPORTANT', 注意: 'WARNING', 警告: 'CAUTION' };

export function codeBlockHtml(code, lang, { title, extraClass = '', rows } = {}) {
  const l = normalizeLang(lang);
  const label = title || langLabel(lang);
  const style = rows ? ` style="--rows:${rows}"` : '';
  return `<div class="code${extraClass ? ' ' + extraClass : ''}" data-lang="${esc(l)}"${style}>` +
    `<div class="code-head"><span class="code-title">${esc(label)}</span>` +
    `<button class="code-copy" type="button" aria-label="复制代码">${icon('content_copy')}<span>复制</span></button></div>` +
    `<pre class="code-body"><code>${highlightLines(code, l)}</code></pre></div>`;
}

export function createRenderer({ components, resolver }) {
  const md = new MarkdownIt({ html: false, linkify: false, typographer: false });

  // Headings: stable ids, an anchor link, and outline entries for h2 and h3.
  md.core.ruler.push('heading_ids', (state) => {
    const env = state.env;
    env.usedIds = env.usedIds || new Map();
    const tokens = state.tokens;
    for (let i = 0; i < tokens.length; i++) {
      const t = tokens[i];
      if (t.type !== 'heading_open') continue;
      const inline = tokens[i + 1];
      const text = inline.children.filter((c) => c.type === 'text' || c.type === 'code_inline').map((c) => c.content).join('');
      let base = (env.idPrefix || '') + (slugify(text) || 'section');
      let id = base;
      const n = env.usedIds.get(base) || 0;
      if (n) id = `${base}-${n}`;
      env.usedIds.set(base, n + 1);
      t.attrSet('id', id);
      const level = Number(t.tag.slice(1));
      const html = inline.children.map((c) => (c.type === 'code_inline' ? `<code>${esc(c.content)}</code>` : c.type === 'text' ? esc(c.content) : '')).join('');
      if (!env.noToc && (level === 2 || level === 3)) (env.toc = env.toc || []).push({ level, id, text, html });
      if (env.onHeading) env.onHeading({ level, id, text, line: t.map ? t.map[0] : null });
    }
  });

  md.renderer.rules.heading_close = (tokens, idx) => {
    const id = tokens[idx - 2].attrGet('id');
    return `<a class="hash" href="#${esc(id)}" aria-label="章节链接">#</a></${tokens[idx].tag}>\n`;
  };

  // Callouts: a blockquote whose first line is [!NOTE], [!TIP], [!IMPORTANT], [!WARNING] or [!CAUTION].
  md.core.ruler.after('block', 'callouts', (state) => {
    const tokens = state.tokens;
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].type !== 'blockquote_open') continue;
      const inline = tokens[i + 2];
      if (!inline || inline.type !== 'inline') continue;
      // GitHub alert syntax, or a Chinese label with a full-width colon: 说明：，提示：，重要：，注意：，警告：
      const m = inline.content.match(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*\n?/) || inline.content.match(/^(说明|提示|重要|注意|警告)：/);
      if (!m) continue;
      const kind = CALLOUTS[m[1]] || CALLOUTS[LABEL_KIND[m[1]]];
      tokens[i].type = 'callout_open';
      tokens[i].meta = kind;
      inline.content = inline.content.slice(m[0].length);
      let depth = 0;
      for (let j = i; j < tokens.length; j++) {
        if (tokens[j].type === 'blockquote_open' || tokens[j].type === 'callout_open') depth++;
        if (tokens[j].type === 'blockquote_close') { depth--; if (depth === 0) { tokens[j].type = 'callout_close'; break; } }
      }
      if (!inline.content.trim()) {
        tokens.splice(i + 1, 3);
      }
    }
  });
  md.renderer.rules.callout_open = (tokens, idx) => {
    const k = tokens[idx].meta;
    return `<div class="callout callout-${k.cls}" role="note"><div class="callout-label">${icon(k.icon)}<span>${k.label}</span></div><div class="callout-body">`;
  };
  md.renderer.rules.callout_close = () => '</div></div>\n';

  md.renderer.rules.link_close = (tokens, idx, options, env, self) => ((env.linkStack || []).pop() === false ? '' : self.renderToken(tokens, idx, options));

  md.renderer.rules.table_open = () => '<div class="table-wrap"><table>\n';
  md.renderer.rules.table_close = () => '</table></div>\n';

  // Links: repo: and page: targets are resolved to site URLs, external links are marked.
  const defaultLinkOpen = md.renderer.rules.link_open || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options));
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const t = tokens[idx];
    const href = t.attrGet('href');
    const url = resolver.resolveHref(href, env);
    env.linkStack = env.linkStack || [];
    env.linkStack.push(url !== null);
    if (url === null) return '';
    t.attrSet('href', url);
    if (/^https?:/.test(url) && !url.startsWith(resolver.siteOrigin)) {
      t.attrSet('rel', 'noopener');
      t.attrJoin('class', 'ext');
    }
    return defaultLinkOpen(tokens, idx, options, env, self);
  };

  // Inline code naming a repository document or spec file links to its page.
  md.core.ruler.push('code_links', (state) => {
    for (const block of state.tokens) {
      if (block.type !== 'inline' || !block.children) continue;
      let inLink = 0;
      const out = [];
      const kids = block.children;
      for (let k = 0; k < kids.length; k++) {
        const c = kids[k];
        // [`SANDBOX.md`](../SANDBOX.md) reads as the page name on the site.
        if (c.type === 'link_open' && kids[k + 1]?.type === 'code_inline' && kids[k + 2]?.type === 'link_close') {
          const title = resolver.titleFor(kids[k + 1].content);
          if (title) {
            const t = new state.Token('text', '', 0);
            t.content = title;
            kids[k + 1] = t;
          }
        }
        if (c.type === 'link_open') inLink++;
        if (c.type === 'link_close') inLink--;
        if (c.type === 'code_inline' && !inLink) {
          const target = resolver.codeLink(c.content, state.env);
          if (target) {
            const open = new state.Token('link_open', 'a', 1);
            open.attrs = [['href', target], ['class', 'code-link']];
            const close = new state.Token('link_close', 'a', -1);
            out.push(open, c, close);
            continue;
          }
        }
        out.push(c);
      }
      block.children = out;
    }
  });

  // On API reference pages a field table (first column a name, one column 类型) is shown as a parameter
  // list: name, type and required on one line, the remaining columns as the description.
  const NAME_COLS = ['字段', '段', '参数', '请求头', '参数名', 'query 参数', '帧字段', '响应头'];
  md.core.ruler.push('param_tables', (state) => {
    if (!state.env.page || state.env.page.layout !== 'api') return;
    const tokens = state.tokens;
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].type !== 'table_open') continue;
      let j = i;
      while (tokens[j].type !== 'table_close') j++;
      const rows = [];
      let cur = null;
      for (let k = i; k < j; k++) {
        const tk = tokens[k];
        if (tk.type === 'tr_open') cur = [];
        else if (tk.type === 'tr_close') rows.push(cur);
        else if (tk.type === 'inline' && cur) cur.push(tk);
      }
      const head = rows[0].map((c) => c.content.trim());
      const typeCol = head.indexOf('类型');
      if (!NAME_COLS.includes(head[0]) || typeCol < 0) continue;
      const reqCol = head.indexOf('必填');
      const descCols = head.map((_, n) => n).filter((n) => n !== 0 && n !== typeCol && n !== reqCol);
      const renderCell = (tk) => md.renderer.renderInline(tk.children, md.options, state.env);
      const items = rows.slice(1).map((cells) => {
        const name = cells[0].content.replace(/`/g, '').trim();
        let type = cells[typeCol].content.trim();
        let required = null;
        if (reqCol >= 0) required = cells[reqCol].content.trim() === '是';
        if (/，可选$/.test(type)) { required = false; type = type.replace(/，可选$/, ''); }
        const typeHtml = md.renderer.renderInline(md.parseInline(type, state.env)[0].children, md.options, state.env);
        const desc = descCols.map((n) => {
          const html = renderCell(cells[n]);
          return descCols.length > 1 && head[n] !== '说明' && head[n] !== '含义' ? `<p><span class="param-k">${esc(head[n])}：</span>${html}</p>` : `<p>${html}</p>`;
        }).join('');
        const req = required === true ? '<span class="param-req">必填</span>' : required === false ? '<span class="param-opt">可选</span>' : '';
        return `<div class="param"><div class="param-head"><code class="param-name">${esc(name)}</code><span class="param-type">${typeHtml}</span>${req}</div><div class="param-desc">${desc}</div></div>`;
      }).join('');
      const html = new state.Token('html_block', '', 0);
      html.content = `<div class="params">${items}</div>\n`;
      tokens.splice(i, j - i + 1, html);
    }
  });

  // Short first-column labels stay on one line; the other columns take the remaining width.
  md.core.ruler.push('key_columns', (state) => {
    const tokens = state.tokens;
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].type !== 'table_open') continue;
      let j = i;
      const firsts = [];
      let col = 0;
      while (tokens[j].type !== 'table_close') {
        if (tokens[j].type === 'tr_open') col = 0;
        if (tokens[j].type === 'th_open' || tokens[j].type === 'td_open') {
          if (col === 0) firsts.push({ open: tokens[j], text: tokens[j + 1].content });
          col++;
        }
        j++;
      }
      if (firsts.length && firsts.every((c) => [...c.text.replace(/`/g, '')].length <= 8)) {
        for (const c of firsts) c.open.attrJoin('class', 'k');
      }
    }
  });

  md.renderer.rules.code_inline = (tokens, idx) => {
    const c = tokens[idx].content;
    return c.length > 36 ? `<code class="long">${esc(c)}</code>` : `<code>${esc(c)}</code>`;
  };

  md.renderer.rules.fence = (tokens, idx, options, env) => {
    const t = tokens[idx];
    const info = t.info.trim();
    const [name, ...rest] = info.split(/\s+/);
    const args = rest.join(' ');
    if (components[name]) return components[name].html(t.content, { env, args, render: (text, sub) => renderNested(text, env, sub) });
    const titleMatch = args.match(/title=("[^"]+"|\S+)/);
    const title = titleMatch ? titleMatch[1].replace(/^"|"$/g, '') : undefined;
    return codeBlockHtml(t.content, name, { title });
  };

  function renderNested(text, env, { idPrefix = '', noToc = true } = {}) {
    const sub = { ...env, idPrefix, noToc, toc: [], onHeading: null };
    sub.usedIds = env.usedIds;
    return md.render(text, sub);
  }

  return md;
}
