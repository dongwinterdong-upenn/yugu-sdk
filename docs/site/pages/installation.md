## 制品与仓库

全部制品发布在 `open.shengzhiai.com`，仓库匿名只读，不需要账号，令牌与证书。已发布的版本不再改动，依赖写成具体版本号即可锁定。

| 端 | 制品 | 仓库 |
|---|---|---|
| 服务端 Java | `com.shengzhiai.yugu:yugu-java-sdk:2.0.0` | Maven `https://open.shengzhiai.com/maven/` |
| 安卓 | `com.shengzhiai.yugu:yugu-android-sdk:2.0.0` | 同上 |
| 安卓声通平替 | `com.shengzhiai.yugu:stkouyu-compat:2.0.0` | 同上 |
| 网页 | `@shengzhiai/yugu-web-sdk@2.0.0` | npm `https://open.shengzhiai.com/npm/` |
| 微信小程序 | `@shengzhiai/yugu-miniprogram-sdk@2.0.0` | 同上 |
| iOS | 包 `yugu-ios-sdk`，产品 `YuguSDK`，标签 `2.0.0` | SwiftPM `https://open.shengzhiai.com/git/yugu-ios-sdk.git` |
| iOS 声通平替 | 包 `stkouyu-ios-compat`，模块 `STKouyuEngine`，标签 `2.0.0` | SwiftPM `https://open.shengzhiai.com/git/stkouyu-ios-compat.git` |

## 添加依赖

### 服务端 Java

```tabs
sync: build-jvm
items:
  - { label: Maven, ref: 'java/README.md#安装', lang: xml, title: pom.xml }
  - { label: Gradle, ref: 'java/README.md#安装', lang: groovy, title: build.gradle }
  - { label: Gradle Kotlin, ref: 'java/README.md#安装', lang: kotlin, title: build.gradle.kts }
```

运行时依赖只有 `jackson-databind` 2.17，从 Maven Central 获取，HTTP 与 WebSocket 使用 JDK 自带的 `java.net.http`。

### 安卓

```tabs
sync: build-jvm
items:
  - label: Gradle
    lang: groovy
    parts:
      - { ref: 'android/README.md#安装', n: 1 }
      - { ref: 'android/README.md#安装', n: 2 }
  - { label: Gradle Kotlin, ref: 'android/README.md#安装', lang: kotlin }
```

第一段写在 `settings.gradle`，第二段写在模块的 `build.gradle`。工程仍在顶层 `build.gradle` 的 `allprojects` 里声明仓库时，把同一行仓库地址加进那里。SDK 自带 R8 混淆保留规则，录音需要的 `RECORD_AUDIO` 权限见[安卓 SDK](repo:android/README.md#权限)。

### iOS

```tabs
sync: build-apple
items:
  - { label: SwiftPM, ref: 'ios/README.md#安装#Package.swift 声明', lang: swift, title: Package.swift }
  - { label: CocoaPods, ref: 'ios/README.md#安装#CocoaPods 方式', lang: ruby, title: Podfile }
```

Xcode 里选择 File 菜单的 Add Package Dependencies，输入仓库地址，Dependency Rule 选择 Up to Next Major Version，版本填 `2.0.0`，产品勾选 `YuguSDK`。录音需要在 `Info.plist` 声明 `NSMicrophoneUsageDescription`。

### 网页

```tabs
sync: build-web
items:
  - label: npm
    lang: bash
    parts:
      - { ref: 'web/README.md#安装#npm 仓库安装', lang: ini }
      - { ref: 'web/README.md#安装#npm 仓库安装', lang: bash }
  - { label: script 标签, ref: 'web/README.md#安装#script 标签引入', lang: html }
```

第一行写进项目根目录的 `.npmrc`。`pnpm` 与 Yarn 1 读取同一个 `.npmrc`，Yarn 2 及以上版本在 `.yarnrc.yml` 的 `npmScopes` 里配置同一个地址。包同时提供 ESM 构建与 UMD 构建，`package.json` 的 `exports` 按导入方式自动选择。

### 微信小程序

```snippet
ref: miniprogram/README.md#安装
lang: bash
```

安装后在微信开发者工具里选择工具菜单的构建 npm，评测与实时评测的域名要登记为合法域名，见[小程序 SDK](repo:miniprogram/README.md#合法域名)。

### 声通平替

已接入声通 SDK 的工程只换依赖，不改业务代码，删除声通文件与替换依赖的步骤见[从声通迁移](page:migrate-shengtong)。

## 版本固定与校验

| 仓库 | 锁定版本 | 校验 |
|---|---|---|
| Maven | 依赖写成具体版本号 | 每个文件附带 `.md5`，`.sha1`，`.sha256`，`.sha512`，另有 `-sources.jar` 与 `-javadoc.jar` |
| npm | `npm install` 写入 `package-lock.json` | 包元数据带 `sha512` 完整性值，npm 安装时自动核对 |
| SwiftPM | 每个版本对应一个 Git 标签，`exact: "2.0.0"` 固定 | `Package.resolved` 记录标签对应的提交 |

回退时把版本号改回历史版本。`1.x` 版本没有发布到制品仓库，已停止维护，升级方法见[升级到 2.0](page:migrate-v2)。
