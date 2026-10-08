# 优谷雅言语音评测微信小程序 SDK

微信小程序端的优谷雅言语音评测 SDK，封装整段评测，声通兼容评测，实时评测，语音合成与报告查询，内置幂等键，自动重试，断线重连，录音器与音频预检。SDK 为零运行时依赖的 CommonJS 包，附完整 TypeScript 声明。

| 项 | 内容 |
|---|---|
| 包名 | `@shengzhiai/yugu-miniprogram-sdk` |
| 当前版本 | `2.0.0`，2026-10-08 |
| npm 源 | `https://open.shengzhiai.com/npm/`，匿名只读 |
| 服务基址 | `https://open.shengzhiai.com`，`wss://open.shengzhiai.com` |
| 基础库 | `2.20.1` 及以上 |
| 源码仓库 | `https://open.shengzhiai.com/git/yugu-sdk.git` 的 `miniprogram` 目录 |
| 示例工程 | 源码仓库的 `demos/miniprogram-demo` 目录 |
| 许可 | Apache-2.0 |

接口契约见仓库根目录的 `CONTRACT.md`，错误码见 `ERRORS.md`，各评测模式的取分字段见 `RESULTS.md`，沙箱见 `SANDBOX.md`。

## 安装

在小程序工程的根目录，即 `project.config.json` 所在目录，执行：

```bash
echo "@shengzhiai:registry=https://open.shengzhiai.com/npm/" >> .npmrc
npm install @shengzhiai/yugu-miniprogram-sdk@2.0.0
```

工程里还没有 `package.json` 时先执行 `npm init -y`。npm 源不需要账号，已发布的版本不会被覆盖，退回历史版本时把版本号换成目标版本重新安装即可。

安装后在微信开发者工具中构建 npm：

1. 打开工程，点击菜单“工具”，选择“构建 npm”。
2. 构建完成后工程里出现 `miniprogram_npm/@shengzhiai/yugu-miniprogram-sdk` 目录。
3. 代码中按包名引用：

```js
const { YuguClient } = require('@shengzhiai/yugu-miniprogram-sdk');
```

`project.config.json` 设置了 `miniprogramRoot` 时，`package.json` 放在该目录下，或者在 `setting.packNpmRelationList` 里登记 `package.json` 与 `miniprogram_npm` 的位置。升级 SDK 后要重新构建 npm。

## 合法域名

SDK 只通过 `wx.request` 与 `wx.connectSocket` 访问平台，上线前在小程序管理后台的“开发管理”，“开发设置”，“服务器域名”中配置：

| 类型 | 域名 |
|---|---|
| request 合法域名 | `https://open.shengzhiai.com` |
| socket 合法域名 | `wss://open.shengzhiai.com` |

SDK 2.0 不再使用 `wx.uploadFile`，不需要配置 uploadFile 合法域名。开发阶段可以在开发者工具的“详情”，“本地设置”里勾选“不校验合法域名，web-view，TLS 版本以及 HTTPS 证书”，真机预览与发布前要改回。

录音需要麦克风权限。在小程序管理后台的“用户隐私保护指引”中声明使用麦克风，首次录音时微信弹出授权框，用户拒绝后录音器报 `PermissionException`，错误码 90201。

## 五分钟上手

整段评测：录一段音，上传评测，打印总分。

```js
const { YuguClient } = require('@shengzhiai/yugu-miniprogram-sdk');

const client = new YuguClient({
  auth: { token: '<由自有服务端下发的短期 token>' },
});
const recorder = client.createRecorder();

Page({
  onRecordStart() {
    recorder.start();
  },
  async onRecordStop() {
    const recording = await recorder.stop();
    const result = await client.evaluate({
      coreType: 'sentence',
      referenceText: '今天天气很好',
      language: 'zh-CN',
      audio: recording,
    });
    console.log('总分', result.overall, '发音', result.dims.pronunciation, '流利', result.dims.fluency);
  },
  onUnload() {
    client.close();
  },
});
```

实时评测：录音帧直接推入会话，停止录音时自动结束会话。

```js
const session = client.streamEvaluate(
  { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' },
  {
    onResult(result) { console.log('总分', result.overall); },
    onError(err) { console.log('评测失败', err.code, err.message); },
  },
);
recorder.pipeTo(session);
recorder.start();
// 用户读完后
recorder.stop();
```

## 鉴权

| 方式 | 写法 | 说明 |
|---|---|---|
| token | `auth: { token }` | 由自有服务端调用开放平台换取短期 JWT 后下发给小程序，正式环境使用这种方式 |
| 签名 | `auth: { appKey, secretKey }` | 每次请求按 `CONTRACT.md` 第 2 节签名，适合沙箱联调。声通兼容整段评测 `evaluateCompat` 要求请求头带 `X-App-Key`，只能用这种方式 |

