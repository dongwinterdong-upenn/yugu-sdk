#!/usr/bin/env node
// Mock of the Yugu speech platform used by every SDK's integration tests.
//
//   node tools/mock-server/server.mjs [--port 0] [--processing-ms 50]
//
// Prints one JSON line {"port": N} on stdout once listening. Implements the endpoints the SDKs
// call, the same signature and idempotency rules as the real platform (CONTRACT.md), and
// fault injection. See README.md next to this file.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, '../../spec/fixtures/platform');
const fixture = (name) => JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));

const args = process.argv.slice(2);
const argVal = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : def;
};
const PORT = Number(argVal('--port', '0'));
let PROCESSING_MS = Number(argVal('--processing-ms', '50'));

export const MOCK_APP_KEY = 'mock-app-key';
export const MOCK_SECRET = 'mock-secret-key';
export const MOCK_TOKEN = 'mock-jwt-token';
const KEYS = { [MOCK_APP_KEY]: MOCK_SECRET, 'mock-app-key-2': 'mock-secret-key-2' };

const COMPAT_CORE_TYPES = new Set(['word.eval', 'word.eval.pro', 'sent.eval', 'sent.eval.pro', 'para.eval',
  'alpha.eval', 'word.eval.cn', 'sent.eval.cn', 'para.eval.cn', 'pinyin']);
const NATIVE_CORE_TYPES = new Set(['word', 'sentence', 'passage', 'connected', 'open', 'alpha', 'pinyin']);

// ---------------------------------------------------------------- state
const state = {
  faults: [],            // queued faults: {match, fault}
  log: [],               // received requests
  billing: [],           // executed evaluations {op, key, idemKey, recordId}
  idem: new Map(),       // scope|key -> {status:'pending'|'done', fp, body, headers, waiters}
  nonces: new Map(),     // appKey|nonce -> first seen ms (the platform rejects reuse within 300 s)
  slowMs: 0,             // extra processing time of the next request, set by the slow: fault
  seq: 0,
};

const DEFAULT_PROCESSING_MS = PROCESSING_MS;

function reset() {
  PROCESSING_MS = DEFAULT_PROCESSING_MS;
  state.faults = [];
  state.log = [];
  state.billing = [];
  state.idem = new Map();
  state.nonces = new Map();
  state.slowMs = 0;
}

function takeFault(pathname, headerFault) {
  if (headerFault) return headerFault;
  const i = state.faults.findIndex((f) => !f.match || pathname.startsWith(f.match));
  if (i < 0) return null;
  const [f] = state.faults.splice(i, 1);
  return f.fault;
}

// ---------------------------------------------------------------- helpers
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function signHmac(params, secret) {
  const keys = Object.keys(params).filter((k) => params[k] !== null && params[k] !== undefined && params[k] !== '').sort();
  const payload = keys.map((k) => `${k}=${params[k]}`).join('&');
  return crypto.createHmac('sha256', secret).update(payload, 'utf8').digest('base64');
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

// Minimal multipart/form-data parser. Returns {fields: {name: string}, files: {name: {filename, contentType, data}}, parts: [...]}
function parseMultipart(body, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!m) throw Object.assign(new Error('missing boundary'), { status: 400 });
  const boundary = Buffer.from('--' + (m[1] || m[2]).trim());
  const out = { fields: {}, files: {}, parts: [] };
  let pos = body.indexOf(boundary);
  while (pos >= 0) {
    pos += boundary.length;
    if (body.slice(pos, pos + 2).toString() === '--') break;
    if (body.slice(pos, pos + 2).toString() === '\r\n') pos += 2;
    const headerEnd = body.indexOf('\r\n\r\n', pos);
    if (headerEnd < 0) break;
    const headerText = body.slice(pos, headerEnd).toString('utf8');
    const next = body.indexOf(boundary, headerEnd + 4);
    if (next < 0) break;
    const data = body.slice(headerEnd + 4, next - 2); // strip trailing CRLF
    const headers = {};
    for (const line of headerText.split('\r\n')) {
      const k = line.indexOf(':');
      if (k > 0) headers[line.slice(0, k).trim().toLowerCase()] = line.slice(k + 1).trim();
    }
    const cd = headers['content-disposition'] || '';
    const name = (/name="([^"]*)"/.exec(cd) || [])[1];
    const fnm = /filename="([^"]*)"/.exec(cd);
    const part = { name, filename: fnm ? fnm[1] : null, contentType: headers['content-type'] || null, data };
    out.parts.push(part);
    if (fnm) out.files[name] = part;
    else out.fields[name] = data.toString('utf8');
    pos = next;
  }
  return out;
}

