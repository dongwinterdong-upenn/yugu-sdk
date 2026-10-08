// Runs demos/miniprogram-demo the way WeChat DevTools does after "构建 npm": the package's
// miniprogram_dist is copied to miniprogram_npm/@shengzhiai/yugu-miniprogram-sdk and the page code
// runs in a separate JavaScript realm with App, Page, getApp, wx and timers only (no Node globals).
// The page records, evaluates the recording, then runs a streaming evaluation, against the mock.
//
// YUGU_SDK_PACKAGE_DIR selects the package to install, for example an unpacked `npm pack` tarball;
// the default is this package directory.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { startMockServer, MOCK_APP_KEY, MOCK_SECRET } from '../helpers/mock-server.mjs';
import { createWxMock } from '../helpers/wx-mock.mjs';
import { PKG_ROOT, REPO_ROOT, waitFor, sleep } from '../helpers/common.mjs';

const DEMO = path.join(REPO_ROOT, 'demos/miniprogram-demo');
const PACKAGE_DIR = process.env.YUGU_SDK_PACKAGE_DIR || PKG_ROOT;
const PKG_NAME = '@shengzhiai/yugu-miniprogram-sdk';

let mock;
test.before(async () => {
  mock = await startMockServer({ processingMs: 20 });
});
test.after(() => mock.stop());

function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'miniprogram_npm') continue;
    const a = path.join(from, e.name);
    const b = path.join(to, e.name);
    if (e.isDirectory()) copyDir(a, b);
    else fs.copyFileSync(a, b);
  }
}

/** Copies the demo and performs the "构建 npm" step for the SDK package. */
const demoDirs = new Set();
process.once('exit', () => {
  for (const d of demoDirs) fs.rmSync(d, { recursive: true, force: true });
});

function prepareDemo(configSource) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'yugu-demo-'));
  demoDirs.add(dir);
  copyDir(DEMO, dir);
  const pkg = JSON.parse(fs.readFileSync(path.join(PACKAGE_DIR, 'package.json'), 'utf8'));
  assert.equal(pkg.name, PKG_NAME);
  const demoPkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  assert.equal(demoPkg.dependencies[PKG_NAME], pkg.version, 'the demo depends on the version under test');
  copyDir(path.join(PACKAGE_DIR, pkg.miniprogram), path.join(dir, 'miniprogram_npm', PKG_NAME));
  if (configSource) fs.writeFileSync(path.join(dir, 'config.js'), configSource);
  return dir;
}

/** A minimal mini program runtime: CommonJS modules, App, Page, getApp, wx. */
function runtime(dir, wx) {
  const pages = [];
  let app = null;
  const ui = { toasts: [], modals: [] };
  wx.getSetting = (o) => setTimeout(() => o.success({ authSetting: {} }), 0);
  wx.authorize = (o) => setTimeout(() => o.success && o.success({}), 0);
  wx.showToast = (o) => ui.toasts.push(o);
  wx.showModal = (o) => ui.modals.push(o);
  const context = vm.createContext({
    console: { log() {}, info() {}, warn() {}, error() {}, debug() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    wx,
    App: (cfg) => { app = cfg; },
    Page: (cfg) => pages.push(cfg),
    getApp: () => app,
  });
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} };
    cache.set(file, module);
    const code = fs.readFileSync(file, 'utf8');
    const fn = vm.runInContext('(function (require, module, exports) {' + code + '\n})', context, { filename: file });
    fn(makeRequire(path.dirname(file)), module, module.exports);
    return module.exports;
  }
  function makeRequire(base) {
    return (spec) => {
      if (spec === PKG_NAME) return load(path.join(dir, 'miniprogram_npm', PKG_NAME, 'index.js'));
      if (spec.startsWith('.')) {
        const f = path.resolve(base, spec);
        return load(f.endsWith('.js') ? f : f + '.js');
      }
      throw new Error('module not available in the mini program runtime: ' + spec);
    };
  }
  load(path.join(dir, 'app.js'));
  load(path.join(dir, 'pages/index/index.js'));
  const cfg = pages[0];
  const page = {};
  for (const [k, v] of Object.entries(cfg)) if (k !== 'data') page[k] = v;
  page.data = JSON.parse(JSON.stringify(cfg.data));
  page.setData = (obj, cb) => {
    Object.assign(page.data, obj);
    if (cb) cb();
  };
  if (app && app.onLaunch) app.onLaunch.call(app);
  return { page, ui };
}

test('the demo records, evaluates the recording and runs a streaming evaluation', async () => {
  const dir = prepareDemo(`module.exports = { auth: { appKey: '${MOCK_APP_KEY}', secretKey: '${MOCK_SECRET}' }, baseUrl: '${mock.baseUrl}', wsBaseUrl: '${mock.wsBaseUrl}' };\n`);
  const wx = createWxMock({ frameIntervalMs: 3 });
  const { page, ui } = runtime(dir, wx);
  try {
    page.onLoad({});
    assert.equal(page.data.configError, '');
    page.onRecordTap();
    assert.equal(page.data.recording, true);
    await waitFor(() => !wx.getRecorderManager().isRecording(), 5000, 'the recording');
    page.onRecordTap();
    await waitFor(() => page.data.hasRecording, 3000, 'recording result');
    assert.match(page.data.recordingInfo, /1\.9 秒/);
    page.onEvaluateTap();
    await waitFor(() => page.data.result && !page.data.busy, 5000, 'evaluation');
    assert.equal(page.data.result.overall, 93.7);
    assert.equal(page.data.result.words.length, 6);
    assert.ok(page.data.result.dims.some((d) => d.name === '发音'));
    page.setData({ result: null });
    page.onStreamTap();
    assert.equal(page.data.streaming, true);
    await waitFor(() => page.data.result && !page.data.streaming, 8000, 'streaming evaluation');
    assert.equal(page.data.result.overall, 93.7);
    assert.equal(page.data.sessionState, 'CLOSED');
    assert.deepEqual(ui.modals, [], 'no error dialog');
    const billing = await mock.billing();
    assert.deepEqual(billing.records.map((r) => r.op).sort(), ['evaluate', 'ws-native']);
  } finally {
    page.onUnload();
    await sleep(20);
    assert.equal(wx.getRecorderManager().micOpen, false);
    wx.__mock.cleanup();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('the demo explains missing credentials instead of failing', () => {
  const dir = prepareDemo(null);
  const wx = createWxMock();
  const { page } = runtime(dir, wx);
  page.onLoad({});
  assert.match(page.data.configError, /config\.js/);
  page.onRecordTap();
  page.onStreamTap();
  page.onUnload();
  wx.__mock.cleanup();
  fs.rmSync(dir, { recursive: true, force: true });
});
