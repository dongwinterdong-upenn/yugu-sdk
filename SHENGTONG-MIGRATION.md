# 声通 SDK 平替

已经接入声通 SDK 的项目，换掉声通的依赖即可切换到优谷雅言服务，业务代码不改。安卓与 iOS 各有一个平替层，类名，方法签名，回调，常量值与回调里的结果 JSON 结构都与声通 SDK 一致。

## 平替范围

| 端 | 平替层 | 对齐的声通接口 | 说明 |
|---|---|---|---|
| 安卓 | `com.shengzhiai.yugu:stkouyu-compat:2.0.0`，包名 `com.stkouyu` | `17kouyu_1.0.0.jar` | 接入说明见 `android-stcompat/README.md` |
| iOS | SwiftPM `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，模块名 `STKouyuEngine` | `STKouyuEngine.framework` 公开头文件 | 接入说明见 `ios-stcompat/README.md` |
| 网页与小程序 | 不提供 | 声通网页与小程序 SDK 的接口资料暂缺 | 用 `@shengzhiai/yugu-web-sdk` 与 `@shengzhiai/yugu-miniprogram-sdk` 接入 |
| 服务端 | 不提供 | 声通 HTTP 接口 | 用兼容整段评测接口 `POST /{coreType}` 或 Java SDK |

## 安卓切换步骤

1. 在 `build.gradle` 里删掉 `api files('libs/17kouyu_1.0.0.jar')` 与 jniLibs 下的 `libskegn.so`，`libmp3lamest.so`。
2. 加仓库 `maven { url "https://open.shengzhiai.com/maven/" }` 与依赖 `api "com.shengzhiai.yugu:stkouyu-compat:2.0.0"`。
3. `initEngine` 传入的 appKey 与 secretKey 换成优谷雅言的密钥，联调期间用沙箱密钥。
4. 代码里显式设置过声通服务器地址的，删掉该行或改为 `https://open.shengzhiai.com`，指向声通域名的地址会被自动改指优谷雅言。

## iOS 切换步骤

1. 从工程里移除 `STKouyuEngine.framework` 与 `libskegn.a`。
2. Xcode 添加 Swift Package，地址 `https://open.shengzhiai.com/git/stkouyu-ios-compat.git`，规则 Up to Next Major，起始版本 `2.0.0`。
3. `#import <STKouyuEngine/KYTestEngine.h>` 与 `import STKouyuEngine` 写法不变，只换 appKey 与 secretKey。

## 与声通 SDK 的差异

| 项 | 声通 SDK | 平替层 |
|---|---|---|
| 引擎 | 在线，离线，双引擎 | 只有在线，离线与双引擎参数照收，统一走在线 |
| 语种与题型 | 中，英，法，日，韩等 | 中文与英文的字词，句子，段落，字母，拼音题，其他 coreType 回 errId 60003 |
| 实时中间评分 | realtime_feedback 返回中间结果 | 不返回中间评分，录音中的音强与 VAD 回调照常 |
| 录音文件格式 | WAV 或 MP3 | 一律 WAV 编码，请求 MP3 时文件名保持 `.mp3` 后缀 |
| 授权证书 | 离线证书 | 不需要证书，证书相关方法直接返回成功 |
| 结果 JSON | 声通字段 | 外层字段与声通一致，result 内字段为优谷雅言评测结果，常用取分字段同名 |

## 自行核对

平替层附带比对工具，接入方可以用自己手上的声通包核对接口是否一一对应：

```bash
# 安卓：比对声通 jar 与平替层 jar 的全部公开与受保护成员
tools/api-diff/run.sh /path/to/17kouyu_x.jar /path/to/stkouyu-compat-classes.jar
# iOS：比对声通 framework 头文件与平替层头文件的全部声明
python3 ios-stcompat/tools/headers-diff.py /path/to/STKouyuEngine.framework/Headers ios-stcompat/Sources/STKouyuEngine/include/STKouyuEngine
```

两个工具都在发现缺失或签名不同的成员时以非零状态退出，并列出差异。
