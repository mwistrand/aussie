const net = require("node:net");
const http = require("node:http");

// Accept OTLP connections without reading or replying: exporter backpressure must not block the gateway.
let exporterConnections = 0;
net.createServer((socket) => {
  exporterConnections += 1;
  socket.on("error", () => {});
}).listen(4317, "0.0.0.0");

http.createServer((request, response) => {
  if (request.url !== "/connections") {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { "content-type": "text/plain" });
  response.end(String(exporterConnections));
}).listen(4318, "0.0.0.0");
