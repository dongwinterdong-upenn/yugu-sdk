// Build-time SVG diagrams. Colours come from CSS custom properties, so a diagram follows the page theme.
// Box sizes follow the text they hold: widths are estimated per character, CJK as one em, Latin as
// 0.56 em proportional or 0.6 em monospace. A label that would not fit stops the build.
import { esc } from './render.mjs';

const FS = 14;
const FS_SMALL = 13;
const LH = 21;

function textWidth(s, size = FS, mono = false) {
  let w = 0;
  for (const ch of String(s)) {
    if (/[⺀-鿿豈-﫿＀-￯　-〿]/.test(ch)) w += size;
    else w += size * (mono ? 0.6 : 0.56);
  }
  return Math.ceil(w);
}

const t = (x, y, s, { cls = 'dg-t', anchor = 'start', size } = {}) =>
  `<text class="${cls}" x="${x}" y="${y}" text-anchor="${anchor}"${size ? ` font-size="${size}"` : ''}>${esc(s)}</text>`;

function fail(name, msg) { throw new Error(`diagram ${name}: ${msg}`); }

function svg(w, h, body, label) {
  return `<figure class="diagram" role="img" aria-label="${esc(label)}"><div class="diagram-scroll"><svg viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" xmlns="http://www.w3.org/2000/svg">` +
    `<defs><marker id="dg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="dg-arrowhead" d="M0 0 L10 5 L0 10 z"/></marker>` +
    `<marker id="dg-arrow-open" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path class="dg-arrowline" d="M1 1 L9 5 L1 9"/></marker></defs>` +
    `${body}</svg></div></figure>`;
}

// Layered architecture: one band per layer, modules side by side, widths by weight.
function layers(spec) {
  const W = 860, PAD = 14, GAP = 10, LABEL_W = 96;
  let y = PAD;
  const parts = [];
  const md = [];
  for (const layer of spec.layers) {
    const innerW = W - PAD * 2 - LABEL_W - GAP - 10;
    const weights = layer.modules.map((m) => m.w || 1);
    const sum = weights.reduce((a, b) => a + b, 0);
    const free = innerW - GAP * (layer.modules.length - 1);
    const rows = Math.max(...layer.modules.map((m) => (m.items || []).length));
    const boxH = 34 + rows * (LH - 2) + 8;
    const bandH = boxH + 20;
    parts.push(`<rect class="dg-band${layer.accent ? ' dg-band-accent' : ''}" x="${PAD}" y="${y}" width="${W - PAD * 2}" height="${bandH}" rx="10"/>`);
    parts.push(t(PAD + 14, y + 30, layer.name, { cls: 'dg-t dg-layer' }));
    if (layer.sub) parts.push(t(PAD + 14, y + 30 + LH, layer.sub, { cls: 'dg-t dg-muted', size: FS_SMALL }));
    if (textWidth(layer.name, 14) + 20 > LABEL_W || (layer.sub && textWidth(layer.sub, FS_SMALL) + 20 > LABEL_W)) fail(spec.name, `layer label "${layer.name}" too wide`);
    md.push(`${layer.name}：` + layer.modules.map((m) => `${m.title}${m.items && m.items.length ? '，' + m.items.join('，') : ''}`).join('。') + '。');
    let x = PAD + LABEL_W + GAP;
    layer.modules.forEach((m, i) => {
      const colW = free * weights[i] / sum;
      const by = y + 10;
      parts.push(`<rect class="dg-box${m.accent ? ' dg-box-accent' : ''}" x="${x.toFixed(1)}" y="${by}" width="${colW.toFixed(1)}" height="${boxH}" rx="8"/>`);
      parts.push(t((x + 12).toFixed(1), by + 24, m.title, { cls: `dg-t dg-title${m.mono === false ? '' : ' dg-mono'}` }));
      // Identifiers and paths in monospace, Chinese descriptions in the body face.
      const itemMono = (it) => m.itemsMono !== false && !/[\u4e00-\u9fff]/.test(it);
      (m.items || []).forEach((it, k) => {
        parts.push(t((x + 12).toFixed(1), by + 24 + LH + k * (LH - 2), it, { cls: `dg-t dg-item${itemMono(it) ? ' dg-mono' : ''}`, size: FS_SMALL }));
      });
      const need = Math.max(textWidth(m.title, FS, m.mono !== false), ...(m.items || []).map((s) => textWidth(s, FS_SMALL, itemMono(s)))) + 24;
      if (need > colW) fail(spec.name, `"${m.title}" needs ${need}px, column is ${Math.round(colW)}px`);
      x += colW + GAP;
    });
    y += bandH + GAP;
  }
  return { html: svg(W, y - GAP + PAD, parts.join(''), spec.label), md: md.join('\n\n') };
}

