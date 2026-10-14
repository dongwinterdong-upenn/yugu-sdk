# STKouyuEngine 声通 iOS 平替包

STKouyuEngine 是优谷雅言语音评测 SDK 2.0 的 iOS 声通平替包。已经接入声通 `STKouyuEngine.framework` 的应用，用这个 SwiftPM 包替换声通的 framework 与 `libskegn.a`，换上优谷雅言的 appKey 与 secretKey，业务代码不改，评测改由优谷雅言云端完成。类名，属性，方法签名，枚举值，常量与代理协议都与声通公开头文件一致，回调里的结果 JSON 与错误 JSON 与安卓平替层一致。

## 版本与环境

| 项 | 值 |
|---|---|
| 包地址 | `https://open.shengzhiai.com/git/stkouyu-ios-compat.git` |
| 版本 | `2.3.0` |
| 产品与模块 | `STKouyuEngine`，Objective-C 实现 |
| 最低系统 | iOS 12，边录边传与朗读进度需要 iOS 13，另支持 macOS 10.15 供测试使用 |
| 工具链 | Xcode 15 及更新版本，`swift-tools-version:5.9` |
| 依赖 | 只用系统框架 Foundation，AVFoundation，CoreGraphics 与 UIKit |
| 许可 | Apache-2.0 |

## 接入步骤

### 需要移除的声通文件

1. 在 Xcode 工程里删除 `STKouyuEngine.framework` 与 `libskegn.a`，同时删除 Build Phases 里对二者的链接与嵌入设置。
2. 用 CocoaPods 接入声通的工程，在 `Podfile` 里删掉 `pod '17kouyu-tt'` 一行，再执行 `pod install`。
3. 工程里自带的 `skegn.h` 副本可以保留，声明与本包相同。

### SwiftPM 依赖

在 Xcode 菜单 `File > Add Package Dependencies` 填入包地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，规则选 `Up to Next Major Version`，起始版本 `2.3.0`，把产品 `STKouyuEngine` 加到应用 target。

用 `Package.swift` 管理依赖的工程这样写：

```swift
dependencies: [
    .package(url: "https://open.shengzhiai.com/git/stkouyu-ios-compat.git", from: "2.3.0"),
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
| `open.eval.cn` | 只能用 `coreTypeNS` | 中文开放题，见下方中文开放题一节，`2.1.2` 起支持 |

其余内核在本地回调 errId 60003，不发网络请求，不录音，包括 `open.eval`，`choice.rec`，`asr.rec`，`asr.eval`，`align.eval`，法语，日语与韩语内核，`KYTestType_Wordspell`，以及声通内核以外的名字。错误 JSON 的 `error` 写明请求的内核，例如“不支持的评测内核：open.eval”，同时打印一行 warn 日志，写明内核与 errId 60003。需要以异常处理时打开 `YuguCompat.throwOnUnsupportedCoreType`，见下方不支持的内核一节。

`refText` 为空时回调 errId 60006。中文内核与 `pinyin` 设置了 `refPinyin` 时可以不设 `refText`，`open.eval.cn` 除外。

### 中文开放题

`2.1.2` 起支持 `open.eval.cn`。`KYTestType` 没有这个内核的成员，要用 `coreTypeNS` 指定。平台按原生开放题评分，看内容，语言运用与表达三项，语言固定为中文。

| KYTestConfig 属性 | 用法 |
|---|---|
| `refText` | 必填，与声通相同，填一条或多条参考答案，多条用竖线隔开，写法见下方示例。也可以填题目或任务提示，由评分模型判断是哪一种。是参考答案时，作答与任意一条意思一致就算答对，不要求逐字相同，作答简短不扣分。`refPinyin` 不能代替 `refText` |
| `qType` | `KYQType_LookAndSay` 即 5 按看图说话评，`KYQType_SituationalReply` 即 6 按情景问答评，其他取值时按自由表达评。兼容接口不收图片，看图说话按自由表达的细则评分 |
| `keypoints`，`keywords` | 采分点，`keypoints` 为字符串数组，`keywords` 用竖线隔开，两项合计最多 20 个。评分时参考，也用来提高这些词的识别率。参考答案不用来提高识别率 |
| `negative_keypoints`，`keypoints_weight`，`mode` | 照常发送，开放题评分不使用 |

```objc
KYTestConfig *test = [[KYTestConfig alloc] init];
test.coreTypeNS = @"open.eval.cn";
test.refText = @"我是学生|我不是学生";
test.qType = KYQType_SituationalReply;
```

结果 JSON 的 `result` 含原生开放题的全部字段：`overall` 总分，`content` 内容，`languageUse` 语言运用，`delivery` 表达，`transcript` 识别文本，`feedback` 点评，含义见仓库根目录 [RESULTS.md](../RESULTS.md) 的开放题一节。`openTaskAudit.refKind` 为 `answers` 时评分模型把 `refText` 当参考答案，为 `question` 时当题目。另有与声通相同的扁平字段 `pronunciation`，`fluency`，`integrity`，`coherence` 与 `speed`，`speed` 为每分钟字数。结果里没有逐字的 `words` 与 `sentences`。录音同样边录边传，`realtime_feedback` 为 YES 时中间结果只有已收音频的字节数 `bytes`，没有朗读进度。

### 不支持的内核

1. 错误 JSON 的 `error` 为“不支持的评测内核：”加上请求的内核，`2.2.0` 起提供。`coreTypeNS` 不为空时写去掉首尾空白的 `coreTypeNS`，为空时写 `coreType` 对应的声通内核名，没有内核名的写 `KYTestType 22` 这样的枚举值，`testConfig` 为 nil 时写“未指定”。C 接口写 `request.coreType`，为空或缺少时写“未指定”。错误 JSON 的其余字段不变。
2. 每次拒绝打印一行 warn 日志，写明内核与 errId 60003，默认的 warn 级别即可看到。
3. `YuguCompat.throwOnUnsupportedCoreType` 默认 NO。设为 YES 之后，`KYTestEngine` 的两个开始评测方法遇到不支持的内核时在调用线程同步抛出 `NSInvalidArgumentException`，`reason` 与 `error` 的文字相同，不回调，不录音，不发请求。C 接口 `skegn_start` 不能抛出异常，改为返回 -1，`skegn_get_last_error` 为 60003，打印一行 error 日志，不回调。引擎未初始化与 `refText` 为空等其余错误照常回调。
4. Swift 不能捕获 Objective-C 异常，Swift 工程打开之前需要先确认内核受支持。

```objc
#import <STKouyuEngine/YuguCompat.h>

