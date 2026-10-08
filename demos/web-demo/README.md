# 优谷雅言 PC Web SDK 演示

独立的演示工程，通过 npm 仓库安装 `@shengzhiai/yugu-web-sdk@2.0.0`，在浏览器里录音或选择音频文件，调用整段评测与实时评测，展示总分，维度分与逐字分。工程只依赖 SDK 本体，静态服务器用 Node.js 内置模块实现。

## 目录结构

| 路径 | 说明 |
|---|---|
| `package.json` | 依赖 `@shengzhiai/yugu-web-sdk` 的 `2.0.0` 版本 |
| `.npmrc` | 把 `@shengzhiai` 作用域指向 `https://open.shengzhiai.com/npm/` |
| `server.mjs` | 静态服务器，`/sdk/` 路径提供已安装的 SDK 构建文件 |
| `public/index.html` 与 `public/app.js` | 演示页面与页面逻辑 |
| `scripts/install-tarball.mjs` | 从本地 tgz 包离线安装 SDK，供 CI 与离线环境使用 |

## 运行环境

Node.js 18 或更高版本。浏览器为 Chrome，Edge，Firefox 或 Safari 的近期版本。录音与签名需要安全上下文，演示服务器默认监听 `127.0.0.1`，用 `http://127.0.0.1` 或 `http://localhost` 访问即可。

## 安装与启动

```bash
cd demos/web-demo
npm install
npm start
```

`npm install` 读取目录里的 `.npmrc`，从优谷雅言 npm 仓库下载 SDK。启动后终端打印页面地址，默认 `http://127.0.0.1:8080/`，端口可以用 `--port` 修改，例如 `node server.mjs --port 9000`。

## 页面用法

1. 填写服务地址与凭据。正式环境的浏览器页面使用接入方后端签发的 token。appKey 与 secretKey 只用于沙箱试用，secretKey 写在浏览器里等于公开。
2. 选择题型与语言，填写参考文本。
3. 点击开始录音，朗读后点击停止录音，或者选择一个 WAV 或 MP3 文件。
4. 点击整段评测，页面显示总分，维度分与逐字分，事件区显示请求，重试与预检日志。
5. 点击实时评测，录音器把 640 字节分帧直接送入实时会话。再次点击时结束录音，页面等待终评结果。断线时事件区显示重连过程。

页面关闭时调用 `recorder.release()` 与 `client.close()`，释放麦克风与连接。

## 离线安装与 CI

SDK 尚未发布或机器不能访问 npm 仓库时，从 `npm pack` 生成的包离线安装：

```bash
cd web && npm pack --pack-destination /tmp/yugu-pack
cd ../demos/web-demo
node scripts/install-tarball.mjs /tmp/yugu-pack/shengzhiai-yugu-web-sdk-2.0.0.tgz
```

脚本用系统自带的 `tar` 解包，直接写入 `node_modules`，不访问网络。`ci/web.sh` 用同样的方式安装，再通过演示服务器跑一次整段评测与实时评测。

## 对接本地平台模拟服务

联调时可以把演示服务器指向仓库里的平台模拟服务，演示服务器转发 REST 与 WebSocket 请求，页面不涉及跨域：

```bash
node ../../tools/mock-server/server.mjs --port 18900
node server.mjs --api http://127.0.0.1:18900
```

模拟服务的测试凭据为 appKey `mock-app-key` 与 secretKey `mock-secret-key`，在页面的鉴权方式里选择 appKey 与 secretKey 后填写。模拟服务对任何音频都返回固定的评测结果，只用于检查接入流程。