secretKey 写进小程序包就可能被反编译取得，正式环境不要在小程序里使用 secretKey。为兼容 `1.x` 写法，`token`，`appKey`，`secretKey` 也可以直接写在客户端配置的顶层。签名鉴权时请求头带 `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature`，实时评测把签名参数放在握手 query 里。设备时钟与服务器时间相差超过 300 秒时请求会被拒绝，错误归为 `AuthException`。

## 接口一览

| 接口 | 说明 |
|---|---|
| `new YuguClient(options)` | 创建客户端，配置见“客户端配置” |
| `client.evaluate(params, options)` | 原生整段评测 `POST /api/v1/evaluate`，返回 `Promise<EvalResult>` |
| `client.evaluateCompat(coreType, params, options)` | 声通兼容整段评测 `POST /{coreType}`，返回 `Promise<EvalResult>`，需要签名鉴权，用 token 时在发请求前以错误码 90010 失败 |
| `client.tts(params, options)` | 语音合成，返回 `Promise<TtsResult>`，`fullUrl` 可直接交给 `InnerAudioContext` |
| `client.getReport(recordId, options)` | 报告查询，返回报告数据 |
| `client.streamEvaluate(params, listener, options)` | 原生实时评测，返回 `StreamSession` |
| `client.streamEvaluateCompat(coreType, params, listener, options)` | 声通兼容实时评测，返回 `StreamSession` |
| `client.createRecorder(options)` | 创建录音器，`client.close()` 时一并释放 |
| `client.close()` | 关闭客户端，可重复调用 |
| `client.isClosed()` | 是否已关闭 |
| `session.sendAudio(chunk)` | 送入 16 位小端 PCM，`ArrayBuffer` 或类型化数组，大于 32000 字节的块拆成多帧发送，平台单帧上限 128 KB |
| `session.end()` | 音频结束，等待终评 |
| `session.cancel()`，`session.close()` | 取消会话，可重复调用 |
| `session.getState()`，`session.isActive()` | 会话状态查询 |
| `session.setListener(listener)`，`session.removeListener()` | 更换或注销监听器 |
| `recorder.start()`，`pause()`，`resume()`，`stop()`，`release()` | 录音控制，`release()` 可重复调用 |
| `recorder.getState()` | 录音器状态查询 |
| `recorder.pipeTo(session)`，`recorder.unpipe()` | 录音帧推入实时评测会话 |
| `isRetryable(error)` | 可重试判定，与 SDK 内部重试共用一套规则 |
| `fromCode(code)`，`fromWarningCode(code)` | 按错误码构造对应类型的错误 |
| `precheckAudio(bytes, options)` | 本地音频预检 |
| `generateIdempotencyKey()` | 生成幂等键 |
| `signParams(params, secretKey)` | 签名，与平台算法一致 |
| `createAbortController()` | 小程序环境的取消控制器 |
| `parseEvalResult(json)` | 把平台原始结果转为 `EvalResult` |

`evaluate` 的参数为 `coreType`，`referenceText`，`language`，`includeReport`，`includeStandardAudio`，`includeAsrText`，`slack`，`scale`，`precision`，`agegroup`，`toneWeight`，`refPinyin`，`phonemeOutput`，`taskType`，`paragraphNeedWordScore` 与音频 `audio`，含义见 `CONTRACT.md` 第 3.1 节。契约之外的新字段放在 `extra` 里原样透传。看图说话题用 `image` 传图片。`evaluateCompat` 用声通参数名 `refText`，`language`，`refPinyin`，其余表单字段放在 `fields` 里。

## 客户端配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `auth` | 必填 | `{ token }` 或 `{ appKey, secretKey }` |
| `baseUrl` | `https://open.shengzhiai.com` | REST 基址 |
| `wsBaseUrl` | `wss://open.shengzhiai.com` | 实时评测基址 |
| `connectTimeoutMs` | 10000 | 实时评测从建连到服务端会话开始的时限 |
| `readTimeoutMs` | 120000 | 单次 REST 尝试的时限 |
| `totalTimeoutMs` | 300000 | 一次逻辑调用的总时限，重试与等待都计入 |
| `retry` | 见“重试与幂等” | 部分覆盖重试策略，`false` 关闭重试 |
| `autoIdempotencyKey` | `true` | 调用方未给幂等键时自动生成 |
| `logLevel` | `WARN` | `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG` |
| `logger` | 控制台 | 日志输出函数 `(level, tag, message, error)` |
| `eventListener` | 无 | 指标回调，见“日志与指标” |
| `audioPrecheck` | `WARN` | 音频预检模式 `OFF`，`WARN`，`REJECT` |
| `strictAudio` | `false` | 评测结果带警告 1001 时抛出 `AudioQualityException` |
| `userAgent` | `yugu-miniprogram-sdk/2.0.0` | 通过请求头 `X-Yugu-SDK` 发送，小程序不能改写 `User-Agent` |
| `reconnect` | 见“实时评测” | 部分覆盖重连策略，`false` 关闭重连 |
| `audioBufferPolicy` | `REPLAY` | 实时评测音频缓冲策略 `REPLAY`，`DROP`，`FAIL` |
| `heartbeatIntervalMs` | 15000 | 应用层心跳间隔，0 关闭心跳 |
| `heartbeatTimeoutMs` | 30000 | 心跳生效后多久收不到任何帧视为断线 |
| `resultTimeoutMs` | 300000 | 发出结束帧后等待终评的时限 |
| `replayBufferLimitBytes` | 10485760 | REPLAY 缓冲上限，即 10 MB |

