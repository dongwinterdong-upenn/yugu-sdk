// Page shell: top bar with the section tabs, left navigation, page header, outline, pager and footer.
import { esc } from './render.mjs';
import { icon } from './ui.mjs';

export function themeBootScript() {
  // Runs before first paint so a stored theme never flashes the other one.
  return `<script>(function(){try{var t=localStorage.getItem('yugu-docs-theme');if(t==='light'||t==='dark')document.documentElement.setAttribute('data-theme',t)}catch(e){}})()</script>`;
}

function brand(site) {
  return `<a class="brand" href="${site.base}" aria-label="${esc(site.name)}"><span class="brand-zh">优谷<em>·</em>雅言</span><span class="brand-sep" aria-hidden="true"></span><span class="brand-doc">SDK 文档</span></a>`;
}

function topbar(site, page, hrefOf) {
  const tabs = site.tabs.map((tab) => `<a class="toptab${tab.id === page.tab ? ' active' : ''}" href="${hrefOf(tab.home)}"${tab.id === page.tab ? ' aria-current="true"' : ''}>${esc(tab.label)}</a>`).join('');
  return `<header class="topbar"><div class="topbar-in">` +
    `<button class="icon-btn nav-open" type="button" aria-label="打开导航" aria-controls="sidebar" aria-expanded="false">${icon('menu')}</button>` +
    brand(site) +
    `<span class="version-chip" title="SDK 版本">${esc(site.version)}</span>` +
    `<nav class="toptabs" aria-label="文档分区">${tabs}</nav>` +
    `<div class="topbar-actions">` +
    `<button class="search-open" type="button" aria-label="搜索文档" aria-keyshortcuts="/ Control+K Meta+K">${icon('search')}<span class="search-open-label">搜索</span><kbd>/</kbd></button>` +
    `<a class="top-link" href="${site.links.platformDocs}">平台接口</a>` +
    `<a class="btn btn-primary btn-sm" href="${site.links.console}">控制台</a>` +
    `<button class="icon-btn theme-toggle" type="button" aria-label="主题">${icon('contrast', 'i theme-system')}${icon('light_mode', 'i theme-light')}${icon('dark_mode', 'i theme-dark')}</button>` +
    `</div></div></header>`;
}

function sidebar(site, page, pages, hrefOf) {
  const groups = site.nav[page.tab] || [];
  const item = (id, depth = 0) => {
    const p = pages[id];
    if (!p) throw new Error(`nav references unknown page ${id}`);
    const active = id === page.id;
    const childIds = p.children || [];
    const open = active || childIds.includes(page.id);
    const ext = p.external ? ` ${icon('north_east', 'i i-ext')}` : '';
    let html = `<li class="nav-item${active ? ' active' : ''}${childIds.length ? ' has-children' : ''}${open ? ' open' : ''}">` +
      `<a class="nav-link" href="${hrefOf(id)}"${active ? ' aria-current="page"' : ''}>${esc(p.navTitle || p.title)}${ext}</a>`;
    if (childIds.length) {
      html += `<ul class="nav-children">${childIds.map((c) => item(c, depth + 1)).join('')}</ul>`;
    }
    return html + '</li>';
  };
  const body = groups.map((g) => `<div class="nav-group">${g.group ? `<div class="nav-group-label">${esc(g.group)}</div>` : ''}<ul class="nav-list">${g.items.map((id) => item(id)).join('')}</ul></div>`).join('');
  const tabs = site.tabs.map((tab) => `<a class="nav-tab${tab.id === page.tab ? ' active' : ''}" href="${hrefOf(tab.home)}">${esc(tab.label)}</a>`).join('');
  return `<nav class="sidebar" id="sidebar" aria-label="文档导航"><div class="sidebar-head">${brand(site)}<button class="icon-btn nav-close" type="button" aria-label="关闭导航">${icon('close')}</button></div><div class="nav-tabs-mobile">${tabs}</div><div class="sidebar-scroll">${body}</div></nav>`;
}

function toc(entries) {
  if (!entries || entries.length < 2) return '<aside class="toc" aria-hidden="true"></aside>';
  const items = entries.map((e) => `<li class="toc-l${e.level}"><a href="#${esc(e.id)}">${e.html || esc(e.text)}</a></li>`).join('');
  return `<aside class="toc"><div class="toc-in"><div class="toc-title">目录</div><ol class="toc-list">${items}</ol></div></aside>`;
}

function crumbs(site, page, pages, hrefOf) {
  const tab = site.tabs.find((t) => t.id === page.tab);
  const parts = [`<a href="${hrefOf(tab.home)}">${esc(tab.label)}</a>`];
  if (page.group) parts.push(`<span>${esc(page.group)}</span>`);
  if (page.parent) parts.push(`<a href="${hrefOf(page.parent)}">${esc(pages[page.parent].navTitle || pages[page.parent].title)}</a>`);
  return `<nav class="crumbs" aria-label="位置">${parts.join(`${icon('chevron_right', 'i crumb-sep')}`)}</nav>`;
}

function pager(prev, next, hrefOf) {
  if (!prev && !next) return '';
  const a = (p, cls, label, ic) => p ? `<a class="pager-link ${cls}" href="${hrefOf(p.id)}"><span class="pager-label">${label}</span><span class="pager-title">${ic === 'l' ? icon('arrow_back') : ''}${esc(p.navTitle || p.title)}${ic === 'r' ? icon('arrow_forward') : ''}</span></a>` : '<span></span>';
  return `<nav class="pager" aria-label="翻页">${a(prev, 'prev', '上一篇', 'l')}${a(next, 'next', '下一篇', 'r')}</nav>`;
}

