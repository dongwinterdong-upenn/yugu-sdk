# 优谷雅言语音评测 Java SDK

服务端 Java SDK，封装优谷雅言开放平台的整段评测，声通兼容评测，语音合成，报告查询与实时流式评测。SDK 是推荐的接入方式，直连 REST 与 WebSocket 接口留给没有 SDK 的语言使用。

| 项目 | 说明 |
|---|---|
| Maven 坐标 | `com.shengzhiai.yugu:yugu-java-sdk:2.0.0` |
| 仓库地址 | `https://open.shengzhiai.com/maven/`，公开读取，无需认证 |
| Java 版本 | Java 11 及更高版本 |
| 运行时依赖 | `jackson-databind` 2.17，HTTP 与 WebSocket 使用 JDK 自带的 `java.net.http` |
| 许可 | Apache-2.0，见 `LICENSE` |
| 包名 | `com.shengzhiai.yugu`，子包 `model`，`errors`，`audio` |

## 安装

Maven 项目在 `pom.xml` 中加入仓库与依赖：

```xml
<repositories>
  <repository>
    <id>shengzhiai</id>
    <url>https://open.shengzhiai.com/maven/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.shengzhiai.yugu</groupId>
    <artifactId>yugu-java-sdk</artifactId>
    <version>2.0.0</version>
  </dependency>
</dependencies>
```

Gradle 项目在 `build.gradle` 中加入：

```groovy
repositories {
    mavenCentral()
    maven { url 'https://open.shengzhiai.com/maven/' }
}

dependencies {
    implementation 'com.shengzhiai.yugu:yugu-java-sdk:2.0.0'
}
```

Gradle Kotlin 脚本写法：

```kotlin
repositories {
    mavenCentral()
    maven("https://open.shengzhiai.com/maven/")
}

dependencies {
    implementation("com.shengzhiai.yugu:yugu-java-sdk:2.0.0")
}
```

`jackson-databind` 从 Maven Central 获取。仓库中每个版本都保留，改写版本号即可回退到历史版本。每个版本附带 `-sources.jar` 与 `-javadoc.jar`，IDE 可直接查看源码与接口文档。

## 五分钟快速开始

准备开放平台控制台签发的 appKey 与 secretKey，再准备一段 16 kHz，16 位，单声道的 WAV 录音。新建 `QuickStart.java`：

```java
import com.shengzhiai.yugu.YuguClient;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;

import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) {
        try (YuguClient client = YuguClient.builder()
                .apiKey(System.getenv("YUGU_APP_KEY"), System.getenv("YUGU_SECRET_KEY"))
                .build()) {
            EvalResult r = client.evaluate(Path.of("audio.wav"),
                    new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN"));
            System.out.println("总分 " + r.getOverall());
            System.out.println("准确度 " + r.getDims().getPronunciation());
            System.out.println("流利度 " + r.getDims().getFluency());
            System.out.println("完整度 " + r.getDims().getIntegrity());
            r.getWords().forEach(w -> System.out.println(w.getWord() + " " + w.getScores().getOverall()));
        } catch (YuguException e) {
            System.err.println(e.getCategory() + " " + e.getCode() + " " + e.getRawMessage());
        }
    }
}
```

设置环境变量后运行，控制台输出总分与逐字分数。`demos/java-cli` 是同样流程的完整工程，可直接运行。

## 接口一览

| 方法 | 平台接口 | 说明 |
|---|---|---|
| `evaluate(audio, EvaluateConfig)` | `POST /api/v1/evaluate` | 原生整段评测，`audio` 可为 `byte[]`，`File`，`Path`，`InputStream`，`AudioSource` |
| `evaluateCompat(audio, CompatConfig)` | `POST /{coreType}` | 声通兼容整段评测，`coreType` 取 `CompatConfig.CORE_TYPES` 中的值 |
| `tts(TtsRequest)` | `POST /api/v1/tts/generate` | 语音合成，返回音频地址 |
| `getReport(recordId)` | `GET /api/v1/report/{recordId}` | 查询评测记录的报告 |
| `streamEvaluate(EvaluateConfig, StreamListener)` | `wss://open.shengzhiai.com/api/v1/ws/evaluate` | 原生实时评测，返回 `StreamSession` |
| `streamEvaluateCompat(CompatConfig, StreamListener)` | `wss://open.shengzhiai.com/{coreType}` | 声通兼容实时评测，可开启进度帧 |
| `close()` | 无 | 释放客户端，取消未结束的实时会话 |

