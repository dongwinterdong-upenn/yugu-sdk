## 鉴权方式

两种方式任选其一，全部接口通用。同时配置时 SDK 使用签名方式。

| 方式 | 凭据 | REST 请求 | 实时评测握手 | 适用 |
|---|---|---|---|---|
| 签名 | appKey 与 secretKey | 请求头 `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature` | query 参数 `appKey`，`timestamp`，`nonce`，`signature` | 服务端，沙箱联调 |
| token | JWT | 请求头 `Authorization: Bearer <jwt>` | query 参数 `token` | 网页，小程序与移动端的正式环境 |

声通兼容整段评测 `POST /{coreType}` 要求请求头带 `X-App-Key`，只能用签名方式。签名的拼接规则，各接口的被签名参数与跨端测试向量见[签名与鉴权](page:api-auth)，SDK 按同一规则自动计算，调用方只提供密钥。

## 各端写法

```tabs
items:
  - label: Java
    lang: java
    code: |
      // 签名
      YuguClient client = YuguClient.builder().apiKey(appKey, secretKey).build();
      // token
      YuguClient tokenClient = YuguClient.builder().token(jwt).build();
  - label: 安卓
    lang: kotlin
    code: |
      // 签名
      val client = YuguClient.builder().auth(Auth.appKey(appKey, secretKey)).build()
      // token
      val tokenClient = YuguClient.builder().auth(Auth.token(jwt)).build()
  - label: iOS
    lang: swift
    code: |
      // 签名
      let client = YuguClient(options: YuguClientOptions(auth: .appKey(appKey, secretKey: secretKey)))
      // token
      let tokenClient = YuguClient(options: YuguClientOptions(auth: .token(jwt)))
  - label: 网页
    lang: js
    code: |
      // 签名，只用于 Node.js 服务端与沙箱试用
      const client = new YuguClient({ appKey, secretKey });
      // token
      const tokenClient = new YuguClient({ token: jwt });
  - label: 小程序
    lang: js
    code: |
      // 签名，只用于沙箱联调
      const client = new YuguClient({ auth: { appKey, secretKey } });
      // token
      const tokenClient = new YuguClient({ auth: { token: jwt } });
```

## 密钥存放

| 项 | 要求 |
|---|---|
| secretKey | 只放在服务端或可信环境。写进安装包，网页脚本或小程序代码包后可能被取出，正式环境的客户端改用 token |
| token | JWT 由开放平台登录接口 `POST /api/v1/auth/login` 签发，接入方服务端取得后下发给客户端 |
| 沙箱密钥 | 环境标记为 sandbox 的密钥，每把每天 200 次，只用于联调，见[沙箱环境](page:sandbox) |
| 日志 | SDK 日志不含 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符 |
| 持续集成 | 密钥用流水线的密钥配置注入，不写进代码仓库与构建日志 |

## 时钟与防重放

服务端校验 `|当前秒 - X-Timestamp| ≤ 300`，超出即拒绝，调用方的服务器要开启时间同步。`X-Nonce` 在服务端 300 秒内去重，SDK 每次请求与每次重试都生成新的时间戳，随机串与签名，幂等键保持不变。

## 鉴权错误

| 错误码 | 名称 | 处理 |
|---|---|---|
| 40100 | `UNAUTHORIZED` | 未认证，检查是否配置了鉴权方式 |
| 2003 | `SIGNATURE_VERIFICATION_FAILED` | 核对 secretKey，被签名参数与时间戳 |
| 2001，2002 | `TOKEN_EXPIRED`，`TOKEN_INVALID` | 重新取得 token |
| 2006，2007，2010，2011 | API Key 无效，已过期，不存在，已禁用 | 在控制台核对密钥状态 |
| 42903 | `SANDBOX_DAILY_LIMIT` | 沙箱密钥当日次数用尽，北京时间零点重置 |

鉴权错误不重试，完整码表见[错误码](page:api-errors)。
