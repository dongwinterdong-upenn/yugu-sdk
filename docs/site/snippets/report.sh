# 报告查询。被签名参数为空集合，recordId 是路径的一段
RECORD_ID=eval_a31712f40cbf
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
SIGN=$(printf '' | openssl dgst -sha256 -hmac "$YUGU_SECRET_KEY" -binary | base64)

curl -sS "https://open.shengzhiai.com/api/v1/report/$RECORD_ID" \
  -H "X-App-Key: $YUGU_APP_KEY" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIGN"
