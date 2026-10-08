```endpoint
method: POST
path: /api/v1/evaluate
```

```aside
- title: 请求示例
  tabs:
    - { label: cURL, file: docs/site/snippets/evaluate.sh, lang: bash }
    - { label: Java, ref: 'java/README.md#五分钟快速开始', lang: java, lines: 10-22 }
    - { label: 安卓, ref: 'android/README.md#五分钟快速开始', lang: kotlin, n: 2 }
    - { label: iOS, ref: 'ios/README.md#五分钟上手', lang: swift, n: 2 }
    - { label: 网页, ref: 'web/README.md#五分钟快速开始', lang: js, lines: 9-15 }
    - { label: 小程序, ref: 'miniprogram/README.md#五分钟上手', lang: js, lines: 12-21 }
- title: 返回示例
  fixture: spec/fixtures/platform/native_evaluate_sentence_zh.json
  label: 200，中文句子
- title: 错误示例
  fixture: spec/fixtures/platform/error_native_bad_signature.json
  pick: body
  label: 401，签名错误
```

## 请求头

| 请求头 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `X-App-Key` | string | 是 | appKey，签名鉴权时必填 |
| `X-Timestamp` | string | 是 | 当前 Unix 时间戳，单位为秒，与服务端相差不超过 300 秒 |
| `X-Nonce` | string | 否 | 随机串，服务端 300 秒内去重防重放，建议每次都带 |
| `X-Signature` | string | 是 | 签名值，被签名参数为 `config` 段的 JSON 原文，规则见[签名与鉴权](page:api-auth) |
| `Authorization` | string | 否 | `Bearer <jwt>`，token 鉴权时代替以上四个请求头 |
| `Idempotency-Key` | string | 否 | 幂等键，1 到 200 个可见 ASCII 字符，也认 `X-Idempotency-Key` |

## 请求体

```include
ref: CONTRACT.md#3 整段评测#3.1 原生接口
until: 成功时直接返回结果对象
```

## 返回

```include
ref: CONTRACT.md#3 整段评测#3.1 原生接口
from: 成功时直接返回结果对象
canonical: false
```

| 响应头 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `Idempotency-Replayed` | string | 否 | 值为 `true` 表示这是同一幂等键首次结果的重放，不再计费 |

## 状态码

| 状态 | 错误码 | 原因 |
|---|---|---|
| 200 | 无 | 评测完成，返回结果对象 |
| 400 | 40001 | 参数校验失败，例如音频短于 1 秒，pinyin 题缺 `refPinyin` |
| 401 | 40100，2001 到 2011 | 未认证，签名错误，token 或 API Key 无效 |
| 403 | 40300 | 无权限，例如 API Key 未授权该 coreType |
| 409 | 40901，40902，40903 | 同一幂等键处理中，额度不足，幂等键用于不同请求 |
| 415 | 无 | `config` 段缺少 `Content-Type: application/json` |
| 429 | 42900，42901，42903，3001 到 3003 | 限流，并发超限，沙箱当日次数用尽，带 `Retry-After` |
| 500 | 50000 | 服务器内部错误，可重试 |
| 502 | 50200 | 上游评测服务暂不可用，可重试 |