每个 REST 方法都有一个带 `RequestOptions` 的重载，每个实时方法都有一个带 `StreamOptions` 的重载。整段评测与实时评测的结果都是 `EvalResult`：

| 取值方法 | 含义 |
|---|---|
| `getOverall()` | 总分，连读题型取 `connected_overall` |
| `getDims()` | 维度分：`pronunciation`，`accuracy`，`fluency`，`integrity`，`tone`，`rhythm`，`emotion`，`readingSkill`，`speed`，`rearTone` |
| `getWords()` | 逐字或逐词分数，含音素，英文的字母发音与音节，读音状态 |
| `getSentences()` | 逐句分数，段落题开启 `paragraphNeedWordScore` 后含逐字明细 |
| `getConnected()` | 连读题型的连读，节奏，失爆，弱读与词间边界，其他题型为 `null` |
| `getOpen()` | 开放题的内容，语言运用，表达呈现，转写文本，反馈与审计，其他题型为 `null` |
| `getAsrText()` | 识别文本与逐字对齐，开启 `includeAsrText` 后返回 |
| `getReport()` | 详细报告原始 JSON，开启 `includeReport` 后返回 |
| `getWarnings()` | 平台音频质量警告，元素含 `code` 与 `message` |
| `getLocalWarnings()` | 本地音频预检的提示，码值 90101 到 90105 |
| `getIdempotencyKey()`，`isReplayed()`，`getAttempts()` | 本次调用的幂等键，是否为平台重放的首次结果，尝试次数 |
| `getRaw()` | 完整响应 JSON，新增字段可从中读取 |

## 鉴权方式

两种方式二选一，同时配置时使用签名方式。

| 方式 | 配置 | REST 请求 | WebSocket 握手 |
|---|---|---|---|
| 签名，推荐服务端使用 | `apiKey(appKey, secretKey)` | `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature` 请求头 | query 参数 `appKey`，`timestamp`，`nonce`，`signature` |
| 令牌 | `token(jwt)` | `Authorization: Bearer` 请求头 | query 参数 `token` |

声通兼容整段评测 `POST /{coreType}` 只接受签名方式，只配置令牌时平台返回 `AuthException`，错误码 40100。

签名算法与平台一致：业务参数去掉空值后按键名排序，拼成 `k1=v1&k2=v2`，计算 `Base64(HMAC_SHA256(payload, secretKey))`。原生评测签名 `config` 分片的完整 JSON 文本，语音合成签名请求体顶层的标量字段，兼容评测签名表单字段，报告查询签名空集合，WebSocket 握手签名 `signature` 之外的全部 query 参数。`Signer` 类公开同一算法，便于自行核对。secretKey 只用于本地计算，不会发送到网络，也不会写入日志。

## 错误处理

SDK 的全部错误都是 `YuguException` 的子类，属于非受检异常，可以按子类分支处理，也可以读取 `getCategory()`：

```java
try {
    EvalResult r = client.evaluate(audio, config);
} catch (InvalidParameterException e) {
    // 参数错误，修改后再提交
} catch (AuthException | PermissionException e) {
    // 凭据或权限问题，告警
} catch (QuotaExceededException e) {
    // 额度或余额不足，提示充值
} catch (AudioQualityException e) {
    // 音频质量问题，提示用户重录
} catch (YuguException e) {
    if (e.isRetryable()) {
        // SDK 已按策略重试过，仍失败，可稍后用同一个幂等键再次提交
    }
    log.warn("code={} http={} key={} attempts={}", e.getCode(), e.getHttpStatus(), e.getIdempotencyKey(), e.getAttempts());
}
```

