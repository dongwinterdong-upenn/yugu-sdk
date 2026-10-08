# 优谷雅言 Android SDK

优谷雅言语音评测的 Android 客户端，Kotlin 编写，Java 可直接调用。SDK 是接入平台的推荐方式，没有 SDK 的语言可按 [CONTRACT.md](../CONTRACT.md) 直连接口。

| 项 | 内容 |
|---|---|
| 坐标 | `com.shengzhiai.yugu:yugu-android-sdk:2.0.0` |
| 仓库 | `https://open.shengzhiai.com/maven/`，公开读取，不需要账号 |
| 包名 | `com.shengzhiai.yugu` |
| 最低系统 | Android 5.0，`minSdk 21` |
| 字节码 | Java 8，Kotlin 1.9 |
| 运行时依赖 | OkHttp 4.12，Kotlin 标准库 |
| 许可 | Apache License 2.0 |

能力：整段评测，声通兼容整段评测，语音合成，报告查询，原生实时评测，声通兼容实时评测，录音器。

## 安装

在 `settings.gradle` 里加入优谷雅言 Maven 仓库：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url "https://open.shengzhiai.com/maven/" }
    }
}
```

在模块的 `build.gradle` 里声明依赖：

```groovy
dependencies {
    implementation "com.shengzhiai.yugu:yugu-android-sdk:2.0.0"
}
```

Kotlin DSL 写法：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://open.shengzhiai.com/maven/") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.shengzhiai.yugu:yugu-android-sdk:2.0.0")
}
```

工程仍在顶层 `build.gradle` 的 `allprojects` 里声明仓库时，把同一行 `maven { url "https://open.shengzhiai.com/maven/" }` 加进那里。

仓库里的每个版本发布后不再改动，依赖写成具体版本号即可锁定，需要回退时改回历史版本号。每个文件都附带 `.md5`，`.sha1`，`.sha256`，`.sha512` 校验和，另有源码包 `-sources.jar` 与接口文档包 `-javadoc.jar`。`1.x` 版本没有发布到 Maven 仓库，源码包仍可在 `https://open.shengzhiai.com/sdk/` 下载，已停止维护。

SDK 自带 R8 混淆保留规则，接入方不需要额外配置。

## 权限

SDK 的清单文件已声明 `INTERNET`，构建时自动合并。使用录音器时，宿主应用需要声明录音权限：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Android 6.0 起录音权限需要在运行时申请：

```kotlin
if (Build.VERSION.SDK_INT >= 23 &&
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
) {
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
}
```

把 `Context` 传给 `Recorder` 后，SDK 在开始录音前检查权限，没有权限时抛 `PermissionException`，错误码 90201。

## 五分钟快速开始

第一步，创建客户端。整个应用共用一个实例即可。

```kotlin
val client = YuguClient.builder()
    .auth(Auth.appKey("YOUR_APP_KEY", "YOUR_SECRET_KEY"))
    .build()
```

第二步，准备一段 16 kHz，16 位，单声道的 WAV 音频，调用整段评测。REST 方法是阻塞调用，放在后台线程执行：

```kotlin
thread {
    val result = client.evaluate(
        EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN),
        File(filesDir, "sample.wav"),
    )
    Log.i("yugu", "总分 ${result.overall}，发音 ${result.dimensions.pronunciation}")
}
```

也可以使用异步方法，回调默认在主线程执行，可以直接更新界面：

```kotlin
client.evaluateAsync(
    EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN),
    AudioInput.fromFile(File(filesDir, "sample.wav")),
    object : YuguCallback<EvalResult> {
        override fun onSuccess(result: EvalResult) {
            scoreView.text = "总分 ${result.overall}"
        }

        override fun onFailure(error: YuguException) {
            scoreView.text = "评测失败 ${error.code} ${error.message}"
        }
    },
)
```

第三步，应用退出或不再评测时关闭客户端：

```kotlin
client.close()
```

完整的可运行工程见 [demos/android-demo](../demos/android-demo)，包含录音评测，示例音频评测与实时评测。

## 客户端选项

