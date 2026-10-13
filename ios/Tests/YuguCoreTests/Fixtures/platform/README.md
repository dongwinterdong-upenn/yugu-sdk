# 平台返回样例

本目录的 JSON 是 2026-10-08 用沙箱密钥调用线上平台录下的原样返回，五端 SDK 的结果解析测试与平台模拟服务都读取这些文件。评测音频在 `spec/fixtures/audio/`。

## 原生整段评测

接口为 `POST /api/v1/evaluate`，文件内容为响应的 `data` 部分。

| 文件 | 评测模式 | 总分字段 |
|---|---|---|
| `native_word_en.json` | 英文单词 word | `result.overall` |
| `native_sentence_en.json` | 英文句子 sentence | `result.overall` |
| `native_evaluate_sentence_zh.json` | 中文句子 sentence | `result.overall` |
| `native_passage_zh.json` | 中文段落 passage | `result.overall` |
| `native_connected_en.json` | 英文连读 connected | `result.connected_overall`，没有 `result.overall` |
| `native_open_zh.json` | 中文开放题 open | `result.overall` |
| `native_alpha_en.json` | 英文字母 alpha | `result.overall` |
| `native_pinyin_zh.json` | 拼音 pinyin | `result.overall` |

## 声通兼容整段评测

接口为 `POST /{coreType}`，文件内容为响应体。

| 文件 | coreType | 说明 |
|---|---|---|
| `compat_sent.eval.cn.json` | `sent.eval.cn` | 单行紧凑格式 |
| `compat_sent_eval_cn.json` | `sent.eval.cn` | 另一次返回，缩进格式，供解析测试覆盖两种排版 |
| `compat_sent.eval.json` | `sent.eval` | 英文句子 |
| `compat_word.eval.json` | `word.eval` | 英文单词 |
| `compat_para.eval.cn.json` | `para.eval.cn` | 未要求逐词详情 |
| `compat_para.eval.cn_word_detail.json` | `para.eval.cn` | 带 `paragraph_need_word_score=1`，分句带 `details` 逐词详情 |
| `compat_sent.eval.cn_attach_audio_url.json` | `sent.eval.cn` | 带 `attachAudioUrl=1`，返回体顶层带 `audioUrl`，为录音下载地址，保留 7 天 |
| `compat_para.eval.cn_single_sentence.json` | `para.eval.cn` | 参考文本只有一句，带 `paragraph_need_word_score=1`，`sentences` 为一个分句 |
| `compat_open.eval.cn.json` | `open.eval.cn` | 2026-10-13 录下，`refText` 为两条参考答案 `我是学生` 与 `我不是学生`，`qType` 为 6，作答为合成的“我是学生。”，音频为 `spec/fixtures/audio/zh_open_answer.wav`，`openTaskAudit.refKind` 为 `answers`，带声通扁平字段 |

## 实时评测帧序列

文件为数组，每项 `at` 是相对连接建立的毫秒数，`frame` 是服务端发出的文本帧。

| 文件 | 接口 | 说明 |
|---|---|---|
| `ws_native_sentence_frames.json` | `/api/v1/ws/evaluate` | 录制时平台还不支持心跳指令，客户端发出的 `{"cmd":"ping"}` 得到一条不带 `code` 的 unknown cmd 错误帧，会话照常出分。平台 2026-10-08 起对该指令回 `{"event":"pong"}`，网页与小程序 SDK 收到这种错误帧时关闭应用层心跳，不结束会话 |
| `ws_compat_sent_eval_cn_frames.json` | `/sent.eval.cn` | 录制脚本发了两个参数帧，平台对每个参数帧回一次 started，同时清空之前收到的音频，SDK 每轮只发一个参数帧 |
| `ws_compat_sent_eval_cn_progress_frames.json` | `/sent.eval.cn` | 2026-10-12 录制，参数帧带 `realtime_feedback`，参考文本 25 个字整句读完，中间帧带朗读进度与声通式增量 `words`，最后一个进度帧里 25 个字都已读，都已对上 |
| `ws_compat_sent_eval_cn_progress_misread_frames.json` | `/sent.eval.cn` | 同一参考文本，第二句的“中”读成“外”，该字 `read` 为 true，`matched` 为 false |
| `ws_compat_sent_eval_progress_frames.json` | `/sent.eval` | 英文句子 16 个词，按词计 |
| `ws_native_sentence_progress_frames.json` | `/api/v1/ws/evaluate` | start 帧带 `realtimeFeedback`，中间帧只有 `bytes`，`readUnits`，`matchedUnits`，`totalUnits` 与 `progress`，最后是 `event` 为 result 的终评 |

带朗读进度的四个文件里，`at` 是相对开始送音频的毫秒数，开头 0.5 秒与结尾 0.5 秒为静音，音频按实时速度发送。整句读完与英文两份的音频是 `spec/fixtures/audio/zh_progress.wav` 与 `en_progress.wav`，均为合成语音，16 kHz 单声道 16 位，参考文本分别为“我是中国人，我是中国人。我们都是中国人。中华民族是一家人。”与“Nice to see you again. I am a student, and I like reading books every day.”

## 错误与语音合成

| 文件 | 内容 |
|---|---|
| `error_native_bad_signature.json` | 原生整段评测签名错误时的状态码，响应头与响应体 |
| `error_compat_pinyin_missing_refpinyin.json` | 兼容接口 pinyin 题缺 `refPinyin` 时的状态码，响应头与响应体 |
| `tts_generate.json` | `POST /api/v1/tts/generate` 的响应体 |