| 类别 | 异常类 | 典型错误码 | 可重试 |
|---|---|---|---|
| `NETWORK` | `NetworkException` | 90001 连接失败，90006 重连次数用尽，90011 证书校验失败 | 90001 可重试 |
| `TIMEOUT` | `RequestTimeoutException` | 90002 连接或读取超时，90007 等待终评超时，HTTP 408 | 是 |
| `AUTH` | `AuthException` | 40100，2001 到 2011，HTTP 401 | 否 |
| `PERMISSION` | `PermissionException` | 40300，1004，1005，1012，1013，HTTP 403 | 否 |
| `INVALID_PARAM` | `InvalidParameterException` | 40001，90010，HTTP 400，413，415，422 | 否 |
| `NOT_FOUND` | `NotFoundException` | 40400，HTTP 404 | 否 |
| `CONFLICT` | `ConflictException` | 40901 同一幂等键处理中，40903 幂等键用于其他请求，1311 | 40901 与 1311 可重试 |
| `RATE_LIMIT` | `RateLimitException` | 42900，42901，3001 到 3003，HTTP 429 | 是 |
| `QUOTA` | `QuotaExceededException` | 40902，42902，42903，1310，2012 | 否 |
| `SERVER` 与 `UPSTREAM` | `ServerException` | 50000，50010，50200，HTTP 500，502，503，504 | 50010 之外可重试 |
| `AUDIO` | `AudioQualityException` | 90101 到 90105，严格模式下的 1001 | 否 |
| `STATE` | `IllegalSessionStateException` | 90004 客户端已关闭，90008 重放缓冲超限，90009 状态不允许 | 否 |
| `CANCELLED` | `RequestCancelledException` | 90003 | 否 |
| `PROTOCOL` | `ProtocolViolationException` | 90005 响应无法解析 | 否 |

异常保留这些字段：`getCode()` 平台错误码或本地码，`getHttpStatus()` HTTP 状态，`isRetryable()` 是否可重试，`getIdempotencyKey()` 幂等键，`getRecordId()` 评测记录号，`getAttempts()` 尝试次数，`getRawBody()` 原始响应体，截断到 4 KB，`getRetryAfterMs()` 服务端建议的等待时间。

`YuguErrors.isRetryable(Throwable)` 是公开的可重试判定，SDK 的重试与重连使用同一个函数。判定顺序：本地码中 90001，90002，90007 可重试，其他本地码不可重试。其次，平台错误码按错误码表的可重试标记判定。错误码缺失或未登记时，HTTP 408，425，429，500，502，503，504 可重试。错误码常量在 `ErrorTable` 中，例如 `ErrorTable.QUOTA_INSUFFICIENT`，完整码表见仓库根目录的 `ERRORS.md`。

音频质量警告 1001 到 1005 与 1009 不是异常，评分照常返回，警告列在 `getWarnings()` 中，`WarningCode` 枚举全部警告码。需要把警告当作错误处理时，调用 `YuguErrors.fromWarning(code)` 构造 `AudioQualityException`。开启 `strictAudio(true)` 后，平台返回 1001 时整段评测与实时评测都改为抛出 `AudioQualityException`，异常的 `getResult()` 保留原结果。

## 重试与幂等

写操作 `evaluate`，`evaluateCompat`，`tts` 默认自动生成幂等键，32 位小写十六进制，通过 `Idempotency-Key` 请求头发送。同一次调用的每次重试复用同一个键，重试时只更新时间戳，随机串与签名。实时会话把同一个键放进握手参数与开始帧，断线重连沿用该键。

调用方也可以指定幂等键，例如用业务订单号保证同一段音频重复提交时只计费一次：

```java
EvalResult r = client.evaluate(audio, config, RequestOptions.withIdempotencyKey("order-20261008-0001"));
if (r.isReplayed()) {
    // 平台识别出重复提交，返回首次结果，没有再次计费
}
```

自定义的键由 1 到 200 个可打印 ASCII 字符组成，不含空格，不符合时在发出请求前抛出 `InvalidParameterException`，错误码 90010。

平台侧的幂等语义：

| 情形 | 平台行为 |
|---|---|
| 作用范围 | 按 appKey 隔离，令牌方式按用户隔离 |
| 有效期 | 首次成功结果保留 24 小时 |
| 同一个键，同一请求 | 返回首次成功的结果，响应头 `Idempotency-Replayed: true`，不再计费 |
| 首次请求仍在处理 | 平台最多等待 30 秒后重放结果，仍未完成时返回 409，错误码 40901，带 `Retry-After`，SDK 自动等待后重试 |
| 同一个键，不同请求 | 返回 409，错误码 40903，不重试 |
| 首次请求失败 | 释放该键，下次提交重新处理 |

重试策略 `RetryPolicy` 的默认值：

| 字段 | 默认值 | 含义 |
|---|---|---|
| `maxRetries` | 2 | 首次之外最多重试 2 次，总计 3 次尝试 |
| `initialDelayMs` | 200 | 第一次退避 |
| `multiplier` | 2.0 | 退避倍数 |
| `maxDelayMs` | 4000 | 退避上限 |
| `jitter` | 0.3 | 随机抖动幅度，正负 30 % |
| `respectRetryAfter` | true | 遵守服务端的 `Retry-After` |
| `maxRetryAfterMs` | 30000 | `Retry-After` 的上限 |

