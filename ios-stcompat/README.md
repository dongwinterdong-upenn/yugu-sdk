# STKouyuEngine 声通 iOS 平替包

STKouyuEngine 是优谷雅言语音评测 SDK 2.0 的 iOS 声通平替包。已经接入声通 `STKouyuEngine.framework` 的应用，用这个 SwiftPM 包替换声通的 framework 与 `libskegn.a`，换上优谷雅言的 appKey 与 secretKey，业务代码不改，评测改由优谷雅言云端完成。类名，属性，方法签名，枚举值，常量与代理协议都与声通公开头文件一致，回调里的结果 JSON 与错误 JSON 与安卓平替层一致。

## 版本与环境

| 项 | 值 |
|---|---|
| 包地址 | `https://open.shengzhiai.com/git/stkouyu-ios-compat.git` |
| 版本 | `2.0.0` |
| 产品与模块 | `STKouyuEngine`，Objective-C 实现 |
| 最低系统 | iOS 12，另支持 macOS 10.15 供测试使用 |
| 工具链 | Xcode 15 及更新版本，`swift-tools-version:5.9` |
| 依赖 | 只用系统框架 Foundation，AVFoundation，CoreGraphics 与 UIKit |
| 许可 | Apache-2.0 |

## 接入步骤

### 需要移除的声通文件

1. 在 Xcode 工程里删除 `STKouyuEngine.framework` 与 `libskegn.a`，同时删除 Build Phases 里对二者的链接与嵌入设置。
2. 用 CocoaPods 接入声通的工程，在 `Podfile` 里删掉 `pod '17kouyu-tt'` 一行，再执行 `pod install`。
3. 工程里自带的 `skegn.h` 副本可以保留，声明与本包相同。

### SwiftPM 依赖

在 Xcode 菜单 `File > Add Package Dependencies` 填入包地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，规则选 `Up to Next Major Version`，起始版本 `2.0.0`，把产品 `STKouyuEngine` 加到应用 target。

用 `Package.swift` 管理依赖的工程这样写：

```swift
dependencies: [
    .package(url: "https://open.shengzhiai.com/git/stkouyu-ios-compat.git", from: "2.0.0"),
],
targets: [
    .target(name: "App", dependencies: [
        .product(name: "STKouyuEngine", package: "stkouyu-ios-compat"),
    ]),
]
```

### 代码与密钥

导入写法不变：

```objc
#import <STKouyuEngine/STKouyuEngine.h>
#import <STKouyuEngine/KYTestEngine.h>
@import STKouyuEngine;
```

```swift
import STKouyuEngine
```

1. `KYStartEngineConfig` 的 `appKey` 与 `secretKey` 换成优谷雅言控制台的密钥。联调期间用沙箱密钥，说明见优谷雅言 SDK 仓库根目录的 `SANDBOX.md`。
2. `server` 留空或保留声通地址时，请求发往 `https://open.shengzhiai.com`。私有部署或联调环境写 `https://` 开头的完整地址。
3. 应用原有的 `NSMicrophoneUsageDescription` 继续生效，平替包不需要新增权限声明。

## 生命周期示例

Objective-C：

```objc
#import <STKouyuEngine/KYTestEngine.h>

@interface ReadingViewController () <KYTestEngineDelegate>
@end

@implementation ReadingViewController

- (void)setUpEngine {
    KYStartEngineConfig *config = [[KYStartEngineConfig alloc] init];
    config.appKey = @"优谷雅言 appKey";
    config.secretKey = @"优谷雅言 secretKey";
    config.vadEnable = YES;
    [KYTestEngine sharedInstance].delegate = self;
    [[KYTestEngine sharedInstance] initEngine:KY_CloudEngine
                            startEngineConfig:config
                                  finishBlock:^(BOOL isSuccess, NSString *str) {
        NSLog(@"init %d %@", isSuccess, str);
    }];
}

- (void)startReading {
    KYTestConfig *test = [[KYTestConfig alloc] init];
    test.coreType = KYTestType_Sentence_Cn;
    test.refText = @"今天天气很好";
    test.duration = 10000;
    NSString *tokenId = [[KYTestEngine sharedInstance] startEngineWithTestConfig:test result:^(NSString *testResult) {
        NSData *data = [testResult dataUsingEncoding:NSUTF8StringEncoding];
        NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
        if (json[@"errId"]) {
            NSLog(@"errId %@ %@", json[@"errId"], json[@"error"]);
        } else {
            NSLog(@"overall %@", json[@"result"][@"overall"]);
        }
    } finishBlock:^(BOOL isSuccess, NSString *str) {
        NSLog(@"start %d %@", isSuccess, str);
    }];
    NSLog(@"tokenId %@", tokenId);
}

- (void)stopReading {
    [[KYTestEngine sharedInstance] stopEngine];   // 结束录音并评测，结果回到 result block
}

- (void)leavePage {
    [[KYTestEngine sharedInstance] cancelEngine]; // 放弃本次评测，不再回调
}

- (void)shutDown {
    [[KYTestEngine sharedInstance] deleteEngine]; // 释放引擎，可重复调用，之后需要重新 initEngine
}

@end
```

