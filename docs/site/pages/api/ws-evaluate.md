```endpoint
method: WSS
path: /api/v1/ws/evaluate
```

```aside
- title: 帧序列，沙箱实录
  frames: docs/site/data/ws-native-session.json
- title: 示例代码
  tabs:
    - { label: Node.js, file: docs/site/snippets/ws-evaluate.mjs, lang: js, title: 直连协议 }
    - { label: Java, ref: 'java/README.md#实时流式评测与断线重连', lang: java }
    - { label: 安卓, ref: 'android/README.md#实时评测与重连#示例', lang: kotlin }
    - { label: iOS, ref: 'ios/README.md#实时评测#会话流程', lang: swift }
    - { label: 小程序, ref: 'miniprogram/README.md#五分钟上手', lang: js, n: 2 }
```

## 握手参数

| query 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `appKey` | string | 否 | appKey，签名鉴权时必填，token 鉴权时不带 |
| `timestamp` | string | 否 | 签名鉴权时必填，当前 Unix 时间戳，单位为秒 |
| `nonce` | string | 否 | 随机串，防重放 |
| `signature` | string | 否 | 签名鉴权时必填，被签名参数为 `signature` 之外的全部 query 参数 |
| `idempotencyKey` | string | 否 | 幂等键，开始帧没带时取这里的值 |
| `token` | string | 否 | token 鉴权时必填，代替以上签名参数 |

## 会话流程

```include
ref: CONTRACT.md#5 实时评测#5.1 原生实时评测
```

## 客户端消息

| 帧字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `{"cmd":"start"}` | 文本帧 | 是 | 开始帧，接受原生整段评测 config 的全部字段，另带 `idempotencyKey` |
| 二进制帧 | 音频 | 是 | PCM 16 位小端，16 kHz，单声道，推荐 640 字节一帧，单帧不超过 128 KB |
| `{"cmd":"audio","data":"<base64>"}` | 文本帧 | 否 | 音频的文本帧写法，与二进制帧二选一 |
| `{"cmd":"end"}` | 文本帧 | 是 | 结束帧，发出后等待终评 |
| `{"cmd":"ping"}` | 文本帧 | 否 | 应用层心跳，平台回 `{"event":"pong"}`，不改变会话状态 |

## 服务端消息

| 帧字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `{"event":"connected"}` | 文本帧 | 是 | 连接建立 |
| `{"event":"started"}` | 文本帧 | 是 | 开始帧已受理，可以送音频 |
| `{"event":"result"}` | 文本帧 | 是 | 终评，带 `recordId`，`eof: 1`，`result`，`report`，`asrText`，`warnings`，重放时带 `"replayed": true` |
| `{"event":"error"}` | 文本帧 | 否 | 错误，带 `message`，原因是服务端业务错误时另带 `code` |
| `{"event":"pong"}` | 文本帧 | 否 | 心跳回复，带服务端毫秒时间戳 `ts` |

断线后的重连与重放见[心跳与断线重连](page:api-ws-reconnect)，`result` 字段见[评测结果字段](page:api-results)。