第 n 次重试前的等待：`min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))`，默认约为 200 毫秒与 400 毫秒。响应带 `Retry-After` 时取两者中较大的值。整次调用受 `totalTimeoutMs` 约束，默认 300 秒，等待会越过期限时不再重试，直接抛出最后一次的错误。

只重试可重试的错误：连接失败，读超时，HTTP 429 与 5xx，以及错误码表标记为可重试的错误码。参数错误，鉴权失败，额度不足不重试。关闭自动幂等键 `autoIdempotencyKey(false)` 后，没有幂等键的写操作不重试，避免重复计费。报告查询天然幂等，照常重试。每次重试都会在 WARN 级别写一条日志，例如 `retry 1/2 in 231 ms: HTTP 503 code=50200`，同时回调 `EventListener.onRetry`。

单次调用可覆盖默认值：

```java
RequestOptions opts = RequestOptions.builder()
        .idempotencyKey("order-20261008-0001")
        .retry(RetryPolicy.builder().maxRetries(4).build())
        .totalTimeoutMs(60_000)
        .readTimeoutMs(30_000)
        .cancellation(token)
        .build();
```

`CancellationToken` 可从其他线程取消调用，正在进行的请求立即中断，重试等待随即结束，调用抛出 `RequestCancelledException`，错误码 90003。

## 实时流式评测与断线重连

实时评测接收 16 kHz，16 位，小端，单声道 PCM，建议每帧 640 字节，即 20 毫秒。单次 `sendAudio` 可以送入任意长度的音频，SDK 按不超过 32000 字节一帧切分发送，平台对超过 128 KB 的帧以 1009 关闭连接。`streamEvaluate` 立即返回会话对象，连接在后台建立，开始前送入的音频先排队，开始后按顺序发出。

```java
StreamSession session = client.streamEvaluate(
        new EvaluateConfig(EvaluateConfig.CORE_SENTENCE, "今天天气很好", "zh-CN"),
        new StreamListener() {
            @Override public void onStateChanged(SessionState from, SessionState to) { ui.show(to); }
            @Override public void onReconnecting(int attempt, long delayMs, YuguException cause) { ui.showReconnecting(attempt); }
            @Override public void onReconnected(int attempt, long droppedBytes) { ui.showConnected(); }
            @Override public void onResult(EvalResult result) { ui.showScore(result.getOverall()); }
            @Override public void onError(YuguException error) { ui.showError(error); }
            @Override public void onClosed(int code, String reason) { ui.release(); }
        });
for (byte[] frame : pcmFrames) {
    session.sendAudio(frame);
}
session.end();
EvalResult result = session.result().get(60, TimeUnit.SECONDS);
```

会话状态 `SessionState` 与转换：

| 状态 | 含义 |
|---|---|
| `IDLE` | 会话已创建，尚未连接 |
| `CONNECTING` | 正在建立 WebSocket 连接 |
| `CONNECTED` | 服务端发出 `connected`，SDK 已发送开始帧 |
| `STARTED` | 服务端发出 `started`，音频开始传输 |
| `ENDING` | 已发送结束帧，等待终评 |
| `RECONNECTING` | 连接中断，等待重连 |
| `COMPLETED` | 已回调终评结果 |
| `FAILED` | 已回调错误 |
| `CANCELLED` | 调用方取消或客户端关闭 |
| `CLOSED` | 连接已释放，最后一个状态 |

正常流程为 `IDLE`，`CONNECTING`，`CONNECTED`，`STARTED`，`ENDING`，`COMPLETED`，`CLOSED`。`getState()` 随时返回最近一次 `onStateChanged` 通知的状态，`isActive()` 在终态之前返回 true。

回调约定：

1. 每个会话恰有一次 `onResult` 或 `onError`，先调用 `cancel()` 时两者都不回调。
2. `onClosed` 总是最后一个回调。
3. 同一会话的回调按顺序执行，互不并发。
4. 重连时的回调顺序为 `onReconnecting`，`onConnected`，`onStarted`，`onReconnected`。