Swift：

```swift
let engine = KYTestEngine.sharedInstance()!
let config = KYStartEngineConfig()
config.appKey = "优谷雅言 appKey"
config.secretKey = "优谷雅言 secretKey"
engine.initEngine(KY_CloudEngine, startEngineConfig: config) { ok, message in print(ok, message ?? "") }

let test = KYTestConfig()
test.coreTypeNS = "sent.eval"
test.refText = "How are you"
let tokenId = engine.start(with: test, result: { json in print(json ?? "") }) { ok, str in print(ok, str ?? "") }
engine.stop()
engine.delete()
```

Swift 里的方法名由编译器按头文件推导，与使用声通 framework 时相同。编译器去掉方法名里与类名 `KYTestEngine` 重复的 `Engine`，也去掉参数名末尾的 `Block`，常用方法的 Swift 写法如下：

| Objective-C | Swift |
|---|---|
| `initEngine:startEngineConfig:finishBlock:` | `initEngine(_:startEngineConfig:finish:)` |
| `startEngineWithTestConfig:result:finishBlock:` | `start(with:result:finish:)` |
| 带录音过程回调的 `startEngineWithTestConfig:` | `start(with:onStart:onStartFail:onPause:onTick:onRecording:onRecordEnd:onScoreBlock:finish:)` |
| `stopEngine`，`cancelEngine`，`deleteEngine` | `stop()`，`cancel()`，`delete()` |
| `getEngineStatus` | `getStatus()` |

`onScoreBlock` 保留 `Block`，是编译器的推导结果。上面的示例与全部方法的 Swift 写法都写在 `Tests/STKouyuEngineTests` 里，随测试一起编译。

## 评测内核

`coreTypeNS` 不为空时优先于 `coreType`。

| coreType | 对应的 KYTestType | 说明 |
|---|---|---|
| `word.eval` | `KYTestType_Word` | 英文单词 |
| `word.eval.pro` | `KYTestType_Word_Pro` | 英文单词，自适应年龄段 |
| `sent.eval` | `KYTestType_Sentence` | 英文句子 |
| `sent.eval.pro` | `KYTestType_Sentence_Pro` | 英文句子，自适应年龄段 |
| `para.eval` | `KYTestType_Paragraph` | 英文段落 |
| `word.eval.cn` | `KYTestType_Word_Cn` | 中文字词 |
| `sent.eval.cn` | `KYTestType_Sentence_Cn` | 中文句子 |
| `para.eval.cn` | `KYTestType_Paragraph_Cn` | 中文段落 |
| `alpha.eval` | 只能用 `coreTypeNS` | 英文字母 |
| `pinyin` | 只能用 `coreTypeNS` | 拼音题，`refPinyin` 必填 |

其余内核在本地回调 errId 60003，不发网络请求，包括 `open.eval`，`choice.rec`，`asr.rec`，`asr.eval`，`align.eval`，法语，日语与韩语内核，`KYTestType_Wordspell`，以及声通内核以外的名字。

`refText` 为空时回调 errId 60006。中文内核与 `pinyin` 设置了 `refPinyin` 时可以不设 `refText`。

## 参数映射

评测请求为 `POST {平台地址}/{coreType}`，`multipart/form-data`，文本字段不带文件名，音频为 `audio` 文件字段。签名覆盖全部非空文本字段。

