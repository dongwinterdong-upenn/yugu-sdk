// Link and anchor check over the built site. Every internal href, src and redirect target must name a file
// that exists, and every #fragment an id on that page.
import fs from 'node:fs';
import path from 'node:path';

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

export function checkSite(outDir, base) {
  const files = walk(outDir);
  const html = files.filter((f) => f.endsWith('.html'));
  const ids = new Map();
  for (const f of html) {
    const text = fs.readFileSync(f, 'utf8');
    ids.set(f, new Set([...text.matchAll(/\sid="([^"]+)"/g)].map((m) => decodeEntities(m[1]))));
  }
  const problems = [];
  const fileFor = (urlPath) => {
    if (!urlPath.startsWith(base)) return null;
    let rel = decodeURIComponent(urlPath.slice(base.length));
    if (rel === '' || rel.endsWith('/')) rel += 'index.html';
    const p = path.join(outDir, rel);
    if (fs.existsSync(p) && fs.statSync(p).isFile()) return p;
    const asDir = path.join(outDir, rel, 'index.html');
    return fs.existsSync(asDir) ? asDir : undefined;
  };
  for (const f of html) {
    const text = fs.readFileSync(f, 'utf8');
    const refs = [...text.matchAll(/\s(?:href|src)="([^"]+)"/g)].map((m) => decodeEntities(m[1]));
    const redirect = text.match(/var m=(\{.*?\});var h=/);
    if (redirect) refs.push(...Object.values(JSON.parse(redirect[1])));
    for (const ref of refs) {
      if (/^(https?:|mailto:|data:|javascript:)/.test(ref)) continue;
      const [p, frag] = ref.split('#');
      let target;
      if (!p) target = f;
      else if (p.startsWith(base)) {
        target = fileFor(p);
        if (!target) { problems.push(`${path.relative(outDir, f)}: missing ${ref}`); continue; }
      } else if (p.startsWith('/')) continue; // other parts of the platform site
      else { problems.push(`${path.relative(outDir, f)}: relative link ${ref}`); continue; }
      if (frag && target.endsWith('.html') && !ids.get(target)?.has(decodeURIComponent(frag))) {
        problems.push(`${path.relative(outDir, f)}: missing anchor ${ref}`);
      }
    }
  }
  return problems;
}

function decodeEntities(s) {
  return s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
