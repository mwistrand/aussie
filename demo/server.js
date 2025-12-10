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
function handleEchoConnection(ws, req) {
  console.log("[Echo] Client connected");

  ws.on("message", (data) => {
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
  var server = createServer((req, res) => {
    var parsedUrl = parse(req.url, true);
    handle(req, res, parsedUrl);
  });

  // Create WebSocket server attached to HTTP server
  var wss = new WebSocketServer({ noServer: true });

  // Handle WebSocket upgrade requests
  server.on("upgrade", (req, socket, head) => {
    var { pathname } = parse(req.url, true);

    console.log(`[WS] Upgrade request for: ${pathname}`);

    if (pathname === "/ws/echo") {
      wss.handleUpgrade(req, socket, head, (ws) => {
        handleEchoConnection(ws, req);
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
