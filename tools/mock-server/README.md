# 平台模拟服务

各端 SDK 的集成测试共用的平台模拟服务，实现 SDK 调用的全部接口，签名与幂等规则与线上平台一致，可注入故障，可核对计费次数。

## 启动

```bash
cd tools/mock-server && npm ci
node server.mjs --port 0 --processing-ms 50
```

启动后在标准输出打印一行 `{"port": N}`，测试读这一行拿端口。测试凭据：appKey `mock-app-key`，secretKey `mock-secret-key`，token `mock-jwt-token`。

## 接口

| 接口 | 说明 |
|---|---|
| `POST /api/v1/evaluate` | 原生整段评测，config 段必须是 `application/json`，否则 415 |
| `POST /{coreType}` | 声通兼容整段评测，coreType 取声通命名。带 `attachAudioUrl=1` 时返回体顶层有 `audioUrl`，同一个幂等键重放时地址不变 |
| `GET /rec/{yyyyMMdd}/{文件名}` | 下载 `audioUrl` 指向的录音，内容为上传的原始字节，`POST /__mock/reset` 后清空 |
| `POST /api/v1/tts/generate` | 语音合成 |
| `GET /api/v1/report/{recordId}` | 报告查询 |
| `WS /api/v1/ws/evaluate` | 原生实时评测 |
| `WS /{coreType}` | 声通兼容实时评测 |

## 故障注入

请求头 `X-Mock-Fault` 只作用于本次请求。`POST /__mock/faults` 传 `{"faults":[{"match":"/api/v1/evaluate","fault":"status:500"}]}` 排队，按路径前缀匹配，用一次消耗一个。实时评测在握手 query 里带 `mockFault`。

| 故障 | 效果 |
|---|---|
| `status:500` | 返回该状态码，错误码按状态推断 |
| `status:429:code=42901:retryAfter=1` | 指定错误码与 Retry-After |
| `status:400:code=40001` | 参数错误 |
| `status:502:detail=xxx` | FastAPI 风格 `{"detail":...}` 错误体 |
| `delay:1500` | 先延迟再开始处理，延迟期间还没有登记幂等键 |
| `slow:3000` | 先登记幂等键再慢处理，模拟服务端算得慢，同一个键的重发会等首次结果 |
| `drop` | 直接断开连接 |
| `hang` | 不应答 |
| `ws-kill-after:N` | 收到 N 帧后强断 |
| `ws-close-after:N` | 收到 N 帧后以 1011 关闭 |
| `ws-silent:N` | 收到 N 帧后不再应答，整个会话也不回协议层 pong |
| `ws-error:50200` | 结束时回带错误码的 error 帧 |
| `ws-delay-result:3000` | 终评延迟 |
| `ws-refuse` | 握手返回 503 |
| `progress-mismatch:I` | 朗读进度里第 I 个单位已读但未对上 |
| `progress-off` | 打开朗读进度时进度帧只有 `bytes` |

## 朗读进度

兼容参数帧带 `realtime_feedback`，或原生开始帧带 `realtimeFeedback` 时，每收 16000 字节音频回一个进度帧，格式同 CONTRACT.md 5.5 节：两条通道都带 `progress`，`readUnits`，`matchedUnits` 与 `totalUnits`，兼容通道另带空串 `recordId`，内核与资源版本，以及声通式增量 `words`。每 8000 字节算读完一个单位，读过的单位都已对上，故障 `progress-mismatch` 除外。收到结束帧后先回一个多读一个单位的进度帧，再回终评，模拟平台在评测期间送达最后一段音频的识别结果。

## 核对接口

| 接口 | 说明 |
|---|---|
| `GET /__mock/log` | 收到的请求，含路径，幂等键，故障 |
| `GET /__mock/billing` | 真正执行的评测次数，按幂等键汇总 |
| `POST /__mock/reset` | 清空状态 |
