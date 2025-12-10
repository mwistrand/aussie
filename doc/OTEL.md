# OpenTelemetry & Observability Guide

This guide covers the complete observability stack for Aussie Gateway, including distributed tracing, metrics collection, traffic attribution for cost allocation, and security monitoring.

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture Overview](#architecture-overview)
3. [Default Stack (Jaeger + Prometheus + Grafana)](#default-stack)
4. [Environment Variables](#environment-variables)
5. [Using Custom Backends](#using-custom-backends)
6. [Metrics Reference](#metrics-reference)
7. [Traffic Attribution & Cost Allocation](#traffic-attribution--cost-allocation)
8. [Security Monitoring](#security-monitoring)
9. [Alerting Configuration](#alerting-configuration)
10. [Troubleshooting](#troubleshooting)

---

## Quick Start

### Start the Observability Stack

```bash
docker compose up -d jaeger prometheus grafana alertmanager
```

### Access the UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9090 | - |
| Jaeger | http://localhost:16686 | - |
| Alertmanager | http://localhost:9093 | - |
| Metrics Endpoint | http://localhost:1234/q/metrics | - |

### Verify Telemetry is Working

```bash
# Check metrics endpoint
curl http://localhost:1234/q/metrics | grep aussie

# Check Prometheus targets
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets'

# View traces in Jaeger UI
open http://localhost:16686
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Aussie Gateway                                │
│                                                                         │
│  ┌───────────────┐    ┌───────────────┐    ┌───────────────┐          │
│  │ HTTP Requests │    │   WebSocket   │    │    Admin      │          │
│  │    (REST)     │    │  Connections  │    │   Operations  │          │
│  └───────┬───────┘    └───────┬───────┘    └───────┬───────┘          │
│          │                    │                    │                   │
│          └────────────────────┴────────────────────┘                   │
│                               │                                        │
│                    ┌──────────┴──────────┐                            │
│                    │  Telemetry Layer    │                            │
│                    │  - GatewayMetrics   │                            │
│                    │  - SecurityMonitor  │                            │
│                    │  - TrafficAttr      │                            │
│                    └──────────┬──────────┘                            │
│                               │                                        │
│         ┌─────────────────────┼─────────────────────┐                 │
│         │                     │                     │                 │
│    ┌────┴────┐          ┌─────┴─────┐        ┌─────┴─────┐           │
│    │ Tracer  │          │   Meter   │        │  Events   │           │
│    │ (OTel)  │          │(Micrometer)│       │   (CDI)   │           │
│    └────┬────┘          └─────┬─────┘        └─────┬─────┘           │
│         │                     │                    │                  │
└─────────┼─────────────────────┼────────────────────┼──────────────────┘
          │                     │                    │
          ▼                     ▼                    ▼
    ┌───────────┐        ┌───────────┐        ┌───────────┐
    │  Jaeger   │        │Prometheus │        │Alertmanager│
    │  (OTLP)   │        │ (Scrape)  │        │           │
    └─────┬─────┘        └─────┬─────┘        └─────┬─────┘
          │                    │                    │
          └────────────────────┴────────────────────┘
                               │
                               ▼
                        ┌───────────┐
                        │  Grafana  │
                        │ Dashboards│
                        └───────────┘
```

---

## Default Stack

The default observability stack uses open-source components:

| Component | Purpose | Port | Protocol |
|-----------|---------|------|----------|
| **Jaeger** | Distributed tracing | 16686 (UI), 4317 (OTLP gRPC), 4318 (OTLP HTTP) | OTLP |
| **Prometheus** | Metrics collection | 9090 | Scrape |
| **Grafana** | Dashboards & visualization | 3001 | HTTP |
| **Alertmanager** | Alert routing | 9093 | HTTP |

### Docker Compose Services

```yaml
services:
  jaeger:
    image: jaegertracing/all-in-one:1.57
    ports:
      - "16686:16686"  # UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP

  prometheus:
    image: prom/prometheus:v2.52.0
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:11.0.0
    ports:
      - "3001:3000"

  alertmanager:
    image: prom/alertmanager:v0.27.0
    ports:
      - "9093:9093"
```

---

## Environment Variables

### Core Telemetry Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_ENV` | `development` | Environment name (appears in traces/metrics) |
| `OTEL_TRACES_ENABLED` | `true` | Enable/disable distributed tracing |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Sampling rate (1.0 = 100%, 0.1 = 10%) |

### Tracing Exporter Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://jaeger:4317` | OTLP endpoint for traces |
| `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `grpc` | Protocol: `grpc` or `http/protobuf` |
| `OTEL_EXPORTER_OTLP_HEADERS` | (none) | Headers for authenticated endpoints |

### Metrics Configuration

Metrics are exposed at `/q/metrics` in Prometheus format. Prometheus scrapes this endpoint.

### Traffic Attribution Headers

| Variable | Default | Description |
|----------|---------|-------------|
| (config property) | `X-Team-ID` | Header name for team identification |
| (config property) | `X-Cost-Center` | Header name for cost center |
| (config property) | `X-Tenant-ID` | Header name for tenant identification |

### Security Monitoring

| Variable | Default | Description |
|----------|---------|-------------|
| `SECURITY_ALERTING_WEBHOOK` | (none) | Webhook URL for security alerts |

### Grafana Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAFANA_PASSWORD` | `admin` | Admin password for Grafana |

---

## Using Custom Backends

### Datadog Integration

To send telemetry to Datadog instead of the default stack:

#### 1. Set Environment Variables

```bash
export DD_API_KEY="your-datadog-api-key"
export DD_SITE="datadoghq.com"  # or datadoghq.eu for EU

# For traces via OTLP
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="https://trace.agent.datadoghq.com:443"
export OTEL_EXPORTER_OTLP_HEADERS="DD-API-KEY=${DD_API_KEY}"

# For metrics, use Datadog Agent or configure Micrometer Datadog registry
```

#### 2. Use Datadog Profile

Create a Quarkus profile for Datadog:

```properties
# application.properties
%datadog.quarkus.otel.exporter.otlp.traces.endpoint=https://trace.agent.datadoghq.com:443
%datadog.quarkus.otel.exporter.otlp.traces.headers=DD-API-KEY=${DD_API_KEY}
```

Run with profile:

```bash
java -Dquarkus.profile=datadog -jar aussie-gateway.jar
```

### New Relic Integration

```bash
export NEW_RELIC_LICENSE_KEY="your-license-key"
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="https://otlp.nr-data.net:4317"
export OTEL_EXPORTER_OTLP_HEADERS="api-key=${NEW_RELIC_LICENSE_KEY}"
```

### Honeycomb Integration

```bash
export HONEYCOMB_API_KEY="your-api-key"
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="https://api.honeycomb.io"
export OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=${HONEYCOMB_API_KEY}"
```

### Generic OTLP Endpoint

For any OTLP-compatible backend:

```bash
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="https://your-collector.example.com:4317"
export OTEL_EXPORTER_OTLP_TRACES_PROTOCOL="grpc"  # or "http/protobuf"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer your-token"
```

### Disable Telemetry Entirely

```bash
export OTEL_TRACES_ENABLED=false
```

Or in `application.properties`:

```properties
quarkus.otel.traces.enabled=false
quarkus.micrometer.enabled=false
```

---

## Metrics Reference

### HTTP Request Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_requests_total` | Counter | service_id, method, status, status_class | Total requests processed |
| `aussie_requests_duration` | Timer | service_id, method, status, status_class | Request duration |
| `aussie_request_size_bytes` | Summary | service_id | Request body size |
| `aussie_response_size_bytes` | Summary | service_id | Response body size |

### Proxy/Upstream Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_proxy_latency` | Timer | service_id, method, upstream_status, upstream_status_class | Time to upstream response |

### Connection Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_connections_http_active` | Gauge | - | Active HTTP connections |
| `aussie_connections_websocket_active` | Gauge | - | Active WebSocket connections |
| `aussie_websocket_events_total` | Counter | service_id, event | WebSocket connection events |
| `aussie_websocket_connection_duration` | Timer | service_id | WebSocket session duration |

### Error Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_errors_total` | Counter | service_id, error_type | Total errors |
| `aussie_errors_route_not_found_total` | Counter | - | Route not found errors |
| `aussie_errors_service_not_found_total` | Counter | service_id | Service not found errors |

### Authentication Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_auth_attempts_total` | Counter | method, result | Auth attempts |
| `aussie_auth_failures_total` | Counter | method, reason, client_ip_hash | Auth failures |
| `aussie_access_denied_total` | Counter | service_id, reason | Access denied events |

### Traffic Attribution Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_attributed_requests_total` | Counter | service_id, team_id, cost_center, tenant_id | Attributed requests |
| `aussie_attributed_bytes_ingress` | Counter | (same) | Incoming bytes |
| `aussie_attributed_bytes_egress` | Counter | (same) | Outgoing bytes |
| `aussie_attributed_compute_units` | Counter | (same) | Compute units consumed |
| `aussie_attributed_duration` | Timer | (same) | Request duration |

### Security Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie_security_events_total` | Counter | event_type | Security events detected |
| `aussie_security_rate_limit_exceeded_total` | Counter | client_ip_hash, service_id | Rate limit violations |

---

## Traffic Attribution & Cost Allocation

### How It Works

Aussie tracks traffic metrics with attribution dimensions that can be used for cost allocation:

1. **Team ID**: Identifies the team responsible for traffic
2. **Cost Center**: Billing unit for chargeback
3. **Tenant ID**: Multi-tenant identifier
4. **Service ID**: Target service

### Setting Attribution Headers

Clients include attribution headers in their requests:

```bash
curl -H "X-Team-ID: platform-team" \
     -H "X-Cost-Center: CC-12345" \
     -H "X-Tenant-ID: tenant-a" \
     http://gateway/my-service/api/resource
```

### Configuring Header Names

In `application.properties`:

```properties
aussie.telemetry.traffic-attribution.team-header=X-Team-ID
aussie.telemetry.traffic-attribution.cost-center-header=X-Cost-Center
aussie.telemetry.traffic-attribution.tenant-header=X-Tenant-ID
```

### Example Cost Allocation Queries

#### Total Requests per Team (Last 30 Days)

```promql
sum by (team_id, service_id) (
  increase(aussie_attributed_requests_total[30d])
)
```

#### Data Transfer per Cost Center (GB)

```promql
sum by (cost_center) (
  increase(aussie_attributed_bytes_ingress[30d]) +
  increase(aussie_attributed_bytes_egress[30d])
) / 1e9
```

#### Compute Units per Team per Day

```promql
sum by (team_id) (
  increase(aussie_attributed_compute_units[1d])
)
```

#### Monthly Cost Report (Export to CSV)

Using Prometheus HTTP API:

```bash
curl -G 'http://localhost:9090/api/v1/query' \
  --data-urlencode 'query=sum by (team_id, cost_center) (increase(aussie_attributed_requests_total[30d]))' \
  | jq -r '.data.result[] | [.metric.team_id, .metric.cost_center, .value[1]] | @csv'
```

---

## Security Monitoring

### Detection Capabilities

Aussie monitors for:

| Threat | Detection Method | Metric |
|--------|------------------|--------|
| **DoS Attacks** | Request spike detection | `aussie_security_rate_limit_exceeded_total` |
| **Brute Force** | Repeated auth failures | `aussie_auth_failures_total` |
| **Anomalies** | High error rates | `aussie_errors_total` |

### Configuration

```properties
# Security monitoring
aussie.telemetry.security.enabled=true
aussie.telemetry.security.rate-limit-window=PT60S
aussie.telemetry.security.rate-limit-threshold=1000

# DoS detection
aussie.telemetry.security.dos-detection.enabled=true
aussie.telemetry.security.dos-detection.request-spike-threshold=5.0
aussie.telemetry.security.dos-detection.error-rate-threshold=0.5

# Brute force detection
aussie.telemetry.security.brute-force.enabled=true
aussie.telemetry.security.brute-force.failure-threshold=5
aussie.telemetry.security.brute-force.window=PT5M
```

### Security Alert Webhook

Configure a webhook to receive security alerts:

```bash
export SECURITY_ALERTING_WEBHOOK="https://your-siem.example.com/webhook"
```

The webhook receives POST requests with JSON:

```json
{
  "event_type": "DosAttackDetected",
  "timestamp": "2024-01-15T10:30:00Z",
  "client_ip_hash": "a1b2c3d4",
  "details": {
    "request_count": 5000,
    "threshold": 1000,
    "spike_factor": 5.0
  }
}
```

---

## Alerting Configuration

### Default Alerts

Pre-configured alerts in `monitoring/prometheus/alerts/aussie-gateway.yaml`:

| Alert | Severity | Description |
|-------|----------|-------------|
| `AussieGatewayDown` | Critical | Gateway unreachable |
| `HighErrorRate` | Warning | Error rate > 5% |
| `HighLatency` | Warning | P99 latency > 5s |
| `HighAuthFailureRate` | Warning | > 10 auth failures/sec |
| `PossibleDosAttack` | Critical | > 50 rate limit violations/min |
| `HighMemoryUsage` | Warning | JVM heap > 90% |
| `UpstreamHighLatency` | Warning | Upstream P95 > 2s |
| `UpstreamHighErrorRate` | Warning | Upstream errors > 10% |

### Configuring Alert Notifications

Edit `monitoring/alertmanager/alertmanager.yml`:

#### Slack Notifications

```yaml
receivers:
  - name: 'slack-critical'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
        channel: '#alerts-critical'
        send_resolved: true
        title: '{{ .GroupLabels.alertname }}'
        text: '{{ .CommonAnnotations.description }}'
```

#### Email Notifications

```yaml
global:
  smtp_smarthost: 'smtp.example.com:587'
  smtp_from: 'alertmanager@example.com'
  smtp_auth_username: 'alerts@example.com'
  smtp_auth_password: 'password'

receivers:
  - name: 'email-team'
    email_configs:
      - to: 'platform-team@example.com'
        send_resolved: true
```

#### PagerDuty Integration

```yaml
receivers:
  - name: 'pagerduty-critical'
    pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_SERVICE_KEY'
        severity: 'critical'
```

---

## Troubleshooting

### Traces Not Appearing in Jaeger

1. **Check OTLP endpoint is reachable:**
   ```bash
   curl -v http://localhost:4317
   ```

2. **Verify environment variables:**
   ```bash
   docker exec aussie-api env | grep OTEL
   ```

3. **Check Aussie logs for OTLP errors:**
   ```bash
   docker logs aussie-api 2>&1 | grep -i otel
   ```

4. **Verify sampling rate is not 0:**
   ```bash
   # Should be > 0
   echo $OTEL_TRACES_SAMPLER_ARG
   ```

### Metrics Not Appearing in Prometheus

1. **Check metrics endpoint:**
   ```bash
   curl http://localhost:1234/q/metrics | head
   ```

2. **Verify Prometheus target is up:**
   ```bash
   curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="aussie-gateway")'
   ```

3. **Check Prometheus config:**
   ```bash
   docker exec aussie-prometheus cat /etc/prometheus/prometheus.yml
   ```

### High Cardinality Warnings

If you see warnings about high cardinality metrics:

1. **Disable per-endpoint metrics:**
   ```properties
   aussie.telemetry.metrics.per-endpoint=false
   ```

2. **Reduce max cardinality:**
   ```properties
   aussie.telemetry.metrics.max-cardinality=500
   ```

### Connection to Jaeger Refused

If using Docker, ensure services are on the same network:

```yaml
services:
  api:
    networks:
      - aussie-network
  jaeger:
    networks:
      - aussie-network

networks:
  aussie-network:
```

### Reducing Trace Volume in Production

For high-traffic production environments:

```properties
# Sample 10% of traces
%prod.quarkus.otel.traces.sampler.arg=0.1

# Or use parent-based sampling (sample if parent is sampled)
quarkus.otel.traces.sampler=parentbased_traceidratio
```

---

## Configuration Reference

### All Telemetry Properties

```properties
# =============================================================================
# OpenTelemetry Tracing
# =============================================================================
quarkus.otel.traces.enabled=true
quarkus.otel.service.name=aussie-gateway
quarkus.otel.exporter.otlp.traces.endpoint=http://jaeger:4317
quarkus.otel.exporter.otlp.traces.protocol=grpc
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=1.0
quarkus.otel.propagators=tracecontext,baggage

# =============================================================================
# Micrometer/Prometheus Metrics
# =============================================================================
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.export.prometheus.path=/q/metrics
quarkus.micrometer.binder.http-client.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.jvm=true
quarkus.micrometer.binder.system=true
quarkus.micrometer.binder.vertx.enabled=true

# =============================================================================
# Traffic Attribution
# =============================================================================
aussie.telemetry.traffic-attribution.enabled=true
aussie.telemetry.traffic-attribution.team-header=X-Team-ID
aussie.telemetry.traffic-attribution.cost-center-header=X-Cost-Center
aussie.telemetry.traffic-attribution.tenant-header=X-Tenant-ID

# =============================================================================
# Security Monitoring
# =============================================================================
aussie.telemetry.security.enabled=true
aussie.telemetry.security.rate-limit-window=PT60S
aussie.telemetry.security.rate-limit-threshold=1000
aussie.telemetry.security.dos-detection.enabled=true
aussie.telemetry.security.dos-detection.request-spike-threshold=5.0
aussie.telemetry.security.dos-detection.error-rate-threshold=0.5
aussie.telemetry.security.brute-force.enabled=true
aussie.telemetry.security.brute-force.failure-threshold=5
aussie.telemetry.security.brute-force.window=PT5M

# =============================================================================
# Custom Metrics
# =============================================================================
aussie.telemetry.metrics.proxy-latency-buckets=5,10,25,50,100,250,500,1000,2500,5000,10000
aussie.telemetry.metrics.request-size-buckets=100,1000,10000,100000,1000000
aussie.telemetry.metrics.per-endpoint=false
aussie.telemetry.metrics.max-cardinality=1000
```

---

## See Also

- [Quarkus OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Quarkus Micrometer Guide](https://quarkus.io/guides/micrometer)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