| KYTestConfig 属性 | 表单字段 | 说明 |
|---|---|---|
| `coreTypeNS` 与 `coreType` | 请求路径 `/{coreType}` | 见评测内核 |
| `refText` | `refText` | 非空时发送 |
| `refPinyin` | `refPinyin` | 非空时发送 |
| `ageGroup` | `agegroup` | 设为 1，2 或 3 时发送 |
| `scale` | `scale` | 非 0 时发送，整数不带小数点 |
| `precision` | `precision` | 非 0 时发送 |
| `slack` | `slack` | 非 0 时发送 |
| `isParagraphNeedWordScore` | `paragraph_need_word_score` | `para.eval` 与 `para.eval.cn` 一律发送 1，`customParams` 也改不掉。其他内核 YES 时发送 1 |
| `phoneme_output` | `phoneme_output` | 默认 YES，YES 时发送 1 |
| `attachAudioUrl` | `attachAudioUrl` | YES 时发送 1，平台在结果 JSON 末尾返回录音的下载地址 `audioUrl`，地址保留 7 天，到期删除。录音同时保存在本机，路径用 `getLastRecordPath` 获取 |
| `phonemeOption` | `dict_type` | 设置后发送 `CMU`，`KK` 或 `IPA88` |
| `dict_dialect` | `dict_dialect` | 原样发送 |
| `customized_lexicon` | `customized_lexicon` | 字典序列化为 JSON 文本 |
| `customized_pron` | `customized_pron` | 字典序列化为 JSON 文本 |
| `readtype_diagnosis` | `readtype_diagnosis` | 非 0 时发送 |
| `output_rawtext` | `output_rawtext` | YES 时发送 1 |
| `punctuate` | `punctuate` | YES 时发送 1 |
| `itn` | `itn` | 非 0 时发送 |
| `detect_nonscorable` | `detect_nonscorable` | YES 时发送 1 |
| `vad_detection` | `vad_detction` | YES 时发送 1，字段名与安卓平替层相同 |
| `keywords` | `keywords` | 原样发送 |
| `keypoints` | `keypoints` | 数组序列化为 JSON 文本 |
| `keypoints_weight` | `keypoints_weight` | 非 0 时发送 |
| `negative_keypoints` | `negative_keypoints` | 数组序列化为 JSON 文本 |
| `negativeReftext` | `negativeReftext` | 原样发送 |
| `mode` | `mode` | `KYModeType_Home` 时发送 `home` |
| `qType` | `qType` | 非 0 时发送数值 |
| `customParams` | 键名即字段名 | 每个键值一个字段，与映射字段同名时覆盖映射值，空值不发送 |
| `request` | `request` | 字典序列化为 JSON 文本，排在最后，平台合并其中的标量字段 |

`customParams` 的值转成文本的规则：字符串原样，布尔值为 `true` 或 `false`，整数不带小数点，小数取最短形式，数组与字典为 JSON 文本。

只在本机生效的属性：

| 属性 | 作用 |
|---|---|
| `audioPath` | 评测已有的音频文件，开始后立即上传 |
| `isStream` | 不录音，由 `feedAudioData:audioLength:` 送入音频，`stopEngine` 后评测 |
| `audioType`，`sampleRate`，`channel`，`sampleBytes` | feed 与 `audioPath` 的音频格式。`wav` 类型的 PCM 加上 WAV 头后上传，其他类型原样上传 |
| `recordPath`，`recordName` | 录音文件位置，默认 `Documents/record/<tokenId>.wav` |
| `duration`，`durationInterval` | 最长录音时长与倒计时回调间隔，单位毫秒 |
| `seek`，`ref_length` | VAD 的说话结束静音时长与最短说话时长，单位 10 毫秒 |
| `soundIntensityEnable` | VAD 回调带上音强 |
| `recordCallbackInterval` | VAD 回调的最小间隔，单位毫秒 |
| `forceRecord` | 只有 `stopEngine` 才结束本次评测 |
| `autoRetry`，`errIds` | 自动重评 |
| `getParam` | 结果 JSON 带上 `params` |
| `serverTimeout` | 本次请求的超时，单位秒 |
| `userId` | 写入结果 JSON 的 `userId` |

照收但不生效的属性：

| 属性 | 说明 |
|---|---|
| `coreProvideType` | 一律云端评测，值为 `native` 时打印警告日志 |
| `compress`，`quality`，`complexity`，`vbr`，`max_ogg_delay` | 上传无损 WAV，不做 Speex 压缩 |
| `realtime_feedback` | 平台整段评测不返回中间评分 |
| `customized_sig`，`customized_sig_url` | 按 appKey 与 secretKey 签名 |
| `refAudio` | `align.eval` 平台不支持 |
| `phoneme_diagnosis`，`blend_phoneme_enable` | 不在映射表里，需要时经 `customParams` 发送 |
| `serialNumber`，`protocol` | 不使用 |

## 初始化参数

