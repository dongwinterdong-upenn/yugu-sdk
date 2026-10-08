# iOS 演示 App

SwiftUI 演示工程，覆盖录音后整段评测，边录边评的实时评测，以及示例音频评测。工程由 XcodeGen 生成，按仓库地址依赖已发布的 `YuguSDK` 包，不拷贝任何 SDK 源码。

## 环境

| 项 | 要求 |
|---|---|
| macOS | 14 起 |
| Xcode | 15 起 |
| XcodeGen | 2.38 起，用 `brew install xcodegen` 安装 |
| 运行设备 | iOS 15 起的真机或模拟器 |
| 密钥 | 沙箱 appKey 与 secretKey，或 token |

## 运行步骤

1. 克隆 SDK 总仓库，进入演示目录。

   ```bash
   git clone https://open.shengzhiai.com/git/yugu-sdk.git
   cd yugu-sdk/demos/ios-demo
   ```

2. 编辑 `YuguDemo/Config.xcconfig`，在 `YUGU_APP_KEY` 与 `YUGU_SECRET_KEY` 后填写沙箱密钥，或在 `YUGU_TOKEN` 后填写 token。沙箱密钥的申请方式见总仓库的 `SANDBOX.md`。

3. 生成工程，然后打开。

   ```bash
   xcodegen generate
   open YuguDemo.xcodeproj
   ```

   Xcode 打开工程时自动拉取 `https://open.shengzhiai.com/git/yugu-ios-sdk.git` 的 `2.0.0` 版本。

4. 在 YuguDemo 目标的 Signing and Capabilities 页选择开发团队，选择模拟器或真机，点击运行。

5. 首次录音时允许麦克风权限。模拟器使用 Mac 的麦克风。

## 功能

| 功能 | 操作 |
|---|---|
| 整段评测 | 方式选“整段评测”，点“开始录音”，读完点“停止并评测”，界面显示总分，各维度，逐字分数与警告 |
| 实时评测 | 方式选“实时评测”，点“开始录音”，音频按 640 字节分帧边录边传，点“停止并评测”后显示终评，状态区显示会话状态与重连事件 |
| 示例音频评测 | 点“示例音频评测”，评测工程自带的 `sample_zh.wav`，不需要麦克风 |
| 取消 | 录音或评测过程中点“取消”，录音与会话立即结束 |

## 代码位置

| 文件 | 内容 |
|---|---|
| `YuguDemo/DemoModel.swift` | 创建客户端与录音器，整段评测，实时会话，错误展示，释放 |
| `YuguDemo/ContentView.swift` | 界面 |
| `YuguDemo/Config.xcconfig` | 基址与密钥 |
| `project.yml` | XcodeGen 工程描述 |

## 使用本地 SDK 源码

把 `project.yml` 中 `packages` 下的 `url` 与 `from` 两行换成 `path: ../../ios`，再执行一次 `xcodegen generate`。
