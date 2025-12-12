# Platform Observability Guide

This guide covers Aussie's observability capabilities for platform teams, including distributed tracing, metrics collection, security monitoring, and traffic attribution.

## Overview

Aussie provides comprehensive observability through:

- **Distributed Tracing** - OpenTelemetry with W3C Trace Context propagation
- **Metrics** - Micrometer with Prometheus, Datadog, New Relic, or custom exporters
- **Security Monitoring** - Anomaly detection and security event handling via SPI
- **Traffic Attribution** - Cost allocation and billing metrics

All telemetry features are **disabled by default** and must be explicitly enabled.

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
aussie.telemetry.attribution.team-header=X-Team-ID
aussie.telemetry.attribution.tenant-header=X-Tenant-ID
aussie.telemetry.attribution.client-app-header=X-Client-App
aussie.telemetry.attribution.include-headers=false
```

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
      - targets: ['aussie:8080']
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

### Security Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.auth.failures.total` | Counter | `reason`, `client_ip_hash` | Authentication failures |
| `aussie.auth.success.total` | Counter | `method` | Successful authentications |
| `aussie.access.denied.total` | Counter | `service_id`, `reason` | Access denied events |
| `aussie.security.events.total` | Counter | `event_type`, `severity` | Security events (via SPI handlers) |
| `aussie.security.auth.failures` | Counter | `reason`, `method`, `client_ip_hash` | Auth failures (via SPI handlers) |
| `aussie.security.rate_limit.exceeded` | Counter | `service_id`, `client_ip_hash` | Rate limit violations |
| `aussie.security.dos.detected` | Counter | `attack_type`, `client_ip_hash` | DoS attack detections |

### Traffic Attribution Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.attributed.requests.total` | Counter | `service_id`, `team_id`, `tenant_id`, `environment` | Attributed request count |
| `aussie.attributed.bytes.ingress` | Counter | (same) | Incoming data volume |
| `aussie.attributed.bytes.egress` | Counter | (same) | Outgoing data volume |
| `aussie.attributed.compute.units` | Counter | (same) | Normalized compute cost |
| `aussie.attributed.duration` | Timer | (same) | Request duration |

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
| `http.url` | Request URL |
| `http.status_code` | Response status code |
| `net.peer.name` | Upstream host |
| `net.peer.port` | Upstream port |

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
aussie.telemetry.attribution.team-header=X-Team-ID
aussie.telemetry.attribution.tenant-header=X-Tenant-ID
aussie.telemetry.attribution.client-app-header=X-Client-App
```

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

## Performance Considerations

- **Sampling**: Use `aussie.telemetry.tracing.sample-rate` to reduce trace volume in high-traffic environments
- **Metrics cardinality**: Avoid high-cardinality labels in custom metrics
- **Security monitoring**: Event dispatch is async and won't block requests
- **Attribution**: Only enabled for successful requests to minimize overhead
