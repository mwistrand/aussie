const endpoint = process.env.GATEWAY_WEBSOCKET_URL;
const connectionCount = Number(process.env.CONNECTION_COUNT || 1);
const holdSeconds = Number(process.env.HOLD_SECONDS || 30);
const expectedCloseCode = Number(process.env.EXPECTED_CLOSE_CODE || 1000);
const closeAfterOpen = process.env.CLOSE_AFTER_OPEN !== "false";

if (!Number.isInteger(connectionCount) || connectionCount < 1 || !Number.isFinite(holdSeconds) || holdSeconds <= 0) {
  console.error("CONNECTION_COUNT must be a positive integer and HOLD_SECONDS must be positive");
  process.exit(2);
}

if (connectionCount > 1) {
  const sockets = [];
  let opened = 0;
  let closed = 0;
  let failed = false;
  let closing = false;
  let holdTimeout;
  let deadline;

  const finish = (exitCode) => {
    clearTimeout(holdTimeout);
    clearTimeout(deadline);
    for (const socket of sockets) {
      if (socket.readyState < WebSocket.CLOSING) {
        socket.close(1000, "qualification complete");
      }
    }
    process.exitCode = exitCode;
  };

  const fail = (message) => {
    if (failed) return;
    failed = true;
    console.error(message);
    finish(1);
  };

  deadline = setTimeout(() => fail(`WebSocket capacity opened ${opened}/${connectionCount} connections`), 10000);
  for (let index = 0; index < connectionCount; index += 1) {
    const socket = new WebSocket(endpoint);
    sockets.push(socket);
    socket.addEventListener("open", () => {
      opened += 1;
      if (opened === connectionCount) {
        clearTimeout(deadline);
        console.log(`WebSocket capacity opened: connections=${opened} hold=${holdSeconds}s`);
        holdTimeout = setTimeout(() => {
          closing = true;
          for (const openSocket of sockets) {
            openSocket.close(1000, "capacity qualification complete");
          }
        }, holdSeconds * 1000);
      }
    });
    socket.addEventListener("error", () => fail(`WebSocket capacity connection ${index + 1} failed`));
    socket.addEventListener("close", (event) => {
      if (!closing) {
        fail(`WebSocket capacity connection ${index + 1} closed before the hold completed`);
        return;
      }
      if (event.code !== 1000 || !event.wasClean) {
        fail(
          `WebSocket capacity connection ${index + 1} closed unexpectedly: `
            + `code=${event.code} wasClean=${event.wasClean}`,
        );
        return;
      }
      closed += 1;
      if (!failed && closed === connectionCount) {
        console.log(`WebSocket capacity closed: connections=${closed}`);
        finish(0);
      }
    });
  }
} else {
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
}
