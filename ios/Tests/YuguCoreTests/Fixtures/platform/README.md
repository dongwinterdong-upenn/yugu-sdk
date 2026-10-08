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
| `compat_sent.eval.cn_attach_audio_url.json` | `sent.eval.cn` | 带 `attachAudioUrl=1`，平台当前不返回录音地址，返回体里没有录音地址字段 |

## 实时评测帧序列

文件为数组，每项 `at` 是相对连接建立的毫秒数，`frame` 是服务端发出的文本帧。

| 文件 | 接口 | 说明 |
|---|---|---|
| `ws_native_sentence_frames.json` | `/api/v1/ws/evaluate` | 录制时平台还不支持心跳指令，客户端发出的 `{"cmd":"ping"}` 得到一条不带 `code` 的 unknown cmd 错误帧，会话照常出分。平台 2026-10-08 起对该指令回 `{"event":"pong"}`，网页与小程序 SDK 收到这种错误帧时关闭应用层心跳，不结束会话 |
| `ws_compat_sent_eval_cn_frames.json` | `/sent.eval.cn` | 录制脚本发了两个参数帧，平台对每个参数帧回一次 started，同时清空之前收到的音频，SDK 每轮只发一个参数帧 |

## 错误与语音合成

| 文件 | 内容 |
|---|---|
| `error_native_bad_signature.json` | 原生整段评测签名错误时的状态码，响应头与响应体 |
| `error_compat_pinyin_missing_refpinyin.json` | 兼容接口 pinyin 题缺 `refPinyin` 时的状态码，响应头与响应体 |
| `tts_generate.json` | `POST /api/v1/tts/generate` 的响应体 |
