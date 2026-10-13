# 更新日志

Android 核心 SDK `com.shengzhiai.yugu:yugu-android-sdk` 的版本记录，格式参照 Keep a Changelog，版本号遵循语义化版本。主版本号变化表示含破坏性变更。

## `2.1.1`，2026-10-13

### 新增

- 声通兼容评测支持中文开放题 `open.eval.cn`，常量为 `CompatCoreType.OPEN_EVAL_CN`，`evaluateCompat` 发往 `POST /open.eval.cn`，`streamEvaluateCompat` 连接 `WS /open.eval.cn`。此前这个内核在本地以错误码 90010 拒绝。`refText` 必填，填一条或多条参考答案，多条用竖线隔开，也可以填题目，`refPinyin` 不能代替，为空时在发请求前以错误码 90010 失败。`qType`，`keypoints`，`keywords` 放进 `params`。结果的分项在 `open`，与原生开放题相同，另有声通的 `pronunciation`，`fluency`，`integrity` 与 `speed`。实时评测的中间结果只有 `bytes`，没有朗读进度。用法见 README 的中文开放题一节。
- 平台的报告查询返回 `reportStatus` 与 `timing`，`ReportResult.data` 原样保留这两个字段，含义见 README 的接口概览一节。

### 变更

- 请求头 `User-Agent` 为 `yugu-android-sdk/2.1.1`。
- 与 `2.1.0` 源码兼容，二进制兼容，其他内核的请求与结果不变，英文 `open.eval` 仍在本地以错误码 90010 拒绝。

## `2.1.0`，2026-10-12

### 新增

- 朗读进度：实时评测打开实时反馈后，`onPartial` 在收音过程中告知参考文本读到了哪里，原生与声通兼容两条通道都有，调用 `end()` 后最后一段音频的进度仍在终评之前回调。`PartialResult` 新增 `readUnits`，`matchedUnits`，`totalUnits`，帧里没有时为 -1，`progress` 为逐字或逐词的 `ReadingWord` 列表，取自 `result.progress`，帧里没有时为空列表，`hasProgress` 表示帧里是否带进度。声通兼容通道的进度帧另带声通格式的增量 `result.words`，SDK 不解析，保留在 `raw` 里。`ReadingWord` 为值类型，含 `word`，`read`，`matched`。进度只对 16 kHz，16 位，单声道 PCM 生效。
- 原生实时评测的 `EvaluateConfig` 新增 `realtimeFeedback`，默认 false，构建器有同名方法。为 true 时开始帧带 `"realtimeFeedback": true`，为 false 时开始帧与 `2.0.0` 逐字相同，整段评测的 `config` 段始终不带该字段。声通兼容实时评测仍用 `CompatConfig.realtimeFeedback`，现在同样给出朗读进度。
- 断线重连后服务端从零重新计算进度。`REPLAY` 策略下会话记住上一次回调的进度，只回调 `readUnits` 与 `matchedUnits` 都不小于上一次的进度帧，其余的丢弃，写一条 DEBUG 日志，界面上的进度不会倒退。`DROP` 策略下新会话只评测重连之后的音频，每次重连成功后新会话的进度照常回调。不带进度的中间帧照常回调。

### 变更

- 请求头 `User-Agent` 为 `yugu-android-sdk/2.1.0`。
- 不带朗读进度的中间帧与 `2.0.0` 相同，只有 `bytes`，原有调用代码不需要修改。
- 与 `2.0.0` 源码兼容，二进制兼容，按 `2.0.0` 编译的 Java 与 Kotlin 代码换用 `2.1.0` 后不需要重新编译。

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