| 选项 | 默认值 | 含义 |
|---|---|---|
| `baseUrl` | `https://open.shengzhiai.com` | REST 基址 |
| `wsBaseUrl` | `wss://open.shengzhiai.com` | 实时评测基址 |
| `auth` | 必填 | `Auth.appKey(appKey, secretKey)` 或 `Auth.token(jwt)` |
| `connectTimeoutMs` | 10000 | TCP 与 TLS 建连超时，也是实时评测从握手到收到开始帧的时限 |
| `readTimeoutMs` | 120000 | 单次 REST 尝试的读取超时 |
| `totalTimeoutMs` | 300000 | 一次逻辑调用的总时限，含全部重试与等待 |
| `retryPolicy` | 见重试一节 | REST 重试策略 |
| `autoIdempotencyKey` | true | 调用方未指定幂等键时自动生成 |
| `logLevel` | `WARN` | 日志级别 |
| `logger` | 写入 Logcat | 日志输出 |
| `eventListener` | 无 | 指标回调 |
| `audioPrecheck` | `WARN` | 本地音频预检方式 |
| `strictAudio` | false | 整段评测结果带 1001 警告时抛异常 |
| `userAgent` | `yugu-android-sdk/2.0.0` | 请求头 `User-Agent` |
| `reconnectPolicy` | 见重连一节 | 实时评测重连策略 |
| `audioBufferPolicy` | `REPLAY` | 实时评测断线期间的音频处理方式 |
| `resultTimeoutMs` | 300000 | 实时评测调用 `end()` 后等待终评的时限 |
| `pingIntervalMs` | 15000 | 实时评测心跳间隔 |
| `maxReplayBytes` | 10 MB | 重放缓冲上限 |
| `callbackExecutor` | 主线程 | 实时评测与异步方法的回调线程 |
| `okHttpClient` | SDK 共用实例 | 自定义代理与证书时传入，SDK 在其基础上派生超时设置 |

单次调用的选项用 `RequestOptions` 传入：`idempotencyKey` 幂等键，`totalTimeoutMs` 总时限，`readTimeoutMs` 读取超时，`retryPolicy` 重试策略，`cancellationToken` 取消令牌。实时会话的选项用 `SessionOptions` 传入：`idempotencyKey`，`reconnectPolicy`，`audioBufferPolicy`，`resultTimeoutMs`，`extraQuery`。

## 接口概览

| 方法 | 接口 | 说明 |
|---|---|---|
| `evaluate(config, audio, options, image)` | `POST /api/v1/evaluate` | 原生整段评测，音频可为 `AudioInput`，`ByteArray`，`File`，`InputStream` |
| `evaluateCompat(config, audio, options)` | `POST /{coreType}` | 声通兼容整段评测，`coreType` 取声通命名 |
| `tts(request, options)` | `POST /api/v1/tts/generate` | 语音合成，返回可直接播放的 `resolvedUrl` |
| `getReport(recordId, options)` | `GET /api/v1/report/{recordId}` | 报告查询 |
| `streamEvaluate(config, listener, options)` | `WS /api/v1/ws/evaluate` | 原生实时评测，返回 `StreamSession` |
| `streamEvaluateCompat(config, listener, options)` | `WS /{coreType}` | 声通兼容实时评测，可开启进度中间帧 |
| `evaluateAsync`，`evaluateCompatAsync`，`ttsAsync`，`getReportAsync` | 同上 | 异步版本，返回可取消的 `YuguCall` |
| `close()` | 无 | 关闭客户端，可重复调用 |

原生 `coreType`：`word`，`sentence`，`passage`，`connected`，`open`，`alpha`，`pinyin`。声通兼容 `coreType`：`word.eval`，`word.eval.pro`，`sent.eval`，`sent.eval.pro`，`para.eval`，`alpha.eval`，`word.eval.cn`，`sent.eval.cn`，`para.eval.cn`，`pinyin`。

`EvaluateConfig` 字段与平台 `config` 段一一对应：`coreType`，`referenceText`，`language`，`includeReport`，`includeStandardAudio`，`includeAsrText`，`slack`，`scale`，`precision`，`agegroup`，`toneWeight`，`refPinyin`，`phonemeOutput`，`taskType`，`paragraphNeedWordScore`。平台新增而 SDK 尚未列出的字段放进 `extra`。值为 null 的字段不发送，由平台取默认值。

看图说话需要上传图片：

```kotlin
client.evaluate(
    EvaluateConfig(CoreType.OPEN, "描述这张图片", Language.ZH_CN, taskType = TaskType.PICTURE),
    AudioInput.fromFile(wavFile),
    RequestOptions.DEFAULT,
    ImageInput.fromFile(jpgFile),
)
```

## 各评测模式的取分字段