`wx.request` 只有一个总超时，SDK 用 `readTimeoutMs` 与剩余总时限中较小的值作为每次尝试的超时。客户端配置里另有 `wx` 与 `random` 两项，分别用于在测试中注入 `wx` 对象与退避抖动的随机数源，业务代码不需要设置。

## 单次调用选项

`evaluate`，`evaluateCompat`，`tts`，`getReport` 的最后一个参数：

| 选项 | 说明 |
|---|---|
| `idempotencyKey` | 调用方指定的幂等键，1 到 200 个可见 ASCII 字符，例如业务单号 |
| `timeoutMs` | 覆盖本次调用的 `readTimeoutMs` |
| `totalTimeoutMs` | 覆盖本次调用的 `totalTimeoutMs` |
| `retry` | 覆盖本次调用的重试策略，`false` 不重试 |
| `signal` | 取消信号，`createAbortController().signal` 或任意 AbortSignal 形态的对象 |
| `audioPrecheck` | 覆盖本次调用的预检模式 |
| `strictAudio` | 覆盖本次调用的 `strictAudio` |

```js
const { createAbortController } = require('@shengzhiai/yugu-miniprogram-sdk');
const ctl = createAbortController();
client.evaluate(params, { idempotencyKey: 'order-20261008-42', signal: ctl.signal })
  .catch((err) => console.log(err.name, err.code));
ctl.abort(); // 页面退出时取消，Promise 以 RequestCancelledException 结束，错误码 90003
```

## 结果模型

整段评测与实时评测都返回 `EvalResult`：

| 字段 | 说明 |
|---|---|
| `overall` | 总分，connected 模式取 `result.connected_overall` |
| `dims` | 朗读类维度：`pronunciation`，`accuracy`，`fluency`，`integrity`，`tone`，`rhythm`，`emotion`，未评的维度为 `null` |
| `words` | 逐字或逐词详情，字段保持平台原名，见 `RESULTS.md` |
| `sentences` | 分句结果，段落题在 `paragraphNeedWordScore` 为 1 时带 `details` |
| `speed`，`rearTone`，`duration`，`durationSec` | 语速，句末语调，时长原值与秒数 |
| `connected` | 连读题的分项，其余题型为 `null` |
| `open` | 开放题的分项，其余题型为 `null` |
| `coreType`，`language` | 引擎报告的题型与语种 |
| `asrText`，`report`，`standardAudio`，`yuguScores` | 识别文本，报告，标准示范音，维度细分 |
| `warnings` | 平台音频质量警告 `[{ code, message }]` |
| `localWarnings` | 本地预检发现的问题，错误码 90101 到 90105 |
| `recordId`，`eof` | 记录号与结束标记 |
| `idempotencyKey`，`replayed` | 本次使用的幂等键，结果是否为同一个键的重放 |
| `attempts` | 整段评测为尝试次数，实时评测为使用的连接数 |
| `raw` | 平台返回的原始 JSON |

各模式的取分字段：

| 原生 coreType | 声通兼容 coreType | 总分 | 分项 |
|---|---|---|---|
| `word` | `word.eval`，`word.eval.pro`，`word.eval.cn` | `overall` | `dims`，`words` |
| `sentence` | `sent.eval`，`sent.eval.pro`，`sent.eval.cn` | `overall` | `dims`，`words`，`sentences` |
| `passage` | `para.eval`，`para.eval.cn` | `overall` | `dims`，`sentences[].details`，`words` |
| `alpha` | `alpha.eval` | `overall` | `dims`，`words` |
| `pinyin` | `pinyin` | `overall` | `dims`，`words`，引擎按句子题评分，`coreType` 报告为 `sentence` |
| `connected` | 无 | `overall`，即 `connected.overall` | `connected.linking`，`elision`，`reduction`，`rhythm`，`nBoundaries`，`boundaries[]` |
| `open` | 无 | `overall` | `open.content`，`open.languageUse`，`open.delivery`，`open.transcript`，`open.feedback`，`open.openTaskAudit` |

`connected.boundaries` 的每一项给出 `between` 两个词，`tags` 应有的连读类型，`realized` 实际实现程度，`start_ms` 与 `end_ms`。`open.delivery` 含 `fluency`，`pronunciation`，`speech_rate`，`speech_rate_label`，`n_pauses`。嵌套对象保持平台原有字段名，原始 JSON 始终可以从 `raw` 取到。