// Sequence diagram: participants across the top, one message or note per row.
function sequence(spec) {
  const PAD = 14, HEAD_H = 40, ROW = 32;
  const W = 860;
  const n = spec.participants.length;
  const left = PAD + 86, right = W - PAD - 86;
  const colX = spec.participants.map((_, i) => left + i * ((right - left) / (n - 1)));
  const rows = [];
  const md = [];
  let y = PAD + HEAD_H + 30;
  for (const m of spec.messages) {
    if (m.note) {
      const [s0, s1] = m.span || [0, n - 1];
      const x1 = colX[s0] - 60, x2 = colX[s1] + 60;
      const need = textWidth(m.note, FS_SMALL) + 20;
      if (need > x2 - x1) fail(spec.name, `note "${m.note}" needs ${need}px, has ${x2 - x1}px`);
      rows.push(`<rect class="dg-note${m.kind ? ' dg-note-' + m.kind : ''}" x="${x1}" y="${y - 16}" width="${x2 - x1}" height="24" rx="6"/>`);
      rows.push(t((x1 + x2) / 2, y + 1, m.note, { cls: 'dg-t dg-note-t', anchor: 'middle', size: FS_SMALL }));
      md.push(`说明：${m.note}`);
      y += ROW;
      continue;
    }
    const a = colX[m.from], b = colX[m.to];
    const span = Math.abs(b - a);
    const need = textWidth(m.text, FS_SMALL, m.mono !== false) + 16;
    if (need > span) fail(spec.name, `message "${m.text}" needs ${need}px, span is ${span}px`);
    const dir = b > a ? 1 : -1;
    const cls = `dg-msg${m.reply ? ' dg-dashed' : ''}`;
    if (m.lost) {
      const end = a + (b - a) * 0.62;
      rows.push(`<line class="${cls}" x1="${a}" y1="${y}" x2="${end}" y2="${y}"/>`);
      rows.push(`<path class="dg-lost" d="M${end - 6} ${y - 6} l12 12 M${end + 6} ${y - 6} l-12 12"/>`);
    } else {
      rows.push(`<line class="${cls}" x1="${a}" y1="${y}" x2="${b - dir * 2}" y2="${y}" marker-end="url(#${m.reply ? 'dg-arrow-open' : 'dg-arrow'})"/>`);
    }
    rows.push(t((a + b) / 2, y - 8, m.text, { cls: `dg-t dg-msg-t${m.mono === false ? '' : ' dg-mono'}`, anchor: 'middle', size: FS_SMALL }));
    md.push(`${spec.participants[m.from]} 到 ${spec.participants[m.to]}：${m.text}${m.lost ? '，未送达' : ''}`);
    y += ROW;
  }
  const H = y + PAD - 12;
  const heads = spec.participants.map((p, i) => {
    const w = Math.max(132, textWidth(p, FS) + 32);
    return `<line class="dg-life" x1="${colX[i]}" y1="${PAD + HEAD_H}" x2="${colX[i]}" y2="${H - PAD}"/>` +
      `<rect class="dg-box dg-head" x="${colX[i] - w / 2}" y="${PAD}" width="${w}" height="${HEAD_H}" rx="8"/>` +
      t(colX[i], PAD + 25, p, { cls: 'dg-t dg-title', anchor: 'middle' });
  }).join('');
  return { html: svg(W, H, heads + rows.join(''), spec.label), md: md.map((s, i) => `${i + 1}. ${s}`).join('\n') };
}