结果统一解析为 `EvalResult`。`raw` 为整个响应，`resultRaw` 为 `result` 节点，`rawJson` 为响应原文，SDK 未建模的字段可从这三处读取。平台各字段的含义见 [`RESULTS.md`](../RESULTS.md)。

| 模式 | 总分 | 分项 | 明细 |
|---|---|---|---|
| `word`，`sentence`，`alpha` | `overall` | `dimensions.pronunciation`，`fluency`，`integrity`，`rhythm`，`accuracy`，`readingSkill` | `words`，`sentences`，`asrText` |
| 中文 `sentence`，`pinyin` | `overall` | 同上，另有 `tone` 声调与 `emotion` | `words` 带 `pinyin`，`symbolPinyin`，`tone`，`toneScore` |
| `passage` | `overall` | 同 `sentence` | `sentences` 按句给分，配置 `paragraphNeedWordScore = 1` 时每句带逐字 `words` |
| `connected` | `overall`，取 `result.connected_overall` | `connected.linking`，`rhythm`，`elision`，`reduction` | `connected.boundaries` 每个词间边界的 `between`，`tags`，`realized`，`startMs`，`endMs` |
| `open` | `overall` | `open.content`，`open.languageUse`，`open.delivery` | `open.transcript`，`open.feedback`，`open.audit` |
| 声通兼容 | `overall` | 同原生对应模式 | `words`，`sentences` |

其余公共字段：`recordId` 评测记录号，`speed` 语速，`rearTone` 句末调型，`duration` 与 `durationSeconds` 时长，`coreType` 与 `language` 平台回传的模式与语种，`report` 评测报告，`standardAudio` 示范音，`warnings` 平台警告，`localWarnings` 本地预检警告，`idempotencyKey` 本次使用的幂等键，`replayed` 是否为幂等重放。

`report.dimensionScores` 里值为 null 的维度表示本次不评，不等于零分。时间单位：`span` 为 10 毫秒，`boundaries` 为毫秒。

## 错误处理

全部错误都是 `YuguException` 的子类，带 `category` 类别，`code` 错误码，`httpStatus`，`message`，`retryable`，`idempotencyKey`，`recordId`，`attempts` 尝试次数，`rawBody` 原始响应体，最多 4 KB。错误码含义见 [ERRORS.md](../ERRORS.md)，SDK 内置同一份表，常量在 `com.shengzhiai.yugu.errors.ErrorCodes`。

| 异常类型 | 类别 | 典型错误码 |
|---|---|---|
| `NetworkException` | `NETWORK` | 90001 网络错误，90006 重连用尽，90011 证书校验失败 |
| `RequestTimeoutException` | `TIMEOUT` | 90002 超时，90007 等待终评超时，HTTP 408 |
| `AuthException` | `AUTH` | 40100，2001 到 2011 |
| `PermissionException` | `PERMISSION` | 40300，1004，1005，1012，1013，90201 |
| `InvalidParameterException` | `INVALID_PARAM` | 40001，90010 |
| `NotFoundException` | `NOT_FOUND` | 40400 |
| `ConflictException` | `CONFLICT` | 40900，40901，40903，1311 |
| `RateLimitException` | `RATE_LIMIT` | 42900，42901，3001 到 3003 |
| `QuotaExceededException` | `QUOTA` | 40902，42902，42903，2012，1310 |
| `ServerException` | `SERVER` 或 `UPSTREAM` | 50000，50010，50200 |
| `AudioQualityException` | `AUDIO` | 90101 到 90105，开启 `strictAudio` 后的 1001 |
| `IllegalSessionStateException` | `STATE` | 90004 客户端已关闭，90008，90009，90202，90203 |
| `RequestCancelledException` | `CANCELLED` | 90003 |
| `ProtocolViolationException` | `PROTOCOL` | 90005 |

映射顺序：SDK 本地错误，响应体里的非零 `code` 查错误码表，FastAPI 形态 `detail` 里的 `[2001]` 前缀，最后按 HTTP 状态归类。错误码不在错误码表里时保留原码，类别与可重试按 HTTP 状态判定。HTTP 状态不在 [ERRORS.md](../ERRORS.md) 的兜底表里时按类别归类：其余 4xx 为 `INVALID_PARAM`，不重试，其余 5xx 为 `SERVER`，只有列在可重试状态里的才重试，其他状态为 `UNKNOWN`。

