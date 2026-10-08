## 分层结构

```diagram architecture
```

| 层 | 职责 |
|---|---|
| 公共接口 | `YuguClient` 提供整段评测，兼容评测，语音合成，报告查询与两条实时评测，实时评测返回会话对象，移动端，网页与小程序另有录音器。五端方法同名，参数按各语言习惯排列 |
| 声通平替层 | 安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 保留声通的类名，方法签名，回调与常量，内部调用声通兼容整段评测 |
| 可靠性 | 写操作生成幂等键，按策略重试可重试的错误，实时会话断线后重连，重放音频，上传前预检音频，把错误归入十六个类别 |
| 传输 | REST 走 HTTPS，`multipart/form-data` 上传音频，JSON 承载参数与结果，每次请求计算签名头。实时评测走 WSS，音频为二进制帧，带心跳 |
| 开放平台 | 原生接口与声通兼容接口共用一套评测引擎，幂等守卫按键识别重复提交，首次结果保存 24 小时 |

## 一次整段评测的处理过程

1. 预检：WAV 与 PCM 检查时长，静音，音量与格式，默认只告警，`REJECT` 模式下不合格的音频在上传前报错，不产生计费。
2. 幂等键：未指定时生成 32 位小写十六进制随机串，放进请求头 `Idempotency-Key`。
3. 签名：取被签名参数，按键名排序拼接，计算 HMAC-SHA256，写入 `X-Signature`，时间戳与随机串每次尝试重新生成。
4. 发送：连接超时，读取超时与总时限分别控制，默认 10 秒，120 秒与 300 秒。
5. 分类：失败时按错误码表归类，错误码缺失时按 HTTP 状态归类，得到类别与是否可重试。
6. 重试：可重试的错误按退避等待后用同一个幂等键再发，平台识别出重复提交时原样返回首次结果。
7. 解析：响应解析为结果模型，总分，维度分，逐字逐句详情，警告，幂等键，重放标记与尝试次数一并给出，原始 JSON 保留。

实时会话的状态机，心跳与重连见[实时评测](page:streaming)。

## 各端实现

| 端 | HTTP | WebSocket | 录音 | 回调线程 |
|---|---|---|---|---|
| Java | JDK `java.net.http` | JDK WebSocket | 不涉及 | SDK 回调线程池，同一会话按顺序执行 |
| 安卓 | OkHttp | OkHttp WebSocket | `AudioRecord` | 异步方法与会话回调默认在主线程 |
| iOS | `URLSession` | `URLSessionWebSocketTask` | `AVAudioEngine` | `callbackQueue`，缺省为主队列 |
| 网页 | `fetch` | 浏览器 WebSocket，Node.js 用 `ws` 包 | `getUserMedia` 加 `AudioWorklet` | 页面主线程 |
| 小程序 | `wx.request` | `wx.connectSocket` | `wx.getRecorderManager` | 小程序逻辑层 |

## 能力对照

五端与两个平替层支持的能力逐项列在 [SDK 总览](page:sdks#能力矩阵)。
