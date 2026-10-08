## 准备工作

| 项 | 要求 |
|---|---|
| 密钥 | 开放平台签发的 appKey 与 secretKey。联调期间用沙箱密钥，每把每天 200 次，不扣套餐与余额，申请方式见[沙箱环境](page:sandbox) |
| 音频 | 一段 1 秒以上的 WAV 录音，16 kHz，16 位，单声道。可以直接用[示例音频](/sdk/v2/spec/fixtures/audio/zh_short.wav)，朗读的参考文本为 `今天天气很好` |
| 运行环境 | Java 11，安卓 5.0，iOS 13，Chrome 73 与 Node.js 18，微信基础库 `2.20.1`，各端完整要求见[版本兼容](page:compatibility) |

> 注意：secretKey 只放在服务端或可信环境。网页，小程序与移动端正式上线时改用 token 方式，JWT 由接入方服务端向开放平台取得后下发给客户端，做法见[鉴权](page:authentication)。

## 接入步骤

```sections
- label: Java
  parts:
    - java/README.md#五分钟快速开始
- label: 安卓
  parts:
    - android/README.md#安装
    - android/README.md#权限
    - android/README.md#五分钟快速开始
- label: iOS
  parts:
    - { ref: 'ios/README.md#安装', drop: [CocoaPods 方式, 仓库访问, 版本固定与回退] }
    - ios/README.md#麦克风权限
    - ios/README.md#五分钟上手
- label: 网页
  parts:
    - { ref: 'web/README.md#安装', drop: [ESM 与打包工具] }
    - web/README.md#五分钟快速开始
- label: 小程序
  parts:
    - miniprogram/README.md#安装
    - miniprogram/README.md#合法域名
    - miniprogram/README.md#五分钟上手
- label: cURL
  file: docs/site/pages/_quickstart-curl.md
```

## 返回结果

整段评测成功时直接返回结果对象，不包信封。常用字段如下，各评测模式的完整字段见[评测结果字段](page:api-results)。

| 字段 | 含义 |
|---|---|
| `recordId` | 评测记录号，报告查询与问题排查使用 |
| `result.overall` | 总分，百分制。连读题型的总分在 `result.connected_overall` |
| `result.pronunciation`，`result.fluency`，`result.integrity` | 准确度，流利度，完整度 |
| `result.tone`，`result.rear_tone` | 中文声调分与句末语调 |
| `result.words` | 逐字或逐词分数，含拼音，音素与起止时间，时间单位 10 毫秒 |
| `warnings` | 音频质量警告码，例如 1002 音量过低，评分照常返回 |

```fixture
file: spec/fixtures/platform/native_evaluate_sentence_zh.json
label: 返回示例，中文句子
limitArrays: 2
```

## 下一步

```cards
cols: 2
items:
  - { title: 实时评测, icon: stream, href: 'page:streaming', desc: 边录边传的会话，状态机，心跳与断线重连 }
  - { title: 错误处理, icon: bug_report, href: 'page:errors-guide', desc: 错误类别，异常类型与可重试判定 }
  - { title: 鉴权, icon: key, href: 'page:authentication', desc: 签名与 token 两种方式，密钥存放位置 }
  - { title: 上线检查清单, icon: checklist, href: 'page:production', desc: 从沙箱切到生产之前逐项核对 }
```