```kotlin
try {
    client.evaluate(config, wavFile)
} catch (e: AuthException) {
    // 凭据错误，重试无效，提示检查 appKey 与 secretKey
} catch (e: AudioQualityException) {
    // 音频不合格，提示重录
} catch (e: YuguException) {
    if (YuguErrors.isRetryable(e)) {
        // SDK 已按策略重试过，仍失败，可稍后再试
    }
    Log.w("yugu", e.toString())
}
```

平台的音频质量警告 1001 到 1005 与 1009 不抛异常，列在 `result.warnings`，`WarningCode` 枚举这些码。需要把警告当异常处理时，用 `YuguErrors.fromWarning(code)` 构造 `AudioQualityException`。开启 `strictAudio` 后，整段评测结果带 1001 未检测到有效音频时直接抛出。

错误码按场景查表，不只看数字：错误响应体与错误帧里的 `code`，以及 `YuguErrors.fromCode`，查错误码表，结果里的警告与 `YuguErrors.fromWarning` 查警告码表，90000 起的本地码查本地码表。1004 与 1005 因此在错误响应里是账户状态，在结果的警告列表里是音频问题。只出现在警告码表里的码，例如 1001，`fromCode` 同样构造 `AudioQualityException`。

## 重试与幂等

### 重试策略

REST 调用失败后，SDK 按 `RetryPolicy` 自动重试：

| 字段 | 默认值 |
|---|---|
| `maxRetries` | 2，最多 3 次尝试 |
| `initialDelayMs` | 200 |
| `multiplier` | 2.0 |
| `maxDelayMs` | 4000 |
| `jitter` | 0.3 |
| `respectRetryAfter` | true |
| `maxRetryAfterMs` | 30000 |

第 n 次重试前的等待时长：

```
delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))
带 Retry-After 时 delay = max(delay, min(retryAfterMs, maxRetryAfterMs))
now + delay 超过总时限时停止重试，抛出最后一次的错误
```

默认值给出约 200 毫秒与 400 毫秒两次重试，各自上下浮动 30%。

是否重试由 `YuguErrors.isRetryable` 判定，SDK 内部重试与实时评测重连共用同一个判定函数，规则按顺序第一条命中为准：

1. 本地错误码 90001，90002，90007 可重试，其余本地错误码不可重试。
2. 有平台错误码时取错误码表里的可重试标记，例如 40901，42900，42901，50000，50200 可重试。
3. 没有错误码或错误码不认识时，HTTP 408，425，429，500，502，503，504 可重试。

参数错误，鉴权失败，额度不足等不会重试。每次重试都会回调 `eventListener.onRetry`，同时写一条 WARN 日志，例如 `retry 1/2 in 231 ms: HTTP 503 code=50200`。

### 幂等键

写操作 `evaluate`，`evaluateCompat`，`tts` 带请求头 `Idempotency-Key`，实时评测在握手参数与开始帧里带 `idempotencyKey`。

- 调用方不指定时，SDK 为每次逻辑调用生成 32 位小写十六进制键。
- 同一次调用的全部重试与实时评测的全部重连都复用同一个键，重试循环里不会生成新键。
- 调用方可通过 `RequestOptions(idempotencyKey = "...")` 或 `SessionOptions(idempotencyKey = "...")` 指定键，键须为 1 到 200 个可见 ASCII 字符，不符合时在发请求前抛 `InvalidParameterException`，错误码 90010。
- 作用域为 appKey，使用 token 鉴权时为用户。有效期 24 小时。
- 平台语义：同一个键加同一份请求，重放首次成功的结果，不再计费，结果的 `replayed` 为 true。首次请求仍在处理时，平台最多等待 30 秒后重放，或返回 409 错误码 40901，同时带 `Retry-After`，SDK 会按同一个键重试。同一个键用于另一份不同的请求时返回 40903，不可重试。首次请求失败时键被释放，可以用同一个键重新提交。
- 关闭 `autoIdempotencyKey` 后，未指定键的写请求不重试，实时会话不重连，避免重复计费，DEBUG 日志会说明原因。`getReport` 是只读请求，不带幂等键，按同样的规则重试。

### 取消与总时限

`totalTimeoutMs` 限制一次逻辑调用的总耗时，包括全部重试与等待，超时抛 `RequestTimeoutException`。取消令牌在任何线程都可以调用：

```kotlin
val token = CancellationToken()
val options = RequestOptions(cancellationToken = token)
// 另一个线程
token.cancel() // 调用方收到 RequestCancelledException，错误码 90003
```

