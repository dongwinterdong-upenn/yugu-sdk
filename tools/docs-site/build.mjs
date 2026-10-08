#!/usr/bin/env node
// Builds the SDK documentation site.
//
//   node tools/docs-site/build.mjs --repo . --out <dir>
//
// Reads docs/site/site.config.mjs, the pages under docs/site/pages and the repository documents they name,
// writes static HTML, a Markdown copy of every page, the search index, llms.txt, sitemap.xml, redirects for
// the first site's *.md.html addresses and the raw spec files. Broken internal links or anchors stop the build.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { initHighlighter } from './lib/highlight.mjs';
import { createRenderer, esc } from './lib/render.mjs';
import { createComponents } from './lib/components.mjs';
import { createResolver } from './lib/resolver.mjs';
import { renderPage, renderRedirect } from './lib/layout.mjs';
import { loadDoc, sectionMarkdown, rebaseLinks, slugify, SourceError } from './lib/sources.mjs';
import { checkSite } from './lib/check.mjs';
import { buildSearchIndex } from './lib/search.mjs';
import * as yaml from 'js-yaml';

const HERE = path.dirname(fileURLToPath(import.meta.url));

function args() {
  const a = process.argv.slice(2);
  const get = (k, d) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : d; };
  return { repo: path.resolve(get('--repo', path.join(HERE, '../..'))), out: path.resolve(get('--out', path.join(HERE, '../../ci-out/docs-site'))), copyDump: get('--copy-dump', null) };
}

const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex').slice(0, 10);

function writeFile(p, data) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
}

// ```include fences are replaced by the named section before rendering. lineOrigin[i] is the repository
// file that line i came from, or null for the page's own text.
// Site-only wording for included repository text, see `rewrites` in site.config.mjs. Contract headings drop
// their section numbers on the site; the numbered text stays registered as an alias for old anchors.
const rewriteUse = new Map();

