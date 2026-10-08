# 优谷雅言语音评测 PC Web SDK

`@shengzhiai/yugu-web-sdk` 是优谷雅言语音评测平台的浏览器端 SDK，版本 `2.0.0`，按 Apache-2.0 许可发布。SDK 是推荐的接入方式，REST 与 WebSocket 直连只作为没有 SDK 的语言的兜底方式。同一个包也可以在 Node.js 18 及以上版本中运行。

SDK 封装整段评测，声通兼容评测，实时流式评测，语音合成，报告查询与浏览器录音，内置幂等键，重试退避，断线重连，类型化错误，音频预检与完整的 TypeScript 类型。运行时没有任何第三方依赖。

## 能力一览

| 能力 | 接口 | 平台路径 |
|---|---|---|
| 整段评测 | `evaluate` | `POST /api/v1/evaluate` |
| 声通兼容整段评测 | `evaluateCompat` | `POST /{coreType}` |
| 实时流式评测 | `streamEvaluate` | `WS /api/v1/ws/evaluate` |
| 声通兼容实时评测 | `streamEvaluateCompat` | `WS /{coreType}` |
| 语音合成 | `tts` | `POST /api/v1/tts/generate` |
| 报告查询 | `getReport` | `GET /api/v1/report/{recordId}` |
| 浏览器录音 | `YuguRecorder` | 无，本地采集 16 kHz 单声道 16 位 PCM |

## 安装

### npm 仓库安装

SDK 发布在优谷雅言自建 npm 仓库 `https://open.shengzhiai.com/npm/`。仓库公开读取，安装不需要账号或令牌。在项目根目录的 `.npmrc` 写入一行：

```ini
@shengzhiai:registry=https://open.shengzhiai.com/npm/
```

然后安装指定版本：

```bash
npm install @shengzhiai/yugu-web-sdk@2.0.0
```

`pnpm` 与 Yarn 1 读取同一个 `.npmrc`，Yarn 2 及以上版本在 `.yarnrc.yml` 的 `npmScopes` 里配置同一个地址。查看全部可用版本用 `npm view @shengzhiai/yugu-web-sdk versions`，回退到历史版本时把 `@2.0.0` 换成目标版本号。

### script 标签引入

安装后的 `dist` 目录带有 UMD 构建，不需要打包工具。把 `node_modules/@shengzhiai/yugu-web-sdk/dist/yugu-sdk.umd.min.js` 复制到站点静态目录后引用，全局对象为 `YuguSDK`：

```html
<script src="/static/yugu-sdk.umd.min.js"></script>
<script>
  const client = new YuguSDK.YuguClient({ token: '由接入方后端签发的 JWT' });
</script>
```

### ESM 与打包工具

```js
import { YuguClient, YuguRecorder } from '@shengzhiai/yugu-web-sdk';
```

包同时提供 ESM 构建 `dist/yugu-sdk.mjs` 与 CommonJS 可用的 UMD 构建 `dist/yugu-sdk.umd.js`，`package.json` 的 `exports` 按导入方式自动选择。Node.js 的 CommonJS 项目用 `require('@shengzhiai/yugu-web-sdk')`。

## 五分钟快速开始