## 实时评测与重连

### 会话状态

`StreamSession.getState()` 返回 `SessionState`：

| 状态 | 含义 |
|---|---|
| `IDLE` | 刚创建 |
| `CONNECTING` | 正在建立连接 |
| `CONNECTED` | 收到服务端 `connected`，已发出开始帧 |
| `STARTED` | 收到服务端 `started`，音频实时发送 |
| `ENDING` | 已发出结束帧，等待终评 |
| `RECONNECTING` | 连接中断，等待重连 |
| `COMPLETED` | 收到终评结果 |
| `FAILED` | 出现不可恢复的错误或重连用尽 |
| `CANCELLED` | 调用方调用了 `cancel()` |
| `CLOSED` | 连接已关闭，终态 |

正常流程的回调顺序：

```
onStateChanged(IDLE, CONNECTING)
onStateChanged(CONNECTING, CONNECTED)  onConnected()
onStateChanged(CONNECTED, STARTED)     onStarted()
onStateChanged(STARTED, ENDING)
onStateChanged(ENDING, COMPLETED)      onResult(result)
onStateChanged(COMPLETED, CLOSED)      onClosed(1000, "completed")
```

回调保证：

- 每个会话最终回调一次 `onResult` 或一次 `onError`，先调用 `cancel()` 时两者都不回调。
- `onClosed` 总是最后一个回调。
- 同一会话的回调不会并发执行，默认在主线程执行。
- 回调里 `getState()` 的返回值等于最近一次 `onStateChanged` 通知的新状态，界面可以随时查询状态。
- `onConnected` 与 `onStarted` 只在首次连接时回调，之后的重连只回调 `onReconnecting` 与 `onReconnected`，在 `onStarted` 里开始录音的代码不会被重复执行。

### 示例

```kotlin
val session = client.streamEvaluate(
    EvaluateConfig(CoreType.SENTENCE, "今天天气很好", Language.ZH_CN),
    object : StreamListener {
        override fun onStateChanged(oldState: SessionState, newState: SessionState) {
            stateView.text = newState.name
        }

        override fun onReconnecting(attempt: Int, delayMs: Long, cause: YuguException) {
            stateView.text = "网络中断，第 $attempt 次重连"
        }

        override fun onResult(result: EvalResult) {
            scoreView.text = "总分 ${result.overall}"
        }

        override fun onError(error: YuguException) {
            scoreView.text = "评测失败 ${error.code}"
        }
    },
)
recorder.start(session) // 每 20 毫秒送一帧 640 字节的 PCM
// 读完以后
recorder.stop()
session.end()
```

声通兼容实时评测用 `streamEvaluateCompat(CompatConfig(...), listener)`，`realtimeFeedback = true` 时服务端在收音过程中下发进度中间帧，回调 `onPartial`，`PartialResult.bytes` 为已收到的字节数。原生实时评测没有中间帧。

### 重连策略

| 字段 | 默认值 |
|---|---|
| `enabled` | true |
| `maxAttempts` | 8，连续重连失败次数的上限，重连成功后从零算起 |
| `initialDelayMs` | 500 |
| `multiplier` | 2.0 |
| `maxDelayMs` | 4000 |
| `jitter` | 0.3 |

默认依次等待约 0.5，1，2，4，4，4，4，4 秒，合计约 23 秒，可以扛过 10 秒左右的断网。连续失败满 `maxAttempts` 次时会话以 90006 结束。为保证会话一定结束，一次会话的重连总次数另有上限，为 `maxAttempts` 的 3 倍，默认 24 次，服务端反复接受又断开连接时会话以 90006 结束，不会无限重连。

触发重连的情况：传输层故障，连接以 1001，1011 等非协议类关闭码断开，心跳超时，握手超时，等待终评超时，服务端错误帧的错误码可重试，例如 40901，42901，50200。直接进入 FAILED 不重连的情况：不可重试的错误帧，鉴权失败的握手，服务端在终评前以 1000 关闭，关闭码为 1002，1003，1007，1008，1009，1010 或 4000 到 4999，后两类的错误码为 90005。

平台不支持会话续传，重连语义如下：重连后是一个新的服务端会话，SDK 用同一组参数与同一个幂等键重新握手，重发开始帧，再按缓冲策略补发音频。同一个幂等键保证重连不会重复计费。

### 断线期间的音频

