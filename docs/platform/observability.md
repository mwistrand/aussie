# Platform Observability Guide

This guide covers Aussie's observability capabilities for platform teams, including distributed tracing, metrics collection, security monitoring, and traffic attribution.

## Overview

Aussie provides comprehensive observability through:

- **Distributed Tracing** - OpenTelemetry with W3C Trace Context propagation
- **Metrics** - Micrometer with Prometheus, Datadog, New Relic, or custom exporters
- **Security Monitoring** - Anomaly detection and security event handling via SPI
- **Traffic Attribution** - Cost allocation and billing metrics

The production profile enables metrics and security telemetry, and the production configuration
validator rejects attempts to disable them. Development and test profiles may enable individual
features as needed.

## Quick Start

Enable telemetry in `application.properties`:

```properties
# Enable all telemetry (recommended for production)
aussie.telemetry.enabled=true
```

Or enable individual features:

```properties
aussie.telemetry.tracing.enabled=true
aussie.telemetry.metrics.enabled=true
aussie.telemetry.security.enabled=true
aussie.telemetry.attribution.enabled=true
```

## Configuration Reference

### Core Telemetry

| Property | Default | Description |
|----------|---------|-------------|
| `aussie.telemetry.enabled` | `false` | Master toggle for all telemetry |
| `aussie.telemetry.tracing.enabled` | `false` | Enable distributed tracing |
| `aussie.telemetry.tracing.sample-rate` | `1.0` | Trace sampling rate (0.0-1.0) |
| `aussie.telemetry.metrics.enabled` | `false` | Enable metrics collection |
| `aussie.telemetry.security.enabled` | `false` | Enable security monitoring |
| `aussie.telemetry.security.rate-limit-window` | `PT1M` | Window for security event rate limiting |
| `aussie.telemetry.security.rate-limit-threshold` | `1000` | Max security events per window before throttling |
| `aussie.telemetry.security.dos-detection.enabled` | `true` | Enable automatic DoS pattern detection |
| `aussie.telemetry.security.dos-detection.spike-threshold` | `5.0` | Request rate spike multiplier for DoS detection |
| `aussie.telemetry.security.dos-detection.error-rate-threshold` | `0.5` | Error rate threshold for DoS detection |
| `aussie.telemetry.security.max-tracked-clients` | `10000` | Maximum clients retained for in-process anomaly detection |
| `aussie.telemetry.security.client-tracking-ttl` | `PT10M` | Inactivity TTL for anomaly-detection entries |
| `aussie.telemetry.attribution.enabled` | `false` | Enable traffic attribution |

### OpenTelemetry Configuration

When tracing is enabled, configure the OTLP exporter:

```properties
# OTLP Exporter (default)
quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=${aussie.telemetry.tracing.sample-rate}

# W3C Trace Context propagation (default)
quarkus.otel.propagators=tracecontext,baggage
```

### Micrometer Configuration

When metrics are enabled, configure the exporter:

```properties
# Prometheus (default)
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.export.prometheus.path=/q/metrics
```

### Traffic Attribution

```properties
aussie.telemetry.attribution.enabled=true
aussie.telemetry.attribution.tenant-header=X-Tenant-ID
aussie.telemetry.attribution.client-app-header=X-Client-App
```

Team ID is derived from the authenticated principal (API key `teamId` field), not from request headers. Set the `teamId` when creating API keys to enable team-based cost attribution.

## Backend Integrations

### Jaeger (Open Source)

```properties
quarkus.otel.exporter.otlp.traces.endpoint=http://jaeger:4317
```

Docker Compose:
```yaml
services:
  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"  # UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
```

### Prometheus + Grafana

Aussie exposes metrics at `/q/metrics` in Prometheus format.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'aussie'
    static_configs:
      - targets: ['api:8080']
    metrics_path: /q/metrics
```

### Datadog

```properties
# Datadog APM
%datadog.quarkus.otel.exporter.otlp.traces.endpoint=https://trace.agent.datadoghq.com:443
%datadog.quarkus.otel.exporter.otlp.traces.headers=DD-API-KEY=${DD_API_KEY}

# Datadog Metrics
%datadog.quarkus.micrometer.export.datadog.enabled=true
%datadog.quarkus.micrometer.export.datadog.api-key=${DD_API_KEY}
%datadog.quarkus.micrometer.export.prometheus.enabled=false
```

### New Relic

```properties
# New Relic OTLP
%newrelic.quarkus.otel.exporter.otlp.traces.endpoint=https://otlp.nr-data.net:4317
%newrelic.quarkus.otel.exporter.otlp.traces.headers=api-key=${NEW_RELIC_LICENSE_KEY}

