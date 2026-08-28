/**
 * Custom server that combines Next.js with WebSocket support.
 *
 * This server handles both HTTP requests (via Next.js) and WebSocket
 * connections for testing Aussie's WebSocket proxy functionality.
 *
 * WebSocket Endpoints:
 * - /ws/echo (public) - Echoes messages back to sender
 * - /ws/chat (authenticated) - Simple chat with user info from JWT claims
 */

const { createServer } = require("http");
const { parse } = require("url");
const next = require("next");
const { WebSocketServer } = require("ws");

const dev = process.env.NODE_ENV !== "production";
const hostname = process.env.HOSTNAME || "localhost";
const port = parseInt(process.env.PORT || "3000", 10);

const app = next({ dev, hostname, port });
const handle = app.getRequestHandler();

// Store connected chat clients
const chatClients = new Map();

// Test-only API. Gated by two env vars: DEMO_TEST_API_ENABLED=true and a
// DEMO_TEST_API_TOKEN shared secret that callers must echo back in the
// X-Test-Auth header. Both gates exist because this surface can dump captured
// request headers (incl. Authorization), inject failures, and stall routes.
// The token gate ensures that even if the enable flag leaks into a non-test
// deployment, callers without the per-run secret are turned away.
const testApiEnabled = process.env.DEMO_TEST_API_ENABLED === "true";
const testApiToken = process.env.DEMO_TEST_API_TOKEN || "";
const TEST_REQUEST_HISTORY_LIMIT = 200;
// Header names captured at record time. Bearer tokens, cookies, and proxy
// credentials are intentionally absent so that the /__test__/state dump cannot
// be used to exfiltrate credentials when this API is reachable.
const TEST_HISTORY_HEADER_ALLOWLIST = new Set([
  "content-type",
  "content-length",
  "accept",
  "accept-encoding",
  "user-agent",
  "host",
  "x-forwarded-for",
  "x-forwarded-host",
  "x-forwarded-proto",
  "x-request-id",
  "x-correlation-id",
  "traceparent",
  "tracestate",
]);
const testRequestHistory = new Array(TEST_REQUEST_HISTORY_LIMIT);
let testRequestHistoryCount = 0;
const testSlowOverrides = new Map();
const testFailOverrides = new Map();

if (testApiEnabled) {
  console.warn(
    "[DEMO TEST API] /__test__/* endpoints enabled. This surface can dump captured request metadata, inject failures, and stall routes. Never enable in production."
  );
  if (!testApiToken) {
    console.warn(
      "[DEMO TEST API] DEMO_TEST_API_TOKEN is empty - refusing all /__test__/* requests until a per-run secret is provided."
    );
  }
}

function projectAllowedHeaders(headers) {
  const out = {};
  for (const name of Object.keys(headers)) {
    if (TEST_HISTORY_HEADER_ALLOWLIST.has(name.toLowerCase())) {
      out[name] = headers[name];
    }
  }
  return out;
}

function recordTestRequest(req) {
  if (!testApiEnabled) {
    return;
  }
  const parsed = parse(req.url, true);
  const contentLength = parseInt(req.headers["content-length"] || "0", 10);
  const slot = testRequestHistoryCount % TEST_REQUEST_HISTORY_LIMIT;
  testRequestHistory[slot] = {
    method: req.method,
    path: parsed.pathname,
    query: parsed.query,
    headers: projectAllowedHeaders(req.headers),
    contentLength: Number.isNaN(contentLength) ? 0 : contentLength,
    timestamp: new Date().toISOString(),
  };
  testRequestHistoryCount++;
}

