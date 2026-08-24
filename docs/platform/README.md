# Aussie Platform Guide

This guide is for platform teams deploying and operating the Aussie API Gateway.

## Table of Contents
- [Setup](#setup)
- [Authentication Configuration](#authentication-configuration)
- [Bootstrap Mode](#bootstrap-mode-first-time-setup)
- [Production Secrets Management](production-secrets.md)
- [Signing Keys and Token Profile](signing-keys.md)
- [IdP Integration (RBAC)](#idp-integration-rbac)
- [Group-Based Access Control](#group-based-access-control)
- [Access Control](#access-control)
- [Request Forwarding](#request-forwarding)
- [Request Size Limits](#request-size-limits)
- [Per-Route Authentication](#per-route-authentication)
- [WebSocket Configuration](websocket-configuration.md)
- [Token Revocation](token-revocation.md)
- [Token Translation](token-translation.md)
- [PKCE](pkce.md)
- [Session Management](#session-management)
- [Auth Rate Limiting (Brute Force Protection)](#auth-rate-limiting-brute-force-protection)
- [Local Caching](#local-caching)
- [Admin API](#admin-api)
- [Benchmarking](#benchmarking)
- [JMH Microbenchmarks](jmh-benchmarks.md)
- [End-to-End Test Suite](e2e-tests.md)
- [Service Permission Policies](#service-permission-policies)
- [Service Configuration Pub/Sub](#service-configuration-pubsub)
- [Environment Variables Reference](#environment-variables-reference)

## Setup

### Running in dev mode
```shell
make up
```
> Note: Quarkus is available at http://localhost:1234/

### Building
```shell
cd api
./gradlew build
```

### Running tests
```shell
cd api
./gradlew test
```

### Coverage reports
JaCoCo runs automatically with every `./gradlew test`. Reports are generated at `api/build/reports/jacoco/test/html/index.html`. To generate both API and CLI coverage:
```shell
make coverage
```

### Native executable
```shell
cd api
./gradlew build -Dquarkus.native.enabled=true
```

## Authentication Configuration

Aussie requires authentication for all admin endpoints (`/admin/*`). Gateway and pass-through routes remain open for public traffic.

### Configuration
Authentication is enabled by default. Configure it with environment variables:
```bash
# Enable/disable authentication (default: true)
export AUSSIE_AUTH_ENABLED=true

# Only require auth for admin paths (default: true)
export AUSSIE_AUTH_ADMIN_PATHS_ONLY=true

# DANGEROUS: Disable authentication entirely (NEVER use in production!)
# This is only for local development
export AUSSIE_AUTH_DANGEROUS_NOOP=false
```

> **Production safeguard:** If `AUSSIE_AUTH_DANGEROUS_NOOP=true` is set in production mode, the application will refuse to start with an `IllegalStateException`.

### Creating API Keys
Create an API key using the CLI:
```bash
# Create a key with full admin access
./aussie keys create --name my-team-key --description "API key for My Team" --ttl 90

# Create a key with specific permissions for service-level access
./aussie keys create --name service-admin --permissions "my-service.admin" --ttl 30

# Create a key with multiple permissions
./aussie keys create --name team-lead --permissions "*,my-service.lead" --ttl 90
```
**Output:**
```
API key created successfully!

Key ID:      ak_abc123
Name:        my-team-key
Permissions: *
Expires:     2025-06-06
Created By:  bootstrap

API Key (save this - it won't be shown again):
  aussie_xxxxxxxxxxxxxxxxxxxx
```
> Important: Save the API key immediately. It is only shown once and cannot be retrieved later.

### Managing API Keys
```bash
# List the first page of API keys
./aussie keys list

# Revoke a key
./aussie keys revoke ak_abc123
```

### Permissions
Permissions control what operations an API key can perform. They work at two levels:

**Aussie-level access** (gateway operations):
| Permission | Description |
|------------|-------------|
| `*` | Full admin access - can perform all gateway and service operations |
| `benchmark.run` | Run latency benchmarks through the gateway |

**Service-level access** (per-service operations):
Service-scoped permissions use a `<service-id>.<operation>` format. Aussie recognizes the following suffixes and automatically grants the corresponding un-scoped endpoint permission:

| Scoped Permission | Grants Endpoint Access |
|-------------------|----------------------|
| `<service-id>.config.read` | `service.config.read` |
| `<service-id>.config.create` | `service.config.create` |
| `<service-id>.config.update` | `service.config.update` |
| `<service-id>.config.delete` | `service.config.delete` |
| `<service-id>.permissions.read` | `service.permissions.read` |
| `<service-id>.permissions.write` | `service.permissions.write` |

Organizations can also define custom permissions mapped to operations via each service's permission policy. For example:
| Permission | Typical Usage |
|------------|---------------|
| `my-service.admin` | Full access to my-service configuration |
| `my-service.lead` | Can update my-service configuration |
| `my-service.readonly` | Can read my-service configuration |

See [Service Permission Policies](#service-permission-policies) for details on configuring service-level access.

## Bootstrap Mode (First-Time Setup)

When deploying Aussie for the first time, you need an admin API key to access the admin endpoints, but you can't create one without authentication - a classic chicken-and-egg problem. Bootstrap mode solves this.

### Enabling Bootstrap
Set the following environment variables before starting Aussie:
```bash
# Enable bootstrap mode
export AUSSIE_BOOTSTRAP_ENABLED=true

# Provide a secure bootstrap key (aussie_v1_ + 43 Base64URL characters)
export AUSSIE_BOOTSTRAP_KEY="aussie_v1_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"

# Optional: Set TTL (default: 24 hours, maximum: 24 hours)
export AUSSIE_BOOTSTRAP_TTL=PT24H

# Start the gateway
make up
```
On startup, Aussie will:
1. Check if bootstrap mode is enabled
2. Verify no admin keys already exist
3. Create a time-limited admin key using your provided bootstrap key
4. Log the key ID and expiration (never the key itself)

### Using the Bootstrap Key
Once created, add your bootstrap key to your configuration and create a permanent admin key:

**~/.aussierc:**
```toml
host = "http://localhost:1234"
api_key = "<the AUSSIE_BOOTSTRAP_KEY value>"
```

```bash
# Create a permanent admin key
./aussie keys create --name primary-admin --permissions "*" --ttl 365
```
Save the returned key securely - the bootstrap key will expire automatically within 24 hours. Update your `~/.aussierc` with the new permanent key.

### Recovery Mode
If you've lost all admin keys, you can use recovery mode to create a new bootstrap key even when admin keys exist:
```bash
# WARNING: Use only for emergency recovery
export AUSSIE_BOOTSTRAP_ENABLED=true
export AUSSIE_BOOTSTRAP_KEY="aussie_v1_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export AUSSIE_BOOTSTRAP_RECOVERY_MODE=true
```
Recovery mode is logged with a security warning - review your system if you didn't initiate this.

### Security Considerations
| Practice | Description |
|----------|-------------|
| **Use a strong key** | `aussie_v1_` plus 43 randomly generated Base64URL characters |
| **Short-lived keys** | Bootstrap keys expire in ≤24 hours by design |
| **Immediate rotation** | Create a permanent key and disable bootstrap immediately |
| **Audit logs** | All bootstrap operations are logged to `aussie.audit.bootstrap` |
| **Recovery mode caution** | Only use when absolutely necessary |

### Configuration Reference
| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (`aussie_v1_` plus 43 Base64URL characters) |
| `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

## IdP Integration (RBAC)

Aussie supports Role-Based Access Control (RBAC) through integration with your organization's Identity Provider (IdP). Instead of manually distributing API keys, developers can authenticate using their organization's SSO (SAML, OIDC, etc.) and receive short-lived tokens.

### Architecture Overview

```
┌──────────┐     ┌───────────────┐     ┌──────────┐     ┌──────────┐
│   CLI    │     │  Translation  │     │  Aussie  │     │ Backend  │
│          │     │    Layer      │     │ Gateway  │     │ Services │
└────┬─────┘     └──────┬────────┘     └────┬─────┘     └────┬─────┘
     │                  │                   │                │
     │ 1. aussie login  │                   │                │
     │─────────────────>│                   │                │
     │                  │ 2. Authenticate   │                │
     │                  │    with IdP       │                │
     │ 3. JWT Token     │                   │                │
     │<─────────────────│                   │                │
     │                  │                   │                │
     │ 4. API Request + JWT                 │                │
     │─────────────────────────────────────>│                │
     │                  │                   │ 5. Expand      │
     │                  │                   │    groups to   │
     │                  │                   │    permissions │
     │                  │                   │─────────────────>
     │ 6. Response      │                   │                │
     │<─────────────────────────────────────│                │
```

### Translation Layer

Platform teams must provide a **translation layer** between the CLI and their IdP. This layer:

1. Receives authentication requests from the Aussie CLI
2. Delegates to the organization's IdP (SAML, OIDC, etc.)
3. Maps IdP claims (roles, groups) to Aussie roles
4. Returns an Aussie-compatible JWT

#### Required Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/aussie/login` | GET | Initiate auth flow (redirect to IdP or return device code) |
| `/auth/aussie/login` | POST | Exchange IdP credentials for Aussie JWT |
| `/auth/aussie/logout` | POST | Invalidate session (optional) |
| `/auth/aussie/refresh` | POST | Refresh token (optional) |

#### Expected JWT Format

Your translation layer must return a JWT with this structure:

```json
{
  "sub": "user@example.com",
  "name": "User Name",
  "groups": ["demo-service.admin", "demo-service.dev"],
  "iat": 1702656000,
  "exp": 1702659600
}
```

### Authentication Flows

**Browser Flow (Default)**
1. CLI opens browser to `login_url?callback=http://127.0.0.1:PORT/callback`
2. Translation layer redirects to IdP
3. User authenticates with IdP
4. IdP redirects back to translation layer
5. Translation layer generates Aussie JWT
6. Redirects to CLI callback with `?token=JWT`

**Device Code Flow (Headless)**
1. CLI POSTs to `login_url?flow=device_code`
2. Translation layer returns device code and verification URL
3. User opens verification URL, enters code, authenticates
4. CLI polls for token
5. Translation layer returns Aussie JWT when auth completes

### Group Mapping Example

Map your IdP roles/groups to Aussie roles in your translation layer:

```javascript
// Example mapping in translation layer
const IDP_TO_AUSSIE_GROUPS = {
  'Engineering/Platform': ['platform-team'],
  'Engineering/Backend': ['demo-service.admin', 'demo-service.dev'],
  'Engineering/Frontend': ['demo-service.dev'],
  'QA': ['demo-service.dev', 'demo-service.readonly'],
};

function mapIdpGroupsToAussie(idpGroups) {
  return idpGroups
    .flatMap(g => IDP_TO_AUSSIE_GROUPS[g] || [])
    .filter((v, i, a) => a.indexOf(v) === i); // Dedupe
}
```

### Token TTL Configuration

Configure token TTL limits in `application.properties` or via environment variables:

```properties
# Maximum allowed TTL for JWT tokens (ISO-8601 duration)
aussie.auth.route-auth.jws.max-token-ttl=PT24H

# Default TTL for issued tokens
aussie.auth.route-auth.jws.token-ttl=PT5M
```

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_MAX_TOKEN_TTL` | `PT24H` | Maximum allowed JWT token TTL |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL` | `PT5M` | Default TTL for issued tokens |

### API Key Fallback

API key authentication can be enabled as a fallback for emergencies or teams without IdP integration:

```bash
# Enable API key authentication (disabled by default)
export AUSSIE_API_KEYS_ENABLED=true

# Maximum TTL for API keys
export AUSSIE_AUTH_API_KEYS_MAX_TTL=P365D
```

**Security Note**: API keys are disabled by default. Only enable for critical teams that need fallback authentication when the IdP is unavailable.

## Group-Based Access Control

Groups provide a mapping between organizational roles (from your IdP) and Aussie permissions.

### Defining Groups

Use the Admin API to define groups:

```bash
# Create a group
curl -X POST https://aussie.example.com/admin/groups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "demo-service.admin",
    "displayName": "Demo Service Admins",
    "permissions": ["demo-service.admin", "demo-service.lead", "demo-service.dev"]
  }'

# List groups
curl https://aussie.example.com/admin/groups \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Update a group
curl -X PUT https://aussie.example.com/admin/groups/demo-service.admin \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "permissions": ["demo-service.admin", "demo-service.lead", "demo-service.dev", "metrics.read"]
  }'

# Delete a group
curl -X DELETE https://aussie.example.com/admin/groups/demo-service.admin \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Example Groups

Groups follow a `<service-id>.<role>` naming convention. Here are example groups for a demo service:

| Group | Permissions | Description |
|-------|-------------|-------------|
| `platform-team` | `*` | Full platform admin access |
| `demo-service.admin` | `demo-service.admin`, `demo-service.lead`, `demo-service.dev` | Full service admin |
| `demo-service.lead` | `demo-service.lead`, `demo-service.dev` | Lead developer access |
| `demo-service.dev` | `demo-service.dev` | Basic developer access |
| `demo-service.readonly` | `demo-service.readonly` | Read-only access |

### How Group Expansion Works

When a user authenticates with a token containing groups, Aussie expands those groups to permissions:

```
Token claims:
  groups: ["demo-service.admin", "demo-service.dev"]

Group mappings (from database):
  demo-service.admin → ["service.config.update", "service.config.read", "service.config.delete", "service.permissions.write"]
  demo-service.dev   → ["service.config.update", "service.config.read"]

Effective permissions:
  ["service.config.update", "service.config.read", "service.config.delete", "service.permissions.write"]
```

Direct permissions in the token are merged with expanded group permissions.

Service-scoped permissions (e.g., `demo-service.config.update`) are also automatically expanded to include their un-scoped equivalents (e.g., `service.config.update`) for endpoint-level access checks. Service-level authorization still controls per-service access.

### Group Management CLI Commands

Groups can be managed via the CLI in addition to the Admin API:

#### Create a group
```bash
aussie roles create --id demo-service.admin \
  --display-name "Demo Service Admins" \
  --description "Full admin access for demo-service" \
  --permissions "service.config.update,service.config.read,service.config.delete,service.permissions.write"

# Create with minimal options
aussie roles create --id demo-service.dev --permissions "service.config.update,service.config.read"
```

| Flag | Short | Required | Description |
|------|-------|----------|-------------|
| `--id` | | Yes | Unique identifier for the group |
| `--display-name` | `-d` | No | Human-readable name (defaults to id) |
| `--description` | | No | Description of the group's purpose |
| `--permissions` | `-p` | No | Comma-separated list of permissions |

#### List groups
```bash
aussie roles list
```

#### Get a group
```bash
aussie roles get demo-service.admin
```

#### Update a group
```bash
# Update permissions
aussie roles update demo-service.admin --permissions "*" # DANGEROUS

# Update display name
aussie roles update demo-service.admin --display-name "Demo Service Administrators"
```

| Flag | Short | Description |
|------|-------|-------------|
| `--display-name` | `-d` | New display name |
| `--description` | | New description |
| `--permissions` | `-p` | New set of permissions (replaces existing) |

#### Delete a group
```bash
aussie roles delete demo-service.readonly
```

## Access Control

Private endpoints are protected by access control rules. Configure globally with environment variables:
```bash
# Allow specific IPs and CIDR ranges
export AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_IPS=10.0.0.0/8,192.168.0.0/16,127.0.0.1
```

The global list is a mandatory outer boundary. A service's `accessConfig.allowedIps`
must be contained by the global ranges and is intersected with them at request time.
Service registration rejects broader ranges. Host, `Forwarded: host=`, and
`X-Forwarded-Host` values never authorize a caller because they identify the requested
authority, not the caller's network identity.

### Trusted Proxy Configuration

By default, Aussie ignores forwarding headers (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`, `X-Forwarded-Proto`) and uses the direct socket peer and request scheme. Enable proxy trust only when requests arrive through known proxy IPs:

```bash
# Enable trusted proxy validation
export AUSSIE_GATEWAY_TRUSTED_PROXY_ENABLED=true

# List your load balancer / reverse proxy CIDR ranges
export AUSSIE_GATEWAY_TRUSTED_PROXY_PROXIES=10.0.0.0/8,192.168.0.0/16
```

Requests from IPs outside the trusted list have their forwarding headers ignored, and the socket-level IP address and direct request scheme are used instead. This is critical for IP-based access control and secure downstream redirects and cookies to work correctly.

For a trusted peer, Aussie caps forwarding headers at 8 KiB and 16 hops, validates
IP literals, and walks the chain from right to left. Trusted proxy hops are removed
until the rightmost untrusted address is found; attacker-controlled leftmost entries
therefore cannot override the effective client. `X-Real-IP` is accepted only as a
single-hop fallback when neither chain header is present. Malformed, obfuscated,
oversized, or overlong chains fall back to the direct socket peer. A trusted
`Forwarded: proto=` or `X-Forwarded-Proto` value is restricted to `http` or `https`
and trusted external host/port metadata is syntax-checked before Aussie rebuilds
forwarding headers. The same immutable request context carries the peer port, per-hop
trust decisions, bounded correlation ID, and identifiers attached only after successful
authentication.

**Important:** If you enable trusted proxy validation without configuring any proxy addresses, *all* forwarding headers will be rejected and the socket IP will always be used directly.

**How to determine your proxy CIDRs:**
- **Cloud load balancers:** Check your cloud provider's documentation for LB source IP ranges
- **Kubernetes:** Use the pod CIDR and service CIDR of your cluster
- **On-premise:** Use the CIDR of your reverse proxy / load balancer network segment

### Security Response Headers

Aussie adds OWASP-recommended security headers to all responses by default. These headers protect against common web vulnerabilities like clickjacking, MIME sniffing, and cross-site scripting.

Default headers (always set when enabled):
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Content-Security-Policy: default-src 'none'`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `X-Permitted-Cross-Domain-Policies: none`

Optional headers (set only when configured):
- `Strict-Transport-Security`: enable only when behind TLS termination. Incorrect HSTS can lock browsers out of your site.
- `Permissions-Policy`: restrict browser features (camera, microphone, etc.)

To customize global defaults:
```bash
# Disable security headers entirely
export AUSSIE_GATEWAY_SECURITY_HEADERS_ENABLED=false

# Or customize individual headers
export AUSSIE_GATEWAY_SECURITY_HEADERS_FRAME_OPTIONS=SAMEORIGIN
export AUSSIE_GATEWAY_SECURITY_HEADERS_CONTENT_SECURITY_POLICY="default-src 'self'"

# Enable HSTS (only when behind TLS termination)
export AUSSIE_GATEWAY_SECURITY_HEADERS_STRICT_TRANSPORT_SECURITY="max-age=31536000; includeSubDomains"
```

#### Per-Service Overrides

Services can override individual security headers via `securityHeadersConfig` in their registration. Fields that are not specified fall through to the global defaults. Setting a field to an empty string (`""`) suppresses that header entirely for the service.

Services can also declare arbitrary additional response headers via `customHeaders`:

```json
{
  "securityHeadersConfig": {
    "contentSecurityPolicy": "default-src 'self'; script-src 'self' 'unsafe-inline'",
    "frameOptions": "SAMEORIGIN",
    "customHeaders": {
      "X-Custom-Header": "value"
    }
  }
}
```

### SSRF Protection

Service registration validates `baseUrl` against an operator-owned host allowlist and SSRF address policy. Configure the hosts before registering services:

```bash
export AUSSIE_GATEWAY_SECURITY_ALLOWED_UPSTREAM_HOSTS=api.example.com,*.services.example.com
```

An absent or empty allowlist denies all upstream routing. Exact hosts and explicit subdomain patterns are supported; a global `*` is rejected. The following address categories are also blocked:

| Category | Examples | Reason |
|----------|----------|--------|
| Loopback | `127.x.x.x`, `::1`, `localhost` | Prevents access to gateway-local services |
| Link-local | `169.254.x.x` | Blocks cloud metadata endpoints (AWS, GCP, Azure) |
| Wildcard | `0.0.0.0`, `::` | Prevents binding to all interfaces |

Private network addresses (`10.x`, `172.16-31.x`, `192.168.x`) are denied by default even when their host is allowlisted. Internal deployments must explicitly set `AUSSIE_GATEWAY_SECURITY_ALLOW_PRIVATE_UPSTREAMS=true`.

Every connection resolves and validates the complete bounded A/AAAA answer set, then connects to that approved address while retaining the registered hostname for HTTP authority, TLS SNI, and certificate verification. Redirect following is disabled. Keep network-level egress policy as an independent second boundary.

HTTPS and WSS use the JVM trust store, hostname verification, TLS 1.2+, a bounded handshake timeout, and application-owned connection pools. Private CAs and mTLS use PEM files:

```properties
aussie.resiliency.http.tls.trust-certificates=/etc/aussie/tls/upstream-ca.pem
aussie.resiliency.http.tls.client-certificate=/etc/aussie/tls/client.pem
aussie.resiliency.http.tls.client-key=/etc/aussie/tls/client-key.pem
```

Setting `trust-certificates` replaces the JVM trust roots for outbound connections. The client certificate and key must be configured together. TLS material is loaded at startup; replace the files and restart instances safely to rotate it.

## Request Forwarding

By default, Aussie uses RFC 7239 `Forwarded` headers:
```
Forwarded: for=192.0.2.60;proto=https;host=api.example.com
```
Inbound forwarding headers are stripped. The outbound value is rebuilt from the
canonical client context and request metadata, including a validated external scheme
from a trusted TLS-terminating proxy, so an upstream never receives an
attacker-supplied chain.
To use legacy `X-Forwarded-*` headers instead, configure:
```bash
export AUSSIE_GATEWAY_FORWARDING_USE_RFC7239=false
```
This sends:
```
X-Forwarded-For: 192.0.2.60
X-Forwarded-Proto: https
X-Forwarded-Host: api.example.com
```

## Request Size Limits

Aussie enforces configurable request size limits:
```bash
# Maximum request body size (default: 10 MB)
export AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE=10485760

# Maximum single header size (default: 8 KB)
export AUSSIE_GATEWAY_LIMITS_MAX_HEADER_SIZE=8192

# Maximum total headers size (default: 32 KB)
export AUSSIE_GATEWAY_LIMITS_MAX_TOTAL_HEADERS_SIZE=32768
```

## Per-Route Authentication

Aussie supports per-route authentication for endpoints that require user identity. When enabled, Aussie validates incoming JWT tokens against configured identity providers and forwards authenticated requests with a signed Aussie token.

### Enabling Per-Route Authentication
Configure token providers and JWS signing with environment variables:
```bash
# Enable per-route authentication
export AUSSIE_AUTH_ROUTE_AUTH_ENABLED=true

# Configure an OIDC provider (e.g., Auth0, Okta, Keycloak)
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_ISSUER=https://example.auth0.com/
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_JWKS_URI=https://example.auth0.com/.well-known/jwks.json
export AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_MY_IDP_AUDIENCES=aussie-gateway

# Configure JWS token issuance for backends
export AUSSIE_AUTH_ROUTE_AUTH_JWS_ISSUER=aussie-gateway
export AUSSIE_AUTH_ROUTE_AUTH_JWS_KEY_ID=v1
export AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL=PT5M
export AUSSIE_AUTH_ROUTE_AUTH_JWS_FORWARDED_CLAIMS=sub,email,name,groups,roles

# RSA signing key (base64-encoded PKCS#8 PEM)
export AUSSIE_JWS_SIGNING_KEY=<base64-encoded-key>
```

### How It Works
1. Client sends request with `Authorization: Bearer <token>` header
2. Aussie validates the token against configured OIDC providers (JWKS signature, issuer, audience, expiration)
3. Aussie issues a new signed JWS token containing forwarded claims
4. Backend receives `Authorization: Bearer <aussie-token>` with validated identity

### Backend Integration
Backends only need to trust Aussie's signing key. The forwarded token includes:
| Claim | Description |
|-------|-------------|
| `iss` | Aussie's issuer (e.g., "aussie-gateway") |
| `sub` | Original token subject |
| `aud` | Audience claim (if configured per-endpoint or via default) |
| `original_iss` | Original token issuer |
| `iat`, `exp` | Issued/expiration times |
| Forwarded claims | Configurable (email, name, groups, roles, etc.) |

### Audience Configuration
Configure audience claims to prevent cross-service token replay attacks:
```bash
# Optional: Default audience for all endpoints without explicit audience
export AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE=aussie-gateway

# Optional: Require audience claim in all issued tokens
# When true and no audience is configured, the serviceId is used
export AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE=true
```

Services can also configure per-endpoint audiences in their registration. See the [Token Audience Validation](../api/token-audience.md) guide for details.

To verify tokens in your backend, configure your JWT library to trust Aussie's public key:
```bash
# Generate an RSA key pair
openssl genrsa -out aussie-private.pem 2048
openssl rsa -in aussie-private.pem -pubout -out aussie-public.pem

# Base64 encode for configuration
cat aussie-private.pem | base64 -w0 > aussie-private.b64
```

## Session Management

Aussie supports server-side sessions for maintaining authentication state across requests. Sessions are stored in Redis and identified by a cookie.

### Configuration

```properties
# Enable session validation and lifecycle operations (default: true)
aussie.session.enabled=true

# Legacy token-to-session endpoints remain disabled in normal mode
aussie.session.public-creation-enabled=false

# Session lifetime and idle timeout
aussie.session.ttl=PT8H
aussie.session.idle-timeout=PT30M
aussie.session.sliding-expiration=true

# Cookie settings
aussie.session.cookie.name=aussie_session
aussie.session.cookie.secure=true
aussie.session.cookie.http-only=true
aussie.session.cookie.same-site=Lax

# Session storage
aussie.session.storage.provider=redis
aussie.session.storage.redis.key-prefix=aussie:session:

# JWS token issuance from sessions
aussie.session.jws.enabled=true
aussie.session.jws.ttl=PT5M
aussie.session.jws.issuer=aussie-gateway
aussie.session.jws.include-claims=sub,email,name,roles
```

When `sliding-expiration` is enabled, the idle timeout resets on each request. Sessions are hard-capped by the earlier of `ttl` and the validated token's expiration regardless of activity.

`POST /auth/session` and `GET /auth/callback` create sessions only after the supplied token passes a configured route-auth validator. They remain enabled only by the `%dev` profile until the production OIDC transaction flow binds state, nonce, provider, and redirect URI atomically.

The session endpoint now accepts the validated token instead of caller-supplied identity data:

```http
POST /auth/session
Content-Type: application/json

{"token":"<signed-jwt>","redirectUrl":"/dashboard"}
```

The callback equivalent is `GET /auth/callback?token=<signed-jwt>&redirect=/dashboard`.

### Cookie Security

For production deployments behind TLS:
- `secure=true` ensures cookies are only sent over HTTPS
- `http-only=true` prevents JavaScript access
- `same-site=Lax` or `Strict` provides CSRF protection

Set `cookie.domain` if Aussie is accessed from multiple subdomains. For local development, set `cookie.secure=false` in the dev profile.

## Auth Rate Limiting (Brute Force Protection)

Aussie includes built-in brute force protection for authentication endpoints. Admission checks run before authentication, so only the canonical network identity can select the lockout bucket; unverified credential bytes are never used as identifiers.

### Configuration

```properties
# Enable auth rate limiting (default: true)
aussie.auth.rate-limit.enabled=true

# Lock out after 5 failures within 1 hour
aussie.auth.rate-limit.max-failed-attempts=5
aussie.auth.rate-limit.failed-attempt-window=PT1H
aussie.auth.rate-limit.lockout-duration=PT15M

# Track by canonical network identity
aussie.auth.rate-limit.track-by-ip=true

# Progressive lockout: each subsequent lockout is 1.5x longer
aussie.auth.rate-limit.progressive-lockout-multiplier=1.5
aussie.auth.rate-limit.max-lockout-duration=PT24H

# Include Retry-After header in 429 responses
aussie.auth.rate-limit.include-headers=true
```

### Behavior

When a client exceeds `max-failed-attempts` within `failed-attempt-window`, subsequent authentication attempts receive a 429 response with a `Retry-After` header. Each subsequent lockout is multiplied by `progressive-lockout-multiplier`, up to `max-lockout-duration`.

The pre-authentication lockout is always network-based. A future post-authentication quota may additionally use a verified principal or API-key ID, but it must not replace this network boundary.

## Local Caching

Aussie uses in-memory (Caffeine) caches for service route configurations, rate limit configurations, and sampling configurations. These caches reduce load on the backing stores (Cassandra/Redis).

### Configuration

```properties
# TTL for each cache type
aussie.cache.local.service-routes-ttl=PT30S
aussie.cache.local.rate-limit-config-ttl=PT30S
aussie.cache.local.sampling-config-ttl=PT30S

# Maximum entries across all local caches
aussie.cache.local.max-entries=10000

# Jitter factor to prevent thundering herd on TTL expiry
aussie.cache.local.jitter-factor=0.1
```

Service configuration pub/sub provides near-instant cache invalidation across instances. The TTL-based refresh serves as a fallback in case events are missed.

## Admin API

All admin endpoints require authentication. See [Authentication Configuration](#authentication-configuration) for details.

Paginated administrative list endpoints default to 50 results. `limit` must be between 1 and 100, and `offset` must be between 0 and 100,000. Stream-backed lockout and token-revocation lists cap `limit` at 100.

### Service Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/services` | GET | List registered services (`limit`, `offset`) |
| `/admin/services` | POST | Register a new service |
| `/admin/services/routing-status` | GET | Show local routing generation, durable generation, convergence lag, checksum, and last rejected generation |
| `/admin/services/{id}` | GET | Get a specific service |
| `/admin/services/{id}` | DELETE | Unregister a service |

**CLI equivalents:**
```bash
# List the first page of services
./aussie service list

# Register a service
./aussie service register -f my-service.json

# Preview a service
./aussie service preview <service-id>
```

### API Key Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/api-keys` | GET | List API keys (`limit`, `offset`; hashes redacted) |
| `/admin/api-keys` | POST | Create a new API key |
| `/admin/api-keys/{id}` | GET | Get a specific API key |
| `/admin/api-keys/{id}` | DELETE | Revoke an API key |

**CLI equivalents:**
```bash
# List the first page of keys
./aussie keys list --limit 50 --offset 0

# Create a key
./aussie keys create --name my-key --ttl 90

# Revoke a key
./aussie keys revoke <key-id>
```

### Translation Config Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/translation-config` | GET | List config versions (`limit`, `offset`) |
| `/admin/translation-config` | POST | Upload a new config version |
| `/admin/translation-config/active` | GET | Get the active config |
| `/admin/translation-config/{versionId}` | GET | Get a specific version |
| `/admin/translation-config/{versionId}/activate` | PUT | Activate a version |
| `/admin/translation-config/rollback/{versionNumber}` | POST | Rollback to version |
| `/admin/translation-config/{versionId}` | DELETE | Delete a version |
| `/admin/translation-config/validate` | POST | Validate config |
| `/admin/translation-config/test` | POST | Test translation |

**CLI equivalents:**
```bash
aussie translation-config upload config.json
aussie translation-config list
aussie translation-config get
aussie translation-config validate config.json
aussie translation-config test --claims '{"roles": ["admin"]}'
aussie translation-config activate <version-id>
aussie translation-config rollback <version-number>
aussie translation-config delete <version-id>
```

See [Token Translation](token-translation.md) for full documentation.

## Benchmarking

The `aussie benchmark` command runs authenticated latency benchmarks through the Aussie gateway. This is useful for measuring gateway overhead, validating performance SLAs, and identifying latency regressions.

For JVM-level microbenchmarks of hot-path domain logic (routing, rate limiting, caching, CIDR matching), see [JMH Microbenchmarks](jmh-benchmarks.md).

### Required Permission

Benchmarking requires the `benchmark.run` permission. This permission is restricted to platform teams to prevent unauthorized load testing against production infrastructure.

**Grant via API key:**
```bash
./aussie keys create --name benchmark-key --permissions "benchmark.run" --ttl 30
```

**Grant via role (for IdP users):**
```bash
aussie roles create --id platform-benchmarker \
  --display-name "Platform Benchmarker" \
  --permissions "benchmark.run"
```

Users with the wildcard permission (`*`) or admin role automatically have benchmark access.

### Usage

```bash
# Benchmark an endpoint with defaults (100 requests, 10ms interval)
aussie benchmark --url http://localhost:1234/my-service/api/health

# Custom number of requests and interval
aussie benchmark --url http://localhost:1234/my-service/api/health -n 500 --interval 5ms

# Output as JSON (for automation)
aussie benchmark --url http://localhost:1234/my-service/api/health -o json

# Use a different HTTP method
aussie benchmark --url http://localhost:1234/my-service/api/ping --method POST
```

### Options

| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--url` | | **(required)** | Target URL to benchmark |
| `--requests` | `-n` | `100` | Total number of requests to send |
| `--interval` | | `10ms` | Interval between starting new requests |
| `--method` | | `GET` | HTTP method to use |
| `--timeout` | | `30s` | Timeout for each request |
| `--output` | `-o` | `text` | Output format: `text` or `json` |

### How It Works

The benchmark uses **open-loop load testing** to avoid coordinated omission:

1. Authenticates using your existing session (`aussie login`) or API key
2. Verifies you have the `benchmark.run` permission
3. Sends requests at fixed intervals regardless of response time
4. Measures latency from request start to response completion
5. Reports statistics including percentiles and histogram

### Example Output

**Text format:**
```
Starting benchmark...
  Target:    http://localhost:1234/my-service/api/health
  Method:    GET
  Requests:  100
  Interval:  10ms
  Estimated: ~990ms

Results:
  Total:     100 requests
  Success:   100 (100.0%)
  Failed:    0 (0.0%)
  Duration:  1.023s

Latency:
  Min:       2.1ms
  Max:       45.3ms
  Mean:      8.7ms
  P50:       7.2ms
  P90:       15.4ms
  P95:       22.1ms
  P99:       38.6ms
```

**JSON format (for automation):**
```json
{
  "total_requests": 100,
  "successful": 100,
  "failed": 0,
  "duration_ms": 1023,
  "latency": {
    "min_ms": 2.1,
    "max_ms": 45.3,
    "mean_ms": 8.7,
    "p50_ms": 7.2,
    "p90_ms": 15.4,
    "p95_ms": 22.1,
    "p99_ms": 38.6
  }
}
```

### Permission Denied

If you don't have the `benchmark.run` permission, you'll see:

```
Error: permission denied: benchmark.run permission is required
Contact your platform team to request access
```

## Service Permission Policies

Service permission policies control which permissions are allowed to perform specific operations on a service. This enables fine-grained access control where different teams or roles can have different levels of access to each service's configuration.

### How It Works

1. **API keys have permissions** - When you create an API key with `--permissions "my-service.lead"`, that permission is available for authorization
2. **Services define permission policies** - Each service can specify which permissions are allowed for each operation
3. **Aussie checks authorization** - When a request comes in, Aussie checks if the API key's permissions match what the service allows

### Defining a Permission Policy

Include a `permissionPolicy` in your service configuration:

```json
{
  "serviceId": "my-service",
  "baseUrl": "http://my-service:3000",
  "permissionPolicy": {
    "permissions": {
      "service.config.read": {
        "anyOfPermissions": ["my-service.readonly", "my-service.lead", "my-service.admin"]
      },
      "service.config.update": {
        "anyOfPermissions": ["my-service.lead", "my-service.admin"]
      },
      "service.config.delete": {
        "anyOfPermissions": ["my-service.admin"]
      },
      "service.permissions.write": {
        "anyOfPermissions": ["my-service.admin"]
      }
    }
  }
}
```

### Available Operations

| Operation | Description |
|-----------|-------------|
| `service.config.read` | Read the service configuration |
| `service.config.write` | Update the service configuration |
| `service.config.delete` | Delete/unregister the service |
| `service.permissions.read` | Read the service's permission policy |
| `service.permissions.write` | Update the service's permission policy |

### Default Policy

Services without an explicit permission policy use the default policy. Config operations (`service.config.*`) accept either the `aussie:admin` permission or the corresponding un-scoped permission (e.g., `service.config.read`). Permission policy operations (`service.permissions.*`) require `aussie:admin`. This ensures new services are secure by default while allowing teams with config-level permissions to manage services.

### Example: Team-Based Access

```bash
# Create keys for different roles
./aussie keys create --name ops-admin --permissions "*" --ttl 365
./aussie keys create --name team-lead --permissions "my-service.lead,other-service.lead" --ttl 90
./aussie keys create --name developer --permissions "my-service.readonly" --ttl 30
```

With the permission policy above:
- `ops-admin` can do everything (wildcard grants `aussie:admin`)
- `team-lead` can read and update my-service config, but not delete it
- `developer` can only read my-service config

## Service Configuration Pub/Sub

In multi-instance deployments, service configuration changes (register, update, delete) are propagated to all instances via Redis pub/sub. This allows receiving instances to immediately refresh their local caches instead of waiting for TTL-based expiration.

### How It Works

When a service is registered, updated, or deleted on any instance, an event is published to a shared Redis channel. All other instances subscribed to that channel receive the event and update their local route caches accordingly. This provides near-instant cross-instance consistency.

TTL-based cache refresh (`aussie.cache.local.service-routes-ttl`) serves as a fallback: if Redis is temporarily unavailable or an event is missed, each instance will still refresh from persistent storage when its local cache expires. The two mechanisms are complementary.

### Configuration

Pub/sub is enabled by default. To disable it (e.g., for single-instance deployments):
```bash
export AUSSIE_SERVICE_PUBSUB_ENABLED=false
```

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_SERVICE_PUBSUB_ENABLED` | `true` | Enable pub/sub for service config events |
| `AUSSIE_SERVICE_PUBSUB_TOPIC` | `aussie:service:config:events` | Redis channel name for events |

### Behavior During Redis Outages

If the Redis connection is lost, event delivery stops but the gateway continues operating normally. The subscription retries with exponential backoff (1s to 30s). During the outage, instances rely on TTL-based cache refresh for eventual consistency. When Redis reconnects, pub/sub resumes automatically.

## Environment Variables Reference

### Authentication & Authorization

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_ENABLED` | `true` | Enable/disable authentication |
| `AUSSIE_AUTH_ADMIN_PATHS_ONLY` | `true` | Only require auth for admin paths |
| `AUSSIE_AUTH_DANGEROUS_NOOP` | `false` | Disable authentication (NEVER use in production) |
| `AUSSIE_API_KEYS_ENABLED` | `false` | Enable API key authentication (fallback) |
| `AUSSIE_AUTH_API_KEYS_MAX_TTL` | `P365D` | Maximum TTL for API keys |
| `AUSSIE_AUTH_ENCRYPTION_KEY` | - | Base64-encoded 256-bit AES key for encrypting API key records at rest |
| `AUSSIE_AUTH_ENCRYPTION_KEY_ID` | `v1` | Version identifier for the encryption key (update when rotating) |

### Token Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_ROUTE_AUTH_ENABLED` | `false` | Enable per-route authentication |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL` | `PT5M` | Default TTL for issued tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_MAX_TOKEN_TTL` | `PT24H` | Maximum allowed JWT token TTL |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_ISSUER` | `aussie-gateway` | Issuer claim for JWS tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_KEY_ID` | `v1` | Key ID for JWS token header |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_FORWARDED_CLAIMS` | `sub,email,name,groups,roles,effective_permissions` | Claims forwarded from upstream token |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE` | - | Default audience claim for issued tokens |
| `AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE` | `false` | Require audience claim in all tokens |
| `AUSSIE_JWS_SIGNING_KEY` | - | RSA signing key (base64-encoded PKCS#8 PEM) |

### Bootstrap Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_BOOTSTRAP_ENABLED` | `false` | Enable bootstrap mode |
| `AUSSIE_BOOTSTRAP_KEY` | - | Bootstrap key (`aussie_v1_` plus 43 Base64URL characters) |
| `AUSSIE_BOOTSTRAP_TTL` | `PT24H` | Bootstrap key TTL (max: 24h) |
| `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | `false` | Allow bootstrap with existing keys |

### Storage Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_STORAGE_REPOSITORY_PROVIDER` | - | Repository backend: `memory`, `cassandra` (auto-selected by priority if unset) |
| `AUSSIE_STORAGE_CACHE_ENABLED` | `false` | Enable caching layer for repository reads |
| `AUSSIE_STORAGE_CACHE_PROVIDER` | - | Cache backend: `redis` |
| `AUSSIE_STORAGE_CACHE_TTL` | `PT15M` | Cache entry TTL |
| `AUSSIE_STORAGE_CASSANDRA_CONTACT_POINTS` | `cassandra:9042` | Cassandra contact points |
| `AUSSIE_STORAGE_CASSANDRA_DATACENTER` | `datacenter1` | Cassandra datacenter name |
| `AUSSIE_STORAGE_CASSANDRA_KEYSPACE` | `aussie` | Cassandra keyspace |
| `AUSSIE_STORAGE_CASSANDRA_USERNAME` | - | Cassandra username |
| `AUSSIE_STORAGE_CASSANDRA_PASSWORD` | - | Cassandra password |
| `CASSANDRA_RUN_MIGRATIONS` | `false` | Run database migrations on startup |
| `AUSSIE_AUTH_STORAGE_PROVIDER` | - | Auth storage backend (falls back to `aussie.storage.*` settings) |
| `AUSSIE_AUTH_CACHE_ENABLED` | `false` | Enable caching for auth storage |
| `AUSSIE_AUTH_ROLES_STORAGE_PROVIDER` | - | Roles storage backend (falls back to auth storage settings) |
| `REDIS_HOSTS` | `redis://localhost:6379` | Redis connection string |

### Gateway Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_GATEWAY_FORWARDING_USE_RFC7239` | `true` | Use RFC 7239 Forwarded headers |
| `AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE` | `10485760` | Maximum request body size (bytes) |
| `AUSSIE_GATEWAY_LIMITS_MAX_HEADER_SIZE` | `8192` | Maximum single header size (bytes) |
| `AUSSIE_GATEWAY_LIMITS_MAX_TOTAL_HEADERS_SIZE` | `32768` | Maximum total headers size (bytes) |
| `AUSSIE_GATEWAY_CORS_ENABLED` | `true` | Enable CORS support |
| `AUSSIE_GATEWAY_CORS_ALLOWED_ORIGINS` | `*` | Allowed CORS origins (comma-separated) |
| `AUSSIE_GATEWAY_CORS_ALLOWED_METHODS` | `GET,POST,PUT,DELETE,PATCH,OPTIONS,HEAD` | Allowed HTTP methods |
| `AUSSIE_GATEWAY_CORS_ALLOWED_HEADERS` | `Content-Type,Authorization,X-Requested-With,Accept,Origin` | Allowed request headers |
| `AUSSIE_GATEWAY_CORS_EXPOSED_HEADERS` | - | Response headers exposed to the client |
| `AUSSIE_GATEWAY_CORS_ALLOW_CREDENTIALS` | `true` | Allow credentials in CORS requests |
| `AUSSIE_GATEWAY_CORS_MAX_AGE` | `3600` | Preflight cache duration in seconds |
| `AUSSIE_GATEWAY_TRUSTED_PROXY_ENABLED` | `false` | Enable trusted proxy validation for forwarding headers |
| `AUSSIE_GATEWAY_TRUSTED_PROXY_PROXIES` | - | Trusted proxy IPs/CIDRs (comma-separated) |
| `AUSSIE_GATEWAY_SECURITY_PUBLIC_DEFAULT_VISIBILITY_ENABLED` | `false` | Allow services to set PUBLIC as default endpoint visibility |
| `AUSSIE_GATEWAY_SECURITY_ALLOWED_UPSTREAM_HOSTS` | - | Required exact hosts or explicit subdomain patterns; empty denies all upstreams |
| `AUSSIE_GATEWAY_SECURITY_ALLOW_PRIVATE_UPSTREAMS` | `false` | Allow allowlisted upstream hosts to resolve to private (site-local) addresses |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_ENABLED` | `true` | Enable security response headers |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_CONTENT_TYPE_OPTIONS` | `nosniff` | X-Content-Type-Options header value |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_FRAME_OPTIONS` | `DENY` | X-Frame-Options header value |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_CONTENT_SECURITY_POLICY` | `default-src 'none'` | CSP header value |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_REFERRER_POLICY` | `strict-origin-when-cross-origin` | Referrer-Policy header value |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_PERMITTED_CROSS_DOMAIN_POLICIES` | `none` | X-Permitted-Cross-Domain-Policies header value |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_STRICT_TRANSPORT_SECURITY` | - | HSTS header (enable when behind TLS) |
| `AUSSIE_GATEWAY_SECURITY_HEADERS_PERMISSIONS_POLICY` | - | Permissions-Policy header value |

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_RATE_LIMITING_ENABLED` | `true` | Enable rate limiting |
| `AUSSIE_RATE_LIMITING_ALGORITHM` | `BUCKET` | Algorithm: `BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW` |
| `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW` | `Long.MAX_VALUE` | Maximum rate limit ceiling for service configs |
| `AUSSIE_RATE_LIMITING_PLATFORM_MAX_WINDOW_SECONDS` | `Long.MAX_VALUE` | Maximum window duration for service configs |
| `AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW` | `100` | Default requests per window |
| `AUSSIE_RATE_LIMITING_WINDOW_SECONDS` | `60` | Window duration in seconds |
| `AUSSIE_RATE_LIMITING_BURST_CAPACITY` | `100` | Burst capacity (bucket algorithm) |
| `AUSSIE_RATE_LIMITING_INCLUDE_HEADERS` | `true` | Include rate limit headers in responses |
| `AUSSIE_RATE_LIMITING_REDIS_ENABLED` | `false` | Use Redis for distributed rate limiting |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_ENABLED` | `true` | Enable WebSocket connection rate limiting |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_REQUESTS_PER_WINDOW` | `10` | WebSocket connections per window |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_WINDOW_SECONDS` | `60` | WebSocket connection window duration |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_BURST_CAPACITY` | `5` | WebSocket connection burst capacity |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_ENABLED` | `true` | Enable WebSocket message rate limiting |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_REQUESTS_PER_WINDOW` | `100` | WebSocket messages per window |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_WINDOW_SECONDS` | `1` | WebSocket message window duration |
| `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_BURST_CAPACITY` | `50` | WebSocket message burst capacity |

### Token Revocation

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_REVOCATION_ENABLED` | `true` | Enable token revocation checks |
| `AUSSIE_AUTH_REVOCATION_CHECK_USER_REVOCATION` | `true` | Enable user-level revocation |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_ENABLED` | `true` | Enable bloom filter optimization |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS` | `100000` | Expected number of revoked tokens |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_FALSE_POSITIVE_PROBABILITY` | `0.001` | Bloom filter false positive rate |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_REBUILD_INTERVAL` | `PT1H` | Bloom filter rebuild interval |
| `AUSSIE_AUTH_REVOCATION_CACHE_ENABLED` | `true` | Enable local revocation cache |
| `AUSSIE_AUTH_REVOCATION_CACHE_MAX_SIZE` | `10000` | Maximum cache entries |
| `AUSSIE_AUTH_REVOCATION_CACHE_TTL` | `PT5M` | Cache entry TTL |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_ENABLED` | `true` | Enable pub/sub for multi-instance sync |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_CHANNEL` | `aussie:revocation:events` | Redis pub/sub channel name |

See [Token Revocation](token-revocation.md) for implementation details.

### Service Configuration Pub/Sub

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_SERVICE_PUBSUB_ENABLED` | `true` | Enable pub/sub for service configuration events |
| `AUSSIE_SERVICE_PUBSUB_TOPIC` | `aussie:service:config:events` | Topic name for events, mapped to transport-specific destination |

### PKCE Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_PKCE_ENABLED` | `true` | Enable PKCE support |
| `AUSSIE_AUTH_PKCE_REQUIRED` | `true` | Require PKCE for authorization requests |
| `AUSSIE_AUTH_PKCE_CHALLENGE_TTL` | `PT10M` | Challenge TTL (time-to-live) |
| `AUSSIE_AUTH_PKCE_STORAGE_PROVIDER` | `redis` | Storage provider: redis, memory |
| `AUSSIE_AUTH_PKCE_STORAGE_REDIS_KEY_PREFIX` | `aussie:pkce:` | Redis key prefix |

See [PKCE](pkce.md) for implementation details.

### Resiliency

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_RESILIENCY_HTTP_CONNECT_TIMEOUT` | `PT5S` | Maximum time to establish connection to upstream service |
| `AUSSIE_RESILIENCY_HTTP_REQUEST_TIMEOUT` | `PT30S` | Maximum time to wait for response from upstream (returns 504 if exceeded) |
| `AUSSIE_RESILIENCY_HTTP_MAX_REQUEST_TIMEOUT` | `PT5M` | Maximum timeout services may configure (ceiling for service/endpoint overrides) |
| `AUSSIE_RESILIENCY_JWKS_FETCH_TIMEOUT` | `PT5S` | Maximum time to fetch JWKS from identity provider |
| `AUSSIE_RESILIENCY_JWKS_MAX_CACHE_ENTRIES` | `100` | Maximum number of JWKS entries to cache (LRU eviction) |
| `AUSSIE_RESILIENCY_JWKS_CACHE_TTL` | `PT1H` | Time-to-live for cached JWKS entries |
| `AUSSIE_RESILIENCY_CASSANDRA_QUERY_TIMEOUT` | `PT5S` | Maximum time to wait for Cassandra queries |
| `AUSSIE_RESILIENCY_CASSANDRA_POOL_LOCAL_SIZE` | `30` | Connections per node in local datacenter |
| `AUSSIE_RESILIENCY_CASSANDRA_MAX_REQUESTS_PER_CONNECTION` | `1024` | Maximum concurrent requests per Cassandra connection |
| `AUSSIE_RESILIENCY_REDIS_OPERATION_TIMEOUT` | `PT1S` | Maximum time to wait for Redis operations |
| `AUSSIE_RESILIENCY_REDIS_POOL_SIZE` | `30` | Maximum Redis connections in pool |
| `AUSSIE_RESILIENCY_REDIS_POOL_WAITING` | `100` | Maximum requests waiting when Redis pool exhausted |
| `AUSSIE_RESILIENCY_HTTP_MAX_CONNECTIONS_PER_HOST` | `50` | Maximum HTTP connections per upstream host |
| `AUSSIE_RESILIENCY_HTTP_MAX_CONNECTIONS` | `200` | Maximum total HTTP connections across all hosts |
| `AUSSIE_RESILIENCY_JWKS_MAX_CONNECTIONS` | `10` | Maximum concurrent JWKS fetch connections |

**Timeout Behavior by Operation:**
- **HTTP Proxy**: Returns 504 Gateway Timeout if upstream doesn't respond
- **JWKS Fetch**: Falls back to cached keys if available on timeout
- **Session Operations**: Propagate error (critical operations)
- **Cache Reads**: Treat timeout as cache miss
- **Rate Limiting**: Fail-open (allow request) on timeout
- **Token Revocation**: Fail-closed (deny request) on timeout for security

**Bulkhead Health Check:**

The `/q/health/ready` endpoint includes a "bulkheads" health check that reports configured pool limits:
- `aussie.bulkhead.cassandra.pool.max` - Cassandra connections per node
- `aussie.bulkhead.redis.pool.max` - Redis pool size
- `aussie.bulkhead.http.pool.max.per_host` - HTTP connections per upstream host
- `aussie.bulkhead.jwks.pool.max` - JWKS fetch connections

This health check always reports UP since configuration is validated at startup. Pool exhaustion should be monitored via metrics and alerts, not health checks.

### Telemetry

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_TELEMETRY_ENABLED` | `false` | Master toggle for telemetry |
| `AUSSIE_TELEMETRY_TRACING_ENABLED` | `false` | Enable distributed tracing |
| `AUSSIE_TELEMETRY_TRACING_SAMPLING_RATE` | `1.0` | Default trace sampling rate (0.0-1.0) |
| `AUSSIE_TELEMETRY_METRICS_ENABLED` | `false` | Enable metrics collection |
| `AUSSIE_TELEMETRY_SECURITY_ENABLED` | `false` | Enable security monitoring |
| `AUSSIE_TELEMETRY_SECURITY_RATE_LIMIT_WINDOW` | `PT1M` | Security event rate limit window |
| `AUSSIE_TELEMETRY_SECURITY_RATE_LIMIT_THRESHOLD` | `1000` | Security event rate limit threshold |
| `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_ENABLED` | `true` | Enable DoS detection |
| `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_SPIKE_THRESHOLD` | `5.0` | Spike multiplier for DoS detection |
| `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_ERROR_RATE_THRESHOLD` | `0.5` | Error rate threshold for DoS detection |
| `AUSSIE_TELEMETRY_ATTRIBUTION_ENABLED` | `false` | Enable traffic attribution |
| `AUSSIE_TELEMETRY_ATTRIBUTION_TENANT_HEADER` | `X-Tenant-ID` | Header for tenant identification |
| `AUSSIE_TELEMETRY_ATTRIBUTION_CLIENT_APP_HEADER` | `X-Client-Application` | Header for client app identification |
| `AUSSIE_TELEMETRY_SAMPLING_ENABLED` | `false` | Enable hierarchical trace sampling |
| `AUSSIE_TELEMETRY_SAMPLING_DEFAULT_RATE` | `1.0` | Default sampling rate |
| `AUSSIE_TELEMETRY_SAMPLING_MINIMUM_RATE` | `0.0` | Minimum sampling rate (floor) |
| `AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE` | `1.0` | Maximum sampling rate (ceiling) |
| `AUSSIE_TELEMETRY_SAMPLING_CACHE_REDIS_ENABLED` | `true` | Enable Redis cache for sampling configs |
| `AUSSIE_TELEMETRY_SAMPLING_CACHE_REDIS_TTL` | `PT5M` | Sampling config Redis cache TTL |
| `AUSSIE_TELEMETRY_SAMPLING_LOOKUP_TIMEOUT` | `PT5S` | Sampling config lookup timeout |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry exporter endpoint |

### Session Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_SESSION_ENABLED` | `true` | Enable session management |
| `AUSSIE_SESSION_TTL` | `PT8H` | Maximum session lifetime |
| `AUSSIE_SESSION_IDLE_TIMEOUT` | `PT30M` | Close session after this idle duration |
| `AUSSIE_SESSION_SLIDING_EXPIRATION` | `true` | Reset idle timeout on each request |
| `AUSSIE_SESSION_ID_GENERATION_MAX_RETRIES` | `3` | Max retries for unique session ID generation |
| `AUSSIE_SESSION_COOKIE_NAME` | `aussie_session` | Session cookie name |
| `AUSSIE_SESSION_COOKIE_PATH` | `/` | Session cookie path |
| `AUSSIE_SESSION_COOKIE_DOMAIN` | - | Session cookie domain |
| `AUSSIE_SESSION_COOKIE_SECURE` | `true` | Require HTTPS for session cookies |
| `AUSSIE_SESSION_COOKIE_HTTP_ONLY` | `true` | Prevent JavaScript access to session cookies |
| `AUSSIE_SESSION_COOKIE_SAME_SITE` | `Lax` | SameSite attribute: `Strict`, `Lax`, `None` |
| `AUSSIE_SESSION_STORAGE_PROVIDER` | `redis` | Session storage backend: `redis` |
| `AUSSIE_SESSION_STORAGE_REDIS_KEY_PREFIX` | `aussie:session:` | Redis key prefix for sessions |
| `AUSSIE_SESSION_JWS_ENABLED` | `true` | Issue JWS tokens for session authentication |
| `AUSSIE_SESSION_JWS_TTL` | `PT5M` | JWS token TTL |
| `AUSSIE_SESSION_JWS_ISSUER` | `aussie-gateway` | JWS token issuer claim |
| `AUSSIE_SESSION_JWS_AUDIENCE` | - | JWS token audience claim |
| `AUSSIE_SESSION_JWS_INCLUDE_CLAIMS` | `sub,email,name,roles` | Claims included in session JWS tokens |

### Auth Rate Limiting (Brute Force Protection)

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_RATE_LIMIT_ENABLED` | `true` | Enable authentication rate limiting |
| `AUSSIE_AUTH_RATE_LIMIT_MAX_FAILED_ATTEMPTS` | `5` | Max failed attempts before lockout |
| `AUSSIE_AUTH_RATE_LIMIT_LOCKOUT_DURATION` | `PT15M` | Initial lockout duration |
| `AUSSIE_AUTH_RATE_LIMIT_FAILED_ATTEMPT_WINDOW` | `PT1H` | Window for tracking failed attempts |
| `AUSSIE_AUTH_RATE_LIMIT_TRACK_BY_IP` | `true` | Track failed attempts by IP address |
| `AUSSIE_AUTH_RATE_LIMIT_TRACK_BY_IDENTIFIER` | `true` | Reserved for post-authentication verified identifiers; never reads raw credential bytes |
| `AUSSIE_AUTH_RATE_LIMIT_PROGRESSIVE_LOCKOUT_MULTIPLIER` | `1.5` | Multiplier for progressive lockout duration |
| `AUSSIE_AUTH_RATE_LIMIT_MAX_LOCKOUT_DURATION` | `PT24H` | Maximum lockout duration |
| `AUSSIE_AUTH_RATE_LIMIT_INCLUDE_HEADERS` | `true` | Include rate limit headers in auth error responses |

### Local Cache

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_CACHE_LOCAL_SERVICE_ROUTES_TTL` | `PT30S` | TTL for cached service route configurations |
| `AUSSIE_CACHE_LOCAL_RATE_LIMIT_CONFIG_TTL` | `PT30S` | TTL for cached rate limit configurations |
| `AUSSIE_CACHE_LOCAL_SAMPLING_CONFIG_TTL` | `PT30S` | TTL for cached sampling configurations |
| `AUSSIE_CACHE_LOCAL_MAX_ENTRIES` | `10000` | Maximum entries in local cache |
| `AUSSIE_CACHE_LOCAL_JITTER_FACTOR` | `0.1` | Random jitter factor for cache TTL to avoid thundering herd |

### Key Rotation

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_KEY_ROTATION_ENABLED` | `false` | Enable automated signing key rotation |
| `AUSSIE_AUTH_KEY_ROTATION_SCHEDULE` | `0 0 0 1 */3 ?` | Cron schedule for key rotation (default: quarterly) |
| `AUSSIE_AUTH_KEY_ROTATION_GRACE_PERIOD` | `PT24H` | Grace period after rotation during which old key is still accepted |
| `AUSSIE_AUTH_KEY_ROTATION_DEPRECATION_PERIOD` | `P7D` | Period before old key stops being used for signing |
| `AUSSIE_AUTH_KEY_ROTATION_RETENTION_PERIOD` | `P30D` | Period before old key is deleted |
| `AUSSIE_AUTH_KEY_ROTATION_KEY_SIZE` | `2048` | RSA key size in bits |
| `AUSSIE_AUTH_KEY_ROTATION_CACHE_REFRESH_INTERVAL` | `PT5M` | Interval to refresh key cache |
| `AUSSIE_AUTH_KEY_ROTATION_CLEANUP_INTERVAL` | `PT1H` | Interval to clean up expired keys |
| `AUSSIE_AUTH_KEY_ROTATION_STORAGE` | `config` | Key storage backend: `config`, `vault` |

### Translation Config Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_TRANSLATION_CONFIG_STORAGE_PROVIDER` | - | Translation config storage backend: `memory`, `cassandra` |
| `AUSSIE_TRANSLATION_CONFIG_CACHE_ENABLED` | `true` | Enable caching for translation configs |
| `AUSSIE_TRANSLATION_CONFIG_CACHE_PROVIDER` | - | Translation config cache backend: `redis` |
| `AUSSIE_TRANSLATION_CONFIG_CACHE_MEMORY_TTL` | `PT5M` | In-memory cache TTL |
| `AUSSIE_TRANSLATION_CONFIG_CACHE_MEMORY_MAX_SIZE` | `100` | Maximum in-memory cache entries |
