// Reads repository Markdown and cuts it into heading sections, so pages can include a section or pick a
// code block from it instead of keeping a second copy.
import fs from 'node:fs';
import path from 'node:path';

export class SourceError extends Error {}

const cache = new Map();

// GitHub-compatible heading slug: lower case, punctuation removed, spaces to hyphens. Chinese is kept,
// so README anchors work the same on the site and in any Git web view.
export function slugify(text) {
  return text.trim().toLowerCase()
    .replace(/<[^>]+>/g, '')
    .replace(/[ -⁯⸀-⹿　-〿＀-／：-＠［-｀｛-･!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~]/g, '')
    .replace(/\s/g, '-');
}

export function headingPlainText(raw) {
  return raw.replace(/`([^`]*)`/g, '$1').replace(/\*\*([^*]*)\*\*/g, '$1').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').trim();
}

export function loadDoc(repo, rel) {
  const key = path.join(repo, rel);
  if (cache.has(key)) return cache.get(key);
  if (!fs.existsSync(key)) throw new SourceError(`missing source ${rel}`);
  const text = fs.readFileSync(key, 'utf8');
  const lines = text.split('\n');
  const headings = [];
  let fence = null;
  lines.forEach((line, i) => {
    const f = line.match(/^(\s*)(```+|~~~+)/);
    if (f) {
      if (!fence) fence = f[2][0];
      else if (f[2][0] === fence) fence = null;
      return;
    }
    if (fence) return;
    const m = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
    if (m) headings.push({ level: m[1].length, raw: m[2], text: headingPlainText(m[2]), line: i });
  });
  headings.forEach((h, idx) => {
    let end = lines.length;
    for (let j = idx + 1; j < headings.length; j++) {
      if (headings[j].level <= h.level) { end = headings[j].line; break; }
    }
    h.end = end;
  });
  const doc = { rel, text, lines, headings };
  cache.set(key, doc);
  return doc;
}

// "CONTRACT.md#6 幂等" or "java/README.md#实时流式评测与断线重连#示例". Each "#" step narrows to a heading
// inside the previous one, matched by plain text.
export function parseRef(ref) {
  const [file, ...parts] = ref.split('#').map((s) => s.trim());
  return { file, parts };
}

export function findSection(repo, ref) {
  const { file, parts } = parseRef(ref);
  const doc = loadDoc(repo, file);
  let start = 0;
  let end = doc.lines.length;
  let heading = null;
  for (const part of parts) {
    const h = doc.headings.find((x) => x.line >= start && x.line < end && x.text === part && (!heading || x.level > heading.level));
    if (!h) throw new SourceError(`heading "${part}" not found in ${file} (ref ${ref})`);
    heading = h;
    start = h.line;
    end = h.end;
  }
  return { doc, heading, start, end };
}

// Markdown text of a section. body=true drops the heading line itself. shift moves every heading level
// by the given amount, so a "###" section can become "##" on its own page.
export function sectionMarkdown(repo, ref, { body = false, shift = 0, drop = [] } = {}) {
  const { doc, heading, start, end } = findSection(repo, ref);
  let lines = doc.lines.slice(body && heading ? start + 1 : start, end);
  const dropped = drop.map((d) => {
    const h = doc.headings.find((x) => x.line >= start && x.line < end && x.text === d);
    if (!h) throw new SourceError(`drop heading "${d}" not found in ${ref}`);
    return h;
  });
  if (dropped.length) {
    const offset = body && heading ? start + 1 : start;
    const keep = lines.map((_, i) => !dropped.some((h) => i + offset >= h.line && i + offset < h.end));
    lines = lines.filter((_, i) => keep[i]);
  }
  let text = lines.join('\n').replace(/^\n+/, '').replace(/\s+$/, '') + '\n';
  if (shift) text = shiftHeadings(text, shift);
  return { text: rebaseLinks(text, doc.rel), file: doc.rel, heading };
}

export function shiftHeadings(text, shift) {
  let fence = null;
  return text.split('\n').map((line) => {
    const f = line.match(/^(\s*)(```+|~~~+)/);
    if (f) { if (!fence) fence = f[2][0]; else if (f[2][0] === fence) fence = null; return line; }
    if (fence) return line;
    const m = line.match(/^(#{1,6})(\s+.*)$/);
    if (!m) return line;
    const level = Math.min(6, Math.max(1, m[1].length + shift));
    return '#'.repeat(level) + m[2];
  }).join('\n');
}

// Relative links inside an included file are rewritten to repository-absolute "repo:" links, so they still
// point at the right document after the text moves to another page.
export function rebaseLinks(text, fromRel) {
  const dir = path.posix.dirname(fromRel);
  return text.replace(/(\]\()([^)\s]+)(\))/g, (m, a, target, b) => {
    if (/^(https?:|mailto:|#|repo:|page:|\/)/.test(target)) return m;
    const [p, anchor] = target.split('#');
    const resolved = path.posix.normalize(path.posix.join(dir, p));
    return `${a}repo:${resolved}${anchor ? '#' + anchor : ''}${b}`;
  });
}

// The n-th fenced code block of a language inside a section, n counted from 1.
export function codeBlock(repo, ref, lang, n = 1, mustContain) {
  const { doc, start, end } = findSection(repo, ref);
  const blocks = [];
  let cur = null;
  for (let i = start; i < end; i++) {
    const line = doc.lines[i];
    const f = line.match(/^(\s*)(```+)\s*([\w+#-]*)/);
    if (f && !cur) { cur = { lang: f[3], lines: [], line: i + 1 }; continue; }
    if (f && cur && line.trim().startsWith(f[2])) { blocks.push(cur); cur = null; continue; }
    if (cur) cur.lines.push(line);
  }
  const matching = blocks.filter((b) => !lang || b.lang === lang);
  const block = matching[n - 1];
  if (!block) throw new SourceError(`code block ${lang}#${n} not found in ${ref}, found ${blocks.map((b) => b.lang || '-').join(',')}`);
  const code = block.lines.join('\n');
  if (mustContain && !code.includes(mustContain)) throw new SourceError(`code block ${lang}#${n} in ${ref} no longer contains "${mustContain}"`);
  return { code, lang: block.lang, file: doc.rel, line: block.line };
}
