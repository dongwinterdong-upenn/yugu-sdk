# SDK 文档站生成器

生成 `https://open.shengzhiai.com/sdk/v2/` 的静态站点。页面内容来自仓库里的 Markdown，`spec/openapi.yaml`，`spec/errors.json` 与 `spec/fixtures`，代码示例从各端 README 按章节摘取，文档站与各端手册始终是同一份代码。

## 构建

```bash
cd tools/docs-site && npm ci && cd ../..
node tools/docs-site/build.mjs --repo . --out /tmp/yugu-docs
```

构建产物为纯静态文件：每个页面一个目录，内含 `index.html` 与同名的 `index.md`，另有 `llms.txt`，`llms-full.txt`，`sitemap.xml`，搜索索引与原样发布的规范文件。样式，脚本，字体按内容哈希命名。构建结束前检查全部站内链接与锚点，有一处失效即以非零状态退出。`ci/run-all.sh docs` 执行同样的构建，发布由 `tools/registry/release.sh` 完成。

## 目录

| 路径 | 内容 |
|---|---|
| `docs/site/site.config.mjs` | 分区，导航，页面地址，仓库文件与页面的对应关系，旧地址跳转 |
| `docs/site/pages/` | 新写的页面，`_` 开头的文件是页面片段 |
| `docs/site/snippets/` | 直连协议与签名的示例代码，均经沙箱或测试向量验证 |
| `docs/site/data/` | 沙箱实录的实时评测帧序列与报告查询返回 |
| `docs/site/legacy-anchors.json` | 第一版站点各页面的锚点，用于把原地址跳转到新页面的对应章节 |
| `tools/docs-site/lib/` | 渲染，组件，示意图，搜索索引，链接检查 |
| `tools/docs-site/assets/` | 样式，脚本，站点图标 |

## 页面写法

页面正文是 Markdown，组件写在围栏代码块里，内容为 YAML：

| 组件 | 用途 |
|---|---|
| `include` | 引入仓库文档的一节，`ref` 写成 `CONTRACT.md#6 幂等`，可用 `from` 与 `until` 截取 |
| `tabs` | 按平台切换的代码，`ref` 指向 README 某一节里的第 `n` 个代码块，`file` 指向示例文件 |
| `sections` | 按平台切换的整段内容，例如快速开始 |
| `cards`，`facts`，`hero` | 卡片，要点与首页头部 |
| `endpoint`，`aside` | 接口地址条与接口页右侧的示例栏 |
| `fixture`，`frames` | 线上真实返回与实时评测帧序列 |
| `diagram` | 分层结构，时序与状态图，图元定义在 `lib/diagrams.mjs` |
| `snippet`，`lookup`，`apiindex` | 单个代码块，错误码定位，接口一览 |

站内链接写成 `page:<页面编号>`，仓库文档写成相对路径，构建时换成站点地址。提示框写成以 `说明：`，`提示：`，`注意：`，`警告：` 开头的引用块。
