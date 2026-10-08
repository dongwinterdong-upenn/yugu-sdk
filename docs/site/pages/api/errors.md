```lookup
label: 按错误码查找
placeholder: 例如 40901
```

错误码表版本 2026-10-08，五端 SDK 内置同一份表，机器可读版本为 [errors.json](/sdk/v2/spec/errors.json)。

## 错误的形态

```include
ref: CONTRACT.md#8 错误码与警告码
from: '| 来源 | 形态 |'
```

```include
ref: ERRORS.md#服务端错误码
body: false
```

```include
ref: ERRORS.md#音频质量警告码
body: false
```

```include
ref: ERRORS.md#SDK 本地错误码
body: false
```

```include
ref: ERRORS.md#HTTP 状态兜底归类
body: false
```

```include
ref: ERRORS.md#声通平替层 errId
body: false
```

> 说明：1004 与 1005 按出现位置解释。错误响应与错误帧里是用户禁用与用户锁定，评测结果的 `warning` 里是环境噪声与音频不完整。