function siteWording(text, file, rewrites, aliases) {
  let out = text;
  for (const r of rewrites.filter((x) => x.file === file)) {
    if (out.includes(r.from)) {
      out = out.split(r.from).join(r.to);
      rewriteUse.set(r, true);
    }
  }
  if (file === 'CONTRACT.md') {
    let fence = false;
    out = out.split('\n').map((line) => {
      if (/^```/.test(line)) fence = !fence;
      if (fence) return line;
      const m = line.match(/^(#{1,6}\s+)(\d+(?:\.\d+)*)\s+(.+)$/);
      if (!m) return line;
      aliases.push({ file, text: headingPlain(m[3]), original: headingPlain(`${m[2]} ${m[3]}`) });
      return m[1] + m[3];
    }).join('\n');
  }
  return out;
}

const headingPlain = (raw) => raw.replace(/`([^`]*)`/g, '$1').trim();

function expandIncludes(text, repo, where, rewrites = []) {
  const lines = text.split('\n');
  const out = [];
  const origin = [];
  const sections = [];
  const aliases = [];
  for (let i = 0; i < lines.length; i++) {
    const open = lines[i].match(/^```include\s*$/);
    if (!open) { out.push(lines[i]); origin.push(null); continue; }
    let j = i + 1;
    const body = [];
    while (j < lines.length && !/^```\s*$/.test(lines[j])) body.push(lines[j++]);
    if (j >= lines.length) throw new SourceError(`${where}: unterminated include`);
    let spec = yaml.load(body.join('\n'));
    if (typeof spec === 'string') spec = { ref: spec };
    const sec = sectionMarkdown(repo, spec.ref, { body: spec.body !== false, shift: spec.shift || 0, drop: spec.drop || [] });
    let incLines = siteWording(sec.text, sec.file, rewrites, aliases).replace(/\n$/, '').split('\n');
    // from and until cut a section body at a paragraph that starts with the given text.
    if (spec.from) {
      const k = incLines.findIndex((l) => l.startsWith(spec.from));
      if (k < 0) throw new SourceError(`${where}: "${spec.from}" not found in ${spec.ref}`);
      incLines = incLines.slice(k);
    }
    if (spec.until) {
      const k = incLines.findIndex((l) => l.startsWith(spec.until));
      if (k < 0) throw new SourceError(`${where}: "${spec.until}" not found in ${spec.ref}`);
      incLines = incLines.slice(0, k);
    }
    while (incLines.length && !incLines[incLines.length - 1].trim()) incLines.pop();
    for (const l of incLines) { out.push(l); origin.push(spec.canonical === false ? null : sec.file); }
    // A section included without its heading is represented by the whole page.
    if (spec.body !== false && spec.canonical !== false && sec.heading) sections.push({ file: sec.file, text: sec.heading.text });
    i = j;
  }
  return { text: out.join('\n') + '\n', origin, sections, aliases };
}

// A page whose text is a whole repository document: the H1 becomes the page title, the first paragraph
// the lead, the rest the body.
function sourcePage(repo, rel) {
  const doc = loadDoc(repo, rel);
  const lines = doc.lines.slice();
  let i = lines.findIndex((l) => /^# /.test(l));
  if (i >= 0) lines.splice(i, 1); else i = 0;
  while (i < lines.length && !lines[i].trim()) i++;
  let lead = null;
  if (i < lines.length && !/^(\||#|```|-|>|\d+\.|\s)/.test(lines[i])) {
    const start = i;
    while (i < lines.length && lines[i].trim()) i++;
    lead = lines.slice(start, i).join('');
    lines.splice(start, i - start);
  }
  const body = rebaseLinks(lines.join('\n').replace(/^\n+/, ''), rel);
  return { text: body, origin: body.split('\n').map(() => rel), lead };
}

function gitDate(repo, files) {
  try {
    const out = execFileSync('git', ['-C', repo, 'log', '-1', '--format=%cs', '--', ...files], { encoding: 'utf8' }).trim();
    if (out) return out;
  } catch { /* not a git checkout */ }
  return null;
}

function leadToPlain(md) {
  return md.replace(/`([^`]*)`/g, '$1').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\*\*/g, '');
}

async function main() {
  const { repo, out, copyDump } = args();
  const site = (await import(pathToFileURL(path.join(repo, 'docs/site/site.config.mjs')).href)).default;
  const siteDir = path.join(repo, 'docs/site');
  const pages = {};
  for (const [id, p] of Object.entries(site.pages)) pages[id] = { id, ...p };
  for (const [tab, groups] of Object.entries(site.nav)) {
    for (const g of groups) for (const id of g.items) {
      if (!pages[id]) throw new Error(`nav ${tab} names unknown page ${id}`);
      for (const c of pages[id].children || []) if (!pages[c]) throw new Error(`page ${id} child ${c} unknown`);
    }
  }
  // Reading order inside each tab, for the pager.
  const order = {};
  for (const [tab, groups] of Object.entries(site.nav)) {
    order[tab] = [];
    for (const g of groups) for (const id of g.items) { order[tab].push(id); for (const c of pages[id].children || []) order[tab].push(c); }
  }

  await initHighlighter();
  const resolver = createResolver(site, pages);
  const components = createComponents({ repo, resolver });
  const md = createRenderer({ components, resolver });

  // Pass 1: compose every page and register where each repository heading ended up.
  const built = [];
  for (const page of Object.values(pages)) {
    if (page.external) continue;
    let composed;
    if (page.source) {
      composed = sourcePage(repo, page.source);
      if (!page.description && composed.lead) { page.description = leadToPlain(composed.lead); page.leadMd = composed.lead; }
      page.files = [page.source];
    } else {
      const file = path.join(siteDir, page.file);
      const raw = fs.readFileSync(file, 'utf8');
      composed = expandIncludes(raw, repo, page.file, site.rewrites || []);
      const included = [...new Set(composed.origin.filter(Boolean))];
      page.files = [path.posix.join('docs/site', page.file), ...included];
    }
    const env = { page, usedIds: new Map(), headings: [] };
    env.onHeading = (h) => env.headings.push(h);
    md.parse(composed.text, env);
    for (const h of env.headings) {
      const from = h.line != null ? composed.origin[h.line] : null;
      if (!from) continue;
      resolver.registerAnchor(from, h.text, page.id, h.id);
      for (const a of composed.aliases || []) {
        if (a.file === from && a.text === h.text) resolver.registerAnchor(from, a.original, page.id, h.id);
      }
    }
    for (const sec of composed.sections || []) resolver.registerAnchor(sec.file, sec.text, page.id, null);
    built.push({ page, composed });
  }
  // Repository files that are a page's whole source answer for their own headings first.
  for (const { page, composed } of built) {
    if (!page.source) continue;
    const env = { page, usedIds: new Map(), headings: [] };
    env.onHeading = (h) => env.headings.push(h);
    md.parse(composed.text, env);
    for (const h of env.headings) resolver.anchors.set(`${page.source}#${slugify(h.text)}`, { page: page.id, id: h.id });
  }

  // Assets with content-hashed names: the front proxy caches js, css, fonts and images for a year.
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(out, { recursive: true });
  const A = path.join(HERE, 'assets');
  const assetOut = (name, data) => {
    const ext = path.extname(name);
    const hashed = `${path.basename(name, ext)}.${sha(data)}${ext}`;
    writeFile(path.join(out, 'assets', hashed), data);
    return site.base + 'assets/' + hashed;
  };
  const fontDir = (pkg) => path.join(HERE, 'node_modules', '@fontsource-variable', pkg, 'files');
  const fontSans = assetOut('inter-latin-wght-normal.woff2', fs.readFileSync(path.join(fontDir('inter'), 'inter-latin-wght-normal.woff2')));
  const fontMono = assetOut('jetbrains-mono-latin-wght-normal.woff2', fs.readFileSync(path.join(fontDir('jetbrains-mono'), 'jetbrains-mono-latin-wght-normal.woff2')));
  const css = fs.readFileSync(path.join(A, 'site.css'), 'utf8').replace('__FONT_SANS__', fontSans).replace('__FONT_MONO__', fontMono);
  const favicon = assetOut('favicon.svg', fs.readFileSync(path.join(A, 'favicon.svg')));

  // Pass 2: render.
  const searchDocs = [];
  const mdCopies = [];
  const copyLines = [];
  const rendered = [];
  for (const { page, composed } of built) {
    // The lead keeps inline code; the meta description and llms.txt use the plain text.
    if (page.leadMd) page.leadHtml = md.renderInline(rebaseLinks(page.leadMd, page.source), { page, usedIds: new Map() });
    else if (page.description) {
      page.leadHtml = md.renderInline(page.description, { page, usedIds: new Map() });
      page.description = leadToPlain(page.description);
    }
    const env = { page, usedIds: new Map(), toc: [], aside: '' };
    const body = md.render(composed.text, env);
    const updated = gitDate(repo, page.files) || new Date().toISOString().slice(0, 10);
    const mdText = toMarkdown(md, components, resolver, page, composed.text, site);
    rendered.push({ page, env, body, updated, mdText });
  }
  const searchJson = JSON.stringify(buildSearchIndex(rendered.map((r) => ({ page: r.page, html: r.body, url: resolver.hrefOf(r.page.id) }))));
  const searchUrl = assetOut('search-index.json', searchJson);
  const jsUrl = assetOut('site.js', fs.readFileSync(path.join(A, 'site.js')));
  const cssUrl = assetOut('site.css', css);
  const assets = { css: cssUrl, js: jsUrl, favicon, fontSans, search: searchUrl };

  for (const r of rendered) {
    const { page } = r;
    const list = order[page.tab] || [];
    const idx = list.indexOf(page.id);
    const nav = (k) => { let j = idx + k; while (list[j] && pages[list[j]].external) j += k; return list[j] ? pages[list[j]] : null; };
    const mdHref = site.base + page.url + 'index.md';
    const html = renderPage({ site, page, pages, hrefOf: resolver.hrefOf, body: r.body, toc: r.env.toc, aside: r.env.aside, endpoint: r.env.endpoint,
      prev: idx >= 0 ? nav(-1) : null, next: idx >= 0 ? nav(1) : null, assets, updated: r.updated, mdHref });
    writeFile(path.join(out, page.url, 'index.html'), html);
    writeFile(path.join(out, page.url, 'index.md'), r.mdText);
    mdCopies.push({ page, text: r.mdText });
    copyLines.push(`## ${page.title}`, '', page.navTitle && page.navTitle !== page.title ? `导航名：${page.navTitle}` : '', page.description || '', '');
  }

  // Raw files at their repository paths.
  for (const rel of site.raw) {
    const src = path.join(repo, rel);
    if (!fs.existsSync(src)) throw new Error(`raw file ${rel} missing`);
    writeFile(path.join(out, rel), fs.readFileSync(src));
  }

  // Redirects for the first site's addresses.
  const legacyAnchors = JSON.parse(fs.readFileSync(path.join(siteDir, 'legacy-anchors.json'), 'utf8'));
  for (const [oldPath, file] of Object.entries(site.legacy)) {
    const target = resolver.hrefOf(site.fileHome[file]);
    const map = {};
    for (const h of legacyAnchors[oldPath] || []) {
      if (h.level === 1) { map[h.id] = target; continue; }
      const hit = resolver.anchors.get(`${file}#${slugify(h.text)}`);
      map[h.id] = hit ? resolver.hrefOf(hit.page) + (hit.id ? '#' + hit.id : '') : (LEGACY_FALLBACK[file]?.[h.text] ? resolver.hrefOf(LEGACY_FALLBACK[file][h.text]) : target);
    }
    writeFile(path.join(out, oldPath), renderRedirect({ site, target, map, title: pages[site.fileHome[file]].title }));
  }

  // llms.txt, llms-full.txt and sitemap.xml.
  const tabOf = (id) => site.tabs.find((t) => t.id === id);
  const llms = [`# ${site.name}`, '', `> ${site.description}`, ''];
  for (const tab of site.tabs) {
    llms.push(`## ${tab.label}`, '');
    for (const id of order[tab.id]) {
      const p = pages[id];
      if (p.external) continue;
      llms.push(`- [${p.title}](${site.origin}${site.base}${p.url}index.md)${p.description ? '：' + p.description : ''}`);
    }
    llms.push('');
  }
  writeFile(path.join(out, 'llms.txt'), llms.join('\n'));
  writeFile(path.join(out, 'llms-full.txt'), mdCopies.map((c) => c.text).join('\n\n---\n\n'));
  const urls = rendered.map((r) => `  <url><loc>${esc(site.origin + resolver.hrefOf(r.page.id))}</loc><lastmod>${r.updated}</lastmod></url>`);
  writeFile(path.join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join('\n')}\n</urlset>\n`);

  const unused = (site.rewrites || []).filter((r) => !rewriteUse.has(r));
  if (unused.length) throw new Error(`rewrites no longer match their source: ${unused.map((r) => r.from).join(' | ')}`);
  if (resolver.broken.length) {
    for (const b of resolver.broken) console.error(`unresolved link ${b.href} on page ${b.page}`);
    throw new Error(`${resolver.broken.length} unresolved links`);
  }
  const problems = checkSite(out, site.base);
  if (problems.length) {
    for (const p of problems) console.error(p);
    throw new Error(`${problems.length} broken links or anchors in the output`);
  }
  if (copyDump) writeFile(copyDump, copyLines.filter((l, i, a) => !(l === '' && a[i - 1] === '')).join('\n'));
  void tabOf;
  console.log(`built ${rendered.length} pages, ${Object.keys(site.legacy).length} redirects into ${out}`);
}

// Headings of the first site that no new page carries, mapped to the page that covers the topic.
const LEGACY_FALLBACK = {
  'CONTRACT.md': { '11 沙箱': 'sandbox', '12 版本与变更': 'compatibility', '8 错误码与警告码': 'api-errors', '3 整段评测': 'api-evaluate',
    '4 语音合成与报告': 'api-tts', '4.1 语音合成': 'api-tts', '5 实时评测': 'api-ws-evaluate' },
};

// The Markdown copy of a page: components replaced by their Markdown form, links made absolute.
function toMarkdown(md, components, resolver, page, text, site) {
  const tokens = md.parse(text, { page, usedIds: new Map() });
  const lines = text.split('\n');
  const repl = [];
  for (const t of tokens) {
    if (t.type !== 'fence') continue;
    const [name, ...rest] = t.info.trim().split(/\s+/);
    if (!components[name]) continue;
    repl.push({ from: t.map[0], to: t.map[1], text: components[name].md(t.content, rest.join(' ')) });
  }
  for (const r of repl.reverse()) lines.splice(r.from, r.to - r.from, ...(r.text ? r.text.split('\n') : []));
  let body = lines.join('\n').replace(/\n{3,}/g, '\n\n');
  body = body.replace(/\[([^\]]*)\]\(([^)\s]+)\)/g, (m, label, href) => {
    const abs = resolver.absoluteHref(href);
    return abs === null ? label : `[${label}](${abs})`;
  });
  const head = `# ${page.title}\n\n${page.description ? page.description + '\n\n' : ''}来源：${site.origin}${site.base}${page.url}\n\n`;
  return head + body.trim() + '\n';
}

main().catch((e) => { console.error(e.stack || String(e)); process.exit(1); });