触发重连的情形：传输层故障，结果前收到 1001，1006，1011 等可恢复的关闭码，心跳超时，连接或开始超时，结束后等待终评超时，以及错误码可重试的服务端错误帧，例如 40901，42901，50200。其他错误帧直接进入 `FAILED`。关闭码 1002，1003，1007，1008，1009，1010 以及 4000 到 4999 表示平台拒收这批数据，重连会被同样拒绝，会话直接进入 `FAILED`，错误码 90005。结果前收到 1000 同样进入 `FAILED`。平台不支持会话续传，每次重连都是新的服务端会话，参数与幂等键与原会话一致，平台据此保证同一段音频只计费一次。

重连策略 `ReconnectPolicy` 默认开启，每次中断最多连续重连 8 次，等待依次约为 0.5，1，2，4，4，4，4，4 秒，合计约 23 秒，抖动正负 30 %。默认设置可以撑过 10 秒左右的断网。次数只累计连续失败的尝试，重连成功后从零开始。一个会话累计最多重连 `maxAttempts` 的 3 倍，默认 24 次，服务端反复接受又断开连接时不会无限重连。任一上限用尽时回调 `onError`，错误码 90006，`getCause()` 为最后一次失败的原因。

重连期间的音频处理由 `AudioBufferPolicy` 决定：

| 策略 | 行为 | 结果覆盖范围 |
|---|---|---|
| `REPLAY`，默认 | 保留本次会话的全部音频，上限 10 MB。重连后重新发送开始帧，重放全部音频，补发重连期间排队的音频，已调用 `end()` 时再次发送结束帧。超过 10 MB 后下一次重连以 90008 失败 | 全部音频 |
| `DROP` | 不缓冲，重连期间送入的音频丢弃。`onReconnected` 的 `droppedBytes` 给出未计入结果的字节数。调用 `end()` 之后断线时直接以原因失败 | 重连之后送入的音频 |
| `FAIL` | 不重连，传输层故障直接进入 `FAILED` | 无 |

心跳使用 WebSocket 协议层 ping，默认每 15 秒一次，30 秒内收不到 pong 或任何帧即判定连接中断。发送结束帧后默认最多等待终评 300 秒，超时触发重连，重连关闭时回调 90007。以上时间可在 `ClientOptions` 中调整，单个会话可用 `StreamOptions` 覆盖幂等键，重连策略，缓冲策略，终评超时与重放上限。

声通兼容实时评测开启 `realtimeFeedback(true)` 后，服务端按收音进度下发进度帧，SDK 回调 `onPartial`，`StreamPartial.getBytes()` 为服务端已收到的字节数。原生实时评测没有进度帧。

## 生命周期

`YuguClient` 线程安全，一个应用创建一个实例即可。客户端实现 `AutoCloseable`，建议用 try-with-resources 管理：

```java
try (YuguClient client = YuguClient.builder()
        .apiKey(appKey, secretKey)
        .build()) {                                       // 创建
    EvalResult r = client.evaluate(audio, config);       // 使用
    StreamSession s = client.streamEvaluate(config, listener);
    s.sendAudio(pcm);
    s.end();
    s.result().get(60, TimeUnit.SECONDS);
}                                                         // 释放：close() 自动调用
```

释放规则：

| 操作 | 效果 |
|---|---|
| `client.close()` | 可重复调用。未结束的实时会话依次进入 `CANCELLED` 与 `CLOSED`，随后回调 `onClosed`。进行中的 REST 调用抛出错误码 90004，SDK 线程随即结束 |
| 关闭之后再调用 | 抛出 `IllegalSessionStateException`，错误码 90004 |
| `session.cancel()` | 可重复调用，不再回调结果与错误，连接以 1000 关闭 |
| `session.close()` | 可重复调用，未结束时等同 `cancel()` |
| `session.awaitClosed(timeout, unit)` | 等待会话进入 `CLOSED` |
| `client.getOpenSessionCount()` | 尚未关闭的会话数量 |

异常路径同样会释放资源：会话失败，被取消或客户端关闭时，连接都会关闭，定时器都会取消。SDK 的线程都是守护线程，不会阻止 JVM 退出。

## 音频预检

整段评测上传前与实时评测调用 `end()` 时，SDK 在本地检查音频，避免为无效音频付费。WAV 与原始 PCM 做全部检查，MP3 等其他格式只检查大小。

