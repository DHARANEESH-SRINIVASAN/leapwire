import net from 'node:net';

const listen = Number(process.env.LISTEN || 8098);
const target = Number(process.env.TARGET || 8099);
let rtt = Number(process.env.RTT || 0);

const control = net.createServer((sock) => {
  sock.on('data', (d) => {
    rtt = Number(String(d).trim());
    sock.end(`rtt=${rtt}\n`);
  });
});
control.listen(listen + 100, '127.0.0.1');

function delayedPipe(from, to) {
  let chain = Promise.resolve();
  from.on('data', (chunk) => {
    const half = rtt / 2;
    const due = Date.now() + half;
    chain = chain.then(() => new Promise((r) => setTimeout(r, Math.max(0, due - Date.now())))).then(() => {
      if (!to.destroyed) to.write(chunk);
    });
  });
  from.on('end', () => { chain.then(() => to.end()); });
  from.on('error', () => to.destroy());
}

net.createServer((client) => {
  const upstream = net.connect(target, '127.0.0.1');
  upstream.setNoDelay(true);
  client.setNoDelay(true);
  delayedPipe(client, upstream);
  delayedPipe(upstream, client);
  upstream.on('error', () => client.destroy());
  client.on('error', () => upstream.destroy());
}).listen(listen, '127.0.0.1', () => console.log(`rtt proxy ${listen} -> ${target}`));
