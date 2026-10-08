# 优谷雅言语音评测接口契约

契约版本 `2.0`，登记日期 2026-10-08，适用接口版本 v1 与 SDK `2.0.0`。五端 SDK 与服务端按本契约对齐，字段，签名算法，协议帧均取自线上服务端代码。`1.x` 版本契约的全部接口与字段继续有效，`2.0` 新增幂等，重试，实时评测心跳与断线重连语义，错误码分类，沙箱与声通平替层。

## 1 基址与连接

| 用途 | 地址 |
|---|---|
| REST 基址 | `https://open.shengzhiai.com` |
| WebSocket 基址 | `wss://open.shengzhiai.com` |
| 接口文档 | `https://open.shengzhiai.com/docs.html` |
| SDK 下载与仓库 | 见 README.md 的安装一节 |

全部接口只接受 HTTPS 与 WSS。单次 REST 请求在服务端最长处理 600 秒，其中评测排队最长 540 秒，入口读超时 660 秒。评测上传的请求体上限 50 MB，实时评测单轮音频上限 10 MB，单帧上限 128 KB。

## 2 鉴权

两种方式任选其一，全部接口通用。

### 2.1 Bearer Token

请求头 `Authorization: Bearer <jwt>`，实时评测握手在 query 里带 `token=<jwt>`。token 由登录接口签发，适合控制台与已登录用户。

### 2.2 签名

面向 API Key 接入方，服务端与 SDK 推荐用这一种。

| 请求头 | 含义 |
|---|---|
| `X-App-Key` | appKey |
| `X-Timestamp` | 当前 Unix 时间戳，单位为秒 |
| `X-Nonce` | 随机串，服务端 300 秒内去重防重放，建议每次都带 |
| `X-Signature` | 签名值 |

签名算法与服务端 `SignatureUtil.signHmacSha256` 一致：

1. 取本次请求的业务参数，不含请求头，不含文件二进制。
2. 丢弃值为 null 或空字符串的项。
3. 按参数名的字典序升序排列。
4. 拼成 `k1=v1&k2=v2`，不做 URL 编码，末尾不追加 secret。
5. `X-Signature = Base64(HMAC_SHA256(payload, secretKey))`，payload 与 secretKey 都按 UTF-8 编码。
6. 服务端校验 `|当前秒 - X-Timestamp| ≤ 300`，超出即拒绝。

### 2.3 各接口的被签名参数

| 接口 | 被签名参数 |
|---|---|
| 原生整段评测 `POST /api/v1/evaluate` | `config` 段不带文件名时，该段是一个表单参数，被签名参数为 `{config: config 段的 JSON 原文}` 加其余文本段。SDK 2.0 一律用这种方式。 |
| 声通兼容整段评测 `POST /{coreType}` | 实际发送的全部文本表单字段，音频不参与 |
| 语音合成 `POST /api/v1/tts/generate` | 请求体 JSON 的顶层非空标量字段，值取 JSON 里的字面文本，数值只写普通十进制，不写指数形式 |
| 报告查询 `GET /api/v1/report/{recordId}` | 空集合，recordId 是路径的一段，不参与 |
| 实时评测握手 | 除 `signature` 外的全部 query 参数，含 appKey，timestamp，nonce，idempotencyKey |

服务端对原生整段评测同时接受另一种口径：表单参数去掉 `config` 后，加上 config JSON 的顶层非空标量字段。浏览器 `FormData` 发送的 `config` 段带文件名 `blob`，只能按这种口径签名。

跨端一致性测试向量在 `spec/fixtures/sign/vectors.json`，其中契约向量为：

```
secret  = test_secret_key_123
params  = {coreType: sent.eval.cn, language: zh-CN, refText: 北京你好}
payload = coreType=sent.eval.cn&language=zh-CN&refText=北京你好
expected = A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=
```

## 3 整段评测

### 3.1 原生接口

`POST /api/v1/evaluate`，`Content-Type: multipart/form-data`。

