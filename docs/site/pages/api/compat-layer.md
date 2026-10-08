```include
ref: CONTRACT.md#10 声通平替层
```

## 兼容接口返回体

平替层在外层补齐 `tokenId`，`applicationId`，`userId`，`refText`，`dtLastResponse`。段落逐字条目 `sentences[].details[]` 缺少 `overall` 时补上取自 `scores.overall` 的值，缺少 `pronunciation` 时一并补上，其余内容逐字节不变。

```fixture
file: spec/fixtures/platform/compat_sent.eval.cn_attach_audio_url.json
label: 兼容接口返回体，节选
limitArrays: 2
```

errId 的完整含义见[错误码](page:api-errors#声通平替层-errid)，平替层的接入步骤见[从声通迁移](page:migrate-shengtong)。
