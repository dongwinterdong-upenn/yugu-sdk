## 密钥与鉴权

| 检查项 | 要求 | 依据 |
|---|---|---|
| 生产密钥 | 换成生产环境的 appKey 与 secretKey，沙箱密钥每天 200 次，超出回 42903 | [沙箱环境](page:sandbox) |
| secretKey 位置 | 只在接入方服务端或可信环境，网页，小程序与移动端用 token | [鉴权](page:authentication) |
| 时钟同步 | 服务器开启时间同步，签名时间戳与平台相差超过 300 秒即被拒绝 | [签名与鉴权](page:api-auth) |
| 密钥注入 | 用部署平台的密钥配置注入，不进代码仓库与日志 | [鉴权](page:authentication) |

## 网络

| 检查项 | 要求 | 依据 |
|---|---|---|
| 出口放行 | 放行 `open.shengzhiai.com` 的 443 端口，HTTPS 与 WSS 共用。语音合成返回的音频地址目前位于 `ygyx.dragonai.tech`，播放示范音的客户端一并放行 | [接口概览](page:api) |
| 小程序域名 | request 与 socket 合法域名都登记 `open.shengzhiai.com` | [小程序 SDK](repo:miniprogram/README.md#合法域名) |
| 超时 | 连接 10 秒，读取 120 秒，总时限 300 秒，按业务调整总时限 | [重试与幂等](page:retries) |
| 代理与证书 | 企业代理或自签证书环境按各端说明配置，证书校验失败报 90011 | [安卓 SDK](repo:android/README.md#代理与自定义证书) |

## 计费与幂等

| 检查项 | 要求 | 依据 |
|---|---|---|
| 自动幂等键 | 保持开启，重试与重连不会重复计费 | [重试与幂等](page:retries) |
| 业务幂等 | 需要同一份作业只计费一次时，用作业号作为幂等键 | [幂等](page:api-idempotency) |
| 重试叠加 | 业务代码不再另做重试，改用 SDK 的重试策略 | [重试](page:api-retries) |
| 预检模式 | 需要避免为无效音频付费时设为 `REJECT` | [音频与录音](page:audio) |
| 额度与并发 | 关注 40902 额度不足与 42901 并发超限，按套餐层级扩容 | [错误码](page:api-errors) |

## 实时评测

| 检查项 | 要求 | 依据 |
|---|---|---|
| 缓冲策略 | 需要评分覆盖整段录音时用默认的 `REPLAY`，只评断线之后的音频时用 `DROP` | [实时评测](page:streaming) |
| 重连提示 | 在 `onReconnecting` 与 `onReconnected` 里更新界面 | [实时评测](page:streaming) |
| 终评等待 | 默认 300 秒，界面需要更快反馈时调小 `resultTimeoutMs` | [心跳与断线重连](page:api-ws-reconnect) |
| 麦克风权限 | 安卓运行时申请，iOS 声明用途，网页走 https，小程序声明隐私指引 | [音频与录音](page:audio) |

## 错误处理与观测

| 检查项 | 要求 | 依据 |
|---|---|---|
| 分支处理 | 按错误类别处理，参数，鉴权与额度错误不重试 | [错误处理](page:errors-guide) |
| 排查字段 | 日志里记录错误码，HTTP 状态，幂等键，记录号与尝试次数 | [错误处理](page:errors-guide) |
| 日志级别 | 生产用 `INFO` 或 `WARN`，接入自有日志系统 | [日志与指标](page:observability) |
| 指标 | 接入指标回调 `eventListener`，统计耗时，重试与重连 | [日志与指标](page:observability) |

## 版本与资源

| 检查项 | 要求 | 依据 |
|---|---|---|
| 版本锁定 | 依赖写成具体版本号，升级前读变更记录的破坏性变更一栏 | [变更记录](page:changelog) |
| 客户端实例 | 一个应用一个客户端，退出时调用 `close()` | [生命周期](page:lifecycle) |
| 资源释放 | 页面退出时释放会话与录音器 | [生命周期](page:lifecycle) |
