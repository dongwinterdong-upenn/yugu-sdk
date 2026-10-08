# 版本兼容矩阵

## SDK 与接口版本

| SDK 版本 | 发布日期 | 接口版本 | 契约版本 | 状态 |
|---|---|---|---|---|
| `2.0.0` | 2026-10-08 | v1 | `2.0` | 当前版本 |
| `1.0.0` | 2026-06-24 | v1 | `1.x` | 停止维护，`2.0` 起改包名与坐标，迁移见 `MIGRATION-2.0.md` |

接口 v1 在 SDK `2.0.0` 发布时新增幂等，心跳与错误码细分，全部向后兼容，`1.0.0` 版本 SDK 仍可调用。

## 各端最低版本

| 端 | 制品 | 语言与工具链 | 运行环境最低版本 |
|---|---|---|---|
| 服务端 Java | `com.shengzhiai.yugu:yugu-java-sdk:2.0.0` | Java 11 字节码，构建用 JDK 17 与 Maven 3.8 | JDK 11 |
| 安卓 | `com.shengzhiai.yugu:yugu-android-sdk:2.0.0` | Kotlin 1.9，AGP 8.5，Java 8 字节码 | Android 5.0，API 21 |
| 安卓声通平替 | `com.shengzhiai.yugu:stkouyu-compat:2.0.0` | Java 8 字节码，无第三方依赖 | Android 5.0，API 21 |
| 网页 | `@shengzhiai/yugu-web-sdk@2.0.0` | ES2018，附 TypeScript 声明 | Chrome 70，Edge 79，Firefox 68，Safari 13，Node 18 |
| 微信小程序 | `@shengzhiai/yugu-miniprogram-sdk@2.0.0` | ES2017 CommonJS，附 TypeScript 声明 | 基础库 `2.20.1` |
| iOS | SwiftPM `https://open.shengzhiai.com/git/yugu-ios-sdk.git` `2.0.0` | Swift `5.9`，Xcode 15 | iOS 13，macOS 11，YuguCore 另支持 Linux |
| iOS 声通平替 | SwiftPM `https://open.shengzhiai.com/git/stkouyu-ios-compat.git` `2.0.0` | Objective-C | iOS 12 |

## 声通接口对应版本

| 平替层 | 对齐的声通接口 |
|---|---|
| 安卓 `com.stkouyu` | `17kouyu_1.0.0.jar`，`Build.VERSION` 为 `1.0.62` 的公开版本 |
| iOS `STKouyuEngine` | 公开头文件 `KYTestEngine.h`，`KYStartEngineConfig.h`，`KYTestConfig.h` |

接入方手上的声通包版本不同时，用平替层附带的比对工具核对，见 `SHENGTONG-MIGRATION.md`。

## 版本规则

版本号分主版本，次版本，修订号三段。修订号只修缺陷，次版本新增能力，同时保持兼容，接口或行为出现不兼容变化时升主版本，同时在 `CHANGELOG.md` 的破坏性变更一栏写明迁移方法。已发布的版本不会被覆盖，任何历史版本都可以按坐标重新拉取。
