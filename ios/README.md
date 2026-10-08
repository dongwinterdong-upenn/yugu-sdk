# YuguSDK iOS 端

优谷雅言语音评测服务的 iOS 与 macOS SDK，提供整段评测，实时评测，语音合成，报告查询，自动重试，幂等键，断线重连，错误分类，音频预检与录音。

| 项 | 内容 |
|---|---|
| 版本 | `2.0.0`，2026-10-08 |
| 仓库 | `https://open.shengzhiai.com/git/yugu-ios-sdk.git`，匿名只读 |
| 产品 | `YuguCore`，`YuguSDK` |
| 平台 | iOS 13 起，macOS 11 起，`YuguCore` 另支持 Linux |
| 工具链 | Swift 5.9 起，Xcode 15 起 |
| 第三方依赖 | 无 |
| 许可 | Apache-2.0 |

## 产品

| 产品 | 内容 | 平台 |
|---|---|---|
| `YuguCore` | 整段评测，实时评测，语音合成，报告查询，重试，幂等，签名，错误分类，音频预检，只依赖 Foundation | iOS，macOS，Linux |
| `YuguSDK` | `YuguCore` 的全部能力加录音器 `YuguRecorder`，写 `import YuguSDK` 即可使用全部类型 | iOS，macOS |

App 一般选 `YuguSDK`。服务端或命令行工具只做评测时选 `YuguCore`。

## 安装

### Xcode 添加依赖

1. 在 Xcode 菜单 File 中选择 Add Package Dependencies。
2. 在搜索框输入 `https://open.shengzhiai.com/git/yugu-ios-sdk.git`。
3. Dependency Rule 选择 Up to Next Major Version，版本填 `2.0.0`。
4. 产品勾选 `YuguSDK`，点击 Add Package。

### Package.swift 声明

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MyApp",
    platforms: [.iOS(.v13)],
    dependencies: [
        .package(url: "https://open.shengzhiai.com/git/yugu-ios-sdk.git", from: "2.0.0"),
    ],
    targets: [
        .target(name: "MyApp", dependencies: [
            .product(name: "YuguSDK", package: "yugu-ios-sdk"),
        ]),
    ]
)
```

包标识取仓库地址的最后一段，所以 `package:` 参数写 `yugu-ios-sdk`。

### 版本固定与回退

每个版本对应仓库里的一个 Git 标签，已发布的标签不会改动。固定在某个版本写 `.package(url: "https://open.shengzhiai.com/git/yugu-ios-sdk.git", exact: "2.0.0")`，回退时把版本号改成更早的标签，然后在 Xcode 中执行 File 菜单的 Packages 下的 Reset Package Caches。

### CocoaPods 方式

```ruby
pod 'YuguSDK', :git => 'https://open.shengzhiai.com/git/yugu-ios-sdk.git', :tag => '2.0.0'
```

CocoaPods 把两个产品编进同一个模块 `YuguSDK`，代码里写 `import YuguSDK`。

### 仓库访问

仓库地址为 HTTPS，匿名只读，不需要账号，令牌与证书配置。沙箱环境与测试密钥的申请方式见 SDK 总仓库的 `SANDBOX.md`。

## 麦克风权限

使用录音器的 App 必须在 Info.plist 中声明麦克风用途，缺少这一项时 iOS 在首次录音时终止 App。

```xml
<key>NSMicrophoneUsageDescription</key>
<string>录制朗读音频，用于语音评测</string>
```

macOS App 开启了沙盒时，另需在 entitlements 中打开 `com.apple.security.device.audio-input`。只评测已有音频时不需要麦克风权限。

## 五分钟上手

```swift
import YuguSDK

// 创建客户端，App 内复用同一个实例
let client = YuguClient(options: YuguClientOptions(
    auth: .appKey("<appKey>", secretKey: "<secretKey>")))

// 评测一段 16 kHz 单声道 WAV
let config = EvaluateConfig(coreType: .sentence, referenceText: "今天天气很好", language: "zh-CN")
let result = try await client.evaluate(audio: .file(wavURL), config: config)

// 读取分数
print("总分", result.overall ?? 0)
print("流利度", result.dimensions.fluency ?? 0, "完整度", result.dimensions.integrity ?? 0)
for word in result.words {
    print(word.word ?? "", word.overall ?? 0)
}

