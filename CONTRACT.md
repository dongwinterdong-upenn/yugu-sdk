# 优谷雅言语音评测接口契约

契约版本 `2.1`，登记日期 2026-10-12，适用接口版本 v1 与 SDK `2.1.0`。五端 SDK 与服务端按本契约对齐，字段，签名算法，协议帧均取自线上服务端代码。`1.x` 版本契约的全部接口与字段继续有效，`2.0` 新增幂等，重试，实时评测心跳与断线重连语义，错误码分类，沙箱与声通平替层。`2.1` 新增实时评测朗读进度，见 5.5 节。

## 1 基址与连接

| 用途 | 地址 |
|---|---|
| REST 基址 | `https://open.shengzhiai.com` |
| WebSocket 基址 | `wss://open.shengzhiai.com` |
| 平台接口文档 | `https://open.shengzhiai.com/docs.html` |
| SDK 下载与仓库 | 见 README.md 的安装一节 |

全部接口只接受 HTTPS 与 WSS。单次 REST 请求在服务端最长处理 600 秒，其中评测排队最长 540 秒，入口读超时 660 秒。评测上传的请求体上限 50 MB，实时评测单轮音频上限 10 MB，单帧上限 128 KB。

## 2 鉴权

两种方式任选其一，声通兼容整段评测 `POST /{coreType}` 只接受签名，其余接口两种方式通用。

### 2.1 Bearer Token

请求头 `Authorization: Bearer <jwt>`，实时评测握手在 query 里带 `token=<jwt>`。token 由登录接口签发，适合控制台与已登录用户。

### 2.2 签名

面向持有 appKey 与 secretKey 的接入方服务端，各端 SDK 联调阶段也可以使用。

| 请求头 | 含义 |
|---|---|
| `X-App-Key` | appKey |
| `X-Timestamp` | 当前 Unix 时间戳，单位为秒 |
| `X-Nonce` | 随机串，服务端 300 秒内去重防重放，建议每次都带 |
| `X-Signature` | 签名值 |

签名算法：

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
| `config` | 文本段，`Content-Type: application/json` | 评测配置，字段见下表。缺少 `application/json` 类型时服务端回 415 |

| 字段 | 类型 | 必填 | 取值与默认 |
|---|---|---|---|
| `coreType` | string | 是 | `word`，`sentence`，`passage`，`connected`，`open`，`alpha`，`pinyin` |
| `referenceText` | string | 是 | 参考文本，open 时为题目，最长 1000 字 |
| `language` | string | 否 | `en-US` 默认，`en-GB`，`zh-CN`。中文评测要显式传 `zh-CN` |
| `includeReport` | bool | 否 | 生成 AI 报告，`report` 另含整体点评 `summary` 与改进建议 `suggestions`，默认 false |
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

成功时返回声通风格结果 `{"recordId": ..., "eof": 1, "result": {...}}`，`result` 内的字段与 3.1 节一致，段落题按 `paragraph_need_word_score` 决定是否带逐词详情。请求带 `attachAudioUrl=1` 时顶层另有 `audioUrl`，为录音下载地址，保留 7 天，同一个幂等键重放时地址不变。兼容接口不返回示范音，服务端也不合成。固定内容题型的 `words` 只含参考文本里的字词，请求带 `readtype_diagnosis=1` 时另含增读与重复读，`readType` 分别为 1 与 3。

### 3.3 分段耗时

兼容接口的 REST 响应头带 `Server-Timing: total;dur=5321, engine;dur=4100, platform;dur=1221`，单位毫秒。`total` 从服务端开始处理已收到的请求算起，到结果备好为止，`engine` 是评测引擎这一段，`platform` 是其余部分。

请求带 `timing=1` 时，结果顶层另有 `timing` 对象，REST 用文本段，实时评测写在参数帧里，见 5.2 节：

```json
"timing": {"receivedAt": 1791800000123, "totalMs": 5321, "engineMs": 4100, "platformMs": 1221,
           "engineStages": {"text_frontend_ms": 1909.3, "asr_ms": 595.0}, "audioBytes": 528000,
           "wsFirstAudioAt": 1791799990000}
```

- `receivedAt` 是服务端开始处理的时刻，毫秒时间戳。REST 为请求体收完时，实时评测为收到结束帧时。
- `engineStages` 是引擎各环节的耗时，键名随引擎版本变化，可能缺省。
- `audioBytes` 是服务端收到的音频字节数。
- `wsFirstAudioAt` 只在实时评测出现，是这一轮第一个音频帧到达服务端的时刻。
- 不带 `timing=1` 时结果不变。错误帧不带 `timing`，重放结果带首次评测的 `timing`。客户端应忽略不认识的键。

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
2. 客户端发开始帧 `{"cmd": "start", "coreType": "sentence", "referenceText": "今天天气很好", "language": "zh-CN", "idempotencyKey": "..."}`，开始帧接受原生整段评测 `config` 的全部字段，另可带 `"realtimeFeedback": true` 打开朗读进度。服务端回 `{"event": "started"}`。
3. 客户端发音频：二进制帧，推荐 640 字节一帧即 16 kHz 16 位单声道 20 毫秒，或文本帧 `{"cmd": "audio", "data": "<base64>"}`。开始帧带 `realtimeFeedback` 时，服务端在收音过程中下发朗读进度帧，见 5.5 节。
4. 客户端发 `{"cmd": "end"}`，服务端回 `{"event": "result", "recordId": ..., "eof": 1, "result": {...}, "report": {...}, "asrText": {...}, "warnings": [...]}`。同一幂等键的重放结果带 `"replayed": true`。