YuguCompat.throwOnUnsupportedCoreType = YES;
@try {
    [[KYTestEngine sharedInstance] startEngineWithTestConfig:test result:resultBlock finishBlock:finishBlock];
} @catch (NSException *e) {
    NSLog(@"%@ %@", e.name, e.reason); // NSInvalidArgumentException 不支持的评测内核：open.eval
}
```

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
| `readtype_diagnosis` | `readtype_diagnosis` | 非 0 时发送，结果的 `words` 另含增读与重复读，默认只含参考文本里的字词 |
| `output_rawtext` | `output_rawtext` | YES 时发送 1 |
| `punctuate` | `punctuate` | YES 时发送 1 |
| `itn` | `itn` | 非 0 时发送 |
| `detect_nonscorable` | `detect_nonscorable` | YES 时发送 1 |
| `vad_detection` | `vad_detction` | YES 时发送 1，字段名与安卓平替层相同 |
| `keywords` | `keywords` | 原样发送，`open.eval.cn` 用作采分点 |
| `keypoints` | `keypoints` | 数组序列化为 JSON 文本，`open.eval.cn` 用作采分点 |
| `keypoints_weight` | `keypoints_weight` | 非 0 时发送 |
| `negative_keypoints` | `negative_keypoints` | 数组序列化为 JSON 文本 |
| `negativeReftext` | `negativeReftext` | 原样发送 |
| `mode` | `mode` | `KYModeType_Home` 时发送 `home` |
| `qType` | `qType` | 非 0 时发送数值，`open.eval.cn` 据此选看图说话或情景问答 |
| `customParams` | 键名即字段名 | 每个键值一个字段，与映射字段同名时覆盖映射值，空值不发送 |
| `request` | `request` | 字典序列化为 JSON 文本，排在最后，平台合并其中的标量字段 |
| `realtime_feedback` | 不是表单字段 | YES 时参数帧带 `realtime_feedback`，评测过程中回调朗读进度，见朗读进度一节。录音与 feed 模式不论本项都边录边传，上表的字段放进参数帧，见边录边传一节 |

`customParams` 的值转成文本的规则：字符串原样，布尔值为 `true` 或 `false`，整数不带小数点，小数取最短形式，数组与字典为 JSON 文本。

只在本机生效的属性：

| 属性 | 作用 |
|---|---|
| `audioPath` | 评测已有的音频文件，开始后立即上传 |
| `isStream` | 不录音，由 `feedAudioData:audioLength:` 送入音频。16 kHz 16 位单声道 PCM 边送边传，`stopEngine` 后只上传剩余部分，其他格式在 `stopEngine` 后整段上传 |
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

全部回调在主线程，按发生顺序回调。一次评测在结果回调里收到一份结果 JSON 或错误 JSON，`realtime_feedback` 为 YES 时在此之前还会收到若干份 `eof` 为 0 的朗读进度 JSON，见朗读进度一节。调用 `cancelEngine` 或 `deleteEngine` 之后这次评测不再有任何回调。结果回调指 `testResultBlock` 或 `onScoreBlock`，同时回调代理的 `kyTestEngineDidScore:`。

开始成功时 `finishBlock` 收到 YES 与 tokenId，录音与 feed 模式随后回调 `onStartBlock` 与 `kyTestEngineDidRecordStart`。开始失败时 `finishBlock` 收到 NO 与错误 JSON，其余回调与安卓平替层一致：

| 情况 | errId | 其余回调 |
|---|---|---|
| 引擎未初始化 | 60007 | 结果回调 |
| 上一次评测还没有结果，打开流水线评测时为上一次评测还在录音或后台等结果的评测多于上限 | 60008 | `onStartFailBlock` 收到 `engine is busy`，代理不回调，正在进行的评测不受影响 |
| 不支持的评测内核 | 60003 | 结果回调，`YuguCompat.throwOnUnsupportedCoreType` 为 YES 时改为抛出 `NSInvalidArgumentException`，不回调 |
| refText 为空 | 60006 | 结果回调 |
| 麦克风不可用或没有录音权限 | 60004 | `onStartFailBlock` 与 `kyTestEngineDidRecordStartFail:` |

录音过程的回调：

1. 录音结束时回调 `onRecordEndBlock`，`kyTestEngineDidRecordEnd` 与 `kyTestEngineDidRecordWriteAudioResult:`。音频已在录音过程中上传，随后发出剩余音频与结束帧，等待评测结果。`YuguCompat.streamUpload` 为 NO 时此时整段上传，见边录边传一节。
2. 设置 `duration` 后每隔 `durationInterval` 回调剩余毫秒与剩余百分比，百分比取值 0 到 100，到时结束录音。
3. `vadEnable` 为 YES 时回调 `vad_status` 与 `sound_intensity`。`vad_status` 为 0 表示没开始说话，1 表示说话中，2 表示说话结束。说话后静音达到 `seek` 乘以 10 毫秒即判定结束，录音模式随即结束录音。`soundIntensityEnable` 为 NO 时 `sound_intensity` 为 0，为 YES 时取 0 到 100。
4. `forceRecord` 为 YES 时 VAD 与 `duration` 都不结束评测。到达 `duration` 时停止录音，保留音频，调用 `stopEngine` 后才上传。
5. 电话等系统打断录音时回调 `onPauseBlock`，打断结束后继续录音。
6. `audioPath` 模式开始后立即上传，文件不可读与音频不合格都以错误 JSON 回到结果回调。

其他接口：

| 接口 | 行为 |
|---|---|
| `stopEngine` | 结束录音或 feed，上传剩余音频后评测，有结果回调 |
| `cancelEngine` | 停止录音与上传，没有结果回调，打开流水线评测时后台等结果的评测一并取消 |
| `deleteEngine` | 取消评测，释放录音器与播放器，恢复为未初始化，可以重复调用 |
| `getEngineStatus` | 录音或接收 feed 期间返回 YES，后台等结果的评测不算在内 |
| `getLastRecordPath` | 最近一次录音文件路径，没有录音时为空字符串 |
| `feedAudioData:audioLength:` | 没有可接收音频的评测时回调 `kyTestEngineDidRecordFeedFail:` |
| `playback`，`playWithPath:` 与带结束回调的两个方法 | 回调 `kyTestEngineDidPlayStart`，`kyTestEngineDidPlayStartFail:` 与 `kyTestEngineDidPlayEnd`，播放结束时调用传入的 block |
| `stopPlay` | 停止播放，回调播放结束 |
| `activeAudioSession` | 把音频会话设为 PlayAndRecord 后激活 |
| `updateProvision` 三个方法 | 返回 YES |
| `inquireProvision` 两个方法 | 返回 YES，回调 `{"provision":"cloud","message":"cloud mode, no provision file needed"}` |

### 流水线评测

一组题目连续朗读时，默认要等上一题的结果回调之后才能开始下一题，否则回调 errId 60008，学生只能等每一题出分。`2.3.0` 起把 `YuguCompat.maxPendingEvaluations` 设为大于 0 的 n，一题停录之后转到后台评测，下一题立即可以录音，各题的结果按评测完成的先后回调。平台可以同时评测多题，一组题目读完之后各题的结果陆续到齐。

1. `maxPendingEvaluations` 默认 0，与 `2.2.0` 相同，一个引擎同一时间只做一次评测。取值限定在 0 到 16，负数按 0，每次开始评测时读取，取值变化时打印一行 info 日志。
2. 停录指调用 `stopEngine`，VAD 判定说话结束，到达 `duration` 或 300 秒上限时结束录音。停录处理完毕时回调 `onRecordEndBlock` 与 `kyTestEngineDidRecordEnd`，这一题随即转到后台，`getEngineStatus` 返回 NO，在这两个回调里开始下一题即可立即录音。`stopEngine` 之后马上开始下一题同样可以，平替包先处理完上一题的停录再开始。
3. 上一题还没有停录时开始评测照旧回调 60008，包括录音中，暂停，等待录音权限，以及设置了 `forceRecord`，录满 `duration` 之后还在等 `stopEngine` 的情况。后台等结果的评测多于 n 次时开始评测同样回调 60008。`finishBlock` 收到 NO 与错误 JSON，带步骤回调的开始方法另在 `onStartFailBlock` 收到 `engine is busy`，代理不回调。
4. n 为 1 时，一题在后台评测的同时可以录下一题。两题都在等结果时开始第三题回调 60008，其中一题的结果回调之后即可开始。`audioPath` 评测不录音，开始之后即算作后台等结果的评测。
5. 每次评测有自己的 tokenId，录音文件与网络连接。重试，超时，自动重评，以及结束帧发出之前连接失败时用同一个 tokenId 整段评测，都与一次只做一次评测时相同。开始评测时传入的 block 只收到这一次评测的回调。代理 `kyTestEngineDidScore:` 收到全部评测的结果，结果 JSON 带 `tokenId` 与 `recordId`，按 `tokenId` 对应到题目，错误 JSON 同样带 `tokenId`。
6. 朗读进度与一次只做一次评测时相同，回调到这一题的终评为止。停录之后平台补发的句末几个字的进度同样送到这一题，下一题已经开始录音时也一样。各题的进度只回调到这一题开始时传入的 block，代理收到各题的进度，按 `tokenId` 区分。终评回调之后不再回调这一题的进度。
7. `YuguCompat.pendingEvaluations` 为已停录或以 `audioPath` 开始，还没有回调结果的评测次数，可以用来显示后台还有几题在评测。
8. `cancelEngine` 取消正在录音的一题与后台等结果的全部评测，之后都不再回调，`pendingEvaluations` 随即回到 0，之后可以照常开始评测。`deleteEngine` 与再次 `initEngine` 同样取消全部评测。
9. 每次开始评测打印一行 info 日志 `pipelined evaluation tokenId=<tokenId> pending=<后台等结果的评测次数> maxPendingEvaluations=<n>`，`audioPath` 评测在末尾另有 `(audioPath)`。开关取值变化时打印一行 info 日志，例如 `pipelined evaluation on: maxPendingEvaluations=3 (was 0)`。后台等结果的评测多于 n 次而被拒时打印一行 warn 日志，例如 `start rejected: engine is busy (pending=2 maxPendingEvaluations=1)`。每次评测照旧打印一行分段耗时。日志里没有密钥，签名，音频与结果内容。
10. 流水线评测只在 `KYTestEngine` 上提供，C 接口 `skegn_*` 的规则不变。

```objc
#import <STKouyuEngine/KYTestEngine.h>
#import <STKouyuEngine/YuguCompat.h>

