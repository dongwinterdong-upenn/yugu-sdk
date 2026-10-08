```hero
eyebrow: 优谷雅言开放平台
version: 2.0.0
title: 语音评测 SDK
lead: 五端 SDK 与两个声通平替层，覆盖整段评测，实时评测，语音合成与报告查询。写操作自动带幂等键，网络抖动时按策略重试，实时评测断线后整段重放，重试与重连都不会重复计费。
actions:
  - { label: 快速开始, href: 'page:quickstart', primary: true }
  - { label: 接口参考, href: 'page:api' }
  - { label: 从声通迁移, href: 'page:migrate-shengtong' }
facts:
  - { k: 当前版本, v: 2.0.0 }
  - { k: 服务基址, v: open.shengzhiai.com }
  - { k: 许可, v: Apache-2.0 }
code:
  - label: Java
    ref: java/README.md#五分钟快速开始
    lang: java
    lines: 10-22
    title: QuickStart.java
  - label: 安卓
    lang: kotlin
    parts:
      - { ref: android/README.md#五分钟快速开始, n: 1 }
      - { ref: android/README.md#五分钟快速开始, n: 2 }
  - label: iOS
    ref: ios/README.md#五分钟上手
    lang: swift
    lines: 1-16
  - label: 网页
    ref: web/README.md#五分钟快速开始
    lang: js
    title: quickstart.mjs
  - label: 小程序
    lang: js
    parts:
      - { ref: miniprogram/README.md#五分钟上手, n: 1, lines: 1-6 }
      - { ref: miniprogram/README.md#五分钟上手, n: 2 }
  - label: cURL
    file: docs/site/snippets/evaluate.sh
    lang: bash
```

## 平台

```cards
cols: 4
items:
  - { title: Java, icon: dns, href: 'page:sdk-java', desc: 服务端评测与批量任务，Java 11 及以上, meta: 'com.shengzhiai.yugu:yugu-java-sdk:2.0.0' }
  - { title: 安卓, icon: android, href: 'page:sdk-android', desc: 内置录音器，Kotlin 与 Java 调用，安卓 5.0 及以上, meta: 'com.shengzhiai.yugu:yugu-android-sdk:2.0.0' }
  - { title: iOS, icon: mobile, href: 'page:sdk-ios', desc: Swift 并发与回调两种写法，iOS 13 及以上, meta: 'yugu-ios-sdk.git 2.0.0' }
  - { title: 网页, icon: web, href: 'page:sdk-web', desc: 浏览器与 Node.js，附 TypeScript 声明, meta: '@shengzhiai/yugu-web-sdk@2.0.0' }
  - { title: 微信小程序, icon: apps, href: 'page:sdk-miniprogram', desc: '小程序录音器直连实时评测，基础库 `2.20.1` 及以上', meta: '@shengzhiai/yugu-miniprogram-sdk@2.0.0' }
  - { title: 安卓声通平替, icon: swap_horiz, href: 'page:sdk-android-stcompat', desc: '包名 `com.stkouyu` 与声通一致，换依赖即可切换', meta: 'com.shengzhiai.yugu:stkouyu-compat:2.0.0' }
  - { title: iOS 声通平替, icon: swap_horiz, href: 'page:sdk-ios-stcompat', desc: '模块名 `STKouyuEngine` 与声通一致，头文件逐项对齐', meta: 'stkouyu-ios-compat.git 2.0.0' }
  - { title: 其他语言, icon: data_object, href: 'page:api-openapi', desc: 按 OpenAPI 描述生成客户端，补上签名头即可调用, meta: spec/openapi.yaml }
```

## 评测能力

```cards
cols: 4
items:
  - { title: 整段评测, icon: graphic_eq, href: 'page:evaluate', desc: 录完整段上传，一次请求返回总分，维度分与逐字详情, meta: 'POST /api/v1/evaluate' }
  - { title: 实时评测, icon: stream, href: 'page:streaming', desc: 边录边传，发出结束帧后返回终评，断线自动重连, meta: 'WSS /api/v1/ws/evaluate' }
  - { title: 语音合成, icon: record_voice_over, href: 'page:tts-report', desc: 中文与英文示范音，返回可播放的音频地址, meta: 'POST /api/v1/tts/generate' }
  - { title: 报告查询, icon: assignment, href: 'page:tts-report', desc: '按 `recordId` 查询评测报告与维度分析', meta: 'GET /api/v1/report/{recordId}' }
```

## 生产可靠性

```facts
- icon: fingerprint
  k: 幂等键
  v: 写操作自动生成 32 位十六进制幂等键，重试与重连复用同一个键，平台按键只评测一次，只计费一次。
  href: 'page:api-idempotency'
  link: 幂等语义
- icon: replay
  k: 自动重试
  v: 默认重试 2 次，指数退避加随机抖动，遵守 Retry-After，只对可重试的错误生效。
  href: 'page:retries'
  link: 重试策略
- icon: sync
  k: 断线重连
  v: 实时评测断线后最多连续重连 8 次，重放这一轮的全部音频，评分覆盖整段录音。
  href: 'page:streaming'
  link: 实时评测
- icon: bug_report
  k: 错误分类
  v: 十六个错误类别，每类一个异常类型，统一错误码表与公开的可重试判定。
  href: 'page:errors-guide'
  link: 错误处理
```

## 参考资料

```cards
cols: 4
items:
  - { title: 接口参考, icon: api, href: 'page:api', desc: REST 与 WebSocket 接口，签名，幂等与重试的完整契约 }
  - { title: 评测结果字段, icon: data_object, href: 'page:api-results', desc: 各评测模式的总分字段，逐字逐句与开放题字段 }
  - { title: 错误码, icon: error, href: 'page:api-errors', desc: 服务端错误码，音频质量警告码与 SDK 本地错误码 }
  - { title: 沙箱环境, icon: science, href: 'page:sandbox', desc: 联调密钥每天 200 次，不扣套餐与余额 }
```
