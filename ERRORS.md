# 错误码表

错误码表版本 2026-10-08，由 `spec/errors.json` 生成，各端 SDK 内置同一份表。

## 服务端错误码

响应体里的 `code` 字段。“常见 HTTP 状态”为典型值，SDK 先按错误码归类，错误码缺失时再按 HTTP 状态归类。

| 错误码 | 名称 | 常见 HTTP 状态 | 类别 | 可重试 | 含义 |
|---|---|---|---|---|---|
| 40001 | `VALIDATION_ERROR` | 400 | `INVALID_PARAM` | 否 | 请求参数校验失败 |
| 40100 | `UNAUTHORIZED` | 401 | `AUTH` | 否 | 未认证，缺少或无效的凭据 |
| 40300 | `FORBIDDEN` | 403 | `PERMISSION` | 否 | 无权限，例如 API Key 未授权该 coreType |
| 40400 | `NOT_FOUND` | 404 | `NOT_FOUND` | 否 | 资源不存在 |
| 40900 | `CONFLICT` | 409 | `CONFLICT` | 否 | 资源冲突 |
| 40901 | `IDEMPOTENCY_IN_PROGRESS` | 409 | `CONFLICT` | 是 | 同一幂等键的请求仍在处理中，稍后用同一个键重试 |
| 40902 | `QUOTA_INSUFFICIENT` | 409 | `QUOTA` | 否 | 套餐额度和账户余额均不足 |
| 40903 | `IDEMPOTENCY_KEY_REUSED` | 409 | `CONFLICT` | 否 | 幂等键已用于另一个不同的请求 |
| 42900 | `TOO_MANY_REQUESTS` | 429 | `RATE_LIMIT` | 是 | 请求过多或评测排队超时，按 Retry-After 稍后重试 |
| 42901 | `CONCURRENCY_LIMIT` | 429 | `RATE_LIMIT` | 是 | 并发评测数超出套餐层级限制 |
| 42902 | `TRIAL_REPORT_DAILY_LIMIT` | 429 | `QUOTA` | 否 | 试用层 AI 报告达到每日上限 |
| 42903 | `SANDBOX_DAILY_LIMIT` | 429 | `QUOTA` | 否 | 沙箱密钥当日调用次数达到上限 |
| 50000 | `INTERNAL_ERROR` | 500 | `SERVER` | 是 | 服务器内部错误 |
| 50010 | `FEATURE_NOT_IMPLEMENTED` | 500 | `SERVER` | 否 | 功能未实现 |
| 50200 | `UPSTREAM_SERVICE_ERROR` | 502 | `UPSTREAM` | 是 | 上游评测服务暂不可用 |
| 2001 | `TOKEN_EXPIRED` | 401 | `AUTH` | 否 | token 已过期，兼容层早期引擎接口也用此码表示缺少鉴权三元组或签名不匹配 |
| 2002 | `TOKEN_INVALID` | 401 | `AUTH` | 否 | token 无效，兼容层早期引擎接口也用此码表示时间戳超窗 |
| 2003 | `SIGNATURE_VERIFICATION_FAILED` | 401 | `AUTH` | 否 | 签名验证失败，兼容层早期引擎接口也用此码表示 appKey 不存在 |
| 2004 | `SESSION_EXPIRED` | 401 | `AUTH` | 否 | 会话已过期 |
| 2005 | `TOKEN_BLACKLISTED` | 401 | `AUTH` | 否 | token 已被列入黑名单 |
| 2006 | `API_KEY_INVALID` | 401 | `AUTH` | 否 | API Key 无效 |
| 2007 | `API_KEY_EXPIRED` | 401 | `AUTH` | 否 | API Key 已过期 |
| 2008 | `TOKEN_METADATA_NOT_FOUND` | 401 | `AUTH` | 否 | token 元数据丢失 |
| 2009 | `TOKEN_REFRESH_FAILED` | 401 | `AUTH` | 否 | token 刷新失败 |
| 2010 | `API_KEY_NOT_FOUND` | 401 | `AUTH` | 否 | API Key 不存在 |
| 2011 | `API_KEY_DISABLED` | 401 | `AUTH` | 否 | API Key 已禁用 |
| 2012 | `API_KEY_QUOTA_EXCEEDED` | 400 | `QUOTA` | 否 | API Key 配额已超限 |
| 3001 | `RATE_LIMIT_EXCEEDED_IP` | 429 | `RATE_LIMIT` | 是 | 请求过于频繁，按来源地址限流 |
| 3002 | `RATE_LIMIT_EXCEEDED_USER` | 429 | `RATE_LIMIT` | 是 | 请求过于频繁，按用户限流 |
| 3003 | `RATE_LIMIT_EXCEEDED_API_KEY` | 429 | `RATE_LIMIT` | 是 | 请求过于频繁，按 API Key 限流 |
| 1004 | `USER_DISABLED` | 400 | `PERMISSION` | 否 | 用户已禁用 |
| 1005 | `USER_LOCKED` | 400 | `PERMISSION` | 否 | 用户已锁定 |
| 1012 | `ACCOUNT_DISABLED` | 400 | `PERMISSION` | 否 | 账户已禁用 |
| 1013 | `ACCOUNT_FROZEN` | 400 | `PERMISSION` | 否 | 账户已冻结 |
| 1310 | `ACCOUNT_BALANCE_INSUFFICIENT` | 400 | `QUOTA` | 否 | 账户余额不足 |
| 1311 | `ACCOUNT_LEDGER_CONFLICT` | 400 | `CONFLICT` | 是 | 账目并发冲突，重试即可 |

