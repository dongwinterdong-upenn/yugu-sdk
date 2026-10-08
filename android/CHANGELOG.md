# 更新日志

Android 核心 SDK `com.shengzhiai.yugu:yugu-android-sdk` 的版本记录，格式参照 Keep a Changelog，版本号遵循语义化版本。主版本号变化表示含破坏性变更。

## `2.0.0`，2026-10-08

### 新增

- 幂等键：`evaluate`，`evaluateCompat`，`tts` 自动带 `Idempotency-Key`，实时评测在握手参数与开始帧里带 `idempotencyKey`。重试与重连复用同一个键，结果给出 `idempotencyKey` 与 `replayed`。调用方可用 `RequestOptions` 与 `SessionOptions` 指定键。
- REST 自动重试：`RetryPolicy` 指数退避加抖动，默认最多 3 次尝试，遵守 `Retry-After`，`totalTimeoutMs` 限制总耗时。每次重试回调 `onRetry`，写 WARN 日志。
- 实时评测自动重连：`SessionState` 状态机，`getState()` 与 `isActive()` 状态查询，`onConnected`，`onStarted`，`onReconnecting`，`onReconnected`，`onClosed` 结构化回调，`REPLAY`，`DROP`，`FAIL` 三种断线缓冲策略，默认连续重连 8 次，合计约 23 秒，一次会话最多重连 24 次，15 秒心跳，握手超时与终评超时。协议类关闭码直接结束会话，大块音频按 32000 字节分帧发送。
- 错误分类：`YuguException` 与 14 个按类别划分的子类，`YuguErrors.isRetryable` 可重试判定，`ErrorCodes` 错误码常量，`WarningCode` 警告码枚举，错误码表由 `spec/errors.json` 生成。
- 生命周期：`YuguClient.close()`，会话的 `cancel()` 与 `close()`，录音器的 `release()`，全部可以重复调用。
- 录音器：状态查询，暂停与继续，音量等级回调，最长时长，监听器注销，边录边送进实时会话，每条出错路径都释放麦克风。
- 外部音频：`AudioInput` 接受字节数组，文件，输入流与裸 PCM，实时会话接受调用方自有的 PCM。
- 本地音频预检：时长，大小，静音，音量，格式，可选 `OFF`，`WARN`，`REJECT`。整段上传上限 50 MB，实时评测一轮上限 10 MB。
- 可观测性：日志级别，可替换的日志输出，凭据脱敏，指标回调 `YuguEventListener`。
- 异步方法 `evaluateAsync`，`evaluateCompatAsync`，`ttsAsync`，`getReportAsync`，取消令牌 `CancellationToken`。
- 结果模型：连读模式 `ConnectedScores`，开放题 `OpenTaskScores`，段落评测按句的逐字详分，`resultRaw` 与 `rawJson` 原始数据。
- 发布到 `https://open.shengzhiai.com/maven/`：AAR，源码包，接口文档包，POM 与校验和。附带 Gradle Wrapper，单元测试与集成测试，JaCoCo 覆盖率报告，持续集成脚本 `ci/android.sh`，独立示例工程 `demos/android-demo`。

### 变更

- 默认基址改为 `https://open.shengzhiai.com` 与 `wss://open.shengzhiai.com`。
- 最低系统由 `minSdk 24` 降到 `minSdk 21`，字节码由 Java 17 改为 Java 8。
- 分数字段由整数改为 `Double`，保留平台给出的小数。
- 结果的 `report` 与原始数据由 `JSONObject` 改为 `Map`，另有响应原文 `rawJson`，SDK 不再依赖 `org.json`。
- 心跳由 20 秒改为 15 秒，未按时收到 pong 即判定连接失效，随后按策略重连。
- 请求头 `User-Agent` 为 `yugu-android-sdk/2.0.0`。
- 已开始发送的请求只由 SDK 按策略重发，OkHttp 只在连接失败，请求还没有发出时改连下一个地址，尝试次数可观测。

### 修复

- 原生整段评测只对铺平后的 10 个配置字段签名，配置里带其他字段时平台返回签名错误 2003。现在对 `config` 段原文签名，与平台规则逐字一致。
- 报告查询把 `recordId` 计入签名，平台按空集校验，签名不符。现在报告查询签空集。
- 分数用整数读取，例如 93.7 被截成 93。现在按小数读取。
- 服务端在终评前关闭连接时没有任何回调，调用方会一直等待。现在会话一定以 `onResult` 或 `onError` 结束，最后回调 `onClosed`。
- 录音器初始化失败时没有释放 AudioRecord，麦克风可能一直被占用。
- 双栈网络里域名的第一个地址连不上时，请求不会改连其余地址，例如 IPv6 不通的网络里每次尝试都连 IPv6，重试用尽后失败。现在同一次尝试内改连下一个地址，不消耗重试次数，实时评测的握手同样改连。IPv6 丢包时先等满一次 `connectTimeoutMs` 再改走 IPv4，实时评测建连与升级的时限为 `connectTimeoutMs` 的 3 倍，留出改连的时间。

### 破坏性变更

- Maven 坐标与包名由 `tech.dragonai.yugu` 改为 `com.shengzhiai.yugu`。
- 客户端改用 `YuguClient.builder()` 创建，鉴权写作 `Auth.appKey(appKey, secretKey)` 或 `Auth.token(jwt)`，`Auth.Sig` 改名为 `Auth.Signature`。
- 异常由继承 `IOException` 的 `YuguClient.YuguException` 改为继承 `RuntimeException` 的 `com.shengzhiai.yugu.YuguException`，按类别分为子类，`httpCode` 改名 `httpStatus`，`bizCode` 改名 `code`。
- 整段评测的音频参数改为 `AudioInput`，另保留 `ByteArray`，`File`，`InputStream` 三个重载。语音合成参数改为 `TtsRequest`，声通兼容评测参数改为 `CompatConfig`，报告查询返回 `ReportResult`。
- 实时评测回调接口 `YuguClient.StreamCallback` 改为 `StreamListener`，`onError` 的参数改为 `YuguException`，`onPartial` 的参数改为 `PartialResult`。实时评测方法改为接受 `EvaluateConfig` 与 `CompatConfig`。
- `Recorder` 移到 `com.shengzhiai.yugu.audio`，`startStreaming(listener)` 改为 `start(session)` 与 `Listener.onFrame`。
- `EvalResult.dims` 改名为 `dimensions`，`warnings` 由整数列表改为 `Warning` 列表。

逐项的改写方法见 [`MIGRATION-2.0.md`](../MIGRATION-2.0.md)。

## `1.0.0`，2026-06-24

### 新增

- 首个版本，以源码包分发：整段评测，声通兼容整段评测，语音合成，报告查询，原生与声通兼容实时评测，录音器，签名器与签名一致性测试。
