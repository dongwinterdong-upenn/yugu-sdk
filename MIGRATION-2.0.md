# 由 1 版本升级到 2 版本

SDK `2.0.0` 统一了品牌，包名与仓库坐标，补齐幂等，重试，实时评测断线重连，错误分类，资源释放与状态查询。接口 v1 不变，`1.x` 版本 SDK 发出的请求在升级后的平台上照常工作。

## 坐标与包名

| 端 | `1.x` | `2.0` |
|---|---|---|
| Java | `tech.dragonai.yugu:yugu-java-sdk:1.0.0`，手工下载 tgz | `com.shengzhiai.yugu:yugu-java-sdk:2.0.0`，仓库 `https://open.shengzhiai.com/maven/` |
| 安卓 | 源码目录 `tech.dragonai.yugu` | `com.shengzhiai.yugu:yugu-android-sdk:2.0.0`，同一仓库 |
| 网页 | `@yugu/web-sdk`，手工下载 tgz | `@shengzhiai/yugu-web-sdk@2.0.0`，npm 源 `https://open.shengzhiai.com/npm/` |
| 小程序 | 源码目录 | `@shengzhiai/yugu-miniprogram-sdk@2.0.0`，同一 npm 源 |
| iOS | 源码目录 | SwiftPM `https://open.shengzhiai.com/git/yugu-ios-sdk.git` `2.0.0` |

代码里的 `import tech.dragonai.yugu.*` 全部换成 `import com.shengzhiai.yugu.*`。默认基址由 `https://ygyx.dragonai.tech` 改为 `https://open.shengzhiai.com`，两个域名指向同一平台，显式配置过 `baseUrl` 的接入方不受影响。

## 行为变化

| 项 | `1.x` | `2.0` |
|---|---|---|
| 幂等 | 不带幂等键，弱网重发会重复计费 | 写操作自动带幂等键，重试与重连复用同一个键 |
| 重试 | 不重试，网络抖动直接失败 | 默认重试 2 次，指数退避加随机抖动，只对可重试错误生效 |
| 实时评测断线 | 静默失败或直接报错 | 自动重连，整段重放，状态与事件回调齐全 |
| 错误 | 单一异常类型，只带 HTTP 状态与错误码 | 十六个类别，每类一个异常类型，带可重试判定 |
| 资源 | 没有统一释放接口 | 客户端，会话，录音器都有可重复调用的释放接口与状态查询 |
| 预检 | 没有 | 上传前检查时长，静音，音量与格式，默认只告警 |
| 小程序原生评测 | 恒报 HTTP 415 | 已修复 |
| 签名 | 安卓只签部分 config 字段，iOS 小数字段签名与发送不一致 | 五端统一签名口径，跨端向量全部命中 |
| 网页实时直连引擎 | `streamRealtime` 直连内部引擎地址 | 已移除，实时评测统一走 `streamEvaluate` 与 `streamEvaluateCompat` |

## 升级步骤

1. 按上表改依赖坐标，删除手工拷贝进项目的 `1.x` 源码。
2. 全局替换包名或模块名。
3. 编译，按各端 CHANGELOG 的破坏性变更一栏调整少量改名的方法与参数。
4. 有自行实现重试的地方，改为使用 SDK 的重试策略，避免两层重试叠加。
5. 用沙箱密钥跑一遍整段评测与实时评测，沙箱见 `SANDBOX.md`。
