const http = require("node:http");
const crypto = require("node:crypto");

const server = http.createServer((request, response) => {
  if (request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"status":"healthy"}');
    return;
  }

  response.writeHead(200, { "content-type": "application/json" });
  response.end(JSON.stringify({ path: request.url }));
});

server.on("upgrade", (request, socket) => {
  if (request.url !== "/ws/echo") {
    socket.destroy();
    return;
  }

  const accept = crypto
    .createHash("sha1")
    .update(request.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
    .digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );
  socket.on("error", () => socket.destroy());
});

server.listen(3000, "0.0.0.0");
