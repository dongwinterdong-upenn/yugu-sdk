# 语音合成。被签名参数为请求体顶层的非空标量字段，按键名排序
BODY='{"text":"你好世界","language":"zh-CN","voice":"xiaoyan","format":"mp3"}'
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
SIGN=$(printf 'format=mp3&language=zh-CN&text=你好世界&voice=xiaoyan' | openssl dgst -sha256 -hmac "$YUGU_SECRET_KEY" -binary | base64)

curl -sS https://open.shengzhiai.com/api/v1/tts/generate \
  -H "Content-Type: application/json" \
  -H "X-App-Key: $YUGU_APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGN" \
  -H "Idempotency-Key: $(openssl rand -hex 16)" \
  -d "$BODY"