// 不再使用时释放
client.close()
```

不使用 `async` 时用完成回调写法，回调在 `callbackQueue` 上执行，缺省为主队列：

```swift
client.evaluate(audio: .file(wavURL), config: config) { outcome in
    switch outcome {
    case .success(let result): print(result.overall ?? 0)
    case .failure(let error): print(error.category, error.code, error.message)
    }
}
```

用麦克风录音再评测：

```swift
let recorder = YuguRecorder()
YuguRecorder.requestPermission { granted in
    guard granted else { return }
    try? recorder.start()
}
// 用户读完后
if let recording = recorder.stop() {
    client.evaluate(audio: recording.audioInput, config: config) { outcome in
        // 处理结果
    }
}
recorder.release()
```

## 客户端选项

`YuguClientOptions` 的缺省值与其他各端 SDK 相同。

| 选项 | 缺省值 | 含义 |
|---|---|---|
| `baseUrl` | `https://open.shengzhiai.com` | REST 基址 |
| `wsBaseUrl` | 由 `baseUrl` 推出，缺省为 `wss://open.shengzhiai.com` | WebSocket 基址 |
| `auth` | 必填 | `.appKey(appKey, secretKey:)` 或 `.token(jwt)` |
| `connectTimeoutMs` | 10000 | REST 连接超时，也是 WebSocket 握手超时 |
| `readTimeoutMs` | 120000 | 单次 REST 尝试的超时 |
| `totalTimeoutMs` | 300000 | 一次逻辑调用的总时限，含重试与等待 |
| `retry` | `RetryPolicy.default` | 重试策略 |
| `autoIdempotencyKey` | `true` | 写操作自动生成幂等键 |
| `logLevel` | `.warn` | 日志级别 |
| `logger` | `ConsoleLogger` | 日志输出 |
| `eventListener` | 无 | 指标回调 |
| `audioPrecheck` | `.warn` | 音频预检方式 |
| `strictAudio` | `false` | 平台报 1001 时改为返回音频错误 |
| `userAgent` | `yugu-ios-sdk/2.0.0` | 请求头 `User-Agent` |
| `reconnect` | `ReconnectPolicy.default` | 实时评测重连策略 |
| `audioBufferPolicy` | `.replay` | 重连时的音频缓冲策略 |
| `pingIntervalMs` | 15000 | 协议层心跳间隔 |
| `pongTimeoutMs` | 30000 | 心跳应答超时 |
| `resultTimeoutMs` | 300000 | 结束后等待终评的时限 |
| `callbackQueue` | 主队列 | 完成回调与会话回调所在队列 |
| `urlSessionConfiguration` | 无 | 自定义 URLSession 配置，例如代理 |
| `httpTransport` | 无 | 替换 HTTP 层，例如证书锁定 |
| `webSocketTransportFactory` | 无 | 替换 WebSocket 层 |

单次调用用 `RequestOptions` 覆盖：`idempotencyKey`，`timeoutMs`，`readTimeoutMs`，`retry`，`cancellationToken`，`audioPrecheck`。实时会话用 `StreamOptions` 覆盖：`idempotencyKey`，`reconnect`，`audioBufferPolicy`，`connectTimeoutMs`，`resultTimeoutMs`，`pingIntervalMs`，`pongTimeoutMs`，`audioPrecheck`。

## 接口一览

| 方法 | 作用 |
|---|---|
| `evaluate(audio:config:image:options:)` | 原生整段评测 `POST /api/v1/evaluate` |
| `evaluateCompat(coreType:audio:params:options:)` | 声通兼容整段评测 `POST /{coreType}` |
| `tts(_:options:)` | 语音合成 `POST /api/v1/tts/generate` |
| `getReport(recordId:options:)` | 报告查询 `GET /api/v1/report/{recordId}` |
| `streamEvaluate(config:options:listener:)` | 原生实时评测会话 |
| `streamEvaluateCompat(coreType:params:options:listener:)` | 声通兼容实时评测会话 |
| `close()` | 释放客户端，可重复调用 |

REST 方法都有两种写法，`async throws` 写法跟随任务取消，完成回调写法返回 `YuguCall`，调用 `cancel()` 取消。客户端的全部方法线程安全。

