# 变更记录

`@shengzhiai/yugu-web-sdk` 的版本记录，格式参照 Keep a Changelog，版本号遵循语义化版本。每个版本按新增，变更，修复，破坏性变更分类。

## `2.0.0` 2026-10-08

### 新增

- 幂等键：`evaluate`，`evaluateCompat`，`tts` 自动携带 `Idempotency-Key`，实时会话在握手 query 与开始帧里携带 `idempotencyKey`。调用方可以用 `idempotencyKey` 选项指定键，重试与重连复用同一个键。结果带 `idempotencyKey` 与 `replayed`。
- 重试与退避：`RetryPolicy` 默认最多重试 2 次，指数退避加正负 30% 抖动，遵守 `Retry-After`，`totalTimeoutMs` 限制整次调用的总时长。重试事件通过 `eventListener.onRetry` 与 WARN 日志暴露。
- 类型化错误：`YuguError` 与 14 个子类，例如 `AuthException`，`RateLimitException`，`QuotaExceededException`，`AudioQualityException`，支持 `instanceof` 分支。错误带类别，原始错误码，HTTP 状态，可重试标记，幂等键，尝试次数与截断的响应体。
- `isRetryable(error)` 判定函数，与重试循环和重连共用同一套规则。
- 错误码表常量 `ErrorCodes`，`WarningCode`，`ERROR_TABLE`，`WARNING_TABLE`，`LOCAL_TABLE`，与 `YuguErrors.fromCode` 等工厂函数，表内容由 `spec/errors.json` 生成。
- 实时会话状态机：IDLE，CONNECTING，CONNECTED，STARTED，ENDING，RECONNECTING，COMPLETED，FAILED，CANCELLED，CLOSED，`getState()` 与 `isActive()` 查询当前状态。
- 断线自动重连与三种音频缓冲策略 `REPLAY`，`DROP`，`FAIL`，回调 `onReconnecting` 与 `onReconnected`。默认最多连续重连 8 次，等待约 0.5，1，2，4，4，4，4，4 秒，合计约 23.5 秒，覆盖 10 秒断网。重连成功后连续失败次数清零，一个会话累计最多重连 `maxAttempts` 的 3 倍次数。
- 应用层心跳 `{"cmd":"ping"}`，默认在平台确认支持后才开启，原生会话自动探测。
- 连接超时，心跳超时与终评超时 `resultTimeoutMs`，任何中断都以回调结束，不会静默卡住。
- 生命周期接口：`client.close()`，`session.cancel()`，`session.close()`，`recorder.release()` 都可以重复调用。
- 录音器状态 IDLE，RECORDING，PAUSED，STOPPED，RELEASED，新增 `pause()`，`resume()`，`release()`，`setListener()`，`start({ session })` 直接把 640 字节分帧送入实时会话。
- 音频预检：时长，大小，静音，音量与 WAV 格式检查，模式 `OFF`，`WARN`，`REJECT`，警告写入 `localWarnings` 与 `onWarning`。大小上限与平台一致，整段评测上传 50 MB，实时评测一轮 10 MB，常量为 `MAX_UPLOAD_BYTES` 与 `MAX_STREAM_BYTES`。
- 外部音频：评测接口接受 `Blob`，`File`，`ArrayBuffer`，`Uint8Array` 与任意 `ArrayBufferView`，裸 PCM 通过 `audioFormat: 'pcm'` 封装成 WAV。
- 结果模型新增 `connected` 与 `open` 字段，覆盖连读题型与开放题型的专项分，另有 `sentences`，`paragraphs`，`yuguScores`，`compositeReport`，`durationSeconds`，`raw` 保留平台原始 JSON。
- 完整的 TypeScript 声明 `types/index.d.ts`，CI 逐项比对运行时导出与声明。
- 构建产物：ESM `dist/yugu-sdk.mjs`，UMD `dist/yugu-sdk.umd.js` 与压缩版，全局对象 `YuguSDK`，AudioWorklet 模块 `dist/yugu-pcm-worklet.js`。
- 日志级别 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，日志脱敏，指标回调 `eventListener`。
- 支持 Node.js 18 及以上版本，可注入 `fetch`，`WebSocket` 与 `crypto`。
- `session.waitForResult()`，`session.getStats()`，`session.setListener()`。

