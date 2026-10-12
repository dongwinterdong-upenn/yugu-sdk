// 实时朗读进度，直连协议。Node.js 18 及以上，依赖 npm install ws
import crypto from 'node:crypto';
import { readFile } from 'node:fs/promises';
import WebSocket from 'ws';

const refText = '我是中国人，我是中国人。我们都是中国人。中华民族是一家人。';
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
    const idempotencyKey = crypto.randomBytes(16).toString('hex');
    ws.send(JSON.stringify({ refText, realtime_feedback: true, idempotencyKey }));
  } else if (msg.event === 'started') {
    // 按录音的速度送音频，每 20 毫秒 640 字节，读完发结束帧
    let i = 0;
    const timer = setInterval(() => {
      if (i < pcm.length) {
        ws.send(pcm.subarray(i, i + 640));
        i += 640;
        return;
      }
      clearInterval(timer);
      ws.send(JSON.stringify({ cmd: 'end' }));
    }, 20);
  } else if (msg.eof === 0 && msg.result.words) {
    // 已读且对上的字原样输出，已读未对上的字加方括号，未读的字输出为点
    const line = msg.result.words.map((w) => (w.matched ? w.word : w.read ? `[${w.word}]` : '·')).join('');
    console.log(`${msg.result.readUnits}/${msg.result.totalUnits}`, line);
  } else if (msg.eof === 1) {
    console.log('recordId', msg.recordId, '总分', msg.result.overall);
    ws.close();
  } else if (msg.event === 'error') {
    console.error('错误', msg.code, msg.message);
    ws.close();
  }
});