# New Relic Metrics
%newrelic.quarkus.micrometer.export.newrelic.enabled=true
%newrelic.quarkus.micrometer.export.newrelic.api-key=${NEW_RELIC_LICENSE_KEY}
%newrelic.quarkus.micrometer.export.prometheus.enabled=false
```

### AWS X-Ray

```properties
%xray.quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
# Use AWS ADOT Collector configured for X-Ray
```

### Splunk

```properties
%splunk.quarkus.otel.exporter.otlp.traces.endpoint=https://ingest.${SPLUNK_REALM}.signalfx.com:443
%splunk.quarkus.otel.exporter.otlp.traces.headers=X-SF-Token=${SPLUNK_ACCESS_TOKEN}
```

## Metrics Reference

### Gateway Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.requests.total` | Counter | `service_id`, `method`, `status`, `status_class` | Total gateway requests |
| `aussie.gateway.results` | Counter | `service_id`, `result_type` | Gateway results by type |
| `aussie.proxy.latency` | Timer | `service_id`, `method`, `status_class` | Upstream proxy latency |
| `aussie.errors.total` | Counter | `service_id`, `error_type` | Gateway errors |
| `aussie.websockets.active` | Gauge | - | Active WebSocket connections |
| `aussie.connections.active` | Gauge | - | Active HTTP connections |
| `aussie.traffic.bytes` | Counter | `service_id`, `team_id`, `direction` | Traffic volume in bytes |

### Bulkhead Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.bulkhead.cassandra.pool.max` | Gauge | `type` | Configured max Cassandra connections per node |
| `aussie.bulkhead.cassandra.requests.max` | Gauge | `type` | Configured max requests per Cassandra connection |
| `aussie.bulkhead.redis.pool.max` | Gauge | `type` | Configured max Redis connections |
| `aussie.bulkhead.redis.pool.waiting.max` | Gauge | `type` | Configured max waiting requests when Redis pool exhausted |
| `aussie.bulkhead.http.pool.max.per_host` | Gauge | `type` | Configured max HTTP connections per upstream host |
| `aussie.bulkhead.http.pool.max.total` | Gauge | `type` | Configured max total HTTP connections |
| `aussie.bulkhead.jwks.pool.max` | Gauge | `type` | Configured max JWKS fetch connections |

These metrics expose configured bulkhead limits. For actual pool usage metrics, enable driver-level metrics:
- Cassandra: `quarkus.cassandra.metrics.enabled=true`
- Redis: Available via Quarkus Redis extension
- HTTP: `quarkus.micrometer.binder.vertx.enabled=true`

### Security Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.auth.failures.total` | Counter | `reason` | Authentication failures |
| `aussie.auth.success.total` | Counter | `method` | Successful authentications |
| `aussie.access.denied.total` | Counter | `service_id`, `reason` | Access denied events |
| `aussie.security.events.total` | Counter | `event_type`, `severity` | Security events (via SPI handlers) |
| `aussie.security.auth.failures` | Counter | `reason`, `method` | Auth failures (via SPI handlers) |
| `aussie.security.rate_limit.exceeded` | Counter | `service_id` | Rate limit violations |
| `aussie.security.dos.detected` | Counter | `attack_type` | DoS attack detections |
| `aussie.signing.key.ready` | Gauge | - | `1` when required token issuance has one active key published in JWKS; otherwise `0` |

### Traffic Attribution Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.attributed.requests.total` | Counter | `service_id`, `team_id`, `tenant_id`, `environment` | Attributed request count |
| `aussie.attributed.bytes.ingress` | Counter | (same) | Incoming data volume |
| `aussie.attributed.bytes.egress` | Counter | (same) | Outgoing data volume |
| `aussie.attributed.compute.units` | Counter | (same) | Normalized compute cost |
| `aussie.attributed.duration` | Timer | (same) | Request duration |

## SLOs and runbooks

The checked-in Prometheus rules publish gateway availability and p99 latency recording
rules. The availability objective is 99.9% successful server responses; alerts use
short- and medium-window error-budget burn so low traffic does not create a fixed-rate
false positive. New SLO alerts carry the `owner: platform-oncall` label and a runbook URL.

Operational actions are documented in the [observability runbooks](runbooks/observability.md)
for dependency outages, route/key failures, rate-limit fallback, traffic spikes,
migrations/rollback, capacity saturation, and WebSocket drain.

## Distributed Tracing

### Trace Context Propagation

Aussie automatically propagates W3C Trace Context headers (`traceparent`, `tracestate`) to downstream services. If an incoming request has no trace context, Aussie creates a new trace.

### Span Attributes

