# Request Timeouts - Service Team Guide

## Overview

Request timeouts control how long the gateway waits for a response from your upstream service before returning a 504 Gateway Timeout. By default, the platform's global timeout applies to all requests. You can configure custom timeouts at the service level or per-endpoint for requests that need more or less time.

## Configuration

Add timeout configuration to your service registration:

```json
{
  "serviceId": "my-service",
  "timeoutConfig": {
    "requestTimeout": "PT30S"
  }
}
```

### Configuration Options

| Field | Description | Format | Default |
|-------|-------------|--------|---------|
| `requestTimeout` | Maximum time to wait for upstream response | ISO-8601 duration | Platform default (typically 30s) |

### Duration Format

Timeouts use ISO-8601 duration format:

| Value | Meaning |
|-------|---------|
| `PT5S` | 5 seconds |
| `PT30S` | 30 seconds |
| `PT1M` | 1 minute |
| `PT1M30S` | 1 minute 30 seconds |
| `PT2M` | 2 minutes |

## Per-Endpoint Configuration

Configure different timeouts for specific endpoints:

```json
{
  "serviceId": "my-service",
  "timeoutConfig": {
    "requestTimeout": "PT30S"
  },
  "endpoints": [
    {
      "path": "/api/reports/generate",
      "methods": ["POST"],
      "visibility": "PRIVATE",
      "timeoutConfig": {
        "requestTimeout": "PT2M"
      }
    },
    {
      "path": "/api/health",
      "methods": ["GET"],
      "visibility": "PUBLIC",
      "timeoutConfig": {
        "requestTimeout": "PT5S"
      }
    }
  ]
}
```

## Resolution Hierarchy

Timeouts are resolved in this order:

1. **Endpoint-specific config** (if present)
2. **Service-level config** (if present)
3. **Platform default**

### Example

```json
{
  "serviceId": "orders",
  "timeoutConfig": {
    "requestTimeout": "PT45S"
  },
  "endpoints": [
    {
      "path": "/api/orders/{id}",
      "methods": ["GET"],
      "visibility": "PUBLIC"
    },
    {
      "path": "/api/orders/export",
      "methods": ["POST"],
      "visibility": "PRIVATE",
      "timeoutConfig": {
        "requestTimeout": "PT3M"
      }
    }
  ]
}
```

Results:
- `GET /orders/api/orders/{id}` -> 45 seconds (service config)
- `POST /orders/api/orders/export` -> 3 minutes (endpoint config)
- Any other endpoint without config -> platform default

## Platform Maximum

All timeout values are validated against the platform maximum. If your requested timeout exceeds the platform limit, registration will be rejected with a 400 error indicating the maximum allowed value.

## Updating Timeout Configuration

### Via Admin API

```bash
# Get current configuration
curl https://gateway.example.com/admin/services/my-service

# Update with timeout configuration
curl -X PUT https://gateway.example.com/admin/services/my-service \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "my-service",
    "displayName": "My Service",
    "baseUrl": "http://my-service:8080",
    "timeoutConfig": {
      "requestTimeout": "PT1M"
    }
  }'
```

## Common Patterns

### Fast Service with One Slow Endpoint

```json
{
  "serviceId": "analytics",
  "timeoutConfig": {
    "requestTimeout": "PT10S"
  },
  "endpoints": [
    {
      "path": "/api/reports/generate",
      "methods": ["POST"],
      "visibility": "PRIVATE",
      "timeoutConfig": {
        "requestTimeout": "PT3M"
      }
    }
  ]
}
```

### Tighter Timeout Than Default

```json
{
  "serviceId": "cache-service",
  "timeoutConfig": {
    "requestTimeout": "PT5S"
  }
}
```

## Limitations

- Timeouts cannot exceed the platform maximum (contact your platform team if you need a higher limit)
- Timeout values must be positive durations
- Configuration changes take effect after cache propagation (typically within minutes)

## Need a Higher Timeout?

If you need a timeout higher than the platform maximum:

1. Contact your platform team with your use case
2. Explain which endpoint needs additional time and why
3. Platform teams can adjust the maximum for your deployment
