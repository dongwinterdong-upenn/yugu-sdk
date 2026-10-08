# 变更记录

服务端 Java SDK `com.shengzhiai.yugu:yugu-java-sdk` 的版本记录。格式遵循 Keep a Changelog，见 `https://keepachangelog.com/zh-CN/1.1.0/`，版本号遵循语义化版本，见 `https://semver.org/lang/zh-CN/`。破坏性变更只在主版本号升级时出现，每个版本按新增，变更，修复，破坏性变更分类记录。

## `2.0.0` - 2026-10-08

### 破坏性变更

- Maven 坐标由 `tech.dragonai.yugu:yugu-java-sdk` 改为 `com.shengzhiai.yugu:yugu-java-sdk`，包名由 `tech.dragonai.yugu` 改为 `com.shengzhiai.yugu`。源码中的 import 需要整体替换。
- 默认地址由 `https://ygyx.dragonai.tech` 改为 `https://open.shengzhiai.com`，WebSocket 默认地址改为 `wss://open.shengzhiai.com`。
- `YuguException` 改为非受检异常，按错误类别派生 `AuthException`，`RateLimitException`，`QuotaExceededException` 等子类。原有的 `getBizCode()` 由 `getCode()` 取代，没有错误码时返回 0，没有 HTTP 状态时 `getHttpStatus()` 返回 0。
- `evaluateCompat` 的参数改为音频加 `CompatConfig`，`streamEvaluate` 的参数改为 `EvaluateConfig` 加监听器，`streamEvaluateCompat` 的参数改为 `CompatConfig` 加监听器。
- `getReport` 返回 `ReportResult`，原来的 `data` 节点由 `getData()` 取得。
- `StreamListener` 调整：`onResult(EvalResult)` 只回调终评，进度帧改由 `onPartial` 回调。`onError` 的参数改为 `YuguException`。`onConnected` 与 `onStarted` 不再带参数。新增状态，重连与预检回调。
- `StreamSession` 的 `sendAudio`，`end`，`close` 改为立即返回，不再返回 `CompletableFuture`，终评结果由 `result()` 取得。`sendText` 与 `sendAudioBase64` 移除。
- `EvalResult.getWarnings()` 改为返回 `Warning` 列表，含警告码与说明，原来的整数列表由 `getWarningCodes()` 取得。
- `Dimensions` 的 `accuracy` 与 `pronunciation` 分开保存，`getAccuracy()` 在平台未返回准确度时回落到发音分。
- 演示程序移出 SDK 的 jar，改为独立工程 `demos/java-cli`。

### 新增

