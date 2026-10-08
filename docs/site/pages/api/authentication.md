```include
ref: CONTRACT.md#2 鉴权
shift: -1
```

## 签名示例

各语言按同一规则计算，结果与契约向量一致即可。

```tabs
sync: sign-lang
items:
  - { label: Shell, file: docs/site/snippets/sign.sh, lang: bash }
  - { label: Python, file: docs/site/snippets/sign.py, lang: python }
  - { label: Node.js, file: docs/site/snippets/sign.mjs, lang: js }
  - { label: Java, file: docs/site/snippets/java/Sign.java, lang: java }
```

## 测试向量

`spec/fixtures/sign/vectors.json` 收录 7 组向量，覆盖契约向量，原生 config 段，兼容表单，语音合成请求体，实时评测握手，空集合与特殊字符，五端 SDK 的签名单测逐条命中。

```fixture
file: spec/fixtures/sign/vectors.json
pick: cases[0]
label: 契约向量
```
