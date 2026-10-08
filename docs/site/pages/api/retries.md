```include
ref: CONTRACT.md#7 重试
```

## 退避参数

| 字段 | 默认值 | 含义 |
|---|---|---|
| `maxRetries` | 2 | 首次之外最多重试 2 次 |
| `initialDelayMs` | 200 | 第一次退避 |
| `multiplier` | 2 | 退避倍数 |
| `maxDelayMs` | 4000 | 退避上限 |
| `jitter` | 0.3 | 随机系数 0.7 到 1.3 |
| `maxRetryAfterMs` | 30000 | `Retry-After` 的上限 |
| `totalTimeoutMs` | 300000 | 一次逻辑调用的总时长上限 |

各端的配置写法见[重试与幂等](page:retries#单次调用覆盖)。
