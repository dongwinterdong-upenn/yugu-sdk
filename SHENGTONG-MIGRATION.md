# 声通 SDK 平替

已经接入声通 SDK 的项目，换掉声通的依赖即可切换到优谷雅言服务，业务代码不改。安卓与 iOS 各有一个平替层，类名，方法签名，回调，常量值与回调里的结果 JSON 结构都与声通 SDK 一致。

## 平替范围

| 端 | 平替层 | 对齐的声通接口 | 说明 |
|---|---|---|---|
| 安卓 | `com.shengzhiai.yugu:stkouyu-compat:2.2.0`，包名 `com.stkouyu` | `17kouyu_1.0.0.jar` | 接入说明见 `android-stcompat/README.md` |
| iOS | SwiftPM `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，模块名 `STKouyuEngine` | `STKouyuEngine.framework` 公开头文件 | 接入说明见 `ios-stcompat/README.md` |
| 网页与小程序 | 不提供 | 声通网页与小程序 SDK 的接口资料暂缺 | 用 `@shengzhiai/yugu-web-sdk` 与 `@shengzhiai/yugu-miniprogram-sdk` 接入 |
| 服务端 | 不提供 | 声通 HTTP 接口 | 用兼容整段评测接口 `POST /{coreType}` 或 Java SDK |

## 安卓切换步骤

1. 在 `build.gradle` 里删掉 `api files('libs/17kouyu_1.0.0.jar')` 与 jniLibs 下的 `libskegn.so`，`libmp3lamest.so`。
2. 加仓库 `maven { url "https://open.shengzhiai.com/maven/" }` 与依赖 `api "com.shengzhiai.yugu:stkouyu-compat:2.2.0"`。
3. `initEngine` 传入的 appKey 与 secretKey 换成优谷雅言的密钥，联调期间用沙箱密钥。
4. 代码里显式设置过声通服务器地址的，删掉该行或改为 `https://open.shengzhiai.com`，指向声通域名的地址会被自动改指优谷雅言。

## iOS 切换步骤

1. 从工程里移除 `STKouyuEngine.framework` 与 `libskegn.a`。
2. Xcode 添加 Swift Package，地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，规则 Up to Next Major，起始版本 `2.2.0`。
3. `#import <STKouyuEngine/KYTestEngine.h>` 与 `import STKouyuEngine` 写法不变，只换 appKey 与 secretKey。

## 与声通 SDK 的差异

| 项 | 声通 SDK | 平替层 |
|---|---|---|
| 引擎 | 在线，离线，双引擎 | 只有在线，离线与双引擎参数照收，统一走在线 |
| 语种与题型 | 中，英，法，日，韩等 | 中文与英文的字词，句子，段落，字母，拼音题，以及中文开放题 `open.eval.cn`。其他 coreType 不发请求，回 errId 60003，文案写明是哪个内核，同时记一行警告日志。`2.2.0` 起可以在 `YuguCompat` 里改为启动评测时抛异常 |
| 实时中间结果 | realtime_feedback 打开时录音过程中返回中间结果 | 同样在录音过程中多次回调，结构与声通中间结果一致，细节见下一节。iOS 需要 iOS 13，iOS 12 只回终评 |
| 录音文件格式 | WAV 或 MP3 | 一律 WAV 编码，默认文件名为 `<tokenId>.wav`，显式指定以 `.mp3` 结尾的 recordName 时保留该文件名，内容仍为 WAV |
| 授权证书 | 离线证书 | 不需要证书，证书相关方法直接返回成功 |
| 结果里的录音地址 | attachAudioUrl 打开时返回录音下载地址 | 同样返回，地址保留 7 天，到期删除 |
| 结果 JSON | 声通字段 | 外层字段与声通一致，result 内字段为优谷雅言评测结果，常用取分字段同名 |
| 语音合成与报告查询 | 无 | `2.2.0` 起另带 `TtsClient` 与 `ReportClient`，用初始化引擎时的 appKey 合成示范音，按结果里的 `recordId` 查 AI 点评报告，见两个平替说明的语音合成与报告查询一节 |

## 实时朗读反馈

`2.1.1` 起平替层默认边录边传，停录后只剩最后一小段音频要传。`realtime_feedback` 打开后，录音过程中另外回调 `eof` 为 0 的中间结果，结束后回调一次 `eof` 为 1 的终评。中间结果沿用声通的结构：

```json
{"tokenId": "...", "recordId": "", "eof": 0, "result": {"kernel_version": "1.0.0", "resource_version": "1.0.0",
 "words": [{"charType": 0, "scores": {"pronunciation": 100, "skipped": 0, "tone": 100, "overall": 100, "overall_pron": 100},
            "word": "华", "word_parts": [{"beginIndex": 0, "charType": 0, "endIndex": 0, "part": "华"}]}],
 "readUnits": 19, "matchedUnits": 18, "totalUnits": 25,
 "progress": [{"word": "我", "read": true, "matched": true}, {"word": "民", "read": false, "matched": false}]}}
```

| 项 | 说明 |
|---|---|
| `result.words` | 增量返回，只含这次新读到的字或词，只取参考文本里的内容，每个字只回调一次。没有新读到的字时不回调 |
| 分数 | 只表示识别结果与参考文本是否一致：一致的各项为 100，`skipped` 为 0，不一致的各项为 0，`skipped` 为 1，英文的 `tone` 为 0。发音与声调的实际得分以终评为准 |
| `recordId` | 中间结果里为空串，评测记录编号在终评中给出 |
| `readUnits` | 当前朗读位置，即已读的字数或词数，界面跟随朗读位置时可以直接使用 |
| `progress` | 参考文本每个字或词的朗读状态，`read` 为已读，`matched` 为识别结果一致 |
| 时延 | 进度一般比朗读晚 1 到 2 秒，录音结束后，句尾的字在终评之前送达 |
| 适用范围 | 安卓的 `OnRecorderListener.onScore`，iOS 的结果回调与代理。安卓 `OnRecordListener` 没有中间结果回调，只收终评。音频文件评测仍走整段上传，没有中间结果 |
| 网络 | 需要访问 `wss://open.shengzhiai.com` 的 443 端口。实时连接在结束帧之前失败时，录音结束后改用整段上传出终评，结束帧之后失败时重连重放，最多 2 次 |

## 自行核对

平替层附带比对工具，接入方可以用自己手上的声通包核对接口是否一一对应：

```bash
# 安卓：比对声通 jar 与平替层 jar 的全部公开与受保护成员
tools/api-diff/run.sh /path/to/17kouyu_x.jar /path/to/stkouyu-compat-classes.jar
# iOS：比对声通 framework 头文件与平替层头文件的全部声明
python3 ios-stcompat/tools/headers-diff.py /path/to/STKouyuEngine.framework/Headers ios-stcompat/Sources/STKouyuEngine/include/STKouyuEngine
```

两个工具都在发现缺失或签名不同的成员时以非零状态退出，并列出差异。