function readRequestHistory() {
  const total = testRequestHistoryCount;
  const size = Math.min(total, TEST_REQUEST_HISTORY_LIMIT);
  const start = total - size;
  const out = new Array(size);
  for (let i = 0; i < size; i++) {
    out[i] = testRequestHistory[(start + i) % TEST_REQUEST_HISTORY_LIMIT];
  }
  return out;
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function parseCanonicalInt(raw) {
  if (typeof raw !== "string") {
    return Number.NaN;
  }
  // Strict: reject "100abc", "1e2", "0x10", leading "+", etc.
  if (!/^-?\d+$/.test(raw)) {
    return Number.NaN;
  }
  return Number.parseInt(raw, 10);
}

function isAuthorizedTestCaller(req) {
  if (!testApiToken) {
    return false;
  }
  const provided = req.headers["x-test-auth"];
  return typeof provided === "string" && provided === testApiToken;
}

async function handleTestApi(req, res, pathname) {
  if (!isAuthorizedTestCaller(req)) {
    sendJson(res, 401, { error: "unauthorized" });
    return;
  }
  if (req.method === "GET" && pathname === "/__test__/state") {
    sendJson(res, 200, {
      requests: readRequestHistory(),
      chatClients: getActiveUsers(),
      slowOverrides: Array.from(testSlowOverrides.entries()).map(([route, ms]) => ({ route, ms })),
      failOverrides: Array.from(testFailOverrides.entries()).map(([route, status]) => ({ route, status })),
    });
    return;
  }
  if (req.method === "POST" && pathname === "/__test__/reset") {
    for (let i = 0; i < testRequestHistory.length; i++) {
      testRequestHistory[i] = undefined;
    }
    testRequestHistoryCount = 0;
    testSlowOverrides.clear();
    testFailOverrides.clear();
    sendJson(res, 204, {});
    return;
  }
  if (req.method === "POST" && pathname === "/__test__/slow") {
    const { ms, route } = parse(req.url, true).query;
    const delay = parseCanonicalInt(ms);
    if (!route || typeof route !== "string" || Number.isNaN(delay) || delay < 0) {
      sendJson(res, 400, { error: "missing or invalid ms/route" });
      return;
    }
    testSlowOverrides.set(route, delay);
    sendJson(res, 200, { route, ms: delay });
    return;
  }
  if (req.method === "POST" && pathname === "/__test__/fail") {
    const { status, route } = parse(req.url, true).query;
    const statusNum = parseCanonicalInt(status);
    if (!route || typeof route !== "string" || Number.isNaN(statusNum) || statusNum < 400 || statusNum > 599) {
      sendJson(res, 400, { error: "missing or invalid status/route" });
      return;
    }
    testFailOverrides.set(route, statusNum);
    sendJson(res, 200, { route, status: statusNum });
    return;
  }
  sendJson(res, 404, { error: "not found" });
}

async function applyTestOverrides(req, res, pathname) {
  if (!testApiEnabled) {
    return false;
  }
  const failStatus = testFailOverrides.get(pathname);
  if (failStatus !== undefined) {
    testFailOverrides.delete(pathname); // one-shot
    sendJson(res, failStatus, {
      error: "test_injected_failure",
      route: pathname,
      status: failStatus,
    });
    return true;
  }
  const slowMs = testSlowOverrides.get(pathname);
  if (slowMs !== undefined && slowMs > 0) {
    await new Promise((resolve) => setTimeout(resolve, slowMs));
  }
  return false;
}

/**
 * Extract user claims from Authorization header.
 * Aussie forwards claims as a JWT in the Authorization header.
 */
function extractClaims(req) {
  var authHeader = req.headers["authorization"];
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }

  try {
    var token = authHeader.substring(7);
    // Decode JWT payload (we trust Aussie's signature verification)
    var parts = token.split(".");
    if (parts.length !== 3) {
      return null;
    }
    var payload = JSON.parse(Buffer.from(parts[1], "base64url").toString());
    return payload;
  } catch (e) {
    console.error("Failed to decode JWT:", e.message);
    return null;
  }
}

/**
 * Handle /ws/echo - Public echo endpoint.
 * Echoes any message back to the sender with a timestamp.
 */
function handleEchoConnection(ws) {
  console.log("[Echo] Client connected");

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      ws.send(data, { binary: true });
      return;
    }
    var message = data.toString();
    console.log("[Echo] Received:", message);

    var response = JSON.stringify({
      type: "echo",
      original: message,
      timestamp: new Date().toISOString(),
    });

    ws.send(response);
  });

  ws.on("close", (code, reason) => {
    console.log(`[Echo] Client disconnected: ${code} ${reason}`);
  });

  ws.on("error", (err) => {
    console.error("[Echo] Error:", err.message);
  });

  // Send welcome message
  ws.send(
    JSON.stringify({
      type: "connected",
      endpoint: "echo",
      message: "Connected to echo endpoint. Send any message to receive it back.",
    })
  );
}

/**
 * Handle /ws/chat - Authenticated chat endpoint.
 * Requires valid JWT claims from Aussie. Broadcasts messages to all clients.
 */
