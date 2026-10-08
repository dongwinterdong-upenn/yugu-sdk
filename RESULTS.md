# 评测结果字段

各评测模式的取分字段，字段名与 `CONTRACT.md` 和 `spec/openapi.yaml` 一致。样例为线上真实返回，存放在 `spec/fixtures/platform/`，五端 SDK 的解析测试逐一覆盖这些样例。分数默认为百分制，`scale` 参数改变分制后按比例换算。

## 各模式的总分字段

| 原生 coreType | 声通兼容 coreType | 总分字段 | 样例 |
|---|---|---|---|
| `word` | `word.eval`，`word.eval.pro`，`word.eval.cn` | `result.overall` | `native_word_en.json` |
| `sentence` | `sent.eval`，`sent.eval.pro`，`sent.eval.cn` | `result.overall` | `native_sentence_en.json`，`native_evaluate_sentence_zh.json` |
| `passage` | `para.eval`，`para.eval.cn` | `result.overall`，分句见 `result.sentences` | `native_passage_zh.json` |
| `connected` | 无 | `result.connected_overall`，没有 `result.overall` | `native_connected_en.json` |
| `open` | 无 | `result.overall`，分项见 `content`，`languageUse`，`delivery` | `native_open_zh.json` |
| `alpha` | `alpha.eval` | `result.overall` | `native_alpha_en.json` |
| `pinyin` | `pinyin` | `result.overall` | `native_pinyin_zh.json` |

SDK 结果模型的总分访问器在 connected 模式下取 `connected_overall`，原始 JSON 始终可以取到。

## 字词，句子，段落，字母，拼音

| 字段 | 类型 | 含义 |
|---|---|---|
| `overall` | number | 总分 |
| `pronunciation` | number | 发音准确度 |
| `fluency` | number | 流利度 |
| `integrity` | number | 完整度，漏读越多越低 |
| `rhythm` | number | 韵律 |
| `tone` | number | 中文声调分，英文为 0 |
| `rear_tone` | string | 句末语调，rise，fall，flat |
| `speed` | number | 语速，中文为字每分钟，英文为词每分钟 |
| `duration` | string | 音频时长，单位秒 |
| `warning` | array | 音频质量警告 `[{code, message}]`，码表见 `ERRORS.md` |
| `words` | array | 逐字或逐词详情，见下表 |
| `sentences` | array | 分句结果，段落题逐句给出 `sentence`，`index`，`overall`，`scores`，`span`，`paragraphNeedWordScore` 为 1 时带 `details` 逐词详情。参考文本只有一句时同样给出一个分句，分数与段落相同 |
| `compositeReport` | object | 维度细分，例如 `emotionScore`，`stopConnScore`，`stressScore`，`intonationScore`，`nasalsScore` |

`words` 每一项：

| 字段 | 类型 | 含义 |
|---|---|---|
| `word` | string | 字或词 |
| `pinyin`，`symbolpinyin`，`tone` | string | 中文拼音，带调拼音，声调 `tone1` 到 `tone5` |
| `charType` | int | 0 为字词，1 为标点 |
| `readType` | int | 0 正常，1 增读，2 漏读，3 重复读 |
| `read_status` | string | correct，mispronounced，skipped，inserted |
| `scores.overall` | int | 字词得分 |
| `scores.pronunciation` | int | 字词发音分 |
| `scores.tone` | int | 中文字声调分 |
| `scores.stress` | array | 英文重音，`ref_stress` 为参考重音，`stress` 为实际重音 |
| `span` | object | 起止时间，单位 10 毫秒 |
| `phonemes` | array | 音素详情，`phoneme`，`pronunciation`，`span` |
| `phonics` | array | 英文字母组合与发音对应，`spell`，`overall` |
| `pause` | object | 词后停顿，`type`，`duration` |

## 连读 connected

| 字段 | 类型 | 含义 |
|---|---|---|
| `connected_overall` | number | 连读总分 |
| `linking` | number | 连读分 |
| `elision` | number | 失爆与省音分 |
| `reduction` | number | 弱读分 |
| `rhythm` | number | 节奏分 |
| `n_boundaries` | int | 词间连接处的个数 |
| `boundaries` | array | 每个连接处：`between` 两个词，`tags` 应有的连读类型，`realized` 实际实现程度 0 到 1，`continuity`，`gap_ms`，`start_ms`，`end_ms` |
| `coverage` | object | 识别覆盖情况，`ratio` 为已覆盖词占比 |

## 开放题 open

| 字段 | 类型 | 含义 |
|---|---|---|
| `overall` | number | 总分，按 `aggregation.formula` 由内容，语言运用，表达三项加权 |
| `content` | object | 内容：`overall`，`relevance` 切题度，`coherence` 连贯性，`task_achievement` 任务完成度 |
| `languageUse` | object | 语言运用：`overall`，`grammar` 语法，`vocabulary` 词汇 |
| `delivery` | object | 表达：`overall`，`fluency`，`pronunciation`，`speech_rate` 每秒字数或词数，`speech_rate_label`，`n_pauses`，`longest_pause_s` |
| `transcript` | string | 识别文本 |
| `feedback` | object | 点评：`strengths`，`weaknesses`，`suggestions` |
| `openTaskAudit` | object | 念题，空话，跑题等审计标记，命中时总分封顶 |
| `audioQuality` | object | `mos` 音质分，`quality` |
| `taskType` | string | picture，situational，free |
| `rubricVersion` | string | 评分细则版本 |

## 报告与识别文本

`report` 为自然语言报告与结构化维度。`report.dimensionScores` 的键随语种与题型不同：中文朗读有 accuracy，fluency，integrity，affect，speechRate，英文朗读没有 affect，另有 reading_skill 与 readSpeedRaw，开放题为空对象。值为整数或 null，null 表示本次不评该维，读取时每个键都按可能缺失处理。`asrText.text` 为识别文本，`asrText.alignment` 逐字给出 `char`，`read_status`，`start_time`，`end_time`，`asr_pinyin`，`gop_score`。字段的完整说明见 `CONTRACT.md` 第 3 节。