| 段 | 类型 | 说明 |
|---|---|---|
| `audio` | 文件 | wav，mp3，m4a，webm，ogg 等常见格式，建议 16 kHz，16 位，单声道 |
| `image` | 文件，可选 | 只在 coreType 为 open，taskType 为 picture 的看图说话时上传 |
| `config` | 文本段，`Content-Type: application/json` | EvaluateConfigDTO，字段见下表。缺少 `application/json` 类型时服务端回 415 |

| 字段 | 类型 | 必填 | 取值与默认 |
|---|---|---|---|
| `coreType` | string | 是 | `word`，`sentence`，`passage`，`connected`，`open`，`alpha`，`pinyin` |
| `referenceText` | string | 是 | 参考文本，open 时为题目，最长 1000 字 |
| `language` | string | 否 | `en-US` 默认，`en-GB`，`zh-CN`。中文评测要显式传 `zh-CN` |
| `includeReport` | bool | 否 | 返回字与音素级详报，默认 false |
| `includeStandardAudio` | bool | 否 | 返回标准示范音地址，默认 false |
| `includeAsrText` | bool | 否 | 返回识别文本，默认 false |
| `slack` | double | 否 | 松紧度，取值 -1 到 1，默认 0 |
| `scale` | int | 否 | 分制，取值 1 到 100，默认 100 |
| `precision` | double | 否 | 精度，取值大于 0 到 1，默认 1 |
| `agegroup` | int | 否 | 1 学前，2 小学，3 中学及以上，默认 3 |
| `toneWeight` | double | 否 | 保留参数，取值 0 到 1，不改变总分 |
| `refPinyin` | string | 否 | 拼音，多音字与 pinyin 题使用，例如 `chong2 qing4` |
| `phonemeOutput` | bool | 否 | 音素级输出 |
| `taskType` | string | 否 | open 题型：`picture`，`situational`，`free` |
| `paragraphNeedWordScore` | int | 否 | passage 是否返回逐字详分，1 或 0 |

成功时直接返回结果对象，不包信封：

```json
{
  "recordId": "eval_xxx", "eof": 1,
  "result": {"overall": 85, "pronunciation": 88, "tone": 78, "fluency": 90, "rhythm": 82,
             "integrity": 100, "speed": 135, "rear_tone": "fall", "duration": "4.10", "warning": [],
             "words": [{"word": "北", "pinyin": "bei", "tone": "tone3",
                        "scores": {"overall": 100, "pronunciation": 100, "tone": 100},
                        "span": {"start": 120, "end": 280},
                        "phonemes": [{"phoneme": "B", "pronunciation": 100, "span": {"start": 120, "end": 150}}]}],
             "sentences": [{"sentence": "北京你好", "index": 0, "scores": {"overall": 85}}]},
  "report": {"summary": "...", "dimensions": {}, "suggestions": ["..."]},
  "asrText": {"text": "北京你好", "alignment": [{"char": "北", "read_status": "correct"}]},
  "standardAudio": {"url": "/audio/standard/xxx.wav", "format": "wav", "duration": "3.50"},
  "warnings": [1002]
}
```

时间单位为 10 毫秒。字段 `read_status` 取值 correct，mispronounced，skipped，inserted。各评测模式的取分字段逐项列在 `spec/openapi.yaml` 与各端 README 的结果模型一节，报告结构化字段沿用 `1.x` 契约第 7 节，含 `dimensionScores`，`dimensionEvidence`，`asrArbitration`，`rubricBackfilled`，开放题另有 `warning`，`openTaskAudit`，`rubricVersion`。

### 3.2 声通兼容接口

`POST /{coreType}`，路径取声通命名，已注册 `word.eval`，`word.eval.pro`，`sent.eval`，`sent.eval.pro`，`para.eval`，`alpha.eval`，`word.eval.cn`，`sent.eval.cn`，`para.eval.cn`，`pinyin`。`multipart/form-data`，`audio` 为文件段，业务参数为文本段，使用声通参数名：`refText`，`language`，`refPinyin`，`agegroup`，`scale`，`precision`，`slack`，`paragraph_need_word_score`，`phoneme_output`，`attachAudioUrl`，`dict_type`，`dict_dialect`，`customized_lexicon`，`customized_pron`，`output_rawtext`，`readtype_diagnosis` 等。另可带文本段 `request`，内容为 JSON，其顶层标量字段并入业务参数。兼容接口必须带 `X-App-Key`。

