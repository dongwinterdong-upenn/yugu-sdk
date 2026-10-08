// Fenced components. A page writes ```tabs, ```cards, ```endpoint and so on with a YAML body; each
// component turns it into HTML for the site and into plain Markdown for the .md copy of the page.
import fs from 'node:fs';
import path from 'node:path';
import * as yaml from 'js-yaml';
import { codeBlock, findSection, sectionMarkdown, SourceError } from './sources.mjs';
import { codeBlockHtml, esc } from './render.mjs';
import { icon, METHOD_CLASS } from './ui.mjs';
import { diagram } from './diagrams.mjs';
import { normalizeLang } from './highlight.mjs';

// Tab keys shared across pages, so a platform picked once stays picked.
const KEYS = { 'Java': 'java', '安卓': 'android', 'iOS': 'ios', '网页': 'web', '小程序': 'miniprogram', 'cURL': 'curl',
  '安卓平替': 'android-stcompat', 'iOS 平替': 'ios-stcompat', 'Objective-C': 'objc', 'Swift': 'swift', 'Maven': 'maven',
  'Gradle': 'gradle', 'Gradle Kotlin': 'gradle-kts', 'npm': 'npm', 'SwiftPM': 'swiftpm', 'CocoaPods': 'cocoapods', 'Node.js': 'node',
  '浏览器': 'browser', 'script 标签': 'script' };

let uid = 0;
const nextId = (p) => `${p}${++uid}`;

function parseYaml(content, where) {
  try { return yaml.load(content) ?? {}; } catch (e) { throw new SourceError(`${where}: ${e.message}`); }
}

function snippetOf(item, repo) {
  if (item.code != null) return { code: String(item.code).replace(/\n$/, ''), lang: item.lang };
  if (item.file) {
    const p = path.join(repo, item.file);
    if (!fs.existsSync(p)) throw new SourceError(`snippet file ${item.file} missing`);
    let code = fs.readFileSync(p, 'utf8');
    if (item.region) {
      const m = code.match(new RegExp(`\\[START ${item.region}\\]\\n([\\s\\S]*?)\\n[^\\n]*\\[END ${item.region}\\]`));
      if (!m) throw new SourceError(`region ${item.region} missing in ${item.file}`);
      code = dedent(m[1]);
    }
    return { code: code.replace(/\n$/, ''), lang: item.lang || path.extname(item.file).slice(1) };
  }
  if (item.parts) {
    const parts = item.parts.map((p) => snippetOf({ lang: item.lang, ...p }, repo));
    return { code: parts.map((p) => p.code).join('\n\n'), lang: item.lang || parts[0].lang };
  }
  if (item.ref) {
    const b = codeBlock(repo, item.ref, item.lang, item.n || 1, item.contains);
    let code = b.code;
    if (item.lines) {
      const [a, z] = String(item.lines).split('-').map(Number);
      code = dedent(code.split('\n').slice(a - 1, z || a).join('\n'));
    }
    return { code, lang: item.lang || b.lang };
  }
  throw new SourceError('tab item needs code, file or ref');
}

function tabsHtml(items, { sync = 'platform', label = '代码示例', variant = '' } = {}) {
  const id = nextId('tabs');
  // Every panel gets the same height, so switching tabs never moves the page. Long samples scroll inside.
  const rows = Math.min(Math.max(...items.map((it) => it.code.split('\n').length)), 26);
  const buttons = items.map((it, i) => `<button class="tab" role="tab" type="button" id="${id}-t${i}" aria-controls="${id}-p${i}" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}" data-key="${esc(it.key)}">${esc(it.label)}</button>`).join('');
  const panels = items.map((it, i) => `<div class="tab-panel" role="tabpanel" id="${id}-p${i}" aria-labelledby="${id}-t${i}" data-key="${esc(it.key)}"${i ? ' hidden' : ''}>${codeBlockHtml(it.code, it.lang, { title: it.title })}</div>`).join('');
  return `<div class="tabs code-tabs${variant ? ' ' + variant : ''}" data-sync="${esc(sync)}" style="--rows:${rows}"><div class="tab-list" role="tablist" aria-label="${esc(label)}">${buttons}</div>${panels}</div>`;
}

