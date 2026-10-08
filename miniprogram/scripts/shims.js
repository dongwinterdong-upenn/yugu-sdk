// Injected by scripts/build.mjs. The generated src/error-table.js calls Object.fromEntries (ES2019),
// which older mini program runtimes lack; the build rewrites those calls to this function.
export function __yuguFromEntries(entries) {
  const out = {};
  const list = Array.from(entries);
  for (let i = 0; i < list.length; i++) out[list[i][0]] = list[i][1];
  return out;
}
