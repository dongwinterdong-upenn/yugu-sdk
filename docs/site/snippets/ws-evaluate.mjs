// 原生实时评测，直连协议。Node.js 18 及以上，依赖 npm install ws
import crypto from 'node:crypto';
import { readFile } from 'node:fs/promises';
import WebSocket from 'ws';

const query = {
  appKey: process.env.YUGU_APP_KEY,
  idempotencyKey: crypto.randomBytes(16).toString('hex'),
  nonce: crypto.randomBytes(16).toString('hex'),
  timestamp: String(Math.floor(Date.now() / 1000)),
};
// 握手签名：signature 之外的全部 query 参数按键名排序拼接
const payload = Object.keys(query).sort().map((k) => `${k}=${query[k]}`).join('&');
const signature = crypto.createHmac('sha256', process.env.YUGU_SECRET_KEY).update(payload, 'utf8').digest('base64');
const ws = new WebSocket('wss://open.shengzhiai.com/api/v1/ws/evaluate?' + new URLSearchParams({ ...query, signature }));

const wav = await readFile('audio.wav');
const pcm = wav.subarray(wav.indexOf('data') + 8); // 16 kHz，16 位，单声道 PCM

ws.on('message', (data, isBinary) => {
  if (isBinary) return;
  const msg = JSON.parse(data.toString());
  if (msg.event === 'connected') {
    ws.send(JSON.stringify({ cmd: 'start', coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN', idempotencyKey: query.idempotencyKey }));
  } else if (msg.event === 'started') {
    for (let i = 0; i < pcm.length; i += 640) ws.send(pcm.subarray(i, i + 640)); // 每帧 640 字节，即 20 毫秒
    ws.send(JSON.stringify({ cmd: 'end' }));
  } else if (msg.event === 'result') {
    console.log('recordId', msg.recordId, '总分', msg.result.overall);
    ws.close();
  } else if (msg.event === 'error') {
    console.error('错误', msg.code, msg.message);
    ws.close();
  }
});