评测参数在 `EvaluateConfig` 中设置，常用字段为 `coreType`，`referenceText`，`language`，`slack`，`scale`，`precision`，`agegroup`，`includeReport`，`includeAsrText`，`refPinyin`，`taskType`，`paragraphNeedWordScore`，平台新增的字段放进 `extra`。`config` 分片的 JSON 文本由 SDK 按固定顺序生成，整数值的小数写成整数，发出的文本与签名的文本一字不差。

## 结果字段

整段评测与实时评测都返回 `EvalResult`。

| 属性 | 来源 | 内容 |
|---|---|---|
| `overall` | `result.overall`，连读题取 `result.connected_overall` | 总分 |
| `dimensions` | `result` 的各维度 | 完整度，准确度，发音，流利度，声调，韵律，朗读技巧，情感，语速 |
| `words` | `result.words` | 逐字逐词得分，拼音，读音状态，音素，时间 |
| `sentences` | `result.sentences` | 分句得分，段落题带 `details` 逐词详情 |
| `yuguScores` | `result.yuguScores` | 五维结构化分数 |
| `connected` | 连读题 | 连读，失爆，弱读，节奏，各连接处的实现程度 |
| `openTask` | 开放题 | 内容，语言运用，表达，识别文本，点评，审计标记 |
| `report` | `report` | 报告摘要，建议，维度分 |
| `asrText` | `asrText` | 识别文本与逐字对齐 |
| `warnings` | `warnings` 与 `result.warning` | 平台的音频质量警告 |
| `localWarnings` | 本地预检 | 本地预检警告 |
| `idempotencyKey`，`replayed`，`attempts` | 调用信息 | 幂等键，是否为平台重放的结果，尝试次数 |
| `raw`，`resultJSON` | 原始 JSON | 未建模的字段从这里读取 |

各评测模式的取分字段逐项见 SDK 总仓库的 `RESULTS.md`。`raw` 是 `JSONValue`，可用下标读取，也可用 `decode(_:)` 解码为自定义类型。

## 错误处理

所有失败都以 `YuguError` 返回。

| 属性 | 含义 |
|---|---|
| `category` | 错误类别，共 16 类 |
| `code` | 平台错误码，警告码或 SDK 本地错误码，未知时为 0 |
| `name` | 错误码在码表中的名称 |
| `httpStatus` | HTTP 状态，没有时为 0 |
| `message` | 说明 |
| `retryable` | 用同一个幂等键再次调用能否成功 |
| `idempotencyKey` | 本次调用的幂等键 |
| `recordId` | 平台已生成的记录号 |
| `attempts` | 已尝试次数 |
| `rawBody` | 响应原文，最多 4 KB |
| `cause` | 底层错误，例如 `URLError` |

类别与便捷属性一一对应，按类别分支即可：

| 类别 | 便捷属性 | 典型处置 |
|---|---|---|
| `network`，`timeout` | `isNetworkError`，`isTimeout` | 提示网络状况，可重试 |
| `auth`，`permission` | `isAuthError`，`isPermissionDenied` | 检查密钥与授权 |
| `invalidParam`，`notFound` | `isInvalidParameter`，`isNotFound` | 修正参数 |
| `conflict` | `isConflict` | 幂等键冲突，见重试与幂等一节 |
| `rateLimit`，`quota` | `isRateLimited`，`isQuotaExceeded` | 稍后重试或充值 |
| `server`，`upstream` | `isServerError` | 可重试，持续失败时联系平台 |
| `audio` | `isAudioQuality` | 提示用户重录 |
| `state` | `isIllegalState` | 检查调用顺序 |
| `cancelled` | `isCancelled` | 调用方已取消 |
| `protocol` | `isProtocolViolation` | 响应无法解析，联系平台 |

```swift
do {
    let result = try await client.evaluate(audio: .file(wavURL), config: config)
    show(result)
} catch let error as YuguError {
    switch error.category {
    case .audio: askToRecordAgain()
    case .auth, .permission: checkCredentials()
    default:
        if error.retryable { retryLater(idempotencyKey: error.idempotencyKey) }
    }
}
```

本地错误码：

