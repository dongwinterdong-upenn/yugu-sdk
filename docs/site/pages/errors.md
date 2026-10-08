SDK 把平台错误码，HTTP 状态与本地发现的问题统一归入十六个类别，每个类别对应一个错误类型，错误对象保留原始错误码，HTTP 状态，是否可重试，幂等键，记录号与尝试次数。音频质量警告随结果返回，不作为错误。

## 处理写法

```tabs
items:
  - { label: Java, ref: 'java/README.md#错误处理', lang: java }
  - { label: 安卓, ref: 'android/README.md#错误处理', lang: kotlin }
  - { label: iOS, ref: 'ios/README.md#错误处理', lang: swift }
  - { label: 网页, ref: 'web/README.md#错误处理', lang: js }
  - { label: 小程序, ref: 'miniprogram/README.md#错误处理', lang: js }
```

Java，安卓，网页与小程序按错误类型分支，类型名在四端相同，iOS 以 `YuguError` 返回，按 `category` 分支。

## 错误类别

| 类别 | 错误类型 | 典型错误码 | 可重试 |
|---|---|---|---|
| `NETWORK` | `NetworkException` | 90001 连接失败，90006 重连次数用尽，90011 证书校验失败 | 90001 可重试 |
| `TIMEOUT` | `RequestTimeoutException` | 90002 连接或读取超时，90007 等待终评超时，HTTP 408 | 是 |
| `AUTH` | `AuthException` | 40100，2001 到 2011，HTTP 401 | 否 |
| `PERMISSION` | `PermissionException` | 40300，1004，1005，1012，1013，90201，HTTP 403 | 否 |
| `INVALID_PARAM` | `InvalidParameterException` | 40001，90010，HTTP 400，413，415，422 | 否 |
| `NOT_FOUND` | `NotFoundException` | 40400，HTTP 404 | 否 |
| `CONFLICT` | `ConflictException` | 40901 同一幂等键处理中，40903 幂等键用于其他请求，1311 | 40901 与 1311 可重试 |
| `RATE_LIMIT` | `RateLimitException` | 42900，42901，3001 到 3003，HTTP 429 | 是 |
| `QUOTA` | `QuotaExceededException` | 40902，42902，42903，1310，2012 | 否 |
| `SERVER`，`UPSTREAM` | `ServerException` | 50000，50010，50200，HTTP 500，502，503，504 | 50010 之外可重试 |
| `AUDIO` | `AudioQualityException` | 90101 到 90105，`strictAudio` 下的 1001 | 否 |
| `STATE` | `IllegalSessionStateException` | 90004 客户端已关闭，90008 重放缓冲超限，90009 状态不允许 | 否 |
| `CANCELLED` | `RequestCancelledException` | 90003 | 否 |
| `PROTOCOL` | `ProtocolViolationException` | 90005 响应或帧无法解析 | 否 |
| `UNKNOWN` | `YuguException` 或 `YuguError` | 错误码未登记，HTTP 状态也不属于 4xx 与 5xx | 否 |

## 可重试判定

五端的可重试判定规则相同，SDK 的重试与重连按同一规则执行。Java，安卓与 iOS 公开为 `YuguErrors.isRetryable`，网页与小程序为 `isRetryable`，错误对象另带 `retryable` 字段：

1. 本地码中 90001，90002，90007 可重试，其他本地码不可重试。
2. 平台错误码按错误码表的可重试标记判定。
3. 错误码缺失或未登记时，HTTP 408，425，429，500，502，503，504 可重试。

判定为可重试的错误，SDK 已按策略重试，仍然失败时可以稍后用同一个幂等键再次提交，平台不会重复计费。遇到不可重试的错误时，修正参数，凭据或额度后再提交。

## 排查信息

上报问题时在控制台提交工单，附上错误码，HTTP 状态，幂等键，记录号与尝试次数，平台按幂等键与记录号定位到具体请求。错误对象另保留原始响应体，网页 SDK 另附 `traceId`。

完整错误码表与错误在协议中的形态见[错误码](page:api-errors)。
