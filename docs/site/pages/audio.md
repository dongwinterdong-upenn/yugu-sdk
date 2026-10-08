## 格式要求

| 项 | 要求 |
|---|---|
| 推荐格式 | WAV，PCM 16 位，16 kHz，单声道 |
| 整段评测 | 常见格式均可上传，例如 WAV，MP3，M4A，WebM，OGG，请求体不超过 50 MB |
| 实时评测 | PCM 16 位小端，16 kHz，单声道，一轮不超过 10 MB，单帧不超过 128 KB |
| 时长 | 不短于 1 秒，短于 1 秒平台回 40001 |
| 采样率 | 不低于 16 kHz，低于 16 kHz 时预检报 90105，评分精度下降 |

## 上传前预检

整段评测上传前，实时评测调用 `end()` 时，SDK 在本地检查 WAV 与 PCM 音频，其他格式只检查大小。五端的检查项与阈值一致：

| 本地码 | 条件 | `WARN`，默认 | `REJECT` |
|---|---|---|---|
| 90101 | 时长短于 1 秒 | 警告 | 上传前报错 |
| 90102 | 时长超过 300 秒，整段上传大于 50 MB，或实时评测一轮大于 10 MB | 警告 | 上传前报错 |
| 90103 | 全程静音：峰值低于 200，均方根低于 30 | 警告 | 上传前报错 |
| 90104 | 音量过低：均方根低于 -45 dBFS | 警告 | 警告 |
| 90105 | 不是 16 位 PCM，或采样率低于 16000 | 警告 | 上传前报错 |

静音阈值与平台的静音判定一致，本地判为静音的音频，平台也会评为 0 分。`REJECT` 模式下不合格的音频不发请求，实时会话不发结束帧，不产生计费。警告放在结果的本地警告列表里，实时会话另外回调 `onWarning`。配置项为 `audioPrecheck`，取 `OFF`，`WARN`，`REJECT`，iOS，网页与小程序可以在单次调用里覆盖，Java 与安卓只在客户端配置。

## 音频质量警告

平台评测时另做一次音质判断，警告码随评测结果返回，评分照常给出，不作为异常抛出：

| 警告码 | 含义 | 处理 |
|---|---|---|
| 1001 | 未检测到有效音频 | 分数不可信，提示重录 |
| 1002 | 音量过低 | 提示靠近麦克风 |
| 1003 | 音量过高，有截幅 | 提示远离麦克风 |
| 1004 | 环境噪声明显 | 提示换安静环境 |
| 1005 | 音频疑似不完整 | 分数仅供参考 |
| 1009 | 部分评分组件临时降级 | 重新提交可能恢复 |

开启 `strictAudio` 后，平台返回 1001 时整段评测以 `AUDIO` 类错误结束。Java 的实时评测同样处理，可以从异常的 `getResult()` 取回原结果，其余各端的实时评测照常返回结果。

## 内置录音器

安卓，iOS，网页与小程序带有录音器，直接产出 16 kHz，16 位，单声道 PCM，可以边录边送进实时会话，也可以录完交给整段评测。

| 端 | 录音器 | 权限 |
|---|---|---|
| 安卓 | `Recorder`，基于 `AudioRecord` | 清单声明 `RECORD_AUDIO`，Android 6.0 起运行时申请 |
| iOS | `YuguRecorder`，基于 `AVAudioEngine` | `Info.plist` 声明 `NSMicrophoneUsageDescription` |
| 网页 | `YuguRecorder`，优先 `AudioWorklet` | https 页面或 localhost，在用户手势里启动 |
| 小程序 | `client.createRecorder()`，基于 `wx.getRecorderManager` | 用户隐私保护指引声明麦克风，首次录音时授权 |

没有麦克风权限时录音器报 90201，麦克风被占用报 90202，录音过程出错报 90203。

## 外部音频

评测不依赖内置录音器，音频可以来自文件，内存，直播流或第三方采集。

| 端 | 整段评测接受 | 实时评测 |
|---|---|---|
| Java | `byte[]`，`File`，`Path`，`InputStream`，`AudioSource.pcm(pcm, 16000)` | `session.sendAudio(pcm)` |
| 安卓 | `AudioInput.fromBytes`，`fromFile`，`fromStream`，`fromPcm(pcm, 16000, 1)` | `session.sendAudio(pcm)` |
| iOS | `.data(bytes)`，`.file(url)`，`.pcm16(pcm, sampleRate:, channels:)` | `session.sendAudio(_:)` |
| 网页 | `Blob`，`File`，`ArrayBuffer`，`ArrayBufferView`，裸 PCM 加 `audioFormat: 'pcm'` | `session.sendAudio(chunk)` |
| 小程序 | `ArrayBuffer`，临时文件路径，录音结果，裸 PCM 加 `audioFormat: 'pcm'` | `session.sendAudio(chunk)` |

裸 PCM 由 SDK 加上 WAV 头再上传。实时评测推荐每帧 640 字节，即 20 毫秒，单次送入超过 32000 字节时 SDK 拆成多帧发送。
