# Hierarchical Trace Sampling - Platform Team Guide

## Overview

Hierarchical trace sampling allows platform teams to control OpenTelemetry trace collection rates across the gateway. This reduces storage costs and backend load while ensuring critical traces are always captured.

The sampling hierarchy allows configuration at three levels:
1. **Platform defaults** - Global fallback for all services
2. **Service-level overrides** - Per-service customization
3. **Endpoint-level overrides** - Fine-grained control for specific endpoints

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AUSSIE_TELEMETRY_SAMPLING_ENABLED` | Enable hierarchical sampling | `false` |
| `AUSSIE_TELEMETRY_SAMPLING_DEFAULT_RATE` | Default sampling rate (0.0-1.0) | `1.0` |
| `AUSSIE_TELEMETRY_SAMPLING_MINIMUM_RATE` | Platform minimum (floor) | `0.0` |
| `AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE` | Platform maximum (ceiling) | `1.0` |

### Application Properties

```properties
# Enable hierarchical sampling
aussie.telemetry.sampling.enabled=true

# Default: sample 10% of traces
aussie.telemetry.sampling.default-rate=0.1

# Platform bounds (service configs are clamped to these)
aussie.telemetry.sampling.minimum-rate=0.01
aussie.telemetry.sampling.maximum-rate=1.0
```

### Understanding Sampling Rates

| Rate | Meaning |
|------|---------|
| `1.0` | No sampling - 100% of requests traced |
| `0.5` | Sample 50% of requests |
| `0.1` | Sample 10% of requests |
| `0.0` | Drop all traces (not recommended) |

## Resolution Hierarchy

When a request arrives, the effective sampling rate is determined by:

```
1. Endpoint-level config (if present) → use it
2. Service-level config (if present)  → use it
3. Platform default                   → use it
```

All resolved rates are clamped to platform minimum and maximum bounds.

### Example

```
Platform config:
  default-rate: 0.1
  minimum-rate: 0.01
  maximum-rate: 0.8

Service "orders":
  samplingRate: 0.5

Endpoint "/orders/api/health":
  samplingRate: 1.0 (no sampling for health checks)

Results:
  /orders/api/health     → 0.8 (clamped from 1.0 to max)
  /orders/api/orders     → 0.5 (service config)
  /inventory/api/stock   → 0.1 (platform default)
```

## Platform Bounds

### Setting the Minimum Rate

Use the minimum rate to ensure you always capture some traces, even if services misconfigure:

```bash
# Ensure at least 1% of traces are captured
export AUSSIE_TELEMETRY_SAMPLING_MINIMUM_RATE=0.01
```

### Setting the Maximum Rate

Use the maximum rate to control costs during high-traffic periods:

```bash
# Cap at 50% sampling during peak traffic
export AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE=0.5
```

### When Services Request "No Sampling"

Services can request `samplingRate: 1.0` (100% trace rate) for critical endpoints. If you need to override this during incidents:

```bash
# Emergency: reduce all sampling to 10% max
export AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE=0.1
```

## Parent-Based Sampling

The hierarchical sampler respects parent trace decisions:

- **Parent sampled**: Child span is always sampled (maintains trace continuity)
- **Parent not sampled**: Child span is dropped (respects upstream decision)
- **No parent (root span)**: Apply hierarchical sampling rate

This ensures distributed traces remain complete when sampling is applied at the edge.

## Caching

Sampling configurations are cached for performance:

| Cache Layer | TTL | Purpose |
|-------------|-----|---------|
| In-memory (Caffeine) | Configurable | Fast lookups, per-instance |
| Source (Cassandra) | - | Source of truth |

Configure the local cache TTL:

```properties
# Cache sampling configs for 30 seconds (default: 5 minutes)
aussie.cache.sampling-config-ttl=PT30S
```

## Monitoring

### Key Metrics

| Metric | Description |
|--------|-------------|
| `aussie.sampling.decisions.total` | Sampling decisions by service and result |
| `aussie.sampling.rate.effective` | Effective sampling rate by service |

### Alerts

Consider alerting on:

```yaml
# Alert if sampling drops unexpectedly
- alert: LowSamplingRate
  expr: aussie_sampling_rate_effective < 0.01
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Very low sampling rate for {{ $labels.service_id }}"
```

## Integration with OTel Collector

When using the OTel Collector, you can apply additional sampling:

```yaml
# otel-collector-config.yaml
processors:
  probabilistic_sampler:
    sampling_percentage: 100  # Let gateway handle sampling

pipelines:
  traces:
    receivers: [otlp]
    processors: [probabilistic_sampler, batch]
    exporters: [jaeger]
```

For cost optimization, consider tail-based sampling at the collector level for error traces:

```yaml
processors:
  tail_sampling:
    decision_wait: 10s
    policies:
      - name: errors-policy
        type: status_code
        status_code: {status_codes: [ERROR]}
      - name: probabilistic-policy
        type: probabilistic
        probabilistic: {sampling_percentage: 10}
```

## Disabling Hierarchical Sampling

To use standard OTel sampling instead:

```properties
# Disable hierarchical sampling
aussie.telemetry.sampling.enabled=false

# Configure standard OTel sampler
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=0.1
```

## Troubleshooting

### Traces Missing for a Service

1. Check if hierarchical sampling is enabled:
   ```bash
   curl http://localhost:8080/q/health | jq '.checks[] | select(.name == "sampling")'
   ```

2. Verify service sampling config in Cassandra:
   ```cql
   SELECT service_id, sampling_config FROM service_registrations WHERE service_id = 'my-service';
   ```

3. Check effective rate via admin API:
   ```bash
   curl http://localhost:8080/admin/services/my-service | jq '.samplingConfig'
   ```

### All Traces Being Dropped

1. Verify platform minimum is not 0.0:
   ```properties
   aussie.telemetry.sampling.minimum-rate=0.01
   ```

2. Check for configuration typos (rate must be 0.0-1.0)

3. Ensure OTel exporter is correctly configured

### High Storage Costs

1. Lower the platform default rate:
   ```properties
   aussie.telemetry.sampling.default-rate=0.05
   ```

2. Set a maximum rate ceiling:
   ```properties
   aussie.telemetry.sampling.maximum-rate=0.5
   ```

3. Review service-level configs for overly permissive rates

### Cache Inconsistency

If sampling configs aren't updating:

1. Wait for cache TTL to expire (default 5 minutes)
2. Restart gateway instances for immediate effect
3. Reduce cache TTL for faster propagation:
   ```properties
   aussie.cache.sampling-config-ttl=PT30S
   ```

## Best Practices

1. **Start with high sampling** - Begin with `default-rate=1.0` and reduce based on data
2. **Set a minimum floor** - Use `minimum-rate=0.01` to ensure some visibility
3. **Use endpoint overrides sparingly** - Most services need only service-level config
4. **Monitor effective rates** - Track actual sampling to catch misconfigurations
5. **Document service requirements** - Help teams understand why their traces may be sampled
