const endpoint = process.env.GATEWAY_WEBSOCKET_URL;
const timeout = setTimeout(() => {
  console.error("WebSocket handshake timed out");
  process.exit(1);
}, 10000);

const socket = new WebSocket(endpoint);
socket.addEventListener("open", () => {
  clearTimeout(timeout);
  console.log(`WebSocket handshake passed: ${endpoint}`);
  socket.close(1000, "qualification complete");
});
socket.addEventListener("close", (event) => {
  clearTimeout(timeout);
  if (event.code !== 1000) {
    console.error(`WebSocket closed with unexpected code ${event.code}`);
    process.exitCode = 1;
  }
});
socket.addEventListener("error", () => {
  clearTimeout(timeout);
  console.error("WebSocket handshake failed");
  process.exitCode = 1;
});
