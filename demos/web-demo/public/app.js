// Demo page logic: create, use, release. The SDK bundle comes from the installed npm package.
import { LogLevel, SDK_VERSION, YuguClient, YuguError, YuguRecorder, isRetryable } from '/sdk/yugu-sdk.mjs';

const $ = (id) => document.getElementById(id);
const logBox = $('log');
function log(...parts) {
  const line = `${new Date().toISOString().slice(11, 23)} ${parts.join(' ')}\n`;
  logBox.textContent = (logBox.textContent + line).split('\n').slice(-200).join('\n');
  logBox.scrollTop = logBox.scrollHeight;
}

let client = null;
let clientKey = '';
let recorder = null;
let lastAudio = null;
let session = null;

$('sdkVersion').textContent = SDK_VERSION;

fetch('/demo-config.json')
  .then((r) => r.json())
  .then((cfg) => {
    $('baseUrl').value = cfg.proxied ? location.origin : cfg.baseUrl;
    if (cfg.proxied) log('页面经演示服务转发到本地平台');
  })
  .catch(() => {
    $('baseUrl').value = 'https://open.shengzhiai.com';
  });

$('authMode').onchange = () => {
  const sig = $('authMode').value === 'signature';
  $('tokenBox').hidden = sig;
  $('sigBox').hidden = !sig;
};

function getClient() {
  const baseUrl = $('baseUrl').value.trim() || location.origin;
  const opts = {
    baseUrl,
    logLevel: LogLevel.INFO,
    logger: (level, tag, message) => log(`[${level}] ${tag}: ${message}`),
    eventListener: {
      onRetry: (op, attempt, delayMs, error) => log(`重试 ${op} 第 ${attempt} 次，${delayMs} ms 后，原因 ${error.code} ${error.message}`),
    },
  };
  if ($('authMode').value === 'token') opts.token = $('token').value.trim();
  else {
    opts.appKey = $('appKey').value.trim();
    opts.secretKey = $('secretKey').value.trim();
  }
  const key = JSON.stringify([baseUrl, opts.token, opts.appKey, opts.secretKey]);
  if (client && key === clientKey) return client;
  if (client) client.close();
  client = new YuguClient(opts);
  clientKey = key;
  return client;
}

function taskConfig() {
  const cfg = {
    coreType: $('coreType').value,
    referenceText: $('referenceText').value.trim(),
    language: $('language').value,
    includeReport: true,
  };
  if (cfg.coreType === 'open') cfg.taskType = 'free';
  return cfg;
}

function getRecorder() {
  if (!recorder) {
    recorder = new YuguRecorder({
      listener: {
        onStateChanged: (_old, state) => {
          $('recState').textContent = state;
        },
        onLevel: (level) => {
          $('level').value = level;
        },
        onError: (e) => log(`录音错误 ${e.code} ${e.message}`),
      },
    });
  }
  return recorder;
}

function showError(e) {
  if (e instanceof YuguError) {
    $('summary').textContent = `失败：${e.name} ${e.code} ${e.message}${isRetryable(e) ? '，可重试' : ''}`;
    log(`错误 ${JSON.stringify(e.toJSON())}`);
  } else {
    $('summary').textContent = `失败：${e && e.message ? e.message : e}`;
  }
}

const DIMS = [
  ['integrity', '完整度'],
  ['accuracy', '准确度'],
  ['fluency', '流利度'],
  ['tone', '声调'],
  ['rhythm', '节奏'],
  ['emotion', '情感表达'],
  ['readingSkill', '朗读技巧'],
];

function render(r) {
  $('summary').textContent = `总分 ${r.overall == null ? '无' : r.overall}，recordId ${r.recordId}${r.replayed ? '，平台重放了同一幂等键的结果' : ''}`;
  const table = $('dims');
  table.textContent = '';
  for (const [key, label] of DIMS) {
    if (r.dims[key] == null) continue;
    const tr = table.insertRow();
    tr.insertCell().textContent = label;
    tr.insertCell().textContent = String(r.dims[key]);
  }
  const words = $('words');
  words.textContent = '';
  for (const w of r.words) {
    const span = document.createElement('span');
    const score = w.scores ? w.scores.overall : null;
    span.textContent = w.word;
    span.title = `${w.word} ${score}`;
    span.className = score == null ? '' : score >= 85 ? 'good' : score >= 60 ? 'fair' : 'poor';
    words.appendChild(span);
  }
  const extra = [];
  if (r.connected) extra.push(`连读 ${r.connected.linking}，失爆 ${r.connected.elision}，弱读 ${r.connected.reduction}`);
  if (r.open && r.open.content) extra.push(`内容 ${r.open.content.overall}，语言 ${r.open.languageUse && r.open.languageUse.overall}，表达 ${r.open.delivery && r.open.delivery.overall}`);
  for (const w of r.warnings) extra.push(`平台警告 ${w.code} ${w.message}`);
  for (const w of r.localWarnings) extra.push(`本地预检 ${w.code} ${w.message}`);
  $('extra').textContent = extra.join('；');
}

$('recBtn').onclick = async () => {
  const rec = getRecorder();
  try {
    if (rec.getState() === 'RECORDING') {
      lastAudio = await rec.stopAndGetWav();
      $('player').src = URL.createObjectURL(lastAudio);
      $('recBtn').textContent = '开始录音';
      log(`录音完成 ${Math.round(rec.getDurationMs())} ms，${rec.isUsingAudioWorklet() ? 'AudioWorklet' : 'ScriptProcessor'}`);
    } else {
      await rec.start();
      $('recBtn').textContent = '停止录音';
    }
  } catch (e) {
    showError(e);
  }
};

$('file').onchange = () => {
  lastAudio = $('file').files[0] || null;
  if (lastAudio) $('player').src = URL.createObjectURL(lastAudio);
};

$('evalBtn').onclick = async () => {
  if (!lastAudio) {
    log('请先录音或选择音频文件');
    return;
  }
  $('summary').textContent = '评测中';
  try {
    render(await getClient().evaluate(lastAudio, taskConfig()));
  } catch (e) {
    showError(e);
  }
};

$('streamBtn').onclick = async () => {
  if (session) {
    await getRecorder().stop();
    session.end();
    $('streamBtn').textContent = '实时评测，边录边传';
    return;
  }
  try {
    session = getClient().streamEvaluate(taskConfig(), {
      onStateChanged: (_old, state) => {
        $('sessionState').textContent = state;
      },
      onReconnecting: (attempt, delayMs, cause) => log(`断线重连第 ${attempt} 次，${delayMs} ms 后，原因 ${cause.message}`),
      onReconnected: (attempt, info) => log(`重连成功，第 ${attempt} 次，重放 ${info.replayedBytes} 字节`),
      onWarning: (w) => log(`本地预检 ${w.code} ${w.message}`),
      onResult: (r) => render(r),
      onError: (e) => showError(e),
      onClosed: () => {
        session = null;
        $('streamBtn').textContent = '实时评测，边录边传';
      },
    });
    await getRecorder().start({ session });
    $('streamBtn').textContent = '结束并取结果';
  } catch (e) {
    if (session) session.cancel();
    session = null;
    showError(e);
  }
};

window.addEventListener('pagehide', () => {
  if (session) session.cancel();
  if (recorder) recorder.release();
  if (client) client.close();
});
