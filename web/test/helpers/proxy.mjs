// TCP proxy in front of the mock server to simulate a network drop (black hole) and a network
// switch (all sockets reset).
import net from 'node:net';

export async function startProxy(targetPort) {
  const conns = new Set();
  let blackhole = false;
  let offline = false;
  const reset = (sock) => {
    if (typeof sock.resetAndDestroy === 'function') sock.resetAndDestroy();
    else sock.destroy();
  };
  const server = net.createServer((client) => {
    if (offline) {
      client.on('error', () => {});
      reset(client);
      return;
    }
    const upstream = net.connect(targetPort, '127.0.0.1');
    const c = { client, upstream, bornInHole: blackhole, touchedHole: blackhole };
    conns.add(c);
    const cleanup = () => {
      conns.delete(c);
      client.destroy();
      upstream.destroy();
    };
    client.on('data', (d) => {
      if (blackhole) c.touchedHole = true;
      else upstream.write(d);
    });
    upstream.on('data', (d) => {
      if (blackhole) c.touchedHole = true;
      else client.write(d);
    });
    for (const s of [client, upstream]) {
      s.on('error', cleanup);
      s.on('close', cleanup);
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return {
    port: server.address().port,
    /** Drop every byte in both directions, like a lost network. Ending it kills the stale sockets. */
    setBlackhole(on) {
      if (on) {
        blackhole = true;
        for (const c of conns) c.touchedHole = true;
        return;
      }
      blackhole = false;
      for (const c of [...conns]) {
        if (c.touchedHole) {
          c.client.destroy();
          c.upstream.destroy();
        }
      }
    },
    /** Reset every connection with RST, like a network switch. */
    resetAll() {
      for (const c of [...conns]) {
        reset(c.client);
        c.upstream.destroy();
      }
    },
    /** Network down: existing connections reset, every new connection refused until back online. */
    setOffline(on) {
      offline = on;
      if (on) this.resetAll();
    },
    get connections() {
      return conns.size;
    },
    async close() {
      for (const c of [...conns]) {
        c.client.destroy();
        c.upstream.destroy();
      }
      await new Promise((r) => server.close(r));
    },
  };
}