| 错误码 | 含义 |
|---|---|
| 90001 | 网络错误，可重试 |
| 90002 | 连接或读取超时，可重试 |
| 90003 | 调用方取消 |
| 90004 | 客户端已关闭 |
| 90005 | 响应或帧无法解析 |
| 90006 | 实时评测重连次数用尽 |
| 90007 | 结束后等待终评超时，可重试 |
| 90008 | 重连重放缓冲超过 10 MB |
| 90009 | 当前会话状态不允许该操作 |
| 90010 | 调用参数不合法，在任何网络请求之前报出 |
| 90011 | 证书校验失败 |
| 90101 到 90105 | 音频预检，见音频预检一节 |
| 90201 到 90203 | 录音器，依次为没有权限，麦克风不可用，录音出错 |

平台错误码的完整码表内置在 `ErrorTable` 中，说明见 SDK 总仓库的 `ERRORS.md`。1004 与 1005 在错误响应里是账户状态错误，在评测结果的 `warning` 里是音频警告，`YuguErrors.fromCode` 按错误响应查表，`YuguErrors.fromWarning` 按警告查表。`YuguErrors.isRetryable(_:)` 与 SDK 自身的重试判断使用同一套规则。

## 重试与幂等

### 幂等键

`evaluate`，`evaluateCompat`，`tts` 与两种实时会话都带幂等键。调用方没有指定时，SDK 为每次逻辑调用生成一个 32 位小写十六进制的键，重试与重连沿用同一个键。指定的键须为 1 到 200 个可打印 ASCII 字符，不含空格，不合法时在发出请求之前报 90010。

平台对幂等键的处理：

| 情形 | 平台行为 |
|---|---|
| 同一个键，同一个请求，首次已成功 | 直接返回首次结果，响应头带 `Idempotency-Replayed: true`，`result.replayed` 为 `true`，不再计费 |
| 首次请求仍在处理 | 等待最多 30 秒后返回首次结果，仍未完成时返回 409 与错误码 40901，可重试 |
| 同一个键，不同的请求 | 返回 409 与错误码 40903，不可重试 |
| 首次请求失败 | 释放这个键，再次提交按新请求处理 |

键的作用域为 appKey，使用 token 时为用户，有效期 24 小时。业务上需要“同一题只计一次费”时，自行指定键，例如用题目号与用户号组成的字符串，失败后用 `error.idempotencyKey` 再次提交。

```swift
let options = RequestOptions(idempotencyKey: "exam-42-user-7-q3")
let result = try await client.evaluate(audio: .file(wavURL), config: config, options: options)
```

### 重试策略

缺省最多重试 2 次，共 3 次尝试。第 n 次重试前等待 `min(maxDelayMs, initialDelayMs 乘 multiplier 的 n-1 次方)`，再乘以 0.7 到 1.3 之间的随机系数，缺省约为 200 毫秒与 400 毫秒。响应带 `Retry-After` 时等待时间不少于该值，该值按 30 秒封顶。等待后会超过 `totalTimeoutMs` 时不再重试，直接返回最后一次的错误。

重试的错误：网络错误，超时，HTTP 408，425，429，500，502，503，504，以及码表标为可重试的错误码，例如 40901，42900，42901，50000，50200。参数错误，鉴权失败，额度不足等不重试。没有幂等键的写操作不重试，`autoIdempotencyKey` 为 `false`，调用时也未指定键，就属于这种情况。报告查询是 GET 请求，不带键也会重试。

每次重试触发 `eventListener` 的 `onRetry`，同时写一条 WARN 日志，形如 `retry 1/2 in 231 ms: HTTP 503 code=50200`。

```swift
var options = YuguClientOptions(auth: .appKey("<appKey>", secretKey: "<secretKey>"))
options.retry = RetryPolicy(maxRetries: 3, initialDelayMs: 300)
options.totalTimeoutMs = 60_000
```

## 实时评测

### 会话流程

```swift
let handlers = YuguStreamHandlers()
handlers.onStateChanged = { from, to in print(from, "->", to) }
handlers.onReconnecting = { attempt, delayMs, cause in showBanner("网络中断，正在重连") }
handlers.onReconnected = { attempt, droppedBytes in hideBanner() }
handlers.onResult = { result in show(result) }
handlers.onError = { error in showError(error) }
handlers.onClosed = { code, reason in releaseUI() }

let session = try client.streamEvaluate(config: config, listener: handlers)
try session.start()
// 每帧 640 字节，16 kHz 单声道 16 位小端 PCM
try session.sendAudio(frame)
// 读完
try session.end()
```