function tabItems(list, repo) {
  if (!Array.isArray(list)) throw new SourceError('tabs body must be a YAML list');
  return list.map((it) => {
    const s = snippetOf(it, repo);
    return { label: it.label, key: it.key || KEYS[it.label] || it.label, title: it.title, code: s.code, lang: s.lang };
  });
}

const tabsMd = (items) => items.map((it) => `${it.label}${it.title ? '，' + it.title : ''}：\n\n\`\`\`${normalizeLang(it.lang) === 'text' ? '' : it.lang}\n${it.code}\n\`\`\``).join('\n\n');

function resolveHref(ctx, href) { return ctx.resolver.resolveHref(href, ctx.env || {}); }

export function createComponents({ repo, resolver }) {
  const C = {};

  C.tabs = {
    html(content, { env, args }) {
      const body = parseYaml(content, `tabs on ${env.page.id}`);
      const list = Array.isArray(body) ? body : body.items;
      const items = tabItems(list, repo);
      return tabsHtml(items, { sync: (!Array.isArray(body) && body.sync) || 'platform', label: (!Array.isArray(body) && body.label) || '代码示例' });
    },
    md(content) {
      const body = parseYaml(content, 'tabs');
      return tabsMd(tabItems(Array.isArray(body) ? body : body.items, repo));
    },
  };

  // Page-level variants: each panel is a rendered Markdown section, for content that differs by platform.
  C.sections = {
    html(content, { env, render }) {
      const body = parseYaml(content, `sections on ${env.page.id}`);
      const list = Array.isArray(body) ? body : body.items;
      const id = nextId('sec');
      const sync = (!Array.isArray(body) && body.sync) || 'platform';
      const panels = list.map((it) => {
        const key = it.key || KEYS[it.label] || it.label;
        return { key, label: it.label, html: render(panelMarkdown(it), { idPrefix: `${key}-` }) };
      });
      const buttons = panels.map((p, i) => `<button class="tab" role="tab" type="button" id="${id}-t${i}" aria-controls="${id}-p${i}" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}" data-key="${esc(p.key)}">${esc(p.label)}</button>`).join('');
      const bodies = panels.map((p, i) => `<div class="tab-panel section-panel" role="tabpanel" id="${id}-p${i}" aria-labelledby="${id}-t${i}" data-key="${esc(p.key)}"${i ? ' hidden' : ''}>${p.html}</div>`).join('');
      return `<div class="tabs section-tabs" data-sync="${esc(sync)}"><div class="tab-list tab-list-lg" role="tablist" aria-label="平台">${buttons}</div>${bodies}</div>`;
    },
    md(content) {
      const body = parseYaml(content, 'sections');
      const list = Array.isArray(body) ? body : body.items;
      return list.map((it) => `### ${it.label}\n\n${require1(panelMarkdown(it, 1))}`).join('\n\n');
    },
  };

  // One platform panel: either one section body, or several sections each under its own heading.
  function panelMarkdown(it, extraShift = 0) {
    const before = it.before ? it.before + '\n\n' : '';
    if (it.parts) {
      return before + it.parts.map((p) => {
        const ref = typeof p === 'string' ? p : p.ref;
        const { doc, heading } = findSection(repo, ref);
        const shift = 3 - heading.level + extraShift;
        return sectionMarkdown(repo, ref, { body: false, shift, drop: (typeof p === 'object' && p.drop) || [] }).text;
      }).join('\n');
    }
    if (it.ref) return before + sectionMarkdown(repo, it.ref, { body: it.body !== false, shift: (it.shift || 0) + extraShift, drop: it.drop || [] }).text;
    if (it.file) {
      const p = path.join(repo, it.file);
      if (!fs.existsSync(p)) throw new SourceError(`panel file ${it.file} missing`);
      return before + fs.readFileSync(p, 'utf8');
    }
    return before + it.markdown;
  }
  const require1 = (t) => t;

  C.cards = {
    html(content, { env }) {
      const body = parseYaml(content, `cards on ${env.page.id}`);
      const list = Array.isArray(body) ? body : body.items;
      const cols = (!Array.isArray(body) && body.cols) || 3;
      const variant = (!Array.isArray(body) && body.variant) || '';
      const cards = list.map((c) => {
        const href = resolveHref({ resolver, env }, c.href);
        return `<a class="card" href="${esc(href)}">` +
          (c.icon ? `<span class="card-icon">${icon(c.icon)}</span>` : '') +
          `<span class="card-title">${esc(c.title)}</span>` +
          (c.desc ? `<span class="card-desc">${inlineCode(c.desc)}</span>` : '') +
          (c.meta ? `<code class="card-meta">${esc(c.meta)}</code>` : '') +
          `</a>`;
      }).join('');
      return `<div class="cards cols-${cols}${variant ? ' cards-' + variant : ''}">${cards}</div>`;
    },
    md(content) {
      const body = parseYaml(content, 'cards');
      const list = Array.isArray(body) ? body : body.items;
      return list.map((c) => `- [${c.title}](${resolver.absoluteHref(c.href)})${c.desc ? '：' + c.desc : ''}${c.meta ? '，`' + c.meta + '`' : ''}`).join('\n');
    },
  };

  // On API pages the address bar sits in the page header, above the examples on narrow screens too.
  C.endpoint = {
    html(content, { env }) {
      const e = parseYaml(content, 'endpoint');
      const m = String(e.method).toUpperCase();
      const base = e.base || (m === 'WSS' ? 'wss://open.shengzhiai.com' : 'https://open.shengzhiai.com');
      const full = base + e.path;
      const html = `<div class="endpoint"><span class="method method-${METHOD_CLASS[m] || 'get'}">${esc(m)}</span>` +
        `<code class="ep-path"><span class="ep-base">${esc(base)}</span>${esc(e.path)}</code>` +
        `<button class="ep-copy" type="button" data-copy="${esc(full)}" aria-label="复制地址">${icon('content_copy')}</button></div>`;
      if (env.page && env.page.layout === 'api') { env.endpoint = html; return ''; }
      return html;
    },
    md(content) {
      const e = parseYaml(content, 'endpoint');
      const m = String(e.method).toUpperCase();
      const base = e.base || (m === 'WSS' ? 'wss://open.shengzhiai.com' : 'https://open.shengzhiai.com');
      return `\`${m} ${base}${e.path}\``;
    },
  };

  // The right-hand example column of API reference pages. Rendered into env.aside, nothing in the flow.
  C.aside = {
    html(content, { env }) {
      const body = parseYaml(content, `aside on ${env.page.id}`);
      const blocks = body.map((b) => {
        let inner = '';
        if (b.tabs) inner = tabsHtml(tabItems(b.tabs, repo), { sync: b.sync || 'platform', label: b.title, variant: 'dark' });
        else if (b.fixture) inner = fixtureHtml(b, true);
        else if (b.frames) inner = framesHtml(b);
        else if (b.code != null || b.file || b.ref) { const s = snippetOf(b, repo); inner = codeBlockHtml(s.code, s.lang, { title: b.label, extraClass: 'dark' }); }
        return `<section class="aside-block"><h2 class="aside-title">${esc(b.title)}</h2>${inner}</section>`;
      }).join('');
      env.aside = (env.aside || '') + blocks;
      return '';
    },
    md(content) {
      const body = parseYaml(content, 'aside');
      return body.map((b) => {
        let inner = '';
        if (b.tabs) inner = tabsMd(tabItems(b.tabs, repo));
        else if (b.fixture) inner = '```json\n' + fixtureText(b) + '\n```';
        else if (b.frames) inner = framesMd(b);
        else { const s = snippetOf(b, repo); inner = '```' + (s.lang || '') + '\n' + s.code + '\n```'; }
        return `## ${b.title}\n\n${inner}`;
      }).join('\n\n');
    },
  };

  function fixtureText(b) {
    const file = b.fixture || b.file;
    const p = path.join(repo, file);
    if (!fs.existsSync(p)) throw new SourceError(`fixture ${file} missing`);
    let data = JSON.parse(fs.readFileSync(p, 'utf8'));
    if (b.pick) {
      for (const part of String(b.pick).split('.')) {
        const m = part.match(/^([^[]+)(?:\[(\d+)\])?$/);
        data = data?.[m[1]];
        if (m[2] != null) data = data?.[Number(m[2])];
      }
      if (data === undefined) throw new SourceError(`fixture ${file} has no ${b.pick}`);
    }
    if (typeof data === 'string' && /^[[{]/.test(data.trim())) data = JSON.parse(data);
    // Keys starting with an underscore are platform internals; examples never show them.
    data = dropPrivate(data);
    for (const pathStr of b.omit || []) {
      const parts = pathStr.split('.');
      let o = data;
      for (const part of parts.slice(0, -1)) o = o?.[part];
      if (!o || !(parts[parts.length - 1] in o)) throw new SourceError(`fixture ${file} has no ${pathStr} to omit`);
      delete o[parts[parts.length - 1]];
    }
    if (b.limitArrays) data = limitArrays(data, b.limitArrays);
    return JSON.stringify(data, null, 2);
  }

  function fixtureHtml(b, dark) {
    const text = fixtureText(b);
    return codeBlockHtml(text, 'json', { title: b.label || (b.status ? `HTTP ${b.status}` : 'JSON'), extraClass: `${dark ? 'dark ' : ''}scroll` });
  }

  // A repository file shown as one code block: "docs/site/snippets/evaluate.sh" or a YAML body with title.
  C.snippet = {
    html(content) {
      const b = parseYaml(content, 'snippet');
      const item = typeof b === 'string' ? { file: b } : b;
      const sn = snippetOf(item, repo);
      return codeBlockHtml(sn.code, sn.lang, { title: item.title });
    },
    md(content) {
      const b = parseYaml(content, 'snippet');
      const item = typeof b === 'string' ? { file: b } : b;
      const sn = snippetOf(item, repo);
      return '```' + (sn.lang === 'sh' ? 'bash' : sn.lang) + '\n' + sn.code + '\n```';
    },
  };

  C.fixture = {
    html(content) { return fixtureHtml(parseYaml(content, 'fixture'), false); },
    md(content) { return '```json\n' + fixtureText(parseYaml(content, 'fixture')) + '\n```'; },
  };

  // A recorded WebSocket session: direction, time and frame for every message.
  const handshakeLine = (h) => `GET ${h.path}?${h.query.map((k) => `${k}=…`).join('&')}`;
  function loadFrames(b) {
    const p = path.join(repo, b.frames);
    if (!fs.existsSync(p)) throw new SourceError(`frames ${b.frames} missing`);
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  }
  function framesHtml(b) {
    const rec = loadFrames(b);
    const rows = rec.frames.map((f) => {
      const dir = f.dir === 'out' ? 'out' : 'in';
      const body = f.handshake ? `<code>${esc(handshakeLine(f.frame))}</code>` : f.binary ? `<span class="frame-bin">${esc(f.binary)}</span>` : `<code>${esc(JSON.stringify(f.frame))}</code>`;
      return `<li class="frame frame-${dir}"><span class="frame-at">${esc(String(f.at))} ms</span><span class="frame-dir">${dir === 'out' ? '客户端' : '服务端'}</span><span class="frame-body">${body}</span></li>`;
    }).join('');
    return `<div class="frames${b.dark === false ? '' : ' dark'}"><div class="code-head"><span class="code-title">${esc(b.label || rec.title || '帧序列')}</span></div><ol class="frame-list">${rows}</ol></div>`;
  }
  function framesMd(b) {
    const rec = loadFrames(b);
    return '```text\n' + rec.frames.map((f) => `${String(f.at).padStart(6)} ms ${f.dir === 'out' ? '客户端' : '服务端'} ${f.handshake ? handshakeLine(f.frame) : f.binary ? f.binary : JSON.stringify(f.frame)}`).join('\n') + '\n```';
  }
  C.frames = {
    html(content) { return framesHtml(parseYaml(content, 'frames')); },
    md(content) { return framesMd(parseYaml(content, 'frames')); },
  };

  C.diagram = {
    html(content, { args }) { return diagram(args.trim(), parseYaml(content || '{}', 'diagram')).html; },
    md(content, args) { return diagram(args.trim(), parseYaml(content || '{}', 'diagram')).md; },
  };

  // Endpoint index for the API overview: method chip, path, name, page link.
  C.apiindex = {
    html(content, { env }) {
      const list = parseYaml(content, 'apiindex');
      const rows = list.map((e) => {
        const m = String(e.method).toUpperCase();
        return `<a class="api-row" href="${esc(resolveHref({ resolver, env }, e.href))}"><span class="method method-${METHOD_CLASS[m] || 'get'}">${esc(m)}</span><code class="api-path">${esc(e.path)}</code><span class="api-name">${esc(e.title)}</span></a>`;
      }).join('');
      return `<div class="api-index">${rows}</div>`;
    },
    md(content) {
      const list = parseYaml(content, 'apiindex');
      return '| 方法 | 路径 | 接口 |\n|---|---|---|\n' + list.map((e) => `| ${e.method} | \`${e.path}\` | [${e.title}](${resolver.absoluteHref(e.href)}) |`).join('\n');
    },
  };

  // Landing hero: title, lead, actions and key facts on the left, a code sample with platform tabs on the right.
  C.hero = {
    html(content, { env }) {
      const h = parseYaml(content, 'hero');
      const actions = (h.actions || []).map((a) => `<a class="btn ${a.primary ? 'btn-primary' : 'btn-secondary'}" href="${esc(resolveHref({ resolver, env }, a.href))}">${esc(a.label)}${a.primary ? icon('arrow_forward') : ''}</a>`).join('');
      const facts = (h.facts || []).map((f) => `<div class="hero-fact"><div class="hero-fact-k">${esc(f.k)}</div><div class="hero-fact-v">${esc(f.v)}</div></div>`).join('');
      const code = h.code ? tabsHtml(tabItems(h.code, repo), { sync: 'platform', label: '代码示例' }) : '';
      return `<section class="hero"><div class="hero-copy">` +
        (h.eyebrow ? `<div class="hero-eyebrow"><span>${esc(h.eyebrow)}</span>${h.version ? `<code>${esc(h.version)}</code>` : ''}</div>` : '') +
        `<h1>${esc(h.title)}</h1><p class="hero-lead">${esc(h.lead)}</p><div class="hero-actions">${actions}</div>` +
        (facts ? `<div class="hero-facts">${facts}</div>` : '') +
        `</div><div class="hero-code">${code}</div></section>`;
    },
    md(content) {
      const h = parseYaml(content, 'hero');
      return `${h.lead}\n\n` + (h.actions || []).map((a) => `- [${a.label}](${resolver.absoluteHref(a.href)})`).join('\n') +
        (h.code ? '\n\n' + tabsMd(tabItems(h.code, repo)) : '');
    },
  };

  C.facts = {
    html(content, { env }) {
      const list = parseYaml(content, 'facts');
      const items = list.map((f) => `<div class="fact"><div class="fact-k">${f.icon ? icon(f.icon) : ''}<span>${esc(f.k)}</span></div><div class="fact-v">${inlineCode(f.v)}</div>` +
        (f.href ? `<a href="${esc(resolveHref({ resolver, env }, f.href))}">${esc(f.link || '详情')}${icon('chevron_right')}</a>` : '') + `</div>`).join('');
      return `<div class="facts-grid">${items}</div>`;
    },
    md(content) {
      const list = parseYaml(content, 'facts');
      return list.map((f) => `- ${f.k}：${f.v}${f.href ? `见 [${f.link || f.k}](${resolver.absoluteHref(f.href)})。` : ''}`).join('\n');
    },
  };

  // Code lookup box for the error code tables.
  C.lookup = {
    html(content) {
      const b = parseYaml(content || '{}', 'lookup');
      return `<div class="lookup"><label class="lookup-label" for="code-lookup">${esc(b.label || '错误码')}</label>` +
        `<div class="lookup-field">${icon('search')}<input id="code-lookup" class="lookup-input" type="text" inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="${esc(b.placeholder || '40001')}"></div>` +
        `<span class="lookup-status" aria-live="polite"></span></div>`;
    },
    md() { return ''; },
  };

  return C;
}

// Plain text with `code` spans, for short component strings.
const inlineCode = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');

function dedent(code) {
  const lines = code.split('\n');
  const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length);
  const cut = indents.length ? Math.min(...indents) : 0;
  return lines.map((l) => l.slice(cut)).join('\n');
}

function dropPrivate(v) {
  if (Array.isArray(v)) return v.map(dropPrivate);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).filter(([k]) => !k.startsWith('_')).map(([k, x]) => [k, dropPrivate(x)]));
  return v;
}

function limitArrays(v, n) {
  if (Array.isArray(v)) return v.slice(0, n).map((x) => limitArrays(x, n));
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, limitArrays(x, n)]));
  return v;
}
