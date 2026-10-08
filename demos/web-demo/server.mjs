#!/usr/bin/env node
// Static server of the demo page. Serves public/ and the installed SDK bundle under /sdk/.
//
//   node server.mjs [--port 8080] [--host 127.0.0.1] [--api http://127.0.0.1:18900]
//
// With --api the server also forwards the platform paths (REST and WebSocket) to that address,
// so the page can talk to a local platform without CORS. Without --api the page calls the
// platform directly (default https://open.shengzhiai.com).
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(HERE, 'public');
const SDK_DIST = path.join(HERE, 'node_modules/@shengzhiai/yugu-web-sdk/dist');

const args = process.argv.slice(2);
const arg = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
};
const PORT = Number(arg('--port', process.env.PORT || '8080'));
const HOST = arg('--host', '127.0.0.1');
const API = arg('--api', process.env.YUGU_DEMO_API || '');
const api = API ? new URL(API) : null;

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.wav': 'audio/wav',
  '.svg': 'image/svg+xml',
};

// Platform paths forwarded with --api: REST, compat coreType paths and the WebSocket endpoints.
const PLATFORM_PATH = /^\/(api\/v1\/|(word|sent|para|alpha)\.eval(\.pro|\.cn)?$|pinyin$|tts\/)/;

function sendFile(res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

function safeJoin(root, urlPath) {
  const p = path.normalize(path.join(root, decodeURIComponent(urlPath)));
  return p.startsWith(root) ? p : null;
}

function proxy(req, res) {
  const upstream = http.request(
    { hostname: api.hostname, port: api.port || 80, path: req.url, method: req.method, headers: { ...req.headers, host: api.host } },
    (up) => {
      res.writeHead(up.statusCode, up.headers);
      up.pipe(res);
    },
  );
  upstream.on('error', (e) => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ code: 50200, message: `demo proxy: ${e.message}` }));
  });
  req.pipe(upstream);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://demo');
  if (url.pathname === '/demo-config.json') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(JSON.stringify({ proxied: !!api, baseUrl: api ? '' : 'https://open.shengzhiai.com' }));
    return;
  }
  if (api && PLATFORM_PATH.test(url.pathname)) {
    proxy(req, res);
    return;
  }
  if (url.pathname.startsWith('/sdk/')) {
    const file = safeJoin(SDK_DIST, url.pathname.slice('/sdk/'.length));
    if (file) sendFile(res, file);
    else res.writeHead(400).end();
    return;
  }
  const file = safeJoin(PUBLIC, url.pathname === '/' ? 'index.html' : url.pathname);
  if (file) sendFile(res, file);
  else res.writeHead(400).end();
});

// WebSocket forwarding for --api: replay the upgrade request and pipe both ways.
server.on('upgrade', (req, socket, head) => {
  if (!api) {
    socket.destroy();
    return;
  }
  const up = net.connect(Number(api.port || 80), api.hostname, () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      const k = req.rawHeaders[i];
      lines.push(`${k}: ${k.toLowerCase() === 'host' ? api.host : req.rawHeaders[i + 1]}`);
    }
    up.write(`${lines.join('\r\n')}\r\n\r\n`);
    if (head && head.length) up.write(head);
    up.pipe(socket);
    socket.pipe(up);
  });
  const close = () => {
    up.destroy();
    socket.destroy();
  };
  up.on('error', close);
  socket.on('error', close);
});

if (!fs.existsSync(SDK_DIST)) {
  console.error('SDK 未安装：先运行 npm install，或 node scripts/install-tarball.mjs <tgz>');
  process.exit(1);
}

server.listen(PORT, HOST, () => {
  const addr = server.address();
  console.log(JSON.stringify({ url: `http://${HOST}:${addr.port}/`, api: API || null }));
});
process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
