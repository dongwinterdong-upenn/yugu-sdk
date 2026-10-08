## 鉴权方式

两种方式任选其一，声通兼容整段评测 `POST /{coreType}` 只接受签名，其余接口两种方式通用。每个客户端只配置一种方式：Java SDK 同时配置两种时使用签名，网页 SDK 同时配置时报 90010，小程序 SDK 同时配置时使用 token，安卓与 iOS 只接受一种。

| 方式 | 凭据 | REST 请求 | 实时评测握手 | 适用 |
|---|---|---|---|---|
| 签名 | appKey 与 secretKey | 请求头 `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature` | query 参数 `appKey`，`timestamp`，`nonce`，`signature` | 接入方服务端，各端联调 |
| token | JWT，由开放平台登录接口签发，有效期 2 小时 | 请求头 `Authorization: Bearer <jwt>` | query 参数 `token` | 网页，小程序与移动端的正式环境 |

签名的拼接规则，各接口的被签名参数与跨端测试向量见[签名与鉴权](page:api-auth)，SDK 按同一规则自动计算，调用方只提供密钥。

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
| secretKey | 只放在接入方服务端或可信环境。写进安装包，网页脚本或小程序代码包后可能被取出，正式环境的客户端改用 token |
| token | 接入方服务端调用开放平台登录接口 `POST /api/v1/auth/login` 取得 JWT 后下发给客户端。有效期 2 小时，过期后请求返回 2001，客户端向接入方服务端重新获取。登录接口见[平台接口文档](/docs.html) |
| 声通平替层与兼容整段评测 | 只支持签名。在 App 里使用时 secretKey 随安装包发布，上线前评估这一风险，不能接受时改由接入方服务端调用兼容接口 |
| 沙箱密钥 | 环境标记为 sandbox 的密钥，每把每天 200 次，只用于联调，见[沙箱环境](page:sandbox) |
| 日志 | SDK 日志不含 secretKey，签名，token 与音频数据，appKey 只保留前 4 个字符 |
| 持续集成 | 密钥用流水线的密钥配置注入，不写进代码仓库与构建日志 |

## 时钟与防重放

平台校验 `|当前秒 - X-Timestamp| ≤ 300`，超出即拒绝，调用方的服务器要开启时间同步。`X-Nonce` 在平台 300 秒内去重。SDK 每次尝试都生成新的时间戳与随机串，幂等键保持不变。

## 鉴权与额度错误

| 错误码 | 名称 | 处理 |
|---|---|---|
| 40100 | `UNAUTHORIZED` | 未认证，检查是否配置了鉴权方式 |
| 2003 | `SIGNATURE_VERIFICATION_FAILED` | 核对 secretKey，被签名参数与时间戳 |
| 2001，2002 | `TOKEN_EXPIRED`，`TOKEN_INVALID` | 重新取得 token |
| 2006，2007，2010，2011 | `API_KEY_INVALID`，`API_KEY_EXPIRED`，`API_KEY_NOT_FOUND`，`API_KEY_DISABLED` | 在控制台核对密钥状态 |
| 42903 | `SANDBOX_DAILY_LIMIT` | 沙箱密钥当日次数用尽，北京时间零点重置 |

这些错误都不重试，完整码表见[错误码](page:api-errors)。