成功时返回声通风格结果 `{"recordId": ..., "eof": 1, "result": {...}}`，`result` 内的字段与 3.1 节一致，段落题按 `paragraph_need_word_score` 决定是否带逐词详情。请求带 `attachAudioUrl=1` 时顶层另有 `audioUrl`，为录音下载地址，保留 7 天，同一个幂等键重放时地址不变。

## 4 语音合成与报告

### 4.1 语音合成

`POST /api/v1/tts/generate`，`Content-Type: application/json`：

```json
{"text": "你好世界", "language": "zh-CN", "voice": "xiaoyan", "format": "mp3", "speed": 50, "pitch": 50, "volume": 50, "style": null}
```

voice 英文取 `female` 或 `male`，中文取 `xiaoyan` 女声或 `xiaofeng` 男声。format 取 `mp3` 默认，`wav`，`ogg`。speed，pitch，volume 取值 0 到 100，默认 50。响应为 `{"code": 0, "message": "success", "data": {"audioUrl": "...", "duration": "1.348", "format": "mp3"}}`，audioUrl 以 `/audio/` 开头时按基址拼成 `https://open.shengzhiai.com/tts/audio/...`。

### 4.2 报告查询

`GET /api/v1/report/{recordId}`，响应 `{"code": 0, "data": {...}}`，只能查询本账号的记录。

## 5 实时评测

### 5.1 原生实时评测

`wss://open.shengzhiai.com/api/v1/ws/evaluate`，握手 query 带鉴权参数。

1. 服务端发 `{"event": "connected"}`。
2. 客户端发开始帧 `{"cmd": "start", "coreType": "sentence", "referenceText": "今天天气很好", "language": "zh-CN", "idempotencyKey": "..."}`，开始帧接受 EvaluateConfigDTO 的全部字段。服务端回 `{"event": "started"}`。
3. 客户端发音频：二进制帧，推荐 640 字节一帧即 16 kHz 16 位单声道 20 毫秒，或文本帧 `{"cmd": "audio", "data": "<base64>"}`。
4. 客户端发 `{"cmd": "end"}`，服务端回 `{"event": "result", "recordId": ..., "eof": 1, "result": {...}, "report": {...}, "asrText": {...}, "warnings": [...]}`。同一幂等键的重放结果带 `"replayed": true`。

一轮结束后要再发开始帧才能开始下一轮。错误帧为 `{"event": "error", "code": 40001, "message": "..."}`，`code` 在原因是服务端业务错误时出现。

### 5.2 声通兼容实时评测

`wss://open.shengzhiai.com/{coreType}`，coreType 同 3.2 节。

1. 服务端发 `{"event": "connected", "coreType": "sent.eval.cn"}`。
2. 客户端发参数帧 `{"refText": "北京你好", "language": "zh-CN", "realtime_feedback": true, "idempotencyKey": "..."}`，也接受 `text` 代替 `refText`。服务端回 `{"event": "started", "coreType": "sent.eval.cn"}`。参数帧会清空这一轮已收的音频，一轮只发一次。
3. 客户端发音频，规则同 5.1 节。`realtime_feedback` 为 true 时服务端每收约 0.5 秒音频回一个进度帧 `{"eof": 0, "result": {"bytes": n}}`，进度帧不含评分。
4. 客户端发 `{"cmd": "end"}` 或 `{"end": true}`，服务端回 `{"recordId": ..., "eof": 1, "result": {...}}`，重放结果带 `"replayed": true`。

### 5.3 心跳

| 端 | 方式 |
|---|---|
| Java，安卓，iOS | 协议层 ping，每 15 秒一次，30 秒收不到 pong 视为断线 |
| 网页，小程序 | 应用层心跳，连接后与开始后每 15 秒发 `{"cmd": "ping"}`，服务端回 `{"event": "pong", "ts": <毫秒>}`，不改变会话状态 |

发出结束帧后服务端在评测，期间不再发应用层心跳，改由终评等待时长判断，默认 300 秒。

### 5.4 断线重连语义

服务端不支持会话续传，一个连接就是一个服务端会话。SDK 用新会话加整段重放实现等效续传：

