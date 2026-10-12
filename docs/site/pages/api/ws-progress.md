```aside
- title: 帧序列，沙箱实录
  frames: docs/site/data/ws-progress-session.json
- title: 示例代码
  tabs:
    - { label: Node.js, file: docs/site/snippets/ws-progress.mjs, lang: js, title: 直连协议 }
    - { label: Java, ref: 'java/README.md#实时流式评测与断线重连#朗读进度', lang: java }
    - { label: 安卓, ref: 'android/README.md#实时评测与重连#朗读进度', lang: kotlin }
    - { label: iOS, ref: 'ios/README.md#实时评测#朗读进度', lang: swift }
    - { label: 网页, ref: 'web/README.md#实时评测与重连#朗读进度', lang: js }
    - { label: 小程序, ref: 'miniprogram/README.md#实时评测#朗读进度', lang: js }
```

## 打开方式

| 接口 | 打开朗读进度 |
|---|---|
| [原生实时评测](page:api-ws-evaluate) | 开始帧带 `"realtimeFeedback": true` |
| [声通兼容实时评测](page:api-ws-compat) | 参数帧带 `"realtime_feedback": true` |

## 进度帧

```include
ref: CONTRACT.md#5 实时评测#5.5 朗读进度
body: true
shift: -1
```
