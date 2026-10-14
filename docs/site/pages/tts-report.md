## 语音合成

把一段文本合成为示范音，返回音频地址与时长，常用于跟读题的标准音。中文音色取 `xiaoyan` 女声或 `xiaofeng` 男声，英文取 `female` 或 `male`，格式取 `mp3`，`wav`，`ogg`，语速，音调，音量取值 0 到 100，默认 50。

```tabs
items:
  - { label: Java, file: docs/site/snippets/java/TtsAndReport.java, region: tts, lang: java }
  - { label: 网页, file: docs/site/snippets/tts-report.mjs, region: tts, lang: js }
  - { label: 安卓平替, ref: 'android-stcompat/README.md#语音合成与报告#语音合成', lang: java }
  - { label: iOS 平替, ref: 'ios-stcompat/README.md#扩展配置#语音合成 TtsClient', lang: objc }
  - { label: cURL, file: docs/site/snippets/tts.sh, lang: bash }
```

| 端 | 方法 | 可直接播放的地址 |
|---|---|---|
| Java | `tts(TtsRequest)` | `TtsResult.getAbsoluteUrl()` |
| 安卓 | `tts(request, options)`，异步 `ttsAsync` | `resolvedUrl` |
| iOS | `tts(_:options:)`，另有 `async` 写法 | `absoluteAudioUrl` |
| 网页 | `tts(request, options)` | `absoluteUrl` |
| 小程序 | `tts(params, options)` | `fullUrl`，可直接交给 `InnerAudioContext` |
| 安卓平替 | `TtsClient.create().tts(request, options)`，异步 `ttsAsync`，`2.2.0` 起 | `getAbsoluteUrl()` |
| iOS 平替 | `[[YuguTtsClient clientFromEngine] tts:options:completion:]`，Swift 为 `TtsClient`，`2.2.0` 起 | `absoluteAudioUrl` |

语音合成是写操作，带幂等键，重试不会重复计费。接口定义见[语音合成](page:api-tts)。

## 报告查询

按 `recordId` 查询一次评测的报告，只能查询本账号的记录，记录不存在时回 HTTP 400，错误码 40001。返回的 `data.score` 为评测分数，`data.report` 为自然语言点评，改进建议与维度分，`data.reportStatus` 说明是不是 AI 终稿。声通兼容接口的评测 `2026-10-13` 起同样可查，`data.timing` 另有这条评测的分段耗时。

```tabs
items:
  - { label: Java, file: docs/site/snippets/java/TtsAndReport.java, region: report, lang: java }
  - { label: 网页, file: docs/site/snippets/tts-report.mjs, region: report, lang: js }
  - { label: 安卓平替, ref: 'android-stcompat/README.md#语音合成与报告#报告查询', lang: java }
  - { label: iOS 平替, ref: 'ios-stcompat/README.md#扩展配置#报告查询 ReportClient', lang: objc }
  - { label: cURL, file: docs/site/snippets/report.sh, lang: bash }
```

| 字段 | 含义 |
|---|---|
| `data.score.overall` | 总分，与评测结果的 `result.overall` 一致 |
| `data.report.summary` | 整体点评 |
| `data.report.suggestions` | 改进建议，逐条给出 |
| `data.report.dimensionScores` | 维度分，键随语种与题型不同，值为 null 表示本次不评该维 |
| `data.reportStatus` | `final` 为 AI 终稿，`pending` 为终稿生成中，`instant` 为终稿不会再来，`none` 为没有报告 |
| `data.timing` | 声通兼容评测的分段耗时，保留 7 天 |

AI 终稿在评测返回之后生成，中文与英文一般约 4 秒。两个平替的 `ReportClient` 设 `waitForFinalMs` 时会每秒再查一次，直到不再是 `pending` 或时间用完。报告查询天然幂等，SDK 照常重试。评测时设置 `includeReport` 会生成 AI 报告，评测结果的 `report` 另含整体点评与改进建议。接口定义见[报告查询](page:api-report)。