| KYStartEngineConfig 属性 | 平替包里的作用 |
|---|---|
| `appKey`，`secretKey` | 必填，用于请求签名，为空时初始化失败，`secretKey` 不写入日志 |
| `server` | 为空或主机在 `stkouyu.com` 下时用 `https://open.shengzhiai.com`。其他 `http`，`https`，`ws`，`wss` 开头的地址作为平台地址，`ws` 换成 `http`，`wss` 换成 `https`。没有这四种前缀或没有主机名的地址使初始化失败 |
| `connectTimeout`，`serverTimeout` | 单位秒，默认 20 与 60，单次请求的超时取二者中较大的值 |
| `vadEnable`，`seek` | 本机能量 VAD，`seek` 默认 60 |
| `sdkLogEnable`，`logLevel`，`sdkLogPath`，`isOutputLog` | 日志，见日志一节 |
| `customized_avaudiosession` | YES 时平替包不设置 AVAudioSession，由应用自己设置 |
| 证书与离线参数 | `isUseOnlineProvison`，`isUpdateProvison`，`provison`，`native`，`native_db_path`，`native_cn`，`ailocalAddress` 照收，不生效 |
| 其余参数 | `enable`，`serverList`，`sdkCfgAddr`，`autoDetectNetwork` 照收，不生效 |

`KY_NativeEngine` 与 `KY_MultiEngine` 照收，按云端评测，打印警告日志。`KY_CloudServer_Release` 与 `KY_CloudServer_Gray` 为声通文档里的两个地址，作为 `server` 时都改用 `https://open.shengzhiai.com`。

## 回调规则

全部回调在主线程，按发生顺序回调。一次评测在结果回调里收到一份结果 JSON 或错误 JSON，调用 `cancelEngine` 或 `deleteEngine` 之后这次评测不再有任何回调。结果回调指 `testResultBlock` 或 `onScoreBlock`，同时回调代理的 `kyTestEngineDidScore:`。

开始成功时 `finishBlock` 收到 YES 与 tokenId，录音与 feed 模式随后回调 `onStartBlock` 与 `kyTestEngineDidRecordStart`。开始失败时 `finishBlock` 收到 NO 与错误 JSON，其余回调与安卓平替层一致：

| 情况 | errId | 其余回调 |
|---|---|---|
| 引擎未初始化 | 60007 | 结果回调 |
| 上一次评测还没有结果 | 60008 | `onStartFailBlock` 收到 `engine is busy`，代理不回调，正在进行的评测不受影响 |
| coreType 不支持 | 60003 | 结果回调 |
| refText 为空 | 60006 | 结果回调 |
| 麦克风不可用或没有录音权限 | 60004 | `onStartFailBlock` 与 `kyTestEngineDidRecordStartFail:` |

录音过程的回调：

1. 录音结束时回调 `onRecordEndBlock`，`kyTestEngineDidRecordEnd` 与 `kyTestEngineDidRecordWriteAudioResult:`，随后上传评测。
2. 设置 `duration` 后每隔 `durationInterval` 回调剩余毫秒与剩余百分比，百分比取值 0 到 100，到时结束录音。
3. `vadEnable` 为 YES 时回调 `vad_status` 与 `sound_intensity`。`vad_status` 为 0 表示没开始说话，1 表示说话中，2 表示说话结束。说话后静音达到 `seek` 乘以 10 毫秒即判定结束，录音模式随即结束录音。`soundIntensityEnable` 为 NO 时 `sound_intensity` 为 0，为 YES 时取 0 到 100。
4. `forceRecord` 为 YES 时 VAD 与 `duration` 都不结束评测。到达 `duration` 时停止录音，保留音频，调用 `stopEngine` 后才上传。
5. 电话等系统打断录音时回调 `onPauseBlock`，打断结束后继续录音。
6. `audioPath` 模式开始后立即上传，文件不可读与音频不合格都以错误 JSON 回到结果回调。

其他接口：

| 接口 | 行为 |
|---|---|
| `stopEngine` | 结束录音或 feed，上传评测，有结果回调 |
| `cancelEngine` | 停止录音与上传，没有结果回调 |
| `deleteEngine` | 取消评测，释放录音器与播放器，恢复为未初始化，可以重复调用 |
| `getEngineStatus` | 录音或接收 feed 期间返回 YES |
| `getLastRecordPath` | 最近一次录音文件路径，没有录音时为空字符串 |
| `feedAudioData:audioLength:` | 没有可接收音频的评测时回调 `kyTestEngineDidRecordFeedFail:` |
| `playback`，`playWithPath:` 与带结束回调的两个方法 | 回调 `kyTestEngineDidPlayStart`，`kyTestEngineDidPlayStartFail:` 与 `kyTestEngineDidPlayEnd`，播放结束时调用传入的 block |
| `stopPlay` | 停止播放，回调播放结束 |
| `activeAudioSession` | 把音频会话设为 PlayAndRecord 后激活 |
| `updateProvision` 三个方法 | 返回 YES |
| `inquireProvision` 两个方法 | 返回 YES，回调 `{"provision":"cloud","message":"cloud mode, no provision file needed"}` |

