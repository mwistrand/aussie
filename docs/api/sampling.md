# Trace Sampling - Service Team Guide

## Overview

Trace sampling controls what percentage of requests generate distributed traces. By default, all requests are traced (100% sampling rate). You can configure sampling at the service level or per-endpoint to reduce tracing overhead for high-volume or less critical endpoints.

## Configuration

Add sampling configuration to your service registration:

```json
{
  "serviceId": "my-service",
  "samplingConfig": {
    "samplingRate": 0.5
  }
}
```

### Configuration Options

| Field | Description | Default |
|-------|-------------|---------|
| `samplingRate` | Trace sampling rate (0.0 to 1.0) | Platform default (typically 1.0) |

### Understanding Sampling Rates

| Rate | Effect |
|------|--------|
| `1.0` | No sampling - all requests traced |
| `0.5` | 50% of requests traced |
| `0.1` | 10% of requests traced |
| `0.0` | No traces (not recommended) |

## Per-Endpoint Configuration

Configure different sampling rates for specific endpoints:

```json
{
  "serviceId": "my-service",
  "samplingConfig": {
    "samplingRate": 0.1
  },
  "endpoints": [
    {
      "path": "/api/health",
      "methods": ["GET"],
      "visibility": "PUBLIC",
      "samplingConfig": {
        "samplingRate": 1.0
      }
    },
    {
      "path": "/api/high-volume",
      "methods": ["GET"],
      "visibility": "PUBLIC",
      "samplingConfig": {
        "samplingRate": 0.01
      }
    }
  ]
}
```

### Recommended Rates by Endpoint Type

| Endpoint Type | Recommended Rate | Reasoning |
|---------------|------------------|-----------|
| Health checks | `1.0` | Always trace for monitoring |
| Error-prone endpoints | `1.0` | Full visibility for debugging |
| High-volume reads | `0.01` - `0.1` | Reduce storage costs |
| Batch operations | `0.1` - `0.5` | Balance visibility and cost |
| Standard CRUD | `0.1` - `0.5` | Platform default often sufficient |

## Resolution Hierarchy

Sampling rates are resolved in this order:

1. **Endpoint-specific config** (if present)
2. **Service-level config** (if present)
3. **Platform default**

All rates are clamped to platform minimum and maximum bounds set by your platform team.

### Example

```json
{
  "serviceId": "orders",
  "samplingConfig": {
    "samplingRate": 0.5
  },
  "endpoints": [
    {
      "path": "/api/orders/{id}",
      "methods": ["GET"],
      "visibility": "PUBLIC"
    },
    {
      "path": "/api/orders/bulk",
      "methods": ["POST"],
      "visibility": "PRIVATE",
      "samplingConfig": {
        "samplingRate": 0.1
      }
    }
  ]
}
```

Results:
- `GET /orders/api/orders/{id}` → 50% sampled (service config)
- `POST /orders/api/orders/bulk` → 10% sampled (endpoint config)

## Ensuring Full Tracing

To ensure all requests to a specific endpoint are traced (useful for debugging or critical paths), set `samplingRate` to `1.0`:

```json
{
  "endpoints": [
    {
      "path": "/api/checkout",
      "methods": ["POST"],
      "visibility": "PUBLIC",
      "samplingConfig": {
        "samplingRate": 1.0
      }
    }
  ]
}
```

**Note**: Platform teams may set a maximum sampling rate. If the platform maximum is `0.5`, your `1.0` will be clamped to `0.5`.

## Distributed Trace Continuity

The gateway respects parent trace decisions:

- If an upstream service already decided to sample a trace, all downstream spans are included
- If an upstream service decided not to sample, the trace is not continued

This ensures distributed traces remain complete across service boundaries.

## Updating Sampling Configuration

### Via Admin API

```bash
# Get current configuration
curl https://gateway.example.com/admin/services/my-service

# Update sampling configuration
curl -X PUT https://gateway.example.com/admin/services/my-service \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "my-service",
    "displayName": "My Service",
    "baseUrl": "http://my-service:8080",
    "samplingConfig": {
      "samplingRate": 0.2
    }
  }'
```

### Configuration Propagation

Sampling configuration changes take effect after the cache TTL expires (typically 5 minutes). For immediate effect, contact your platform team.

## Common Patterns

### High-Volume Service with Critical Endpoints

```json
{
  "serviceId": "analytics",
  "samplingConfig": {
    "samplingRate": 0.01
  },
  "endpoints": [
    {
      "path": "/api/events",
      "methods": ["POST"],
      "visibility": "PUBLIC"
    },
    {
      "path": "/api/reports/{id}",
      "methods": ["GET"],
      "visibility": "PRIVATE",
      "samplingConfig": {
        "samplingRate": 0.5
      }
    }
  ]
}
```

### Debugging Mode (Temporary)

```json
{
  "serviceId": "my-service",
  "samplingConfig": {
    "samplingRate": 1.0
  }
}
```

Remember to revert after debugging to avoid excessive trace storage.

### Production-Optimized

```json
{
  "serviceId": "my-service",
  "samplingConfig": {
    "samplingRate": 0.1
  },
  "endpoints": [
    {
      "path": "/api/health",
      "methods": ["GET"],
      "visibility": "PUBLIC",
      "samplingConfig": {
        "samplingRate": 1.0
      }
    }
  ]
}
```

## Limitations

- Sampling rates cannot exceed the platform maximum
- Sampling rates cannot go below the platform minimum
- WebSocket connections use the service-level sampling rate (per-endpoint not supported)
- Sampling configuration changes are eventually consistent (cache TTL applies)

## Troubleshooting

### Traces Not Appearing

1. Check your sampling rate isn't too low
2. Verify the platform hasn't set a very low maximum rate
3. Ensure tracing is enabled at the platform level

### More Traces Than Expected

1. Parent-based sampling may include traces started by upstream services
2. Platform minimum rate may override your configuration

### Configuration Not Taking Effect

1. Wait for cache TTL (typically 5 minutes)
2. Verify configuration was saved correctly via admin API
3. Check for validation errors in the response

## Need Higher Sampling?

If you need a higher sampling rate than the platform maximum allows:

1. Contact your platform team with your use case
2. Provide estimated request volume
3. Explain why full tracing is needed (debugging, compliance, etc.)

Platform teams can temporarily raise limits for specific services during incidents or debugging sessions.
