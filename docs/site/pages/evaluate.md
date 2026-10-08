整段评测适合录完再评的场景：作业提交，批量补评，服务端转评。一次 HTTPS 请求上传音频与评测参数，平台评完后返回结果，单次请求在服务端最长处理 600 秒。边录边评，需要读完即出分时用[实时评测](page:streaming)。

## 调用

```tabs
items:
  - { label: Java, ref: 'java/README.md#五分钟快速开始', lang: java, lines: 10-22 }
  - { label: 安卓, ref: 'android/README.md#五分钟快速开始', lang: kotlin, n: 3 }
  - { label: iOS, ref: 'ios/README.md#五分钟上手', lang: swift, n: 2 }
  - { label: 网页, ref: 'web/README.md#五分钟快速开始', lang: js, lines: 9-15 }
  - { label: 小程序, ref: 'miniprogram/README.md#五分钟上手', lang: js, lines: 12-21 }
  - { label: cURL, file: docs/site/snippets/evaluate.sh, lang: bash }
```

评测参数至少包含 coreType，参考文本与语种，可选参数与取值见[评测模式](page:core-types)。安卓的 `evaluate` 是阻塞调用，放在后台线程执行，`evaluateAsync` 的回调默认在主线程。iOS 另有 `async` 写法，见 [iOS SDK](repo:ios/README.md#五分钟上手)。

## 音频输入

| 端 | 接受的音频 |
|---|---|
| Java | `byte[]`，`File`，`Path`，`InputStream`，`AudioSource.pcm(pcm, 16000)` |
| 安卓 | `ByteArray`，`File`，`InputStream`，`AudioInput.fromPcm(pcm, 16000, 1)` |
| iOS | `.data(bytes)`，`.file(url)`，`.pcm16(pcm, sampleRate:, channels:)` |
| 网页 | `Blob`，`File`，`ArrayBuffer`，`Uint8Array` 与任意 `ArrayBufferView`，裸 PCM 传 `audioFormat: 'pcm'` 与 `sampleRate` |
| 小程序 | `ArrayBuffer`，`Uint8Array`，临时文件路径，SDK 录音结果，裸 PCM 传 `audioFormat: 'pcm'` 与 `sampleRate` |

裸 PCM 由 SDK 加上 WAV 头后上传。推荐 WAV，PCM 16 位，16 kHz，单声道，时长不短于 1 秒，请求体不超过 50 MB，完整要求见[音频与录音](page:audio)。

## 结果

整段评测与实时评测返回同一个结果模型：总分，维度分，逐字或逐词分数，分句，音频质量警告，本地预检提示，幂等键，重放标记与尝试次数，原始 JSON 始终可取，平台新增的字段从原始 JSON 读取。连读题型的总分取 `connected_overall`，SDK 的总分访问器已按题型处理。字段说明见[评测结果字段](page:api-results)。

## 声通兼容整段评测

`evaluateCompat` 调用 `POST /{coreType}`，coreType 与参数名沿用声通命名，例如 `sent.eval.cn` 与 `refText`，适合参数体系已按声通设计的业务。结果结构与原生接口相同，请求带 `attachAudioUrl=1` 时另有录音下载地址，保留 7 天。兼容接口只接受签名鉴权。接口定义见[声通兼容整段评测](page:api-evaluate-compat)。

## 计费与重复提交

每次调用自动带幂等键，网络抖动引起的重试复用同一个键，平台按键只评测一次，只计费一次。业务上同一份作业只计一次费时，用作业号作为幂等键，做法见[重试与幂等](page:retries)。
