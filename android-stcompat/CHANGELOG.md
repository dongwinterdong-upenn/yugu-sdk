# 更新日志

`com.shengzhiai.yugu:stkouyu-compat` 的版本记录。格式参照 Keep a Changelog，版本号遵循语义化版本，破坏性变更只在主版本号升级时出现。

## 版本 `2.0.0` 2026-10-08

声通安卓平替层的首个发布版本。

### 新增

- 声通 `17kouyu_1.0.0.jar` 的平替 AAR，`com.stkouyu` 包中全部公共与受保护的类，接口，方法，字段与常量与声通 jar 一致，常量取值不变，接入方替换依赖，删除 `.so` 文件后原有代码照常编译运行。
- 评测在优谷雅言云平台完成，录音结束后经兼容接口 `POST /{coreType}` 上传，`onScore` 收到的结果 JSON 包含 `tokenId`，`recordId`，`applicationId`，`userId`，`refText`，`eof`，`dtLastResponse` 与平台返回的 `result`。
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
- 平台兼容接口目前不返回音频地址，`setNeedAttachAudioUrlInResult(true)` 暂不起作用，录音保存在本地。
- 平台兼容通道没有中间结果，`realtime_feedback` 不发送。
- 不需要授权文件，`updateProvision` 与 `inquireProvision` 直接返回成功。
- 日志只写入本地文件，不上传。
- 字节码版本为 Java 8，声通 jar 为 Java 7。

### 破坏性变更

- 无。