- 幂等键：写操作自动生成 32 位小写十六进制的 `Idempotency-Key`，可通过 `RequestOptions` 指定，重试与重连复用同一个键。结果提供 `getIdempotencyKey()` 与 `isReplayed()`，后者对应响应头 `Idempotency-Replayed: true` 与实时结果帧的 `replayed` 字段。
- 重试与退避：`RetryPolicy` 默认最多重试 2 次，退避约 200 毫秒与 400 毫秒，抖动正负 30 %，遵守 `Retry-After`，整次调用受 300 秒总期限约束。只重试连接失败，读超时，HTTP 429，HTTP 5xx 与错误码表标记为可重试的错误码。每次重试写 WARN 日志，同时回调 `EventListener.onRetry`。
- 错误分类：`ErrorCategory` 枚举与 14 个异常子类，`YuguErrors.isRetryable` 公开可重试判定，`YuguErrors.fromCode` 与 `fromWarning` 按码构造异常，错误码常量由 `spec/errors.json` 生成到 `ErrorTable`。异常保留错误码，HTTP 状态，幂等键，记录号，尝试次数与截断到 4 KB 的原始响应体。
- 实时会话状态机：`SessionState` 共 10 个状态，`getState()` 与 `isActive()` 可随时查询，回调保证恰有一次结果或错误，`onClosed` 最后到达，同一会话的回调不并发。
- 断线重连：`ReconnectPolicy` 默认连续重连 8 次，等待合计约 23 秒，默认设置可以撑过 10 秒左右的断网。覆盖传输故障，异常关闭，心跳超时，连接超时，终评超时与可重试的错误帧。一个会话累计最多重连 `maxAttempts` 的 3 倍，默认 24 次。关闭码 1002，1003，1007，1008，1009，1010 以及 4000 到 4999 不重连，直接以 90005 失败。`AudioBufferPolicy` 提供 `REPLAY`，`DROP`，`FAIL` 三种音频处理方式，默认 `REPLAY` 重放全部音频。
- 心跳：每 15 秒发送 WebSocket 协议层 ping，30 秒收不到 pong 判定断线。结束后等待终评默认 300 秒。
- 生命周期：`YuguClient` 实现 `AutoCloseable`，`close()` 可重复调用，取消未结束的会话，关闭后的调用抛出错误码 90004。`StreamSession` 的 `cancel()` 与 `close()` 可重复调用。
- 音频预检：`AudioPrecheckMode` 提供 `OFF`，`WARN`，`REJECT` 三种模式，检查时长，大小，静音，音量与格式，本地码 90101 到 90105，`REJECT` 模式在上传前拦截无效音频。大小上限为整段评测 50 MB，实时评测单轮 10 MB，时长上限 300 秒。
- 外部音频：`evaluate` 与 `evaluateCompat` 接受 `byte[]`，`File`，`Path`，`InputStream` 与原始 PCM，`AudioSource.pcm` 自动封装为 WAV。
- 可观测性：`LogLevel` 五个级别，`YuguLogger` 接入任意日志框架，日志中的 secretKey，签名与令牌全部脱敏。`EventListener` 提供请求开始，请求结束，重试，会话状态与重连五个指标回调。
- 结果模型：逐句分数 `SentenceScore`，连读题型 `ConnectedScores`，开放题 `OpenScores`，英文单词的重音，字母发音与音节，`getRaw()` 保留完整响应。连读题型没有 `overall` 字段，`getOverall()` 改取 `connected_overall`。
- `strictAudio` 选项：平台返回警告 1001 时抛出 `AudioQualityException`。
- `CancellationToken`：从其他线程取消进行中的 REST 调用。
- 发布物：`-sources.jar` 与 `-javadoc.jar`，许可证，开发者与源码仓库信息，Maven Wrapper，JaCoCo 覆盖率门槛 70 %，持续集成脚本 `ci/java.sh`。
- 测试：签名向量，错误码表全量映射，各评测模式的结果解析，multipart 组装，退避计算，实时会话状态机，基于平台模拟服务的幂等，重试与断线集成测试。

### 变更

- 最低 Java 版本由 17 降为 11。
- 原生评测的 `config` 分片不带文件名，`Content-Type` 为 `application/json; charset=utf-8`，签名集合为 `config` 分片的完整 JSON 文本。
- 兼容评测的 `coreType` 只放在路径中，不再作为表单字段发送，签名集合为实际发送的表单字段。
- 语音合成请求体中的数字一律写成不带指数的十进制，签名使用相同的文本。
- 请求带 `User-Agent: yugu-java-sdk/2.0.0`。
- 实时评测单次 `sendAudio` 的音频按不超过 32000 字节一帧发送，低于平台 128 KB 的帧上限。
- HTTP 客户端固定使用 HTTP/1.1。
- 只配置 `baseUrl` 时，WebSocket 地址由其推导。

### 修复

- 报告查询的记录号按路径段编码，空格不再被编码为加号。
- 实时会话的发送改为有序队列，并发调用 `sendAudio` 不再触发 `IllegalStateException`。
- 结果中 `{code, message}` 形式的警告不再丢失。
- 实时会话断线时不再静默结束，调用方总能收到结果，错误或取消之一，随后收到 `onClosed`。

## `1.0.0` - 2026-06-24

### 新增

- 首个版本，坐标 `tech.dragonai.yugu:yugu-java-sdk:1.0.0`，Java 17。
- 原生整段评测 `evaluate`，声通兼容整段评测 `evaluateCompat`，语音合成 `tts`，报告查询 `getReport`。
- 原生实时评测 `streamEvaluate` 与声通兼容实时评测 `streamEvaluateCompat`。
- 签名器 `Signer`，与平台共用签名一致性向量。
- 结果模型 `EvalResult`，`Dimensions`，`WordScore`，`AsrAlignment`。

