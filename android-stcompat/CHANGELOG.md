# 更新日志

`com.shengzhiai.yugu:stkouyu-compat` 的版本记录。格式参照 Keep a Changelog，版本号遵循语义化版本，破坏性变更只在主版本号升级时出现。

## 版本 `2.1.2` 2026-10-13

支持声通中文开放题 `open.eval.cn`。

### 新增

- `coreType` 为 `"open.eval.cn"` 时由平台的开放题评测打分，看内容，语言运用与表达三项。`refText` 必填，与声通相同，填一条或多条参考答案，多条用竖线隔开，也可以填题目，由评分模型判断是哪一种。是参考答案时作答与任意一条意思一致就算答对，短答不扣分。`qType` 为 5 时按看图说话评，6 时按情景问答评，其他取值按自由表达评。`keypoints` 与 `keywords` 作为采分点，两项合计最多 20 个。结果含原生开放题的字段与声通的 `pronunciation`，`fluency`，`integrity`，`coherence`，`speed`，说明见 README 的中文开放题一节。
- `open.eval.cn` 的录音同样边录边传。`realtime_feedback` 设为 1 时中间结果只有已收音频的字节数 `bytes`，没有朗读进度。

### 变更

- `getSDKVersion()` 返回 `yugu-stkouyu-compat/2.1.2 (api 1.0.62)`，请求头 `User-Agent` 为 `yugu-stkouyu-compat/2.1.2`。

### 与声通 SDK 的差异

- 兼容接口不收图片，看图说话按自由表达的细则评分。
- 录音最长 300 秒，与其他内核相同。

### 破坏性变更

- 无。其他内核的请求与结果不变，英文 `open.eval` 仍在本地回 errId 60003。

## 版本 `2.1.1` 2026-10-13

边录边传成为默认，最终结果压缩传输，日志给出每次评测的分段耗时。

### 新增

- 录音与 `setIsStream(true)` 的外部音频默认经平台兼容实时评测通道边录边传，与 `realtime_feedback` 的取值无关。停止录音后只需发出剩余的少量音频与结束帧，结果随后经同一通道返回。`realtime_feedback` 设为 1 时才有中间结果，其他取值时只回调最终结果，结果 JSON 与整段上传时相同，录音文件照常写入。
- `YuguCompat.setStreamUpload(false)` 改回停止录音后整段上传，与 `2.1.0` 相同，`YuguCompat.isStreamUpload()` 返回当前设置，`YuguCompat.reset()` 恢复边录边传。
- 实时评测通道请求 permessage-deflate 压缩，平台同意后下发的消息压缩传输，由平替层解压。解压后的大小同样受 8 MB 上限约束，超过上限的消息在解压过程中即被拒绝。兼容接口请求 gzip 压缩，同样由平替层解压。
- 日志级别为 INFO 时每次评测输出一行 `timing`，给出上传方式，音频时长，停止录音之后的上传，等待与下载时间，结果在网络上与解压后的字节数，以及平台给出的总耗时，评测引擎耗时与平台处理耗时。DEBUG 另外逐步输出 `stage` 行与评测引擎各环节的耗时。
- 日志级别为 INFO 或 DEBUG 时请求带 `timing=1`，平台附带的分段耗时只写入日志，回调前从结果 JSON 中删除。兼容接口响应头 `Server-Timing` 同样写入日志。

### 变更

- `getSDKVersion()` 返回 `yugu-stkouyu-compat/2.1.1 (api 1.0.62)`，请求头 `User-Agent` 为 `yugu-stkouyu-compat/2.1.1`。
- `EngineSetting` 的 `setSDKLogEnabled`，`setLogLevel` 与 `setEnableSaveLogCatToFile` 在 `initEngine` 之后设置也立即生效，`setSDKLogEnabled(false)` 恢复 WARN。默认级别仍为 WARN，`YuguCompat.setLogLevel` 仍然优先。
- 实时评测通道在结束帧之前失败而退回整段上传时，`timing` 行的上传方式记为 `ws>rest`，行末给出原因，原因同时写在 WARN 日志中。

### 与声通 SDK 的差异

- 边录边传需要设备能访问平台的 wss 地址，连不上时停止录音后整段上传。
- `SkEgn` 只在 `request` 带 `realtime_feedback` 1 时边写边传。

### 破坏性变更

- 无。录音的上传方式改为边录边传，回调顺序与结果 JSON 不变。

## 版本 `2.1.0` 2026-10-12

朗读进度：开启 `realtime_feedback` 后，学生朗读过程中 `onScore` 收到声通结构的中间结果。

### 新增

