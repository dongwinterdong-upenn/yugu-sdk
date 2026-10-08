// Self test of the mock platform: signature, idempotency replay, fault queue, WS ping and replay.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import WebSocket from 'ws';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const proc = spawn(process.execPath, [path.join(HERE, 'server.mjs'), '--port', '0'], { stdio: ['ignore', 'pipe', 'inherit'] });
const port = await new Promise((resolve) => proc.stdout.once('data', (d) => resolve(JSON.parse(d.toString()).port)));
const base = `http://127.0.0.1:${port}`;
const sign = (params, secret) => {
  const keys = Object.keys(params).filter((k) => params[k] !== null && params[k] !== undefined && params[k] !== '').sort();
  return crypto.createHmac('sha256', secret).update(keys.map((k) => `${k}=${params[k]}`).join('&'), 'utf8').digest('base64');
};
const wav = fs.readFileSync(path.resolve(HERE, '../../spec/fixtures/audio/zh_short.wav'));

function multipart(fields, cfgJson) {
  const b = '----mock' + crypto.randomBytes(6).toString('hex');
  const chunks = [];
  for (const [k, v] of Object.entries(fields)) chunks.push(Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`));
  if (cfgJson) chunks.push(Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="config"\r\nContent-Type: application/json\r\n\r\n${cfgJson}\r\n`));
  chunks.push(Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="audio"; filename="a.wav"\r\nContent-Type: audio/wav\r\n\r\n`), wav, Buffer.from(`\r\n--${b}--\r\n`));
  return { body: Buffer.concat(chunks), type: 'multipart/form-data; boundary=' + b };
}
const authHeaders = (params) => ({ 'X-App-Key': 'mock-app-key', 'X-Timestamp': String(Math.floor(Date.now() / 1000)), 'X-Nonce': crypto.randomBytes(8).toString('hex'), 'X-Signature': sign(params, 'mock-secret-key') });

let failures = 0;
async function check(name, fn) {
  try { await fn(); console.log('ok   ' + name); } catch (e) { failures++; console.log('FAIL ' + name + ': ' + e.message); }
}

const cfg = JSON.stringify({ coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' });

await check('native evaluate with config signature', async () => {
  const m = multipart({}, cfg);
  const r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, ...authHeaders({ config: cfg }) } });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.ok(j.recordId && j.result && j.result.overall > 0);
});

await check('bad signature is 401 2003', async () => {
  const m = multipart({}, cfg);
  const r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, ...authHeaders({ config: cfg }), 'X-Signature': 'x' } });
  assert.equal(r.status, 401);
  assert.equal((await r.json()).code, 2003);
});

await check('idempotency replay bills once', async () => {
  await fetch(base + '/__mock/reset', { method: 'POST' });
  const key = 'k-' + crypto.randomBytes(8).toString('hex');
  const ids = [];
  for (let i = 0; i < 3; i++) {
    const m = multipart({}, cfg);
    const r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, 'Idempotency-Key': key, ...authHeaders({ config: cfg }) } });
    assert.equal(r.status, 200);
    ids.push((await r.json()).recordId);
    if (i > 0) assert.equal(r.headers.get('idempotency-replayed'), 'true');
  }
  assert.equal(new Set(ids).size, 1);
  const b = await (await fetch(base + '/__mock/billing')).json();
  assert.equal(b.billed, 1);
});

await check('fault queue: 500 then success', async () => {
  await fetch(base + '/__mock/faults', { method: 'POST', body: JSON.stringify({ faults: [{ match: '/api/v1/evaluate', fault: 'status:500' }] }) });
  const m = multipart({}, cfg);
  let r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, ...authHeaders({ config: cfg }) } });
  assert.equal(r.status, 500);
  assert.equal((await r.json()).code, 50000);
  const m2 = multipart({}, cfg);
  r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body: m2.body, headers: { 'Content-Type': m2.type, ...authHeaders({ config: cfg }) } });
  assert.equal(r.status, 200);
});

await check('compat REST', async () => {
  const fields = { refText: '今天天气很好' };
  const m = multipart(fields, null);
  const r = await fetch(base + '/sent.eval.cn', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, ...authHeaders(fields) } });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.eof, 1);
  assert.ok(Array.isArray(j.result.words));
});

await check('compat attachAudioUrl returns a download link, same link on replay', async () => {
  const fields = { refText: '今天天气很好', attachAudioUrl: '1' };
  const key = 'k-' + crypto.randomBytes(8).toString('hex');
  const urls = [];
  for (let i = 0; i < 2; i++) {
    const m = multipart(fields, null);
    const r = await fetch(base + '/sent.eval.cn', { method: 'POST', body: m.body, headers: { 'Content-Type': m.type, 'Idempotency-Key': key, ...authHeaders(fields) } });
    assert.equal(r.status, 200);
    urls.push((await r.json()).audioUrl);
  }
  assert.match(urls[0], /\/rec\/[0-9]{8}\/eval_[A-Za-z0-9_]+-[0-9a-f]{32}\.wav$/);
  assert.equal(urls[1], urls[0]);
  const a = await fetch(urls[0]);
  assert.equal(a.status, 200);
  assert.ok(Buffer.from(await a.arrayBuffer()).equals(wav));
  const plain = multipart({ refText: '今天天气很好' }, null);
  const n = await fetch(base + '/sent.eval.cn', { method: 'POST', body: plain.body, headers: { 'Content-Type': plain.type, ...authHeaders({ refText: '今天天气很好' }) } });
  assert.equal('audioUrl' in (await n.json()), false);
});

await check('config part without application/json is 415', async () => {
  const b = '----x';
  const body = Buffer.concat([Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="config"\r\n\r\n${cfg}\r\n--${b}\r\nContent-Disposition: form-data; name="audio"; filename="a.wav"\r\n\r\n`), wav, Buffer.from(`\r\n--${b}--\r\n`)]);
  const r = await fetch(base + '/api/v1/evaluate', { method: 'POST', body, headers: { 'Content-Type': 'multipart/form-data; boundary=' + b, ...authHeaders({ config: cfg }) } });
  assert.equal(r.status, 415);
});

