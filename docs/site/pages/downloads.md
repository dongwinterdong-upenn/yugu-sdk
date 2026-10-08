## 制品仓库

| 仓库 | 地址 | 内容 |
|---|---|---|
| Maven | `https://open.shengzhiai.com/maven/` | Java SDK，安卓 SDK，安卓声通平替，附源码包，接口文档包与四种校验和 |
| npm | `https://open.shengzhiai.com/npm/` | `@shengzhiai/yugu-web-sdk`，`@shengzhiai/yugu-miniprogram-sdk` |
| SwiftPM | `https://open.shengzhiai.com/git/yugu-ios-sdk.git` | iOS SDK，按 Git 标签发布 |
| SwiftPM | `https://open.shengzhiai.com/git/stkouyu-ios-compat.git` | iOS 声通平替 |

仓库匿名只读，不需要账号。已发布的版本不会被覆盖，任一历史版本都可以按版本号重新拉取。

## 源码仓库

```bash
git clone https://open.shengzhiai.com/git/yugu-sdk.git && cd yugu-sdk
bash ci/run-all.sh            # 全部步骤
bash ci/run-all.sh java web   # 指定步骤
```

仓库包含五端 SDK，两个平替层，各端演示工程，平台模拟服务，接口比对工具与持续集成脚本。每次提交与每晚各构建一次，构建记录公开在[持续集成](/sdk/ci/)。

## 规范文件

| 文件 | 内容 |
|---|---|
| [openapi.yaml](/sdk/v2/spec/openapi.yaml) | OpenAPI 3.0 描述，四个 REST 接口，用于生成客户端 |
| [errors.json](/sdk/v2/spec/errors.json) | 统一错误码表，五端 SDK 的错误码常量按这份表生成 |
| [vectors.json](/sdk/v2/spec/fixtures/sign/vectors.json) | 签名跨端测试向量，各端签名单测逐条命中 |
| [zh_short.wav](/sdk/v2/spec/fixtures/audio/zh_short.wav) | 示例音频，16 kHz 单声道，朗读 `今天天气很好` |

## 文档的机器可读版本

每个页面都有同名的 Markdown 版本，页面上方的复制 Markdown 按钮复制全文。整站目录在 [llms.txt](/sdk/v2/llms.txt)，全文合集在 [llms-full.txt](/sdk/v2/llms-full.txt)，可以直接交给代码助手作为上下文。
