# Observability runbooks

These runbooks are the owner-linked actions for the alerts in
`monitoring/prometheus/alerts`. The platform on-call owns first response;
service owners own upstream behavior after the gateway is healthy.

## Availability

1. Confirm `up{job="aussie"}` and readiness before treating the alert as an
   upstream incident.
2. Check `aussie:gateway:server_errors:rate5m` against dependency health,
   route convergence, and the upstream error panels.
3. If a dependency is failing, follow the dependency section below. If one
   service is isolated, pause its rollout or route change and contact its
   owner. Roll back only the last known-safe configuration generation.

## Latency

Check `aussie:gateway:latency:p99_5m`, HTTP pool saturation, DNS/TLS timing,
and upstream status by service. Apply the documented timeout/bulkhead policy;
do not increase queues or disable TLS to hide the alert.

## Dependency outage

Readiness is expected to go down when required Redis, Cassandra, signing-key,
or route state is unavailable. Keep liveness separate. Restore the dependency,
verify readiness and migration state, then allow traffic gradually. Do not
enable local security-state fallbacks in production.

## Rate-limit fallback or traffic spike

Inspect rate-limit fallback activations and rejection ratios. Preserve the
fail-closed authentication/admin policy, identify the bounded source of the
spike from access-controlled security events, and use edge controls for a
distributed attack. Never add client/IP/token labels to metrics.

## Key/JWKS and certificate expiry

For `SigningKeyUnavailable` or JWKS failures, verify key readiness, clock
health, issuer configuration, and the published key set. For upstream TLS
failures, verify certificate expiry and the configured trust policy. Rotate or
roll back through the documented key/certificate procedure; never disable
verification.

## Migration and rollback

Stop a failed rollout, preserve the migration lease evidence, and take a
snapshot before recovery. Prefer a forward-fix migration. Restore only after
the rollback rehearsal has been approved, then verify schema checksums,
readiness, and route/config generation convergence.

## Resource saturation and WebSocket drain

Check active connections, queue limits, Redis/Cassandra pools, file
descriptors, and WebSocket drain age. During deployment, mark the instance
unready, stop admissions, send the restart close code, and wait only for the
configured drain deadline. Investigate reconnect storms at the load balancer
before increasing per-instance limits.
