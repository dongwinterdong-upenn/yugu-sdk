# 微信小程序 SDK 示例工程

可直接导入微信开发者工具的完整小程序工程，通过 npm 引用 `@shengzhiai/yugu-miniprogram-sdk@2.0.0`，演示录音，整段评测，实时评测与结果展示，页面卸载时释放全部资源。

| 文件 | 内容 |
|---|---|
| `package.json` | 依赖 `@shengzhiai/yugu-miniprogram-sdk` `2.0.0` |
| `.npmrc` | `@shengzhiai` 作用域的 npm 源 `https://open.shengzhiai.com/npm/` |
| `project.config.json` | 开发者工具工程配置，AppID 为测试号 `touristappid` |
| `config.js` | 鉴权与服务地址 |
| `pages/index/` | 录音，整段评测，实时评测，结果展示 |

## 准备

| 工具 | 版本 |
|---|---|
| 微信开发者工具 | 稳定版，支持构建 npm |
| Node.js 与 npm | Node.js 18 及以上 |
| 小程序基础库 | `2.20.1` 及以上，工程默认调试基础库 `3.0.0` |

## 运行步骤

1. 取得示例工程：克隆 `https://open.shengzhiai.com/git/yugu-sdk.git`，进入 `demos/miniprogram-demo` 目录。
2. 安装依赖：在该目录执行 `npm install`。`.npmrc` 已指定 `@shengzhiai` 的 npm 源，安装后出现 `node_modules/@shengzhiai/yugu-miniprogram-sdk`。
3. 导入工程：打开微信开发者工具，选择“导入项目”，目录选 `demos/miniprogram-demo`。AppID 可以用自己的小程序 AppID，没有时保留测试号。
4. 构建 npm：点击菜单“工具”，选择“构建 npm”，完成后工程里出现 `miniprogram_npm/@shengzhiai/yugu-miniprogram-sdk`。
5. 填写凭据：打开 `config.js`，填入 token，或者沙箱 appKey 与 secretKey。沙箱密钥的申请方式见仓库根目录的 `SANDBOX.md`。
6. 配置域名：使用自己的 AppID 时，在小程序管理后台把 `https://open.shengzhiai.com` 加入 request 合法域名，把 `wss://open.shengzhiai.com` 加入 socket 合法域名。只在开发者工具里试用时，可以在“详情”，“本地设置”里勾选“不校验合法域名，web-view，TLS 版本以及 HTTPS 证书”。
7. 编译运行：点击“编译”，首次录音时允许使用麦克风。

```bash
git clone https://open.shengzhiai.com/git/yugu-sdk.git
cd yugu-sdk/demos/miniprogram-demo
npm install
```

## 页面操作

| 按钮 | 行为 |
|---|---|
| 开始录音，停止录音 | 用 SDK 录音器录 16000 Hz 单声道 PCM，停止后显示时长与大小 |
| 整段评测 | 把录音结果直接交给 `client.evaluate`，显示总分，维度分与逐字得分 |
| 实时评测，结束实时评测 | 录音帧经 `recorder.pipeTo(session)` 推入实时评测会话，结束录音即结束会话，事件区显示重连与预检信息 |

题型，语言，参考文本与参考拼音可以在页面顶部修改。connected 与 open 两种题型的分项也会显示在维度区。

## 资源释放

页面 `onUnload` 依次调用 `session.close()`，`recorder.release()`，`client.close()`，三个接口都可以重复调用，麦克风在停止录音，释放录音器与录音出错时都会释放。

## 离线试用

没有网络或还没有密钥时，可以连仓库自带的平台模拟服务：

1. 在仓库根目录执行 `cd tools/mock-server && npm ci && node server.mjs --port 18900`。
2. 把 `config.js` 改为 `auth: { appKey: 'mock-app-key', secretKey: 'mock-secret-key' }`，`baseUrl: 'http://127.0.0.1:18900'`，`wsBaseUrl: 'ws://127.0.0.1:18900'`。
3. 在开发者工具里勾选不校验合法域名，然后编译运行。模拟服务返回固定的样例结果，只用于熟悉接入流程。

## 常见问题

| 现象 | 处理 |
|---|---|
| 构建 npm 后找不到模块 | 确认 `package.json` 与 `project.config.json` 在同一目录，重新执行“构建 npm” |
| 提示 url not in domain list | 按第 6 步配置合法域名，或在本地设置里关闭域名校验 |
| 录音报错误码 90201 | 用户拒绝了麦克风授权，在小程序设置页重新打开麦克风权限 |
| 评测报错误码 2003 | 检查 appKey 与 secretKey 是否成对，设备时间是否准确 |
| 页面顶部提示填写凭据 | `config.js` 里没有 token，也没有成对的 appKey 与 secretKey |