| 策略 | 行为 |
|---|---|
| `REPLAY` | 默认。保留本次会话已送入的全部音频，上限 10 MB。重连后先重放缓冲，再补发断线期间送入的音频，已调用 `end()` 时再发结束帧。缓冲超过上限后，下一次需要重连时会话以 90008 失败 |
| `DROP` | 不缓冲。断线期间送入的音频丢弃，新会话只评测重连后送入的音频，`onReconnected` 的 `droppedBytes` 给出丢弃的字节数。已发出结束帧后断线，会话直接失败 |
| `FAIL` | 不重连，传输层故障直接进入 FAILED |

### 心跳与超时

- 心跳：每 15 秒发一次 WebSocket ping，到下一次发 ping 时仍未收到上一次的 pong 即判定连接失效，最长 30 秒，随后按重连策略处理。
- 握手：建连，握手到收到 `started` 须在 `connectTimeoutMs` 内完成。
- 终评：调用 `end()` 后 `resultTimeoutMs` 内未收到结果，按 90007 处理，可重连时用同一个幂等键重放音频取回结果。

### 区分中断与结束

| 现象 | 回调 |
|---|---|
| 重连中 | `onReconnecting(attempt, delayMs, cause)`，状态为 `RECONNECTING` |
| 重连成功 | `onReconnected(attempt, droppedBytes)` |
| 重连失败 | `onError`，错误码 90006，`cause` 为最后一次的原因 |
| 评测正常结束 | `onResult`，状态为 `COMPLETED` |
| 连接关闭 | `onClosed(code, reason)`，正常结束与取消为 1000，传输层故障为 1006，服务端主动关闭时为其关闭码 |

## 生命周期

客户端，会话与录音器都有显式的释放方法，全部可以重复调用：

| 对象 | 释放 | 释放后 |
|---|---|---|
| `YuguClient` | `close()` | 取消进行中的会话与请求，再调用任何方法抛 `IllegalSessionStateException`，错误码 90004 |
| `StreamSession` | `cancel()` 或 `close()` | 进入 CANCELLED 与 CLOSED，不再回调 `onResult` 与 `onError` |
| `Recorder` | `release()` 或 `close()` | 停止录音，释放麦克风，注销监听器 |

创建，使用，释放的完整示例：

```kotlin
class EvalActivity : Activity() {
    private lateinit var client: YuguClient
    private lateinit var recorder: Recorder
    private var session: StreamSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 创建
        client = YuguClient.builder().auth(Auth.token(tokenFromYourServer)).build()
        recorder = Recorder(this)
        recorder.setListener(object : Recorder.Listener {
            override fun onLevel(level: Int) {
                runOnUiThread { volumeBar.progress = level }
            }

            override fun onError(error: YuguException) {
                session?.cancel()
            }
        })
    }

    // 使用：每道题一个会话
    fun startQuestion(refText: String) {
        val s = client.streamEvaluate(EvaluateConfig(CoreType.SENTENCE, refText, Language.ZH_CN), listener)
        session = s
        recorder.start(s)
    }

    fun finishQuestion() {
        recorder.stop()
        session?.end()
    }

    override fun onDestroy() {
        // 释放
        recorder.release()
        session?.cancel()
        client.close()
        super.onDestroy()
    }
}
```

录音器的状态为 `IDLE`，`RECORDING`，`PAUSED`，`STOPPED`，`RELEASED`，可用 `getState()` 查询。`stop()`，`release()` 与每一条出错路径都会释放麦克风：初始化失败抛 90202，没有权限抛 90201，录音中读取失败时状态回到 `STOPPED`，回调 `onError`，错误码 90203。`pause()` 停止采集，`resume()` 继续。录到的音频用 `pcm()`，`toWav()`，`writeWav(file)` 读取，默认最长 300 秒，到时自动停止。`setListener(null)` 或 `removeListener()` 注销监听器。

异常退出时的清理：客户端与会话占用的线程，连接与定时器都随 `close()` 与 `cancel()` 释放，SDK 的后台线程空闲时自动退出。进程被系统回收时麦克风与连接由系统回收，下次启动重新创建客户端即可。连续创建与释放客户端 100 次，线程与内存不会累积增长，单元测试覆盖了这一场景。

## 音频预检

整段评测上传前与实时评测调用 `end()` 时，SDK 在本地检查 WAV 或 PCM 音频，其他格式只检查大小：

