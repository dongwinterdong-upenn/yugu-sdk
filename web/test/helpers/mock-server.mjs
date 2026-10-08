// Spawns tools/mock-server/server.mjs on a free port and wraps its control API.
import { spawn } from 'node:child_process';
import path from 'node:path';
import { REPO } from './common.mjs';

export const MOCK_SERVER = path.join(REPO, 'tools/mock-server/server.mjs');
export const MOCK_CREDS = Object.freeze({ appKey: 'mock-app-key', secretKey: 'mock-secret-key' });
export const MOCK_TOKEN = 'mock-jwt-token';

export async function startMock({ processingMs = 50, env = {} } = {}) {
  const proc = spawn(process.execPath, [MOCK_SERVER, '--port', '0', '--processing-ms', String(processingMs)], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, ...env },
  });
  let stderr = '';
  proc.stderr.on('data', (d) => {
    stderr += d.toString();
  });
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`mock server did not start: ${stderr}`)), 10000);
    let buf = '';
    proc.stdout.on('data', (d) => {
      buf += d.toString();
      const nl = buf.indexOf('\n');
      if (nl >= 0) {
        clearTimeout(timer);
        try {
          resolve(JSON.parse(buf.slice(0, nl)).port);
        } catch (e) {
          reject(e);
        }
      }
    });
    proc.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`mock server exited with ${code}: ${stderr}`));
    });
  });
  const base = `http://127.0.0.1:${port}`;
  const call = async (p, init) => {
    const r = await fetch(base + p, init);
    return r.json();
  };
  return {
    port,
    base,
    wsBase: `ws://127.0.0.1:${port}`,
    reset: () => call('/__mock/reset', { method: 'POST' }),
    faults: (faults, extra = {}) => call('/__mock/faults', { method: 'POST', body: JSON.stringify({ faults, ...extra }) }),
    log: () => call('/__mock/log'),
    billing: () => call('/__mock/billing'),
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
