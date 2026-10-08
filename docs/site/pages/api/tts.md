```endpoint
method: POST
path: /api/v1/tts/generate
```

```aside
- title: 请求示例
  tabs:
    - { label: cURL, file: docs/site/snippets/tts.sh, lang: bash }
    - { label: Java, file: docs/site/snippets/java/TtsAndReport.java, region: tts, lang: java }
    - { label: 网页, file: docs/site/snippets/tts-report.mjs, region: tts, lang: js }
- title: 返回示例
  fixture: spec/fixtures/platform/tts_generate.json
  label: '200'
```

## 请求体

`Content-Type: application/json`，被签名参数为请求体顶层的非空标量字段，值取 JSON 里的字面文本，数值只写普通十进制。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 待合成的文本 |
| `language` | string | 否 | `zh-CN` 默认，`en-US`，`en-GB` |
| `voice` | string | 否 | 中文 `xiaoyan` 女声或 `xiaofeng` 男声，英文 `female` 或 `male` |
| `format` | string | 否 | `mp3` 默认，`wav`，`ogg` |
| `speed` | integer | 否 | 语速，0 到 100，默认 50 |
| `pitch` | integer | 否 | 音调，0 到 100，默认 50 |
| `volume` | integer | 否 | 音量，0 到 100，默认 50 |
| `style` | string | 否 | 风格，可为 null |

请求头与[原生整段评测](page:api-evaluate#请求头)相同，带 `Idempotency-Key` 时同一个键只合成一次，只计费一次。

## 返回

响应为平台信封，`data` 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `audioUrl` | string | 是 | 音频地址。完整地址可直接播放，以 `/audio/` 开头时按基址拼成 `https://open.shengzhiai.com/tts/audio/...` |
| `duration` | string | 是 | 时长，单位秒 |
| `format` | string | 是 | 音频格式 |

SDK 的结果模型同时给出原始地址与可直接播放的完整地址。