1. 准备环境：Node.js 20 或更高版本，一段 3 秒左右的 16 kHz 单声道 WAV 录音，沙箱环境的 appKey 与 secretKey。沙箱密钥的申请方式与每日额度见 [`SANDBOX.md`](../SANDBOX.md)，Node.js 18 的做法见 [Node.js 用法](README.md#nodejs-用法)。

2. 新建工程，安装 SDK：

```bash
mkdir yugu-quickstart && cd yugu-quickstart
npm init -y
echo "@shengzhiai:registry=https://open.shengzhiai.com/npm/" > .npmrc
npm install @shengzhiai/yugu-web-sdk@2.0.0
```

3. 新建 `quickstart.mjs`：

```js
import { readFile } from 'node:fs/promises';
import { YuguClient } from '@shengzhiai/yugu-web-sdk';

const client = new YuguClient({
  appKey: process.env.YUGU_APP_KEY,
  secretKey: process.env.YUGU_SECRET_KEY,
});

const audio = await readFile(process.argv[2] || 'hello.wav');
const result = await client.evaluate(audio, {
  coreType: 'sentence',
  referenceText: '今天天气很好',
  language: 'zh-CN',
});
console.log('总分', result.overall, '各维度', result.dims);
await client.close();
```

4. 运行脚本，查看分数：

```bash
YUGU_APP_KEY=沙箱appKey YUGU_SECRET_KEY=沙箱secretKey node quickstart.mjs hello.wav
```

5. 浏览器里试用时，在同一目录新建 `index.html`，用任意静态服务器打开，例如 `python3 -m http.server 8080` 后访问 `http://localhost:8080/`：

```html
<!doctype html>
<meta charset="utf-8" />
<input type="file" id="f" accept=".wav,.mp3" />
<pre id="out"></pre>
<script src="node_modules/@shengzhiai/yugu-web-sdk/dist/yugu-sdk.umd.min.js"></script>
<script>
  // 仅限沙箱试用。正式环境的浏览器页面改用后端签发的 token。
  const client = new YuguSDK.YuguClient({ appKey: '沙箱appKey', secretKey: '沙箱secretKey' });
  document.getElementById('f').onchange = async (e) => {
    const r = await client.evaluate(e.target.files[0], { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' });
    document.getElementById('out').textContent = '总分 ' + r.overall;
  };
</script>
```

包含录音，实时评测与结果展示的完整页面见仓库 `demos/web-demo` 目录。

## 鉴权

| 方式 | 适用场景 | 构造参数 |
|---|---|---|
| token | 浏览器正式环境，JWT 由接入方后端签发 | `token: 'jwt'`，或返回 JWT 的函数，每次请求与每次连接前调用 |
| 签名 | Node.js 服务端，沙箱试用 | `appKey` 加 `secretKey`，SDK 按平台规则计算 HMAC-SHA256 签名 |

两种方式只能选一种。secretKey 写进浏览器页面等于公开密钥，正式环境的浏览器页面一律使用 token。声通兼容整段评测 `evaluateCompat` 只支持签名鉴权，平台要求请求头 `X-App-Key`，token 模式下调用会在发出请求前抛出 `InvalidParameterException`。签名规则：整段评测签名 `config` 段的 JSON 原文，声通兼容评测签名实际发送的文本字段，语音合成签名请求体顶层的标量字段，报告查询签名空集，WebSocket 握手签名除 `signature` 以外的全部 query 参数。浏览器中签名依赖 Web Crypto，页面必须运行在 https 或 localhost。

## 接口一览

### 客户端方法

| 方法 | 返回 | 说明 |
|---|---|---|
| `new YuguClient(options)` | 客户端 | 缺少鉴权信息时抛出 `InvalidParameterException` |
| `evaluate(audio, config, options)` | `Promise<EvalResult>` | 原生整段评测 |
| `evaluateCompat(coreType, params, audio, options)` | `Promise<EvalResult>` | 声通兼容整段评测，`coreType` 取声通命名 |
| `tts(request, options)` | `Promise<TtsResult>` | 语音合成，`absoluteUrl` 可直接播放 |
| `getReport(recordId, options)` | `Promise<ReportData>` | 报告查询 |
| `streamEvaluate(config, listener, options)` | `YuguStreamSession` | 原生实时评测 |
| `streamEvaluateCompat(coreType, params, listener, options)` | `YuguStreamSession` | 声通兼容实时评测 |
| `resolveTtsUrl(url)` 与 `resolveAudioUrl(url)` | 字符串 | 把相对音频地址转成可播放地址 |
| `close()` | `Promise<void>` | 释放客户端，可重复调用 |
| `isClosed()` | 布尔值 | 是否已经关闭 |

### 客户端选项

| 选项 | 默认值 | 含义 |
|---|---|---|
| `baseUrl` | `https://open.shengzhiai.com` | REST 地址 |
| `wsBaseUrl` | `wss://open.shengzhiai.com` | WebSocket 地址，只给 `baseUrl` 时由 `baseUrl` 推出 |
| `token` 或 `appKey` 加 `secretKey` | 必填 | 鉴权信息 |
| `connectTimeoutMs` | `10000` | WebSocket 建连到会话开始的时限 |
| `readTimeoutMs` | `120000` | 单次 REST 请求时限 |
| `totalTimeoutMs` | `300000` | 一次逻辑调用含全部重试与等待的总时限 |
| `retry` | 见重试一节 | 重试策略，`false` 关闭重试 |
| `reconnect` | 见实时评测一节 | 重连策略，`false` 关闭重连 |
| `audioBufferPolicy` | `REPLAY` | 重连时的音频缓冲策略 |
| `heartbeat` | `auto` | 应用层心跳，可取 `auto`，`true`，`false` |
| `resultTimeoutMs` | `300000` | 发出结束帧后等待终评结果的时限 |
| `autoIdempotencyKey` | `true` | 调用方没给幂等键时自动生成 |
| `logLevel` | `WARN` | 日志级别 |
| `logger` | 控制台 | 日志输出函数 |
| `eventListener` | 无 | 指标回调 |
| `audioPrecheck` | `WARN` | 音频预检模式 |
| `strictAudio` | `false` | 结果带警告码 1001 时抛出 `AudioQualityException` |
| `userAgent` | `yugu-web-sdk/2.0.0` | 浏览器中通过 `X-Yugu-SDK` 请求头发送，其他环境通过 `User-Agent` 发送 |
| `fetch`，`WebSocket`，`crypto` | 全局对象 | 注入自定义实现，Node.js 中注入 `ws` 包的 WebSocket |
| `random` | `Math.random` | 退避抖动的随机源，测试中可传入固定种子的随机函数 |

### 单次调用选项

REST 调用的最后一个参数可传 `idempotencyKey`，`timeoutMs`，`totalTimeoutMs`，`retry`，`signal`，`headers`。`signal` 接受 `AbortSignal`，中止后调用以 `RequestCancelledException` 结束。整段评测另外接受 `image`，`filename`，`contentType`，`audioFormat`，`sampleRate`，`audioPrecheck`，`strictAudio`。实时评测的选项见实时评测一节。

## TypeScript

类型声明随包发布在 `types/index.d.ts`，`package.json` 的 `types` 与 `exports` 已指向声明文件，安装后不需要额外的类型包。全部公开类，方法，请求与响应模型，配置项与错误类都有声明，`strict` 与 `noImplicitAny` 下没有隐式 `any`，字段拼错或类型不符在编译期报错。

```ts
import { YuguClient, type EvalResult, type EvaluateConfig } from '@shengzhiai/yugu-web-sdk';

const config: EvaluateConfig = { coreType: 'passage', referenceText: '今天天气很好。我们一起去公园散步。', paragraphNeedWordScore: 1 };
const result: EvalResult = await client.evaluate(file, config);
const firstSentence = result.sentences[0]?.details?.map((w) => `${w.word}:${w.scores.overall}`);
```

声明文件同时适用于带 DOM 库的浏览器工程与不带 DOM 库的 Node.js 工程。CI 逐项比对运行时导出与声明，类，枚举，结果模型的字段不一致时构建失败。

## 结果模型

`evaluate`，`evaluateCompat` 与实时会话的结果统一为 `EvalResult`，常用字段见表，平台原始响应保留在 `raw`：

| 字段 | 含义 |
|---|---|
| `overall` | 总分，连读题型取 `result.connected_overall` |
| `dims` | 维度分：`integrity` 完整度，`accuracy` 准确度，`pronunciation` 发音，`fluency` 流利度，`tone` 声调，`rhythm` 节奏，`emotion` 情感表达，`readingSkill` 朗读技巧，没有的维度为 `null` |
| `words`，`sentences`，`paragraphs` | 逐字逐词与逐句结果 |
| `connected` | 连读题型的专项分，其他题型为 `null` |
| `open` | 开放题型的专项分，其他题型为 `null` |
| `asrText`，`report`，`standardAudio` | 识别文本，报告，标准示范音，平台没有返回或返回空对象时为 `null` |
| `warnings` | 平台音频质量警告 `{code, message}`，不作为异常抛出 |
| `localWarnings` | 本地预检警告 |
| `recordId`，`idempotencyKey`，`replayed` | 记录号，本次调用的幂等键，是否为平台重放的首次结果 |
| `raw` | 平台原始 JSON |

各题型的取分字段：

| 题型 | 总分 | 专项字段 |
|---|---|---|
| `word` 单词 | `overall` | `dims`，`words[].scores`，`words[].phonemes`，`words[].phonics` |
| `sentence` 句子 | `overall` | `dims`，`words[]`，`sentences[].scores`，中文另有 `dims.tone` 与 `raw.result.rear_tone` |
| `passage` 段落 | `overall` | `sentences[]`，`paragraphNeedWordScore` 为 1 时 `sentences[].details[]` 给出逐字分 |
| `connected` 连读 | `overall` 等于 `connected.overall` | `connected.linking`，`connected.rhythm`，`connected.elision`，`connected.reduction`，`connected.nBoundaries`，`connected.boundaries[]` 的 `between`，`tags`，`realized`，`start_ms`，`end_ms` |
| `open` 开放题 | `overall` | `open.content` 的 `relevance`，`coherence`，`task_achievement`，`open.languageUse`，`open.delivery` 的 `fluency`，`pronunciation`，`speech_rate`，`speech_rate_label`，`n_pauses`，`open.transcript`，`open.feedback`，`open.openTaskAudit` |
| `alpha` 字母 | `overall` | `dims`，`words[]`，`asrText.mismatches` |
| `pinyin` 拼音 | `overall` | `dims`，`words[].pinyin`，`sentences[].details[]` |

声通兼容结果的取分字段与同名原生题型一致，`mode` 为 `compat`。全部字段的含义见 [`RESULTS.md`](../RESULTS.md)，线上真实返回的样例在 `spec/fixtures/platform/`，SDK 的解析测试逐一覆盖这些样例。

## 错误处理

SDK 抛出或回调的错误都是 `YuguError` 的子类，可以用 `instanceof` 分支处理。每个错误带有 `category` 类别，`code` 原始错误码，`httpStatus` HTTP 状态，`retryable` 是否可重试，`idempotencyKey`，`recordId`，`attempts` 尝试次数，`rawBody` 截断到 4 KB 的响应体，`retryAfterMs`，`traceId` 与 `cause`。

| 错误类 | 类别 | 典型错误码 | 处置 |
|---|---|---|---|
| `NetworkException` | `NETWORK` | 90001，90006，90011 | 网络问题，提示检查网络 |
| `RequestTimeoutException` | `TIMEOUT` | 90002，90007 | 超时，可重试 |
| `AuthException` | `AUTH` | 40100，2001 到 2011 | 检查凭据与系统时钟 |
| `PermissionException` | `PERMISSION` | 40300，1004，1005，90201 | 权限或账户状态问题，麦克风权限被拒 |
| `InvalidParameterException` | `INVALID_PARAM` | 40001，90010 | 修正请求参数，不要重试 |
| `NotFoundException` | `NOT_FOUND` | 40400 | 资源不存在 |
| `ConflictException` | `CONFLICT` | 40901，40903 | 40901 用同一个键稍后重试，40903 说明键被另一个请求占用 |
| `RateLimitException` | `RATE_LIMIT` | 42900，42901，3001 到 3003 | 按 `retryAfterMs` 稍后重试 |
| `QuotaExceededException` | `QUOTA` | 40902，42902，42903，1310 | 额度用完，提示充值或明天再试 |
| `ServerException` | `SERVER` 与 `UPSTREAM` | 50000，50200 | 服务端临时故障，可重试 |
| `AudioQualityException` | `AUDIO` | 90101 到 90105，1001 | 提示用户重录 |
| `IllegalSessionStateException` | `STATE` | 90004，90009，90202 | 调用顺序或资源状态问题 |
| `RequestCancelledException` | `CANCELLED` | 90003 | 调用方主动取消 |
| `ProtocolViolationException` | `PROTOCOL` | 90005 | 响应无法解析，带上 `traceId` 联系技术支持 |

`isRetryable(error)` 是重试循环，实时重连与调用方共用的判定函数。规则按顺序匹配。本地码只有 90001，90002，90007 可重试。平台业务码按错误码表的可重试标记判定。没有业务码时 HTTP 408，425，429，500，502，503，504 可重试。错误码按出现的位置查表：错误响应与错误帧里的码查服务端错误码表，所以 1004 与 1005 是账户状态错误，结果 `warning` 里的码用 `YuguErrors.fromWarning` 查警告码表，9 开头的五位码查本地错误码表。没有业务码，HTTP 状态又不在兜底表里时，其余 4xx 归入 `INVALID_PARAM`，其余 5xx 归入 `SERVER`，其他状态归入 `UNKNOWN`。`YuguErrors.fromCode(code)` 按错误码构造对应的错误对象，`ErrorCodes` 给出全部错误码常量，`WarningCode` 给出全部警告码常量，完整的错误码表见仓库根目录 `ERRORS.md`。

```js
try {
  await client.evaluate(file, config);
} catch (e) {
  if (e instanceof AudioQualityException) showRerecordHint(e.message);
  else if (e instanceof QuotaExceededException) showQuotaPage();
  else if (isRetryable(e)) showRetryButton();
  else reportToMonitoring(e.toJSON());
}
```

## 重试与幂等

### 重试策略

| 字段 | 默认值 |
|---|---|
| `maxRetries` | `2`，共 3 次尝试 |
| `initialDelayMs` | `200` |
| `multiplier` | `2` |
| `maxDelayMs` | `4000` |
| `jitter` | `0.3`，即正负 30% |
| `respectRetryAfter` | `true` |
| `maxRetryAfterMs` | `30000` |

```
delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))
响应带 Retry-After 时：delay = max(delay, min(retryAfterMs, maxRetryAfterMs))
now + delay 超过 totalTimeoutMs 截止时间时停止重试，抛出最后一次错误
```

默认间隔约为 200 ms 与 400 ms，各自上下浮动 30%。参数错误与鉴权失败这类不可重试的错误立即抛出，不发生任何重试。每次重试先调用 `eventListener.onRetry(op, attempt, delayMs, error)`，再输出一条 WARN 日志，例如 `retry 1/2 in 231 ms: HTTP 503 code=50200`。

### 幂等键

写操作 `evaluate`，`evaluateCompat`，`tts` 携带请求头 `Idempotency-Key`，实时会话在握手 query 与开始帧里携带 `idempotencyKey`。

- 调用方没有给键时，SDK 为每次逻辑调用生成 32 位小写十六进制的随机键，同一次调用的全部重试与重连复用同一个键。
- 调用方可以用 `idempotencyKey` 选项指定键，取值为 1 到 200 个可见 ASCII 字符，不合规时在发出请求前抛出 `InvalidParameterException`，错误码 90010。
- 键的作用域是同一个 appKey，token 鉴权时是同一个用户，有效期 24 小时。
- 同一个键加同样的请求再次提交时，平台返回首次的成功结果，不再计费，响应头带 `Idempotency-Replayed: true`，结果的 `replayed` 为 `true`。
- 首次请求仍在处理时，平台最多等待 30 秒后返回首次结果，或者返回 409 错误码 40901 与 `Retry-After`，SDK 按重试策略用同一个键重试。
- 同一个键用于内容不同的请求时，平台返回 409 错误码 40903，不可重试。首次请求失败时平台释放这个键，可以用同一个键重新提交。
- `getReport` 是天然幂等的查询，不带键也会重试。关闭 `autoIdempotencyKey` 又没有传键时，写操作不重试，DEBUG 日志记录原因。

## 实时评测与重连

### 会话状态

```
IDLE -> CONNECTING -> CONNECTED -> STARTED -> ENDING -> COMPLETED -> CLOSED
CONNECTING、CONNECTED、STARTED、ENDING -> RECONNECTING -> CONNECTING ...
任意未结束状态 -> FAILED -> CLOSED
任意未结束状态 -> CANCELLED -> CLOSED
```

`session.getState()` 的返回值始终等于最近一次 `onStateChanged` 通知的状态，`session.isActive()` 在会话结束前为 `true`。

### 回调与保证

`streamEvaluate` 的监听对象必须提供 `onResult` 与 `onError`，其余回调可选：`onStateChanged(old, new)`，`onConnected()`，`onStarted()`，`onPartial(partial)`，`onReconnecting(attempt, delayMs, cause)`，`onReconnected(attempt, info)`，`onWarning(warning)`，`onClosed(code, reason)`。

- 每个会话恰有一次 `onResult` 或 `onError`，调用 `cancel()` 之后两者都不再触发。
- `onClosed` 总是最后一个回调。
- 同一会话的回调不会嵌套执行，回调里调用 `cancel()` 或 `end()` 产生的事件在当前回调返回后依次送达。
- `onConnected` 与 `onStarted` 只在首次连接时触发，重连成功时触发 `onReconnected`。
- 声通兼容会话的参数帧带 `realtime_feedback: true` 时，进度帧 `{"eof":0,"result":{"bytes":n}}` 送到 `onPartial({bytes})`。原生会话没有进度帧。

### 重连策略

传输失败，异常关闭，心跳超时，连接超时，终评超时与可重试的服务端错误帧会触发重连。服务端不可重试的错误帧，1000 正常关闭但没有结果，握手被拒绝的鉴权错误，以及重连无法改变结果的关闭码 1002，1003，1007，1008，1009，1010 与 4000 到 4999 直接进入 FAILED。1009 表示单帧超过平台上限 128 KB。

| 字段 | 默认值 |
|---|---|
| `enabled` | `true` |
| `maxAttempts` | `8`，连续失败的次数 |
| `initialDelayMs` | `500` |
| `multiplier` | `2` |
| `maxDelayMs` | `4000` |
| `jitter` | `0.3` |

默认等待约 0.5，1，2，4，4，4，4，4 秒，各自上下浮动 30%，合计约 23.5 秒，足以覆盖 10 秒的断网。重连成功后连续失败次数清零，下一次断线的第一次重连重新编号为 1。服务端反复接受连接又立即断开时，一个会话累计最多重连 `maxAttempts` 的 3 倍次数，之后以 90006 结束，不会无限重连。连续失败次数用尽时 `onError` 收到 `NetworkException`，错误码 90006，`cause` 为最后一次失败的原因。平台不支持会话续传，重连后是一个新的服务端会话，SDK 用同一个幂等键与同样的参数重新开始，所以重连不会重复计费。没有幂等键的会话不重连。

### 音频缓冲策略

| 策略 | 重连时的处理 |
|---|---|
| `REPLAY`，默认 | 保留这次会话发送过的全部音频，上限 10 MB。重连后重发开始帧，重放全部音频，再补发重连期间送入的音频，`end()` 已调用时重发结束帧。超过上限后的下一次重连以 90008 失败 |
| `DROP` | 不缓冲。断线前发出的音频与重连期间送入的音频都丢弃，新会话只评测重连之后的音频，`onReconnected` 的 `info.droppedBytes` 给出丢弃的字节数，`sendAudio` 对丢弃的音频返回 `false` |
| `FAIL` | 不重连，传输失败直接进入 FAILED，回调 `onError` |

### 心跳

浏览器无法发送协议层 ping，SDK 在 CONNECTED 与 STARTED 状态每 15 秒发送应用层心跳 `{"cmd":"ping"}`，平台回复 `{"event":"pong"}`。连续 30 秒收不到任何回复视为连接失效，触发重连。ENDING 状态不发心跳，平台此时正在评测，SDK 依靠 `resultTimeoutMs` 与连接关闭事件判断。

平台在 `2.0.0` 发布期间逐步上线心跳支持。没有心跳支持的早期声通兼容服务会把 ping 当成新的参数帧，清空已收音频，所以 `heartbeat` 默认取 `auto`：

- 原生会话进入 STARTED 后发送一次探测 ping。平台回复 pong 时，同一客户端的全部会话开启心跳。平台回复错误帧 “unknown cmd” 时，这个回复不算错误，本会话关闭心跳，同一客户端之后的会话不再探测。
- 声通兼容会话在同一客户端收到过 pong 之后才发送心跳，在此之前不发送任何 ping。
- `heartbeat: true` 表示调用方确认平台支持心跳，原生与兼容会话都从连接起发送心跳。`heartbeat: false` 关闭心跳。

### 超时

| 选项 | 默认值 | 覆盖的阶段 |
|---|---|---|
| `connectTimeoutMs` | `10000` | 握手，收到 connected，收到 started |
| `heartbeatIntervalMs` 与 `heartbeatTimeoutMs` | `15000` 与 `30000` | CONNECTED 与 STARTED |
| `resultTimeoutMs` | `300000` | 发出结束帧之后 |

这些选项与 `reconnect`，`audioBufferPolicy`，`maxReplayBytes`，`audioPrecheck`，`sampleRate`，`idempotencyKey`，`signal` 都可以在 `streamEvaluate` 的第三个参数里按会话覆盖。`query` 选项附加的握手参数参与签名。

### 异常路径的清理

会话进入 COMPLETED，FAILED 或 CANCELLED 后，SDK 关闭 WebSocket，清理全部定时器与缓冲区，然后进入 CLOSED，回调 `onClosed`。`cancel()` 与 `close()` 可以重复调用。`client.close()` 取消全部未结束的会话，中止进行中的 REST 调用，之后的调用抛出 `IllegalSessionStateException`，错误码 90004。录音器在 `stop()`，`release()` 与任何错误路径上都释放麦克风与 AudioContext。

## 生命周期示例

创建客户端与录音器，边录边传做实时评测，用完释放：

```js
import { YuguClient, YuguRecorder } from '@shengzhiai/yugu-web-sdk';

// 创建
const client = new YuguClient({ token: () => fetch('/api/yugu-token').then((r) => r.text()) });
const recorder = new YuguRecorder({
  listener: {
    onStateChanged: (_old, state) => console.log('录音器', state),
    onLevel: (level) => meter.value = level,
    onError: (e) => console.warn('录音出错', e.code, e.message),
  },
});

// 使用
async function readOnce(referenceText) {
  const session = client.streamEvaluate(
    { coreType: 'sentence', referenceText, language: 'zh-CN' },
    {
      onStateChanged: (_old, state) => (statusLabel.textContent = state),
      onReconnecting: (attempt, delayMs) => console.log('重连中', attempt, delayMs),
      onResult: (result) => console.log('总分', result.overall),
      onError: (error) => console.warn('评测失败', error.code, error.message),
    },
  );
  await recorder.start({ session });
  await waitForUserToFinish();
  await recorder.stop();
  session.end();
  return session.waitForResult();
}

// 释放
window.addEventListener('pagehide', () => {
  recorder.release();
  client.close();
});
```

`recorder.release()` 停止全部麦克风轨道，关闭 AudioContext，丢弃录音与监听器，可以重复调用，调用时不抛出异常。`recorder.setListener(null)` 只注销监听器。录音器状态为 IDLE，RECORDING，PAUSED，STOPPED，RELEASED，`pause()` 与 `resume()` 用于暂停和继续，`stopAndGetWav()` 返回 16 kHz 单声道 16 位 WAV。录音器优先使用 AudioWorklet，浏览器不支持或内容安全策略禁止 blob 地址时自动改用 ScriptProcessor，严格内容安全策略的站点可以把 `dist/yugu-pcm-worklet.js` 放到同源路径，再通过 `workletModuleUrl` 指定。

## 音频预检

整段评测在上传前检查 WAV 与声明为 PCM 的音频，其他格式只检查大小。实时会话在 `end()` 时检查累计音频。

| 本地码 | 条件 | WARN 模式 | REJECT 模式 |
|---|---|---|---|
| 90101 | 时长短于 1 秒 | 警告 | 上传前抛出异常 |
| 90102 | 时长超过 300 秒，整段评测上传大于 50 MB，或实时评测一轮大于 10 MB | 警告 | 抛出异常 |
| 90103 | 全程静音：峰值低于 200，均方根低于 30 | 警告 | 抛出异常 |
| 90104 | 音量过低：均方根低于 -45 dBFS | 警告 | 只警告 |
| 90105 | WAV 不是 16 位 PCM，或采样率低于 16000 | 警告 | 抛出异常 |

`audioPrecheck` 取 `OFF`，`WARN`，`REJECT`，默认 `WARN`。警告写入结果的 `localWarnings`，实时会话另外回调 `onWarning`。静音阈值与平台的静音判定一致，本地拒绝的音频在平台上也会得 0 分。REJECT 模式下实时会话不发送结束帧，会话以 `AudioQualityException` 失败，不产生计费。`precheckAudio(bytes)` 可以单独调用。

## 外部音频

评测接口接受调用方自有的音频：`Blob`，`File`，`ArrayBuffer`，`Uint8Array` 以及任意 `ArrayBufferView`，格式为 WAV 或 MP3。裸 PCM 数据传 `audioFormat: 'pcm'` 与 `sampleRate`，SDK 先封装成 WAV 再上传。实时会话通过 `session.sendAudio(chunk)` 接收 16 kHz 单声道 16 位小端 PCM，建议每帧 640 字节即 20 毫秒，录音器不是必需组件。大于 32000 字节的分片由 SDK 拆成多帧发送，不会触碰平台 128 KB 的单帧上限。会话开始前送入的音频先排队，开始后按顺序发出。

## 日志与指标

`logLevel` 取 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，默认 `WARN`。`logger(level, tag, message, error)` 替换默认的控制台输出。日志不包含 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符加 `***`。

`eventListener` 的方法都可选：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op, method, path, attempt)` | 每次 REST 尝试之前 |
| `onRequestEnd(op, httpStatus, latencyMs, attempts, error)` | 每次逻辑调用结束，`latencyMs` 为整个调用的耗时 |
| `onRetry(op, attempt, delayMs, error)` | 每次重试之前 |
| `onSessionStateChanged(sessionId, old, new)` | 实时会话每次状态变化 |
| `onReconnect(sessionId, attempt, succeeded)` | 每次重连尝试的结果 |

回调或日志函数抛出的异常由 SDK 捕获后写入日志，不影响所在的调用。

## Node.js 用法

Node.js 18 及以上版本自带 `fetch`。实时评测需要 `ws` 包提供 WebSocket：

```js
import WebSocket from 'ws';
import { YuguClient } from '@shengzhiai/yugu-web-sdk';