## 结果 JSON

```json
{"tokenId":"3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c","recordId":"eval_77c081e19541","applicationId":"优谷雅言 appKey",
 "userId":"u1","refText":"今天天气很好","eof":1,"dtLastResponse":"2026-10-08 16:21:32:255",
 "result":{"overall":94.6,"pronunciation":100,"fluency":96,"integrity":100,"words":[]},
 "params":{"app":{"applicationId":"优谷雅言 appKey","userId":"u1","timestamp":"1791447692"},
           "audio":{"audioType":"wav","sampleRate":16000,"channel":1,"sampleBytes":2},
           "request":{"coreType":"sent.eval.cn","tokenId":"3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c","refText":"今天天气很好","phoneme_output":"1"}}}
```

1. `result` 为平台兼容接口返回的评测结果，原样转交，常用取分字段与声通同名。只有一处补充：平台把逐字得分放在 `sentences[].details[].scores` 里，声通示例直接读取 `details[].overall`，所以 `details[]` 里缺少 `overall` 的条目在末尾补上取自 `scores.overall` 的 `overall`，条目没有 `pronunciation` 时一并补上取自 `scores.pronunciation` 的 `pronunciation`。原有的键不改不删，其余内容逐字节不变。
2. `recordId` 为平台评测记录号，平台没有返回时不出现。
3. `params` 只在 `getParam` 为 YES 时出现。`audioUrl` 只在 `attachAudioUrl` 为 YES 时出现，位于 JSON 末尾，取值为平台返回的录音下载地址，原样转交。平台保留录音 7 天，到期删除，需要长期保存的录音在 7 天内下载，或用 `getLastRecordPath` 取本机录音。同一 tokenId 重复提交时平台返回同一个地址。
4. `para.eval` 与 `para.eval.cn` 一律请求逐字得分，结果总带 `result.sentences[].details[]`。
5. `dtLastResponse` 为本机时区收到结果的时刻，格式 `yyyy-MM-dd HH:mm:ss:SSS`。

## 错误 JSON 与 errId

```json
{"tokenId":"3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c","errId":20009,"error":"网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 503 code=50000 system busy (attempts 3)","eof":1,"applicationId":"优谷雅言 appKey"}
```

| errId | 含义 | 来源 |
|---|---|---|
| 20009 | 网络或服务端临时故障，重试后仍失败 | 网络错误，超时，HTTP 408，425，429，500，502，503，504，以及平台可重试错误码 |
| 平台错误码 | 平台业务错误，例如 40001，40100，40300，2003，2010，40902 | 错误响应体的 `code` |
| 60001 | 音频文件不存在或不可读 | `audioPath` |
| 60002 | 音频为空 | 本机检查，不上传 |
| 60003 | coreType 不支持 | 本机检查，不发请求 |
| 60004 | 麦克风不可用或没有录音权限 | 录音开始 |
| 60005 | 音频短于 1 秒 | 本机检查 WAV 与 PCM 时长，不上传 |
| 60006 | refText 为空 | 本机检查，不发请求 |
| 60007 | 引擎未初始化 | 本机检查 |
| 60008 | 引擎正忙，上一次评测未结束 | 本机检查 |
| 60009 | 音频大于 50 MB 或长于 300 秒 | 本机检查文件大小与 WAV 和 PCM 的时长，不上传 |
| 90005 | 平台响应无法解析 | 成功响应里没有 `result` |
| 90011 | 证书校验失败 | TLS 错误，或 App Transport Security 拦截了 http 地址 |

错误响应体没有错误码时按 HTTP 状态取码：401 为 40100，403 为 40300，404 为 40400，409 为 40900，501 为 50010，其他 4xx 为 40001，其他 5xx 为 50000，其余状态为 90005。完整错误码表见优谷雅言 SDK 仓库根目录的 `ERRORS.md`。