function sendJson(res, status, obj, headers = {}) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(body);
}

function platformError(res, status, code, message, headers = {}) {
  sendJson(res, status, { code, message, timestamp: Date.now() }, headers);
}

// Authentication. Returns {ok, scope} or writes the error response and returns null.
function authenticate(req, res, signParams) {
  const auth = req.headers['authorization'];
  if (auth) {
    if (auth === 'Bearer ' + MOCK_TOKEN) return { scope: 'u:mock-user' };
    platformError(res, 400, 2002, 'token 无效');
    return null;
  }
  const appKey = req.headers['x-app-key'];
  const ts = req.headers['x-timestamp'];
  const sig = req.headers['x-signature'];
  if (!appKey || !ts || !sig) {
    platformError(res, 401, 40100, '认证失败,请提供有效的认证信息');
    return null;
  }
  const secret = KEYS[appKey];
  if (!secret) {
    platformError(res, 401, 2010, 'api key 不存在');
    return null;
  }
  if (Math.abs(Date.now() / 1000 - Number(ts)) > 300) {
    platformError(res, 401, 2003, '时间戳过期');
    return null;
  }
  const nonce = req.headers['x-nonce'];
  if (nonce) {
    const nk = appKey + '|' + nonce;
    const seen = state.nonces.get(nk);
    if (seen && Date.now() - seen < 300000) {
      platformError(res, 401, 2003, '重复的请求');
      return null;
    }
    state.nonces.set(nk, Date.now());
  }
  const accepted = signParams.some((p) => signHmac(p, secret) === sig);
  if (!accepted) {
    platformError(res, 401, 2003, '签名验证失败');
    return null;
  }
  return { scope: 'k:' + appKey };
}

function fingerprint(parts) {
  const h = crypto.createHash('sha256');
  for (const p of parts) h.update(typeof p === 'string' ? p : p || Buffer.alloc(0)).update('\u0000');
  return h.digest('hex');
}

// Idempotency, same semantics as the platform (CONTRACT.md 幂等).
async function withIdempotency(scope, key, fp, run) {
  if (!key) return run();
  if (key.length > 200) return run();
  const id = scope + '|' + key;
  const existing = state.idem.get(id);
  if (existing) {
    if (existing.fp !== fp) {
      return { status: 409, body: { code: 40903, message: '该 Idempotency-Key 已用于另一个不同的请求', timestamp: Date.now() } };
    }
    if (existing.status === 'pending') {
      const deadline = Date.now() + Number(process.env.MOCK_IDEM_WAIT_MS || 3000);
      while (existing.status === 'pending' && Date.now() < deadline) await sleep(25);
      if (existing.status === 'pending') {
        return { status: 409, body: { code: 40901, message: '相同 Idempotency-Key 的请求正在处理中', timestamp: Date.now() }, headers: { 'Retry-After': '1' } };
      }
    }
    if (existing.status === 'done') {
      return { ...existing.result, headers: { ...(existing.result.headers || {}), 'Idempotency-Replayed': 'true' }, replayed: true };
    }
  }
  const entry = { status: 'pending', fp };
  state.idem.set(id, entry);
  try {
    const result = await run();
    if (result.status >= 200 && result.status < 300) {
      entry.status = 'done';
      entry.result = result;
    } else {
      state.idem.delete(id);
    }
    return result;
  } catch (e) {
    state.idem.delete(id);
    throw e;
  }
}

function nextRecordId(prefix = 'eval_') {
  state.seq += 1;
  return prefix + crypto.createHash('md5').update(String(state.seq) + ':' + Date.now()).digest('hex').slice(0, 12);
}