| 本地码 | 条件 | `WARN` 模式，默认 | `REJECT` 模式 |
|---|---|---|---|
| 90101 | 时长短于 1 秒 | 警告 | 上传前抛出 |
| 90102 | 时长超过 300 秒，或者整段评测大于 50 MB，或者实时评测单轮大于 10 MB | 警告 | 抛出 |
| 90103 | 全程静音：峰值低于 200，RMS 低于 30 | 警告 | 抛出 |
| 90104 | 音量过低：RMS 低于 -45 dBFS | 警告 | 警告 |
| 90105 | 不是 16 位 PCM 或采样率低于 16000 | 警告 | 抛出 |

静音阈值与平台的静音判定一致，本地拒绝的文件就是平台会评为 0 分的文件。警告写入 WARN 日志，整段评测放在 `getLocalWarnings()` 中，实时评测另外回调 `onWarning`。`audioPrecheck(AudioPrecheckMode.OFF)` 关闭预检。`AudioPrecheck.analyze(bytes)` 可单独调用，返回时长，峰值，RMS 与全部检查项。

## 外部音频

评测接口接受调用方自有的音频，录音设备，直播流与第三方采集都可接入：

| 来源 | 写法 |
|---|---|
| 字节数组 | `client.evaluate(bytes, config)` |
| 文件 | `client.evaluate(new File("a.wav"), config)` 或 `client.evaluate(Path.of("a.wav"), config)` |
| 输入流 | `client.evaluate(inputStream, config)`，读到流末尾，不关闭流 |
| 原始 PCM | `client.evaluate(AudioSource.pcm(pcm, 16000), config)`，SDK 封装为 WAV 后上传 |
| 实时流 | `session.sendAudio(pcm)`，可分任意大小的块送入 |

整段评测支持 WAV 与 MP3，平台接受的上限为 50 MB，SDK 读取上限为 64 MB。音频在一次调用内只读取一次，重试上传同一份数据。

## 日志与指标

日志级别 `LogLevel` 从低到高为 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，默认 `WARN`，写到标准错误输出。`logger` 选项可接入任意日志框架，例如 SLF4J：

```java
Logger slf = LoggerFactory.getLogger("yugu");
YuguClient client = YuguClient.builder()
        .apiKey(appKey, secretKey)
        .logLevel(LogLevel.INFO)
        .logger((level, tag, message, error) -> {
            if (level == LogLevel.ERROR) slf.error("[{}] {}", tag, message, error);
            else if (level == LogLevel.WARN) slf.warn("[{}] {}", tag, message, error);
            else slf.info("[{}] {}", tag, message, error);
        })
        .build();
```

| 级别 | 内容 |
|---|---|
| `ERROR` | SDK 内部问题，例如回调抛出异常 |
| `WARN` | 重试，重连，预检警告，实时会话失败 |
| `INFO` | 每次调用的状态码，耗时与尝试次数，会话状态变化 |
| `DEBUG` | 每次请求的方法，路径，超时与脱敏后的请求头，WebSocket 握手地址 |

日志不包含 secretKey，签名，令牌与音频数据，appKey 只保留前 4 个字符，后接 `***`。

指标回调 `EventListener` 的方法全部可选：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op, method, path, attempt)` | 每次 HTTP 尝试之前 |
| `onRequestEnd(op, httpStatus, latencyMs, attempts, error)` | 每次逻辑调用结束，含总耗时与尝试次数 |
| `onRetry(op, attempt, delayMs, error)` | 每次重试等待之前 |
| `onSessionStateChanged(sessionId, old, new)` | 实时会话每次状态变化 |
| `onReconnect(sessionId, attempt, succeeded)` | 每次重连尝试的结果 |

## 配置项

| 选项 | 默认值 | 含义 |
|---|---|---|
| `baseUrl` | `https://open.shengzhiai.com` | REST 地址 |
| `wsBaseUrl` | `wss://open.shengzhiai.com` | WebSocket 地址，只设置 `baseUrl` 时由其推导 |
| `apiKey(appKey, secretKey)` 或 `token(jwt)` | 必填 | 鉴权方式 |
| `connectTimeoutMs` | 10000 | 建立连接的超时 |
| `readTimeoutMs` | 120000 | 单次 HTTP 尝试的超时 |
| `totalTimeoutMs` | 300000 | 一次调用含重试与等待的总期限 |
| `retry` | `RetryPolicy.defaults()` | 重试策略 |
| `autoIdempotencyKey` | true | 写操作自动生成幂等键 |
| `logLevel` 与 `logger` | `WARN`，标准错误输出 | 日志 |
| `eventListener` | 无 | 指标回调 |
| `audioPrecheck` | `WARN` | 音频预检模式 |
| `strictAudio` | false | 平台警告 1001 改为抛出异常 |
| `userAgent` | `yugu-java-sdk/2.0.0` | `User-Agent` 请求头 |
| `reconnect` | `ReconnectPolicy.defaults()`，连续 8 次 | 实时会话重连策略 |
| `audioBufferPolicy` | `REPLAY` | 实时会话断线时的音频处理 |
| `pingIntervalMs` 与 `pongTimeoutMs` | 15000，30000 | 心跳 |
| `resultTimeoutMs` | 300000 | 结束后等待终评的时间 |