/// 一组句子连续朗读：录完一句立即开始下一句，结果按完成先后到达，用 tokenId 对应到句子
@interface GroupReading : NSObject <KYTestEngineDelegate>
@property (nonatomic, copy) NSArray<NSString *> *sentences;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *sentenceOfToken; // tokenId 到句子序号
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSDictionary *> *results;     // 句子序号到终评
@end

@implementation GroupReading

- (void)startGroup {
    YuguCompat.maxPendingEvaluations = 3; // 录音时最多 3 句在后台等结果，默认 0 为一次一句
    self.sentenceOfToken = [NSMutableDictionary dictionary];
    self.results = [NSMutableDictionary dictionary];
    [KYTestEngine sharedInstance].delegate = self;
    [self readSentenceAt:0];
}

- (void)readSentenceAt:(NSUInteger)index {
    KYTestConfig *test = [[KYTestConfig alloc] init];
    test.coreType = KYTestType_Sentence_Cn;
    test.refText = self.sentences[index];
    __weak GroupReading *weakSelf = self;
    NSString *tokenId = [[KYTestEngine sharedInstance] startEngineWithTestConfig:test
        onStartBlock:nil
        onStartFailBlock:^(NSString *failReason) {
            NSLog(@"第 %lu 句未能开始：%@", (unsigned long)index, failReason); // 后台等结果的句子多于 3 句时为 engine is busy
        }
        onPauseBlock:nil
        onTickBlock:nil
        onRecordingBlock:nil
        onRecordEndBlock:^{
            // 这一句停录已处理，转到后台评测，立即开始下一句
            if (index + 1 < weakSelf.sentences.count) [weakSelf readSentenceAt:index + 1];
        }
        onScoreBlock:nil
        finishBlock:nil];
    self.sentenceOfToken[tokenId] = @(index);
}

- (void)nextSentence {
    [[KYTestEngine sharedInstance] stopEngine]; // 结束当前一句的录音
}