`start()` 之后即可送音频，服务端就绪之前送来的音频由 SDK 暂存后按顺序发出。单次送入超过 32000 字节时，SDK 拆成不超过 32000 字节的帧发送，平台会以 1009 关闭超过 128 KB 的帧。`session.state` 随时可查。`cancel()` 与 `close()` 可重复调用。

### 状态

```
IDLE -> CONNECTING -> CONNECTED -> STARTED -> ENDING -> COMPLETED -> CLOSED
CONNECTING | CONNECTED | STARTED | ENDING -> RECONNECTING -> CONNECTING
任一非终止状态 -> FAILED -> CLOSED
任一非终止状态 -> CANCELLED -> CLOSED
```

| 回调 | 时机 |
|---|---|
| `onStateChanged` | 每次状态变化 |
| `onConnected` | 首次连接建立 |
| `onStarted` | 服务端首次接受参数，此后送入的音频直接发出 |
| `onPartial` | 声通兼容会话打开 `realtimeFeedback` 后的进度帧 |
| `onReconnecting` | 连接中断，第几次重连，等待多久，原因 |
| `onReconnected` | 重连成功，DROP 策略下附带丢弃的字节数 |
| `onWarning` | `end()` 时的本地预检警告 |
| `onResult` | 终评结果 |
| `onError` | 失败 |
| `onClosed` | 会话结束，总是最后一个回调 |

回调的约定：每个会话只会收到 `onResult` 与 `onError` 中的一个，调用过 `cancel()` 的会话两者都不会收到，`onClosed` 总是最后一个回调，同一会话的回调不会同时执行，`session.state` 与最近一次 `onStateChanged` 报告的状态一致。

### 断线重连

触发重连的情况：连接失败，连接被异常关闭，心跳超时，握手超时，终评超时，以及带可重试错误码的错误帧，例如 40901，42901，50200。不可重试的错误帧，鉴权失败的握手，证书错误直接进入 FAILED。服务端以 1000 正常关闭却没有给出结果时，会话以 90005 失败。服务端以 1002，1003，1007，1008，1009，1010 或 4000 到 4999 之间的代码关闭连接时，新连接也会被同样拒绝，会话直接以 90005 失败，不重连。

`ReconnectPolicy` 缺省最多连续重连 8 次，间隔约为 0.5 秒，1 秒，2 秒，4 秒，之后每次 4 秒，合计约 23 秒，各有正负 30% 的随机抖动，足以撑过 10 秒的断网。重连成功后次数从零重新计算，连续失败次数用尽时会话以 90006 失败，`cause` 为最后一次的原因。整个会话的重连总数不超过 `maxAttempts` 的 3 倍，缺省为 24 次，服务端反复接受连接又断开时，到达上限后同样以 90006 失败。

平台没有服务端续传，重连等于用同样的参数与同一个幂等键开一个新的服务端会话。音频缓冲策略 `AudioBufferPolicy` 决定音频怎么处理：

| 策略 | 行为 |
|---|---|
| `.replay`，缺省 | 保留本次会话已送的全部音频，上限 10 MB。重连后重发开始帧，按原顺序重放全部音频，接着发出重连期间送入的音频，`end()` 已调用时再发结束帧。同一个幂等键使平台只评测一次，只计费一次。音频超过 10 MB 后，下一次需要重连时会话以 90008 失败 |
| `.drop` | 不保留音频，重连期间送入的音频丢弃，`onReconnected` 给出丢弃的字节数，新会话只评测重连之后送入的音频。`end()` 已发出后断线时，会话直接失败 |
| `.fail` | 不重连，连接中断即失败 |

关闭自动幂等键，也未指定键时，会话不重连。

### 心跳与超时

会话进入 STARTED 后每 15 秒发一次协议层 ping，30 秒收不到 pong 视为连接中断。`connectTimeoutMs` 限定从发起连接到服务端接受参数的时间，`resultTimeoutMs` 限定 `end()` 之后等待终评的时间。任何状态都有时限，会话不会无回调地停住。

## 生命周期

客户端，会话，录音器都有显式释放接口，重复调用不会报错。

