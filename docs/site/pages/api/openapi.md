`spec/openapi.yaml` 是 OpenAPI `3.0.3` 描述，覆盖原生整段评测，声通兼容整段评测，语音合成与报告查询四个 REST 接口，持续集成每次构建都校验格式。实时评测的 WebSocket 协议无法用 OpenAPI 3.0 描述，见[原生实时评测](page:api-ws-evaluate)。

## 下载

| 内容 | 文件 | 地址 |
|---|---|---|
| OpenAPI 描述 | [openapi.yaml](/sdk/v2/spec/openapi.yaml) | `https://open.shengzhiai.com/sdk/v2/spec/openapi.yaml` |
| 错误码表 | [errors.json](/sdk/v2/spec/errors.json) | `https://open.shengzhiai.com/sdk/v2/spec/errors.json` |
| 签名测试向量 | [vectors.json](/sdk/v2/spec/fixtures/sign/vectors.json) | `https://open.shengzhiai.com/sdk/v2/spec/fixtures/sign/vectors.json` |

## 生成客户端

```bash
npx @openapitools/openapi-generator-cli generate \
  -i https://open.shengzhiai.com/sdk/v2/spec/openapi.yaml \
  -g python -o yugu-client
```

`-g` 换成 `go`，`php`，`csharp`，`rust` 等生成器名即可得到对应语言的客户端。生成的客户端不含签名逻辑，要在请求拦截器里补上 `X-App-Key`，`X-Timestamp`，`X-Nonce`，`X-Signature` 四个请求头，被签名参数与拼接规则见[签名与鉴权](page:api-auth)，各语言的签名函数见[签名示例](page:api-auth#签名示例)。生成的客户端发送 `config` 段时若带文件名，被签名参数改为去掉 `config` 后的表单参数加上 config JSON 的顶层非空标量字段。

## 描述内容

| 部分 | 内容 |
|---|---|
| `paths` | 四个 REST 接口的请求体，参数与状态码 |
| `components.schemas` | 评测配置，评测结果，逐字与分句结构，语音合成请求与响应，错误信封 |
| `components.securitySchemes` | 签名四个请求头与 Bearer token |
| `components.parameters` | 幂等键请求头 `Idempotency-Key` |
