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

