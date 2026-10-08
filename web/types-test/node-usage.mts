// Compile-only test for projects without the DOM library (Node services, NodeNext resolution).
// The declarations must compile with skipLibCheck off and no DOM types available.
import { YuguClient, isRetryable, YuguError, precheckAudio, AudioPrecheck } from '@shengzhiai/yugu-web-sdk';
import type { FetchLike, FetchResponseLike, EvalResult, WebSocketConstructorLike } from '@shengzhiai/yugu-web-sdk';

declare const audio: ArrayBuffer;
declare const nodeFetch: (url: string, init: { method: string; headers: Record<string, string>; body?: ArrayBuffer | string }) => Promise<FetchResponseLike>;
declare const WsClass: new (url: string, protocols?: string[]) => { send(data: unknown): void; close(): void };

const fetchImpl: FetchLike = (url, init) => nodeFetch(url, init);
const ws: WebSocketConstructorLike = WsClass;

const client = new YuguClient({
  baseUrl: 'https://open.shengzhiai.com',
  appKey: 'sandbox-app-key',
  secretKey: 'sandbox-secret',
  fetch: fetchImpl,
  WebSocket: ws,
  audioPrecheck: AudioPrecheck.REJECT,
});

export async function run(): Promise<number | null> {
  const report = precheckAudio(audio);
  if (report.warnings.length) return null;
  try {
    const r: EvalResult = await client.evaluate(audio, { coreType: 'word', referenceText: 'apple', language: 'en-US' });
    return r.overall;
  } catch (e) {
    if (e instanceof YuguError && isRetryable(e)) return null;
    throw e;
  } finally {
    await client.close();
  }
}