// State machine: states placed by centre, edges drawn from the box geometry, so a wider state name
// moves its arrows with it.
function states(spec) {
  const parts = [];
  const B = {};
  for (const s of spec.states) {
    const w = Math.max(textWidth(s.name, FS, true) + 26, 92);
    B[s.name] = { x: s.x - w / 2, y: s.y - 18, w, h: 36, cx: s.x, cy: s.y, l: s.x - w / 2, r: s.x + w / 2, t: s.y - 18, b: s.y + 18 };
  }
  for (const g of spec.groups || []) {
    parts.push(`<rect class="dg-group" x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="12"/>`);
    parts.push(t(g.x + 14, g.y + 20, g.label, { cls: 'dg-t dg-muted', size: FS_SMALL }));
  }
  for (const e of spec.edges(B)) {
    parts.push(`<path class="dg-msg" d="${e.d}" fill="none"${e.arrow === false ? '' : ' marker-end="url(#dg-arrow)"'}/>`);
    if (e.label) {
      const size = e.mono ? 11 : FS_SMALL;
      if (e.room && textWidth(e.label, size, e.mono) + 6 > e.room) fail(spec.name, `edge label "${e.label}" needs more than ${e.room}px`);
      parts.push(t(e.lx, e.ly, e.label, { cls: `dg-t dg-edge${e.mono ? ' dg-mono' : ''}`, anchor: e.anchor || 'middle', size }));
    }
  }
  for (const s of spec.states) {
    const b = B[s.name];
    parts.push(`<rect class="dg-box dg-state${s.kind ? ' dg-state-' + s.kind : ''}" x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" rx="18"/>`);
    parts.push(t(s.x, s.y + 5, s.name, { cls: 'dg-t dg-title dg-mono', anchor: 'middle' }));
  }
  return { html: svg(spec.width, spec.height, parts.join(''), spec.label), md: spec.text.map((x) => `- ${x}`).join('\n') };
}