```js
const r = await client.evaluate({ coreType: 'open', referenceText: '介绍一下自己', language: 'zh-CN', audio: recording });
if (r.open) {
  console.log(r.overall, r.open.content.overall, r.open.delivery.speech_rate_label, r.open.feedback.suggestions);
}
```

语音合成返回 `TtsResult`：`audioUrl` 为平台原值，`fullUrl` 为可直接播放的完整地址，另有 `duration`，`format`，`idempotencyKey`，`replayed`。

## TypeScript

包内附 `types/index.d.ts`，`package.json` 的 `types` 字段已指向该文件，引入后字段有补全与编译期检查，开启 `strict` 与 `noImplicitAny` 也没有隐式 any。声明只依赖 ES2017 标准库，不依赖 DOM 与 Node 类型，与小程序官方类型 `miniprogram-api-typings` 可以同时使用。

```ts
import { YuguClient, isRetryable, RateLimitException } from '@shengzhiai/yugu-miniprogram-sdk';
import type { EvalResult, StreamListener } from '@shengzhiai/yugu-miniprogram-sdk';

const client = new YuguClient({ auth: { token } });
const listener: StreamListener = {
  onResult(result: EvalResult) { console.log(result.overall); },
  onError(err) { if (err instanceof RateLimitException && isRetryable(err)) console.log(err.retryAfterMs); },
};
```

## 错误处理

SDK 抛出的错误都是 `YuguError` 或其子类，字段为 `category`，`code`，`codeNamespace`，`httpStatus`，`message`，`retryable`，`idempotencyKey`，`recordId`，`attempts`，`rawBody`，`op`，`cause`。`rawBody` 截取前 4 KB。`codeNamespace` 说明错误码来自哪张表：`errors` 为平台错误码，`warnings` 为音频质量警告码，`local` 为 SDK 本地错误码，`unknown` 为码表之外的码或没有码。

| 类别 | 类型 | 典型错误码 |
|---|---|---|
| `NETWORK` | `NetworkException` | 90001 网络错误，90006 重连次数用尽，90011 证书校验失败 |
| `TIMEOUT` | `RequestTimeoutException` | 90002 超时，90007 终评超时 |
| `AUTH` | `AuthException` | 40100，2001 到 2011 |
| `PERMISSION` | `PermissionException` | 40300，90201 没有麦克风权限 |
| `INVALID_PARAM` | `InvalidParameterException` | 40001，90010 调用参数不合法 |
| `NOT_FOUND` | `NotFoundException` | 40400 |
| `CONFLICT` | `ConflictException` | 40901 同一幂等键处理中，40903 幂等键用于不同请求 |
| `RATE_LIMIT` | `RateLimitException` | 42900，42901，3001 到 3003 |
| `QUOTA` | `QuotaExceededException` | 40902，42903 沙箱当日次数用尽 |
| `SERVER`，`UPSTREAM` | `ServerException` | 50000，50200 |
| `AUDIO` | `AudioQualityException` | 90101 到 90105，警告码 1001 到 1005 与 1009 |
| `STATE` | `IllegalSessionStateException` | 90004 客户端已关闭，90008，90009，90202，90203 |
| `CANCELLED` | `RequestCancelledException` | 90003 |
| `PROTOCOL` | `ProtocolViolationException` | 90005 响应或帧无法解析 |
| `UNKNOWN` | `YuguError` | 无法归类的错误 |

错误归类的顺序：本地错误，响应体里的 `code`，引擎 `detail` 中的 `[2001]` 形式，最后按 HTTP 状态。响应体里的 `code` 只按平台错误码表解释。HTTP 状态先查 `ERRORS.md` 的兜底表，表里没有的其余 4xx 归为 `INVALID_PARAM`，其余 5xx 归为 `SERVER`，两者都不重试，其他状态归为 `UNKNOWN`。完整码表见 `ERRORS.md`，SDK 内置同一份表，导出为 `ERROR_TABLE`，`WARNING_TABLE`，`LOCAL_TABLE` 与 `ErrorCodes`。

```js
const { isRetryable, AuthException, AudioQualityException, ErrorCodes } = require('@shengzhiai/yugu-miniprogram-sdk');
try {
  await client.evaluate(params);
} catch (err) {
  if (err instanceof AuthException) {
    // 重新获取 token
  } else if (err instanceof AudioQualityException) {
    // 提示重录，err.code 为 90101 到 90105
  } else if (err.code === ErrorCodes.QUOTA_INSUFFICIENT) {
    // 额度不足，提示充值
  } else if (isRetryable(err)) {
    // SDK 已按策略重试过，稍后再提示用户重试
  }
}
```

