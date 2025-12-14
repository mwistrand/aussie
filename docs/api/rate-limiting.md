# Rate Limiting - Service Team Guide

## Overview

Rate limiting protects your service endpoints from abuse. You can configure
default limits for your service and per-endpoint overrides for specific use cases.

## Configuration

Add rate limit configuration to your service registration:

```json
{
  "serviceId": "my-service",
  "rateLimitConfig": {
    "requestsPerWindow": 100,
    "burstCapacity": 100,
    "endpoints": {
      "/api/expensive-operation": {
        "requestsPerWindow": 10,
        "burstCapacity": 5
      }
    }
  }
}
```

### Configuration Options

| Field | Description | Default |
|-------|-------------|---------|
| `requestsPerWindow` | Requests allowed per time window | Platform default |
| `burstCapacity` | Maximum burst size (bucket algorithm) | Platform default |
| `endpoints` | Per-endpoint overrides | None |

### Per-HTTP Endpoint Configuration

Configure different limits for different HTTP endpoints based on their characteristics:

Note that per-endpoint configurations for WebSocket connections are not yet supported.

| Endpoint Type | Recommended Limit | Reasoning |
|---------------|-------------------|-----------|
| File uploads | 5-10/min | Resource intensive |
| Search/queries | 20-50/min | Database intensive |
| Health checks | 500-1000/min | Monitoring needs |
| Read operations | 100-500/min | Standard limits |
| Write operations | 50-100/min | More restrictive |

### WebSocket Rate Limiting

For WebSocket endpoints, you can configure both connection and message limits:

```json
{
  "rateLimitConfig": {
    "websocket": {
      "connection": {
        "requestsPerWindow": 5,
        "burstCapacity": 3
      },
      "message": {
        "requestsPerWindow": 200,
        "burstCapacity": 100
      }
    }
  }
}
```

## Response Headers

When rate limiting is enabled, responses include these headers:

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Maximum requests per window |
| `X-RateLimit-Remaining` | Remaining requests in current window |
| `X-RateLimit-Reset` | Unix timestamp when window resets |
| `Retry-After` | Seconds to wait (only on 429) |

## Handling 429 Responses

When your client receives a 429 response:

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded. Retry after 3 seconds.",
  "retryAfter": 3
}
```

**Best Practices:**
1. Respect the `Retry-After` header
2. Implement exponential backoff
3. Cache responses where possible
4. Use bulk endpoints to reduce request count

### Example Client Code

```typescript
async function fetchWithRetry(url: string, maxRetries = 3): Promise<Response> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    const response = await fetch(url);

    if (response.status !== 429) {
      return response;
    }

    const retryAfter = parseInt(response.headers.get('Retry-After') || '1');
    await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
  }

  throw new Error('Max retries exceeded');
}
```

### WebSocket Rate Limiting Behavior

**Connection Rate Limiting:**
- Upgrade request rejected with HTTP 429 if limit exceeded
- Includes `Retry-After` header in response

**Message Rate Limiting:**
- Connection closed with status code 1008 (Policy Violation)
- Close reason includes retry information
- Both client and backend connections are closed

## Client Identification

Rate limits are applied per-client. Client identification priority:

1. Session ID (from cookie or `X-Session-ID` header)
2. Bearer token (hashed for privacy)
3. API Key ID (from `X-API-Key-ID` header)
4. IP address (from `X-Forwarded-For` or remote address)

## Limitations

- Rate limits are per-client per-service
- Platform teams control the algorithm selection
- Your limits cannot exceed the platform maximum
- Rate limit state is shared across all gateway instances (when using Redis)

## Troubleshooting

**Getting rate limited unexpectedly?**
- Check your current usage against configured limits
- Verify client identification is consistent
- Review per-endpoint configurations

**Need higher limits?**
- Contact platform team to review platform maximum
- Consider optimizing your request patterns
- Implement client-side caching
