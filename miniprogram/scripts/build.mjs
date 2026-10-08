#!/usr/bin/env node
// Bundles src/ (ES modules, including the generated src/error-table.js) into one CommonJS file
// miniprogram_dist/index.js for the WeChat mini program runtime (ES2017 at best).
import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
const OUT_DIR = path.join(ROOT, 'miniprogram_dist');

const constants = fs.readFileSync(path.join(ROOT, 'src/constants.js'), 'utf8');
const m = /SDK_VERSION = '([^']+)'/.exec(constants);
if (!m || m[1] !== pkg.version) {
  console.error(`version mismatch: package.json ${pkg.version}, src/constants.js ${m && m[1]}`);
  process.exit(1);
}

fs.rmSync(OUT_DIR, { recursive: true, force: true });
fs.mkdirSync(OUT_DIR, { recursive: true });

const result = await build({
  entryPoints: [path.join(ROOT, 'src/index.js')],
  outfile: path.join(OUT_DIR, 'index.js'),
  bundle: true,
  format: 'cjs',
  platform: 'neutral',
  target: 'es2017',
  minify: false,
  sourcemap: false,
  legalComments: 'none',
  charset: 'utf8',
  define: { 'Object.fromEntries': '__yuguFromEntries' },
  inject: [path.join(ROOT, 'scripts/shims.js')],
  banner: {
    js: `/*! ${pkg.name} ${pkg.version} | Apache-2.0 | ${pkg.homepage} */`,
  },
  logLevel: 'warning',
  metafile: true,
});

const out = fs.readFileSync(path.join(OUT_DIR, 'index.js'), 'utf8');
if (/Object\.fromEntries/.test(out)) {
  console.error('build output still calls Object.fromEntries');
  process.exit(1);
}
const inputs = Object.keys(result.metafile.inputs).length;
if (!process.argv.includes('--quiet')) console.log(`built miniprogram_dist/index.js: ${out.length} bytes from ${inputs} modules`);