const DIAGRAMS = {
  architecture: () => layers({
    name: 'architecture',
    label: 'SDK 分层架构：应用代码调用 YuguClient 与实时会话，SDK 内部负责幂等，重试，重连，预检与签名，经 HTTPS 与 WSS 到开放平台',
    layers: [
      { name: '应用代码', sub: '接入方', modules: [
        { title: '服务端', mono: false, itemsMono: false, items: ['Java 11 及以上'] },
        { title: '移动端', mono: false, itemsMono: false, items: ['安卓 5.0 及以上', 'iOS 13 及以上'] },
        { title: '网页与小程序', mono: false, itemsMono: false, items: ['浏览器，Node.js，微信'] },
        { title: '声通接口代码', mono: false, items: ['com.stkouyu', 'STKouyuEngine'] },
      ] },
      { name: '公共接口', sub: 'SDK', accent: true, modules: [
        { title: 'YuguClient', accent: true, w: 1.35, items: ['evaluate()', 'evaluateCompat()', 'tts()', 'getReport()', 'streamEvaluate()', 'streamEvaluateCompat()'] },
        { title: '实时会话', mono: false, items: ['sendAudio()', 'end()', 'cancel()', 'getState()'] },
        { title: '录音器', mono: false, items: ['Recorder', 'YuguRecorder', 'createRecorder()'] },
        { title: '声通平替层', mono: false, items: ['SkEgnManager', 'KYTestEngine'] },
      ] },
      { name: '可靠性', sub: 'SDK 内部', modules: [
        { title: '幂等键', mono: false, items: ['Idempotency-Key', '32 位十六进制'] },
        { title: 'RetryPolicy', items: ['默认重试 2 次', '指数退避加抖动'] },
        { title: 'ReconnectPolicy', items: ['最多连续重连 8 次', 'REPLAY DROP FAIL'] },
        { title: '预检与错误分类', mono: false, items: ['90101 到 90105', '十六个类别'] },
      ] },
      { name: '传输', sub: 'SDK 内部', modules: [
        { title: 'HTTPS', items: ['multipart/form-data 与 JSON', 'HMAC-SHA256 签名头'] },
        { title: 'WSS', items: ['二进制音频帧', '每 15 秒心跳'] },
      ] },
      { name: '开放平台', sub: '服务端', modules: [
        { title: 'REST', w: 1.45, items: ['POST /api/v1/evaluate', 'POST /{coreType}', 'POST /api/v1/tts/generate', 'GET /api/v1/report/{recordId}'] },
        { title: 'WebSocket', items: ['/api/v1/ws/evaluate', '/{coreType}'] },
        { title: '幂等校验', mono: false, itemsMono: false, items: ['同一个键只评测一次', '首次结果保存 24 小时'] },
      ] },
    ],
  }),

  'ws-session': () => sequence({
    name: 'ws-session',
    label: '原生实时评测一轮会话的帧时序',
    participants: ['应用代码', 'SDK 实时会话', '开放平台'],
    messages: [
      { from: 0, to: 1, text: 'streamEvaluate(config, listener)' },
      { from: 1, to: 2, text: '握手 /api/v1/ws/evaluate?…&signature' },
      { from: 2, to: 1, reply: true, text: '{"event":"connected"}' },
      { from: 1, to: 2, text: '{"cmd":"start",…,"idempotencyKey"}' },
      { from: 2, to: 1, reply: true, text: '{"event":"started"}' },
      { from: 1, to: 0, reply: true, text: 'onStateChanged(STARTED)' },
      { from: 0, to: 1, text: 'sendAudio(pcm)' },
      { from: 1, to: 2, text: '二进制帧 640 字节，即 20 ms' },
      { note: '心跳每 15 秒一次，网页与小程序用应用层 ping，平台回 pong', span: [1, 2] },
      { from: 0, to: 1, text: 'end()' },
      { from: 1, to: 2, text: '{"cmd":"end"}' },
      { from: 2, to: 1, reply: true, text: '{"event":"result","eof":1,…}' },
      { from: 1, to: 0, reply: true, text: 'onResult(result)' },
    ],
  }),

  'ws-reconnect': () => sequence({
    name: 'ws-reconnect',
    label: '结束帧发出后断线，SDK 用同一个幂等键重连，整段重放，平台重放首次结果',
    participants: ['应用代码', 'SDK 实时会话', '开放平台'],
    messages: [
      { from: 1, to: 2, text: '二进制音频帧' },
      { from: 0, to: 1, text: 'end()' },
      { from: 1, to: 2, text: '{"cmd":"end"}' },
      { from: 2, to: 1, reply: true, lost: true, mono: false, text: '连接断开，终评未送达' },
      { from: 1, to: 0, reply: true, text: 'onStateChanged(RECONNECTING)' },
      { note: '退避等待 0.5，1，2，4 秒，最多连续 8 次', span: [1, 2] },
      { from: 1, to: 2, mono: false, text: '新握手，幂等键不变' },
      { from: 2, to: 1, reply: true, text: '{"event":"connected"}' },
      { from: 1, to: 2, mono: false, text: '开始帧，参数与幂等键不变' },
      { from: 2, to: 1, reply: true, text: '{"event":"started"}' },
      { from: 1, to: 2, mono: false, text: '重放这一轮的全部音频' },
      { from: 1, to: 2, text: '{"cmd":"end"}' },
      { note: '平台按幂等键重放首次结果，不重复评测，不重复计费', span: [1, 2], kind: 'accent' },
      { from: 2, to: 1, reply: true, text: '{"event":"result",…,"replayed":true}' },
      { from: 1, to: 0, reply: true, text: 'onResult(result)' },
    ],
  }),

  'rest-retry': () => sequence({
    name: 'rest-retry',
    label: '整段评测读取超时后用同一个幂等键重试',
    participants: ['应用代码', 'SDK', '开放平台'],
    messages: [
      { from: 0, to: 1, text: 'evaluate(audio, config)' },
      { from: 1, to: 2, text: 'POST /api/v1/evaluate，键 7f3a…' },
      { from: 2, to: 1, reply: true, lost: true, mono: false, text: '读取超时' },
      { note: '第 1 次重试前等待 200 ms，乘以 0.7 到 1.3 的随机系数', span: [1, 2] },
      { from: 1, to: 2, text: 'POST /api/v1/evaluate，键 7f3a…' },
      { note: '同一个键的首次请求已完成，平台不再评测，不再计费', span: [1, 2], kind: 'accent' },
      { from: 2, to: 1, reply: true, text: '200  Idempotency-Replayed: true' },
      { from: 1, to: 0, reply: true, mono: false, text: '评测结果，重放标记与尝试次数 2' },
    ],
  }),

  'session-states': () => states({
    name: 'session-states',
    label: '实时评测会话状态：IDLE 到 ENDING 与 RECONNECTING 为活动状态，任一活动状态可进入 FAILED 或 CANCELLED，COMPLETED，FAILED，CANCELLED 之后进入 CLOSED',
    width: 860, height: 372,
    groups: [{ x: 18, y: 18, w: 824, h: 196, label: '活动状态' }],
    states: [
      { name: 'IDLE', x: 80, y: 66 },
      { name: 'CONNECTING', x: 240, y: 66 },
      { name: 'CONNECTED', x: 416, y: 66 },
      { name: 'STARTED', x: 590, y: 66 },
      { name: 'ENDING', x: 766, y: 66 },
      { name: 'RECONNECTING', x: 416, y: 172, kind: 'warn' },
      { name: 'FAILED', x: 300, y: 270, kind: 'bad' },
      { name: 'CANCELLED', x: 520, y: 270 },
      { name: 'COMPLETED', x: 766, y: 270, kind: 'ok' },
      { name: 'CLOSED', x: 520, y: 340, kind: 'end' },
    ],
    edges: (B) => {
      const row = (from, to, label) => ({ d: `M${B[from].r} 66 H${B[to].l - 2}`, label, mono: true, lx: (B[from].r + B[to].l) / 2, ly: 57, room: B[to].l - B[from].r });
      const busY = 122;
      return [
        row('IDLE', 'CONNECTING'),
        row('CONNECTING', 'CONNECTED', 'connected'),
        row('CONNECTED', 'STARTED', 'started'),
        row('STARTED', 'ENDING', 'end()'),
        { d: `M${B.CONNECTING.cx + 22} ${B.CONNECTING.b} V${busY} H${B.ENDING.cx - 24} V${B.ENDING.b}`, arrow: false },
        { d: `M${B.CONNECTED.cx} ${B.CONNECTED.b} V${busY}`, arrow: false },
        { d: `M${B.STARTED.cx} ${B.STARTED.b} V${busY}`, arrow: false },
        { d: `M${B.RECONNECTING.cx + 24} ${busY} V${B.RECONNECTING.t - 2}`, label: '断线，心跳超时，可重试的错误帧', lx: 610, ly: 146 },
        { d: `M${B.RECONNECTING.l} ${B.RECONNECTING.cy} H${B.CONNECTING.cx - 26} V${B.CONNECTING.b + 2}`, label: '退避后重连', lx: B.CONNECTING.cx - 34, ly: 146, anchor: 'end' },
        { d: `M${B.FAILED.cx} 214 V${B.FAILED.t - 2}`, label: '不可重试的错误，重连用尽 90006', lx: B.FAILED.cx - 10, ly: 240, anchor: 'end' },
        { d: `M${B.CANCELLED.cx} 214 V${B.CANCELLED.t - 2}`, label: 'cancel()', mono: true, lx: B.CANCELLED.cx + 10, ly: 240, anchor: 'start' },
        { d: `M${B.ENDING.cx + 26} ${B.ENDING.b} V${B.COMPLETED.t - 2}`, label: '收到终评', lx: B.ENDING.cx + 36, ly: 240, anchor: 'start' },
        { d: `M${B.FAILED.cx} ${B.FAILED.b} V${B.CLOSED.cy} H${B.CLOSED.l - 2}` },
        { d: `M${B.CANCELLED.cx} ${B.CANCELLED.b} V${B.CLOSED.t - 2}` },
        { d: `M${B.COMPLETED.cx} ${B.COMPLETED.b} V${B.CLOSED.cy} H${B.CLOSED.r + 2}` },
      ];
    },
    text: [
      'IDLE 到 CONNECTING：调用 streamEvaluate 后建立连接，iOS 为调用 start 后。',
      'CONNECTING 到 CONNECTED：平台发来 connected，SDK 随即发出开始帧。',
      'CONNECTED 到 STARTED：平台回 started，开始送音频。',
      'STARTED 到 ENDING：调用 end，结束帧已发出。',
      'ENDING 到 COMPLETED：收到终评。',
      'CONNECTING，CONNECTED，STARTED，ENDING 到 RECONNECTING：断线，心跳超时，连接超时，终评超时或收到可重试的错误帧。',
      'RECONNECTING 到 CONNECTING：退避等待后重新连接。',
      '任一活动状态到 FAILED：不可重试的错误，或重连次数用尽，错误码 90006。',
      '任一活动状态到 CANCELLED：调用方取消或关闭客户端。',
      'COMPLETED，FAILED，CANCELLED 到 CLOSED：连接释放，会话结束。',
    ],
  }),
};

export function diagram(name) {
  const f = DIAGRAMS[name];
  if (!f) throw new Error(`unknown diagram ${name}`);
  return f();
}