平台音频质量警告 1001 到 1005 与 1009 随结果返回，放在 `result.warnings`，不抛出异常。设置 `strictAudio: true` 后，警告 1001 即未检测到有效音频会以 `AudioQualityException` 抛出。错误码 1004 与 1005 在平台错误码与警告码中都存在，按出现的位置解释：错误响应体里是用户禁用与锁定，评测结果的警告里是噪声与音频不完整。`fromCode` 按平台错误码构造，警告请用 `fromWarningCode`。

## 重试与幂等

写操作 `evaluate`，`evaluateCompat`，`tts` 默认每次逻辑调用生成一个幂等键，放在请求头 `Idempotency-Key`，重试复用同一个键，平台对同一个键只评测一次，只计费一次。结果与错误都带 `idempotencyKey`，同一个键的重复提交返回首次结果，`replayed` 为 `true`。幂等键按 appKey 或 token 用户隔离，平台保存 24 小时，首次请求失败时释放。同一个键用于不同请求时平台回 409，错误码 40903，不重试。

默认重试策略：

| 字段 | 默认值 |
|---|---|
| `maxRetries` | 2，即一共 3 次尝试 |
| `initialDelayMs` | 200 |
| `multiplier` | 2 |
| `maxDelayMs` | 4000 |
| `jitter` | 0.3 |
| `respectRetryAfter` | `true` |
| `maxRetryAfterMs` | 30000 |

第 n 次重试前等待 `min(maxDelayMs, initialDelayMs × multiplier^(n-1)) × (1 + U(-jitter, jitter))` 毫秒，默认约为 200 毫秒与 400 毫秒，各上下浮动 30%。响应带 `Retry-After` 时取两者较大值，上限 `maxRetryAfterMs`。下一次等待会超出 `totalTimeoutMs` 时不再重试，直接抛出最后一次的错误，`attempts` 记录已尝试次数。

可重试的情形：网络错误，超时，HTTP 408，425，429，500，502，503，504，以及错误码表里标为可重试的错误码，例如 40901，42900，42901，50000，50200。参数错误，鉴权失败，权限不足，额度不足等不重试。每次重试都会回调 `eventListener.onRetry`，同时输出一条 WARN 日志，例如 `retry 1/2 in 231 ms: HTTP 503 code=50200`。

关闭 `autoIdempotencyKey` 后，调用时没有给键的写操作不重试，DEBUG 日志会说明原因。报告查询天然幂等，不带键也会重试。

## 实时评测

实时评测在一条 WebSocket 连接上完成一轮评测：连接，开始，送音频，结束，终评。`streamEvaluate` 与 `streamEvaluateCompat` 返回会话后在下一个微任务开始连接，开始前送入的音频与提前调用的 `end()` 都会按顺序补发。

会话状态：

| 状态 | 含义 |
|---|---|
| `IDLE` | 未连接 |
| `CONNECTING` | 建立连接中 |
| `CONNECTED` | 服务端已发 connected |
| `STARTED` | 服务端已回 started，可以送音频 |
| `ENDING` | 结束帧已发出，等待终评 |
| `RECONNECTING` | 断线后等待重连 |
| `COMPLETED` | 已收到终评 |
| `FAILED` | 以错误结束 |
| `CANCELLED` | 调用方取消 |
| `CLOSED` | 会话结束，连接已关闭 |

监听器回调：

| 回调 | 时机 |
|---|---|
| `onStateChanged(oldState, newState)` | 每次状态变化 |
| `onConnected()` | 首次连接成功 |
| `onStarted()` | 首次服务端会话开始 |
| `onPartial({ bytes })` | 声通兼容评测打开 `realtimeFeedback` 后的进度帧，不含评分 |
| `onReconnecting(attempt, delayMs, cause)` | 断线后开始等待重连 |
| `onReconnected(attempt, info)` | 重连成功，`info` 含 `droppedBytes` 与 `replayedBytes` |
| `onWarning(warning)` | `end()` 时对这一轮音频的预检发现 |
| `onResult(result)` | 终评，必填 |
| `onError(error)` | 以错误结束，必填 |
| `onClosed(code, reason)` | 会话结束，总在最后 |

回调保证：每个会话只触发 `onResult` 与 `onError` 中的一个，最多一次，调用方先调用 `cancel()` 时两者都不触发。`onClosed` 总是最后一个回调。同一个会话的回调不会嵌套执行，回调里调用会话方法引起的新回调排在当前回调返回之后。`getState()` 返回的值与最近一次 `onStateChanged` 给出的新状态一致。

断线重连语义：平台不支持会话续传，一个连接就是一个服务端会话。连接意外关闭，心跳超时，连接超时，终评超时，或收到可重试的错误帧时，会话进入 `RECONNECTING`，按退避等待后新建连接，用同一个幂等键重发开始帧，按缓冲策略处理音频，结束帧已发出时一并重发。平台按幂等键识别重发，首次评测已完成时直接重放首次结果，不重复评测，不重复计费。重连次数用尽时以 `NetworkException` 结束，错误码 90006，`cause` 为最后一次的原因。服务端以关闭码 1002，1003，1007，1008，1009，1010 或 4000 到 4999 关闭连接时，重连也会被同样拒绝，会话立即以 `ProtocolViolationException` 结束，错误码 90005，不重连。服务端以 1000 正常关闭却没有给出终评时同样处理。