function footer(site, hrefOf) {
  const col = (title, links) => `<div class="foot-col"><div class="foot-title">${esc(title)}</div><ul>${links.map(([label, href]) => `<li><a href="${href}">${esc(label)}</a></li>`).join('')}</ul></div>`;
  return `<footer class="sitefoot"><div class="sitefoot-in">` +
    `<div class="foot-brand">${brand(site)}<p class="foot-note">SDK 源码，文档与示例代码按 Apache-2.0 许可发布。</p></div>` +
    col('文档', [['快速开始', hrefOf('quickstart')], ['SDK 总览', hrefOf('sdks')], ['接口参考', hrefOf('api')], ['错误码', hrefOf('api-errors')]]) +
    col('资源', [['变更记录', hrefOf('changelog')], ['版本兼容', hrefOf('compatibility')], ['制品与下载', hrefOf('downloads')], ['持续集成', site.links.ci]]) +
    col('平台', [['平台首页', site.links.home], ['控制台', site.links.console], ['平台接口文档', site.links.platformDocs]]) +
    `</div></footer>`;
}

function searchDialog() {
  return `<div class="search-dialog" role="dialog" aria-modal="true" aria-label="搜索文档" hidden><div class="search-backdrop" data-close></div>` +
    `<div class="search-panel"><div class="search-field">${icon('search')}<input class="search-input" type="search" placeholder="搜索文档" autocomplete="off" spellcheck="false" aria-label="搜索文档" aria-controls="search-results"><kbd class="search-esc">Esc</kbd></div>` +
    `<ol class="search-results" id="search-results" role="listbox" aria-label="搜索结果"></ol>` +
    `<div class="search-foot"><span><kbd>↑</kbd><kbd>↓</kbd> 选择</span><span><kbd>Enter</kbd> 打开</span><span><kbd>Esc</kbd> 关闭</span></div></div></div>`;
}

export function renderPage({ site, page, pages, hrefOf, body, toc: tocEntries, aside, endpoint, prev, next, assets, updated, mdHref }) {
  const title = page.id === 'home' ? site.name : `${page.title} | ${site.name}`;
  const canonical = site.origin + hrefOf(page.id);
  const layout = page.layout || 'doc';
  const head = layout === 'landing' ? '' :
    `<header class="page-head">${crumbs(site, page, pages, hrefOf)}<h1>${esc(page.title)}</h1>` +
    (page.description ? `<p class="lead">${page.leadHtml || esc(page.description)}</p>` : '') +
    `<div class="page-actions"><button class="btn btn-ghost btn-sm copy-page" type="button" data-md="${mdHref}">${icon('content_copy')}<span>复制 Markdown</span></button>` +
    `<a class="btn btn-ghost btn-sm" href="${mdHref}" target="_blank" rel="noopener">${icon('markdown')}<span>查看 Markdown</span></a></div>${endpoint || ''}</header>`;
  const main = layout === 'api'
    ? `<div class="api-grid">${head}<div class="api-aside"><div class="api-aside-in">${aside || ''}</div></div><article class="prose api-body">${body}</article></div>`
    : `${head}<article class="prose">${body}</article>`;
  const foot = layout === 'landing' ? '' : `${pager(prev, next, hrefOf)}<div class="page-meta">${icon('schedule')}<span>更新于 ${esc(updated)}</span></div>`;
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(page.description || site.description)}">
<link rel="canonical" href="${esc(canonical)}">
<link rel="alternate" type="text/markdown" href="${mdHref}">
<link rel="icon" type="image/svg+xml" href="${assets.favicon}">
<link rel="preload" href="${assets.fontSans}" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="${assets.css}">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#121214" media="(prefers-color-scheme: dark)">
<meta property="og:title" content="${esc(page.title)}">
<meta property="og:description" content="${esc(page.description || site.description)}">
<meta property="og:type" content="website">
<meta property="og:url" content="${esc(canonical)}">
${themeBootScript()}
<script src="${assets.js}" defer></script>
</head>
<body class="layout-${layout}" data-search="${assets.search}">
<a class="skip-link" href="#main">跳到正文</a>
${topbar(site, page, hrefOf)}
<div class="shell${layout === 'landing' ? ' shell-landing' : ''}">
${sidebar(site, page, pages, hrefOf)}
<div class="scrim" hidden></div>
<main id="main" class="main" tabindex="-1">
${main}
${foot}
</main>
${layout === 'doc' ? toc(tocEntries) : ''}
</div>
${footer(site, hrefOf)}
${searchDialog()}
</body>
</html>
`;
}

export function renderRedirect({ site, target, map, title }) {
  // Old *.md.html pages: jump to the new page, keeping the reader's anchor where one maps.
  const json = JSON.stringify(map).replace(/</g, '\\u003c');
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} | ${esc(site.name)}</title>
<link rel="canonical" href="${esc(site.origin + target)}">
<meta name="robots" content="noindex">
<meta http-equiv="refresh" content="0; url=${esc(target)}">
<script>(function(){var m=${json};var h=decodeURIComponent(location.hash.slice(1));location.replace(h&&m[h]?m[h]:${JSON.stringify(target)});})()</script>
</head><body><p><a href="${esc(target)}">${esc(title)}</a></p></body></html>
`;
}
