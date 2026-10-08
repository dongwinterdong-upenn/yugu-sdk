#!/usr/bin/env node
// Builds dist/: ESM bundle, UMD bundle (global YuguSDK), minified UMD, the AudioWorklet module.
// Fails on any esbuild warning so the build stays warning free.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';
import { PCM_WORKLET_SOURCE } from '../src/recorder.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIST = path.join(ROOT, 'dist');
const quiet = process.argv.includes('--quiet');
const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
const BANNER = `/*! ${pkg.name} ${pkg.version} | ${pkg.license} | ${pkg.homepage} */`;

const UMD_HEAD = `(function (root, factory) {
  if (typeof define === 'function' && define.amd) define([], factory);
  else if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.YuguSDK = factory();
}(typeof globalThis !== 'undefined' ? globalThis : typeof self !== 'undefined' ? self : this, function () {
'use strict';`;
const UMD_FOOT = `return __yuguSdk;
}));`;

const common = {
  entryPoints: [path.join(ROOT, 'src/index.js')],
  bundle: true,
  platform: 'neutral',
  target: ['es2018'],
  sourcemap: true,
  keepNames: true,
  legalComments: 'none',
  logLevel: 'silent',
  write: true,
};

fs.rmSync(DIST, { recursive: true, force: true });
fs.mkdirSync(DIST, { recursive: true });

const builds = [
  { ...common, format: 'esm', outfile: path.join(DIST, 'yugu-sdk.mjs'), banner: { js: BANNER } },
  {
    ...common,
    format: 'iife',
    globalName: '__yuguSdk',
    outfile: path.join(DIST, 'yugu-sdk.umd.js'),
    banner: { js: `${BANNER}\n${UMD_HEAD}` },
    footer: { js: UMD_FOOT },
  },
  {
    ...common,
    format: 'iife',
    globalName: '__yuguSdk',
    minify: true,
    outfile: path.join(DIST, 'yugu-sdk.umd.min.js'),
    banner: { js: `${BANNER}\n${UMD_HEAD}` },
    footer: { js: UMD_FOOT },
  },
];

let warnings = 0;
for (const b of builds) {
  const res = await esbuild.build(b);
  for (const w of res.warnings) {
    warnings += 1;
    console.error(`esbuild warning in ${path.basename(b.outfile)}: ${w.text}`);
  }
  for (const e of res.errors) console.error(`esbuild error: ${e.text}`);
}

fs.writeFileSync(path.join(DIST, 'yugu-pcm-worklet.js'), `${BANNER}\n${PCM_WORKLET_SOURCE}`);
// The UMD file is CommonJS for Node's require() although the package is "type": "module".
fs.writeFileSync(path.join(DIST, 'package.json'), `${JSON.stringify({ type: 'commonjs' }, null, 2)}\n`);

for (const f of ['yugu-sdk.mjs', 'yugu-sdk.umd.js', 'yugu-sdk.umd.min.js']) {
  const code = fs.readFileSync(path.join(DIST, f), 'utf8');
  if (/\bimport\s*\(|require\(\s*['"]node:/.test(code)) {
    console.error(`${f}: unexpected dynamic import or node: require`);
    warnings += 1;
  }
  if (/ygyx\.dragonai\.tech|voiceapi\.dragonai\.tech/.test(code)) {
    console.error(`${f}: contains an internal host name`);
    warnings += 1;
  }
}

if (warnings) {
  console.error(`build failed: ${warnings} warning(s)`);
  process.exit(1);
}
if (!quiet) {
  for (const f of fs.readdirSync(DIST).sort()) {
    console.log(`dist/${f}  ${fs.statSync(path.join(DIST, f)).size} bytes`);
  }
}