一轮结束后要再发开始帧才能开始下一轮。错误帧为 `{"event": "error", "code": 40001, "message": "..."}`，`code` 在原因是服务端业务错误时出现。

### 5.2 声通兼容实时评测

`wss://open.shengzhiai.com/{coreType}`，coreType 同 3.2 节。

1. 服务端发 `{"event": "connected", "coreType": "sent.eval.cn"}`。
2. 客户端发参数帧 `{"refText": "北京你好", "language": "zh-CN", "realtime_feedback": true, "idempotencyKey": "..."}`，也接受 `text` 代替 `refText`。不要朗读进度时不带 `realtime_feedback`。服务端回 `{"event": "started", "coreType": "sent.eval.cn"}`。参数帧会清空这一轮已收的音频，一轮只发一次。
3. 客户端发音频，规则同 5.1 节。`realtime_feedback` 为 true 时服务端在收音过程中下发朗读进度帧，见 5.5 节。
4. 客户端发 `{"cmd": "end"}` 或 `{"end": true}`，服务端回 `{"recordId": ..., "eof": 1, "result": {...}}`，重放结果带 `"replayed": true`。

参数帧带 `"timing": 1` 时终评帧另有 `timing` 对象，见 3.3 节。握手可协商 `permessage-deflate`，服务端下发的帧随之压缩，段落题终评帧可小到原来的十分之一左右。

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

### 5.5 朗读进度

原生开始帧带 `"realtimeFeedback": true`，或兼容参数帧带 `"realtime_feedback": true` 时，服务端在收音过程中下发进度帧。

兼容通道的进度帧沿用声通 `realtime_feedback` 中间结果的结构：

```json
{"eof": 0, "recordId": "", "result": {"kernel_version": "1.0.0", "resource_version": "1.0.0",
 "words": [{"charType": 0, "scores": {"pronunciation": 100, "skipped": 0, "tone": 100, "overall": 100, "overall_pron": 100},
            "word": "华", "word_parts": [{"beginIndex": 0, "charType": 0, "endIndex": 0, "part": "华"}]}],
 "bytes": 96000, "readUnits": 19, "matchedUnits": 18, "totalUnits": 25,
 "progress": [{"word": "我", "read": true, "matched": true}, {"word": "民", "read": false, "matched": false}]}}
```

原生通道的进度帧：

```json
{"eof": 0, "result": {"bytes": 96000, "readUnits": 19, "matchedUnits": 18, "totalUnits": 25,
 "progress": [{"word": "我", "read": true, "matched": true}, {"word": "民", "read": false, "matched": false}]}}
```

| 字段 | 通道 | 说明 |
|---|---|---|
| `recordId` | 兼容 | 空串，评测记录编号在终评中给出 |
| `result.kernel_version`，`result.resource_version` | 兼容 | 与终评相同 |
| `result.words` | 兼容 | 本帧新读到的字或词，按参考文本顺序，覆盖第 `readUnits - words.length` 到第 `readUnits - 1` 个单位，没有新读到的字时为空数组。每项结构同终评的 `words`，分数只表示识别结果与参考文本是否一致，详见下文 |
| `result.bytes` | 两条 | 这一轮服务端已收到的音频字节数 |
| `result.progress` | 两条 | 参考文本按原文顺序逐字或逐词列出的朗读状态。中文按字，去掉标点与空白。英文按空白分词，去掉词首词尾的标点，词内的撇号与连字符保留 |
| `result.progress[].word` | 两条 | 字或词，取自参考文本 |
| `result.progress[].read` | 两条 | 朗读位置已越过这个单位 |
| `result.progress[].matched` | 两条 | 识别结果与这个单位一致 |
| `result.readUnits` | 两条 | `read` 为 true 的单位数，即当前朗读位置 |
| `result.matchedUnits` | 两条 | `matched` 为 true 的单位数 |
| `result.totalUnits` | 两条 | 单位总数，等于 `progress` 的长度 |