`error` 字段：errId 20009 时依次为 20009 的含义，HTTP 状态与错误码，平台消息与尝试次数，其他 errId 为平台消息或本机检查的说明。

## 重试与自动重评

1. 每次评测的 tokenId 同时作为请求头 `Idempotency-Key`，重试与自动重评都用同一个 tokenId。平台对同一个 tokenId 只计费一次，重复提交得到第一次的结果。
2. 网络错误，超时，可重试的 HTTP 状态与可重试的错误码自动重试，一次提交最多 3 次请求，两次重试的间隔约 200 毫秒与 400 毫秒，上下浮动 30%。响应带 `Retry-After` 时至少等待该时长，最多 30 秒。重试与等待限定在一次评测 300 秒的总时限内，下一次重试会越过总时限时不再重试，直接回调错误 JSON。
3. 400 等不可重试的错误不重试，直接回调错误 JSON。
4. `autoRetry` 为 YES 时，错误 JSON 的 errId 落在 `errIds` 里的评测用同一个 tokenId 重新提交，最多 2 次。`errIds` 默认为 `@[@"20009"]`，元素可以是字符串或数字。
5. `YuguCompat` 可以调整重试次数与总时限。

## 与声通 SDK 的行为差异

| 项 | 声通 SDK | 平替包 |
|---|---|---|
| 引擎 | 在线，离线，双引擎 | 只有云端评测 |
| 内核 | 多语种多题型 | 中文与英文的字词，句子，段落，字母与拼音题 |
| 中间结果 | `realtime_feedback` 返回中间评分 | 不返回中间评分，VAD 与音强回调照常 |
| 上传音频 | 可用 Speex 压缩 | 录音以 16 kHz 单声道 16 位 WAV 上传 |
| 录音文件 | WAV 或 MP3 | 一律 WAV 编码，`recordName` 写 `.mp3` 时保留原文件名 |
| 证书 | 离线证书 | 不需要证书，证书相关方法返回 YES |
| 音频地址 | `attachAudioUrl` 返回录音的下载地址 | 同样返回，字段为结果 JSON 末尾的 `audioUrl`，平台保留 7 天，到期删除 |
| 鉴权 | 可用 `customized_sig` | 一律按 appKey 与 secretKey 签名，`secretKey` 必填 |
| 结果 JSON | 声通评测字段 | 外层字段一致，`result` 为优谷雅言评测结果，`details[]` 条目补上 `overall` 与 `pronunciation` |
| 并发 | 由引擎决定 | 一个引擎同一时间只做一次评测，上一次结果回调之前开始新评测回调 60008 |
| 录音时长 | 由引擎决定 | 录音与 PCM 流到 300 秒时自动结束，随即评测，音频最大 50 MB，短于 1 秒不上传 |

## C 接口

`skegn.h` 的声明与声通公开的 `skegn.h` 相同，由本包实现，替换 `libskegn.a`。直接调用 C 接口的代码不用改，`#include "skegn.h"` 可以继续使用工程里的副本，也可以改为 `#import <STKouyuEngine/skegn.h>`。Swift 用 `import STKouyuEngine.skegn`。

| 函数 | 平替包里的行为 |
|---|---|
| `skegn_new` | 读取 cfg JSON 的 `appKey`，`secretKey` 与 `cloud.server`，缺少密钥时返回 NULL |
| `skegn_start` | 读取 param JSON 的 `app.userId`，`audio` 与 `request`，`request` 的成员作为表单字段发送，`id` 收到 tokenId |
| `skegn_feed` | 收集音频 |
| `skegn_stop` | 上传评测，结果 JSON 经 `callback` 以 `SKEGN_MESSAGE_TYPE_JSON` 回调，回调在内部队列上执行，不在主线程 |
| `skegn_cancel` | 取消，不回调 |
| `skegn_delete` | 取消评测，释放引擎 |
| `skegn_opt` | 版本，模块，流量与证书查询写入文本，返回文本长度 |
| `skegn_get_device_id` | 本机首次生成后保存的设备标识 |
| `skegn_update_provision`，`skegn_inquire_provision` | 返回 0，查询时回调云端说明 |
| `skegn_get_last_error` | 最近一次错误码，数值与声通 `skegn_errno.h` 相同 |

`request` 成员转成文本的规则：字符串原样，JSON 布尔值为 1 或 0，数字取最短形式，数组与对象为 JSON 文本。`request.getParam` 为真时结果带上 `params`，`request.autoRetry` 为真时按 errId 20009 自动重评。回调里可以调用 `skegn_cancel` 与 `skegn_start`。