## 音频质量警告码

评测结果 `warning` 数组里的码，评分照常返回，不作为异常抛出。SDK 把这些码归为 `AUDIO` 类别。

| 警告码 | 名称 | 原文 | 重新提交可能改善 | 含义 |
|---|---|---|---|---|
| 1001 | `NO_VALID_AUDIO` | `No valid audio detected!` | 否 | 未检测到有效音频，分数不可信，需要重录 |
| 1002 | `VOLUME_TOO_LOW` | `Audio volume too low!` | 否 | 音量过低 |
| 1003 | `VOLUME_TOO_HIGH` | `Audio volume too high!` | 否 | 音量过高，有截幅 |
| 1004 | `AUDIO_NOISY` | `Audio noisy!` | 否 | 环境噪声明显 |
| 1005 | `AUDIO_INCOMPLETE` | `Audio not complete!` | 否 | 音频疑似不完整，分数仅供参考 |
| 1009 | `SCORER_DEGRADED` | `scorer degraded` | 是 | 部分评分组件临时降级，重新提交可能恢复 |

## SDK 本地错误码

SDK 在本地发现的问题，没有服务端错误码时使用。

| 错误码 | 名称 | 类别 | 可重试 | 含义 |
|---|---|---|---|---|
| 90001 | `NETWORK_ERROR` | `NETWORK` | 是 | 网络错误，连接失败或被重置 |
| 90002 | `TIMEOUT` | `TIMEOUT` | 是 | 连接或读取超时 |
| 90003 | `CANCELLED` | `CANCELLED` | 否 | 调用方取消了请求 |
| 90004 | `CLIENT_CLOSED` | `STATE` | 否 | 客户端已关闭 |
| 90005 | `PROTOCOL_ERROR` | `PROTOCOL` | 否 | 响应或帧无法解析 |
| 90006 | `RECONNECT_EXHAUSTED` | `NETWORK` | 否 | 实时连接重连次数用尽 |
| 90007 | `RESULT_TIMEOUT` | `TIMEOUT` | 是 | 结束后等待终评结果超时 |
| 90008 | `REPLAY_BUFFER_OVERFLOW` | `STATE` | 否 | 重连重放缓冲超过上限 |
| 90009 | `INVALID_STATE` | `STATE` | 否 | 当前会话状态不允许该操作 |
| 90010 | `INVALID_ARGUMENT` | `INVALID_PARAM` | 否 | 调用参数不合法 |
| 90011 | `TLS_ERROR` | `NETWORK` | 否 | 证书校验失败 |
| 90101 | `AUDIO_TOO_SHORT` | `AUDIO` | 否 | 音频短于 1 秒 |
| 90102 | `AUDIO_TOO_LONG` | `AUDIO` | 否 | 音频长于 300 秒，整段上传大于 50 MB，或实时评测一轮大于 10 MB |
| 90103 | `AUDIO_SILENT` | `AUDIO` | 否 | 音频全程静音 |
| 90104 | `AUDIO_LOW_VOLUME` | `AUDIO` | 否 | 音量过低 |
| 90105 | `AUDIO_FORMAT_UNSUPPORTED` | `AUDIO` | 否 | 不是 16 位 PCM 或采样率低于 16000 |
| 90201 | `RECORDER_PERMISSION_DENIED` | `PERMISSION` | 否 | 没有麦克风权限 |
| 90202 | `RECORDER_UNAVAILABLE` | `STATE` | 否 | 麦克风被占用或不可用 |
| 90203 | `RECORDER_ERROR` | `STATE` | 否 | 录音过程出错 |

## HTTP 状态兜底归类

| HTTP 状态 | 类别 |
|---|---|
| 400 | `INVALID_PARAM` |
| 401 | `AUTH` |
| 403 | `PERMISSION` |
| 404 | `NOT_FOUND` |
| 405 | `INVALID_PARAM` |
| 408 | `TIMEOUT` |
| 409 | `CONFLICT` |
| 413 | `INVALID_PARAM` |
| 415 | `INVALID_PARAM` |
| 422 | `INVALID_PARAM` |
| 425 | `RATE_LIMIT` |
| 429 | `RATE_LIMIT` |
| 500 | `SERVER` |
| 501 | `SERVER` |
| 502 | `UPSTREAM` |
| 503 | `UPSTREAM` |
| 504 | `UPSTREAM` |

错误码缺失时可重试的 HTTP 状态：408，425，429，500，502，503，504。

## 声通平替层 errId

安卓与 iOS 声通平替层在 `onScore` 回调的错误 JSON 里给出 `errId`。服务端错误码原样作为 errId，下表为平替层自有的 errId。

| errId | 含义 |
|---|---|
| 20009 | 网络或服务端临时故障，可重评，autoRetry 默认重评此码 |
| 60001 | 音频文件不存在或不可读 |
| 60002 | 音频为空 |
| 60003 | coreType 不支持 |
| 60004 | 麦克风不可用或没有录音权限 |
| 60005 | 音频短于 1 秒 |
| 60006 | refText 为空 |
| 60007 | 引擎未初始化 |
| 60008 | 引擎正忙，上一次评测未结束 |
| 60009 | 音频大于 50 MB 或长于 300 秒 |
