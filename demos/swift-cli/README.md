# 命令行评测工具 yugu-eval

用 `YuguCore` 评测 WAV 文件的 SwiftPM 可执行工程，macOS 与 Linux 都能运行。

## 构建

```bash
git clone https://open.shengzhiai.com/git/yugu-sdk.git
cd yugu-sdk/demos/swift-cli
swift build -c release
```

缺省依赖已发布的 `https://open.shengzhiai.com/git/yugu-ios-sdk.git` 的 `2.0.0` 版本。针对本地 SDK 源码构建时设置 `YUGU_SDK_PATH`：

```bash
YUGU_SDK_PATH=../../ios swift build -c release
```

## 用法

```bash
export YUGU_APP_KEY='<appKey>'
export YUGU_SECRET_KEY='<secretKey>'
.build/release/yugu-eval --text "今天天气很好" --language zh-CN ../../spec/fixtures/audio/zh_short.wav
```

| 参数 | 含义 |
|---|---|
| `--base-url` | 平台基址，缺省为 `https://open.shengzhiai.com`，也可用环境变量 `YUGU_BASE_URL` |
| `--app-key`，`--secret-key` | 密钥对，也可用环境变量 `YUGU_APP_KEY` 与 `YUGU_SECRET_KEY` |
| `--token` | 用 token 代替密钥对，也可用环境变量 `YUGU_TOKEN` |
| `--text` | 参考文本，必填 |
| `--core-type` | 题型，缺省为 `sentence` |
| `--language` | `en-US`，`en-GB` 或 `zh-CN` |
| `--compat` | 改用声通兼容整段评测，值为声通题型，例如 `sent.eval.cn` |
| `--stream` | 改用原生实时评测，Linux 上不可用 |
| `--idempotency-key` | 指定幂等键，重复提交不重复计费 |
| `--precheck` | 音频预检方式 `off`，`warn` 或 `reject` |
| `--json` | 输出平台返回的原始 JSON |

输出示例：

```
recordId        eval_058d943fad1c
coreType        sentence
overall         93.7
integrity       100   accuracy 100   fluency 96
tone            83   rhythm 78   speed 225
words           今 78 | 天 95 | 天 85 | 气 76 | 很 94 | 好 73
attempts        1   replayed false   idempotencyKey 93868550ab0d44dc906feddbadb1f3fd
```

退出码 0 表示成功，1 表示评测失败，错误类别，错误码与幂等键写在标准错误输出中，2 表示参数有误。

## 对接平台模拟服务

不用真实密钥时，可对接 SDK 总仓库自带的平台模拟服务，需要 Node.js 18 起：

```bash
(cd ../../tools/mock-server && npm ci)
node ../../tools/mock-server/server.mjs --port 18900 &
.build/release/yugu-eval --base-url http://127.0.0.1:18900 \
  --app-key mock-app-key --secret-key mock-secret-key \
  --text "今天天气很好" --language zh-CN ../../spec/fixtures/audio/zh_short.wav
```

## Linux 说明

Linux 上的整段评测，声通兼容评测，语音合成与报告查询都可用。Linux 版 Foundation 依赖 libcurl 提供 WebSocket，Ubuntu 24.04 自带的 libcurl 不含这项支持，所以 `--stream` 在 Linux 上以错误码 90010 结束。
