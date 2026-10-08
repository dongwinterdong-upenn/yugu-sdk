## 日志级别

五端的日志级别相同，从低到高为 `OFF`，`ERROR`，`WARN`，`INFO`，`DEBUG`，默认 `WARN`。

| 级别 | 内容 |
|---|---|
| `ERROR` | SDK 内部问题，例如回调抛出异常 |
| `WARN` | 重试，重连，预检警告，实时会话失败 |
| `INFO` | 每次调用的状态码，耗时与尝试次数，会话状态变化 |
| `DEBUG` | 每次请求的方法，路径，超时与脱敏后的请求头，WebSocket 握手地址 |

日志不含 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符，地址里的 `signature` 与 `token` 参数显示为 `***`。默认输出位置：Java 为标准错误输出，安卓为 Logcat 标签 `YuguSDK`，iOS 为 `ConsoleLogger`，网页与小程序为控制台。

## 接入自有日志

```tabs
items:
  - { label: Java, ref: 'java/README.md#日志与指标', lang: java }
  - { label: 安卓, ref: 'android/README.md#日志与指标', lang: kotlin }
  - { label: iOS, ref: 'ios/README.md#日志与指标', lang: swift }
  - { label: 网页, file: docs/site/snippets/observability-web.mjs, region: logging, lang: js }
  - { label: 小程序, ref: 'miniprogram/README.md#日志与指标', lang: js }
```

## 指标回调

指标回调 `eventListener` 的方法全部可选，适合接入监控系统：

| 方法 | 时机 |
|---|---|
| `onRequestStart(op, method, path, attempt)` | 每次 HTTP 尝试之前 |
| `onRequestEnd(op, httpStatus, latencyMs, attempts, error)` | 每次逻辑调用结束，耗时含全部重试与等待 |
| `onRetry(op, attempt, delayMs, error)` | 每次重试等待之前 |
| `onSessionStateChanged(sessionId, oldState, newState)` | 实时会话每次状态变化 |
| `onReconnect(sessionId, attempt, succeeded)` | 每次重连尝试的结果 |

`op` 取 `evaluate`，`evaluateCompat`，`tts`，`getReport`。回调与日志函数抛出的异常由 SDK 捕获后写入日志，不影响所在的调用。

## 建议的监控项

| 指标 | 来源 | 关注点 |
|---|---|---|
| 调用耗时 | `onRequestEnd` 的 `latencyMs` | 整段评测耗时随音频时长增长，按题型分开统计 |
| 重试次数 | `onRetry` 与 `attempts` | 持续偏高时检查出口网络与平台限流 |
| 错误类别 | 错误对象的 `category` 与 `code` | `RATE_LIMIT` 与 `QUOTA` 需要扩容或续费 |
| 重连次数 | `onReconnect` | 移动网络切换时的断线比例 |
| 重放比例 | 结果的重放标记 | 比例高说明客户端存在重复提交 |
