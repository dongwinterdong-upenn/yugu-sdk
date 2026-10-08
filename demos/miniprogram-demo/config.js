// 示例工程的接入配置。
//
// 鉴权二选一：
// 1. token：由自有服务端调用开放平台换取的短期 JWT，正式环境只用这种方式；
// 2. appKey 与 secretKey：只用于沙箱联调，不要把正式环境的 secretKey 打进小程序包。
//
// 沙箱密钥的申请方式与额度见 SDK 仓库的 SANDBOX.md。
module.exports = {
  auth: {
    token: ''
    // appKey: 'sandbox-xxxx',
    // secretKey: 'xxxx'
  },
  baseUrl: 'https://open.shengzhiai.com',
  wsBaseUrl: 'wss://open.shengzhiai.com'
};
