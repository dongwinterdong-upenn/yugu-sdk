# 原生整段评测。被签名参数只有 config 段的 JSON 原文
CONFIG='{"coreType":"sentence","referenceText":"今天天气很好","language":"zh-CN"}'
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
SIGN=$(printf 'config=%s' "$CONFIG" | openssl dgst -sha256 -hmac "$YUGU_SECRET_KEY" -binary | base64)

curl -sS https://open.shengzhiai.com/api/v1/evaluate \
  -H "X-App-Key: $YUGU_APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGN" \
  -H "Idempotency-Key: $(openssl rand -hex 16)" \
  -F "audio=@audio.wav" \
  -F "config=$CONFIG;type=application/json"
