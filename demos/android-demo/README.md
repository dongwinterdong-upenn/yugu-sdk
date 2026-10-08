# 优谷雅言 Android 示例工程

独立的 Gradle 工程，通过 Maven 依赖 `com.shengzhiai.yugu:yugu-android-sdk:2.0.0`，不包含 SDK 源码。可直接用 Android Studio 打开，也可在命令行构建。

## 功能

| 按钮 | 流程 |
|---|---|
| “录音”，“停止并评测” | 录音器采集 16 kHz 单声道 PCM，停止后整段评测，展示总分，分项与逐字得分 |
| “载入示例音频并评测” | 读取工程自带的 `assets/sample_zh.wav`，参考文本为“今天天气很好”，不需要麦克风 |
| “开始实时评测”，“结束实时评测” | 边录边送进实时会话，界面显示会话状态，断网时显示重连进度，结束后展示终评结果 |

## 环境

- JDK 17
- Android SDK Platform 34，Build Tools 34
- 运行设备为 Android 5.0 及以上

## 获取

```bash
git clone https://open.shengzhiai.com/git/yugu-sdk.git
cd yugu-sdk/demos/android-demo
```

## 密钥

在 `~/.gradle/gradle.properties` 里写入测试密钥，构建时写进示例应用：

```properties
yuguAppKey=YOUR_APP_KEY
yuguSecretKey=YOUR_SECRET_KEY
```

也可以在命令行传入 `-PyuguAppKey=... -PyuguSecretKey=...`，或在应用界面上填写。沙箱密钥的申请方式见 [`SANDBOX.md`](../../SANDBOX.md)。正式应用不要把 secretKey 打进安装包，改由自己的服务端签发 token。

基址默认为 `https://open.shengzhiai.com`，需要时用 `-PyuguBaseUrl=...` 与 `-PyuguWsBaseUrl=...` 修改。

## 构建与安装

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

首次构建会从 `https://open.shengzhiai.com/maven/`，Google Maven 与 Maven Central 下载依赖。SDK 只从优谷雅言仓库解析，配置在 `settings.gradle.kts` 的 `exclusiveContent` 里。

使用本地构建的 SDK 时，先在 `android` 目录发布到本地目录，再指向该目录：

```bash
cd ../../android
./gradlew :yugu-android-sdk:publishToYuguDir -PyuguPublishDir=/tmp/yugu-maven
cd ../demos/android-demo
./gradlew assembleDebug -PyuguMavenUrl=file:///tmp/yugu-maven
```

## 代码位置

全部逻辑在 `app/src/main/kotlin/com/shengzhiai/yugu/demo/MainActivity.kt`：

- `onCreate` 创建录音器，首次评测时按界面上的密钥创建客户端。
- 整段评测用 `evaluateAsync`，回调在主线程执行，直接更新界面。
- 实时评测用 `streamEvaluate` 加 `recorder.start(session)`，结束时先停录音，再调用 `session.end()`。
- `onDestroy` 释放录音器，取消实时会话，关闭客户端。

## 许可

按 Apache License 2.0 授权，见仓库根目录的 `LICENSE`。
