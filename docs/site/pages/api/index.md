## 接口一览

```apiindex
- { method: POST, path: /api/v1/evaluate, title: 原生整段评测, href: 'page:api-evaluate' }
- { method: POST, path: '/{coreType}', title: 声通兼容整段评测, href: 'page:api-evaluate-compat' }
- { method: POST, path: /api/v1/tts/generate, title: 语音合成, href: 'page:api-tts' }
- { method: GET, path: '/api/v1/report/{recordId}', title: 报告查询, href: 'page:api-report' }
- { method: WSS, path: /api/v1/ws/evaluate, title: 原生实时评测, href: 'page:api-ws-evaluate' }
- { method: WSS, path: '/{coreType}', title: 声通兼容实时评测, href: 'page:api-ws-compat' }
```

契约版本 `2.0`，适用接口版本 v1 与 SDK `2.0.0`，五端 SDK 与平台按同一份契约对齐，`1.x` 契约的全部接口与字段继续有效。没有 SDK 的语言直接按契约调用，或用 [OpenAPI 描述](page:api-openapi)生成客户端。

## 基址与限额

```include
ref: CONTRACT.md#1 基址与连接
```

## 通用约定

| 项 | 约定 |
|---|---|
| 编码 | 请求与响应一律 UTF-8，JSON 字段名区分大小写 |
| 鉴权 | appKey 签名或 Bearer token，见[签名与鉴权](page:api-auth) |
| 幂等 | 写操作带 `Idempotency-Key`，实时评测在开始帧或参数帧带 `idempotencyKey`，见[幂等](page:api-idempotency) |
| 时间单位 | `span` 的起止时间单位为 10 毫秒，连读题 `boundaries` 的 `start_ms`，`end_ms`，`gap_ms` 为毫秒，`duration` 为秒 |
| 分数 | 默认百分制，`scale` 改变分制后按比例换算 |
| 错误 | 平台信封 `{"code": 40001, "message": "..."}`，错误码见[错误码](page:api-errors) |
| 重试 | 只对带幂等键或天然幂等的请求重试，见[重试](page:api-retries) |

## 契约全文

契约原文见 [CONTRACT.md](/sdk/v2/CONTRACT.md)。
