# WebSocket Endpoint Onboarding Guide (API Teams)

This guide explains how to configure your service to expose WebSocket endpoints through Aussie.

## Quick Start

Add WebSocket endpoints to your `aussie-service.json`:

```json
{
  "serviceId": "my-service",
  "baseUrl": "http://my-service:3000",
  "endpoints": [
    {
      "path": "/ws/notifications",
      "type": "WEBSOCKET",
      "visibility": "PRIVATE",
      "authRequired": true
    },
    {
      "path": "/ws/public-feed",
      "type": "WEBSOCKET",
      "visibility": "PUBLIC",
      "authRequired": false
    }
  ]
}
```

Key differences from HTTP endpoints:
- Set `type` to `"WEBSOCKET"` (default is `"HTTP"`)
- The `methods` field is ignored (WebSocket always uses GET for upgrade)

## Endpoint Configuration

### Required Fields

| Field | Description |
|-------|-------------|
| `path` | The WebSocket endpoint path (e.g., `/ws/notifications`) |
| `type` | Must be `"WEBSOCKET"` |

### Optional Fields

| Field | Default | Description |
|-------|---------|-------------|
| `visibility` | `"PRIVATE"` | `"PUBLIC"` or `"PRIVATE"` |
| `authRequired` | Service default | Whether authentication is required |

### Path Patterns

You can use glob patterns to match multiple paths:

```json
{
  "path": "/ws/rooms/*",
  "type": "WEBSOCKET",
  "authRequired": true
}
```

This matches `/ws/rooms/123`, `/ws/rooms/abc`, etc.

For deeper paths:

```json
{
  "path": "/ws/**",
  "type": "WEBSOCKET"
}
```

This matches `/ws/anything`, `/ws/any/path/depth`, etc.

## Authentication

### Unauthenticated Endpoints

For public WebSocket endpoints (e.g., live scores, public chat):

```json
{
  "path": "/ws/live-scores",
  "type": "WEBSOCKET",
  "visibility": "PUBLIC",
  "authRequired": false
}
```

Clients can connect without any authentication.

### Authenticated Endpoints

For protected WebSocket endpoints (e.g., user notifications, private chat):

```json
{
  "path": "/ws/notifications",
  "type": "WEBSOCKET",
  "visibility": "PRIVATE",
  "authRequired": true
}
```

Clients must authenticate before connecting. Aussie supports:
- Session cookies (from prior login)
- Bearer tokens in the initial upgrade request

### Receiving User Claims

For authenticated endpoints, Aussie forwards user claims to your backend via the `Authorization` header:

```
Authorization: Bearer <jwt>
```

Your backend receives a short-lived JWT containing the user's claims:

```javascript
// Node.js example: extracting claims from JWT
function extractClaims(req) {
  var authHeader = req.headers["authorization"];
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }

  var token = authHeader.substring(7);
  var parts = token.split(".");
  if (parts.length !== 3) return null;

  // Decode payload (Aussie has already validated the signature)
  var payload = JSON.parse(Buffer.from(parts[1], "base64url").toString());
  return payload;
}

// Usage in WebSocket handler
wss.on("connection", (ws, req) => {
  var claims = extractClaims(req);
  if (!claims) {
    ws.close(4001, "Authentication required");
    return;
  }

  console.log("User connected:", claims.sub, claims.name);
});
```

The JWT typically contains:
- `sub`: User ID
- `name`: Display name
- `email`: Email address
- `roles`: User roles/permissions
- `iat`: Issued at timestamp
- `exp`: Expiration timestamp

## Client Connection

### Gateway Mode

Connect through the gateway prefix:

```javascript
const ws = new WebSocket("ws://aussie:1234/gateway/ws/notifications");
```

### Pass-Through Mode

Connect using your service ID:

```javascript
const ws = new WebSocket("ws://aussie:1234/my-service/ws/notifications");
```

### Connection with Authentication

For authenticated endpoints, the client must have already authenticated (e.g., logged in via browser session cookie).

If using bearer tokens:

```javascript
// Note: WebSocket API doesn't support custom headers directly
// Use a query parameter or subprotocol for token transmission
const ws = new WebSocket("ws://aussie:1234/my-service/ws/notifications?token=...");
```

Or use session-based auth:

```javascript
// If user is logged in via browser, cookies are sent automatically
const ws = new WebSocket("ws://aussie:1234/my-service/ws/notifications");
```

## Connection Lifecycle

### Connection Limits

Aussie enforces connection lifecycle limits:

| Limit | Default | Description |
|-------|---------|-------------|
| Idle timeout | 5 minutes | Closes if no messages in either direction |
| Max lifetime | 24 hours | Hard limit regardless of activity |

Your application should:
- Send periodic messages to prevent idle timeout (if needed)
- Handle reconnection gracefully
- Not assume connections last forever

### Close Codes

Your backend may receive these close codes from Aussie:

| Code | Meaning | Your Action |
|------|---------|-------------|
| `1000` | Normal closure | Log and clean up |
| `1001` | Going away | Client disconnected |
| `1002` | Protocol error | Check client implementation |
| `1011` | Unexpected error | Check logs for details |

### Handling Disconnects

Implement reconnection logic in your clients:

```javascript
class ReconnectingWebSocket {
  constructor(url) {
    this.url = url;
    this.connect();
  }

  connect() {
    this.ws = new WebSocket(this.url);

    this.ws.onclose = (event) => {
      console.log("Disconnected:", event.code, event.reason);
      // Reconnect after delay (with exponential backoff)
      setTimeout(() => this.connect(), 1000);
    };

    this.ws.onerror = (error) => {
      console.error("WebSocket error:", error);
    };
  }
}
```

## Complete Example

### Service Configuration

`aussie-service.json`:

```json
{
  "serviceId": "chat-service",
  "displayName": "Chat Service",
  "baseUrl": "http://chat-service:3000",
  "defaultVisibility": "PRIVATE",
  "defaultAuthRequired": true,
  "endpoints": [
    {
      "path": "/ws/public",
      "type": "WEBSOCKET",
      "visibility": "PUBLIC",
      "authRequired": false
    },
    {
      "path": "/ws/rooms/*",
      "type": "WEBSOCKET",
      "authRequired": true
    },
    {
      "path": "/api/rooms",
      "methods": ["GET", "POST"]
    }
  ]
}
```

### Backend Handler (Node.js)

```javascript
const { WebSocketServer } = require("ws");

const wss = new WebSocketServer({ noServer: true });

// Handle upgrade requests
server.on("upgrade", (req, socket, head) => {
  var pathname = new URL(req.url, "http://localhost").pathname;

  if (pathname.startsWith("/ws/rooms/")) {
    wss.handleUpgrade(req, socket, head, (ws) => {
      handleRoomConnection(ws, req);
    });
  } else if (pathname === "/ws/public") {
    wss.handleUpgrade(req, socket, head, (ws) => {
      handlePublicConnection(ws);
    });
  } else {
    socket.destroy();
  }
});

function handleRoomConnection(ws, req) {
  // Extract user claims from Aussie's forwarded JWT
  var claims = extractClaims(req);
  if (!claims) {
    ws.close(4001, "Authentication required");
    return;
  }

  var roomId = req.url.split("/").pop();
  console.log(`${claims.name} joined room ${roomId}`);

  ws.on("message", (data) => {
    // Broadcast to room with user info
    broadcast(roomId, {
      type: "message",
      userId: claims.sub,
      userName: claims.name,
      content: data.toString()
    });
  });

  ws.on("close", () => {
    console.log(`${claims.name} left room ${roomId}`);
  });
}

function handlePublicConnection(ws) {
  // No authentication needed
  ws.send(JSON.stringify({ type: "connected", message: "Welcome!" }));
}
```

### Frontend Client

```javascript
class ChatClient {
  constructor(serviceId, roomId) {
    this.url = `ws://${window.location.hostname}:1234/${serviceId}/ws/rooms/${roomId}`;
  }

  connect() {
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log("Connected to chat");
    };

    this.ws.onmessage = (event) => {
      var message = JSON.parse(event.data);
      this.handleMessage(message);
    };

    this.ws.onclose = (event) => {
      console.log("Disconnected:", event.reason);
      // Reconnect after 1 second
      setTimeout(() => this.connect(), 1000);
    };
  }

  send(content) {
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(content);
    }
  }

  handleMessage(message) {
    switch (message.type) {
      case "message":
        console.log(`${message.userName}: ${message.content}`);
        break;
      case "user_joined":
        console.log(`${message.userName} joined`);
        break;
    }
  }
}

// Usage
const chat = new ChatClient("chat-service", "room-123");
chat.connect();
```

## Migration from Direct WebSocket

If your service currently accepts direct WebSocket connections:

1. Add WebSocket endpoints to your `aussie-service.json`
2. Update backend to extract claims from `Authorization` header (for authenticated endpoints)
3. Update clients to connect through Aussie instead of directly to your service
4. Test both authenticated and unauthenticated flows

No changes needed to your WebSocket message handling - only the connection establishment and authentication extraction differ.

## Testing

Use the demo service's WebSocket test page at `/websocket` to verify your configuration works correctly with Aussie's WebSocket proxy.

## Troubleshooting

### "Not a WebSocket endpoint" Error

Ensure your endpoint has `type: "WEBSOCKET"` in the configuration.

### "Unauthorized" Error

1. Check that you're authenticated (session cookie or bearer token)
2. Verify `authRequired` matches your intent
3. Check token hasn't expired

### Connection Drops After Inactivity

This is expected - Aussie has idle timeouts. Send periodic messages or implement reconnection logic.

### Backend Receives No User Info

Ensure `authRequired: true` is set for the endpoint. Only authenticated endpoints receive the forwarded JWT.
