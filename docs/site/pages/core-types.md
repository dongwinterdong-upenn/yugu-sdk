## 题型与 coreType

原生接口用 7 个 `coreType`，语种由 `language` 指定。声通兼容接口沿用声通命名，coreType 写在路径里，`.cn` 结尾的为中文。

| 题型 | 原生 coreType | 声通兼容 coreType | 语种 | 参考文本 |
|---|---|---|---|---|
| 字词 | `word` | `word.eval`，`word.eval.pro`，`word.eval.cn` | 中文，英文 | 一个字词 |
| 句子 | `sentence` | `sent.eval`，`sent.eval.pro`，`sent.eval.cn` | 中文，英文 | 一句话 |
| 段落 | `passage` | `para.eval`，`para.eval.cn` | 中文，英文 | 多句，逐句给出分句结果 |
| 连读 | `connected` | 无 | 英文 | 一句话，评连读，失爆与弱读 |
| 开放题 | `open` | 无 | 中文，英文 | 题目，`taskType` 取 `picture`，`situational`，`free` |
| 字母 | `alpha` | `alpha.eval` | 英文 | 字母序列 |
| 拼音 | `pinyin` | `pinyin` | 中文 | 汉字，`refPinyin` 必填，例如 `chong2 qing4` |

原生接口的 `language` 取 `en-US`，`en-GB`，`zh-CN`，缺省为 `en-US`，中文评测要显式传 `zh-CN`。参考文本最长 1000 字，开放题为题目。看图说话另上传一张图片。

## 评分参数

| 参数 | 原生字段 | 兼容字段 | 取值与默认 |
|---|---|---|---|
| 松紧度 | `slack` | `slack` | -1 到 1，默认 0 |
| 分制 | `scale` | `scale` | 1 到 100，默认 100 |
| 精度 | `precision` | `precision` | 大于 0 到 1，默认 1 |
| 年龄段 | `agegroup` | `agegroup` | 1 学前，2 小学，3 中学及以上，默认 3 |
| 段落逐字或逐词详情 | `paragraphNeedWordScore` | `paragraph_need_word_score` | 1 或 0 |
| 音素输出 | `phonemeOutput` | `phoneme_output` | 布尔值或 1 与 0 |
| 详细报告 | `includeReport` | 无 | 默认 false |
| 识别文本 | `includeAsrText` | 无 | 默认 false |
| 标准示范音 | `includeStandardAudio` | 无 | 默认 false |
| 录音地址 | 无 | `attachAudioUrl` | 1 时返回录音下载地址，保留 7 天 |

`precision` 当前不改变返回粒度，设置后结果带一条说明此事的警告。英文评测设置 `scale` 时发音分会失真，`scale` 不大于 11 时发音分为 0，结果同样带警告，英文评测暂用默认分制，在调用方换算。字段的完整定义见[原生整段评测](page:api-evaluate)与[声通兼容整段评测](page:api-evaluate-compat)。

## 各模式的取分字段

```include
ref: RESULTS.md#各模式的总分字段
canonical: false
```

字段含义见[评测结果字段](page:api-results)。样例文件在源码仓库的 `spec/fixtures/platform/` 目录，为线上真实返回，五端 SDK 的解析测试逐一覆盖。