- (void)kyTestEngineDidScore:(NSString *)str {
    NSData *data = [str dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
    if ([json[@"eof"] integerValue] != 1) return; // 朗读进度
    NSNumber *index = self.sentenceOfToken[json[@"tokenId"]];
    if (index) self.results[index] = json; // 错误 JSON 同样带 tokenId
    NSLog(@"已出结果 %lu 句，后台还有 %ld 句", (unsigned long)self.results.count, (long)YuguCompat.pendingEvaluations);
}

- (void)leaveGroup {
    [[KYTestEngine sharedInstance] cancelEngine]; // 正在录的一句与后台等结果的各句都不再回调
}

@end
```

```swift
import STKouyuEngine.YuguCompat

YuguCompat.maxPendingEvaluations = 3
print(YuguCompat.pendingEvaluations)
```

示例与测试一起编译，副本在 `Tests` 目录里。

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

## 边录边传

录音模式与 feed 模式默认边录边传：音频在录音过程中经声通兼容实时评测接口 `wss://{平台地址}/{coreType}` 上传，`stopEngine` 时只剩最后一小段音频与结束帧，平台随即评测，不再等录音结束后整段上传。录音文件照旧写入 `recordPath`，`getLastRecordPath` 与回放不变。

1. feed 模式要求 `audioType` 为 `wav` 或 `pcm`，`sampleRate` 为 16000，`channel` 为 1，`sampleBytes` 为 2，其他格式在 `stopEngine` 之后整段上传。`audioPath` 模式总是整段上传。
2. 需要 iOS 13 或更新版本，iOS 12 上照旧整段上传，日志级别为 info 时打印一行原因。
3. 不打开 `realtime_feedback` 时参数帧不带 `realtime_feedback`，平台不下发进度，结果回调只收到终评 JSON，与整段上传时相同。打开时回调朗读进度，见朗读进度一节。
4. 结束帧发出之前连接失败，例如握手被拒，断网或心跳超时，`stopEngine` 之后用同一个 tokenId 整段上传评测，日志级别为 warn 时打印失败原因。结束帧发出之后连接失败时按朗读进度一节第 8 条重新连接。同一个 tokenId 只评测一次，只计费一次。
5. `YuguCompat.streamUpload` 设为 NO 之后开始的评测与 `2.1.0` 相同，录音结束后整段上传。`realtime_feedback` 为 YES 的评测仍然边录边传，回调朗读进度。

```objc
#import <STKouyuEngine/YuguCompat.h>

YuguCompat.streamUpload = NO;   // 录音结束后整段上传，与 2.1.0 相同，默认 YES
```

```swift
import STKouyuEngine.YuguCompat

YuguCompat.streamUpload = false
```

### 压缩

1. 整段上传的评测结果：NSURLSession 自动在请求头带上 `Accept-Encoding: gzip, deflate, br`，回调之前自动解压，平台对评测结果做 gzip 压缩，1.5 MB 的段落结果在网络上约 120 KB。平替包不改这个请求头。
2. 边录边传的终评帧：平替包使用系统的 NSURLSessionWebSocketTask，由系统自行在握手里提出 `permessage-deflate`，平台接受后终评帧压缩传输，系统解压之后交给平替包。系统同时压缩上行的音频帧。平替包不另写 WebSocket 实现，也不改握手里的这个请求头，系统没有提出时终评帧不压缩传输。
3. 是否提出由系统版本决定，Apple 的接口文档没有说明。Apple 开发者论坛上 Apple 技术支持工程师的答复与抓包显示 URLSession 的 WebSocket 握手带 `Sec-WebSocket-Extensions: permessage-deflate`，出处与核对情况见验证状态一节。
4. 分段耗时日志里 `deflate` 表示这一次协商成功，`gzip` 表示整段上传的结果经 gzip 压缩，`wire` 为结果在网络上的字节数。系统不报告终评帧在网络上的字节数，边录边传时 `wire` 记为 `-`。

## 朗读进度

`KYTestConfig.realtime_feedback` 设为 YES 时，录音与 feed 模式的评测经声通兼容实时评测接口 `wss://{平台地址}/{coreType}` 边读边上传音频，参数帧带 `realtime_feedback`。平台在收音过程中回报参考文本里读到了哪里，平替包把进度以 `eof` 为 0 的 JSON 交给结果回调，与终评使用同一个回调，结构同声通 `realtime_feedback` 的中间结果。界面可以据此让参考文本跟随朗读位置。终评 JSON 与不打开时相同，同一个 tokenId 只评测一次，只计费一次。

```json
{"tokenId":"3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c","recordId":"","applicationId":"优谷雅言 appKey","userId":"u1",
 "refText":"北京你好","eof":0,"dtLastResponse":"2026-10-12 14:03:21:456",
 "result":{"kernel_version":"1.0.0","resource_version":"1.0.0",
  "words":[{"charType":0,"scores":{"pronunciation":100,"skipped":0,"tone":100,"overall":100,"overall_pron":100},
            "word":"京","word_parts":[{"beginIndex":0,"charType":0,"endIndex":0,"part":"京"}]}],
  "bytes":48000,"readUnits":2,"matchedUnits":2,"totalUnits":4,
  "progress":[{"word":"北","read":true,"matched":true},{"word":"京","read":true,"matched":true},
              {"word":"你","read":false,"matched":false},{"word":"好","read":false,"matched":false}]}}
```

| 字段 | 说明 |
|---|---|
| `eof` | 0 为朗读进度，终评与错误 JSON 为 1 |
| `recordId` | 空串，评测记录编号在终评中给出 |
| `result.words` | 上一次进度回调之后新读到的字或词，按参考文本顺序。各次回调的 `words` 依次拼起来就是已读的部分，每个字或词只回调一次 |
| `result.words[].scores` | 只表示识别结果与参考文本是否一致：一致时各项为 100，`skipped` 为 0，不一致时各项为 0，`skipped` 为 1，英文的 `tone` 为 0。回调之后不再更新，发音与声调的得分以终评为准 |
| `result.words[].charType` | 0 为汉字，1 为英文 |
| `result.progress` | 参考文本全部的字或词，`read` 为朗读已越过，`matched` 为识别结果一致，每次回调都是最新状态。一次评测之内两者只会由 false 变为 true，`matched` 为 true 时 `read` 也为 true |
| `result.readUnits`，`result.matchedUnits`，`result.totalUnits` | 已读的个数，识别一致的个数，总数。`readUnits` 即当前朗读位置 |
| `result.bytes` | 平台这一轮已收到的音频字节数 |
| `result.kernel_version`，`result.resource_version` | 与终评相同 |

1. 中文按字，去掉标点与空白。英文按空白分词，去掉词首词尾的标点，词内的撇号与连字符保留。`coreType` 以 `.cn` 结尾或为 `pinyin` 时按字，其余按词，`customParams` 带 `language` 时按 `language` 判断。
2. 读到新的字或词时平台立即下发进度，此外大约每 0.5 秒音频下发一次。没有新读到内容的进度不回调，只有字节数的进度也不回调。进度一般比朗读晚 1 到 2 秒，随网络情况变化。
3. `stopEngine` 发出结束帧之后，平台对最后一段音频再识别一次，句末的几个字以进度回调送达，然后才是终评。终评之后不再有进度回调。打开流水线评测时同样如此，上一题的句末进度可能在下一题开始录音之后送达，只回调到上一题自己的 block，见回调规则一节的流水线评测。
4. 朗读进度只对 16 kHz 16 位单声道 PCM 生效。录音模式总是满足，feed 模式要求 `audioType` 为 `wav` 或 `pcm`，`sampleRate` 为 16000，`channel` 为 1，`sampleBytes` 为 2。`audioPath` 模式与其他音频照旧整段评测，不回调进度。
5. 需要 iOS 13 或更新版本。iOS 12 上照旧整段评测，不回调进度，日志级别为 info 时打印一行说明。
6. 参考文本为空或平台当时无法跟踪进度时，这一轮没有进度回调，终评不受影响。
7. 结束帧发出之前连接失败，例如握手被拒，断网或心跳超时，平替包关闭连接，不再回调进度，录音照常进行，`stopEngine` 之后用同一个 tokenId 整段评测，与 `YuguCompat.streamUpload` 为 NO 时相同。平台没有收到结束帧时不评测，不计费。
8. 结束帧发出之后连接失败或终评超时，平替包用同一个 tokenId 重新连接，重发参数帧，全部音频与结束帧。重连次数与整段评测的重试次数相同，默认 2 次，用尽后回调与整段评测相同的错误 JSON，errId 为 20009。平台按 tokenId 只评测一次，重放期间平台从第一个字重新计算进度，已经回调过的字或词按位置去掉，不会重复回调。
9. 握手的 query 带 `appKey`，`timestamp`，`nonce`，`idempotencyKey` 与 `signature`，签名算法与整段评测相同，参数帧为参数映射一节的表单字段加 `realtime_feedback`，日志级别为 info 或 debug 时另加 `timing`，最后是 `idempotencyKey`。结束帧发出之前每 15 秒发一次协议层 ping，30 秒收不到 pong 视为断线。

### 按朗读进度给参考文本着色

```objc
#import <STKouyuEngine/KYTestEngine.h>
#import <UIKit/UIKit.h>

/// 按 result.progress 给参考文本着色：已读且对上为绿色，已读未对上为橙色，未读为灰色
static NSAttributedString *ColoredRefText(NSString *refText, NSArray<NSDictionary *> *progress) {
    NSMutableAttributedString *text = [[NSMutableAttributedString alloc]
        initWithString:refText attributes:@{NSForegroundColorAttributeName : UIColor.grayColor}];
    NSUInteger from = 0;
    for (NSDictionary *unit in progress) {
        NSRange r = [refText rangeOfString:unit[@"word"] options:0 range:NSMakeRange(from, refText.length - from)];
        if (r.location == NSNotFound) break;
        from = NSMaxRange(r);
        if (![unit[@"read"] boolValue]) continue;
        [text addAttribute:NSForegroundColorAttributeName
                     value:[unit[@"matched"] boolValue] ? UIColor.greenColor : UIColor.orangeColor
                     range:r];
    }
    return text;
}

static void StartReadingWithProgress(UILabel *label) {
    KYTestConfig *test = [[KYTestConfig alloc] init];
    test.coreType = KYTestType_Sentence_Cn;
    test.refText = @"我是中国人，我是中国人。我们都是中国人。中华民族是一家人。";
    test.realtime_feedback = YES;
    label.attributedText = ColoredRefText(test.refText, @[]);
    [[KYTestEngine sharedInstance] startEngineWithTestConfig:test
        result:^(NSString *testResult) {
            NSData *data = [testResult dataUsingEncoding:NSUTF8StringEncoding];
            NSDictionary *json = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
            if ([json[@"eof"] integerValue] == 0) {
                label.attributedText = ColoredRefText(test.refText, json[@"result"][@"progress"]);
            } else if (json[@"errId"]) {
                NSLog(@"errId %@ %@", json[@"errId"], json[@"error"]);
            } else {
                NSLog(@"overall %@", json[@"result"][@"overall"]);
            }
        }
        finishBlock:nil];
}
```

`result.progress` 每次都是全部字或词的最新状态，整段重新着色即可。只需要跟随朗读位置时，取 `result.readUnits`，或者累加各次回调 `result.words` 的个数。示例与测试一起编译，副本在 `Tests/STKouyuEngineObjCSupport` 里。

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
| 60003 | 不支持的评测内核，`error` 写明请求的内核 | 本机检查，不发请求 |
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
| 内核 | 多语种多题型 | 中文与英文的字词，句子，段落，字母与拼音题，以及中文开放题 `open.eval.cn`，其余内核回调 errId 60003 |
| 中间结果 | `realtime_feedback` 返回中间结果 | `realtime_feedback` 为 YES 时同样以 `eof` 为 0 的 JSON 回调新读到的字或词，结构同声通，分数只表示是否读对，需要 iOS 13，见朗读进度一节 |
| 上传音频 | 可用 Speex 压缩 | 录音与 PCM feed 默认边录边传 16 kHz 单声道 16 位 PCM，`streamUpload` 为 NO 时录音结束后整段上传 WAV，`audioPath` 整段上传 |
| 录音文件 | WAV 或 MP3 | 一律 WAV 编码，`recordName` 写 `.mp3` 时保留原文件名 |
| 证书 | 离线证书 | 不需要证书，证书相关方法返回 YES |
| 音频地址 | `attachAudioUrl` 返回录音的下载地址 | 同样返回，字段为结果 JSON 末尾的 `audioUrl`，平台保留 7 天，到期删除 |
| 鉴权 | 可用 `customized_sig` | 一律按 appKey 与 secretKey 签名，`secretKey` 必填 |
| 结果 JSON | 声通评测字段 | 外层字段一致，`result` 为优谷雅言评测结果，`details[]` 条目补上 `overall` 与 `pronunciation` |
| 并发 | 由引擎决定 | 默认一个引擎同一时间只做一次评测，上一次结果回调之前开始新评测回调 60008。`YuguCompat.maxPendingEvaluations` 大于 0 时一题停录之后即可录下一题，前面的评测在后台进行，见回调规则一节的流水线评测 |
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

`request.coreType` 不受支持，为空或缺少时，`skegn_start` 返回 0，随即在内部队列回调 errId 60003 的错误 JSON，`error` 写明请求的内核，同时打印一行 warn 日志，之后的 `skegn_feed` 与 `skegn_stop` 返回 -1，最近错误码分别为 21 与 24。`YuguCompat.throwOnUnsupportedCoreType` 为 YES 时 `skegn_start` 返回 -1，最近错误码为 60003，打印一行 error 日志，不回调。

朗读进度，边录边传与流水线评测只在 `KYTestEngine` 上提供。C 接口照旧在 `skegn_stop` 之后整段评测，`request` 里的 `realtime_feedback` 与其他成员一样作为表单字段发送，不回调进度。日志级别为 info 时同样每次评测打印一行分段耗时。

## 扩展配置

声通接口以外的配置集中在 `YuguCompat.h`，不改变原有声明。

```objc
#import <STKouyuEngine/YuguCompat.h>

YuguCompat.baseURL = @"https://sandbox.example.com";   // 优先于 KYStartEngineConfig.server，nil 取消
[YuguCompat setLogLevel:YuguCompatLogLevelDebug];      // 优先于 KYStartEngineConfig.logLevel
[YuguCompat setLogHandler:^(NSInteger level, NSString *message) { /* 写入应用自己的日志 */ }];
YuguCompat.maxRetries = 2;      // 一次提交内的重试次数，0 到 5
YuguCompat.totalTimeout = 300;  // 一次评测的总时限，单位秒
YuguCompat.streamUpload = YES;  // 录音与 feed 边录边传，默认 YES，见边录边传一节
YuguCompat.throwOnUnsupportedCoreType = NO; // 不支持的内核改为抛出异常，默认 NO，见不支持的内核一节
YuguCompat.maxPendingEvaluations = 0;       // 录音时最多几次评测在后台等结果，默认 0 为一次一个，见流水线评测一节
NSLog(@"%@ %@", YuguCompat.sdkVersion, YuguCompat.effectiveBaseURL);
```

Swift 用 `import STKouyuEngine.YuguCompat`。`YuguCompat.h` 里的语音合成与报告查询类在 Swift 中去掉 `Yugu` 前缀，例如 `YuguTtsClient` 为 `TtsClient`。

### 语音合成 TtsClient

`YuguTtsClient` 调用优谷雅言语音合成接口 `POST /api/v1/tts/generate`，`2.2.0` 起提供。

1. `clientFromEngine` 使用已初始化引擎的 appKey，secretKey 与平台地址，引擎由 `initEngine` 或 `skegn_new` 初始化，每次调用时读取。没有初始化或已经 `deleteEngine` 时以错误 60007 结束，`localizedDescription` 为“引擎未初始化”。`initWithAppKey:secretKey:` 与 `initWithAppKey:secretKey:baseURL:` 直接指定密钥，`baseURL` 为 nil 时用 `YuguCompat.baseURL`，未设置时用 `https://open.shengzhiai.com`。
2. `YuguTtsRequest` 的字段与接口相同：`text` 必填，`language`，`voice`，`format`，`speed`，`pitch`，`volume`，`sampleRate`，`emotion`，`style`，`pinyin` 与 `genre` 可选，只发送设置过的字段，取值见优谷雅言接口文档。
3. 结果的 `audioUrl` 为平台返回的地址，`absoluteAudioUrl` 为按平台地址补全的完整地址，`/audio/` 开头的地址补为 `https://open.shengzhiai.com/tts/audio/...`。另有时长 `duration`，单位秒，音频格式 `format` 与平台响应的完整 JSON `rawJSON`。
4. 每次调用带一个幂等键 `Idempotency-Key`，重试沿用同一个键，同一个键重复提交时平台返回第一次的结果，不重复计费。`YuguTtsOptions.idempotencyKey` 可以指定业务单号作为键，`timeout` 为单次请求的超时，默认 60 秒。
5. 回调在主线程，`result` 与 `error` 恰有一个不为 nil。错误与日志见语音合成与报告查询的错误一节。

```objc
#import <STKouyuEngine/YuguCompat.h>

YuguTtsRequest *request = [[YuguTtsRequest alloc] initWithText:@"床前明月光"];
request.language = @"zh-CN";
request.voice = @"xiaoyan";
[[YuguTtsClient clientFromEngine] tts:request options:nil completion:^(YuguTtsResult *result, NSError *error) {
    if (result) {
        NSLog(@"%@ %.2f", result.absoluteAudioUrl, result.duration);
    } else {
        NSLog(@"%ld %@", (long)error.code, error.localizedDescription);
    }
}];
```

```swift
import STKouyuEngine.YuguCompat

let request = TtsRequest(text: "床前明月光")
request.language = "zh-CN"
request.voice = "xiaoyan"
TtsClient.fromEngine().tts(request, options: nil) { result, error in
    if let r = result {
        print(r.absoluteAudioUrl ?? "", r.duration)
    } else if let e = error as NSError? {
        print(e.code, e.localizedDescription)
    }
}
```

### 报告查询 ReportClient

`YuguReportClient` 按评测结果 JSON 的 `recordId` 查询网站上展示的 AI 报告，调用 `GET /api/v1/report/{recordId}`，`2.2.0` 起提供。平替包评测的记录属于 appKey 所在的账号，用评测时的同一个 appKey 查询。构造方式与 `YuguTtsClient` 相同。

| reportStatus | 含义 |
|---|---|
| `final` | AI 终稿 |
| `pending` | 即时稿，AI 终稿正在生成，稍后再查 |
| `instant` | 即时稿，AI 终稿不会再来，内容不再变化 |
| `none` | 这次评测没有报告 |

1. 中文与英文的 AI 终稿一般在评测返回后约 4 秒就绪。`YuguReportOptions.waitForFinalMs` 默认 0，只查一次。设为大于 0 的毫秒数时，结果为 `pending` 就每 1000 毫秒再查一次，直到不是 `pending` 或时间用完，返回最后一次的结果，仍为 `pending` 也是正常结果，不是错误。`isFinal` 只在 `final` 时为 YES。第一次查询失败时以该错误结束。之后的某次查询失败时返回最近一次查到的结果，`error` 为 nil，同时打印一行 warn 日志，写明 recordId，错误码与已查询的次数，与安卓平替相同。
2. 结果含 `recordId`，`reportStatus`，`isFinal`，整体点评 `summary`，改进建议 `suggestions`，取自 `score.overall` 的总分 `overall`，报告对象 `report`，评分对象 `score`，声通兼容评测的分段耗时 `timing` 与平台响应 `data` 的完整 JSON `dataJSON`。`report` 另含各维度点评 `dimensions`，维度建议 `dimensionSuggestions` 与朗读技巧观察 `skillInsights` 等，`timing` 保留 7 天。
3. 开放题 `open.eval.cn` 的点评在 `score.feedback`，`reportStatus` 总为 `final`。
4. 查询不带幂等键，按评测相同的规则重试。`timeout` 为单次请求的超时，默认 30 秒。

```objc
YuguReportOptions *options = [[YuguReportOptions alloc] init];
options.waitForFinalMs = 15000;
[[YuguReportClient clientFromEngine] getReport:recordId options:options completion:^(YuguReportResult *result, NSError *error) {
    if (!result) {
        NSLog(@"%ld %@", (long)error.code, error.localizedDescription);
        return;
    }
    NSLog(@"%@ %d %@ %@", result.reportStatus, result.isFinal, result.summary, result.suggestions);
}];
```

```swift
let options = ReportOptions()
options.waitForFinalMs = 15000
ReportClient.fromEngine().getReport("eval_3fb45f4c8e71", options: options) { result, error in
    guard let r = result else { print(error?.localizedDescription ?? ""); return }
    print(r.reportStatus ?? "-", r.isFinal, r.summary ?? "", r.suggestions)
}
```

不支持的内核，语音合成与报告查询的示例与测试一起编译，副本在 `Tests` 目录里。

### 语音合成与报告查询的错误

1. 失败时回调 `NSError`，`domain` 为 `YuguCompatErrorDomain`，`code` 为平台错误码，例如 40001 为评测记录不存在或参数不合法，2001 到 2011 为鉴权错误。网络错误，超时与可重试的服务端错误按评测相同的规则重试，一次调用最多 3 次请求，重试用尽后 `code` 为 20009。
2. 本机检查的错误不发请求：60007 为引擎未初始化，90010 为 `text` 或 `recordId` 为空，密钥为空，幂等键或平台地址不合法。平台响应无法解析时为 90005。
3. `localizedDescription` 为平台消息或本机说明。收到 HTTP 响应时 `userInfo[YuguCompatHTTPStatusKey]` 为 HTTP 状态码。
4. 重试次数与总时限沿用 `YuguCompat.maxRetries` 与 `YuguCompat.totalTimeout`。
5. 每次调用打印一行日志，成功为 info 级别，失败为 warn 级别，含操作，报告查询的 recordId，状态，查询次数，请求次数与毫秒数，例如 `getReport recordId=eval_3fb4 status=final lookups=3 attempts=3 ms=2150`。日志不含 secretKey，签名，音频与报告内容。

## 日志

1. 级别 0 为 error，1 为 warn，2 为 info，3 为 debug，默认 warn，与 `KYLOG_ERROR`，`KYLOG_WARN`，`KYLOG_INFO`，`KYLOG_DEBUG` 的取值相同。
2. `isOutputLog` 为 YES 时写控制台，`sdkLogEnable` 为 YES 时另外写入 `sdkLogPath`，默认 `Documents/sdkLog.txt`。
3. 日志不含 secretKey，签名与音频内容，appKey 只记前 4 位。
4. 应用调用 `KYLog` 的内容写入同一日志。
5. warn 级别下评测成功时不打印日志。需要看评测各步骤耗时时打开 info 级别，见分段耗时一节。

### 打开日志

声通文档里的写法照样生效：`sdkLogEnable` 设为 YES，`logLevel` 设为 2，日志级别为 info，日志写入控制台与 `sdkLogPath`。

```objc
KYStartEngineConfig *config = [[KYStartEngineConfig alloc] init];
config.sdkLogEnable = YES;   // 写入日志文件
config.logLevel = 2;         // info，每次评测打印一行分段耗时
config.sdkLogPath = [NSTemporaryDirectory() stringByAppendingPathComponent:@"sdkLog.txt"]; // 可选，默认 Documents/sdkLog.txt
```

1. 日志文件默认在应用沙盒的 `Documents/sdkLog.txt`，在 Xcode 的 Devices and Simulators 窗口下载应用容器即可取出，模拟器上直接在 `xcrun simctl get_app_container booted <bundle id> data` 给出的目录里查看。
2. `[YuguCompat setLogLevel:YuguCompatLogLevelInfo]` 覆盖 `logLevel`，`setLogHandler:` 把日志交给应用自己的日志系统，交给 handler 时不再写控制台，文件照写。
3. C 接口在 `skegn_new` 的 cfg 里写 `"sdkLog":{"enable":1,"level":2,"output":"<日志文件路径>"}`，`level` 也接受 `error`，`warn`，`info`，`debug` 这几个名称。

### 分段耗时

日志级别为 info 或 debug 时，每次到达平台的评测在结果回调开始之后打印一行 `timing`，字段与安卓平替层相同。此时请求另带 `timing=1`，实时评测的参数帧另带 `"timing":1`，平台在结果里附上平台侧耗时，平替包读取之后从结果中去掉，应用收到的结果 JSON 与 warn 级别时相同。warn，error 与关闭日志时不请求，不打印。

```text
timing tokenId=3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c recordId=eval_77c081e19541 mode=ws audio=31.20s afterStop=1830ms upload=120ms wait=1610ms download=- parse=40ms deliver=3ms handshake=85ms queued=6400B final=1532118B wire=- deflate server.total=1321ms server.engine=1100ms server.platform=221ms
timing tokenId=9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d recordId=eval_3fb45f4c8e71 mode=rest audio=12.30s afterStop=6150ms upload=1884ms wait=4120ms download=85ms parse=40ms deliver=10ms attempts=1 sent=394012B body=1532118B wire=122880B gzip server.total=3910ms server.engine=3700ms server.platform=210ms
```

| 字段 | 含义 |
|---|---|
| `recordId` | 结果的评测记录号，与平台按记录保存的分段耗时对应，错误时为 `-`，`2.2.0` 起提供 |
| `mode` | `ws` 为边录边传，`rest` 为录音结束后整段上传或 `audioPath` 模式，`ws>rest` 为边录边传在结束帧之前失败后整段上传，行末 `fallback` 为失败原因 |
| `audio` | 音频时长，单位秒，压缩格式的音频为 `-` |
| `afterStop` | 从 `stopEngine` 或 VAD，`duration`，300 秒上限结束录音到结果回调开始，`audioPath` 模式从开始评测算起 |
| `upload` | 从结束录音到最后一个字节发出：边录边传为结束帧写出，整段上传为最后一次请求的请求体发完 |
| `wait` | 从上传完成到收到结果的第一个字节。边录边传时系统只交出完整的终评帧，算到终评帧收齐，含下载 |
| `download` | 结果从第一个字节到最后一个字节，边录边传为 `-` |
| `parse` | 解析平台结果，组装回调 JSON |
| `deliver` | 从组装完成到主线程开始执行结果回调 |
| `handshake` | 边录边传时从发起连接到握手完成 |
| `queued` | 结束录音时还没有写出到网络的音频字节数 |
| `final`，`body` | 结果的字节数，按解压之后计 |
| `wire` | 结果在网络上的字节数，压缩时小于 `body`，系统不报告时为 `-` |
| `deflate`，`gzip` | 终评帧协商了 permessage-deflate，整段上传的结果经 gzip 压缩 |
| `attempts`，`connections` | 整段上传的请求次数，边录边传的连接次数，只连接一次时不打印 |
| `sent` | 整段上传的请求体字节数 |
| `server.total`，`server.engine`，`server.platform` | 平台从开始处理到结果备好的总时长，其中调用评测引擎的时长，以及平台其余部分的时长。整段上传取自响应头 `Server-Timing`，边录边传取自终评里的平台耗时，平台没有给出时不打印 |
| `replayed`，`errId` | 平台按 tokenId 重放了已有的结果，评测失败时的 errId |

1. 不明的值记为 `-`。
2. debug 级别另外打印两行：`phases` 为各阶段相对第一个阶段的时刻，`engineStages` 为平台评测引擎各环节的耗时与平台收到请求的时刻。
3. 判断慢在哪一步：`afterStop` 接近 `server.total` 时时间花在平台评测上，`server.engine` 占大头时花在评测引擎上。`upload` 大说明上行慢，结束录音时积压的音频看 `queued`。`wait` 减去 `server.total` 约为网络往返与终评帧的下载。整段上传时 `download` 大说明下行慢。

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

Linux 上的结果截至 2026-10-14，对应 `2.3.0`，沙箱用例截至 2026-10-13，对应 `2.1.2`。macOS 构建机上的结果截至 2026-10-14，对应 `2.3.0`，GitHub Actions 运行 38549911436 的 11 个作业全部通过，swift test 47 项，iOS 模拟器 43 项，各有沙箱用例 1 项跳过，其余全部通过。`2.2.0` 于 2026-10-13 在运行 38397557283 中通过。

| 部分 | 状态 |
|---|---|
| C 核心 | 签名，参数映射，multipart，WAV，VAD，重试判断，错误映射与结果 JSON 组装都在 `Sources/STKouyuEngine/core`。`2.1.0` 的握手地址与签名，参数帧，进度帧解析，`eof` 为 0 的 JSON 组装，按位置去重，以及实时评测的连接，心跳，回退整段评测与结束后重连的判断也在这里。`2.1.1` 的参数帧选项，`Server-Timing` 与平台耗时字段的解析，分段耗时日志的格式化也在这里，平台耗时字段不进结果 JSON 有专门的用例。`2.1.2` 的内核表放开 `open.eval.cn`，开放题的 `refText` 不能由 `refPinyin` 代替，也有专门的用例。`2.2.0` 的语音合成请求体与签名参数，报告路径，补全音频地址，`{code, message, data}` 响应的分类，报告字段，等待终稿的判断，调用日志一行与不支持内核的错误文字也在这里，签名与另行计算的 HMAC 值核对。`2.3.0` 的流水线评测忙碌判断，开关取值的限定，三种日志一行与报告查询的 `partial` 也在这里，忙碌判断与另行写出的规则逐项核对，默认关闭，三题连读，上限，取消与 `audioPath` 几种情形按引擎的记录方式重放。Linux 上用 gcc 13 按 C99 编译零警告，114 组单元测试 14471 项检查全部通过，AddressSanitizer 与 UndefinedBehaviorSanitizer 下同样通过，行覆盖率 99.05%，实时评测，分段耗时，`2.2.0` 与 `2.3.0` 新增部分 100%。2026-10-08 在 macOS 上用 Xcode 15.4 的 clang 跑过 `2.0.0` 的同样步骤，结果一致。按 2026-10-08 录下的平台返回核对，平台返回的 `audioUrl` 原样进入结果 JSON 末尾，单句段落与多句段落的 `details` 补充方式相同。按 2026-10-12 录下的四份进度帧与共享签名向量核对朗读进度 |
| 集成测试 | C 核心按 KYTestEngine 的请求方式连接平台模拟服务 `tools/mock-server/server.mjs`，47 个场景 934 项检查在 Linux 上通过，其中 `2.0.0` 的 21 个场景 2026-10-08 在 macOS 上也通过。`2.1.0` 的 12 个场景经测试用的 WebSocket 客户端跑实时评测：模拟服务按同一算法校验握手签名，进度回调的 `words` 依次拼起来不重不漏，握手被拒，结束前断线与心跳超时时用同一个 tokenId 整段评测，结束时断线，结束后出错与终评超时时重连重放，只计费一次，重试用尽得到 20009，进度不一致与没有进度两种故障，以及短于 1 秒的录音不发结束帧。`2.0.0` 的场景覆盖 500 与 429 重试，读超时与平台处理中两种情形下同一 tokenId 的多次提交只计费一次，409 40901 等待后重试，每次请求换新的 nonce，段落内核的逐字得分与 details 补充，`attachAudioUrl` 作为表单字段发送，400 不重试，autoRetry，签名错误，以及本机错误不发请求。`2.1.1` 的 5 个场景：不带 `realtime_feedback` 的默认边录边传，参数帧不带 `realtime_feedback`，握手被拒时用同一个 tokenId 整段评测，info 级别时请求带 `timing=1` 与 `"timing":1`，模拟服务照常验签，以及连接测试用服务 `core-tests/integration/timing_server.py` 的整段评测与实时评测，核对 `Server-Timing`，平台耗时字段不进结果 JSON 与分段耗时日志的各字段。`2.1.2` 的 1 个场景：`open.eval.cn` 走自己的路径，`refText` 与 `qType` 照原样发送，`refPinyin` 不能代替开放题的 `refText`，结果原样进入结果 JSON，实时评测不回调朗读进度。`2.2.0` 的 7 个场景经 C 核心连接平台模拟服务的语音合成与报告查询接口：请求体只含设置过的字段，签名与另行计算的 HMAC 值一致，模拟服务照常验签，重试沿用同一个幂等键，业务单号作为幂等键时重复提交只计费一次，查询不带幂等键，路径中的 recordId 经过编码，503 与断网后重试，400，401 与不存在的记录不重试，本机检查不发请求，即时稿每隔约 1000 毫秒再查一次，直到终稿或时间用完，第一次查询失败时以该错误结束，之后的查询失败时返回最近一次查到的结果，打印一行 warn 日志。另有用例核对不支持内核的错误文字，C 接口的空内核按未指定处理，以及分段耗时一行的 recordId。`2.3.0` 的 1 个场景连接 `timing_server.py` 的报告查询，平台依次回答 `pending`，`partial` 与 `final`：等待终稿时每隔约 1000 毫秒再查，`partial` 时继续，`final` 时结束，不等待时 `partial` 作为结果返回，`isFinal` 为 0，时间用完时返回最近一次的 `partial`，`instant` 不再查 |
| 沙箱测试 | 设置 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后，`ci/ios-stcompat.sh` 的 sandbox 步骤让同一套 C 核心经 HTTPS 连接真实平台，校验证书与主机名，依次跑八个用例。`sent.eval.cn` 句子评测得到数值型 `result.overall`，结果 JSON 不带 `audioUrl`，`para.eval.cn` 段落评测的 `details` 每项带 `overall` 与 `pronunciation`，带 `attachAudioUrl` 的句子评测在结果 JSON 末尾得到平台返回的 https 下载地址 `audioUrl`，平台不认识的 appKey 得到鉴权类 errId，`pinyin` 题缺 `refPinyin` 得到 40001，两个错误用例都只发一次请求，不触发评测。实时评测用例按实时速度经 wss 上传客户例句，核对进度回调与终评。每次运行最多 8 次平台调用，密钥只经环境变量传给测试程序，不出现在命令行与日志里。没有密钥时这一步记为跳过。2026-10-08 平台更新后实测五个用例 59 项检查全部通过，共 5 次平台调用。2026-10-12 实测实时评测用例 77 项检查通过，平台接受握手签名与参数帧，7 次进度回调依次覆盖 25 个字，每个字一次，终评 78 分，1 次平台调用。`2.1.1` 起评测用例按 info 级别请求平台耗时，另有一个段落按实时速度边录边传的用例。2026-10-13 实测七个用例 165 项检查全部通过，共 7 次平台调用：整段评测的分段耗时一行带 `Server-Timing` 给出的平台耗时，边录边传的段落参数帧不带 `realtime_feedback`，终评带平台耗时字段与 `wsFirstAudioAt`，交给应用的结果 JSON 不带平台耗时字段。`Tests/STKouyuEngineTests/SandboxTests.swift` 经 Objective-C 接口跑其中四个用例与 `2.1.1` 的边录边传段落用例，不含 `attachAudioUrl` 用例，`2.1.0` 的四个用例已在 macOS 与 iOS 模拟器上编译，GitHub 的推送构建不注入沙箱密钥，这些用例在 macOS 上尚未运行。`2.1.2` 新增中文开放题用例，两条参考答案“我是学生”与“我不是学生”用竖线隔开，作答音频为 `zh_open_answer.wav`，核对 `openTaskAudit.refKind` 为 `answers`，总分高于原先的短答封顶，带声通的扁平字段，不带 `words`。2026-10-13 单独实测这个用例 15 项检查通过，1 次平台调用，总分 96。`2.2.0` 没有新增沙箱用例，语音合成与报告查询尚未连接真实平台验证 |
| 头文件 | 与声通公开头文件比对 5 个文件 227 项声明，差异为 0，参数名也一致。`2.2.0` 的新接口都在本包独有的 `YuguCompat.h` 里，`2.3.0` 的两个类属性同样在 `YuguCompat.h` 里，比对结果不变 |
| 包清单 | Linux 上用 Swift `6.0.3` 加载 `Package.swift`，`swift package describe` 识别出全部目标与源文件，没有警告。macOS 上 Swift `5.10` 按同一份清单构建全部目标 |
| Objective-C 层 | 2026-10-12 在 GitHub Actions 的 macos-14 构建机上用 Xcode 15.4 编译，链接，运行 `2.1.0`。swift build 构建 macOS 版，xcodebuild 构建 iOS 真机版与模拟器版，真机版部署目标为 iOS 12，构建日志零警告。Linux 上另用 libclang 18 按 Objective-C ARC 解析全部 `.m` 文件，对照手写的 Apple 接口桩声明做类型检查，22 个编译单元零错误零警告。接口桩为 WebSocket 接口带上 iOS 13 的可用性标注，iOS 12 目标下可用性判断之外的调用会报警告，现为零警告。`2.1.1` 的改动经同样的检查，22 个编译单元零错误零警告，接口桩新增 NSURLSessionTaskMetrics 与会话代理的声明，2026-10-13 在 GitHub Actions 运行 38347556835 中于 macOS 上编译通过。`2.1.2` 同样 22 个编译单元零错误零警告。`2.2.0` 新增 `YuguClients.m`，经同样的检查，24 个编译单元零错误零警告，接口桩新增 NSError 与 NSException 的构造方法与 `NS_SWIFT_NAME`，libclang 按头文件核对 Swift 名的参数个数，尚未在 macOS 上编译。`2.3.0` 的流水线评测与 `partial` 经同样的检查，24 个编译单元零错误零警告，接口桩新增 `HTTPMaximumConnectionsPerHost` 与 NSArray 的几个方法。测试转发器 `YGSTTestGate.m` 加入之后为 26 个编译单元，同样零错误零警告，接口桩另新增 BSD 套接字，`poll` 与 NSCondition 的声明。2026-10-14 在 GitHub Actions 运行 38549911436 中于 macOS 上编译通过 |
| XCTest | `Tests/STKouyuEngineTests` 的 Swift 测试与其调用的 Objective-C 检查 `Tests/STKouyuEngineObjCSupport`。macOS 上 swift test 执行 21 项，20 项通过，沙箱用例 1 项跳过，iOS 模拟器上 xcodebuild test 结果相同，连接平台模拟服务的文件评测，流式评测，取消，skegn 接口与七个实时评测用例在两处都通过。七个实时评测用例 `RealtimeFeedbackTests` 经 Objective-C 接口与 NSURLSessionWebSocketTask 连接平台模拟服务，覆盖进度回调，握手被拒与结束前断线时整段评测，结束后出错时重连重放，进度不一致，没有进度与取消。2026-10-08 测得 `Sources/STKouyuEngine` 的行覆盖率在 macOS 上为 58.50%，在模拟器上为 57.76%，录音器 `YGSTRecorder.m` 需要麦克风，测试没有覆盖。`2.1.1` 新增 `StreamUploadTests` 五项，连接平台模拟服务核对默认边录边传，`streamUpload` 关闭，握手被拒时整段评测，关闭时 `realtime_feedback` 照常边录边传，以及 `sdkLogEnable` 与 `logLevel` 为 2 时日志文件里的分段耗时。另新增 `TimingAndCompressionTests` 三项，只在 macOS 上运行，由测试启动 `timing_server.py`，核对 NSURLSessionWebSocketTask 的压缩协商与解压，NSURLSession 的 gzip 与 NSURLSessionTaskMetrics 分段。2026-10-13 在 GitHub Actions 运行 38347556835 中，swift test 29 项与 iOS 模拟器 26 项全部通过。`2.2.0` 新增 `UnsupportedCoreTypeTests` 四项与 `YuguClientsTests` 五项，核对不支持内核的错误文字，warn 日志与抛出异常，两个客户端连接平台模拟服务的结果与错误，以及 Swift 名，2026-10-13 在运行 38397557283 中通过。`2.3.0` 新增 `PipelinedEvaluationTests` 八项，经 Objective-C 接口连接平台模拟服务。引擎与模拟服务之间隔着测试里的转发器 `YGSTTestGate`，平台的每个结果都先停在转发器里，由用例按步骤放行，各题何时出结果是用例的一步，不靠模拟服务的延迟：默认关闭时两种开始方法都照旧回调 60008，三题连读时结果按放行先后回到各自的 block，tokenId 各自独立，朗读进度只回调到各自的 block，直到各自的终评，上一题的句末进度在下一题开始之后照常送到上一题，上限为 1 时第三题被拒，一题出结果之后可以开始，取消分两种情形核对：结果还在平台时取消，以及结果已经评测完，正在排队等主线程回调时取消，两种情形下都不再回调，`pendingEvaluations` 回到 0，结束帧之前断线的一题用同一个 tokenId 整段评测之后照常回调，`audioPath` 评测计为等结果的评测，另有开关的限定，日志与 Swift 名。`TimingAndCompressionTests` 另新增报告查询的 `partial` 一项。2026-10-14 在 GitHub Actions 运行 38549911436 中，swift test 47 项与 iOS 模拟器 43 项通过，各有沙箱用例 1 项跳过 |
| macOS 构建 | `ci/ios-stcompat-macos.sh` 2026-10-12 在 GitHub Actions 的 macos-14 构建机上全部步骤通过：在 macOS 上重跑 C 核心的各步骤，swift build，iOS 真机与模拟器的 xcodebuild，连接平台模拟服务的 swift test，以及 iOS 模拟器上的 xcodebuild test。设置沙箱密钥时 swift test 另跑沙箱用例。`2.1.1` 于 2026-10-13 在运行 38347556835 中全部通过，`2.2.0` 于 2026-10-13 在运行 38397557283 中通过，`2.3.0` 于 2026-10-14 在运行 38549911436 中全部通过 |
| 压缩 | 整段评测的 gzip 由 NSURLSession 自动协商与解压。边录边传的 permessage-deflate 由 NSURLSessionWebSocketTask 自行提出，出处为 Apple 开发者论坛 https://developer.apple.com/forums/thread/775190 里 Apple 技术支持工程师 2025 年 2 月的答复与抓包，URLSession 的握手带 `Sec-WebSocket-Extensions: permessage-deflate`，系统按压缩上下文解压，应用另设这个请求头时系统不再改写，另见 https://developer.apple.com/forums/thread/654362 。2026-10-13 用同样的握手请求实测平台，1 次平台调用：平台回 `Sec-WebSocket-Extensions: permessage-deflate`，接受压缩的参数帧，音频帧与结束帧，289756 字节的终评帧压缩为 14586 字节，分两帧发送，首帧 8192 字节带 RSV1，续帧不带，沿用压缩上下文。`timing_server.py` 按同样的方式发送终评帧，Linux 上用 Node.js 的 ws 客户端核对过。系统自身的协商与解压由 macOS 上的 `TimingAndCompressionTests` 核对，2026-10-13 在运行 38347556835 中通过，URLSessionWebSocketTask 自行协商 permessage-deflate。沙箱段落用例在 macOS 上尚未运行 |
| 真机 | 录音，回放，系统打断与音频路由切换尚未在真机验证，构建机上的测试不录音，`2.1.1` 的边录边传同样尚未在真机验证 |

## 常见问题

### skegn 符号重复的链接错误

工程里还留着 `libskegn.a`，删除即可。本包已经实现全部 `skegn_*` 函数。

### 开始评测时的 60008 回调

上一次评测还没有回调结果就开始了新评测。等结果回调之后再开始，或者先调用 `cancelEngine`。需要录完一题立即录下一题时打开流水线评测，见回调规则一节，打开之后上一题还在录音或后台等结果的评测多于上限时仍回调 60008。

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

### 收不到朗读进度

依次核对：`realtime_feedback` 为 YES，系统为 iOS 13 或更新版本，评测为录音模式或 16 kHz 16 位单声道 PCM 的 feed 模式，参考文本不为空。条件不满足时平替包照旧整段评测，日志级别为 info 时打印原因。实时连接在结束帧之前失败时进度停止，日志级别为 warn 时打印原因，`stopEngine` 之后照常拿到终评。

### 停止录音之后等待时间长

把日志级别设为 info，按日志一节的分段耗时一行判断慢在哪一步。`mode` 为 `rest` 时核对 `YuguCompat.streamUpload` 是否被设为 NO，系统是否为 iOS 13 或更新版本，feed 的音频是否为 16 kHz 16 位单声道 PCM。`mode` 为 `ws>rest` 时行末的 `fallback` 给出边录边传失败的原因。

## 许可

Apache License 2.0，全文见 `LICENSE`，另见 `NOTICE`。声通与 17kouyu 是其各自权利人的名称，此处仅用于说明接口兼容关系。本包只按公开头文件重新实现，不含声通的实现代码，不分发声通的二进制文件。
