实时评测在一个 WebSocket 连接上边录边传，读完发出结束帧后返回终评。连接意外断开时 SDK 用同一个幂等键重连，重放这一轮已发的音频，评分覆盖整段录音，平台不重复计费。

## 会话时序

```diagram ws-session
```

## 调用

```tabs
items:
  - { label: Java, ref: 'java/README.md#实时流式评测与断线重连', lang: java }
  - { label: 安卓, ref: 'android/README.md#实时评测与重连#示例', lang: kotlin }
  - { label: iOS, ref: 'ios/README.md#实时评测#会话流程', lang: swift }
  - { label: 网页, ref: 'web/README.md#生命周期示例', lang: js, lines: 14-29 }
  - { label: 小程序, ref: 'miniprogram/README.md#五分钟上手', lang: js, n: 2 }
  - { label: Node.js, file: docs/site/snippets/ws-evaluate.mjs, lang: js, title: 直连协议 }
```

Java，安卓，网页与小程序的 `streamEvaluate` 立即返回会话对象，在后台建立连接，iOS 在调用 `session.start()` 后建立连接。开始前送入的音频先排队，开始后按顺序发出。声通兼容实时评测用 `streamEvaluateCompat`，Java，安卓，iOS 与小程序设置 `realtimeFeedback`，网页在参数里传 `realtime_feedback: true`，平台每收约 0.5 秒音频回一个进度帧，进度帧只有已收字节数，不含评分。

## 音频帧

| 项 | 要求 |
|---|---|
| 格式 | PCM，16 位，小端，16 kHz，单声道 |
| 帧长 | 推荐 640 字节，即 20 毫秒 |
| 拆帧 | 单次送入超过 32000 字节时 SDK 拆成多帧发送 |
| 单帧上限 | 128 KB，超出时平台以关闭码 1009 断开 |
| 一轮上限 | 10 MB |

录音器直接产出这种格式，安卓 `recorder.start(session)`，网页 `recorder.start({ session })`，小程序 `recorder.pipeTo(session)` 把录音逐帧送进会话，iOS 在 `YuguRecorder` 的 `onFrame` 回调里调用 `session.sendAudio(frame)`。

## 会话状态

```diagram session-states
```

| 状态 | 含义 |
|---|---|
| `IDLE` | 会话已创建，尚未连接 |
| `CONNECTING` | 正在建立连接 |
| `CONNECTED` | 平台发出 connected，SDK 已发出开始帧 |
| `STARTED` | 平台发出 started，音频开始传输 |
| `ENDING` | 结束帧已发出，等待终评 |
| `RECONNECTING` | 连接中断，等待重连 |
| `COMPLETED` | 已收到终评 |
| `FAILED` | 以错误结束 |
| `CANCELLED` | 调用方取消或客户端关闭 |
| `CLOSED` | 连接已释放，会话结束 |

回调约定：

1. 每个会话只触发一次终评回调或错误回调，调用方主动取消时两者都不触发。
2. 连接关闭回调总在最后。
3. 同一会话的回调按顺序执行，互不并发。
4. 任一时刻查询到的会话状态，与最近一次状态变化回调给出的新状态一致。

## 断线重连

```diagram ws-reconnect
```

| 项 | 规则 |
|---|---|
| 触发条件 | 连接意外断开，心跳超时，连接超时，终评超时，可重试的错误帧 |
| 不重连的情形 | 关闭码 1002，1003，1007 到 1010 与 4000 到 4999，会话以 90005 结束 |
| 连续重连 | 最多 8 次，等待依次约为 0.5，1，2，4，4，4，4，4 秒，抖动正负 30% |
| 一个会话累计 | 24 次 |
| 用尽之后 | 会话以错误结束，错误码 90006 |
| 结束帧之前断线 | 平台没有开始评测，新会话上评测一次，只计费一次 |
| 结束帧之后断线 | 平台按幂等键重放首次结果，首次仍在评测时最多等 30 秒，结果带重放标记 |
| 失败的情形 | `REPLAY` 缓冲超过 10 MB 时报 90008，`DROP` 策略在结束帧发出后断线即失败，平台以 1000 关闭却没有终评时报 90005 |

音频缓冲策略：

| 策略 | 行为 | 评分覆盖 |
|---|---|---|
| `REPLAY`，默认 | 缓冲这一轮的全部音频，上限 10 MB，重连后整段重放 | 全部音频 |
| `DROP` | 不缓冲，重连期间送入的音频丢弃 | 重连之后的音频 |
| `FAIL` | 不重连，断线即以错误结束 | 无 |

## 心跳与超时

| 端 | 心跳 | 判定断线 |
|---|---|---|
| Java，安卓，iOS | WebSocket 协议层 ping，每 15 秒一次 | 30 秒收不到 pong |
| 网页，小程序 | 应用层 `{"cmd":"ping"}`，每 15 秒一次，平台回 `{"event":"pong"}` | 30 秒收不到任何回复 |

网页与小程序的 `heartbeat` 默认取 `auto`：原生会话进入 STARTED 后先发一次探测 ping，平台回 pong 后才按 15 秒发送，同一客户端的声通兼容会话在收到过 pong 之后才发 ping。`heartbeat: true` 强制开启，`false` 关闭。

发出结束帧之后改由终评等待时长判断，默认 300 秒，超时触发重连。协议层细节见[心跳与断线重连](page:api-ws-reconnect)，帧格式见[原生实时评测](page:api-ws-evaluate)。