1. 连接意外断开，心跳超时，连接超时，终评超时，或收到可重试的错误帧时，会话进入重连中，按退避等待后建立新连接。
2. 新连接上重发相同参数与同一个幂等键的开始帧，重放这一轮已发的全部音频，补发断线期间排队的音频，结束帧已发出时一并重发。
3. 服务端按幂等键识别重发：首次评测已完成时直接重放首次结果，首次评测仍在进行时最多等 30 秒后重放，不重复评测，不重复计费。
4. 默认连续重连 8 次，等待依次为 0.5，1，2，4，4，4，4，4 秒，约 23 秒内网络恢复即可接上，次数可配置。重连次数用尽时会话以错误结束，错误码 90006。

音频缓冲策略由调用方选择：

| 策略 | 行为 |
|---|---|
| REPLAY，默认 | 缓冲这一轮的全部音频，上限 10 MB，重连后整段重放，评分覆盖全部音频 |
| DROP | 不缓冲，重连期间送入的音频丢弃，新会话只评重连之后的音频 |
| FAIL | 不重连，断线即以错误结束 |

会话状态与回调：

| 状态 | 含义 |
|---|---|
| IDLE | 未连接 |
| CONNECTING | 建立连接中 |
| CONNECTED | 已连接，服务端已发 connected |
| STARTED | 服务端已回 started，可以送音频 |
| ENDING | 结束帧已发出，等待终评 |
| RECONNECTING | 断线后等待重连 |
| COMPLETED | 已收到终评 |
| FAILED | 以错误结束 |
| CANCELLED | 调用方取消 |
| CLOSED | 连接已关闭，会话结束 |

每个会话只触发终评回调与错误回调中的一个，最多一次。调用方主动取消时两者都不触发。连接关闭回调总在最后。任一时刻查询会话状态得到的值，与最近一次状态变化回调给出的新状态一致。

## 6 幂等

### 6.1 幂等键

| 接口 | 幂等键的位置 |
|---|---|
| 原生整段评测，兼容整段评测，语音合成 | 请求头 `Idempotency-Key`，也认 `X-Idempotency-Key` |
| 原生实时评测 | 开始帧字段 `idempotencyKey`，开始帧没带时取握手 query 的 `idempotencyKey` |
| 兼容实时评测 | 参数帧字段 `idempotencyKey`，参数帧没带时取握手 query 的 `idempotencyKey` |

键长 1 到 200 个可见 ASCII 字符。SDK 每个逻辑请求自动生成 32 位小写十六进制随机串，调用方也可以指定，例如业务单号。重试与断线重连复用同一个键。不带键时行为与 `1.x` 版本完全一致。

### 6.2 服务端语义

| 情形 | 服务端行为 |
|---|---|
| 首次出现的键 | 正常评测与计费，结果保存 24 小时 |
| 同一个键，同一个请求，首次已成功 | 不再评测，不再计费，原样返回首次结果，REST 响应头带 `Idempotency-Replayed: true`，实时评测结果帧带 `"replayed": true` |
| 同一个键，同一个请求，首次仍在处理 | 最多等 30 秒，等到即重放首次结果，等不到回 HTTP 409，错误码 40901，响应头 `Retry-After: 2` |
| 同一个键，不同的请求 | 回 HTTP 409，错误码 40903，不评测 |
| 首次请求失败 | 释放这个键，用同一个键重试会重新评测 |

请求是否相同按请求指纹判断：整段评测为 config 加音频加图片，兼容接口为 coreType 加全部文本表单字段加音频，语音合成为请求体，实时评测为评测参数加这一轮的音频。键按租户隔离，签名鉴权按 appKey，token 鉴权按用户，不同租户用同一个键互不影响。服务端记录处理中状态最长 15 分钟，进程异常退出后键在 15 分钟内自动释放。

## 7 重试

SDK 只对带幂等键或天然幂等的请求重试。默认最多重试 2 次即一共 3 次尝试，第 n 次重试前等待 `min(4000, 200 × 2^(n-1))` 毫秒，再乘以 0.7 到 1.3 之间的随机系数。响应带 `Retry-After` 时等待时间取两者较大值，上限 30 秒。一次逻辑调用的总时长上限默认 300 秒，重试与等待都计入，到时即以最后一次的错误结束。

