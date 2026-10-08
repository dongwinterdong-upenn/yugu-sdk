# 签名：业务参数去掉空值，按键名字典序拼成 k1=v1&k2=v2，不做 URL 编码
PAYLOAD='coreType=sent.eval.cn&language=zh-CN&refText=北京你好'
printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac 'test_secret_key_123' -binary | base64
# 输出 A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=