function wsUrl(p, extra = {}) {
  const q = { appKey: 'mock-app-key', timestamp: String(Math.floor(Date.now() / 1000)), nonce: crypto.randomBytes(6).toString('hex'), ...extra };
  q.signature = sign(q, 'mock-secret-key');
  return `ws://127.0.0.1:${port}${p}?` + new URLSearchParams(q);
}
function wsRun(url, first, { idem } = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const frames = [];
    ws.on('message', (d) => {
      const j = JSON.parse(d.toString());
      frames.push(j);
      if (j.event === 'connected') { ws.send(JSON.stringify({ cmd: 'ping' })); ws.send(JSON.stringify({ ...first, ...(idem ? { idempotencyKey: idem } : {}) })); }
      if (j.event === 'started') {
        const pcm = wav.subarray(44);
        for (let i = 0; i < pcm.length; i += 640) ws.send(pcm.subarray(i, i + 640));
        ws.send(JSON.stringify({ cmd: 'end' }));
      }
      if (j.eof === 1 || j.event === 'error') { ws.close(1000); resolve(frames); }
    });
    ws.on('error', reject);
  });
}

await check('compat WS ping, progress and result', async () => {
  const frames = await wsRun(wsUrl('/sent.eval.cn'), { refText: '今天天气很好', language: 'zh-CN', realtime_feedback: true });
  assert.ok(frames.some((f) => f.event === 'pong'));
  assert.ok(frames.some((f) => f.eof === 0 && f.result && f.result.bytes > 0));
  assert.equal(frames.at(-1).eof, 1);
  assert.equal(frames.filter((f) => f.event === 'started').length, 1);
});

await check('native WS replay with same key', async () => {
  await fetch(base + '/__mock/reset', { method: 'POST' });
  const key = 'ws-' + crypto.randomBytes(6).toString('hex');
  const a = await wsRun(wsUrl('/api/v1/ws/evaluate'), { cmd: 'start', coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' }, { idem: key });
  const b = await wsRun(wsUrl('/api/v1/ws/evaluate'), { cmd: 'start', coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' }, { idem: key });
  assert.equal(a.at(-1).recordId, b.at(-1).recordId);
  assert.equal(b.at(-1).replayed, true);
  const bill = await (await fetch(base + '/__mock/billing')).json();
  assert.equal(bill.billed, 1);
});

await check('WS kill after 3 frames', async () => {
  await new Promise((resolve) => {
    const ws = new WebSocket(wsUrl('/sent.eval.cn', { mockFault: 'ws-kill-after:3' }));
    let n = 0;
    ws.on('message', () => { for (let i = 0; i < 5; i++) { n++; ws.send(Buffer.alloc(640)); } });
    ws.on('close', (code) => { assert.notEqual(code, 1000); resolve(); });
    ws.on('error', () => {});
  });
});

proc.kill('SIGTERM');
if (failures) { console.log(failures + ' failure(s)'); process.exit(1); }
console.log('mock server selftest passed');