可重试的情形：连接失败，连接被重置，连接或读取超时，HTTP 408，425，429，500，502，503，504，以及错误码表里标为可重试的错误码，例如 40901，42900，42901，50000，50200，3001 到 3003。参数错误，鉴权失败，权限不足，额度不足与其余 4xx 不重试。每次重试都通过事件回调与日志告知调用方。

## 8 错误码与警告码

完整错误码表见 `ERRORS.md`，由 `spec/errors.json` 生成，五端 SDK 内置同一份表。

| 来源 | 形态 |
|---|---|
| 平台信封 | `{"code": 40001, "message": "...", "timestamp": ...}`，HTTP 状态见错误码表 |
| 评测引擎 | `{"detail": "..."}` 或 `{"detail": [...]}` |
| 实时评测错误帧 | `{"event": "error", "code": 40001, "message": "..."}` |
| 音频质量警告 | 评测结果的 `result.warning` 为 `[{"code": 1002, "message": "Audio volume too low!"}]`，原生接口另有顶层 `warnings: [1002]` |

SDK 把错误归为 NETWORK，TIMEOUT，AUTH，PERMISSION，INVALID_PARAM，NOT_FOUND，CONFLICT，RATE_LIMIT，QUOTA，SERVER，UPSTREAM，AUDIO，STATE，CANCELLED，PROTOCOL，UNKNOWN 十六类，每类对应一个异常类型，异常保留原始错误码，HTTP 状态，幂等键与尝试次数。警告码 1001 到 1005 与 1009 随评测结果返回，不作为异常抛出。

## 9 音频要求与预检

| 项 | 要求 |
|---|---|
| 推荐格式 | WAV，PCM 16 位，16 kHz，单声道 |
| 时长 | 不短于 1 秒，短于 1 秒服务端回 40001 |
| 大小 | REST 上传不超过 50 MB，实时评测单轮不超过 10 MB |

SDK 在上传前对 WAV 与 PCM 做预检，模式 OFF，WARN，REJECT，默认 WARN。检查项为时长短于 1 秒，时长超过 300 秒，整段上传大于 50 MB 或实时评测一轮大于 10 MB，全程静音，音量过低，非 16 位 PCM 或采样率低于 16000，错误码 90101 到 90105。REJECT 模式下前三项与格式问题在上传前即报错，不发请求，不计费。

## 10 声通平替层

安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个平替层的接口与声通 SDK 一致，内部走 3.2 节的兼容整段评测。回调里的结果 JSON 在兼容接口返回的基础上补齐声通外层字段：

```json
{"tokenId": "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c", "recordId": "eval_xxx", "applicationId": "<appKey>",
 "userId": "<userId>", "refText": "北京你好", "eof": 1, "dtLastResponse": "2026-10-08 12:00:00:123",
 "result": {"overall": 85}}
```

请求带 `attachAudioUrl=1` 时，兼容接口的返回体顶层另有 `audioUrl`，为本次录音的下载地址，形如 `https://open.shengzhiai.com/rec/<yyyyMMdd>/<文件名>`，保留 7 天，平替层把该地址原样放进回调 JSON。错误时回调 `{"tokenId": "...", "errId": 20009, "error": "...", "eof": 1, "applicationId": "..."}`。可重试的失败在重试用尽后统一给 errId 20009，其余服务端错误把平台错误码作为 errId，平替层本地错误为 60001 到 60009，见 `ERRORS.md`。接入方式见 `SHENGTONG-MIGRATION.md`。

## 11 沙箱

沙箱密钥与生产使用同一个基址与同一套引擎，每把密钥每天 200 次，北京时间零点重置，超出回 HTTP 429，错误码 42903，沙箱调用照常写调用记录，不扣套餐与余额。详见 `SANDBOX.md`。

## 12 版本与变更

版本兼容矩阵见 `COMPATIBILITY.md`，变更记录见 `CHANGELOG.md`。接口出现不兼容变化时契约与 SDK 一起升主版本，同时在变更记录的破坏性变更一栏列出迁移方法。