Aussie adds the following attributes to spans:

| Attribute | Description |
|-----------|-------------|
| `aussie.service.id` | Target service ID |
| `aussie.route.path` | Matched route path |
| `aussie.route.method` | HTTP method |
| `http.method` | HTTP method (OTel semantic) |
| `http.url` | Upstream URL without user info, query, or fragment |
| `http.status_code` | Response status code |
| `net.peer.name` | Upstream host |
| `net.peer.port` | Upstream port |

### Configurable Span Attributes

Some span attributes can be enabled or disabled to control cardinality and storage costs. All configurable attributes respect the master `aussie.telemetry.enabled` and `aussie.telemetry.tracing.enabled` switches.

| Property | Default | Description |
|----------|---------|-------------|
| `aussie.telemetry.attributes.request-size` | `true` | Request body size in bytes |
| `aussie.telemetry.attributes.response-size` | `true` | Response body size in bytes |
| `aussie.telemetry.attributes.upstream-host` | `true` | Upstream service hostname |
| `aussie.telemetry.attributes.upstream-port` | `true` | Upstream service port |
| `aussie.telemetry.attributes.upstream-uri` | `false` | Upstream URI without user info, query, or fragment (high-cardinality) |
| `aussie.telemetry.attributes.upstream-latency` | `true` | Upstream call latency in milliseconds |
| `aussie.telemetry.attributes.rate-limited` | `true` | Whether request was rate limited |
| `aussie.telemetry.attributes.rate-limit-remaining` | `true` | Remaining requests in window |
| `aussie.telemetry.attributes.rate-limit-type` | `true` | Type of rate limit (http, ws_connection) |
| `aussie.telemetry.attributes.rate-limit-retry-after` | `true` | Seconds until rate limit resets |
| `aussie.telemetry.attributes.auth-rate-limited` | `true` | Whether request was auth rate limited (brute force protection) |
| `aussie.telemetry.attributes.auth-lockout-key` | `true` | The lockout key (IP or identifier) |
| `aussie.telemetry.attributes.auth-lockout-retry-after` | `true` | Seconds until auth lockout resets |

**High-Cardinality Warning**: `upstream-uri` is disabled by default because variable path segments can create unbounded cardinality. User info, query parameters, and fragments are always omitted to avoid exporting credentials or other sensitive values. Enable only for debugging in non-production environments.

Example configuration to enable all attributes for debugging:

```properties
# Production: high-cardinality attributes disabled (default)
aussie.telemetry.attributes.upstream-uri=false

# Development: enable for debugging
%dev.aussie.telemetry.attributes.upstream-uri=true
```

## Security Monitoring

### Security Events

Aussie monitors for security anomalies and emits events for:

- **Authentication failures** - Invalid API keys, expired sessions
- **Access denied** - Permission/policy violations
- **Rate limit exceeded** - Throttling events
- **Suspicious patterns** - Unusual request patterns
- **DoS detection** - High request volumes from single sources

### Custom Security Event Handlers

Implement the `SecurityEventHandler` SPI to process security events:

```java
package com.example;

import aussie.spi.SecurityEventHandler;
import aussie.telemetry.security.SecurityEvent;

public class SlackAlertHandler implements SecurityEventHandler {

    @Override
    public String name() {
        return "slack-alerts";
    }

    @Override
    public int priority() {
        return 100; // Higher priority = earlier execution
    }

    @Override
    public void handle(SecurityEvent event) {
        if (event.severity() == SecurityEvent.Severity.CRITICAL) {
            sendSlackAlert(event);
        }
    }

    private void sendSlackAlert(SecurityEvent event) {
        // Send to Slack webhook
    }
}
```

Register via ServiceLoader in `META-INF/services/aussie.spi.SecurityEventHandler`:

```
com.example.SlackAlertHandler
```

### Built-in Handlers

| Handler | Priority | Description |
|---------|----------|-------------|
| `MetricsSecurityEventHandler` | 10 | Records events as Micrometer metrics |
| `LoggingSecurityEventHandler` | 0 | Logs events via JBoss Logging |

## Traffic Attribution

### Configuration

Enable attribution and configure header extraction:

```properties
aussie.telemetry.attribution.enabled=true
aussie.telemetry.attribution.tenant-header=X-Tenant-ID
aussie.telemetry.attribution.client-app-header=X-Client-App
```

Team ID is derived from the authenticated API key's `teamId` field, not from request headers.

### Compute Units

Traffic attribution calculates normalized compute units:

```
compute_units = 1.0 (base) +
                (request_bytes + response_bytes) / 10KB +
                duration_ms / 100ms
```

### PromQL Examples

