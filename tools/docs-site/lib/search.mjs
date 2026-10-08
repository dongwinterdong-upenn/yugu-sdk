// Search index: one entry per page section, text without code blocks. Matching happens in the browser by
// substring, which works for Chinese without a word segmenter.
const strip = (html) => html
  .replace(/<pre[\s\S]*?<\/pre>/g, ' ')
  .replace(/<svg[\s\S]*?<\/svg>/g, ' ')
  .replace(/<button[\s\S]*?<\/button>/g, ' ')
  .replace(/<a class="hash"[^>]*>#<\/a>/g, '')
  .replace(/<\/?(p|li|td|th|tr|h[1-6]|div|br|ol|ul|table|section|figure)\b[^>]*>/g, ' ')
  .replace(/<[^>]+>/g, '')
  .replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&')
  .replace(/\s+/g, ' ').trim();

export function buildSearchIndex(pages) {
  const entries = [];
  for (const { page, html, url } of pages) {
    const parts = html.split(/(?=<h[23] id=")/);
    parts.forEach((part, i) => {
      const m = part.match(/^<h([23]) id="([^"]+)">([\s\S]*?)<\/h\1>/);
      const heading = m ? strip(m[3]) : '';
      const text = strip(m ? part.slice(m[0].length) : part).slice(0, 1600);
      if (!heading && !text && i > 0) return;
      entries.push({ p: page.title, u: url, h: heading, a: m ? m[2] : '', t: i === 0 && page.description ? `${page.description} ${text}`.slice(0, 1600) : text, g: page.group || '' });
    });
  }
  return entries;
}
