# 变更记录

微信小程序 SDK `@shengzhiai/yugu-miniprogram-sdk` 的变更记录，格式参照 Keep a Changelog，版本号规则见仓库根目录的 `COMPATIBILITY.md`。

## 版本 `2.0.0`，2026-10-08

### 新增

- 以 npm 包 `@shengzhiai/yugu-miniprogram-sdk` 发布到 `https://open.shengzhiai.com/npm/`，用开发者工具的构建 npm 引入，附 TypeScript 声明 `types/index.d.ts`。
- 写操作 `evaluate`，`evaluateCompat`，`tts` 自动带幂等键 `Idempotency-Key`，可以由调用方指定，重试复用同一个键。结果与错误带 `idempotencyKey`，结果带 `replayed`。
- 可配置的重试策略：默认重试 2 次，指数退避加 30% 随机抖动，尊重 `Retry-After`，总时限 300 秒，只对可重试错误生效，每次重试回调 `onRetry`，同时输出 WARN 日志。
- 实时评测会话 `StreamSession`：十个状态的状态机，`getState()` 与 `isActive()`，自动重连，默认最多连续重连 8 次，约 23 秒，可以撑过断网 10 秒，每个会话的重连总次数不超过 24 次，关闭码 1002，1003，1007 到 1010 与 4000 到 4999 不重连，以错误码 90005 结束，`REPLAY`，`DROP`，`FAIL` 三种音频缓冲策略，应用层心跳，连接超时与终评超时，结构化回调 `onStateChanged`，`onConnected`，`onStarted`，`onPartial`，`onReconnecting`，`onReconnected`，`onWarning`，`onResult`，`onError`，`onClosed`。
- 心跳自动识别：服务端回 pong 后心跳才生效，不支持心跳的服务端回 `unknown cmd` 时本会话关闭心跳，不影响评测。
- 十六类错误与对应异常类型，`isRetryable` 可重试判定，内置与 `ERRORS.md` 一致的错误码表 `ERROR_TABLE`，`WARNING_TABLE`，`LOCAL_TABLE`，`ErrorCodes`。错误带 `codeNamespace`，区分平台错误码，警告码与本地错误码。
- 录音器 `YuguRecorder`：16000 Hz 单声道 PCM，帧回调，暂停与恢复，`release()` 可重复调用，`pipeTo(session)` 把录音帧推入实时评测会话。
- 客户端 `close()`：取消会话，中止请求，释放录音器。
- 音频预检：时长，大小，静音，音量，格式，模式 `OFF`，`WARN`，`REJECT`，另有 `precheckAudio` 供界面提前提示。大小上限按用途区分，整段上传 50 MB，实时评测一轮 10 MB。
- 外部音频：`ArrayBuffer`，类型化数组，`tempFilePath`，录音结果均可直接评测，原始 PCM 自动加 WAV 头。
- 日志级别，自定义日志输出，指标回调 `eventListener`，日志中不出现密钥，签名，token 与音频数据。
- 结果模型补充各题型字段：connected 题的 `connected`，open 题的 `open`，`coreType`，`language`，`durationSec`，`localWarnings`，`attempts`。
- 看图说话题可以上传图片 `image`。
- 取消控制器 `createAbortController`，单次调用可用 `signal` 取消。
- 示例工程 `demos/miniprogram-demo`，单元测试与集成测试，持续集成脚本 `ci/miniprogram.sh`。

### 变更

- 默认基址由 `https://ygyx.dragonai.tech` 改为 `https://open.shengzhiai.com`，两个域名指向同一平台。
- SDK 通过请求头 `X-Yugu-SDK` 标识版本，小程序不能改写 `User-Agent`。
- `evaluateCompat` 要求签名鉴权，兼容接口必须带 `X-App-Key`，只配置 token 时在发请求前报错。
- 实时评测大于 32000 字节的音频块拆成多帧发送，不超过平台 128 KB 的单帧上限。
- 录音器默认格式由 wav 改为 PCM，帧回调可以直接推流，整段评测时自动加 WAV 头。
- 语音合成结果的 `duration` 改为秒数。
- 许可改为 Apache-2.0。

### 修复

- 原生整段评测恒报 HTTP 415：`wx.uploadFile` 不能设置表单段的 Content-Type，`config` 段以 text/plain 到达平台。2.0 自行拼装 multipart 请求体，`config` 段不带文件名，类型为 `application/json; charset=utf-8`，签名口径为 `config` 段原文，用 `wx.request` 发送。
- 看图说话题的图片在 `1.x` 版本中作为文本字段发送，2.0 改为文件段。
- 实时评测断线后只回调 `onClose`，调用方无法区分正常结束与连接断开。2.0 按重连语义处理，每个会话最终以 `onResult` 或 `onError` 结束。

### 破坏性变更

- 包名与引入方式：`1.x` 以源码目录 `lib/yugu-sdk.js` 拷贝进工程，2.0 改为 npm 包，代码改为 `require('@shengzhiai/yugu-miniprogram-sdk')`。
- 客户端缺少鉴权配置时直接抛出 `InvalidParameterException`，`1.x` 只打印警告。
- 实时评测的调用方式改为 `streamEvaluate(params, listener, options)` 与 `streamEvaluateCompat(coreType, params, listener, options)`，回调由参数对象移到监听器：`onOpen` 改为 `onStarted`，`onClose` 改为 `onClosed`，`onProgress` 改为 `onPartial`，`onError` 的参数由字符串改为 `YuguError`。`YuguStream` 改名为 `StreamSession`，`sendAudio` 不再接受 Base64 字符串。
- 录音器由 `createRecorder()` 返回的对象改为 `client.createRecorder()` 或 `new YuguRecorder()`，状态与回调见 README。
- 错误类型由单一的 `YuguError(message, code, data)` 改为按类别细分的异常类型，`code` 只表示错误码，HTTP 状态在 `httpStatus`，原始响应在 `rawBody`。
- 整段评测的音频参数由 `audioPath` 改为 `audio`，`audioPath` 仍可使用。
- 移除 `_sha256` 与 `WARNING_MESSAGES` 导出，签名请用 `signParams`，警告文本见 `WARNING_TABLE`。

## 版本 `1.0.0`，2026-06-24

### 新增

- 首个版本：整段评测，声通兼容评测，语音合成，报告查询，原生与声通兼容实时评测，签名与 token 两种鉴权，录音封装，以源码目录方式提供。
