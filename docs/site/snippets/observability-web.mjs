import { YuguClient } from '@shengzhiai/yugu-web-sdk';

const metrics = { observe: (/** @type {unknown[]} */ ...values) => console.log(...values) };
// [START logging]
const client = new YuguClient({
  token: process.env.YUGU_TOKEN,
  logLevel: 'INFO',
  logger: (level, tag, message, error) => console.log(level, tag, message, error ?? ''),
  eventListener: {
    onRequestEnd: (op, httpStatus, latencyMs, attempts) => metrics.observe(op, httpStatus, latencyMs, attempts),
    onRetry: (op, attempt, delayMs, error) => console.warn('retry', op, attempt, delayMs, error.code),
    onReconnect: (sessionId, attempt, succeeded) => console.warn('reconnect', sessionId, attempt, succeeded),
  },
});
// [END logging]
await client.close();
