const endpoint = process.env.GATEWAY_WEBSOCKET_URL;
const expectedCloseCode = Number(process.env.EXPECTED_CLOSE_CODE || 1000);
const closeAfterOpen = process.env.CLOSE_AFTER_OPEN !== "false";
const timeout = setTimeout(() => {
  console.error("WebSocket handshake timed out");
  process.exit(1);
}, 10000);

const socket = new WebSocket(endpoint);
socket.addEventListener("open", () => {
  clearTimeout(timeout);
  console.log(`WebSocket handshake passed: ${endpoint}`);
  if (closeAfterOpen) {
    socket.close(1000, "qualification complete");
  }
});
socket.addEventListener("close", (event) => {
  clearTimeout(timeout);
  if (event.code !== expectedCloseCode) {
    console.error(`WebSocket closed with unexpected code ${event.code}; expected ${expectedCloseCode}`);
    process.exitCode = 1;
  } else {
    console.log(`WebSocket closed with expected code ${event.code}`);
  }
});
socket.addEventListener("error", () => {
  clearTimeout(timeout);
  console.error("WebSocket handshake failed");
  process.exitCode = 1;
});
