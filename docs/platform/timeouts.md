# Request Timeouts - Platform Team Guide

## Overview

Request timeouts control how long the gateway waits for upstream services to respond before returning a 504 Gateway Timeout. The platform provides a global default and a maximum ceiling; services can configure custom timeouts within these bounds.

The timeout hierarchy allows configuration at three levels:
1. **Platform default** - Global fallback for all requests
2. **Service-level overrides** - Per-service customization via registration
3. **Endpoint-level overrides** - Fine-grained control for specific endpoints

## Configuration

### Application Properties

```properties
# Default request timeout for all upstream requests
aussie.resiliency.http.request-timeout=PT30S

# Maximum timeout services may configure (ceiling for service/endpoint overrides)
aussie.resiliency.http.max-request-timeout=PT5M
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AUSSIE_RESILIENCY_HTTP_REQUEST_TIMEOUT` | Default request timeout | `PT30S` |
| `AUSSIE_RESILIENCY_HTTP_MAX_REQUEST_TIMEOUT` | Maximum allowed service/endpoint timeout | `PT5M` |

## Resolution Hierarchy

When a request arrives, the effective timeout is determined by:

```
1. Endpoint-level config (if present) -> use it
2. Service-level config (if present)  -> use it
3. Platform default                   -> use it
```

### Example

```
Platform config:
  request-timeout: PT30S    (default)
  max-request-timeout: PT5M (ceiling)

Service "reports":
  requestTimeout: PT2M

Endpoint "/reports/api/generate":
  requestTimeout: PT4M

Results:
  GET  /reports/api/summary   -> 2 minutes  (service config)
  POST /reports/api/generate  -> 4 minutes  (endpoint config)
  GET  /inventory/api/stock   -> 30 seconds (platform default)
```

## Validation

Service and endpoint timeouts are validated at registration time:

- Timeouts must be positive durations
- Timeouts cannot exceed `max-request-timeout`
- Validation returns HTTP 400 with details if exceeded

### Example Error

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Service requestTimeout PT10M exceeds the platform maximum of PT5M."
}
```

## Tuning Guidelines

### Setting the Default

The default timeout should cover most typical requests:

```properties
# Conservative default for microservices
aussie.resiliency.http.request-timeout=PT30S
```

### Setting the Maximum

The maximum should accommodate legitimate slow operations (report generation, batch processing) while preventing runaway connections:

```properties
# Allow up to 5 minutes for long-running operations
aussie.resiliency.http.max-request-timeout=PT5M
```

### Production Recommendations

| Setting | Recommended | Reasoning |
|---------|-------------|-----------|
| Default timeout | 10-30s | Covers typical API responses |
| Maximum timeout | 3-5m | Accommodates batch/report endpoints |

## Monitoring

When a request exceeds its timeout, the gateway:

1. Returns 504 Gateway Timeout to the client
2. Logs the timeout event with service and endpoint context
3. Records the event in traces (if sampling is enabled)

## Troubleshooting

### Service Getting 504s

1. Check the effective timeout for the endpoint:
   ```bash
   curl http://localhost:8080/admin/services/my-service | jq '.timeoutConfig'
   ```
2. Verify upstream service response times
3. Consider increasing the service or endpoint timeout

### Service Registration Rejected

If a service registration fails with a timeout validation error:
1. Check the current platform maximum: `aussie.resiliency.http.max-request-timeout`
2. Either reduce the requested timeout or increase the platform maximum
