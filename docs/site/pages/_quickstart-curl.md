没有 SDK 的语言直接调用 REST 接口。签名用 HMAC-SHA256，`openssl` 一行即可算出，被签名参数与拼接规则见[签名与鉴权](page:api-auth)。

```bash
export YUGU_APP_KEY=沙箱appKey YUGU_SECRET_KEY=沙箱secretKey
curl -sSO https://open.shengzhiai.com/sdk/v2/spec/fixtures/audio/zh_short.wav && mv zh_short.wav audio.wav
```

在 `audio.wav` 所在目录执行：

```snippet
docs/site/snippets/evaluate.sh
```