function handleChatConnection(ws, req) {
  var claims = extractClaims(req);

  if (!claims) {
    console.log("[Chat] Connection rejected - no valid claims");
    ws.close(4001, "Authentication required");
    return;
  }

  var userId = claims.sub || "anonymous";
  var userName = claims.name || claims.preferred_username || userId;
  var clientId = `${userId}-${Date.now()}`;

  console.log(`[Chat] User connected: ${userName} (${userId})`);

  // Store client info
  chatClients.set(clientId, { ws, userId, userName, claims });

  // Broadcast join notification
  broadcast(
    {
      type: "user_joined",
      userId,
      userName,
      timestamp: new Date().toISOString(),
      activeUsers: getActiveUsers(),
    },
    clientId
  );

  ws.on("message", (data) => {
    var message = data.toString();
    console.log(`[Chat] ${userName}: ${message}`);

    // Broadcast to all clients including sender
    broadcast({
      type: "message",
      userId,
      userName,
      content: message,
      timestamp: new Date().toISOString(),
    });
  });

  ws.on("close", (code, reason) => {
    console.log(`[Chat] ${userName} disconnected: ${code} ${reason}`);
    chatClients.delete(clientId);

    // Broadcast leave notification
    broadcast({
      type: "user_left",
      userId,
      userName,
      timestamp: new Date().toISOString(),
      activeUsers: getActiveUsers(),
    });
  });

  ws.on("error", (err) => {
    console.error(`[Chat] Error for ${userName}:`, err.message);
  });

  // Send welcome message with user info
  ws.send(
    JSON.stringify({
      type: "connected",
      endpoint: "chat",
      userId,
      userName,
      claims: {
        sub: claims.sub,
        name: claims.name,
        email: claims.email,
        roles: claims.roles || [],
      },
      activeUsers: getActiveUsers(),
      message: `Welcome to chat, ${userName}!`,
    })
  );
}

/**
 * Get list of active users in chat.
 */
function getActiveUsers() {
  var users = [];
  for (var [, client] of chatClients) {
    users.push({
      userId: client.userId,
      userName: client.userName,
    });
  }
  return users;
}

/**
 * Broadcast message to all chat clients.
 */
function broadcast(message, excludeClientId = null) {
  var data = JSON.stringify(message);
  for (var [clientId, client] of chatClients) {
    if (clientId !== excludeClientId && client.ws.readyState === 1) {
      client.ws.send(data);
    }
  }
}

app.prepare().then(() => {
  var server = createServer(async (req, res) => {
    var parsedUrl = parse(req.url, true);
    var pathname = parsedUrl.pathname;

    try {
      if (testApiEnabled && pathname && pathname.startsWith("/__test__/")) {
        await handleTestApi(req, res, pathname);
        return;
      }

      if (testApiEnabled) {
        recordTestRequest(req);
        if (await applyTestOverrides(req, res, pathname)) {
          return;
        }
      }

      handle(req, res, parsedUrl);
    } catch (err) {
      console.error("[server] unhandled request error:", err);
      if (!res.headersSent) {
        sendJson(res, 500, { error: "internal_error" });
      } else if (!res.writableEnded) {
        res.end();
      }
    }
  });

  // Create WebSocket server attached to HTTP server
  var wss = new WebSocketServer({ noServer: true });

  // Handle WebSocket upgrade requests
  server.on("upgrade", (req, socket, head) => {
    var { pathname } = parse(req.url, true);

    console.log(`[WS] Upgrade request for: ${pathname}`);

    if (pathname === "/ws/echo") {
      wss.handleUpgrade(req, socket, head, (ws) => {
        handleEchoConnection(ws);
      });
    } else if (pathname === "/ws/chat") {
      wss.handleUpgrade(req, socket, head, (ws) => {
        handleChatConnection(ws, req);
      });
    } else {
      console.log(`[WS] Unknown WebSocket path: ${pathname}`);
      socket.destroy();
    }
  });

  server.listen(port, () => {
    console.log(`> Demo server ready on http://${hostname}:${port}`);
    console.log(`> WebSocket endpoints:`);
    console.log(`>   - ws://${hostname}:${port}/ws/echo (public)`);
    console.log(`>   - ws://${hostname}:${port}/ws/chat (authenticated)`);
  });
});
