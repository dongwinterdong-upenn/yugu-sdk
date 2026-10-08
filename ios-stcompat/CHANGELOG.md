# 变更记录

STKouyuEngine 声通 iOS 平替包的版本记录。版本号遵循语义化版本，包地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`。

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