```swift
final class ReadingViewController: UIViewController {
    private let client = YuguClient(options: YuguClientOptions(
        auth: .appKey("<appKey>", secretKey: "<secretKey>")))
    private let recorder = YuguRecorder()
    private let recorderHandlers = YuguRecorderHandlers()
    private var session: YuguStreamSession?

    func startReading(text: String) throws {
        let handlers = YuguStreamHandlers()
        handlers.onResult = { [weak self] result in self?.show(result) }
        handlers.onError = { [weak self] error in self?.show(error) }
        let session = try client.streamEvaluate(
            config: EvaluateConfig(coreType: .sentence, referenceText: text, language: "zh-CN"),
            listener: handlers)
        self.session = session
        recorderHandlers.onFrame = { frame in try? session.sendAudio(frame) }
        recorder.setListener(recorderHandlers)
        try session.start()
        try recorder.start()
    }

    func finishReading() {
        recorder.stop()
        try? session?.end()
    }

    deinit {
        session?.cancel()
        recorder.release()
        client.close()
    }
}
```

| 对象 | 释放接口 | 行为 |
|---|---|---|
| `YuguClient` | `close()` | 取消进行中的 REST 调用，这些调用以 90004 结束，取消全部会话，释放网络资源。关闭后的调用返回 90004 |
| `YuguStreamSession` | `cancel()`，`close()` | 关闭连接，不再回调结果，最后回调 `onClosed`，随后释放监听器 |
| `YuguRecorder` | `stop()`，`release()` | 停止采集，销毁音频引擎，释放麦克风，`release()` 之后丢弃监听器 |

录音器的状态为 IDLE，RECORDING，PAUSED，STOPPED，RELEASED，用 `recorder.state` 查询，`pause()` 与 `resume()` 用于暂停与继续。音频引擎在 `start()` 时创建，在 `stop()`，`release()` 与任何错误路径上销毁，麦克风不会在录音结束后继续占用。来电等音频中断开始时录音器转为 PAUSED，中断结束时回调 `interruptionEndedShouldResume`，由 App 决定是否调用 `resume()`。耳机插拔等路由变化时录音器重新连接输入继续录音，无法继续时以 90203 结束。

App 被系统终止时，进程内的连接与音频引擎随进程一起释放。平台只在收到结束帧后评测，没有发出结束帧的实时会话不评测，不计费。结束帧或整段评测请求已经到达平台时，平台照常评测与计费，用同一个幂等键与同样的音频再次提交即可取回结果，不再计费。

## 音频预检

整段评测上传前与实时评测 `end()` 时，SDK 在本地检查 WAV 与 PCM 音频。

| 错误码 | 条件 | WARN 模式 | REJECT 模式 |
|---|---|---|---|
| 90101 | 时长不足 1 秒 | 警告 | 上传前失败 |
| 90102 | 时长超过 300 秒，整段上传大于 50 MB，或一次实时会话的音频大于 10 MB | 警告 | 上传前失败 |
| 90103 | 全程静音，峰值低于 200，均方根低于 30 | 警告 | 上传前失败 |
| 90104 | 音量过低，均方根低于 -45 dBFS | 警告 | 警告 |
| 90105 | 不是 16 位 PCM，或采样率低于 16000 | 警告 | 上传前失败 |

缺省为 WARN，警告放在结果的 `localWarnings` 中，实时会话另回调 `onWarning`。静音阈值与平台的静音判定一致，本地拒绝的音频上传后也只会得 0 分。REJECT 模式下实时会话不发结束帧，平台不评测，不计费。MP3 等其他格式只检查大小，整段上传的上限为平台限制的 50 MB。`AudioPrecheck.check(_:)` 可单独调用。

## 外部音频

评测不依赖内置录音器，音频可以来自任何来源。

| 写法 | 用途 |
|---|---|
| `.data(bytes)` | 内存中的 WAV，MP3 等编码音频，类型由文件头识别 |
| `.file(url)` | 本地音频文件 |
| `.pcm16(pcm, sampleRate:, channels:)` | 16 位小端 PCM，SDK 加上 WAV 头后上传 |

实时会话用 `sendAudio(_:)` 送入外部采集的 16 kHz 单声道 16 位 PCM，每次 640 字节，即 20 毫秒，最为合适。`YuguWAV.wrap` 与 `YuguWAV.parse` 用于 WAV 封装与解析。

## 日志与指标

