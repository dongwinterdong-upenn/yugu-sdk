// 声通兼容实时评测，直连协议。Node.js 18 及以上，依赖 npm install ws
import crypto from 'node:crypto';
import { readFile } from 'node:fs/promises';
import WebSocket from 'ws';

const idempotencyKey = crypto.randomBytes(16).toString('hex');
const query = {
  appKey: process.env.YUGU_APP_KEY,
  nonce: crypto.randomBytes(16).toString('hex'),
  timestamp: String(Math.floor(Date.now() / 1000)),
};
const payload = Object.keys(query).sort().map((k) => `${k}=${query[k]}`).join('&');
const signature = crypto.createHmac('sha256', process.env.YUGU_SECRET_KEY).update(payload, 'utf8').digest('base64');
const ws = new WebSocket('wss://open.shengzhiai.com/sent.eval.cn?' + new URLSearchParams({ ...query, signature }));

const wav = await readFile('audio.wav');
const pcm = wav.subarray(wav.indexOf('data') + 8);

ws.on('message', (data, isBinary) => {
  if (isBinary) return;
  const msg = JSON.parse(data.toString());
  if (msg.event === 'connected') {
    // 参数帧一轮只发一次，幂等键放在参数帧里
    ws.send(JSON.stringify({ refText: '今天天气很好', language: 'zh-CN', realtime_feedback: true, idempotencyKey }));
  } else if (msg.event === 'started') {
    for (let i = 0; i < pcm.length; i += 640) ws.send(pcm.subarray(i, i + 640));
    ws.send(JSON.stringify({ cmd: 'end' }));
  } else if (msg.eof === 0) {
    console.log('进度', msg.result.bytes);
  } else if (msg.eof === 1) {
    console.log('recordId', msg.recordId, '总分', msg.result.overall);
    ws.close();
  } else if (msg.event === 'error') {
    console.error('错误', msg.code, msg.message);
    ws.close();
  }
});
