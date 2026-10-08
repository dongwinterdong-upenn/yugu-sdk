// Build-time syntax highlighting with shiki. Every token carries both theme colours as CSS variables,
// the stylesheet picks one per theme, so a page needs no highlighting script.
import { createHighlighter } from 'shiki';

const LANGS = ['java', 'kotlin', 'swift', 'objective-c', 'c', 'typescript', 'javascript', 'json', 'jsonc', 'bash', 'xml', 'html',
  'groovy', 'ini', 'ruby', 'yaml', 'properties', 'diff', 'http', 'python'];

const ALIASES = {
  objc: 'objective-c', 'obj-c': 'objective-c', m: 'objective-c', h: 'objective-c', ts: 'typescript', js: 'javascript', mjs: 'javascript',
  sh: 'bash', shell: 'bash', console: 'bash', zsh: 'bash', gradle: 'groovy', kts: 'kotlin', kt: 'kotlin', yml: 'yaml', curl: 'bash',
};

// Labels shown in the code block header.
const LABELS = {
  java: 'Java', kotlin: 'Kotlin', swift: 'Swift', 'objective-c': 'Objective-C', c: 'C', typescript: 'TypeScript', javascript: 'JavaScript',
  json: 'JSON', jsonc: 'JSON', bash: 'Shell', xml: 'XML', html: 'HTML', groovy: 'Groovy', ini: 'INI', ruby: 'Ruby', yaml: 'YAML',
  properties: 'Properties', diff: 'Diff', http: 'HTTP', python: 'Python', text: '文本',
};

let highlighter;

export async function initHighlighter() {
  highlighter = await createHighlighter({ themes: ['github-light', 'github-dark-dimmed'], langs: LANGS });
}

export function normalizeLang(lang) {
  const l = (lang || '').trim().toLowerCase();
  if (!l) return 'text';
  if (ALIASES[l]) return ALIASES[l];
  return LANGS.includes(l) ? l : 'text';
}

export function langLabel(lang) {
  return LABELS[normalizeLang(lang)] || LABELS.text;
}

const escapeHtml = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// Returns the inner HTML of <code>: one <span class="line"> per source line, tokens coloured through
// --shiki-light and --shiki-dark custom properties.
export function highlightLines(code, lang) {
  const l = normalizeLang(lang);
  const src = code.replace(/\n$/, '');
  if (l === 'text') {
    return src.split('\n').map((line) => `<span class="line">${escapeHtml(line)}</span>`).join('\n');
  }
  const tokens = highlighter.codeToTokens(src, { lang: l, themes: { light: 'github-light', dark: 'github-dark-dimmed' }, defaultColor: false });
  return tokens.tokens.map((line) => {
    const inner = line.map((t) => {
      const style = Object.entries(t.htmlStyle || {}).map(([k, v]) => `${k}:${v}`).join(';');
      return style ? `<span style="${style}">${escapeHtml(t.content)}</span>` : escapeHtml(t.content);
    }).join('');
    return `<span class="line">${inner}</span>`;
  }).join('\n');
}
