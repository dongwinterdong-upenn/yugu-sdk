// Small HTML helpers shared by the layout and the components.
import { ICONS } from './icons.mjs';

export function icon(name, cls = 'i') {
  const d = ICONS[name];
  if (!d) throw new Error(`unknown icon ${name}`);
  return `<svg class="${cls}" viewBox="0 -960 960 960" aria-hidden="true" focusable="false"><path d="${d}"/></svg>`;
}

export const METHOD_CLASS = { GET: 'get', POST: 'post', WSS: 'wss', PUT: 'put', DELETE: 'delete' };
