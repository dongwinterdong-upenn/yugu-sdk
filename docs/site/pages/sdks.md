## 制品

```cards
cols: 4
items:
  - { title: Java, icon: dns, href: 'page:sdk-java', desc: 服务端，Java 11 及以上, meta: 'com.shengzhiai.yugu:yugu-java-sdk:2.0.0' }
  - { title: 安卓, icon: android, href: 'page:sdk-android', desc: 安卓 5.0 及以上，Kotlin 编写，Java 可调用, meta: 'com.shengzhiai.yugu:yugu-android-sdk:2.0.0' }
  - { title: iOS, icon: mobile, href: 'page:sdk-ios', desc: 'iOS 13 及以上，Swift `5.9`', meta: 'yugu-ios-sdk.git 2.0.0' }
  - { title: 网页, icon: web, href: 'page:sdk-web', desc: 主流浏览器与 Node.js 18 及以上, meta: '@shengzhiai/yugu-web-sdk@2.0.0' }
  - { title: 微信小程序, icon: apps, href: 'page:sdk-miniprogram', desc: '基础库 `2.20.1` 及以上', meta: '@shengzhiai/yugu-miniprogram-sdk@2.0.0' }
  - { title: 安卓声通平替, icon: swap_horiz, href: 'page:sdk-android-stcompat', desc: '包名 `com.stkouyu`，Java 8 字节码', meta: 'com.shengzhiai.yugu:stkouyu-compat:2.0.0' }
  - { title: iOS 声通平替, icon: swap_horiz, href: 'page:sdk-ios-stcompat', desc: '模块 `STKouyuEngine`，iOS 12 及以上', meta: 'stkouyu-ios-compat.git 2.0.0' }
  - { title: 其他语言, icon: data_object, href: 'page:api-openapi', desc: 按 OpenAPI 描述生成客户端, meta: spec/openapi.yaml }
```

安装写法见[安装](page:installation)，各端最低版本与工具链见[版本兼容](page:compatibility)。

## 接口对照

五端方法同名，参数顺序按各语言习惯排列。

| 能力 | Java | 安卓 | iOS | 网页 | 小程序 |
|---|---|---|---|---|---|
| 原生整段评测 | `evaluate(audio, config)` | `evaluate(config, audio, options, image)` | `evaluate(audio:config:image:options:)` | `evaluate(audio, config, options)` | `evaluate(params, options)` |
| 声通兼容整段评测 | `evaluateCompat(audio, config)` | `evaluateCompat(config, audio, options)` | `evaluateCompat(coreType:audio:params:options:)` | `evaluateCompat(coreType, params, audio, options)` | `evaluateCompat(coreType, params, options)` |
| 语音合成 | `tts(request)` | `tts(request, options)` | `tts(_:options:)` | `tts(request, options)` | `tts(params, options)` |
| 报告查询 | `getReport(recordId)` | `getReport(recordId, options)` | `getReport(recordId:options:)` | `getReport(recordId, options)` | `getReport(recordId, options)` |
| 原生实时评测 | `streamEvaluate(config, listener)` | `streamEvaluate(config, listener, options)` | `streamEvaluate(config:options:listener:)` | `streamEvaluate(config, listener, options)` | `streamEvaluate(params, listener, options)` |
| 声通兼容实时评测 | `streamEvaluateCompat(config, listener)` | `streamEvaluateCompat(config, listener, options)` | `streamEvaluateCompat(coreType:params:options:listener:)` | `streamEvaluateCompat(coreType, params, listener, options)` | `streamEvaluateCompat(coreType, params, listener, options)` |
| 释放 | `close()` | `close()` | `close()` | `close()` | `close()` |

安卓另有 `evaluateAsync`，`evaluateCompatAsync`，`ttsAsync`，`getReportAsync`，返回可取消的 `YuguCall`。iOS 的整段评测，合成与报告查询同时提供 `async` 与完成回调两种写法。Java 的每个方法都有带单次调用选项的重载。

## 能力矩阵

```include
ref: README.md#能力矩阵
```