| 错误码 | 条件 | `WARN` | `REJECT` |
|---|---|---|---|
| 90101 | 时长短于 1 秒 | 警告 | 上传前抛异常 |
| 90102 | 时长超过 300 秒，整段上传大于 50 MB，或实时评测一轮大于 10 MB | 警告 | 抛异常 |
| 90103 | 全程静音：峰值低于 200，同时均方根低于 30 | 警告 | 抛异常 |
| 90104 | 音量过低：均方根电平低于 -45 dBFS | 警告 | 只警告 |
| 90105 | 不是 16 位 PCM WAV，或采样率低于 16000 | 警告 | 抛异常 |

默认为 `WARN`，警告写进 `result.localWarnings`，实时评测另外回调 `onWarning`。`REJECT` 模式在上传前抛 `AudioQualityException`，不产生计费。静音阈值与平台的静音判定一致，本地判为静音的音频，平台也会给零分。`OFF` 关闭预检。

## 外部音频

评测接口接受调用方自有的音频，录音器可以不用：

| 来源 | 写法 |
|---|---|
| 字节数组 | `AudioInput.fromBytes(bytes)` 或 `client.evaluate(config, bytes)` |
| 文件 | `AudioInput.fromFile(file)` 或 `client.evaluate(config, file)` |
| 输入流 | `AudioInput.fromStream(stream)`，读到结尾，流由调用方关闭 |
| 裸 PCM | `AudioInput.fromPcm(pcm, 16000, 1)`，上传前自动加 WAV 头 |
| 实时音频 | `session.sendAudio(pcm)`，16 kHz，16 位，单声道，推荐每帧 640 字节，超过 32000 字节的块自动拆成多帧发送 |

文件名决定上传时的 `Content-Type`，例如 `.wav` 为 `audio/wav`，`.mp3` 为 `audio/mpeg`，也可以在构造时传入。平台支持 WAV 与 MP3，采样率不低于 16 kHz。直播流与第三方采集的 PCM 可以直接用 `sendAudio` 送进实时会话。

## 签名规则

使用 appKey 与 secretKey 鉴权时，SDK 自动计算签名：业务参数丢弃空值，按参数名字典序拼成 `k1=v1&k2=v2`，不做 URL 编码，签名值为 `Base64(HMAC_SHA256(payload, secretKey))`。各接口的业务参数：

| 接口 | 参与签名的参数 |
|---|---|
| 原生整段评测 | `{config: config 段原文}`，`config` 段不带文件名，`Content-Type: application/json; charset=utf-8` |
| 声通兼容整段评测 | 实际发送的文本表单字段，音频不参与 |
| 语音合成 | 请求体顶层非空标量字段，值与请求体文本一致，数字写成不带指数的小数 |
| 报告查询 | 空集 |
| 实时评测握手 | 除 `signature` 以外的全部 query 参数，含 `appKey`，`timestamp`，`nonce`，`idempotencyKey` |

请求头 `X-Timestamp` 与 `X-Nonce` 每次尝试重新生成，签名对应的业务参数不变。`Signer` 公开可用，共享签名向量 `spec/fixtures/sign/vectors.json` 由单元测试逐条验证。

## 日志与指标

日志级别为 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，默认 `WARN`，默认输出到 Logcat，标签为 `YuguSDK`。自定义输出：

```kotlin
val client = YuguClient.builder()
    .auth(auth)
    .logLevel(LogLevel.DEBUG)
    .logger { level, tag, message, error -> println("$level $tag $message ${error ?: ""}") }
    .build()
```

日志不包含 secretKey，签名值，token 与音频字节，appKey 只输出前 4 位加 `***`，地址里的 `signature` 与 `token` 参数显示为 `***`。