const client = new YuguClient({ appKey, secretKey, WebSocket });
```

Node.js 18 没有全局的 `crypto`，签名鉴权需要传入 `crypto` 选项：

```js
import { webcrypto } from 'node:crypto';
const client = new YuguClient({ appKey, secretKey, crypto: webcrypto });
```

Node.js 20 及以上版本不需要这一步。录音器只能在浏览器中使用。

## 浏览器支持

| 浏览器 | 最低版本 | 录音方式 |
|---|---|---|
| Chrome | 73 | AudioWorklet |
| Edge | 79 | AudioWorklet |
| Firefox | 68 | 76 起使用 AudioWorklet，68 到 75 使用 ScriptProcessor |
| Safari | 13 | `14.1` 起使用 AudioWorklet，更早的版本使用 ScriptProcessor |
| iOS Safari | 13 | `14.5` 起使用 AudioWorklet，更早的版本使用 ScriptProcessor |
| Internet Explorer | 不支持 | 不支持 |

构建目标为 ES2018，表中的最低版本与仓库根目录的 [`COMPATIBILITY.md`](../COMPATIBILITY.md) 一致，Node.js 最低为 18。录音与签名需要安全上下文，即 https 页面或 localhost。录音应在用户手势里启动，例如在点击事件里调用 `recorder.start()`。没有用户手势时浏览器让 AudioContext 保持挂起，`start()` 照常返回，录音在页面下一次交互后开始产生音频。

## 常见问题

### 整段评测返回 415

`1.x` 用浏览器 FormData 上传，`config` 段带有文件名，平台按文件处理后拒绝。`2.0.0` 手工构造 multipart 请求体，`config` 段不带文件名，类型为 `application/json; charset=utf-8`，这一问题已修复。

### 签名验证失败 2003

检查 secretKey 是否与 appKey 配对，设备时钟与标准时间的偏差是否超过 300 秒。签名鉴权在浏览器中只用于沙箱试用。

### 浏览器报跨域错误

平台已允许任意来源的跨域请求，通过 `Access-Control-Expose-Headers` 暴露 `Idempotency-Replayed`，`Retry-After`，`X-Trace-Id`。代理或网关改写了响应头时，跨域请求会被浏览器拦截，表现为 `NetworkException` 90001，需要在代理上放行同样的请求头与响应头。

### Web Crypto 不可用

页面通过 http 访问，地址又不是 localhost 时，浏览器不提供 `crypto.subtle`，签名鉴权无法工作。改用 https，或改用 token 鉴权。

### 麦克风权限被拒绝

`recorder.start()` 以 `PermissionException` 失败，错误码 90201。设备被占用或不存在时为 `IllegalSessionStateException`，错误码 90202。

### 结果标记为重放

`result.replayed` 为 `true` 说明平台用同一个幂等键找到了首次的成功结果，直接返回，没有重复计费。需要重新评测时不要复用旧的幂等键。

### 心跳没有发送

`heartbeat` 默认取 `auto`，平台确认支持之前声通兼容会话不发送 ping。确认平台已支持时传 `heartbeat: true`。

### 实时评测结束后迟迟没有结果

`resultTimeoutMs` 默认 300 秒，期满按可重试错误 90007 触发重连，REPLAY 策略重放音频后由平台返回首次结果。界面需要更快的反馈时调小这个值。

## 开发与测试

在 `web` 目录执行 `npm ci` 与 `npm test`。单元测试覆盖签名，错误码映射，各题型结果解析，multipart 组装，退避计算，会话状态机，录音器与生命周期。集成测试启动仓库里的平台模拟服务，覆盖幂等计费，重试，断线重连与 10 秒断网。`npm run typecheck` 检查类型声明，`ci/web.sh` 依次执行全部步骤，最后一行输出行覆盖率。

设置环境变量 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后，`npm test` 另外运行沙箱端到端测试，`YUGU_SANDBOX_BASE` 默认 `https://open.shengzhiai.com`。每次运行调用平台 5 次：用同一个幂等键提交两次原生整段评测，第二次应为重放，另有声通兼容整段评测，原生实时评测与声通兼容实时评测各一次。没有设置密钥时这组测试自动跳过，不访问平台。沙箱密钥的申请方式与额度见 [`SANDBOX.md`](../SANDBOX.md)。

## 版本与许可

当前版本 `2.0.0`，对应平台接口 v1 与契约 `2.0`，变更记录见 `CHANGELOG.md`。各端版本对应关系见 [`COMPATIBILITY.md`](../COMPATIBILITY.md)，从 `1.x` 升级的步骤见 [`MIGRATION-2.0.md`](../MIGRATION-2.0.md) 与 `CHANGELOG.md` 的破坏性变更一节，接口与协议帧的权威说明见 [`CONTRACT.md`](../CONTRACT.md)。SDK 按 Apache License 2.0 许可发布，许可全文见 `LICENSE`，版权与第三方说明见 `NOTICE`。
