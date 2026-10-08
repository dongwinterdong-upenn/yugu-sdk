// Turns the link forms used in sources into site addresses:
//   page:<id>[#anchor]       a site page
//   repo:<path>[#anchor]     a repository file, from included or source documents
//   CONTRACT.md#x, ../X.md   relative links in repository documents, rebased to repo: before rendering
import path from 'node:path';
import { slugify } from './sources.mjs';

export function createResolver(site, pages) {
  const anchors = new Map();   // "<file>#<slug>" -> { page, id }
  const rawSet = new Set(site.raw);
  const broken = [];

  const hrefOf = (id) => {
    const p = pages[id];
    if (!p) throw new Error(`unknown page ${id}`);
    return p.external ? p.external : site.base + p.url;
  };

  function repoTarget(file, anchor) {
    const norm = path.posix.normalize(file).replace(/^\.\//, '');
    if (anchor) {
      const hit = anchors.get(`${norm}#${slugify(decodeURIComponent(anchor))}`) || anchors.get(`${norm}#${decodeURIComponent(anchor)}`);
      if (hit) return hrefOf(hit.page) + (hit.id ? '#' + hit.id : '');
    }
    const home = site.fileHome[norm];
    if (home) return hrefOf(home) + (anchor ? '#' + anchor : '');
    if (rawSet.has(norm)) return site.base + norm;
    return null;
  }

  function resolveHref(href, env = {}) {
    if (!href) return href;
    if (href.startsWith('page:')) {
      const [id, anchor] = href.slice(5).split('#');
      if (!pages[id]) { broken.push({ page: env.page?.id, href }); return href; }
      return hrefOf(id) + (anchor ? '#' + anchor : '');
    }
    if (href.startsWith('repo:')) {
      const [file, anchor] = href.slice(5).split('#');
      const t = repoTarget(file, anchor);
      if (t) return t;
      // Source folders such as demos/ have no page: the link text stays, the link goes.
      if ((site.unlinkPrefixes || []).some((p) => file.startsWith(p))) return null;
      broken.push({ page: env.page?.id, href });
      return href;
    }
    if (/^[^:/#]+\.(md|yaml|json)(#.*)?$/.test(href) || href.startsWith('../') || href.startsWith('./')) {
      // A relative link written in a site page: relative to the repository root.
      const [file, anchor] = href.split('#');
      const t = repoTarget(file, anchor);
      if (!t) { broken.push({ page: env.page?.id, href }); return href; }
      return t;
    }
    return href;
  }

  // Inline code that names a repository document, for example `ERRORS.md` or `spec/openapi.yaml`.
  function codeLink(content) {
    const c = content.trim();
    if (site.fileHome[c]) return hrefOf(site.fileHome[c]);
    if (rawSet.has(c) && !c.endsWith('.md')) return site.base + c;
    return null;
  }

  const absoluteHref = (href) => {
    const r = resolveHref(href);
    return r && r.startsWith('/') ? site.origin + r : r;
  };

  return {
    siteOrigin: site.origin,
    hrefOf, resolveHref, codeLink, absoluteHref, repoTarget,
    registerAnchor(file, text, page, id) {
      const key = `${file}#${slugify(text)}`;
      if (!anchors.has(key)) anchors.set(key, { page, id });
    },
    anchors, broken,
  };
}
