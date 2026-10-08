# 优谷雅言语音评测 SDK

优谷雅言语音评测服务的官方 SDK，覆盖服务端 Java，安卓，iOS，网页，微信小程序五端，另有安卓与 iOS 两个声通 SDK 平替层。SDK 为推荐接入方式，没有 SDK 的语言按 `CONTRACT.md` 直连 REST 与 WebSocket，或按 `spec/openapi.yaml` 生成客户端。

| 项 | 地址 |
|---|---|
| 当前版本 | `2.0.0`，2026-10-08 |
| 服务基址 | `https://open.shengzhiai.com`，`wss://open.shengzhiai.com` |
| 源码仓库 | `https://open.shengzhiai.com/git/yugu-sdk.git`，只读，匿名可克隆 |
| Maven 仓库 | `https://open.shengzhiai.com/maven/` |
| npm 源 | `https://open.shengzhiai.com/npm/` |
| SwiftPM 仓库 | `https://open.shengzhiai.com/git/yugu-ios-sdk.git`，`https://open.shengzhiai.com/git/stkouyu-ios-compat.git` |
| 持续集成 | `https://open.shengzhiai.com/sdk/ci/` |
| 在线文档 | `https://open.shengzhiai.com/sdk/v2/` |
| 许可 | Apache-2.0 |

制品仓库全部匿名只读，不需要账号与认证。已发布的版本不会被覆盖，任一历史版本都可以按版本号重新拉取。

## 安装

### 服务端 Java

```xml
<repositories>
  <repository>
    <id>shengzhiai</id>
    <url>https://open.shengzhiai.com/maven/</url>
  </repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>com.shengzhiai.yugu</groupId>
    <artifactId>yugu-java-sdk</artifactId>
    <version>2.0.0</version>
  </dependency>
</dependencies>
```

### 安卓

```groovy
repositories { maven { url "https://open.shengzhiai.com/maven/" } }
dependencies { implementation "com.shengzhiai.yugu:yugu-android-sdk:2.0.0" }
```

### 安卓声通平替

```groovy
repositories { maven { url "https://open.shengzhiai.com/maven/" } }
dependencies { api "com.shengzhiai.yugu:stkouyu-compat:2.0.0" }
```

### 网页

```bash
echo "@shengzhiai:registry=https://open.shengzhiai.com/npm/" >> .npmrc
npm install @shengzhiai/yugu-web-sdk@2.0.0
```

### 微信小程序

```bash
echo "@shengzhiai:registry=https://open.shengzhiai.com/npm/" >> .npmrc
npm install @shengzhiai/yugu-miniprogram-sdk@2.0.0
```

安装后在微信开发者工具里执行构建 npm。

### iOS

```swift
.package(url: "https://open.shengzhiai.com/git/yugu-ios-sdk.git", from: "2.0.0")
```

### iOS 声通平替

```swift
.package(url: "https://open.shengzhiai.com/git/stkouyu-ios-compat.git", from: "2.0.0")
```

各端的初始化，五分钟上手，接口说明，错误处理，生命周期示例见各端目录的 README.md。

## 能力矩阵

| 能力 | Java | 安卓 | iOS | 网页 | 小程序 | 安卓声通平替 | iOS 声通平替 |
|---|---|---|---|---|---|---|---|
| 原生整段评测 | 支持 | 支持 | 支持 | 支持 | 支持 | 不涉及 | 不涉及 |
| 声通兼容整段评测 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 语音合成 | 支持 | 支持 | 支持 | 支持 | 支持 | 不涉及 | 不涉及 |
| 报告查询 | 支持 | 支持 | 支持 | 支持 | 支持 | 不涉及 | 不涉及 |
| 原生实时评测 | 支持 | 支持 | 支持，需 iOS 13 | 支持 | 支持 | 不涉及 | 不涉及 |
| 声通兼容实时评测 | 支持 | 支持 | 支持，需 iOS 13 | 支持 | 支持 | 不涉及，录完整段上传 | 不涉及，录完整段上传 |
| 实时评测断线重连 | 支持 | 支持 | 支持 | 支持 | 支持 | 不涉及 | 不涉及 |
| 实时中间评分 | 不支持 | 不支持 | 不支持 | 不支持 | 不支持 | 不支持 | 不支持 |
| 内置录音 | 不涉及 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 外部音频注入 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持，feed 与音频文件 | 支持，feedAudioData 与 audioPath |
| 幂等键自动注入 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 自动重试 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 错误分类与可重试判定 | 支持 | 支持 | 支持 | 支持 | 支持 | errId | errId |
| 资源释放与状态查询 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 音频预检 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 日志级别与指标回调 | 支持 | 支持 | 支持 | 支持 | 支持 | 日志级别 | 日志级别 |
| TypeScript 声明 | 不涉及 | 不涉及 | 不涉及 | 支持 | 支持 | 不涉及 | 不涉及 |
| 离线评测 | 不支持 | 不支持 | 不支持 | 不支持 | 不支持 | 不支持，统一在线 | 不支持，统一在线 |
| 私有音色管理 | 不支持，控制台功能 | 不支持 | 不支持 | 不支持 | 不支持 | 不涉及 | 不涉及 |

实时中间评分指录音过程中逐字返回分数。平台的声通兼容实时评测在 `realtime_feedback` 打开时只回进度帧，不含评分，终评在结束后返回。

## 文档

| 文档 | 内容 |
|---|---|
| `CONTRACT.md` | 接口契约：鉴权，签名，整段评测，实时评测，心跳，断线重连，幂等，重试 |
| `RESULTS.md` | 各评测模式的取分字段 |
| `ERRORS.md` | 错误码，警告码，本地错误码，声通平替 errId |
| `SANDBOX.md` | 沙箱环境与测试密钥 |
| `COMPATIBILITY.md` | 版本兼容矩阵 |
| `CHANGELOG.md` | 变更记录 |
| `MIGRATION-2.0.md` | 由 1 版本升级 |
| `SHENGTONG-MIGRATION.md` | 声通 SDK 平替 |
| `spec/openapi.yaml` | OpenAPI 3.0 描述，供其他服务端语言生成客户端 |

## 目录

| 目录 | 内容 |
|---|---|
| `java/` | 服务端 Java SDK，Maven 工程 |
| `android/` | 安卓 SDK，Gradle 工程 |
| `android-stcompat/` | 安卓声通平替，Gradle 工程 |
| `web/` | 网页 SDK，npm 包 |
| `miniprogram/` | 小程序 SDK，npm 包 |
| `ios/` | iOS SDK，SwiftPM 包 |
| `ios-stcompat/` | iOS 声通平替，SwiftPM 包 |
| `demos/` | 各端可独立运行的演示工程 |
| `spec/` | 错误码表，OpenAPI 描述，跨端测试样例 |
| `tools/` | 平台模拟服务，接口比对工具，发布脚本，持续集成 |
| `ci/` | 构建与测试脚本，`ci/run-all.sh` 一键跑全部 |

## 构建与测试

```bash
git clone https://open.shengzhiai.com/git/yugu-sdk.git && cd yugu-sdk
bash ci/run-all.sh            # 全部步骤
bash ci/run-all.sh java web   # 指定步骤
```

各端也可以单独执行 `mvn test`，`./gradlew test`，`npm test`，`swift test`，命令见各端 README。集成测试默认连仓库自带的平台模拟服务，不连生产。设置沙箱密钥环境变量后另跑一组联调测试，见 `SANDBOX.md`。

## 许可

Apache-2.0，见 `LICENSE` 与 `NOTICE`。
