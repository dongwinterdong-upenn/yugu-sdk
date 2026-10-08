import crypto from 'node:crypto';

function sign(params, secretKey) {
  // 丢弃 null 与空串，按键名字典序拼成 k1=v1&k2=v2，不做 URL 编码
  const payload = Object.keys(params).sort()
    .filter((k) => params[k] !== null && params[k] !== undefined && params[k] !== '')
    .map((k) => `${k}=${params[k]}`).join('&');
  return crypto.createHmac('sha256', secretKey).update(payload, 'utf8').digest('base64');
}

console.log(sign({ coreType: 'sent.eval.cn', language: 'zh-CN', refText: '北京你好' }, 'test_secret_key_123'));
// 输出 A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=
