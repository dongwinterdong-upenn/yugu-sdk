# Java 命令行演示

独立的 Maven 工程，从 `https://open.shengzhiai.com/maven/` 拉取 `com.shengzhiai.yugu:yugu-java-sdk:2.0.0`，评测命令行给出的 WAV 文件，打印总分，维度分，逐句与逐字分数，警告与幂等键。工程不引用 SDK 源码，复制本目录即可单独运行。

## 运行条件

| 项目 | 要求 |
|---|---|
| Java | 11 及更高版本 |
| Maven | 不需要预装，`mvnw` 自动下载 Maven `3.9.9` |
| 凭据 | 开放平台控制台签发的 appKey 与 secretKey，或者沙箱密钥 |
| 音频 | 16 kHz，16 位，单声道 WAV，时长 1 秒到 300 秒 |

## 运行方式

```bash
cd demos/java-cli
export YUGU_APP_KEY=<appKey>
export YUGU_SECRET_KEY=<secretKey>
./mvnw -q compile exec:java -Dexec.args="../../spec/fixtures/audio/zh_short.wav 今天天气很好"
```

Windows 下使用 `mvnw.cmd`。输出示例：

```
recordId       eval_3fb45f4c8e71
overall        93.7
pronunciation  100.0
fluency        96.0
integrity      100.0
tone           83.0
rhythm         78.0
sentence       今天天气很好  94.0
  今  78.0  correct
  ...
idempotencyKey 73860a2c1be5424ab745ccf4b0315463
```

## 命令行参数

| 参数 | 含义 | 默认值 |
|---|---|---|
| 第一个参数 | WAV 文件路径 | 必填 |
| 第二个参数 | 参考文本 | `今天天气很好` |
| `--core` | 题型：`word`，`sentence`，`passage`，`connected`，`open`，`alpha`，`pinyin` | `sentence` |
| `--lang` | 语言：`zh-CN`，`en-US`，`en-GB` | `zh-CN` |
| `--stream` | 改用实时评测，按 20 毫秒一帧送入音频 | 关闭 |

英文句子示例：

```bash
./mvnw -q compile exec:java -Dexec.args="../../spec/fixtures/audio/en_fox.wav 'The quick brown fox jumps over the lazy dog.' --lang en-US"
```

## 环境变量

| 变量 | 含义 |
|---|---|
| `YUGU_APP_KEY` 与 `YUGU_SECRET_KEY` | 签名鉴权的密钥对 |
| `YUGU_TOKEN` | 令牌鉴权，设置后忽略密钥对 |
| `YUGU_BASE_URL` | 平台地址，默认 `https://open.shengzhiai.com`，WebSocket 地址由其推导 |

## 持续集成中的用法

发布前可以先把 SDK 部署到本地目录，再让演示工程从该目录取包：

```bash
cd java
./mvnw -B -DskipTests deploy -DaltDeploymentRepository=staging::file:///tmp/yugu-staging
cd ../demos/java-cli
./mvnw -B -Dyugu.repo.url=file:///tmp/yugu-staging -Dmaven.repo.local=/tmp/yugu-demo-m2 compile
```

`yugu.repo.url` 覆盖仓库地址，`maven.repo.local` 使用独立的本地仓库，确保 SDK 来自刚部署的目录。对平台模拟服务运行时，把 `YUGU_BASE_URL` 设为模拟服务的地址，测试凭据为 `mock-app-key` 与 `mock-secret-key`。

## 错误输出

评测失败时打印错误类别，错误码，是否可重试与平台的说明，进程以退出码 1 结束，例如：

```
evaluation failed: AUTH code=2003 retryable=false 签名验证失败
```
