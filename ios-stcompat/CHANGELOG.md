# 变更记录

STKouyuEngine 声通 iOS 平替包的版本记录。版本号遵循语义化版本，包地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`。

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