**Requests by team:**
```promql
sum(rate(aussie_attributed_requests_total[5m])) by (team_id)
```

**Data transfer by tenant:**
```promql
sum(rate(aussie_attributed_bytes_ingress[5m]) + rate(aussie_attributed_bytes_egress[5m])) by (tenant_id)
```

**Compute units by service:**
```promql
sum(rate(aussie_attributed_compute_units[5m])) by (service_id)
```

## Alerting Examples

### Prometheus Alertmanager Rules

```yaml
groups:
  - name: aussie-alerts
    rules:
      - alert: HighErrorRate
        expr: |
          sum(rate(aussie_errors_total[5m])) by (service_id)
          / sum(rate(aussie_requests_total[5m])) by (service_id) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High error rate for {{ $labels.service_id }}"

      - alert: AuthFailureSpike
        expr: |
          sum(rate(aussie_auth_failures_total[5m])) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Authentication failure spike detected"

      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            sum(rate(aussie_proxy_latency_seconds_bucket[5m])) by (le, service_id)
          ) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High p99 latency for {{ $labels.service_id }}"

      # Bulkhead alerts - monitor driver-provided pool usage against configured limits
      # Note: These use driver metrics, not aussie.bulkhead.* (which are configured limits)
      - alert: CassandraPoolNearCapacity
        expr: |
          cassandra_pool_open_connections / on() aussie_bulkhead_cassandra_pool_max > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Cassandra connection pool at >85% capacity"

      - alert: RedisPoolNearCapacity
        expr: |
          redis_pool_active / on() aussie_bulkhead_redis_pool_max > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Redis connection pool at >85% capacity"
```

## Grafana Dashboards

Pre-built dashboards are available in `monitoring/grafana/dashboards/`:

- `gateway-overview.json` - Gateway metrics overview
- `security.json` - Security events and auth failures
- `traffic-attribution.json` - Cost allocation metrics

Import these dashboards into Grafana or use the provisioning configuration.

## Development Profile

The `dev` profile enables telemetry for local development:

```properties
%dev.aussie.telemetry.enabled=true
%dev.quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
```

Run with the optional observability stack:

```bash
make up
```

Access:
- Jaeger UI: http://localhost:16686
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- Alertmanager: http://localhost:9093

## Troubleshooting

### Traces Not Appearing

1. Verify tracing is enabled: `aussie.telemetry.tracing.enabled=true`
2. Check OTLP endpoint is reachable
3. Verify sample rate is not 0.0
4. Check Jaeger/backend logs for errors

### Metrics Not Exposed

1. Verify metrics are enabled: `aussie.telemetry.metrics.enabled=true`
2. Access `/q/metrics` directly to check Prometheus endpoint
3. Verify Prometheus scrape config targets the correct endpoint

### Security Events Not Processing

1. Verify security monitoring is enabled: `aussie.telemetry.security.enabled=true`
2. Check logs for handler initialization
3. Verify SPI registration in `META-INF/services/`

## Operational contract

- Production must keep `aussie.telemetry.enabled`, metrics, and security monitoring enabled;
  the normal-mode validator rejects missing required signals.
- Metric labels are limited to service, route/method class, status class, reason code, and
  other bounded categories. Client, session, token, user, and IP identifiers are never labels.
- In-process anomaly detection is bounded by `max-tracked-clients` and
  `client-tracking-ttl`; use the distributed abuse-control backend for cluster-wide decisions.
- The Prometheus Compose target is the `api` service. Validate `/q/metrics` and alert rules after
  changing metric names or labels.

For an outage, first verify `/q/health/ready` and `/q/metrics`, then check the dependency-specific
readiness reason, Redis/Cassandra health, route convergence, and signing-key readiness. Keep
traffic blocked while a required security dependency is unavailable; do not enable a local or
fail-open fallback in production.

## Hierarchical Trace Sampling

For fine-grained control over trace sampling, enable hierarchical sampling:

```properties
aussie.telemetry.sampling.enabled=true
aussie.telemetry.sampling.default-rate=0.1
```

This allows:
- Platform-wide default sampling rates
- Service-level overrides
- Endpoint-level overrides
- Platform minimum/maximum bounds

See [Hierarchical Sampling Guide](sampling.md) for complete configuration details.

## Performance Considerations

- **Sampling**: Use hierarchical sampling (`aussie.telemetry.sampling.*`) for granular control, or `aussie.telemetry.tracing.sample-rate` for simple global sampling
- **Metrics cardinality**: Avoid high-cardinality labels in custom metrics
- **Security monitoring**: Event dispatch is async and won't block requests
- **Attribution**: Only enabled for successful requests to minimize overhead
