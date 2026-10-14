# 变更记录

STKouyuEngine 声通 iOS 平替包的版本记录。版本号遵循语义化版本，包地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`。

## `2.3.0` 2026-10-14

### 新增

1. 流水线评测。`YuguCompat.maxPendingEvaluations` 默认 0，与 `2.2.0` 相同，一次只做一次评测。设为大于 0 的 n 之后，一题停录处理完毕即转到后台评测，下一题可以立即录音，后台等结果的评测最多 n 次。各题的结果按评测完成的先后回调到各自开始时传入的 block，代理收到全部结果，按结果 JSON 的 `tokenId` 对应到题目。每次评测的 tokenId，录音文件，网络连接，重试，超时，自动重评与整段评测回退都与一次只做一次评测时相同。取值限定在 0 到 16，每次开始评测时读取。说明与示例见 README 回调规则一节的流水线评测。
2. `YuguCompat.pendingEvaluations`，已停录或以 `audioPath` 开始，还没有回调结果的评测次数，可以用来显示后台还有几题在评测。
3. 打开流水线评测时，上一题还在录音，暂停，等待录音权限或等待 `stopEngine` 时开始评测回调 60008，后台等结果的评测多于 n 次时同样回调 60008，回调方式与 `2.2.0` 的引擎忙相同。`cancelEngine`，`deleteEngine` 与再次 `initEngine` 取消正在录音的一题与后台的全部评测，之后都不再回调。各题的朗读进度与一次只做一次评测时相同，回调到这一题的终评为止，平台在结束帧之后补发的句末进度同样送到这一题自己的 block，不会回调到其他题的 block。
4. 日志：开关取值变化时打印一行 info 日志，每次流水线评测开始时打印一行 info 日志，写明后台等结果的评测次数 `pending`，后台等结果的评测多于上限而被拒时打印一行 warn 日志。

### 变更

1. 评测，边录边传与两个客户端共用的网络会话把每个主机的并发连接数提高到 32，后台评测的上传与实时连接不再排队等待系统默认的 4 到 6 个连接。
2. 版本号 `2.3.0`。`YuguCompat.sdkVersion` 为 `2.3.0`，User-Agent 为 `yugu-ios-stcompat-sdk/2.3.0`，`STKouyuEngineVersionNumber` 为 2.3。
3. 调用 `cancelEngine`，`deleteEngine` 或再次 `initEngine` 时，已经评测完，正在排队等主线程回调的结果同样不再回调。此前恰在这一刻调用仍可能收到这次的结果，一次只做一次评测时也是如此，打开流水线评测后等结果的题多，这一刻更容易遇到。

### 已知限制

1. 流水线评测只在 `KYTestEngine` 上提供，C 接口 `skegn_*` 的规则不变。
2. 流水线评测的忙碌判断与日志一行在 C 核心，Linux 上经单元测试验证。Objective-C 层在 Linux 上通过了语法与类型检查，新增的 XCTest 用例在 macOS 与 iOS 模拟器上运行，验证情况见 README 的验证状态一节。

## `2.2.0` 2026-10-13

### 新增

1. 语音合成 `YuguTtsClient`，Swift 名为 `TtsClient`，调用 `POST /api/v1/tts/generate`，回调在主线程。请求只发送设置过的字段，结果含平台返回的 `audioUrl`，按平台地址补全的 `absoluteAudioUrl`，`duration`，`format` 与完整 JSON。每次调用一个幂等键，重试沿用同一个键。说明见 README 的语音合成 TtsClient 一节。
2. 报告查询 `YuguReportClient`，Swift 名为 `ReportClient`，按评测结果的 `recordId` 查询网站上展示的 AI 报告。结果含 `reportStatus`，`isFinal`，`summary`，`suggestions`，`overall`，`report`，`score`，`timing` 与 `data` 的完整 JSON。`waitForFinalMs` 大于 0 时，即时稿每 1000 毫秒再查一次，直到不再是即时稿或时间用完。之后的某次查询失败时返回最近一次查到的结果，打印一行 warn 日志，与安卓平替相同。说明见 README 的报告查询 ReportClient 一节。
3. 两个客户端默认使用已初始化引擎的 appKey，secretKey 与平台地址，也可以直接指定。失败时回调 `NSError`，`domain` 为 `YuguCompatErrorDomain`，`code` 为平台错误码，网络或服务端临时故障重试用尽后为 20009。
4. `YuguCompat.throwOnUnsupportedCoreType`，默认 NO。设为 YES 时不支持的评测内核在 `KYTestEngine` 的两个开始评测方法里抛出 `NSInvalidArgumentException`，不回调，C 接口 `skegn_start` 返回 -1，最近错误码为 60003。

### 变更

1. 不支持的评测内核的错误 JSON，`error` 由“coreType 不支持”改为“不支持的评测内核：”加上请求的内核，例如“不支持的评测内核：open.eval”，没有指定内核时为“不支持的评测内核：未指定”。错误 JSON 的其余字段不变。
2. 不支持的评测内核每次打印一行 warn 日志，写明内核与 errId 60003，C 接口此前不打印。C 接口的 `request.coreType` 只含空白时同样按未指定处理，此前按 `word.eval` 评测。
3. 分段耗时一行在 `tokenId` 之后增加 `recordId`，错误时为 `-`，用来与平台按记录保存的分段耗时对应。
4. 版本号 `2.2.0`。`YuguCompat.sdkVersion` 为 `2.2.0`，User-Agent 为 `yugu-ios-stcompat-sdk/2.2.0`，`STKouyuEngineVersionNumber` 为 2.2。

### 已知限制

1. 语音合成与报告查询的请求体，签名，重试判断，报告字段与等待终稿的判断在 C 核心，Linux 上经单元测试与平台模拟服务验证。Objective-C 层与 Swift 名在 Linux 上通过了语法与类型检查，macOS 与 iOS 模拟器上的构建与测试，以及连接真实平台的验证情况见 README 的验证状态一节。

## `2.1.2` 2026-10-13

### 新增

1. 支持声通中文开放题 `open.eval.cn`，用 `coreTypeNS` 指定，`KYTestType` 没有对应成员。平台按原生开放题评分，看内容，语言运用与表达三项。`refText` 必填，与声通相同，填一条或多条参考答案，多条用竖线隔开，也可以填题目，由评分模型判断是哪一种。是参考答案时作答与任意一条意思一致就算答对，短答不扣分。`qType` 为 `KYQType_LookAndSay` 时按看图说话评，为 `KYQType_SituationalReply` 时按情景问答评，其他取值按自由表达评。`keypoints` 与 `keywords` 作为采分点，两项合计最多 20 个。结果含原生开放题的字段与声通的 `pronunciation`，`fluency`，`integrity`，`coherence`，`speed`，说明见 README 的中文开放题一节。
2. `open.eval.cn` 的录音同样边录边传。`realtime_feedback` 为 YES 时中间结果只有已收音频的字节数 `bytes`，没有朗读进度。

### 变更

1. `YuguCompat.sdkVersion` 为 `2.1.2`，`User-Agent` 为 `yugu-ios-stcompat-sdk/2.1.2`。

### 与声通 SDK 的差异

1. 兼容接口不收图片，看图说话按自由表达的细则评分。
2. 录音最长 300 秒，与其他内核相同。

## `2.1.1` 2026-10-13

### 新增

1. 边录边传。录音模式与 16 kHz 16 位单声道 PCM 的 feed 模式默认在录音过程中经声通兼容实时评测接口上传音频，`stopEngine` 之后只需发出最后一小段音频与结束帧，不再等录音结束后整段上传。`YuguCompat.streamUpload` 默认 YES，设为 NO 时与 `2.1.0` 相同。结束帧发出之前连接失败时，`stopEngine` 之后用同一个 tokenId 整段上传评测，日志级别为 warn 时打印失败原因。
2. 分段耗时日志。日志级别为 info 或 debug 时，每次评测在结果回调开始之后打印一行 `timing`：上传方式，音频时长，结束录音到回调的总耗时，上传，等待，下载，解析与回调各段耗时，结果的字节数，以及平台侧的总耗时，引擎耗时与平台其余耗时。debug 级别另外打印各阶段时刻与评测引擎各环节耗时。字段与安卓平替层相同，说明见 README 的日志一节。
3. info 与 debug 级别下整段评测的请求带 `timing=1`，实时评测的参数帧带 `"timing":1`，平台在结果里附上平台侧耗时，平替包读取之后从结果中去掉，应用收到的结果 JSON 与 `2.1.0` 相同。整段评测另外读取响应头 `Server-Timing`。
4. 整段评测的分段耗时取自 NSURLSessionTaskMetrics，含请求体发完，收到第一个字节与收齐结果的时刻，以及结果压缩前后的字节数。
5. C 接口 `skegn_new` 的 cfg 里 `sdkLog.level` 也接受 `error`，`warn`，`info`，`debug` 这几个名称，取值为空或不是数字时按 warn 处理。

### 变更

1. 版本号 `2.1.1`。`YuguCompat.sdkVersion` 为 `2.1.1`，User-Agent 为 `yugu-ios-stcompat-sdk/2.1.1`，`STKouyuEngineVersionNumber` 仍为 2.1。
2. 不打开 `realtime_feedback` 的录音与 feed 评测也经实时评测接口上传，参数帧不带 `realtime_feedback`，平台不下发进度，结果回调只收到终评 JSON，与 `2.1.0` 的终评 JSON 相同。打开 `realtime_feedback` 时的回调与 `2.1.0` 相同。
3. 整段评测共用的 NSURLSession 设置了代理，只用于收集 NSURLSessionTaskMetrics，请求内容与回调不变。
4. warn 级别下的日志与 `2.1.0` 相同，评测成功时不打印。

### 已知限制

1. 边录边传需要 iOS 13，iOS 12 上照旧整段上传。`audioPath` 模式与其他格式的 feed 照旧整段上传。C 接口 `skegn_*` 照旧在 `skegn_stop` 之后整段上传。
2. 边录边传的终评帧由系统的 NSURLSessionWebSocketTask 自行协商 `permessage-deflate` 压缩，平替包不另写 WebSocket 实现。系统是否提出由 iOS 版本决定，协商成功时分段耗时日志带 `deflate`。系统不报告终评帧在网络上的字节数。
3. Objective-C 层的 `2.1.1` 改动在 Linux 上通过了语法与类型检查，macOS 与 iOS 模拟器上的构建与测试，以及真机上的边录边传，验证情况见 README 的验证状态一节。

## `2.1.0` 2026-10-12

### 新增

1. 朗读进度。`KYTestConfig.realtime_feedback` 设为 YES 时，录音与 feed 模式的评测改走声通兼容实时评测接口，边读边上传音频，结果回调在终评之前收到 `eof` 为 0 的进度 JSON。`result.words` 为新读到的字或词，结构同声通 `realtime_feedback` 的中间结果，`result.progress` 为参考文本全部字或词的朗读状态，`result.readUnits` 为当前朗读位置。进度里的分数只表示是否读对，发音与声调的得分以终评为准。
2. 结束帧发出之前连接失败时，`stopEngine` 之后用同一个 tokenId 整段评测。结束帧发出之后连接失败时用同一个 tokenId 重新连接，重放全部音频，次数与整段评测的重试次数相同，已回调的字或词不再重复回调，同一次评测只评测一次，只计费一次。
3. README 新增朗读进度一节，含按进度给参考文本着色的示例。

### 变更

1. 版本号 `2.1.0`。`YuguCompat.sdkVersion` 为 `2.1.0`，User-Agent 为 `yugu-ios-stcompat-sdk/2.1.0`，`STKouyuEngineVersionNumber` 为 2.1。
2. 打开 `realtime_feedback` 的评测在结果回调里先收到进度 JSON，再收到终评或错误 JSON。终评 JSON 与错误 JSON 的格式不变。

### 已知限制

1. 朗读进度需要 iOS 13，iOS 12 上照旧整段评测。只对 16 kHz 16 位单声道 PCM 生效，`audioPath` 模式与其他音频照旧整段评测。
2. C 接口 `skegn_*` 不回调朗读进度。
3. 实时评测在 macOS 与 iOS 模拟器上连接平台模拟服务验证，尚未在真机上验证边录边传，验证情况见 README 的验证状态一节。

## `2.0.0` 2026-10-08

首个版本。

### 新增

1. SwiftPM 包 `STKouyuEngine`，最低 iOS 12，替换声通 `STKouyuEngine.framework` 与 `libskegn.a`。
2. 公开头文件 `STKouyuEngine.h`，`KYTestEngine.h`，`KYStartEngineConfig.h`，`KYTestConfig.h` 与 `skegn.h` 的声明与声通公开头文件逐项一致，`#import <STKouyuEngine/KYTestEngine.h>`，`@import STKouyuEngine;` 与 Swift 的 `import STKouyuEngine` 写法不变。
3. 评测走优谷雅言云端兼容接口 `POST /{coreType}`，支持中文与英文的字词，句子，段落，字母与拼音题，其他内核在本机回调 errId 60003。
4. 录音为 16 kHz 单声道 16 位 WAV，支持 feed 音频流与已有音频文件评测，支持本机 VAD，音强，倒计时与系统打断后继续录音。
5. tokenId 作为幂等键，重试与自动重评共用，同一次评测只计费一次。可重试错误自动重试，一次提交最多 3 次请求，遵从 `Retry-After`。
6. 结果 JSON 与错误 JSON 的字段，errId 规则与安卓平替层一致。段落内核一律请求逐字得分，`result.sentences[].details[]` 的条目补上 `overall` 与 `pronunciation`，声通示例的读取写法可以直接使用。
7. 本机检查音频：短于 1 秒回调 60005，大于 50 MB 或长于 300 秒回调 60009，录音与 PCM 流到 300 秒自动结束。
8. C 接口 `skegn_*` 由本包实现，直接调用 C 接口的代码不用改。
9. 扩展配置头文件 `YuguCompat.h`，可设置平台地址，日志级别，日志接收方，重试次数与总时限。
10. 头文件比对工具 `tools/headers-diff.py`，接入方可以用自己的声通 framework 核对接口。
11. `attachAudioUrl` 为 YES 时，结果 JSON 末尾带平台返回的录音下载地址 `audioUrl`，平台保留 7 天，到期删除。

### 已知限制

1. 只有云端评测，离线引擎与双引擎参数照收，不生效。
2. 不返回实时中间评分，不带 MP3 编码器。
3. 录音，回放，系统打断与音频路由切换尚未在真机验证。Objective-C 层已在 macOS 构建机上用 Xcode 15.4 编译，XCTest 在 macOS 与 iOS 模拟器上通过，验证情况见 README 的验证状态一节。
