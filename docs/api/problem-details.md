# Problem Details Error Bodies

## Overview

All error responses returned by Aussie use [RFC 9457 Problem Details for HTTP
APIs](https://www.rfc-editor.org/rfc/rfc9457). Errors emitted from both the
JAX-RS path (REST endpoints, exception mappers) and the native Vert.x path
(WebSocket upgrades, proxy failures) share a single wire shape so clients can
parse them with one schema.

Content-Type for every error body is `application/problem+json`.

## Wire shape

```json
{
  "status": 429,
  "title": "Too Many Requests",
  "detail": "Rate limit exceeded. Retry after 30 seconds.",
  "retryAfter": 30,
  "limit": 100,
  "remaining": 0,
  "resetAt": 1700000000
}
```

### Fields

| Field     | Type    | Always present | Notes |
| --------- | ------- | -------------- | ----- |
| `status`  | number  | yes            | Matches the HTTP status code on the response. |
| `title`   | string  | yes            | Short, human-readable summary. Stable across patch releases. |
| `detail`  | string  | no             | Per-occurrence explanation. Omitted when null or empty. |
| extras    | varies  | no             | Status-specific extension members listed below. |

The standard RFC 9457 `type` and `instance` fields are not emitted by default.
If you need machine-readable error categorization beyond `title`, treat `title`
as the discriminator (it is part of the contract).

### Field order

Base fields appear in the order shown above (`status`, `title`, `detail`),
followed by extension members in caller-defined insertion order. Order is
stable across releases on both the JAX-RS and Vert.x paths.

## Extension members on `429 Too Many Requests`

When the rate limiter rejects a request, the body includes:

| Key          | Type   | Notes |
| ------------ | ------ | ----- |
| `retryAfter` | number | Seconds the client should wait. Mirrors the `Retry-After` response header. |
| `limit`      | number | Requests allowed in the current window. Mirrors `X-RateLimit-Limit`. |
| `remaining`  | number | Always `0` for a 429 response. |
| `resetAt`    | number | Unix epoch seconds when the limit resets. Mirrors `X-RateLimit-Reset`. |

Clients reading rate-limit state should prefer the JSON body when present
because it is consistent across all error paths; the headers are sent for
compatibility with HTTP-only clients.

## Producing problem responses from gateway code

REST handlers throw the relevant `HttpProblem` from `GatewayProblem`:

```java
throw GatewayProblem.serviceNotFound("user-service");
throw GatewayProblem.tooManyRequests("Throttled", 30L, 100L, 0L, resetAt);
```

Native Vert.x error paths use the injected `ProxyErrorWriter`:

```java
errorWriter.write(ctx, ProblemDetail.badGateway("Upstream unavailable"), serviceId);
errorWriter.writeRateLimit(ctx, problem, serviceId, retryAfter, limit, resetAt);
```

Both routes read the same `ProblemDetail` factories, so the wire body is the
same regardless of which adapter served the response.

## Custom extension members

Callers may attach additional fields via the `extras` map on `ProblemDetail`.
Keys reserved by RFC 9457 (`type`, `title`, `status`, `detail`, `instance`)
are rejected by the constructor to prevent silent collisions with the base
fields.

## Observability

Every problem response increments the `aussie.errors.total` counter tagged by
service ID and problem title; 5xx responses are also logged at WARN with the
status, title, and request URI. Dashboards can break errors down by title
without parsing log lines.
