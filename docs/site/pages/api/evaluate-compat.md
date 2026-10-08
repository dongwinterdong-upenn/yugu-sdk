```endpoint
method: POST
path: /{coreType}
```

```aside
- title: 请求示例
  tabs:
    - { label: cURL, file: docs/site/snippets/evaluate-compat.sh, lang: bash }
    - { label: 安卓平替, ref: 'android-stcompat/README.md#代码改动', lang: java }
    - { label: iOS 平替, ref: 'ios-stcompat/README.md#生命周期示例', lang: swift }
- title: 返回示例
  fixture: spec/fixtures/platform/compat_sent.eval.cn_attach_audio_url.json
  label: 200，带 attachAudioUrl=1
- title: 错误示例
  fixture: spec/fixtures/platform/error_compat_pinyin_missing_refpinyin.json
  pick: body
  label: 400，pinyin 题缺 refPinyin
```

`multipart/form-data` 请求，`audio` 为文件段，业务参数为文本段，字段名沿用声通命名。另可带文本段 `request`，内容为 JSON，其顶层标量字段并入业务参数。兼容接口必须带 `X-App-Key`，只接受签名鉴权，被签名参数为实际发送的全部文本表单字段，音频不参与。

## 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `coreType` | string | 是 | `word.eval`，`word.eval.pro`，`sent.eval`，`sent.eval.pro`，`para.eval`，`alpha.eval`，`word.eval.cn`，`sent.eval.cn`，`para.eval.cn`，`pinyin` |

## 表单字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `audio` | 文件 | 是 | 音频文件，建议 WAV，PCM 16 位，16 kHz，单声道 |
| `refText` | string | 是 | 参考文本，中文题设置了 `refPinyin` 时可以为空 |
| `language` | string | 否 | 语种，例如 `zh-CN`，`en-US` |
| `refPinyin` | string | 否 | 拼音，pinyin 题必填，例如 `chong2 qing4` |
| `agegroup` | string | 否 | 年龄段，`1` 学前，`2` 小学，`3` 中学及以上 |
| `scale` | string | 否 | 分制，1 到 100 |
| `precision` | string | 否 | 精度，大于 0 到 1 |
| `slack` | string | 否 | 松紧度，-1 到 1 |
| `paragraph_need_word_score` | string | 否 | 段落题是否返回逐词详情，`1` 或 `0` |
| `phoneme_output` | string | 否 | 音素级输出，`1` 或 `0` |
| `attachAudioUrl` | string | 否 | `1` 时返回体顶层带录音下载地址 `audioUrl`，保留 7 天 |
| `dict_type`，`dict_dialect`，`customized_lexicon`，`customized_pron` | string | 否 | 词典与自定义发音，原样传给评测引擎，`dict_dialect` 取 `en_br` 或 `en_us` |
| `output_rawtext`，`readtype_diagnosis` | string | 否 | 原文输出与读法诊断，`1` 或 `0` |
| `request` | string | 否 | JSON 文本，顶层标量字段并入业务参数 |

## 返回

```include
ref: CONTRACT.md#3 整段评测#3.2 声通兼容接口
from: 成功时返回声通风格结果
```

状态码与[原生整段评测](page:api-evaluate#状态码)相同，没有 415。