| 缓冲策略 | 行为 |
|---|---|
| `REPLAY` 默认 | 缓冲这一轮的全部音频，上限 10 MB，重连后整段重放，评分覆盖全部音频。超过上限后停止缓冲，之后的重连以错误码 90008 结束 |
| `DROP` | 不缓冲，断开的连接上已发的音频与重连期间送入的音频都不再评分，`onReconnected` 的 `droppedBytes` 给出字节数。结束帧发出后断线时没有可评的音频，直接以错误结束 |
| `FAIL` | 不重连，断线即以错误结束 |

默认重连策略为 `enabled` `true`，`maxAttempts` 8，`initialDelayMs` 500，`multiplier` 2，`maxDelayMs` 4000，`jitter` 0.3，各次等待约为 0.5 秒，1 秒，2 秒，4 秒，4 秒，4 秒，4 秒，4 秒，合计约 23 秒，按抖动最短也有 16 秒以上，默认设置即可撑过客户端断网 10 秒。`maxAttempts` 指一次断线内连续失败的次数，重连成功后重新计。一个会话的重连总次数另有上限，为 `maxAttempts` 的 3 倍，默认 24 次，服务端反复接受后又断开时，会话在达到上限后以错误码 90006 结束，`getStats().reconnectAttempts` 给出已用的次数。

心跳：小程序不能发送协议层 ping，SDK 使用应用层心跳 `{"cmd":"ping"}`，服务端回 `{"event":"pong"}`。

1. 每条连接收到 connected 后先发一个探测 ping，再发开始帧或参数帧，所以不认识 ping 的服务端也不会因此丢失音频。
2. 服务端回了 pong，心跳才生效：连接后与开始后每 `heartbeatIntervalMs` 发一次 ping，`heartbeatTimeoutMs` 内收不到任何帧视为断线，随即重连。
3. 服务端对探测回 `{"event":"error","message":"unknown cmd"}`，或不回 pong 先回 started 时，视为不支持心跳，本会话关闭心跳，不当作错误，此后依靠连接超时，连接关闭检测与终评超时。
4. 发出结束帧后服务端在评测，SDK 不再发心跳，改由 `resultTimeoutMs` 判断，默认 300 秒。

单个会话可以用 `streamEvaluate` 的第三个参数覆盖 `idempotencyKey`，`reconnect`，`audioBufferPolicy`，`heartbeatIntervalMs`，`heartbeatTimeoutMs`，`resultTimeoutMs`，`connectTimeoutMs`，`replayBufferLimitBytes`，`audioPrecheck` 与附加握手参数 `query`。`end()` 之后再调用 `sendAudio` 会被忽略，返回 `false`，同时记录一条 WARN 日志。

## 生命周期

客户端，会话，录音器都有可以重复调用的释放接口：

| 对象 | 释放接口 | 行为 |
|---|---|---|
| 客户端 | `close()` | 取消未结束的会话，中止进行中的请求，释放 `createRecorder` 创建的录音器。之后的调用以错误码 90004 失败 |
| 会话 | `cancel()`，`close()` | 关闭连接，不再触发 `onResult` 与 `onError`，仍触发 `onClosed` |
| 录音器 | `release()` | 停止录音，释放麦克风，清除监听器与推流关系，之后再调用 `start`，`stop` 等方法以错误码 90009 失败 |

创建，使用，释放的完整示例：

```js
const { YuguClient, RecorderState } = require('@shengzhiai/yugu-miniprogram-sdk');

Page({
  data: { state: 'IDLE', overall: null },

  onLoad() {
    this.client = new YuguClient({ auth: { token: getApp().globalData.token } });
    this.recorder = this.client.createRecorder({ sampleRate: 16000, numberOfChannels: 1, format: 'PCM' });
    this.recorder.setListener({
      onError: (err) => wx.showToast({ title: '录音失败 ' + err.code, icon: 'none' }),
    });
  },

  start() {
    this.session = this.client.streamEvaluate(
      { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN' },
      {
        onStateChanged: (oldState, newState) => this.setData({ state: newState }),
        onReconnecting: (attempt, delayMs) => console.log('重连', attempt, delayMs),
        onResult: (result) => this.setData({ overall: result.overall }),
        onError: (err) => wx.showToast({ title: err.message, icon: 'none' }),
        onClosed: () => console.log('会话结束', this.session.getState()),
      },
    );
    this.recorder.pipeTo(this.session);
    this.recorder.start();
  },

  stop() {
    if (this.recorder.getState() === RecorderState.RECORDING) this.recorder.stop();
  },

  onUnload() {
    if (this.session) this.session.close();
    this.recorder.release();
    this.client.close();
  },
});
```

