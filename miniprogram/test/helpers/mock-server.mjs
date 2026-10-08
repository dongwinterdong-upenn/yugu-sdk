// Starts tools/mock-server/server.mjs on a free port for one test file.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { REPO_ROOT } from './common.mjs';

const SERVER = path.join(REPO_ROOT, 'tools/mock-server/server.mjs');

export const MOCK_APP_KEY = 'mock-app-key';
export const MOCK_SECRET = 'mock-secret-key';
export const MOCK_TOKEN = 'mock-jwt-token';

export async function startMockServer({ processingMs = 20, idemWaitMs } = {}) {
  const env = { ...process.env };
  if (idemWaitMs !== undefined) env.MOCK_IDEM_WAIT_MS = String(idemWaitMs);
  const proc = spawn(process.execPath, [SERVER, '--port', '0', '--processing-ms', String(processingMs)], {
    stdio: ['ignore', 'pipe', 'inherit'],
    env,
  });
  const port = await new Promise((resolve, reject) => {
    let buf = '';
    proc.stdout.on('data', (d) => {
      buf += d.toString();
      const line = buf.split('\n')[0];
      if (buf.includes('\n')) {
        try {
          resolve(JSON.parse(line).port);
        } catch (e) {
          reject(new Error('mock server printed ' + line));
        }
      }
    });
    proc.once('exit', (code) => reject(new Error('mock server exited with ' + code)));
  });
  const base = `http://127.0.0.1:${port}`;
  const call = async (method, p, body) => {
    const r = await fetch(base + p, { method, body: body === undefined ? undefined : JSON.stringify(body) });
    return r.json();
  };
  return {
    port,
    baseUrl: base,
    wsBaseUrl: `ws://127.0.0.1:${port}`,
    reset: () => call('POST', '/__mock/reset'),
    faults: (faults, extra = {}) => call('POST', '/__mock/faults', { faults, ...extra }),
    log: () => call('GET', '/__mock/log'),
    billing: () => call('GET', '/__mock/billing'),
    async stop() {
      if (proc.exitCode !== null) return;
      const exited = new Promise((r) => proc.once('exit', r));
      proc.kill('SIGTERM');
      const t = setTimeout(() => proc.kill('SIGKILL'), 3000);
      await exited;
      clearTimeout(t);
    },
  };
}