## 沙箱环境

开放平台为联调提供沙箱密钥，名称以 `sandbox-` 开头，每个密钥每天 200 次调用，超出后返回错误码 42903，不重试。沙箱与正式环境使用同一地址，只有密钥不同。申请方式与限制见仓库根目录的 `SANDBOX.md`。SDK 的集成测试在设置 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 两个环境变量后对沙箱运行。

## 构建与测试

```bash
cd java
./mvnw verify          # 编译，单元测试，基于平台模拟服务的集成测试，覆盖率门槛
./mvnw verify -Pslow   # 另外运行断网 10 秒的慢速测试
```

集成测试会启动 `tools/mock-server/server.mjs`，需要 Node 20 与该目录下的 `npm ci`。覆盖率报告位于 `target/site/jacoco/index.html`，行覆盖率低于 70 % 时构建失败。`ci/java.sh` 是持续集成使用的脚本。

## 常见问题

问题：调用一直返回 `AuthException`，错误码 2003。

解答：签名不匹配。核对 appKey 与 secretKey 是否成对，服务器时钟与标准时间相差是否超过 300 秒。签名方式与令牌方式同时配置时 SDK 使用签名方式。

问题：网络抖动时同一段音频会被计费几次。

解答：一次。SDK 的每次重试与每次重连都复用同一个幂等键，平台对同一键只计费一次。业务层自行重新提交时，把首次使用的键通过 `RequestOptions.withIdempotencyKey` 传入，平台返回首次结果，`isReplayed()` 为 true。

问题：错误 40903 是什么意思。

解答：同一个幂等键已经用于另一段音频或另一组参数。每段不同的音频应使用不同的键，自动生成的键天然满足这一点。

问题：实时评测断线后结果是否完整。

解答：默认的 `REPLAY` 策略重放全部音频，结果覆盖整段音频。`DROP` 策略只评测重连之后的音频，`onReconnected` 的 `droppedBytes` 给出缺失的字节数。

问题：如何区分“正在重连”，“重连失败”与“评测完成”。

解答：分别对应 `onReconnecting` 回调，错误码 90006 的 `onError` 回调与 `onResult` 回调。三者也可以从 `onStateChanged` 中的 `RECONNECTING`，`FAILED`，`COMPLETED` 状态判断。

问题：断网多久以内实时评测可以自动恢复。

解答：默认设置下连续重连 8 次，等待合计约 23 秒，网络在这段时间内恢复即可继续，默认的 `REPLAY` 策略重放全部音频，结果完整。超过后回调错误码 90006。`ReconnectPolicy.builder().maxAttempts(n)` 可调整次数。

问题：回调运行在哪个线程。

解答：运行在 SDK 的守护线程上。同一会话的回调依次执行，不会并发。回调中不宜执行耗时操作，界面更新需切换到界面线程。

问题：为什么评测结果的 `getLocalWarnings()` 中有 90104。

解答：本地预检发现音量偏低，音频照常上传评测，分数可能偏低，建议提示用户靠近麦克风重录。

问题：如何从 `1.0.0` 版本迁移。

解答：坐标改为 `com.shengzhiai.yugu:yugu-java-sdk:2.0.0`，包名由 `tech.dragonai.yugu` 改为 `com.shengzhiai.yugu`，`YuguException` 改为非受检异常，流式接口的参数改为 `EvaluateConfig` 与 `CompatConfig`，回调接口的方法有调整。详细说明见 `CHANGELOG.md` 与仓库根目录的 `MIGRATION-2.0.md`。

## 版本与许可

版本记录见 `CHANGELOG.md`，各端版本与接口版本的对应关系见仓库根目录的 `COMPATIBILITY.md`。SDK 按 Apache License 2.0 授权，全文见 `LICENSE`，第三方说明见 `NOTICE`。