// Real responses captured from the platform, one per evaluation mode (spec/fixtures/platform/native_*.json).
const NATIVE_FIXTURES = {
  'word:en': 'native_word_en.json', 'sentence:en': 'native_sentence_en.json', 'sentence:zh': 'native_evaluate_sentence_zh.json',
  'passage:zh': 'native_passage_zh.json', 'connected:en': 'native_connected_en.json', 'open:zh': 'native_open_zh.json',
  'alpha:en': 'native_alpha_en.json', 'pinyin:zh': 'native_pinyin_zh.json',
};
function nativeResult(cfg = {}) {
  const lang = String(cfg.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en';
  const ct = cfg.coreType || 'sentence';
  const name = NATIVE_FIXTURES[ct + ':' + lang] || NATIVE_FIXTURES[ct + ':zh'] || NATIVE_FIXTURES[ct + ':en'] || 'native_evaluate_sentence_zh.json';
  const r = fixture(fs.existsSync(path.join(FIXTURES, name)) ? name : 'native_evaluate_sentence_zh.json');
  r.recordId = nextRecordId();
  return r;
}

function compatResult(coreType) {
  const name = fs.existsSync(path.join(FIXTURES, `compat_${coreType}.json`)) ? `compat_${coreType}.json` : 'compat_sent.eval.cn.json';
  const r = fixture(name);
  r.recordId = nextRecordId(coreType.startsWith('word') ? 'eval_' : 'eval_');
  return r;
}

// Applies an HTTP fault. Returns true when the fault fully handled the response.
async function applyHttpFault(fault, req, res) {
  if (!fault) return false;
  const [kind, ...rest] = fault.split(':');
  if (kind === 'drop') {
    req.socket.destroy();
    return true;
  }
  if (kind === 'hang') {
    return true; // never answer; client must time out
  }
  if (kind === 'delay') {
    await sleep(Number(rest[0] || 1000));
    return false;
  }
  if (kind === 'slow') {
    // the request is registered for idempotency first and then takes this long to process
    req.slowMs = Number(rest[0] || 1000);
    return false;
  }
  if (kind === 'status') {
    const status = Number(rest[0]);
    const opts = Object.fromEntries(rest.slice(1).map((s) => s.split('=')));
    const headers = {};
    if (opts.retryAfter) headers['Retry-After'] = opts.retryAfter;
    if (opts.raw === 'html') {
      res.writeHead(status, { 'Content-Type': 'text/html' });
      res.end('<html><body>' + status + '</body></html>');
      return true;
    }
    if (opts.detail) {
      sendJson(res, status, { detail: decodeURIComponent(opts.detail) }, headers);
      return true;
    }
    const code = opts.code ? Number(opts.code) : status >= 500 ? (status === 502 ? 50200 : 50000) : status === 429 ? 42900 : status === 409 ? 40900 : status === 401 ? 40100 : status === 403 ? 40300 : status === 404 ? 40400 : 40001;
    platformError(res, status, code, opts.message ? decodeURIComponent(opts.message) : 'mock fault ' + status, headers);
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- HTTP
async function handleHttp(req, res) {
  const url = new URL(req.url, 'http://mock');
  const pathname = url.pathname;
  const body = await readBody(req);
  const entry = {
    t: Date.now(), method: req.method, path: pathname,
    idempotencyKey: req.headers['idempotency-key'] || null,
    userAgent: req.headers['user-agent'] || null, sdk: req.headers['x-yugu-sdk'] || null,
    contentType: req.headers['content-type'] || null, bytes: body.length,
  };

  // control API
  if (pathname === '/__mock/reset') { reset(); return sendJson(res, 200, { ok: true }); }
  if (pathname === '/__mock/faults' && req.method === 'POST') {
    const j = JSON.parse(body.toString() || '{}');
    for (const f of j.faults || []) state.faults.push(typeof f === 'string' ? { fault: f } : f);
    if (j.processingMs !== undefined) PROCESSING_MS = Number(j.processingMs);
    return sendJson(res, 200, { queued: state.faults.length });
  }
  if (pathname === '/__mock/log') return sendJson(res, 200, state.log);
  if (pathname === '/__mock/billing') {
    const byKey = {};
    for (const b of state.billing) byKey[b.idemKey || '(none)'] = (byKey[b.idemKey || '(none)'] || 0) + 1;
    return sendJson(res, 200, { billed: state.billing.length, byKey, records: state.billing });
  }
  if (pathname === '/healthz') return sendJson(res, 200, { ok: true });

  state.log.push(entry);
  const fault = takeFault(pathname, req.headers['x-mock-fault']);
  entry.fault = fault || null;
  if (await applyHttpFault(fault, req, res)) return;

  try {
    if (req.method === 'POST' && pathname === '/api/v1/evaluate') return await evaluateNative(req, res, body, entry);
    if (req.method === 'POST' && pathname === '/api/v1/tts/generate') return await tts(req, res, body, entry);
    if (req.method === 'GET' && pathname.startsWith('/api/v1/report/')) return report(req, res, decodeURIComponent(pathname.slice('/api/v1/report/'.length)));
    const ct = pathname.slice(1);
    if (req.method === 'POST' && COMPAT_CORE_TYPES.has(ct)) return await evaluateCompat(req, res, body, ct, entry);
    if (req.method === 'POST' && /^\/[a-z]+\.[a-z.]+$/.test(pathname)) return platformError(res, 403, 40300, '该 API Key 未授权调用此 coreType: ' + ct);
    return platformError(res, 404, 40400, 'No static resource ' + pathname);
  } catch (e) {
    return platformError(res, e.status || 500, e.code || 50000, e.message || 'system busy, please try again later');
  }
}

async function evaluateNative(req, res, body, entry) {
  if (!/multipart\/form-data/i.test(req.headers['content-type'] || '')) return platformError(res, 415, 40001, '不支持的 Content-Type，请使用 application/json');
  const mp = parseMultipart(body, req.headers['content-type']);
  const cfgPart = mp.parts.find((p) => p.name === 'config');
  const audio = mp.files.audio;
  if (!cfgPart) return platformError(res, 400, 40001, 'config 不能为空');
  if (!/application\/json/i.test(cfgPart.contentType || '')) return platformError(res, 415, 40001, '不支持的 Content-Type，请使用 application/json');
  let cfg;
  try { cfg = JSON.parse(cfgPart.data.toString('utf8')); } catch (e) { return platformError(res, 400, 40001, 'config 不是合法 JSON'); }
  entry.config = cfg;
  // Signature forms accepted by the platform: form params (config text part without filename is a param)
  // or form params minus config plus top-level scalar config fields.
  const formParams = { ...mp.fields };
  const flat = { ...mp.fields };
  delete flat.config;
  for (const [k, v] of Object.entries(cfg)) if (v !== null && typeof v !== 'object') flat[k] = String(v);
  const auth = authenticate(req, res, [formParams, flat]);
  if (!auth) return;
  if (!cfg.coreType || !NATIVE_CORE_TYPES.has(cfg.coreType)) return platformError(res, 400, 40001, 'coreType 不合法');
  if (!cfg.referenceText) return platformError(res, 400, 40001, 'referenceText 不能为空');
  if (!audio || audio.data.length === 0) return platformError(res, 400, 40001, 'audio 不能为空');
  const fp = fingerprint(['evaluate', cfgPart.data.toString('utf8'), audio.data]);
  const result = await withIdempotency(auth.scope, entry.idempotencyKey, fp, async () => {
    await sleep(PROCESSING_MS + (req.slowMs || 0));
    const r = nativeResult(cfg);
    state.billing.push({ op: 'evaluate', key: auth.scope, idemKey: entry.idempotencyKey, recordId: r.recordId });
    return { status: 200, body: r };
  });
  entry.replayed = !!result.replayed;
  sendJson(res, result.status, result.body, result.headers || {});
}

async function evaluateCompat(req, res, body, coreType, entry) {
  const mp = parseMultipart(body, req.headers['content-type']);
  const fields = { ...mp.fields };
  entry.fields = fields;
  if (!req.headers['x-app-key']) return platformError(res, 401, 40100, '缺少 X-App-Key,兼容层接口需鉴权');
  const auth = authenticate(req, res, [fields]);
  if (!auth) return;
  if (coreType === 'pinyin' && !fields.refPinyin) return platformError(res, 400, 40001, 'coreType=pinyin 时 refPinyin 必填（如 "chong2 qing4"）');
  const audio = mp.files.audio;
  if (!audio || audio.data.length === 0) return platformError(res, 400, 40001, 'audio 不能为空');
  const fp = fingerprint(['compat', coreType, JSON.stringify(Object.keys(fields).sort().map((k) => [k, fields[k]])), audio.data]);
  const result = await withIdempotency(auth.scope, entry.idempotencyKey, fp, async () => {
    await sleep(PROCESSING_MS + (req.slowMs || 0));
    const r = compatResult(coreType);
    state.billing.push({ op: 'compat', key: auth.scope, idemKey: entry.idempotencyKey, recordId: r.recordId });
    return { status: 200, body: r };
  });
  entry.replayed = !!result.replayed;
  sendJson(res, result.status, result.body, result.headers || {});
}

async function tts(req, res, body, entry) {
  let j;
  try { j = JSON.parse(body.toString('utf8')); } catch (e) { return platformError(res, 400, 40001, 'body 不是合法 JSON'); }
  const flat = {};
  for (const [k, v] of Object.entries(j)) if (v !== null && typeof v !== 'object') flat[k] = String(v);
  const auth = authenticate(req, res, [{}, flat]);
  if (!auth) return;
  if (!j.text) return platformError(res, 400, 40001, 'text 不能为空');
  const fp = fingerprint(['tts', body.toString('utf8')]);
  const result = await withIdempotency(auth.scope, entry.idempotencyKey, fp, async () => {
    await sleep(PROCESSING_MS + (req.slowMs || 0));
    state.billing.push({ op: 'tts', key: auth.scope, idemKey: entry.idempotencyKey });
    return { status: 200, body: { code: 0, message: 'success', data: { audioUrl: '/audio/mock-' + state.seq + '.mp3', duration: '1.348', format: j.format || 'mp3', warnings: [] }, timestamp: Date.now() } };
  });
  sendJson(res, result.status, result.body, result.headers || {});
}

function report(req, res, recordId) {
  const auth = authenticate(req, res, [{}]);
  if (!auth) return;
  if (!recordId.startsWith('eval_')) return platformError(res, 400, 40001, '评测记录不存在: ' + recordId);
  sendJson(res, 200, { code: 0, message: 'success', data: { recordId, overall: 93.7, report: { summary: 'mock report' } }, timestamp: Date.now() });
}

// ---------------------------------------------------------------- WebSocket
function wsAuth(url) {
  const q = Object.fromEntries(url.searchParams.entries());
  if (q.token) return q.token === MOCK_TOKEN ? { scope: 'u:mock-user' } : null;
  if (!q.appKey) return { scope: 'anon' };
  const secret = KEYS[q.appKey];
  if (!secret || !q.signature || !q.timestamp) return null;
  const params = { ...q };
  delete params.signature;
  return signHmac(params, secret) === q.signature ? { scope: 'k:' + q.appKey } : null;
}

function attachWs(server) {
  // 128 KB frame limit like the platform (WebSocketConfig); larger frames close the socket with 1009
  const wss = new WebSocketServer({ noServer: true, maxPayload: 128 * 1024 });
  // ws-silent sessions must not answer protocol pings either, so heartbeat timeouts can be tested.
  const silentWss = new WebSocketServer({ noServer: true, autoPong: false, maxPayload: 128 * 1024 });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://mock');
    const p = url.pathname;
    const isNative = p === '/api/v1/ws/evaluate';
    const ct = p.slice(1);
    if (!isNative && !COMPAT_CORE_TYPES.has(ct)) {
      state.log.push({ t: Date.now(), method: 'WS', path: p, rejected: 404 });
      socket.destroy();
      return;
    }
    const auth = wsAuth(url);
    if (!auth) {
      state.log.push({ t: Date.now(), method: 'WS', path: p, rejected: 403 });
      socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
      socket.destroy();
      return;
    }
    const fault = url.searchParams.get('mockFault') || takeFault(p, null);
    state.log.push({ t: Date.now(), method: 'WS', path: p, idempotencyKey: url.searchParams.get('idempotencyKey'), fault: fault || null });
    if (fault === 'ws-refuse') {
      state.log.at(-1).rejected = 503;
      socket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n');
      socket.destroy();
      return;
    }
    const target = fault && fault.startsWith('ws-silent') ? silentWss : wss;
    target.handleUpgrade(req, socket, head, (ws) => wsSession(ws, { isNative, coreType: ct, auth, url, fault }));
  });
}

function wsSession(ws, ctx) {
  // An oversized frame (over maxPayload) raises an error on the socket; ws then closes it with 1009.
  // Without a handler the error would be unhandled and take the whole mock process down.
  ws.on('error', (e) => { state.log.push({ t: Date.now(), method: 'WS', path: ctx.url.pathname, wsError: String(e && e.message) }); });
  const send = (o) => { if (ws.readyState === 1) ws.send(JSON.stringify(o)); };
  let audio = [];
  let params = null;
  let inbound = 0;
  let silent = false;
  let progress = 0;
  const pushAudio = (b) => {
    audio.push(b);
    if (!ctx.isNative && params && (params.realtime_feedback === true || params.realtime_feedback === 1 || params.realtime_feedback === '1' || params.realtime_feedback === 'true')) {
      const n = audio.reduce((a, x) => a + x.length, 0);
      if (n - progress >= 16000) { progress = n; send({ eof: 0, result: { bytes: n } }); }
    }
  };
  const [fkind, farg] = (ctx.fault || '').split(':');
  send(ctx.isNative ? { event: 'connected', message: 'stream channel ready' } : { event: 'connected', coreType: ctx.coreType });

  ws.on('message', async (data, isBinary) => {
    inbound += 1;
    if (fkind === 'ws-kill-after' && inbound >= Number(farg || 1)) { ws.terminate(); return; }
    if (fkind === 'ws-close-after' && inbound >= Number(farg || 1)) { ws.close(1011, 'mock server error'); return; }
    if (silent) return;
    if (fkind === 'ws-silent' && inbound >= Number(farg || 1)) { silent = true; return; }
    if (isBinary) { pushAudio(Buffer.from(data)); return; }
    let j;
    try { j = JSON.parse(data.toString('utf8')); } catch (e) { send({ event: 'error', message: 'bad json frame' }); return; }
    if (j.cmd === 'ping') { send({ event: 'pong', ts: Date.now() }); return; }
    if (j.cmd === 'audio') { pushAudio(Buffer.from(j.data || '', 'base64')); return; }
    if (j.cmd === 'end' || j.end === true) { await finish(); return; }
    if (ctx.isNative && j.cmd !== 'start') { send({ event: 'error', message: 'unknown cmd' }); return; }
    params = j;
    audio = [];
    progress = 0;
    send(ctx.isNative ? { event: 'started' } : { event: 'started', coreType: ctx.coreType });
  });

  async function finish() {
    if (!params) { send({ event: 'error', message: 'no audio/config' }); return; }
    const buf = Buffer.concat(audio);
    if (buf.length === 0) { send({ event: 'error', message: 'no audio' }); return; }
    if (fkind === 'ws-error') { send({ event: 'error', code: Number(farg || 50200), message: 'mock upstream error' }); return; }
    const idemKey = params.idempotencyKey || ctx.url.searchParams.get('idempotencyKey') || null;
    const p = { ...params };
    delete p.idempotencyKey;
    const fp = fingerprint([ctx.isNative ? 'ws-native' : 'ws-compat:' + ctx.coreType, JSON.stringify(Object.keys(p).sort().map((k) => [k, p[k]])), buf]);
    const result = await withIdempotency(ctx.auth.scope, idemKey, fp, async () => {
      await sleep(PROCESSING_MS + (fkind === 'ws-delay-result' ? Number(farg || 1000) : 0));
      const r = ctx.isNative ? nativeResult(params) : compatResult(ctx.coreType);
      state.billing.push({ op: ctx.isNative ? 'ws-native' : 'ws-compat', key: ctx.auth.scope, idemKey, recordId: r.recordId, bytes: buf.length });
      return { status: 200, body: r };
    });
    if (result.status !== 200) { send({ event: 'error', code: result.body.code, message: result.body.message }); return; }
    const frame = ctx.isNative ? { event: 'result', ...result.body } : { ...result.body };
    if (result.replayed) frame.replayed = true;
    send(frame);
    audio = [];
  }

}

// ---------------------------------------------------------------- main
const server = http.createServer((req, res) => {
  handleHttp(req, res).catch((e) => {
    try { platformError(res, 500, 50000, String(e && e.message)); } catch (_) { /* socket gone */ }
  });
});
attachWs(server);
server.listen(PORT, '127.0.0.1', () => {
  process.stdout.write(JSON.stringify({ port: server.address().port }) + '\n');
});
process.on('SIGTERM', () => server.close(() => process.exit(0)));