指标回调 `YuguEventListener` 的方法全部可选：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op, method, path, attempt)` | REST 每次尝试发出前 |
| `onRequestEnd(op, httpStatus, latencyMs, attempts, error)` | REST 每个逻辑调用结束时，`latencyMs` 含重试，`attempts` 为总尝试次数 |
| `onRetry(op, attempt, delayMs, error)` | 每次重试前 |
| `onSessionStateChanged(sessionId, oldState, newState)` | 实时会话状态变化 |
| `onReconnect(sessionId, attempt, succeeded)` | 实时会话每次重连的结果 |

`op` 取值：`evaluate`，`evaluateCompat`，`tts`，`getReport`。

## 测试与覆盖率

```bash
cd android
./gradlew test
./gradlew :yugu-android-sdk:jacocoTestReport
```

测试包括签名向量，参数序列化与校验，multipart 组装，各模式结果解析，全部错误码与警告码的映射，幂等键生成与复用，重试判定与退避计算，实时会话状态机，三种缓冲策略，生命周期，录音器与音频预检。集成测试自动启动平台模拟服务 `tools/mock-server`，需要 Node.js 20，覆盖幂等计费，重试，服务端断开，异常关闭，服务端无响应，客户端断网 10 秒与网络切换。覆盖率报告在 `yugu-android-sdk/build/reports/jacoco/jacocoTestReport/html/index.html`，行覆盖率门槛为 70%。持续集成脚本为 `ci/android.sh`。

## 源码构建与本地发布

需要 JDK 17 与 Android SDK Platform 34，`ANDROID_HOME` 指向 SDK 目录，或在 `local.properties` 里写 `sdk.dir`。

```bash
cd android
./gradlew :yugu-android-sdk:assembleRelease
./gradlew :yugu-android-sdk:publishToYuguDir -PyuguPublishDir=/absolute/path/to/maven-repo
```

发布目录按 Maven 仓库布局写入 AAR，POM，Gradle 模块元数据，源码包，接口文档包与各自的校验和，可直接合并到 Maven 仓库，也可用 `maven { url "file:///absolute/path/to/maven-repo" }` 引用。

## 常见问题

### 主线程调用阻塞方法

`evaluate`，`evaluateCompat`，`tts`，`getReport` 会发起网络请求，在主线程调用会被系统拒绝。在后台线程调用，或改用对应的 `Async` 方法，Kotlin 协程中可写 `withContext(Dispatchers.IO) { client.evaluate(config, file) }`。

### secretKey 放在客户端的风险

secretKey 写进安装包后可能被反编译取出。正式应用建议由自己的服务端签发 token，客户端使用 `Auth.token(jwt)`，appKey 与 secretKey 只在集成与测试阶段放在客户端。

### 回调所在的线程

实时评测回调与异步方法回调默认在主线程，可以直接更新界面。录音器的 `onFrame` 与 `onLevel` 在采集线程上回调，更新界面需要切回主线程。需要其他线程时设置 `callbackExecutor`。

### 网络抖动导致的重复计费

SDK 的重试与重连都复用同一个幂等键，平台对同一个键只计费一次。业务层自己重新提交同一段音频时，传入同一个 `idempotencyKey` 也能避免重复计费。

### 录音器启动失败

错误码 90201 表示没有录音权限，90202 表示麦克风被其他应用占用或设备不支持 16 kHz 单声道录音，都不会占住麦克风。

### 代理与自定义证书

通过 `okHttpClient(client)` 传入配置好代理或证书的 OkHttpClient，SDK 在其基础上派生超时与心跳设置，不会关闭传入的实例。证书校验失败抛 `NetworkException`，错误码 90011，不会重试。

### Java 调用

客户端与配置类都提供构建器，例如 `EvaluateConfig.builder(CoreType.SENTENCE, "今天天气很好").language(Language.ZH_CN).build()`。监听器接口的可选方法是 Java 默认方法，只需实现 `onResult` 与 `onError`。异常类型都继承 `RuntimeException`，按需捕获 `YuguException`。

### 沙箱与测试密钥

沙箱环境，测试密钥的申请方式与每日调用额度见 [SANDBOX.md](../SANDBOX.md)。

### 从 `1.x` 升级

包名由 `tech.dragonai.yugu` 改为 `com.shengzhiai.yugu`，默认基址改为 `https://open.shengzhiai.com`，详见 [`CHANGELOG.md`](CHANGELOG.md) 与 [`MIGRATION-2.0.md`](../MIGRATION-2.0.md)。

## 版本兼容

各端版本的完整对照见 [`COMPATIBILITY.md`](../COMPATIBILITY.md)。

| SDK 版本 | 接口版本 | Android | 编译环境 |
|---|---|---|---|
| `2.0.0` | API v1，契约 v2 | 5.0 及以上，`minSdk 21` | Java 8 字节码，Kotlin 1.9，OkHttp 4.12 |
| `1.0.0` | API v1 | 7.0 及以上，`minSdk 24` | 源码包，已停止维护 |

## 许可

按 Apache License 2.0 授权，全文见 [LICENSE](LICENSE)，第三方名称说明见 [NOTICE](NOTICE)。
