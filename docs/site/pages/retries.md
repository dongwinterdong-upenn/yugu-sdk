## 自动幂等键

写操作 `evaluate`，`evaluateCompat`，`tts` 每次逻辑调用自动生成一个幂等键，32 位小写十六进制，放进请求头 `Idempotency-Key`。实时会话把同一个键放进握手参数与开始帧。重试与断线重连都复用这个键，平台按键只评测一次，只计费一次，重复提交时原样返回首次结果，标记为重放。

```diagram rest-retry
```

## 指定幂等键

需要同一份作业只计费一次时，用业务单号作为幂等键，例如作业号加题号。键为 1 到 200 个可见 ASCII 字符，不含空格，不合规时在发出请求前报错，错误码 90010。

```tabs
items:
  - { label: Java, ref: 'java/README.md#重试与幂等', lang: java, n: 1 }
  - label: 安卓
    lang: kotlin
    code: |
      val result = client.evaluate(config, audio, RequestOptions(idempotencyKey = "order-20261008-0001"))
      if (result.replayed) {
          // 平台识别出重复提交，返回首次结果，没有再次计费
      }
  - { label: iOS, ref: 'ios/README.md#重试与幂等#幂等键', lang: swift }
  - label: 网页
    lang: js
    code: |
      const result = await client.evaluate(audio, config, { idempotencyKey: 'order-20261008-0001' });
      if (result.replayed) {
        // 平台识别出重复提交，返回首次结果，没有再次计费
      }
  - label: 小程序
    lang: js
    code: |
      const result = await client.evaluate(params, { idempotencyKey: 'order-20261008-0001' });
      if (result.replayed) {
        // 平台识别出重复提交，返回首次结果，没有再次计费
      }
```

首次请求失败时平台释放这个键，再用同一个键提交会重新评测。同一个键用于内容不同的请求时平台回 409，错误码 40903，不重试。平台侧的完整语义见[幂等](page:api-idempotency)。

## 重试策略

五端默认值相同：

| 字段 | 默认值 | 含义 |
|---|---|---|
| `maxRetries` | 2 | 首次之外最多重试 2 次，一共 3 次尝试 |
| `initialDelayMs` | 200 | 第一次退避 |
| `multiplier` | 2 | 退避倍数 |
| `maxDelayMs` | 4000 | 退避上限 |
| `jitter` | 0.3 | 随机抖动，正负 30% |
| `respectRetryAfter` | true | 遵守响应头 `Retry-After` |
| `maxRetryAfterMs` | 30000 | `Retry-After` 的上限 |
| `totalTimeoutMs` | 300000 | 一次逻辑调用含全部重试与等待的总时限 |

```text
delay(n) = min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter))
响应带 Retry-After 时 delay = max(delay, min(retryAfterMs, maxRetryAfterMs))
now + delay 超过总时限时停止重试，抛出最后一次的错误
```

默认约在 200 毫秒与 400 毫秒后各重试一次。只重试可重试的错误：连接失败，超时，HTTP 408，425，429，500，502，503，504，以及错误码表标为可重试的错误码，例如 40901，42900，42901，50000，50200。参数错误，鉴权失败，额度不足不重试。每次重试都回调 `onRetry`，同时写一条 WARN 日志，例如 `retry 1/2 in 231 ms: HTTP 503 code=50200`。

## 单次调用覆盖

```tabs
items:
  - { label: Java, ref: 'java/README.md#重试与幂等', lang: java, n: 2 }
  - label: 安卓
    lang: kotlin
    code: |
      val options = RequestOptions(totalTimeoutMs = 60_000, retryPolicy = RetryPolicy(maxRetries = 4))
      val result = client.evaluate(config, audio, options)
  - label: iOS
    lang: swift
    code: |
      let options = RequestOptions(timeoutMs: 60_000, retry: RetryPolicy(maxRetries: 4))
      let result = try await client.evaluate(audio: .file(wavURL), config: config, options: options)
  - label: 网页
    lang: js
    code: |
      const result = await client.evaluate(audio, config, { retry: { maxRetries: 4 }, totalTimeoutMs: 60000 });
  - label: 小程序
    lang: js
    code: |
      const result = await client.evaluate(params, { retry: { maxRetries: 4 }, totalTimeoutMs: 60000 });
```

客户端级别的默认值在创建客户端时设置，例如 iOS 的 `YuguClientOptions.retry` 与 `totalTimeoutMs`，网页与小程序的 `retry` 与 `totalTimeoutMs` 选项，`retry: false` 关闭重试。

关闭自动幂等键后，没有幂等键的写操作不重试，实时会话不重连，避免重复计费。报告查询是只读请求，照常重试。业务代码已有重试时改用 SDK 的策略，两层重试叠加会拉长总耗时。