## 扩展配置

声通接口以外的配置集中在 `YuguCompat.h`，不改变原有声明。

```objc
#import <STKouyuEngine/YuguCompat.h>

YuguCompat.baseURL = @"https://sandbox.example.com";   // 优先于 KYStartEngineConfig.server，nil 取消
[YuguCompat setLogLevel:YuguCompatLogLevelDebug];      // 优先于 KYStartEngineConfig.logLevel
[YuguCompat setLogHandler:^(NSInteger level, NSString *message) { /* 写入应用自己的日志 */ }];
YuguCompat.maxRetries = 2;      // 一次提交内的重试次数，0 到 5
YuguCompat.totalTimeout = 300;  // 一次评测的总时限，单位秒
NSLog(@"%@ %@", YuguCompat.sdkVersion, YuguCompat.effectiveBaseURL);
```

Swift 用 `import STKouyuEngine.YuguCompat`。

## 日志

1. 级别 0 为 error，1 为 warn，2 为 info，3 为 debug，默认 warn，与 `KYLOG_ERROR`，`KYLOG_WARN`，`KYLOG_INFO`，`KYLOG_DEBUG` 的取值相同。
2. `isOutputLog` 为 YES 时写控制台，`sdkLogEnable` 为 YES 时另外写入 `sdkLogPath`，默认 `Documents/sdkLog.txt`。
3. 日志不含 secretKey，签名与音频内容，appKey 只记前 4 位。
4. 应用调用 `KYLog` 的内容写入同一日志。

## 头文件一致性核对

`tools/headers-diff.py` 随包发布，接入方可以用手上的声通 framework 核对两边的公开接口是否逐项一致。只需要 Python 3.7 或更新版本，不依赖第三方库。

```bash
# 克隆本包后在包目录执行，第二个参数默认为本包的头文件目录
python3 tools/headers-diff.py /path/to/STKouyuEngine.framework/Headers
# 连同 skegn.h 一起比对，参数名不同也算差异
python3 tools/headers-diff.py --original /path/to/STKouyuEngine.framework/Headers \
    --original /path/to/skegn.h --strict --json headers-diff.json
```

比对内容：类与父类，协议与继承关系，属性及其修饰符与类型，方法的选择子，返回类型与参数类型，协议方法的 `@optional` 与 `@required`，枚举的成员与取值，typedef 与 block 类型，结构体，外部常量与函数及其属性宏，宏的取值，头文件保护宏，以及 framework 内部头文件之间的导入。注释，空白与换行不影响结果，UIKit 与 Foundation 等系统头文件的导入不比对。

退出码：0 表示一致，1 表示有差异，2 表示参数错误或无法解析。只存在于本包的 `YuguCompat.h` 列出但不比对。

## 验证状态

截至 2026-10-08：

