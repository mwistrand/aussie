# WebSocket Configuration Guide (Platform Teams)

This guide covers the configuration options for Aussie's WebSocket proxy functionality.

## Configuration Properties

All WebSocket settings use the prefix `aussie.websocket.*` and can be set via environment variables using the format `AUSSIE_WEBSOCKET_*`.

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `aussie.websocket.idle-timeout` | `AUSSIE_WEBSOCKET_IDLE_TIMEOUT` | `PT5M` (5 minutes) | Close connection if no messages in either direction |
| `aussie.websocket.max-lifetime` | `AUSSIE_WEBSOCKET_MAX_LIFETIME` | `PT24H` (24 hours) | Hard limit on connection lifetime regardless of activity |
| `aussie.websocket.max-connections` | `AUSSIE_WEBSOCKET_MAX_CONNECTIONS` | `10000` | Maximum concurrent WebSocket connections **per instance** |
| `aussie.websocket.ping.enabled` | `AUSSIE_WEBSOCKET_PING_ENABLED` | `true` | Enable ping/pong heartbeats to detect stale clients |
| `aussie.websocket.ping.interval` | `AUSSIE_WEBSOCKET_PING_INTERVAL` | `PT30S` (30 seconds) | How often to send ping frames |
| `aussie.websocket.ping.timeout` | `AUSSIE_WEBSOCKET_PING_TIMEOUT` | `PT10S` (10 seconds) | Close connection if pong not received within this time |

### Connection Limits

**Important:** The `max-connections` limit is **per Aussie instance**, not cluster-wide.

In a 3-instance cluster with `max-connections=10000`:
- Each instance can handle up to 10,000 WebSocket connections
- The cluster can handle up to 30,000 total WebSocket connections

### Duration Format

Duration values use ISO-8601 format:
- `PT5M` = 5 minutes
- `PT30S` = 30 seconds
- `PT24H` = 24 hours
- `PT1H30M` = 1 hour and 30 minutes

## Connection Architecture

Aussie maintains **two separate WebSocket connections** per session:

```
Client (Browser) <--A--> Aussie <--B--> Backend Service
```

- **Connection A**: Client to Aussie
- **Connection B**: Aussie to Backend

When either connection closes (gracefully or due to error/timeout), the other is also closed with a reason message. Both connections always close together.

## Connection Lifecycle

### Idle Timeout

If no messages pass in either direction for the idle timeout period, both connections are closed with code `1000` and reason "Idle timeout exceeded".

### Maximum Lifetime

After the max lifetime period (regardless of activity), both connections are closed with code `1000` and reason "Maximum connection lifetime exceeded".

This prevents connections from staying open indefinitely and ensures resources are eventually reclaimed.

### Ping/Pong Heartbeats

When enabled, Aussie sends periodic ping frames to the client:
1. Aussie sends a ping frame every `ping.interval`
2. If the client doesn't respond with a pong within `ping.timeout`, the connection is closed with code `1002` and reason "Ping timeout - no pong received"

This detects clients that disconnect without sending a close frame (e.g., network failure, browser crash).

## Authentication Flow

WebSocket connections authenticate during the HTTP upgrade handshake:

1. Client initiates WebSocket upgrade request
2. Aussie validates authentication (Bearer token or session cookie)
3. If valid, Aussie:
   - Connects to the backend WebSocket endpoint
   - Upgrades the client connection
   - Forwards a short-lived JWT with user claims to the backend
4. If invalid, Aussie returns an HTTP error response (401, 403, etc.)

### Forwarded Claims

For authenticated WebSocket endpoints, Aussie forwards user claims to the backend via the `Authorization` header:

```
Authorization: Bearer <short-lived-jwt>
```

The JWT contains the original user claims (sub, name, email, roles, etc.) and has a short expiration to minimize the window for token theft.

## Close Code Reference

| Code | Meaning | When Used |
|------|---------|-----------|
| `1000` | Normal closure | Idle timeout, max lifetime, user disconnect |
| `1001` | Going away | Client upgrade failed after backend connected |
| `1002` | Protocol error | Ping timeout (no pong received) |
| `1011` | Unexpected error | Client or backend error |

## Routing Modes

WebSocket connections work with both routing modes:

### Gateway Mode

```
ws://aussie:1234/gateway/ws/echo
```

Routes based on path patterns configured in the service registration.

### Pass-Through Mode

```
ws://aussie:1234/{serviceId}/ws/echo
```

Routes directly to the specified service ID.

## Monitoring

### Active Session Count

The `WebSocketGateway` class provides `getActiveSessionCount()` to get the current number of active WebSocket sessions per instance.

### Log Messages

Key log messages for observability:
- `WebSocket session {id} established to {backendUri}` - Session started
- `WebSocket session {id} closed: {reason} (duration: {seconds}s)` - Session ended

## Example Configuration

### Production Configuration

```properties
# Longer timeouts for production
aussie.websocket.idle-timeout=PT10M
aussie.websocket.max-lifetime=PT24H
aussie.websocket.max-connections=20000
aussie.websocket.ping.enabled=true
aussie.websocket.ping.interval=PT30S
aussie.websocket.ping.timeout=PT10S
```

### Development Configuration

```properties
# Shorter timeouts for development
aussie.websocket.idle-timeout=PT2M
aussie.websocket.max-lifetime=PT1H
aussie.websocket.max-connections=100
aussie.websocket.ping.enabled=true
aussie.websocket.ping.interval=PT10S
aussie.websocket.ping.timeout=PT5S
```

## Troubleshooting

### Connection Closes Immediately

1. Check if the backend WebSocket endpoint is reachable
2. Verify the service registration includes the WebSocket endpoint with `type: "WEBSOCKET"`
3. Check authentication requirements match client capabilities

### Connection Closes After Inactivity

This is expected behavior. If connections need to stay open longer:
1. Increase `idle-timeout`
2. Send periodic messages from the client
3. The ping/pong mechanism keeps the connection alive but doesn't reset idle timeout

### Too Many Connections Error

Increase `max-connections` or add more Aussie instances. Remember this is per-instance, not cluster-wide.

### Backend Receives No Authorization Header

Ensure the endpoint has `authRequired: true` in the service registration.
