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
| `data.report.summary` | string | 否 | 整体点评 |
| `data.report.suggestions` | array | 否 | 改进建议 |
| `data.report.dimensionScores` | object | 否 | 维度分，键随语种与题型不同，值为 null 表示本次不评该维 |

报告查询天然幂等，可以重试，不需要幂等键。