### 变更

- 包名改为 `@shengzhiai/yugu-web-sdk`，发布到 `https://open.shengzhiai.com/npm/`，按 Apache-2.0 许可发布。
- 默认服务地址改为 `https://open.shengzhiai.com` 与 `wss://open.shengzhiai.com`，只设置 `baseUrl` 时 WebSocket 地址由 `baseUrl` 推出。
- 整段评测与声通兼容评测的 multipart 请求体由 SDK 手工构造，不再使用 FormData。
- 签名口径与平台一致：整段评测签名 `config` 段的 JSON 原文，报告查询签名空集，WebSocket 握手签名除 `signature` 以外的全部 query 参数。
- 浏览器中 SDK 版本通过请求头 `X-Yugu-SDK` 发送，Node.js 中通过 `User-Agent` 发送。
- 声通兼容实时评测的业务参数只放在参数帧里，不再复制到握手 query。
- 大于 32000 字节的音频分片拆成多帧发送，避开平台 128 KB 的单帧上限。关闭码 1009 等重连无法改变结果的关闭直接结束会话。
- 声通兼容整段评测只支持签名鉴权，token 模式下在发出请求前报错，平台要求请求头 `X-App-Key`。

### 修复

- `config` 段由 FormData 生成时带有文件名，平台把这一段当成文件，导致 415 或签名验证失败 2003。
- 报告查询把 `recordId` 计入签名，与平台的签名口径不符。
- `1.x` 把 WebSocket 的非致命错误事件转给 `onError` 又不结束会话，调用方无法区分评测结束与连接断开。`2.0.0` 每个会话只有一次终态回调，`onClosed` 总在最后。
- Blob 音频分片转成 base64 时异步读取，分片可能乱序发送。
- `dims.accuracy` 固定取发音分。`2.0.0` 优先取平台返回的准确度分。
- 连读题型没有 `result.overall`，总分为空。`2.0.0` 的 `overall` 取 `result.connected_overall`。
- 平台返回空的 `asrText` 或 `standardAudio` 对象时，结果里出现缺字段的对象。`2.0.0` 返回 `null`。

### 破坏性变更

- 包名由 `@yugu/web-sdk` 改为 `@shengzhiai/yugu-web-sdk`。入口文件 `src/yugu-sdk.js` 与子路径 `@yugu/web-sdk/recorder` 不再提供，全部接口从包根导入。
- 移除 `streamRealtime`，`realtimeUrl` 选项与 `evalStart()`。这组接口连接内部引擎地址，不属于公开平台接口，实时评测改用 `streamEvaluate` 或 `streamEvaluateCompat`。
- 构造客户端必须提供鉴权信息，`token` 与 `appKey` 加 `secretKey` 只能二选一。`token`，`appKey`，`secretKey` 不再是客户端对象的公开字段。
- `YuguError` 的 `status` 改为 `httpStatus`，`raw` 改为字符串 `rawBody`，错误按类别细分为子类。
- `result.warnings` 由数字数组改为 `{code, message}` 对象数组。
- 实时会话的监听对象必须提供 `onResult` 与 `onError`。`onClose(event)` 改为 `onClosed(code, reason)`，`onError` 只在会话失败时调用一次，未知帧不再转给 `onPartial`，`onConnected` 与 `onStarted` 只在首次连接时触发。
- 会话的 `readyState` 改为 `getState()`，`sendFrame()` 移除，`sendAudio()` 不再接受 Blob，传入前用 `await blob.arrayBuffer()` 转换。
- 录音器在暂停状态与释放之后调用 `start()` 抛出 `IllegalSessionStateException`。

## `1.0.0` 2026-06-24

### 新增

- 首个版本：原生整段评测，声通兼容整段评测，原生与声通兼容实时评测，语音合成，报告查询，浏览器录音，HMAC-SHA256 签名工具函数与签名一致性测试向量。
