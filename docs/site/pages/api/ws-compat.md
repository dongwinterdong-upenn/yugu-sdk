```endpoint
method: WSS
path: /{coreType}
```

```aside
- title: 帧序列，沙箱实录
  frames: docs/site/data/ws-compat-session.json
- title: 示例代码
  tabs:
    - { label: Node.js, file: docs/site/snippets/ws-compat.mjs, lang: js, title: 直连协议 }
```

## 握手参数

query 参数与[原生实时评测](page:api-ws-evaluate#握手参数)相同，路径中的 coreType 取值同[声通兼容整段评测](page:api-evaluate-compat#路径参数)。

## 会话流程

```include
ref: CONTRACT.md#5 实时评测#5.2 声通兼容实时评测
```

## 客户端消息

| 帧字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| 参数帧 | 文本帧 | 是 | `{"refText": ..., "language": ..., "realtime_feedback": true, "idempotencyKey": ...}`，也接受 `text` 代替 `refText`，一轮只发一次 |
| 二进制帧 | 音频 | 是 | 规则同原生实时评测 |
| `{"cmd":"end"}` | 文本帧 | 是 | 结束帧，也接受 `{"end": true}` |
| `{"cmd":"ping"}` | 文本帧 | 否 | 应用层心跳 |

## 服务端消息

| 帧字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `{"event":"connected"}` | 文本帧 | 是 | 连接建立，带 `coreType` |
| `{"event":"started"}` | 文本帧 | 是 | 参数帧已受理 |
| `{"eof":0}` | 文本帧 | 否 | 进度帧，`result.bytes` 为已收字节数，`realtime_feedback` 为 true 时约每 0.5 秒一个，不含评分 |
| `{"eof":1}` | 文本帧 | 是 | 终评，带 `recordId` 与 `result`，重放时带 `"replayed": true` |
| `{"event":"error"}` | 文本帧 | 否 | 错误，带 `message`，原因是服务端业务错误时另带 `code` |
| `{"event":"pong"}` | 文本帧 | 否 | 心跳回复 |
