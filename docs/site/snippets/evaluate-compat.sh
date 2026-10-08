# 声通兼容整段评测。被签名参数为全部文本表单字段，按键名排序
REF_TEXT='今天天气很好'
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
SIGN=$(printf 'language=zh-CN&refText=%s' "$REF_TEXT" | openssl dgst -sha256 -hmac "$YUGU_SECRET_KEY" -binary | base64)

curl -sS https://open.shengzhiai.com/sent.eval.cn \
  -H "X-App-Key: $YUGU_APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGN" \
  -H "Idempotency-Key: $(openssl rand -hex 16)" \
  -F "audio=@audio.wav" \
  -F "refText=$REF_TEXT" \
  -F "language=zh-CN"
