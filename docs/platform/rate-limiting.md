# Rate Limiting - Platform Team Guide

## Overview

Rate limiting protects the Aussie API Gateway from abuse and ensures fair resource allocation across all services. Platform teams control the rate limiting algorithm and can set a maximum ceiling that service teams cannot exceed.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AUSSIE_RATE_LIMITING_ENABLED` | Enable/disable rate limiting | `true` |
| `AUSSIE_RATE_LIMITING_ALGORITHM` | Algorithm (`BUCKET`; other values are rejected until implemented) | `BUCKET` |
| `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW` | Maximum rate limit ceiling | `Long.MAX_VALUE` |
| `AUSSIE_RATE_LIMITING_PLATFORM_MAX_WINDOW_SECONDS` | Maximum window duration in seconds | `Long.MAX_VALUE` |
| `AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW` | Default for unconfigured services | `100` |
| `AUSSIE_RATE_LIMITING_WINDOW_SECONDS` | Time window duration | `60` |
| `AUSSIE_RATE_LIMITING_BURST_CAPACITY` | Burst capacity (bucket algorithm) | `100` |
| `AUSSIE_RATE_LIMITING_REDIS_ENABLED` | Require Redis for distributed rate limiting | `false` |
| `AUSSIE_RATE_LIMITING_FALLBACK_BEHAVIOR` | Runtime Redis failure policy: `DENY`, `LOCAL_BUCKET`, or `ALLOW` | `DENY` |

### Algorithm Selection

| Algorithm | Best For | Behavior |
|-----------|----------|----------|
| `BUCKET` | General use | Allows controlled bursts, smooth refill |
| `FIXED_WINDOW` | Not implemented | Gateway startup is rejected |
| `SLIDING_WINDOW` | Not implemented | Gateway startup is rejected |

Unsupported algorithms fail startup; they never fall back to token-bucket semantics.

### Setting the Platform Maximum

The platform maximum prevents service teams from accidentally configuring
overly permissive rate limits that could enable DoS attacks.

Two maximums are available:
- **Requests per window** caps how many requests a service can allow per window.
  Registrations with `requestsPerWindow` or `burstCapacity` exceeding this value are
  rejected with a 400 error. As a safety net, values are also capped at runtime.
- **Window duration** caps how long a service's rate limit window can be.
  Registrations with a `windowSeconds` exceeding this value are rejected with a 400 error.

**Recommendations:**
- Set generous ceilings (e.g., 10,000 requests/minute, 3,600-second window)
- Monitor actual usage before lowering
- Consider peak traffic patterns

```bash
# Example: Set platform max to 10,000 requests per minute
export AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW=10000
# Example: Set platform max window duration to 1 hour
export AUSSIE_RATE_LIMITING_PLATFORM_MAX_WINDOW_SECONDS=3600
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
- Redis `TIME` is authoritative, so gateway clock skew cannot change decisions
- Automatic key expiration
- Shared state across all gateway instances
- Versioned keys (`v1:bucket`) prevent deployments from reinterpreting old state
- Cursor-based cleanup; production paths do not use Redis `KEYS`
- Authentication-abuse keys use a base64url identity inside a Redis Cluster
  hash tag, so failed-attempt, lockout, and escalation state share one slot;
  legacy TTL-backed keys remain readable and clearable during upgrades

Configure via:
```properties
aussie.rate-limiting.redis.enabled=true
aussie.rate-limiting.fallback.behavior=DENY
```

`DENY` is the default backend-outage policy. `LOCAL_BUCKET` is an explicitly
weaker, per-instance emergency mode; `ALLOW` is intended only for development.
When Redis is configured but cannot be constructed, startup fails instead of
silently switching the cluster to independent in-memory quotas.

Pre-authentication HTTP and WebSocket buckets use only the canonical network
identity. Service quotas use the resolved route's service ID, including
`/gateway/...` routes; unverified cookie, bearer, and API-key values never select
a bucket.

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