- 一轮之内 `read` 与 `matched` 只会由 false 变为 true，不会退回。`matched` 为 true 时 `read` 也为 true。`read` 为 true 而 `matched` 为 false，表示朗读已越过这个位置但识别结果与参考文本不一致，可能是读错，漏读或识别误差，以终评为准。
- 兼容通道的 `words` 是增量：每个单位只在朗读位置刚越过这个单位的那一帧出现一次，把各帧的 `words` 依次拼起来就是已读的单位。识别结果一致的单位各项分数为 100，`skipped` 为 0，不一致的各项分数为 0，`skipped` 为 1，英文的 `tone` 为 0。下发之后不再更新，发音与声调的实际得分以终评为准。界面跟随朗读位置时，可以累加 `words` 的个数，也可以直接取 `readUnits`。
- 进度变化时立即下发一帧，此外每收约 0.5 秒音频下发一帧。进度一般比朗读晚 1 到 2 秒，随网络情况变化。结束帧发出后，服务端对最后一段音频再识别一次，结果以进度帧在终评之前送达，终评帧是这一轮的最后一帧，之后没有进度帧。
- 进度帧不含发音评分。终评与不打开朗读进度时相同，不额外计费。
- 按字还是按词由语言决定：原生开始帧的 `language` 以 zh 开头，或兼容 coreType 以 `.cn` 结尾或为 `pinyin` 时按字，其余按词。兼容参数帧带 `language` 时按 `language` 判断。
- 进度只对 16 kHz 16 位单声道 PCM 生效，一轮开头带 WAV 头的同样生效。其他封装格式的音频照常出终评，进度帧只有 `bytes`。
- 进度字段只在这一轮能跟踪进度时出现。参考文本为空，音频不是 PCM，或服务端当时无法跟踪时，进度帧为 `{"eof": 0, "result": {"bytes": n}}`，终评不受影响。一旦出现，这一轮之后的进度帧都会带上，内容为最新状态。
- 断线重连后，服务端在新会话上重新计算进度，重放音频期间进度从零追上来，兼容通道的 `words` 也从第一个单位重新下发。SDK 只回调 `readUnits` 与 `matchedUnits` 都不小于上一次回调的进度帧，音频缓冲策略为 DROP 时每次重连成功后从零重新计算。声通平替层按位置去掉已经回调过的单位。

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

可重试的情形：连接失败，连接被重置，连接或读取超时，HTTP 408，425，429，500，502，503，504，以及错误码表里标为可重试的错误码，例如 40901，42900，42901，50000，50200，3001 到 3003。参数错误，鉴权失败，权限不足，额度不足与其余 4xx 不重试。每次重试都通过指标回调与日志告知调用方。

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

SDK 在上传前对 WAV 与 PCM 做预检，模式 OFF，WARN，REJECT，默认 WARN。检查项为时长短于 1 秒，时长超过 300 秒，整段上传大于 50 MB 或实时评测一轮大于 10 MB，全程静音，音量过低，非 16 位 PCM 或采样率低于 16000，错误码 90101 到 90105。REJECT 模式下 90101，90102，90103 与 90105 在上传前即报错，不发请求，不计费，90104 只警告。

## 10 声通平替层

安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个平替层的接口与声通 SDK 一致。`2.1.1` 起麦克风录音与送流默认走 5.2 节的兼容实时评测边录边传，结束帧之前连接失败时改用 3.2 节的兼容整段评测，音频文件评测走 3.2 节。日志级别为 INFO 或 DEBUG 时请求带 `timing=1`，回调前去掉 `timing`，交给应用的结果 JSON 与不带时相同。回调里的结果 JSON 在兼容接口返回的基础上补齐声通外层字段：

```json
{"tokenId": "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c", "recordId": "eval_xxx", "applicationId": "<appKey>",
 "userId": "<userId>", "refText": "北京你好", "eof": 1, "dtLastResponse": "2026-10-08 12:00:00:123",
 "result": {"overall": 85}}
```

请求带 `attachAudioUrl=1` 时，兼容接口的返回体顶层另有 `audioUrl`，为本次录音的下载地址，形如 `https://open.shengzhiai.com/rec/<yyyyMMdd>/<文件名>`，保留 7 天，平替层把该地址原样放进回调 JSON。错误时回调 `{"tokenId": "...", "errId": 20009, "error": "...", "eof": 1, "applicationId": "..."}`。可重试的失败在重试用尽后统一给 errId 20009，其余服务端错误把平台错误码作为 errId，平替层本地错误为 60001 到 60009 与 90005，90011，见 `ERRORS.md`。接入方式见 `SHENGTONG-MIGRATION.md`。

## 11 沙箱

沙箱密钥与生产使用同一个基址与同一套引擎，每把密钥每天 200 次，北京时间零点重置，超出回 HTTP 429，错误码 42903，沙箱调用照常写调用记录，不扣套餐与余额。详见 `SANDBOX.md`。

## 12 版本与变更

版本兼容矩阵见 `COMPATIBILITY.md`，变更记录见 `CHANGELOG.md`。接口出现不兼容变化时契约与 SDK 一起升主版本，同时在变更记录的破坏性变更一栏列出迁移方法。
