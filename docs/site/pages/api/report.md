```endpoint
method: GET
path: '/api/v1/report/{recordId}'
```

```aside
- title: 请求示例
  tabs:
    - { label: cURL, file: docs/site/snippets/report.sh, lang: bash }
    - { label: Java, file: docs/site/snippets/java/TtsAndReport.java, region: report, lang: java }
    - { label: 网页, file: docs/site/snippets/tts-report.mjs, region: report, lang: js }
- title: 返回示例
  fixture: docs/site/data/report-example.json
  label: 200，节选
```

```include
ref: CONTRACT.md#4 语音合成与报告#4.2 报告查询
```

## 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `recordId` | string | 是 | 评测返回的记录号，例如 `eval_3fb45f4c8e71` |

被签名参数为空集合，recordId 是路径的一段，不参与签名。

## 返回

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `data.recordId` | string | 是 | 记录号 |
| `data.score` | object | 是 | 评测分数，字段与评测结果的 `result` 一致，总分为 `data.score.overall` |
| `data.reportStatus` | string | 是 | `final` 为 AI 终稿，`pending` 为终稿生成中，`instant` 为终稿不会再来，`none` 为没有报告 |
| `data.report.summary` | string | 否 | 整体点评 |
| `data.report.dimensions` | object | 否 | 分维点评，中文为 `integrity`，`accuracy`，`fluency`，`reading_skill`，`emotion` 五项，英文没有 `emotion` |
| `data.report.dimensionSuggestions` | object | 否 | 分维建议，键同 `dimensions`，值为数组 |
| `data.report.suggestions` | array | 否 | 提升建议，逐条给出 |
| `data.report.skillInsights` | array | 否 | 内在技巧观察，`aspect`，`observation`，`quote`，`confidence`，只作教学参考，不计分 |
| `data.report.i18n.zh` | object | 否 | 英文题的中文版，含 `summary`，`dimensions`，`suggestions`，`dimensionSuggestions` |
| `data.report.dimensionScores` | object | 否 | 维度分，键随语种与题型不同，值为 null 表示本次不评该维 |
| `data.timing` | object | 否 | 声通兼容评测的分段耗时，保留 7 天 |

报告查询天然幂等，可以重试，不需要幂等键。等 AI 终稿时隔 1 秒再查一次即可。

## 状态码

| 状态 | 错误码 | 原因 |
|---|---|---|
| 200 | 无 | 返回报告 |
| 400 | 40001 | 记录不存在 |
| 401 | 40100，2001 到 2011 | 未认证，签名错误，token 或 API Key 无效 |
