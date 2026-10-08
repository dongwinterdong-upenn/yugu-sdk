# 变更记录

格式参照 Keep a Changelog，版本号规则见 SDK 总仓库的 `COMPATIBILITY.md`，由 1 版本升级的步骤见 `MIGRATION-2.0.md`。

## 版本 `2.0.0`，2026-10-08

### 新增

- 产品拆为 `YuguCore` 与 `YuguSDK`。`YuguCore` 只依赖 Foundation，可在 Linux 上构建与测试，`YuguSDK` 在其上加录音器。
- 写操作与实时会话自动带幂等键，重试与重连复用同一个键，结果带 `idempotencyKey` 与 `replayed`，平台对同一个键只计费一次。
- 重试策略 `RetryPolicy`：指数退避加随机抖动，遵守 `Retry-After`，总时限 `totalTimeoutMs`，重试事件进入日志与指标回调，公开的可重试判定 `YuguErrors.isRetryable`。
- 实时评测会话 `YuguStreamSession`：十种状态，断线自动重连 `ReconnectPolicy`，音频缓冲策略 REPLAY，DROP，FAIL，协议层心跳，握手超时与终评超时，结构化回调 `YuguStreamListener`。
- 实时会话的保护规则：超过 32000 字节的音频拆成小帧发送，整个会话的重连总数不超过 `maxAttempts` 的 3 倍，服务端以 1002，1003，1007，1008，1009，1010 或 4000 到 4999 之间的代码关闭时不重连。
- 错误类型 `YuguError`：十六个类别与对应的便捷属性，保留原始错误码，HTTP 状态，幂等键，尝试次数与响应原文。码表 `ErrorTable` 由 `spec/errors.json` 生成。
- 资源释放与状态查询：客户端 `close()`，会话 `cancel()` 与 `close()`，录音器 `release()`，均可重复调用，会话与录音器可随时查询状态。
- 录音器的暂停与继续，音频中断与路由变化处理，WAV 导出，640 字节分帧回调。
- 上传前音频预检，WARN 与 REJECT 两种方式，实时会话在 `end()` 时检查。
- 外部音频输入 `AudioInput`：内存数据，文件，裸 PCM。
- 日志级别，日志脱敏，指标回调 `YuguEventListener`。
- `async` 写法与任务取消，取消令牌 `YuguCancellationToken`。
- 连读题与开放题的结果模型 `ConnectedScores` 与 `OpenTaskScores`，段落题的逐句详情，结果保留原始 JSON。
- 语音合成与报告查询的结果模型 `TTSResult` 与 `ReportResult`。
- 支持 macOS 11，新增 CocoaPods 规格文件 `YuguSDK.podspec`，单元测试，集成测试与覆盖率报告。

### 变更

- 默认基址改为 `https://open.shengzhiai.com` 与 `wss://open.shengzhiai.com`，`wsBaseUrl` 未设置时由 `baseUrl` 推出。
- 每个请求与握手带 `User-Agent: yugu-ios-sdk/2.0.0`。
- 语音合成的签名改为请求体顶层的标量字段，取值为实际发出的 JSON 文本，与各端一致。
- HMAC-SHA256 改用 SDK 自带的实现，不再依赖 CryptoKit。
- 响应改用 SDK 自带的 JSON 解析器，各系统版本上的行为一致。
- 许可改为 Apache-2.0。

### 修复

- 签名修复：`1.0.0` 版把小数字段按 `1.0` 的形式签名，JSON 里写的却是 `1`，整数值的小数字段因此签名失败。`2.0.0` 版签名的对象是 `config` 分片的整段 JSON 文本，JSON 由 SDK 按固定顺序生成，整数值写成整数，发出的文本与签名的文本一字不差。
- 实时评测握手参数里签名值的 `+`，`/`，`=` 改为百分号编码，避免服务端把 `+` 解码成空格后验签失败。
- 报告查询的签名改为空参数集，`recordId` 是路径的一部分，不参与签名。
- 实时会话的结果与错误回调只触发一次，连接中断与服务端无响应都有回调，不再出现静默停住。

### 破坏性变更

- 配置类型 `YuguConfig` 改为 `YuguClientOptions`，鉴权写法 `.signature(appKey:secretKey:)` 改为 `.appKey(_:secretKey:)`。
- 整段评测的参数 `audioWav: Data` 改为 `audio: AudioInput`，完成回调的错误类型改为 `YuguError`，返回值为可取消的 `YuguCall`。
- `evaluateCompat` 的参数改为 `coreType:audio:params:`，参数类型为 `CompatParams`。
- `tts` 返回 `TTSResult`，`getReport` 返回 `ReportResult`。
- `EvalResult.dims` 改名为 `dimensions`，`warnings` 由整数数组改为 `YuguWarning` 数组。
- 实时评测由 `streamEvaluate(config:)` 改为 `streamEvaluate(config:options:listener:)`，`connect()` 改为 `start()`，`finish()` 改为 `end()`，代理协议 `YuguStreamDelegate` 改为 `YuguStreamListener`，`sendAudio` 改为可抛出错误。
- 录音器的 `onFrame` 闭包改为监听器 `YuguRecorderListener`，`stop()` 返回 `YuguRecording`。
- `CoreType` 与 `CompatCoreType` 由枚举改为结构体，`.sentence` 等写法不变。
- Swift 工具链要求 5.9 起，最低系统版本仍为 iOS 13。

## 版本 `1.0.0`，2026-06-24

### 新增

- 首个版本：原生整段评测，声通兼容整段评测，语音合成，报告查询，原生与声通兼容实时评测，签名与 token 两种鉴权，AVAudioEngine 录音与 WAV 封装。