日志级别为 `.off`，`.error`，`.warn`，`.info`，`.debug`，缺省为 `.warn`。日志不含 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符。自定义输出实现 `YuguLogger`：

```swift
final class AppLogger: YuguLogger {
    func log(level: YuguLogLevel, tag: String, message: String, error: Error?) {
        os_log("%{public}@ %{public}@", tag, message)
    }
}
options.logger = AppLogger()
options.logLevel = .info
```

`YuguEventListener` 的方法都是可选的：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op:method:path:attempt:)` | 每次 REST 尝试开始 |
| `onRequestEnd(op:httpStatus:latencyMs:attempts:error:)` | 一次逻辑调用结束，耗时含全部重试 |
| `onRetry(op:attempt:delayMs:error:)` | 安排一次重试 |
| `onSessionStateChanged(sessionId:from:to:)` | 实时会话状态变化 |
| `onReconnect(sessionId:attempt:succeeded:)` | 一次重连结束 |

指标回调在 SDK 内部队列上执行，需要尽快返回。

## 测试

```bash
swift test
swift test --enable-code-coverage
```

测试覆盖签名的全部跨端向量，错误码表的每一项，各评测模式的真实返回，multipart 字节布局，退避计算，幂等键复用，实时会话状态机与三种缓冲策略，生命周期与音频预检。在 SDK 总仓库中执行时，测试还会启动 `tools/mock-server` 的平台模拟服务，验证重试，计费只计一次，以及实时会话在服务端断开，网络中断 10 秒，连接重置时的行为。没有 Node.js 或单独克隆本仓库时，这部分测试自动跳过。持续集成脚本为总仓库的 `ci/ios.sh` 与 `ci/ios-macos.sh`。

设置 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后，测试另外连接沙箱跑三条端到端用例，未设置时这三条自动跳过，不连接平台。基址缺省为 `https://open.shengzhiai.com`，可用 `YUGU_SANDBOX_BASE` 修改。一次运行在沙箱上产生 4 次评测调用，第二次整段评测是重放，不占当日次数。

| 用例 | 内容 |
|---|---|
| 原生整段评测 | 同一个幂等键提交两次，第二次为重放，记录号相同 |
| 声通兼容整段评测 | `sent.eval.cn` |
| 原生实时评测 | 完整会话，核对回调顺序与总分，Linux 上经测试用的 WebSocket 客户端与本地转发连接沙箱 |

```bash
export YUGU_SANDBOX_APPKEY='<appKey>'
export YUGU_SANDBOX_SECRET='<secretKey>'
swift test --filter SandboxTests
```

## 常见问题

### 模拟器录音

iOS 模拟器使用 Mac 的麦克风，首次录音时 macOS 会询问是否允许 Xcode 使用麦克风。模拟器没有输入设备时 `start()` 报 90202。

### Linux 上的实时评测

Linux 版 Foundation 的 WebSocket 依赖 libcurl 的 ws 支持，Ubuntu 24.04 自带的 libcurl 不包含这项支持，会话会以 90010 失败。Linux 上的整段评测，语音合成与报告查询不受影响。需要在 Linux 上做实时评测时，实现 `YuguWebSocketTransport` 后通过 `webSocketTransportFactory` 传入。

### 签名失败

鉴权错误码 2003 表示签名不匹配，常见原因是 secretKey 填错，或设备时间与标准时间相差超过 300 秒。SDK 每次尝试都重新生成时间戳与随机串，签名的就是实际发出的文本，不需要自行签名。

### 重复计费

写操作与实时会话都带幂等键，重试与重连沿用同一个键，平台对同一个键只计费一次。业务层再次提交同一次作答时，传入首次的 `idempotencyKey`。

### 明文 HTTP 地址

App Transport Security 默认拦截 `http://` 地址，此时报 90010。联调本地服务时在 Info.plist 中为该域名添加 ATS 例外，生产地址为 HTTPS，不需要配置。

### 回调线程

完成回调与会话回调在 `callbackQueue` 上执行，缺省为主队列，可直接更新界面。`async` 写法不经过 `callbackQueue`。录音器回调在 `RecorderOptions.callbackQueue` 上执行，缺省同样为主队列，在回调里调用 `sendAudio` 是线程安全的。

## 许可

Apache-2.0，见 `LICENSE` 与 `NOTICE`。
