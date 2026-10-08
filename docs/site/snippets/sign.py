import base64, hashlib, hmac

def sign(params: dict, secret_key: str) -> str:
    # 丢弃 None 与空串，按键名字典序拼成 k1=v1&k2=v2，不做 URL 编码
    payload = '&'.join(f'{k}={v}' for k, v in sorted(params.items()) if v not in (None, ''))
    digest = hmac.new(secret_key.encode('utf-8'), payload.encode('utf-8'), hashlib.sha256).digest()
    return base64.b64encode(digest).decode('ascii')

print(sign({'coreType': 'sent.eval.cn', 'language': 'zh-CN', 'refText': '北京你好'}, 'test_secret_key_123'))
# 输出 A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=