| 部分 | 状态 |
|---|---|
| C 核心 | 签名，参数映射，multipart，WAV，VAD，重试判断，错误映射与结果 JSON 组装都在 `Sources/STKouyuEngine/core`。Linux 上用 gcc 13 按 C99 编译零警告，62 组单元测试 2955 项检查全部通过，AddressSanitizer 与 UndefinedBehaviorSanitizer 下同样通过，行覆盖率 98.39%。macOS 上用 Xcode 15.4 的 clang 跑同样的步骤，结果一致。按 2026-10-08 录下的平台返回核对，平台返回的 `audioUrl` 原样进入结果 JSON 末尾，单句段落与多句段落的 `details` 补充方式相同 |
| 集成测试 | C 核心按 KYTestEngine 的请求方式连接平台模拟服务 `tools/mock-server/server.mjs`，21 个场景 118 项检查在 Linux 与 macOS 上都通过，覆盖 500 与 429 重试，读超时与平台处理中两种情形下同一 tokenId 的多次提交只计费一次，409 40901 等待后重试，每次请求换新的 nonce，段落内核的逐字得分与 details 补充，`attachAudioUrl` 作为表单字段发送，400 不重试，autoRetry，签名错误，以及本机错误不发请求 |
| 沙箱测试 | 设置 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后，`ci/ios-stcompat.sh` 的 sandbox 步骤让同一套 C 核心经 HTTPS 连接真实平台，校验证书与主机名，依次跑五个用例。`sent.eval.cn` 句子评测得到数值型 `result.overall`，结果 JSON 不带 `audioUrl`，`para.eval.cn` 段落评测的 `details` 每项带 `overall` 与 `pronunciation`，带 `attachAudioUrl` 的句子评测在结果 JSON 末尾得到平台返回的 https 下载地址 `audioUrl`，平台不认识的 appKey 得到鉴权类 errId，`pinyin` 题缺 `refPinyin` 得到 40001，两个错误用例都只发一次请求，不触发评测。每次运行最多 6 次平台调用，密钥只经环境变量传给测试程序，不出现在命令行与日志里。没有密钥时这一步记为跳过。2026-10-08 平台更新后实测五个用例 59 项检查全部通过，共 5 次平台调用。`Tests/STKouyuEngineTests/SandboxTests.swift` 经 Objective-C 接口跑其中四个用例，不含 `attachAudioUrl` 用例，已在 macOS 与 iOS 模拟器上编译，GitHub 的推送构建不注入沙箱密钥，这四个用例在 macOS 上尚未运行 |
| 头文件 | 与声通公开头文件比对 5 个文件 227 项声明，差异为 0，参数名也一致 |
| 包清单 | Linux 上用 Swift `6.0.3` 加载 `Package.swift`，`swift package describe` 识别出全部目标与源文件，没有警告。macOS 上 Swift `5.10` 按同一份清单构建全部目标 |
| Objective-C 层 | 2026-10-08 在 GitHub Actions 的 macos-14 构建机上用 Xcode 15.4 编译，链接，运行。swift build 构建 macOS 版，xcodebuild 构建 iOS 真机版与模拟器版，真机版部署目标为 iOS 12，全部零警告。Linux 上另用 libclang 18 按 Objective-C ARC 解析全部 `.m` 文件，对照手写的 Apple 接口桩声明做类型检查，20 个编译单元零错误零警告 |
| XCTest | `Tests/STKouyuEngineTests` 的 Swift 测试与其调用的 Objective-C 检查 `Tests/STKouyuEngineObjCSupport`。macOS 上 swift test 执行 14 项，13 项通过，沙箱用例 1 项跳过，iOS 17.5 模拟器上 xcodebuild test 结果相同，连接平台模拟服务的文件评测，流式评测，取消与 skegn 接口用例在两处都通过。`Sources/STKouyuEngine` 的行覆盖率在 macOS 上为 58.50%，在模拟器上为 57.76%，录音器 `YGSTRecorder.m` 需要麦克风，测试没有覆盖 |
| macOS 构建 | `ci/ios-stcompat-macos.sh` 2026-10-08 在 GitHub Actions 的 macos-14 构建机上全部步骤通过：在 macOS 上重跑 C 核心的各步骤，swift build，iOS 真机与模拟器的 xcodebuild，连接平台模拟服务的 swift test，以及 iOS 模拟器上的 xcodebuild test。设置沙箱密钥时 swift test 另跑沙箱用例 |
| 真机 | 录音，回放，系统打断与音频路由切换尚未在真机验证，构建机上的测试不录音 |

## 常见问题

### skegn 符号重复的链接错误

工程里还留着 `libskegn.a`，删除即可。本包已经实现全部 `skegn_*` 函数。

### 开始评测时的 60008 回调

上一次评测还没有回调结果就开始了新评测。等结果回调之后再开始，或者先调用 `cancelEngine`。

### 结果里的声通字段

外层字段与声通一致。`result` 内为优谷雅言评测结果，常用取分字段 `overall`，`pronunciation`，`fluency`，`integrity`，`words` 与声通同名，其余字段以优谷雅言接口文档为准。

### 私有部署与联调地址

`server` 写 `https://` 开头的地址，或设置 `YuguCompat.baseURL`。用 `http://` 地址时应用需要配置 App Transport Security 例外，否则回调 errId 90011。

### 扩展名为 mp3 的 WAV 录音文件

平替包不带 MP3 编码器。`recordName` 写 `.mp3` 时保留文件名，内容为 WAV，用 `playback` 或 AVAudioPlayer 都能正常播放。

### 应用自管的音频会话

把 `customized_avaudiosession` 设为 YES，平替包不再设置 AVAudioSession。录音前需要应用把类别设为可录音的类别。

### 重复提交与计费

同一次评测的重试与自动重评共用一个 tokenId，只计费一次。每次 `startEngine` 都生成新的 tokenId，算作新的评测。

## 许可

Apache License 2.0，全文见 `LICENSE`，另见 `NOTICE`。声通与 17kouyu 是其各自权利人的名称，此处仅用于说明接口兼容关系。本包只按公开头文件重新实现，不含声通的实现代码，不分发声通的二进制文件。
