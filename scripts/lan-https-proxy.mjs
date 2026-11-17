// Minimal TLS reverse proxy for LAN testing of ThreadForge.
// Browsers only allow camera/microphone (getUserMedia) on a secure context. A self-signed
// cert for the LAN IP gives the app a secure context on other devices; accept the browser
// warning once on each device. For a real deployment use nginx/haproxy + a trusted cert
// (see frontend.md) instead of this script.
import https from 'node:https';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const LAN_IP = process.env.LAN_IP || '127.0.0.1';
const LISTEN_PORT = 8443;
const BACKEND_HOST = '127.0.0.1';
const BACKEND_PORT = 8080;

const tlsDir = path.resolve('data/tls');
const key = fs.readFileSync(path.join(tlsDir, 'key.pem'));
const cert = fs.readFileSync(path.join(tlsDir, 'cert.pem'));

function forwardedHeaders(req, clientIp) {
  const host = `${LAN_IP}:${LISTEN_PORT}`;
  return {
    ...req.headers,
    host,
    'x-real-ip': clientIp,
    // SET, never append: the app reads the leftmost X-Forwarded-For as the client address.
    'x-forwarded-for': clientIp,
    'x-forwarded-proto': 'https',
    'x-forwarded-host': host,
    'x-forwarded-port': String(LISTEN_PORT),
  };
}

const server = https.createServer({ key, cert }, (req, res) => {
  const upstream = http.request(
    {
      hostname: BACKEND_HOST,
      port: BACKEND_PORT,
      method: req.method,
      path: req.url,
      headers: forwardedHeaders(req, req.socket.remoteAddress),
    },
    (upstreamRes) => {
      // Dev Keycloak speaks plain HTTP on :8081 while this proxy speaks HTTPS on :8443.
      // Under browsers' Schemeful Same-Site rules, http:// -> https:// on the same IP is
      // cross-site, so SameSite=Strict withholds JSESSIONID on the OAuth2 callback redirect.
      // Relaxing to Lax here (dev LAN proxy only) keeps top-level OAuth2 callbacks working.
      const headers = { ...upstreamRes.headers };
      if (headers['set-cookie']) {
        const list = Array.isArray(headers['set-cookie'])
          ? headers['set-cookie']
          : [headers['set-cookie']];
        headers['set-cookie'] = list.map((c) => c.replace(/;\s*SameSite=Strict/gi, '; SameSite=Lax'));
      }
      if ((upstreamRes.statusCode || 0) >= 400) {
        console.error(`${req.method} ${req.url} -> ${upstreamRes.statusCode}`);
      }
      res.writeHead(upstreamRes.statusCode || 502, headers);
      upstreamRes.pipe(res);
    },
  );
  upstream.on('error', (err) => {
    console.error('upstream error:', err.message);
    if (!res.headersSent) res.writeHead(502);
    res.end('Bad gateway');
  });
  req.pipe(upstream);
});

// STOMP-over-WebSocket needs the raw upgrade tunneled through the proxy.
server.on('upgrade', (req, clientSocket, head) => {
  const upstream = http.request({
    hostname: BACKEND_HOST,
    port: BACKEND_PORT,
    method: 'GET',
    path: req.url,
    headers: forwardedHeaders(req, clientSocket.remoteAddress),
  });
  upstream.on('upgrade', (upstreamRes, upstreamSocket, upstreamHead) => {
    let raw = 'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n';
    for (const [name, value] of Object.entries(upstreamRes.headers)) {
      if (['connection', 'upgrade', 'transfer-encoding'].includes(name.toLowerCase())) continue;
      raw += `${name}: ${Array.isArray(value) ? value.join(', ') : value}\r\n`;
    }
    clientSocket.write(`${raw}\r\n`);
    if (upstreamHead.length) clientSocket.write(upstreamHead);
    if (head.length) upstreamSocket.write(head);
    upstreamSocket.pipe(clientSocket);
    clientSocket.pipe(upstreamSocket);
  });
  // An unauthenticated /ws handshake comes back as a 302, not a 101. Without this the browser
  // hangs on the login redirect instead of being sent to Keycloak.
  upstream.on('response', (upstreamRes) => {
    let raw = `HTTP/1.1 ${upstreamRes.statusCode} ${upstreamRes.statusMessage || ''}\r\n`;
    for (const [name, value] of Object.entries(upstreamRes.headers)) {
      if (['connection', 'transfer-encoding'].includes(name.toLowerCase())) continue;
      raw += `${name}: ${Array.isArray(value) ? value.join(', ') : value}\r\n`;
    }
    clientSocket.write(`${raw}\r\n`);
    upstreamRes.pipe(clientSocket);
  });
  upstream.on('error', () => clientSocket.destroy());
  upstream.end();
});

server.listen(LISTEN_PORT, '0.0.0.0', () => {
  console.log(`HTTPS proxy listening on https://${LAN_IP}:${LISTEN_PORT} -> ${BACKEND_HOST}:${BACKEND_PORT}`);
});
