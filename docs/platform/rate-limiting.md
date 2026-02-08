# Rate Limiting - Platform Team Guide

## Overview

Rate limiting protects the Aussie API Gateway from abuse and ensures fair resource
allocation across all services. Platform teams control the rate limiting algorithm
and can set a maximum ceiling that service teams cannot exceed.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AUSSIE_RATE_LIMITING_ENABLED` | Enable/disable rate limiting | `true` |
| `AUSSIE_RATE_LIMITING_ALGORITHM` | Algorithm: `BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW` | `BUCKET` |
| `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW` | Maximum rate limit ceiling | `Long.MAX_VALUE` |
| `AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW` | Default for unconfigured services | `100` |
| `AUSSIE_RATE_LIMITING_WINDOW_SECONDS` | Time window duration | `60` |
| `AUSSIE_RATE_LIMITING_BURST_CAPACITY` | Burst capacity (bucket algorithm) | `100` |

### Algorithm Selection

| Algorithm | Best For | Behavior |
|-----------|----------|----------|
| `BUCKET` | General use | Allows controlled bursts, smooth refill |
| `FIXED_WINDOW` | Strict limits | Hard cutoff at window boundary |
| `SLIDING_WINDOW` | Smooth limits | More even distribution |

### Setting the Platform Maximum

The platform maximum prevents service teams from accidentally configuring
overly permissive rate limits that could enable DoS attacks.

**Recommendations:**
- Set a generous ceiling (e.g., 10,000 requests/minute)
- Monitor actual usage before lowering
- Consider peak traffic patterns

```bash
# Example: Set platform max to 10,000 requests per minute
export AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW=10000
```

### WebSocket Rate Limiting

WebSocket connections have two rate limiting dimensions:

| Limit Type | Description | Default |
|------------|-------------|---------|
| Connection rate | New connections per window | 10/minute |
| Message rate | Messages per second per connection | 100/second |

Configure via:
```properties
aussie.rate-limiting.websocket.connection.requests-per-window=10
aussie.rate-limiting.websocket.connection.window-seconds=60
aussie.rate-limiting.websocket.message.requests-per-window=100
aussie.rate-limiting.websocket.message.window-seconds=1
```

## Monitoring

### Key Metrics

| Metric | Description |
|--------|-------------|
| `aussie_ratelimit_checks_total` | Total rate limit checks by service |
| `aussie_ratelimit_exceeded_total` | Rate limit violations by service and type |

### Alerts

Pre-configured alerts in Prometheus:

| Alert | Severity | Description |
|-------|----------|-------------|
| `HighRateLimitRejections` | warning | High rejection rate (>10/sec for 5min) |
| `RateLimitExhaustion` | info | Service approaching limits |
| `SuspiciousRateLimitPattern` | critical | Possible DoS attempt (>100/sec) |

### Grafana Dashboard

A dedicated rate limiting dashboard is available at `monitoring/grafana/dashboards/rate-limiting.json` with:
- Rate limit checks over time by service
- Rejection rates by limit type
- Top rate-limited clients

## Storage Backends

### In-Memory (Default)

Suitable for single-instance deployments or development:
- State not shared across instances
- State lost on restart
- Stale entries automatically cleaned up after 2x the window duration
- No external dependencies

### Redis (Production)

For multi-instance deployments:
- Atomic Lua scripts for correctness
- Automatic key expiration
- Shared state across all gateway instances

Configure via:
```properties
aussie.rate-limiting.provider=redis
```

## Troubleshooting

### Common Issues

**1. Services reporting 429s unexpectedly**
- Check if platform max is too low
- Review service-level configuration
- Verify client identification is correct

**2. Rate limits not enforcing**
- Verify `AUSSIE_RATE_LIMITING_ENABLED=true`
- Check Redis connectivity (if using distributed limiter)
- Verify rate limit provider is loaded correctly

**3. High Redis latency**
- Consider using in-memory limiter for non-critical services
- Check Redis cluster health
- Review Lua script execution time

### Debugging

Enable debug logging for rate limiting:
```properties
quarkus.log.category."aussie.adapter.out.ratelimit".level=DEBUG
quarkus.log.category."aussie.system.filter.RateLimitFilter".level=DEBUG
```