- `RecordSetting.setRealtime_feedback(1)` 时，录音与 `setIsStream(true)` 的外部音频经平台兼容实时评测通道边录边传，`OnRecorderListener.onScore` 先收到 `eof` 为 0 的中间结果，停止录音后收到一次 `eof` 为 1 的最终结果，最终结果与整段上传时相同。
- 中间结果的 `result.words` 为新读到的字或词，结构同声通 `realtime_feedback` 的中间结果，各项分数只表示识别结果与参考文本是否一致，回调之后不再更新。`result.progress` 给出参考文本每个字或词的 `read` 与 `matched`，另有 `readUnits`，`matchedUnits` 与 `totalUnits`。只在有新读到的字或词时回调，进度一般比朗读晚 1 到 2 秒。
- 实时评测通道在结束帧发出之前连不上或断开时，停止录音后照常整段上传，`tokenId` 不变，不重复计费。结束帧发出之后断开时，用同一个 `tokenId` 重新连接，重发参数帧，全部音频与结束帧，最多 2 次，已经回调过的字不再回调，平台只计费一次，仍失败时给 errId 20009。
- `SkEgn` 的 `request` 带 `realtime_feedback`，音频为 16000 Hz 单声道 16 位 PCM 时，中间结果同样经 `skegn_callback` 回调。
- AAR 内置 WebSocket 客户端，支持 TLS 与 SNI，仍然没有运行时依赖。

### 变更

- `getSDKVersion()` 返回 `yugu-stkouyu-compat/2.1.0 (api 1.0.62)`，请求头 `User-Agent` 为 `yugu-stkouyu-compat/2.1.0`。
- `realtime_feedback` 设为 1 的应用此前只收到最终结果，现在先收到 `eof` 为 0 的中间结果，与声通 SDK 的行为一致。其他取值的行为不变。

### 与声通 SDK 的差异

- 中间结果需要设备能访问平台的 wss 地址，连不上时只有最终结果。
- `OnRecordListener` 没有中间结果的回调，`existsAudioTrans` 没有中间结果。

### 破坏性变更

- 无。

## 版本 `2.0.0` 2026-10-08

声通安卓平替层的首个发布版本。

### 新增

- 声通 `17kouyu_1.0.0.jar` 的平替 AAR，`com.stkouyu` 包中全部公共与受保护的类，接口，方法，字段与常量与声通 jar 一致，常量取值不变，接入方替换依赖，删除 `.so` 文件后原有代码照常编译运行。
- 评测在优谷雅言云平台完成，录音结束后经兼容接口 `POST /{coreType}` 上传，`onScore` 收到的结果 JSON 包含 `tokenId`，`recordId`，`applicationId`，`userId`，`refText`，`eof`，`dtLastResponse` 与平台返回的 `result`。
- `setNeedAttachAudioUrlInResult(true)` 时发送 `attachAudioUrl=1`，结果 JSON 带平台返回的录音下载地址 `audioUrl`，地址保留 7 天，录音同时保存在本地。
- 结果结构对齐声通：段落内核总是请求逐字得分，`result.sentences[].details[]` 中缺少 `overall` 的条目补充从 `scores` 复制的 `overall` 与 `pronunciation`，其余内容与平台返回一致。
- 录音使用 `AudioRecord` 采集 16000 Hz 单声道 16 位 PCM，保存为 WAV 文件，支持暂停与继续，本地能量 VAD 给出 `vad_status` 与 `sound_intensity`，设置 `duration` 后按 `durationInterval` 回调 `onTick`，到时后自动停止。
- 外部音频写入 `feed`，已有文件评测 `existsAudioTrans`，回放 `playback` 与 `playWithPath`，麦克风预备 `activeMic` 与 `releaseMic`，状态查询 `getEngineStatus`。
- 可靠性：`tokenId` 作为 `Idempotency-Key`，网络重试与 `autoRetry` 重新提交复用同一个值，平台只计费一次。可重试错误自动重试 2 次，退避间隔约 200 毫秒与 400 毫秒，遵守 `Retry-After`。
- errId：20009 表示重试用尽的临时故障，60001 到 60009 表示本地检查发现的问题，其他服务端错误给出平台业务码。音频大于 50 MB，或 WAV 与 PCM 长于 300 秒时给 60009，录音到 300 秒自动停止。
- `SkEgn` 的同名接口以 Java 实现，引擎句柄为编号，结果经 `skegn_callback` 在主线程回调。
- `STRecorder` 与 `com.stkouyu.util` 中的工具类保留全部公共接口，由平替层的录音与文件实现支撑。
- `com.stkouyu.YuguCompat` 提供平台地址，日志级别，重试策略与超时的配置入口。
- 接口一致性核对工具 `tools/api-diff/run.sh`，同时运行 japicmp 与 javap 两种比对，接入方可以用自己手中的声通 jar 核对。
- 示例应用 `compat-demo`，只使用 `com.stkouyu` 接口。

### 与声通 SDK 的差异

- 只做云端评测，`ENGINE_NATIVE` 与 `ENGINE_MULTI` 按云端运行。
- 不带 MP3 编码器，录音一律为 WAV，默认文件名为 `<tokenId>.wav`，显式设置的 `recordName` 以 `.mp3` 结尾时沿用该文件名，`SimpleLame` 的方法返回 -1。
- 平台兼容通道没有中间结果，`realtime_feedback` 不发送。
- 不需要授权文件，`updateProvision` 与 `inquireProvision` 直接返回成功。
- 日志只写入本地文件，不上传。
- 字节码版本为 Java 8，声通 jar 为 Java 7。

### 破坏性变更

- 无。
