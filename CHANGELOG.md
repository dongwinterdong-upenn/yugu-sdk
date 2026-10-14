# 变更记录

格式参照 Keep a Changelog，版本号规则见 `COMPATIBILITY.md`。各端的详细变更见各端目录下的 CHANGELOG.md。

## 版本 `2.3.0`，2026-10-14

安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个声通平替发 `2.3.0`，其余各端版本不变。

### 新增
- 两个平替层新增连续评测：安卓 `YuguCompat.setMaxPendingEvaluations(n)`，iOS `YuguCompat.maxPendingEvaluations`，默认 0，与 `2.2.0` 相同。n 大于 0 时，上一题停止录音后即可开始下一题，最多 n 题同时在等结果，结果按完成先后回调到各题自己的监听器，用 `tokenId` 对应题目。另有 `pendingEvaluations` 返回还在等结果的题数。`cancel` 一类调用同时取消等结果的各题。
- 平台侧：AI 终稿分几路并行生成，中文与英文一般都在评测返回后约 4 秒就绪。英文题的中文版随后补进同一份报告。

### 变更
- 两个平替的版本号与 User-Agent 为 `2.3.0`。

## 版本 `2.2.0`，2026-10-13

安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个声通平替发 `2.2.0`。安卓，Java，网页，小程序四个原生包发 `2.1.1`，iOS 原生包仍为 `2.1.0`。

### 新增
- 两个平替层新增语音合成 `TtsClient` 与报告查询 `ReportClient`。安卓在 `com.shengzhiai.yugu.stcompat` 包里，iOS 在 `YuguCompat.h` 里，Swift 名为 `TtsClient` 与 `ReportClient`。默认用初始化引擎时的 appKey 与 secretKey，也可以单独指定。报告查询设 `waitForFinalMs` 时每秒再查一次，直到 AI 终稿就绪或时间用完。
- 两个平替层的 `YuguCompat` 新增开关，打开后不支持的内核在开始评测时直接抛异常，安卓为 `IllegalArgumentException`，iOS 为 `NSInvalidArgumentException`，默认关闭。
- 平台侧：声通兼容接口的评测记录记下所属账号，用同一个 appKey 可以按 `recordId` 查报告。报告查询新增 `reportStatus`，说明是不是 AI 终稿，声通兼容评测另带分段耗时 `timing`，保留 7 天，见 `CONTRACT.md` 4.2 节。

### 变更
- 不支持的内核：errId 60003 的 `error` 改为“不支持的评测内核：<内核>”，未指定内核时为“未指定”，每个入口都记一行警告日志。安卓 `skegn_start` 当场回调，之后 `skegn_feed` 与 `skegn_stop` 返回 -1，与 iOS 一致。iOS `skegn_start` 的内核只含空白时回 60003，此前按 `word.eval` 评测。
- 日志级别为 INFO 时，分段耗时一行在 `tokenId` 之后另有 `recordId`，可与报告查询返回的 `timing` 对照。
- 安卓，Java，网页，小程序四个原生包的兼容评测放开中文开放题 `open.eval.cn`，此前在本地就被挡下。`refText` 必填，不能用 `refPinyin` 代替。
- 平台侧：AI 终稿拿到逐字比对事实，多读与重复读不再被说成读错，完整度一段只有增读或重复时不再说漏读。即时稿的语速建议按文体标准区间判断，报告里不再出现语速数字区间。英文题的中文版与英文终稿同时生成，英文终稿约 11 秒就绪。
- 文档：结果字段新增“增读与重复读”一节，写明标识，计分，排序与时间区间。

### 兼容性
- 两个平替与 `2.1.2` 源码兼容，二进制兼容，替换版本号即可升级。交给应用的结果 JSON 不变，只有 60003 的 `error` 文案变了，按 `errId` 判断的代码不受影响。
- 四个原生包与 `2.1.0` 源码兼容，二进制兼容。

## 版本 `2.1.2`，2026-10-13

只发了安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个声通平替，其余五端仍为 `2.1.0`。

### 新增
- 声通兼容接口新增中文开放题 `open.eval.cn`，与原生 `coreType` 为 `open` 同一套评分。`refText` 与声通相同，可以是用竖线隔开的参考答案，也可以是题目，由评分模型判断，是参考答案时意思一致就算答对，短答不扣分。`qType` 5 与 6 分别按看图说话与情景问答评，其他按自由表达评，`keypoints` 与 `keywords` 为采分点。结果另有声通的 `pronunciation`，`fluency`，`integrity`，`coherence`，`speed`。两个平替层放开这个内核，实时评测不下发朗读进度。

## 版本 `2.1.1`，2026-10-13

只发了安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 两个声通平替，其余五端仍为 `2.1.0`。