异常路径的清理：录音器在 `stop()`，`release()` 与每一种错误路径上都释放麦克风，推流中的录音器出错时取消对应会话。会话进入 `COMPLETED`，`FAILED`，`CANCELLED` 后立即关闭连接，清除全部定时器，进入 `CLOSED`。页面被销毁前没有调用释放接口时，客户端 `close()` 仍可以在任何时刻补调。

## 录音器

| 配置 | 默认值 | 说明 |
|---|---|---|
| `sampleRate` | 16000 | 采样率 |
| `numberOfChannels` | 1 | 声道数 |
| `format` | `PCM` | 录音格式，逐帧回调需要 `PCM` 或 `mp3` |
| `frameSize` | 1 | 每次帧回调的大小，单位 KB |
| `duration` | 300000 | 最长录音时长，单位毫秒，到时自动停止 |
| `audioSource` | 自动 | 音频输入源 |

录音器状态为 `IDLE`，`RECORDING`，`PAUSED`，`STOPPED`，`RELEASED`。`stop()` 返回 `{ tempFilePath, duration, fileSize, format, sampleRate, numberOfChannels }`，可以直接作为 `evaluate` 的 `audio`，SDK 会为 PCM 加上 WAV 头再上传。监听器回调为 `onStateChanged`，`onStart`，`onPause`，`onResume`，`onStop`，`onFrame(frame, isLastFrame)`，`onError`，`onInterruptionBegin`，`onInterruptionEnd`，`setListener(null)` 注销监听器。

`pipeTo(session)` 把录音帧切成 640 字节一帧，即 16 kHz 单声道 20 毫秒，送入会话，录音停止时补发剩余音频，再调用 `session.end()`。会话提前结束时录音器自动停止。

`wx.getRecorderManager()` 返回全局共用的同一个对象，没有注销回调的方法。SDK 只在该对象上注册一次回调，再分发给当前占用麦克风的录音器，所以反复创建与释放录音器不会累积回调。同一时刻只有一个录音器可以录音，另一个录音器开始录音会以错误码 90202 失败。使用 SDK 录音器期间，业务代码不要再给 `wx.getRecorderManager()` 注册回调，否则会覆盖 SDK 的回调。

## 音频预检

整段评测上传前与实时评测 `end()` 时，SDK 对 WAV 与 PCM 做本地检查，其他格式只检查大小。

| 错误码 | 条件 | WARN 模式 | REJECT 模式 |
|---|---|---|---|
| 90101 | 时长短于 1 秒 | 记为警告 | 上传前报错 |
| 90102 | 时长超过 300 秒，整段上传大于 50 MB，或实时评测一轮大于 10 MB | 记为警告 | 上传前报错 |
| 90103 | 全程静音：峰值低于 200，均方根也低于 30 | 记为警告 | 上传前报错 |
| 90104 | 音量过低：均方根低于 -45 dBFS | 记为警告 | 只记为警告 |
| 90105 | 不是 16 位 PCM WAV，或采样率低于 16000 | 记为警告 | 上传前报错 |

大小上限按用途区分：整段评测上传的上限为平台请求体上限 50 MB，实时评测一轮音频的上限为 10 MB，`precheckAudio` 默认按上传检查，传 `stream: true` 时按实时评测检查。90103 的阈值与平台的静音判定一致，被拦下的音频提交上去也只会得 0 分。警告放在结果的 `localWarnings` 里，实时评测另外回调 `onWarning`。REJECT 模式下实时评测在 `end()` 时发现问题，会话以 `AudioQualityException` 结束，不发结束帧，不计费。界面需要在评测前提示时，可以直接调用 `precheckAudio`：

```js
const { precheckAudio } = require('@shengzhiai/yugu-miniprogram-sdk');
const report = precheckAudio(arrayBuffer); // 或 precheckAudio(pcm, { format: 'pcm', sampleRate: 16000, stream: true })
console.log(report.durationMs, report.rmsDbfs, report.warnings);
```

## 外部音频

评测接口接受调用方自有的音频，不必使用 SDK 的录音器：

| 输入 | 写法 |
|---|---|
| 内存中的音频 | `audio: arrayBuffer` 或 `audio: uint8Array` |
| 本地文件 | `audio: tempFilePath`，也可以是 `wxfile://` 或用户目录下的路径，SDK 用 `wx.getFileSystemManager().readFile` 读取 |
| 带格式说明的文件 | `audio: { tempFilePath, format: 'pcm', sampleRate: 16000 }` |
| 原始 PCM 数据 | `audio: pcmArrayBuffer, audioFormat: 'pcm', sampleRate: 16000` |
| SDK 录音结果 | `audio: await recorder.stop()` |