### 变更
- 平台侧：声通兼容接口固定内容题型的 `words` 只含参考文本里的字词，增读与重复读在请求带 `readtype_diagnosis=1` 时才给，计分不变。
- 两个平替层默认边录边传：麦克风录音与送流走兼容实时评测，不再等录音结束才整段上传。安卓 `YuguCompat.setStreamUpload(false)`，iOS `YuguCompat.streamUpload = NO` 时恢复录完整段上传。`realtime_feedback` 只决定有没有朗读进度回调。
- 安卓平替层的实时连接协商 `permessage-deflate`，终评结果压缩传输。iOS 由系统网络库协商。
- 日志级别为 INFO 时每次评测记一行分段耗时，含上传，等待，下载，解析与服务端各环节耗时，DEBUG 时另记引擎各环节。安卓的日志设置在 `initEngine` 之后修改也立即生效。

### 新增
- 平台侧：兼容接口的 REST 响应头带 `Server-Timing`，请求带 `timing=1` 时结果顶层附 `timing`，格式见 `CONTRACT.md` 3.3 节。兼容接口不再合成示范音。

### 兼容性
- 与 `2.1.0` 源码兼容，二进制兼容，替换版本号即可升级。交给应用的结果 JSON 不变。

## 版本 `2.1.0`，2026-10-12

### 新增
- 实时评测朗读进度：原生实时评测的开始帧带 `realtimeFeedback`，声通兼容实时评测的参数帧带 `realtime_feedback` 时，平台边收音边下发进度帧，参考文本逐字或逐词给出 `read` 与 `matched`，另有 `readUnits`，`matchedUnits` 与 `totalUnits`，格式见 `CONTRACT.md` 5.5 节。
- 五端 SDK 的 `onPartial` 带朗读进度，原生实时评测新增 `realtimeFeedback` 选项，断线重连后回调的进度不倒退。
- 安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 平替层支持 `realtime_feedback`：打开后边录边传，录音过程中多次回调，`eof` 为 0 的结果是朗读进度，结束后回调 `eof` 为 1 的终评。iOS 平替层的这项能力需要 iOS 13。
- 平台侧：两条实时评测通道下发朗读进度帧，终评与计费不变。

### 变更
- 声通兼容实时评测的进度帧在 `bytes` 之外增加朗读进度字段，只读 `bytes` 的 `2.0.0` 客户端不受影响。

### 兼容性
- 与 `2.0.0` 源码兼容，二进制兼容，替换版本号即可升级。

## 版本 `2.0.0`，2026-10-08

### 新增
- 五端写操作自动带幂等键，重试与实时评测重连复用同一个键，平台按键只评测一次，只计费一次。
- 可配置的重试策略，默认重试 2 次，指数退避加随机抖动，尊重 Retry-After，只对可重试错误生效，重试事件可观测。
- 实时评测会话状态机，自动重连，REPLAY，DROP，FAIL 三种音频缓冲策略，心跳，终评超时，结构化事件回调。
- 十六类错误分类与对应异常类型，统一错误码表 `ERRORS.md`，公开的可重试判定函数。
- 客户端，会话，录音器的释放接口与状态查询。
- 日志级别与指标回调。
- 上传前音频预检。
- 网页与小程序 TypeScript 声明。
- 安卓 `com.stkouyu` 与 iOS `STKouyuEngine` 声通平替层，附接口比对工具。
- 自建 Maven 仓库，npm 源与 SwiftPM 仓库，持续集成与公开构建记录。
- 沙箱密钥，`SANDBOX.md`。
- OpenAPI 描述 `spec/openapi.yaml`，供其他服务端语言生成客户端。
- 平台侧：兼容整段评测，语音合成，两条实时评测接入幂等键，实时评测应用层心跳，错误帧带错误码，新错误码 40901，40902，40903，42903。
- 平台侧：兼容接口支持 `attachAudioUrl`，返回录音下载地址，保留 7 天。
- 平台侧：段落题参考文本只有一句时返回一个分句。

### 变更
- 包名与仓库坐标统一为 `com.shengzhiai.yugu` 与 `@shengzhiai`，默认基址改为 `https://open.shengzhiai.com`。
- 许可改为 Apache-2.0。
- 平台侧：额度不足的错误码由 40900 改为 40902，HTTP 状态仍为 409。
- 平台侧：原生实时评测以 appKey 签名握手时按次计费，评测记录归属密钥所属账号，按 recordId 可以查到报告，并发与额度限制与原生整段评测一致。

### 修复
- 小程序原生整段评测恒报 HTTP 415。
- 安卓只签部分 config 字段导致签名失败。
- iOS 整数值小数字段签名与发送不一致。

### 破坏性变更
- 包名与坐标变化，迁移见 `MIGRATION-2.0.md`。
- 网页 SDK 移除 `streamRealtime`。

## 版本 `1.0.0`，2026-06-24

### 新增
- 首个版本，五端整段评测，语音合成，报告查询，原生与声通兼容实时评测，签名与 token 两种鉴权。