WAV，mp3，m4a，aac，ogg，webm 等容器按文件头识别后原样上传，原始 PCM 自动加 WAV 头。实时评测用 `session.sendAudio` 送入任意来源的 16 位小端 PCM，例如直播流或第三方采集模块的输出。

## 日志与指标

日志级别为 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，默认 `WARN`，输出到控制台，可以用 `logger` 改为自有的日志通道。日志里不出现 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符，实时评测的握手地址在记录前去掉凭据参数。

`eventListener` 的方法都可以不实现，回调里抛出的异常会被记录，不影响 SDK：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op, method, path, attempt)` | 每次 REST 尝试开始 |
| `onRequestEnd(op, httpStatus, latencyMs, attempts, error)` | 一次逻辑调用结束，成功时 `error` 为空 |
| `onRetry(op, attempt, delayMs, error)` | 每次重试前 |
| `onSessionStateChanged(sessionId, oldState, newState)` | 实时评测会话状态变化 |
| `onReconnect(sessionId, attempt, succeeded)` | 每次重连尝试的结果 |

```js
const client = new YuguClient({
  auth: { token },
  logLevel: 'INFO',
  logger: (level, tag, message) => wx.getRealtimeLogManager().info(level, tag, message),
  eventListener: {
    onRequestEnd: (op, status, latencyMs, attempts) => report(op, status, latencyMs, attempts),
  },
});
```

## 基础库与运行环境

| 项 | 要求 |
|---|---|
| 小程序基础库 | `2.20.1` 及以上 |
| 用到的接口 | `wx.request` 的 `timeout` 与 `responseType`，`wx.connectSocket` 与 `SocketTask`，`wx.getRecorderManager` 的 PCM 录音与 `onFrameRecorded`，`wx.getFileSystemManager` |
| JavaScript | 产物为 ES2017 语法的 CommonJS，不依赖 `TextEncoder`，`crypto.subtle`，`AbortController`，`globalThis` |
| 开发者工具 | 支持构建 npm 的稳定版，“将 JS 编译成 ES5”打开或关闭都可以 |

签名所需的 SHA-256，HMAC，Base64 与 UTF-8 编码都由 SDK 用纯 JavaScript 实现。

## 测试与构建

```bash
cd miniprogram
npm ci
npm test            # 构建，单元测试与集成测试，c8 行覆盖率门槛 70%
npm run typecheck   # 用 strict 配置检查 TypeScript 声明
bash ../ci/miniprogram.sh
```

集成测试启动仓库自带的平台模拟服务 `tools/mock-server`，覆盖整段评测，重试，幂等，实时评测断线与重连，不连接生产环境。覆盖率报告在 `coverage/index.html`。设置 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后另跑沙箱联调用例，见 `SANDBOX.md`。

## 常见问题

### 原生整段评测报 HTTP 415

这是 `1.x` 版本的缺陷：`wx.uploadFile` 不能给表单段设置 Content-Type，`config` 段以 text/plain 到达平台，平台回 415。2.0 自行拼装 multipart 请求体，`config` 段不带文件名，类型为 `application/json; charset=utf-8`，用 `wx.request` 发送，已修复，升级即可。

### 报 url not in domain list

域名没有加入合法域名，按“合法域名”一节配置 request 与 socket 两类域名。错误归为 `NetworkException`，错误码 90001。

### 签名校验失败，错误码 2003

检查 appKey 与 secretKey 是否成对，设备时间是否准确。平台要求时间戳与服务器时间相差不超过 300 秒。

### 录音失败，错误码 90201

用户拒绝了麦克风授权，或者小程序没有在用户隐私保护指引中声明麦克风。可以用 `wx.openSetting` 引导用户重新授权。

### 实时评测一直在重连

先看 `onReconnecting` 的 `cause`：`NetworkException` 多为网络中断，`RequestTimeoutException` 多为心跳或终评超时。重连次数用尽后会话以错误码 90006 结束，不会停在中间状态。默认的 8 次约覆盖 23 秒的中断，网络更差的场景可以调大 `reconnect.maxAttempts`。

### 同一个幂等键报 40903

一个幂等键只能用于一个请求，参数或音频变化后要换新键。SDK 自动生成的键每次调用都不同，只有调用方显式传入同一个键时才会出现。

### 分数为 0

查看 `result.warnings` 与 `result.localWarnings`：1001 或 90103 表示没有有效语音，应提示用户重录。开启 `audioPrecheck: 'REJECT'` 可以在上传前拦下静音与过短的录音，不产生计费。

### 打包体积

`miniprogram_dist/index.js` 约 130 KB，未压缩，上传代码时开发者工具会再压缩。SDK 没有运行时依赖。

## 版本与许可

变更记录见 `CHANGELOG.md`，各端版本对应关系见仓库根目录的 `COMPATIBILITY.md`，由 `1.x` 升级见 `MIGRATION-2.0.md`。许可为 Apache-2.0，见 `LICENSE` 与 `NOTICE`。
